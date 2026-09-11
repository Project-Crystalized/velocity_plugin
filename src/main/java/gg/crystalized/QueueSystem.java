package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public class QueueSystem {
    private Velocity_plugin velocity;
    public ProxyServer server;
    public static List<GameQueue> queues = new ArrayList<>();

    protected enum ServerStatus{
        ONLINE_AVAILABLE,
        ONLINE_INGAME,
        OFFLINE,
    }
    protected enum queueTypes{
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
        queues.add(new GameQueue(server, plugin, queueTypes.litestrike_ranked, 6, 8, text("Litestrike Ranked").color(NamedTextColor.GREEN))); //FixMe
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

        CommandMeta commandMetaQueueStatus = commandManager.metaBuilder("queuestatus").plugin(plugin).build();
        commandManager.register(commandMetaQueueStatus, new QueueStatusCommand(server));

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
            if (rs.getServerInfo().getName().startsWith(getSuperType(type).toString())) {
                servers.add(new GameServer(rs, type));
            }
        }
        Velocity_plugin.logger.info("[QueueSystem] Registered \"" + type + "\" queue with " + servers.size() + " server(s).");

        AtomicReference<AtomicInteger> timer = new AtomicReference<>(new AtomicInteger(15)); //werid shit
        proxyServer.getScheduler().buildTask(plugin, () -> {
            for (GameServer s : servers) {
                s.updateServerStatus();
            }

            //Queue timer
            if ((players.size() == needed || players.size() > needed) && !queueTimerStarted) {
                queueTimerStarted = true;
                timer.set(new AtomicInteger(15));
            } else if ((players.size() < needed) && queueTimerStarted) {
                queueTimerStarted = false;
                for (Player p : players) {
                    p.sendMessage(translatable("crystalized.generic.queue.cancelled"));
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
                }
                for (Player p : players) {
                    p.sendActionBar(translatable("crystalized.generic.queue.for").append(name).append(text(" (" + players.size() + "/" + needed + "), ").append(translatable("crystalized.generic.queue.teleporting")).append(text(timer.toString()))));
                }
                if (timer.get().get() == 0) {
                    sendAllPlayersToServer(type);
                    timer.set(new AtomicInteger(15));
                }
            } else {
                timer.set(new AtomicInteger(15));
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

    public QueueSystem.queueTypes getSuperType(QueueSystem.queueTypes type){
        //because litestrike and litestrike_ranked use the same servers this is e.g. if type = litestrike_ranked to find the type litestrike and use that instead
        QueueSystem.queueTypes result = type;
        for(QueueSystem.queueTypes t : QueueSystem.queueTypes.values()){
            if(t == type) continue;
            if(type.toString().startsWith(t.toString())) result = t;
        }
        return result;
    }

    public void addPlayerToQueue(Player p) {
        if (players.size() == max) {
            p.sendMessage(translatable("crystalized.generic.queue.full", List.of(name)));
        } else {
            for (GameServer s : servers) {
                if (s.available.equals(QueueSystem.ServerStatus.ONLINE_AVAILABLE)) {
                    players.add(p);
                    p.sendMessage(translatable("crystalized.generic.queue.queued_for").append(name));
                    return;
                }
            }

            p.sendMessage(translatable("crystalized.generic.queue.unavailable").color(NamedTextColor.RED).append(name).append(translatable("crystalized.generic.queue.try_again").color(NamedTextColor.RED)));
        }
    }

    public void removePlayerToQueue(Player p) {
        if (players.contains(p)) {
            players.remove(p);
            p.sendMessage(translatable("crystalized.generic.queue.left", List.of(name)));
            p.sendActionBar(text("")); //To instantly remove the actionbar instead of minecraft fading the text away
        }
    }

    public void sendAllPlayersToServer(QueueSystem.queueTypes type) {
        List<GameServer> templist = new ArrayList<>(servers);
        Collections.shuffle(templist);

        for (GameServer s : templist) {
            if (s.available.equals(QueueSystem.ServerStatus.ONLINE_AVAILABLE)) {
                List<Player> playerList = new ArrayList<>(players); //copying here to prevent a ConcurrentModificationException
                players.clear();
                s.playersInGame = playerList;
                CompletableFuture<ConnectionRequestBuilder.Result> future = null;
                for (Player p : playerList) {
                    if(future == null) future = p.createConnectionRequest(s.server).connect();
                    else p.createConnectionRequest(s.server).connect();
                }
                CompletableFuture<ConnectionRequestBuilder.Result> finalFuture = future;
                sendAdditionalMessage(s, type, finalFuture);
                return;
            }
        }

        for (Player p : players) {
            p.sendMessage(translatable("crystalized.generic.queue.unavailable").color(NamedTextColor.RED).append(name).append(translatable("crystalized.generic.queue.try_again").color(NamedTextColor.RED)));
        }
    }

    public void sendAdditionalMessage(GameServer s, QueueSystem.queueTypes type, CompletableFuture<ConnectionRequestBuilder.Result> future){
        if(future == null) return;
        future.whenComplete((result, error) -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            if(type != QueueSystem.queueTypes.litestrike && type != QueueSystem.queueTypes.litestrike_ranked){
                return;
            }
            if(type == QueueSystem.queueTypes.litestrike) {
                out.writeUTF("ranked_off");
            }else if(type == QueueSystem.queueTypes.litestrike_ranked){
                out.writeUTF("ranked_on");
            }
            s.server.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
        });
    }
}

class GameServer{
    RegisteredServer server;
    QueueSystem.queueTypes type;
    QueueSystem.ServerStatus available = QueueSystem.ServerStatus.OFFLINE;
    boolean isGoing = false;
    public List<Player> playersInGame = new ArrayList<>(); //for rejoining

    public GameServer(RegisteredServer server, QueueSystem.queueTypes type) {
        this.type = type;
        this.server = server;

    }

    public void updateServerStatus() {
        QueueSystem.ServerStatus status = available;
        server.ping().orTimeout(3, TimeUnit.SECONDS).whenComplete((ping, error) -> {
            if (error != null) {
                available = QueueSystem.ServerStatus.OFFLINE;
								if (!error.getMessage().contains("Connection refused")) {
									Velocity_plugin.logger.warn("got unknown error when pinging backend: " + error.toString());
								}
            } else if (server.getPlayersConnected().isEmpty() && isGoing) {
                available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
                isGoing = false;
                playersInGame.clear();
            } else if (isGoing) {
                available = QueueSystem.ServerStatus.ONLINE_INGAME;
            } else {
                available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
            }
            if (status != available) {
                Velocity_plugin.logger.info("[QueueSystem] " + server.getServerInfo().getName() + " has changed availability from " + status + " to " + available);
            }
        });
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
                        proxy.getServer("lobby").ifPresentOrElse(
                            lobby -> p.createConnectionRequest(lobby).connect(),
                            () -> p.sendMessage(text("[QueueSystem] Lobby server not found.", NamedTextColor.RED))
                        );
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
                                p.sendMessage(translatable("crystalized.generic.queue.rejoin"));
                                p.createConnectionRequest(gs.server).connect();
                                return Command.SINGLE_SUCCESS;
                            } else {
                                p.sendMessage(translatable("crystalized.generic.queue.rejoin.supported").color(NamedTextColor.RED));
                                return Command.SINGLE_SUCCESS;
                            }
                        }
                    }
                }
                p.sendMessage(translatable("crystalized.generic.queue.rejoin.fail").color(NamedTextColor.RED));
            }
            return Command.SINGLE_SUCCESS;
        }).build();

        return new BrigadierCommand(commandNode);
    }
}

