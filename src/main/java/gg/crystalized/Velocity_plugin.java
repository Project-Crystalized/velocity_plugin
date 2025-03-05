package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
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
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;

@Plugin(id = "crystalized_plugin", name = "crystalized_plugin", version = "0.1.0-SNAPSHOT", url = "https://crystalized.cc", description = "plugin for crystalized mc server", authors = {
		"crystalized_team" })
public class Velocity_plugin {

	private final ProxyServer server;
	public final Logger logger;
	public Litestrike_Selector ls_selector;
	public Knockoff_Selector ko_selector;
	public QueSystem que_system;
	public BanCommand ban_command;
	public PartySystem party_system;

	public static boolean event_started = true;

	public static final MinecraftChannelIdentifier CRYSTAL_CHANNEL = MinecraftChannelIdentifier.from("crystalized:main");
	public static final MinecraftChannelIdentifier LS_CHANNEL = MinecraftChannelIdentifier.from("crystalized:litestrike");
	public static final MinecraftChannelIdentifier KO_CHANNEL = MinecraftChannelIdentifier.from("crystalized:knockoff");

	@Inject
	public Velocity_plugin(ProxyServer server, Logger logger) {
		this.server = server;
		this.logger = logger;
	}

	@Subscribe
	public void onCommand(CommandExecuteEvent e) {
		if (e.getCommand().startsWith("server")) {
			e.setResult(CommandResult.denied());
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent e) {
		que_system.remove_player_from_que(e.getPlayer());
		party_system.remove_player(e.getPlayer());
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		server.getChannelRegistrar().register(CRYSTAL_CHANNEL);
		server.getChannelRegistrar().register(LS_CHANNEL);
		server.getChannelRegistrar().register(KO_CHANNEL);

		this.ls_selector = new Litestrike_Selector(server, logger, this);
		this.ko_selector = new Knockoff_Selector(server, logger, this);
		server.getEventManager().register(this, ls_selector);
		server.getEventManager().register(this, ko_selector);

		this.party_system = new PartySystem(server, logger, this);
		this.que_system = new QueSystem(server, logger, this);

		CommandManager commandManager = server.getCommandManager();

		CommandMeta commandMetahub = commandManager.metaBuilder("hub").aliases("l", "lobby").plugin(this).build();
		commandManager.register(commandMetahub, new HubCommand(server.getServer("lobby").get()));

		CommandMeta commandMetaUnque = commandManager.metaBuilder("unque").aliases("unqueue").plugin(this).build();
		commandManager.register(commandMetaUnque, new UnqueCommand(this));

		CommandMeta commandMetarejoin = commandManager.metaBuilder("rejoin").plugin(this).build();
		commandManager.register(commandMetarejoin, new RejoinCommand(ls_selector, que_system));

		CommandMeta commandMetaban = commandManager.metaBuilder("ban").plugin(this).build();
		ban_command = new BanCommand(logger, server);
		commandManager.register(commandMetaban, ban_command);

		CommandMeta commandMetaEvent = commandManager.metaBuilder("start_event").plugin(this).build();
		commandManager.register(commandMetaEvent, new StartEventCommand());

		CommandMeta commandMetaBroadcast = commandManager.metaBuilder("broadcast").plugin(this).build();
		commandManager.register(commandMetaBroadcast, new BroadCastCommand(server));

		CommandMeta commandMetaMsg = commandManager.metaBuilder("msg").plugin(this).build();
		commandManager.register(commandMetaMsg, new MsgCommand(server));
	}

	@Subscribe
	public void onPreConnect(PreLoginEvent e) {
		if (ban_command.isBanned(e.getUniqueId())) {
			e.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("you are banned")));
		}

	}

	@Subscribe
	public void onPluginMessageFromBackend(PluginMessageEvent event) {
		if (!CRYSTAL_CHANNEL.equals(event.getIdentifier())) {
			return;
		}
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (!(event.getSource() instanceof ServerConnection backend_conn)) {
			return;
		}

		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
		String message1 = in.readUTF();
		if (!(message1.contains("Connect"))) {
			return;
		}

		String message2 = in.readUTF();
		if (message2.contains("litestrike")) {
			ls_selector.send_player(backend_conn.getPlayer());
			que_system.ls_que.add_player(backend_conn.getPlayer());
		} else if (message2.contains("knockoff")) {
			ko_selector.send_player(backend_conn.getPlayer());
			que_system.ko_que.add_player(backend_conn.getPlayer());
		} else if (message2.contains("lobby")) {
			RegisteredServer lobby = server.getServer("lobby").get();
			backend_conn.getPlayer().createConnectionRequest(lobby).connect();
		}
	}

	public static boolean is_admin(Player p) {
		if (p.getUsername().equals("cooltexture")
				|| p.getUsername().equals("Callum_Is_Bad")
				|| p.getUsername().equals("LadyCat_")
				|| p.getUsername().equals("___mira___")) {
			return true;
		} else {
			return false;
		}
	}
}

class StartEventCommand implements SimpleCommand {

	@Override
	public void execute(Invocation invocation) {
		Velocity_plugin.event_started = !Velocity_plugin.event_started;
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		Player p = (Player) invocation.source();
		return Velocity_plugin.is_admin(p);
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

class RejoinCommand implements SimpleCommand {
	private Litestrike_Selector ls_selector;
	private QueSystem qs;

	public RejoinCommand(Litestrike_Selector ls_selector, QueSystem qs) {
		this.qs = qs;
		this.ls_selector = ls_selector;
	}

	@Override
	public void execute(Invocation invocation) {
		Player p = (Player) invocation.source();
		RegisteredServer rs = ls_selector.get_server_of(p);
		if (rs == null) {
			p.sendMessage(text("looks like your not part of any game"));
		} else {
			qs.remove_player_from_que(p);
			p.sendMessage(text("Connecting you to previous game"));
			p.createConnectionRequest(rs).connect();
		}
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
			invocation.source().sendMessage(text("Couldnt find that player"));
			return;
		}
		Component message = text("");
		for (String arg : invocation.arguments()) {
			if (arg == invocation.arguments()[0])
				continue;
			message = message.append(text(" " + arg));
		}
		invocation.source()
				.sendMessage(text("[You -> " + p.getUsername() + "] ").append(message).color(NamedTextColor.LIGHT_PURPLE));
		p.sendMessage(text("[" + messenger_name + " -> You] ").append(message).color(NamedTextColor.LIGHT_PURPLE));
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length == 0 || invocation.arguments().length == 1) {
			return server.getAllPlayers().stream().map(player -> player.getUsername()).collect(Collectors.toList());
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
		Component message = text("!!IMPORTANT SERVER BROADCAST!!\n").color(NamedTextColor.YELLOW);
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

interface ServerSelector {
	public void send_player(Player p);
}
