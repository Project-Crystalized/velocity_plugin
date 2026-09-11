package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;

@Plugin(id = "crystalized_plugin", name = "crystalized_plugin", version = "0.1.0-SNAPSHOT", url = "https://crystalized.cc", description = "plugin for crystalized mc server", authors = {
		"crystalized_team" })
public class Velocity_plugin {

	public final ProxyServer server;
	public static Logger logger;
	final static boolean CLOSED_BETA = false;
    public static QueueSystem queueSystem;
	public static BanCommand ban_command;
	public static UnbanCommand unban_command;
	public PartySystem party_system;
	public FriendSystem friend_system;
	private static ArrayList<UUID> newPlayers = new ArrayList<>();

	public static final MinecraftChannelIdentifier CRYSTAL_CHANNEL = MinecraftChannelIdentifier.from("crystalized:main");
	public static final MinecraftChannelIdentifier CRYSTALIZED_ESSENTIALS = MinecraftChannelIdentifier.from("crystalized:essentials");


	@Inject
	public Velocity_plugin(ProxyServer server, Logger logger) {
		this.server = server;
		Velocity_plugin.logger = logger;
	}

	@Subscribe
	public void onCommand(CommandExecuteEvent e) {
		if (e.getCommand().startsWith("server")) {
			if (e.getCommandSource() instanceof Player) {
				if (!(is_admin((Player) e.getCommandSource()))) {
					e.setResult(CommandResult.denied());
				}
			} else {
				e.setResult(CommandResult.denied());
			}
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent e) {
		party_system.remove_player(e.getPlayer());
		Friend.allFriends.remove(Friend.getFriendObject(e.getPlayer()));
		Databases.setOnline(e.getPlayer(), false);
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		server.getChannelRegistrar().register(CRYSTAL_CHANNEL);
		server.getChannelRegistrar().register(CRYSTALIZED_ESSENTIALS);
		server.getChannelRegistrar().register(SetRankedCommand.LS_CHANNEL);

		this.party_system = new PartySystem(server, this);
		this.friend_system = new FriendSystem(server, this);

		CommandManager commandManager = server.getCommandManager();

		CommandMeta commandMetahub = commandManager.metaBuilder("hub").aliases("l", "lobby").plugin(this).build();
		commandManager.register(commandMetahub, createHubCommand(server));

		CommandMeta commandMetaban = commandManager.metaBuilder("ban").plugin(this).build();
		ban_command = new BanCommand(server);
		commandManager.register(commandMetaban, ban_command);

		CommandMeta commandMetaunban = commandManager.metaBuilder("unban").plugin(this).build();
		unban_command = new UnbanCommand(server);
		commandManager.register(commandMetaunban, unban_command);

		CommandMeta commandMetaBroadcast = commandManager.metaBuilder("broadcast").plugin(this).build();
		commandManager.register(commandMetaBroadcast, createBroadcastCommand(server));

		CommandMeta commandMetaMsg = commandManager.metaBuilder("msg").plugin(this).build();
		commandManager.register(commandMetaMsg, createMsgCommand(server));

		CommandMeta commandMetaSetRanked = commandManager.metaBuilder("ls_set_ranked").plugin(this).build();
		commandManager.register(commandMetaSetRanked, new SetRankedCommand(server));

		CommandMeta commandMetaKeygen = commandManager.metaBuilder("keygen").plugin(this).build();
		commandManager.register(commandMetaKeygen, new KeyGenCommand());

		CommandMeta commandMetaKey = commandManager.metaBuilder("key").plugin(this).build();
		commandManager.register(commandMetaKey, new KeyCommand());

		CommandMeta commandMetaSend = commandManager.metaBuilder("send").plugin(this).build();
		commandManager.register(commandMetaSend, createSendCommand(server));

        queueSystem = new QueueSystem(server, this); //new version
        server.getEventManager().register(this, queueSystem);
	}

	private BrigadierCommand createSendCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> sendNode = BrigadierCommand.literalArgumentBuilder("send")
				.requires(source -> !(source instanceof Player) || is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /send <player> <server>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							proxy.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
							return builder.buildFuture();
						})
						.then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									proxy.getAllServers().forEach(s -> builder.suggest(s.getServerInfo().getName()));
									return builder.buildFuture();
								})
								.executes(ctx -> {
									String playerName = ctx.getArgument("player", String.class);
									String serverName = ctx.getArgument("server", String.class);
									CommandSource source = ctx.getSource();
									Optional<Player> target = proxy.getPlayer(playerName);
									if (target.isEmpty()) {
										source.sendMessage(text("Player not found: " + playerName).color(RED));
										return Command.SINGLE_SUCCESS;
									}
									Optional<RegisteredServer> destination = proxy.getServer(serverName);
									if (destination.isEmpty()) {
										source.sendMessage(text("Server not found: " + serverName).color(RED));
										return Command.SINGLE_SUCCESS;
									}
									target.get().createConnectionRequest(destination.get()).connect();
									source.sendMessage(text("Sent " + target.get().getUsername() + " to " + destination.get().getServerInfo().getName()).color(NamedTextColor.GREEN));
									return Command.SINGLE_SUCCESS;
								})
						)
				)
				.build();
		return new BrigadierCommand(sendNode);
	}

	private BrigadierCommand createHubCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> hubNode = BrigadierCommand.literalArgumentBuilder("hub")
				.executes(ctx -> {
					if (ctx.getSource() instanceof Player p) {
						Optional<RegisteredServer> lobby = proxy.getServer("lobby");
						if (lobby.isEmpty()) {
							p.sendMessage(text("Lobby server not found.").color(RED));
							return Command.SINGLE_SUCCESS;
						}
						p.createConnectionRequest(lobby.get()).connect();
					} else {
						ctx.getSource().sendMessage(text("Only players can use this command.").color(RED));
					}
					return Command.SINGLE_SUCCESS;
				})
				.build();
		return new BrigadierCommand(hubNode);
	}

	private BrigadierCommand createMsgCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> msgNode = BrigadierCommand.literalArgumentBuilder("msg")
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /msg <player> <message>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							proxy.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
							return builder.buildFuture();
						})
						.then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
								.executes(ctx -> {
									String targetName = ctx.getArgument("player", String.class);
									String rawMessage = ctx.getArgument("message", String.class);
									CommandSource source = ctx.getSource();
									Player target = proxy.getPlayer(targetName).orElse(null);
									if (target == null) {
										source.sendMessage(translatable("crystalized.proxy.msg.not_found").color(RED));
										return Command.SINGLE_SUCCESS;
									}
									String messengerName = "Console";
									if (source instanceof Player sender) {
										messengerName = sender.getUsername();
										if (!Settings.isAllowed("dms", target, sender)) {
											source.sendMessage(translatable("crystalized.proxy.msg.not_allowed", List.of(Component.text(target.getUsername()))).color(RED));
											return Command.SINGLE_SUCCESS;
										}
									}
									Component message = text(" " + rawMessage);
									source.sendMessage(text("[").append(translatable("crystalized.generic.you")).append(text(" -> " + target.getUsername() + "] ")).append(message).color(NamedTextColor.AQUA));
									target.sendMessage(text("[" + messengerName + " -> ").append(translatable("crystalized.generic.you")).append(text("] ")).append(message).color(NamedTextColor.AQUA));
									return Command.SINGLE_SUCCESS;
								})
						)
				)
				.build();
		return new BrigadierCommand(msgNode);
	}

	private BrigadierCommand createBroadcastCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> broadcastNode = BrigadierCommand.literalArgumentBuilder("broadcast")
				.requires(source -> !(source instanceof Player) || is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /broadcast <message>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
						.executes(ctx -> {
							String rawMessage = ctx.getArgument("message", String.class);
							Component message = translatable("crystalized.generic.broadcast").color(YELLOW);
							message = message.append(text(rawMessage));
							message = message.append(text("\n"));
							Audience.audience(proxy.getAllPlayers()).sendMessage(message);
							return Command.SINGLE_SUCCESS;
						})
				)
				.build();
		return new BrigadierCommand(broadcastNode);
	}

	@Subscribe
	public void onPreConnect(PreLoginEvent e) {
		if(CLOSED_BETA && !Databases.isPlayerInDatabase(e.getUniqueId())) {
		 newPlayers.add(e.getUniqueId());
		}
		if (ban_command.isBanned(e.getUniqueId())) {
			e.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    text("You've been banned for ").color(RED)
                            .append(text(BanCommand.getBannedFor(e.getUniqueId())).color(RED)).append(text("\n").color(RED))
                            .append(text("You will be unbanned in: ").color(RED)).append(text(BanCommand.getBannedUntil(e.getUniqueId())).color(RED))
            ));
			newPlayers.remove(e.getUniqueId());
		}
	}

	@Subscribe
	public void onPostConnect(ServerPostConnectEvent e){
		if(!newPlayers.contains(e.getPlayer().getUniqueId())) return;
		Databases.deletePlayerData(e.getPlayer());
		Databases.deleteSettings(e.getPlayer());
		Optional<RegisteredServer> s = server.getServer("limbo");
		if(s.isEmpty()) return;
		e.getPlayer().createConnectionRequest(s.get()).connect();
		newPlayers.remove(e.getPlayer().getUniqueId());
	}

	@Subscribe
	public void onPluginMessageFromBackend(PluginMessageEvent event) {
		if (!CRYSTAL_CHANNEL.equals(event.getIdentifier())) {
			return;
		}
		if (event.getIdentifier().equals(CRYSTALIZED_ESSENTIALS)) {
			event.getTarget().sendPluginMessage(CRYSTALIZED_ESSENTIALS, event.getData());
			return;
		}
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (!(event.getSource() instanceof ServerConnection backend_conn)) {
			return;
		}

		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
		String message1 = in.readUTF();
		if(message1.contains("Party")){
			String message2 = in.readUTF();
			if(message2.contains("invite")){
				String message3 = in.readUTF();
				String command = "party invite " + message3;
				server.getCommandManager().executeAsync(backend_conn.getPlayer(), command);
			}else if(message2.contains("members")){
				Party party = this.party_system.get_party_of(backend_conn.getPlayer());
				ByteArrayDataOutput out = ByteStreams.newDataOutput();
				if(party == null){
					out.writeUTF("Party");
					backend_conn.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
					return;
				}
				backend_conn.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, party.update_message().toByteArray());
			}else if(message2.contains("remove")){
				String message3 = in.readUTF();
				String command = "party remove " + message3;
				server.getCommandManager().executeAsync(backend_conn.getPlayer(), command);
			}
			return;
		}

		if(message1.contains("Friend")){
			String message2 = in.readUTF();
			if(message2.contains("remove")){
				String message3 = in.readUTF();
				String command = "friend remove " + message3;
				server.getCommandManager().executeAsync(backend_conn.getPlayer(), command);
			}else if(message2.contains("add")){
				String message3 = in.readUTF();
				String command = "friend request " + message3;
				server.getCommandManager().executeAsync(backend_conn.getPlayer(), command);
			}
			return;
		}

		if(message1.contains("Online")){
			String message2 = in.readUTF();
			if(message2.equals("a")){
				ByteArrayDataOutput out = ByteStreams.newDataOutput();
				out.writeUTF("Online");
				out.writeUTF(message2);
				out.writeInt(server.getAllPlayers().size());
				backend_conn.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
				return;
			}
			int i = 0;
			if(server.getPlayer(message2).isPresent()){
				i = 1;
			}
			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeUTF("Online");
			out.writeUTF(message2);
			out.writeInt(i);
			backend_conn.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
			return;
		}

		if (!(message1.contains("Connect"))) {
			return;
		}

		String message2 = in.readUTF();

		String message3 = null;
		try{message3 = in.readUTF();}catch(IllegalStateException e){}
		boolean connect = true;
		if (message3 != null && message3.contains("false")) {
			connect = false;
		}

    QueueSystem.removeFromAllQueues(backend_conn.getPlayer());
		if (message2.contains("litestrike")) {
			if(message2.contains("ranked")) {
				QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).addPlayerToQueue(backend_conn.getPlayer());
			}else {
				QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).addPlayerToQueue(backend_conn.getPlayer());
			}
		} else if (message2.contains("knockoff")) {
            QueueSystem.getQueue(QueueSystem.queueTypes.knockoff).addPlayerToQueue(backend_conn.getPlayer());
		} else if (message2.contains("crystalblitz")) {
			QueueSystem.getQueue(QueueSystem.queueTypes.crystalblitz).addPlayerToQueue(backend_conn.getPlayer());
		} else if (message2.contains("lobby")) {
			server.getServer("lobby").ifPresentOrElse(
				lobby -> backend_conn.getPlayer().createConnectionRequest(lobby).connect(),
				() -> backend_conn.getPlayer().sendMessage(text("[QueueSystem] Lobby server not found.", NamedTextColor.RED))
			);
			if(connect){
				QueueSystem.removeFromAllQueues(backend_conn.getPlayer());
			}
		}
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent e){
		Databases.updatePlayerNames(e.getPlayer());
		Databases.setOnline(e.getPlayer(), true);
		Friend.allFriends.add(new Friend(e.getPlayer()));
		ArrayList<Object[]> list = Databases.fetchFriendsWithNames(e.getPlayer());
		if(list == null){
			return;
		}
		for(Player p : server.getAllPlayers()){
			for(Object[] o : list){
				if(Arrays.equals((byte[]) o[0], Databases.uuid_to_bytes(p))){
					p.sendMessage(text(e.getPlayer().getUsername()).append(translatable("crystalized.proxy.friends.joined")).color(YELLOW));
				}
			}
		}
	}

	public static boolean is_admin(Player p) {
		if (p.getUsername().equals("cooltexture")
				|| p.getUsername().equals("Callum_Is_Bad")
				|| p.getUsername().equals(".CallumIsBad6502")
				|| p.getUsername().equals("LadyCat_")
				|| p.getUsername().equals("___mira___")
				|| p.getUsername().equals("Delieve")) {
			return true;
		} else {
			return false;
		}
	}
}

