package gg.crystalized;

 /*
 NOTICE: THIS IS A WIP REWRITE OF THE QUEUE SYSTEM, QueSystem.java is the old version - Callum
  */

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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public class QueueSystem {
    private Velocity_plugin velocity;
    public static ProxyServer server;
    public List<GameServer> gameServers = new ArrayList<>();
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
        //queues.add(new GameQueue(server, plugin, queueTypes.litestrike, 6, 10));
        //queues.add(new GameQueue(server, plugin, queueTypes.litestrike_ranked, 6, 8));
        queues.add(new GameQueue(server, plugin, queueTypes.knockoff, 3, 12));
        queues.add(new GameQueue(server, plugin, queueTypes.crystalblitz, 3, 8));
        //queues.add(new GameQueue(server, plugin, queueTypes.crystalblitz_duos, 4, 16));

        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMetaQueue = commandManager.metaBuilder("new_queue").plugin(plugin).build();
        commandManager.register(commandMetaQueue, QueueCommand.createBrigadierCommand(server));

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
    ProxyServer proxyServer;
    public QueueSystem.queueTypes type;

    public GameQueue(ProxyServer proxyServer, Velocity_plugin plugin, QueueSystem.queueTypes type, int playersNeededToStart, int playersMaxLimit) {
        players.clear();
        this.type = type;
        this.proxyServer = proxyServer;
        for (RegisteredServer rs : proxyServer.getAllServers()) {
            if (rs.getServerInfo().getName().startsWith(type.toString())) {
                servers.add(new GameServer(rs, type));
            }
        }
        Velocity_plugin.logger.info("[QueueSystem] Registered \"" + type + "\" queue with " + servers.size() + " server(s).");

        proxyServer.getScheduler().buildTask(plugin, () -> {
            for (Player p : players) {
                List<Component> translatableList = new ArrayList<>();
                translatableList.add(text(type.toString()));
                translatableList.add(text(players.size()));
                translatableList.add(text(playersNeededToStart));
                p.sendActionBar(translatable("crystalized.generic.queue.actionbar", translatableList).color(NamedTextColor.YELLOW));
            }

            for (GameServer s : servers) {
                s.updateServerStatus();
            }

            if (players.size() == playersMaxLimit) {
                sendAllPlayersToServer();
            }
        }).repeat(500, TimeUnit.MILLISECONDS).schedule();
    }

    public void addPlayerToQueue(Player p) {
        players.add(p);
        p.sendMessage(text("You are queued for " + type)); //TODO make translatable
    }

    public void removePlayerToQueue(Player p) {
        if (players.contains(p)) {
            players.remove(p);
            p.sendMessage(text("You left the queue for " + type)); //TODO make translatable
            p.sendActionBar(text("")); //To instantly remove the actionbar instead of minecraft fading the text away

            //RegisteredServer lobby = proxyServer.getServer("lobby").get();
            //p.createConnectionRequest(lobby).connect();
        }
    }

    public void sendAllPlayersToServer() {
        for (GameServer s : servers) {
            if (s.available.equals(QueueSystem.ServerStatus.online_free)) {
                for (Player p : players) {
                    if (!p.getCurrentServer().get().getServer().equals(s.server)) {
                        p.createConnectionRequest(s.server);
                        players.remove(p);
                    }
                }
                return;
            }
        }
    }
}

class GameServer{
    RegisteredServer server;
    QueueSystem.queueTypes type;
    QueueSystem.ServerStatus available;
    boolean isGoing = false;

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
        LiteralCommandNode<CommandSource> commandNode = BrigadierCommand.literalArgumentBuilder("new_queue")
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
                .then(BrigadierCommand.literalArgumentBuilder("exitCurrent").executes(ctx -> {
                    if (ctx.getSource() instanceof Player p) {
                        QueueSystem.removeFromAllQueues((Player) ctx.getSource());
                        RegisteredServer lobby = QueueSystem.server.getServer("lobby").get();
                        p.createConnectionRequest(lobby).connect();
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build();

        return new BrigadierCommand(commandNode);
    }
}
