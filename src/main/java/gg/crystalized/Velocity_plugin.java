package gg.crystalized;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
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

	public static final MinecraftChannelIdentifier CRYSTAL_CHANNEL = MinecraftChannelIdentifier.from("crystalized:main");
	public static final MinecraftChannelIdentifier LS_CHANNEL = MinecraftChannelIdentifier.from("crystalized:litestrike");

	@Inject
	public Velocity_plugin(ProxyServer server, Logger logger) {
		this.server = server;
		this.logger = logger;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		server.getChannelRegistrar().register(CRYSTAL_CHANNEL);
		server.getChannelRegistrar().register(LS_CHANNEL);

		this.ls_selector = new Litestrike_Selector(server, logger, this);
		server.getEventManager().register(this, ls_selector);

		CommandManager commandManager = server.getCommandManager();

		CommandMeta commandMetahub = commandManager.metaBuilder("hub").aliases("l", "lobby").plugin(this).build();
		commandManager.register(commandMetahub, new HubCommand(server.getServer("lobby").get()));

		CommandMeta commandMetarejoin = commandManager.metaBuilder("rejoin").plugin(this).build();
		commandManager.register(commandMetarejoin, new RejoinCommand(ls_selector));
	}

	@Subscribe
	public void onPluginMessageFromBackend(PluginMessageEvent event) {
		if (!CRYSTAL_CHANNEL.equals(event.getIdentifier())) {
			return;
		}
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (!(event.getSource() instanceof ServerConnection backend)) {
			return;
		}

		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
		String message1 = in.readUTF();
		if (!(message1.contains("Connect"))) {
			return;
		}

		String message2 = in.readUTF();
		if (message2.contains("litestrike")) {
			RegisteredServer reg_server = ls_selector.get_selected();
			if (reg_server == null) {
				backend.getPlayer()
						.sendMessage(Component.text("No Litestrike Server is available atm.").color(NamedTextColor.RED));
				return;
			}
			backend.getPlayer().createConnectionRequest(reg_server).connect();
		} else if (message2.contains("lobby")) {
			RegisteredServer lobby = server.getServer("lobby").get();
			backend.getPlayer().createConnectionRequest(lobby).connect();
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
