package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
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
import static net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;

@Plugin(id = "crystalized_plugin", name = "crystalized_plugin", version = "0.1.0-SNAPSHOT", url = "https://crystalized.cc", description = "plugin for crystalized mc server", authors = {
		"crystalized_team" })
public class Velocity_plugin {

	public final ProxyServer server;
	public static Logger logger;

    public static QueueSystem queueSystem;
	public BanCommand ban_command;
	public PartySystem party_system;
	public FriendSystem friend_system;

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

		this.party_system = new PartySystem(server, this);
		this.friend_system = new FriendSystem(server, this);

		CommandManager commandManager = server.getCommandManager();

		CommandMeta commandMetahub = commandManager.metaBuilder("hub").aliases("l", "lobby").plugin(this).build();
		commandManager.register(commandMetahub, new HubCommand(server.getServer("lobby").get()));

		CommandMeta commandMetaban = commandManager.metaBuilder("ban").plugin(this).build();
		ban_command = new BanCommand(server);
		commandManager.register(commandMetaban, ban_command);

		CommandMeta commandMetaBroadcast = commandManager.metaBuilder("broadcast").plugin(this).build();
		commandManager.register(commandMetaBroadcast, new BroadCastCommand(server));

		CommandMeta commandMetaMsg = commandManager.metaBuilder("msg").plugin(this).build();
		commandManager.register(commandMetaMsg, new MsgCommand(server));

        queueSystem = new QueueSystem(server, this); //new version
        server.getEventManager().register(this, queueSystem);
	}

	@Subscribe
	public void onPreConnect(PreLoginEvent e) {
		if (ban_command.isBanned(e.getUniqueId())) {
            //TODO make this better with colour/visuals and replace placeholders with text.
			e.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    text("You've been banned by ")
                            .append(text("[Name here]")).append(text(" for the reason: \n\""))
                            .append(text("[Reason here]")).append(text("\".\n"))
                            .append(text("You will be unbanned in: ")).append(text("[time here]"))
            ));
		}
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
				//TODO
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

		String message3 = in.readUTF();
		boolean connect = true;
		if(message3 != null && message3.contains("false")){
			connect = false;
		}
        QueueSystem.removeFromAllQueues(backend_conn.getPlayer());
		if (message2.contains("litestrike")) {
            QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).addPlayerToQueue(backend_conn.getPlayer());
		} else if (message2.contains("knockoff")) {
            QueueSystem.getQueue(QueueSystem.queueTypes.knockoff).addPlayerToQueue(backend_conn.getPlayer());
		} else if (message2.contains("crystalblitz")) {
            QueueSystem.getQueue(QueueSystem.queueTypes.crystalblitz).addPlayerToQueue(backend_conn.getPlayer());
		} else if (message2.contains("lobby")) {
			RegisteredServer lobby = server.getServer("lobby").get();
			backend_conn.getPlayer().createConnectionRequest(lobby).connect();
			if(connect){
				QueueSystem.removeFromAllQueues(backend_conn.getPlayer());
			}
		}
	}

	@Subscribe
	public void onPreConnect(ServerPreConnectEvent e){
		Databases.updatePlayerNames(e.getPlayer());
		Databases.setOnline(e.getPlayer(), true);
		Friend.allFriends.add(new Friend(e.getPlayer()));
		ArrayList<Object[]> list = Databases.fetchFriends(e.getPlayer());
		for(Player p : server.getAllPlayers()){
			for(Object[] o : list){
				if(o[1] == Databases.uuid_to_bytes(p)){
					p.sendMessage(text(e.getPlayer().getUsername() + " has come online").color(YELLOW));
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

class HubCommand implements SimpleCommand {
	private RegisteredServer lobby;

	public HubCommand(RegisteredServer lobby) {
		this.lobby = lobby;
	}

	@Override
	public void execute(Invocation invocation) {
		((Player) invocation.source()).createConnectionRequest(lobby).connect();
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return true;
	}
}

class MsgCommand implements SimpleCommand {
	private ProxyServer server;

	public MsgCommand(ProxyServer server) {
		this.server = server;
	}

	@Override
	public void execute(final Invocation invocation) {
		if (invocation.arguments().length == 0) {
			return;
		}
		String messenger_name = "Console";
		if (invocation.source() instanceof Player) {
			messenger_name = ((Player) invocation.source()).getUsername();
		}
		Player p = server.getPlayer(invocation.arguments()[0]).orElse(null);
		if (p == null) {
			invocation.source().sendMessage(text("Couldn't find that player").color(RED));
			return;
		}

		if(!Settings.isAllowed("dms", p, (Player)invocation.source())){
			invocation.source().sendMessage(text("You cannot send messages to " + p.getUsername()).color(RED));
			return;
		}

		Component message = text("");
		for (String arg : invocation.arguments()) {
			if (arg == invocation.arguments()[0])
				continue;
			message = message.append(text(" " + arg));
		}
		invocation.source()
				.sendMessage(text("[You -> " + p.getUsername() + "] ").append(message).color(NamedTextColor.AQUA));
		p.sendMessage(text("[" + messenger_name + " -> You] ").append(message).color(NamedTextColor.AQUA));
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length == 0) {
			return server.getAllPlayers().stream().map(player -> player.getUsername()).collect(Collectors.toList());
		}
		if (invocation.arguments().length == 1) {
			return server.getAllPlayers().stream().map(player -> player.getUsername())
					.filter(name -> name.startsWith(invocation.arguments()[0])).collect(Collectors.toList());
		}
		return List.of();
	}

	@Override
	public boolean hasPermission(final Invocation invocation) {
		return true;
	}

}

class BroadCastCommand implements RawCommand {
	private ProxyServer server;

	public BroadCastCommand(ProxyServer server) {
		this.server = server;
	}

	@Override
	public void execute(final Invocation invocation) {
		Component message = text("!!IMPORTANT SERVER BROADCAST!!\n").color(YELLOW);
		message = message.append(text(invocation.arguments()));
		message.append(text("\n"));
		Audience.audience(server.getAllPlayers()).sendMessage(message);
	}

	@Override
	public boolean hasPermission(final Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		Player p = (Player) invocation.source();
		return Velocity_plugin.is_admin(p);
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