class Settings{
	public static Double toDouble(Object o){
		if(o instanceof Double){
			return (Double)o;
		}
		if(!(o instanceof Integer)){
			return null;
		}
		return Double.valueOf((Integer) o);
	}

	public static boolean isAllowed(String database, Player p, Player invocer){
		boolean player;
		if(Databases.fetchSettings(p).get(database) == null){
			player = false;
		}else if(toDouble(Databases.fetchSettings(p).get(database)) == 0){
			player = false;
		}else if(toDouble(Databases.fetchSettings(p).get(database)) == 0.5 && !Databases.areFriends(p, invocer)){
			player =  false;
		}else {
			player = true;
		}

		boolean invoc;
		if(Databases.fetchSettings(invocer).get(database) == null){
			invoc = false;
		}else if(toDouble(Databases.fetchSettings(invocer).get(database)) == 0){
			invoc = false;
		}else if(toDouble(Databases.fetchSettings(invocer).get(database)) == 0.5 && !Databases.areFriends(p, invocer)){
			invoc =  false;
		}else {
			invoc = true;
		}

		return player && invoc;
	}

	public static boolean isReceiveAllowed(String database, Player p, Player invocer){
		if(Databases.fetchSettings(p).get(database) == null){
			return false;
		}
		if(toDouble(Databases.fetchSettings(p).get(database)) == 0){
			return false;
		}

		if(toDouble(Databases.fetchSettings(p).get(database)) == 0.5 && !Databases.areFriends(p, invocer)){
			return false;
		}
		return true;
	}
}
