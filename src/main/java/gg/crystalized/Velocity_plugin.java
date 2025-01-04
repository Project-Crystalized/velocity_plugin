package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

import org.slf4j.Logger;

@Plugin(id = "crystalized_plugin", name = "crystalized_plugin", version = "0.1.0-SNAPSHOT", url = "https://crystalized.cc", description = "plugin for crystalized mc server", authors = {
		"crystalized_team" })
public class Velocity_plugin {

	private final ProxyServer server;
	public final Logger logger;
	public Litestrike_Selector ls_selector;
	public QueSystem que_system;
	public BanCommand ban_command;

	public static boolean event_started = true;

	public static final MinecraftChannelIdentifier CRYSTAL_CHANNEL = MinecraftChannelIdentifier.from("crystalized:main");
	public static final MinecraftChannelIdentifier LS_CHANNEL = MinecraftChannelIdentifier.from("crystalized:litestrike");

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
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		server.getChannelRegistrar().register(CRYSTAL_CHANNEL);
		server.getChannelRegistrar().register(LS_CHANNEL);

		this.ls_selector = new Litestrike_Selector(server, logger, this);
		this.que_system = new QueSystem(server, logger, this);
		server.getEventManager().register(this, ls_selector);

		CommandManager commandManager = server.getCommandManager();

		CommandMeta commandMetahub = commandManager.metaBuilder("hub").aliases("l", "lobby").plugin(this).build();
		commandManager.register(commandMetahub, new HubCommand(server.getServer("lobby").get()));

		CommandMeta commandMetaUnque = commandManager.metaBuilder("unque").plugin(this).build();
		commandManager.register(commandMetaUnque, new UnqueCommand(this));

		CommandMeta commandMetarejoin = commandManager.metaBuilder("rejoin").plugin(this).build();
		commandManager.register(commandMetarejoin, new RejoinCommand(ls_selector));

		CommandMeta commandMetaban = commandManager.metaBuilder("ban").plugin(this).build();
		ban_command = new BanCommand(logger, server);
		commandManager.register(commandMetaban, ban_command);

		CommandMeta commandMetaEvent = commandManager.metaBuilder("start_event").plugin(this).build();
		commandManager.register(commandMetaEvent, new StartEventCommand());
	}

	@Subscribe
	public void onPreConnect(PreLoginEvent e) {
		if (ban_command.isBanned(e.getUniqueId())) {
			e.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("you are banned")));
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
			if (!event_started) {
				backend_conn.getPlayer().sendMessage(Component.text("The event hasnt started yet!").color(NamedTextColor.RED));
				return;
			}
			ls_selector.send_player_litestrike(backend_conn.getPlayer());
			que_system.add_player_ls(backend_conn.getPlayer());
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
	public List<String> suggest(Invocation invocation) {
		return List.of();
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
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return true;
	}
}

class RejoinCommand implements SimpleCommand {
	private Litestrike_Selector ls_selector;

	public RejoinCommand(Litestrike_Selector ls_selector) {
		this.ls_selector = ls_selector;
	}

	@Override
	public void execute(Invocation invocation) {
		Player p = (Player) invocation.source();
		RegisteredServer rs = ls_selector.get_server_of(p);
		if (rs == null) {
			p.sendMessage(Component.text("looks like your not part of any game"));
		} else {
			p.sendMessage(Component.text("Connecting your to ls_server"));
			p.createConnectionRequest(rs).connect();
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return true;
	}
}
