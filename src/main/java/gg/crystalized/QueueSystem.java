package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public class QueueSystem {
    private Velocity_plugin velocity;
    public static ProxyServer server;
    public static List<GameQueue> queues = new ArrayList<>();

    public enum ServerStatus{
        online_free,
        online_playing,
        offline,
    }
    public enum queueTypes{
        litestrike,
        litestrike_ranked,
        knockoff,
        crystalblitz,
        crystalblitz_duos,
    }

    public QueueSystem(ProxyServer server, Velocity_plugin plugin) {
        this.velocity = plugin;
        this.server = server;

        //NOTE: These need to be unique, no duplicates otherwise we may have issues with for loops and/or commands iterating through the queues list
        queues.add(new GameQueue(server, plugin, queueTypes.litestrike, 6, 10, translatable("crystalized.game.litestrike.name").color(NamedTextColor.GREEN)));
        //queues.add(new GameQueue(server, plugin, queueTypes.litestrike_ranked, 6, 8, text("Litestrike Ranked").color(NamedTextColor.GREEN)));
        queues.add(new GameQueue(server, plugin, queueTypes.knockoff, 3, 12, translatable("crystalized.game.knockoff.name").color(NamedTextColor.GOLD)));
        queues.add(new GameQueue(server, plugin, queueTypes.crystalblitz, 3, 8, translatable("crystalized.game.crystalblitz.name").color(NamedTextColor.LIGHT_PURPLE)));
        //queues.add(new GameQueue(server, plugin, queueTypes.crystalblitz_duos, 4, 16, text("Crystal Blitz duos").color(NamedTextColor.LIGHT_PURPLE)));

        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMetaQueue = commandManager.metaBuilder("queue").plugin(plugin).build();
        commandManager.register(commandMetaQueue, QueueCommand.createBrigadierCommand(server));

        CommandMeta commandMetaUnqueue = commandManager.metaBuilder("unqueue").plugin(plugin).build();
        commandManager.register(commandMetaUnqueue, QueueCommand.createUnQueueCommand(server));

        CommandMeta commandMetaRejoin = commandManager.metaBuilder("rejoin").plugin(plugin).build();
        commandManager.register(commandMetaRejoin, QueueCommand.createRejoinCommand(server));

    }

    public static GameQueue getQueue(queueTypes type) {
        for (GameQueue q : queues) {
            if (q.type.equals(type)) {
                return q;
            }
        }
        return null;
    }

    public static void removeFromAllQueues(Player p) {
        for (GameQueue q : queues) {
            q.removePlayerToQueue(p);
        }
    }

    @Subscribe
    public void onPluginMessageFromBackend(PluginMessageEvent event) {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String message = in.readUTF();
        //Velocity_plugin.logger.info("queue message");
        if (message.contains("start_game")) {
            for (GameQueue q : queues) {
                for (GameServer s : q.servers) {
                    if (s.server.equals(backend.getServer())) {
                        s.isGoing = true;
                        for (Player p : s.server.getPlayersConnected()) {
                            QueueSystem.removeFromAllQueues(p);
                        }
                    }
                }
            }
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent e) {
        Player p = e.getPlayer();
        QueueSystem.removeFromAllQueues(p);
    }
}

class GameQueue{
    List<Player> players = new ArrayList<>();
    List<GameServer> servers = new ArrayList<>();
    private Velocity_plugin plugin;
    ProxyServer proxyServer;
    public QueueSystem.queueTypes type;
    boolean queueTimerStarted = false;

    public Component name;
    int needed;
    int max;

    public GameQueue(ProxyServer proxyServer, Velocity_plugin plugin, QueueSystem.queueTypes type, int playersNeededToStart, int playersMaxLimit,  Component visualName) {
        players.clear();
        this.type = type;
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.needed = playersNeededToStart;
        this.max = playersMaxLimit;
        this.name = visualName;
        for (RegisteredServer rs : proxyServer.getAllServers()) {
            if (rs.getServerInfo().getName().startsWith(type.toString())) {
                servers.add(new GameServer(rs, type));
            }
        }
        Velocity_plugin.logger.info("[QueueSystem] Registered \"" + type + "\" queue with " + servers.size() + " server(s).");

        AtomicReference<AtomicInteger> timer = new AtomicReference<>(new AtomicInteger(7)); //werid shit
        proxyServer.getScheduler().buildTask(plugin, () -> {
            for (GameServer s : servers) {
                s.updateServerStatus();
            }

            //Queue timer
            if ((players.size() == needed || players.size() > needed) && !queueTimerStarted) {
                queueTimerStarted = true;
                timer.set(new AtomicInteger(7));
            } else if ((players.size() < needed) && queueTimerStarted) {
                queueTimerStarted = false;
                for (Player p : players) {
                    p.sendMessage(text("Game cancelled, not enough players!"));
                }
            } else if (queueTimerStarted) {
                //timer.getAndDecrement();
                timer.set(new AtomicInteger(timer.get().get() - 1));
                switch (timer.get().get()) {
                    case 3,2,1 -> {
                        for (Player p : players) {
                            //TODO I would play a sound here but velocity doesn't support playing sounds for some reason - Callum
                        }
                    }
                    case 0 -> {
                        sendAllPlayersToServer();
                    }
                }
                for (Player p : players) {
                    p.sendActionBar(text("Queuing for ").append(name).append(text(" (" + players.size() + "/" + needed + "), Teleporting in: " + timer)));
                }
                if (timer.get().get() == 0) {
                    sendAllPlayersToServer();
                    timer.set(new AtomicInteger(7));
                }
            } else {
                timer.set(new AtomicInteger(7));
                for (Player p : players) {
                    List<Component> translatableList = new ArrayList<>();
                    translatableList.add(name);
                    translatableList.add(text(players.size()));
                    translatableList.add(text(needed));
                    p.sendActionBar(translatable("crystalized.generic.queue.actionbar", translatableList));
                }
            }


        }).repeat(1, TimeUnit.SECONDS).schedule();
    }

    public void addPlayerToQueue(Player p) {
        if (players.size() == max) {
            p.sendMessage(text("The ").append(name).append(text(" queue is currently full! Please try queueing again in a short bit.")));
        } else {
            for (GameServer s : servers) {
                if (s.available.equals(QueueSystem.ServerStatus.online_free)) {
                    players.add(p);
                    p.sendMessage(text("You are queued for ").append(name));
                    return;
                }
            }

            p.sendMessage(text("No servers are currently available for ").color(NamedTextColor.RED).append(name).append(text(". Please try again later.").color(NamedTextColor.RED)));
        }
    }

    public void removePlayerToQueue(Player p) {
        if (players.contains(p)) {
            players.remove(p);
            p.sendMessage(text("You left the queue for ").append(name)); //TODO make translatable
            p.sendActionBar(text("")); //To instantly remove the actionbar instead of minecraft fading the text away
        }
    }

    public void sendAllPlayersToServer() {
        List<GameServer> templist = new ArrayList<>(servers);
        Collections.shuffle(templist);
        List<Player> playerList = new ArrayList<>(players); //copying here to prevent a ConcurrentModificationException
        players.clear();

        for (GameServer s : templist) {
            if (s.available.equals(QueueSystem.ServerStatus.online_free)) {
                s.playersInGame = playerList;
                for (Player p : playerList) {
                    p.createConnectionRequest(s.server).connect();
                }
                return;
            }
        }

        for (Player p : playerList) {
            p.sendMessage(text("No servers are currently available for ").color(NamedTextColor.RED).append(name).append(text(". Please try again later.").color(NamedTextColor.RED)));
        }
    }
}

class GameServer{
    RegisteredServer server;
    QueueSystem.queueTypes type;
    QueueSystem.ServerStatus available;
    boolean isGoing = false;
    public List<Player> playersInGame = new ArrayList<>(); //for rejoining

    public GameServer(RegisteredServer server, QueueSystem.queueTypes type) {
        this.type = type;
        this.server = server;

    }

    public void updateServerStatus() {
        QueueSystem.ServerStatus status = available;
        try {
            server.ping().get(3, TimeUnit.SECONDS); //to check if its online, otherwise exception is thrown (?)
            if (server.getPlayersConnected().isEmpty() && isGoing) {
                available = QueueSystem.ServerStatus.online_free;
                isGoing = false;
                playersInGame.clear();
            } else if (isGoing) {
                available = QueueSystem.ServerStatus.online_playing;
            } else {
                available = QueueSystem.ServerStatus.online_free;
            }
        } catch (Exception e) {
            available = QueueSystem.ServerStatus.offline;
        }
        if (status != available && status != null) { //last check for plugin startup
            Velocity_plugin.logger.info("[QueueSystem] " + server.getServerInfo().getName() + " has changed availability from " + status + " to " + available);
        }
    }
}

class QueueCommand{
    //this is a mess
    public static BrigadierCommand createBrigadierCommand(final ProxyServer proxy) {
        LiteralCommandNode<CommandSource> commandNode = BrigadierCommand.literalArgumentBuilder("queue")
                .then(BrigadierCommand.literalArgumentBuilder("enter")
                        .then(BrigadierCommand.requiredArgumentBuilder("argument", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    QueueSystem.queues.forEach(GameQueue -> builder.suggest(GameQueue.type.toString()));
                                    return builder.buildFuture();
                                }).executes(ctx -> {
                                    String argumentProvided = ctx.getArgument("argument", String.class);
                                    try {
                                        QueueSystem.removeFromAllQueues((Player) ctx.getSource());
                                        GameQueue q = QueueSystem.getQueue(QueueSystem.queueTypes.valueOf(argumentProvided));
                                        q.addPlayerToQueue((Player) ctx.getSource());
                                    } catch (Exception ex) {
                                        ctx.getSource().sendRichMessage("[QueueSystem] <red>Cannot add to queue, exception occurred.");
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .build();

        return new BrigadierCommand(commandNode);
    }

    public static BrigadierCommand createUnQueueCommand(final ProxyServer proxy) {
        LiteralCommandNode<CommandSource> commandNode = BrigadierCommand.literalArgumentBuilder("unqueue").executes(ctx -> {
                    if (ctx.getSource() instanceof Player p) {
                        QueueSystem.removeFromAllQueues((Player) ctx.getSource());
                        RegisteredServer lobby = QueueSystem.server.getServer("lobby").get();
                        p.createConnectionRequest(lobby).connect();
                    }
                    return Command.SINGLE_SUCCESS;
                }).build();

        return new BrigadierCommand(commandNode);
    }

    public static BrigadierCommand createRejoinCommand(final ProxyServer proxy) {
        LiteralCommandNode<CommandSource> commandNode = BrigadierCommand.literalArgumentBuilder("rejoin").executes(ctx -> {
            if (ctx.getSource() instanceof Player p) {
                for (GameQueue gq : QueueSystem.queues) {
                    for (GameServer gs : gq.servers) {
                        if (gs.playersInGame.contains(p)) {
                            if (gs.type.equals(QueueSystem.queueTypes.litestrike) || gs.type.equals(QueueSystem.queueTypes.litestrike_ranked)) {
                                p.sendMessage(text("Connecting you to your previous Litestrike game..."));
                                p.createConnectionRequest(gs.server).connect();
                                return Command.SINGLE_SUCCESS;
                            } else {
                                p.sendMessage(text("Rejoining is only supported for Litestrike and Litestrike ranked currently.").color(NamedTextColor.RED));
                                return Command.SINGLE_SUCCESS;
                            }
                        }
                    }
                }
                p.sendMessage(text("You're not part of any ongoing game.").color(NamedTextColor.RED));
            }
            return Command.SINGLE_SUCCESS;
        }).build();

        return new BrigadierCommand(commandNode);
    }
}