class QueueStatusCommand implements SimpleCommand {
	private final ProxyServer server;

	public QueueStatusCommand(ProxyServer server) {
		this.server = server;
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		return invocation.source() instanceof Player p && Velocity_plugin.is_admin(p);
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		source.sendMessage(text("Queue system status:").color(NamedTextColor.AQUA));

		for (GameQueue q : QueueSystem.queues) {
			source.sendMessage(text("  ").append(q.name)
					.append(text(" (" + q.type + ") - " + q.players.size() + " queued (needed " + q.needed + " / max " + q.max + ")")));
			for (GameServer s : q.servers) {
				NamedTextColor color = NamedTextColor.RED;
				String status = s.available.toString();
				if (s.available == QueueSystem.ServerStatus.ONLINE_AVAILABLE) {
					color = NamedTextColor.GREEN;
				} else if (s.available == QueueSystem.ServerStatus.ONLINE_INGAME) {
					color = NamedTextColor.GOLD;
					status += " (" + s.server.getPlayersConnected().size() + " players)";
				}
				source.sendMessage(text("    ").append(text(s.server.getServerInfo().getName(), NamedTextColor.WHITE))
						.append(text(": ", NamedTextColor.WHITE)).append(text(status, color)));
			}
		}

		source.sendMessage(text("  Other servers:").color(NamedTextColor.AQUA));
		for (RegisteredServer rs : server.getAllServers()) {
			if (isServerInGameQue(rs)) {
				continue;
			}
			source.sendMessage(text("    ").append(text(rs.getServerInfo().getName(), NamedTextColor.WHITE))
					.append(text(": " + rs.getPlayersConnected().size() + " players")));
		}
		source.sendMessage(text("-------------").color(NamedTextColor.GOLD));
	}

	private boolean isServerInGameQue(RegisteredServer rs) {
		return QueueSystem.queues.stream().anyMatch(q -> q.servers.stream().anyMatch(gs -> gs.server == rs));
	}
}
