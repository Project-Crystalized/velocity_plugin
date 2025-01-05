package gg.crystalized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.slf4j.Logger;

public class Knockoff_Selector {

	private List<KnockoffServer> ko_servers = new ArrayList<>();
	private KnockoffServer selected_server;

	private Logger logger;
	private Velocity_plugin plugin;

	public Knockoff_Selector(ProxyServer server, Logger logger, Velocity_plugin plugin) {
		this.logger = logger;
		this.plugin = plugin;
		for (RegisteredServer rs : server.getAllServers()) {
			if (rs.getServerInfo().getName().startsWith("knockoff")) {
				ko_servers.add(new KnockoffServer(rs, server, plugin));
			}
		}
		select_new_server();

		// stop server when no player is connected to them
		server.getScheduler().buildTask(plugin, () -> {
			for (KnockoffServer kos : ko_servers) {
				if (kos.rs.getPlayersConnected().size() == 0 && kos.is_going()) {
					server.getScheduler().buildTask(plugin, () -> kos.game_end()).delay(21, TimeUnit.SECONDS)
							.schedule();
					logger.info("Scheduled a knockoff server to become ready again");
				}
			}
		}).repeat(22, TimeUnit.SECONDS).schedule();
	}

	public void send_player_knockoff(Player p) {
		if (selected_server == null || selected_server.is_going() || !selected_server.is_online) {
			select_new_server();
		}
		if (selected_server == null) {
			p.sendMessage(get_servers_status());
			return;
		}
		p.createConnectionRequest(selected_server.rs).connect();
	}

	@Subscribe
	public void onPluginMessageFromBackend(PluginMessageEvent event) {
		if (!Velocity_plugin.KO_CHANNEL.equals(event.getIdentifier())) {
			return;
		}
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (!(event.getSource() instanceof ServerConnection backend)) {
			return;
		}
		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());

		String message = in.readUTF();
		if (message.contains("start_game")) {
			for (KnockoffServer kos : ko_servers) {
				if (kos.rs == backend.getServer()) {
					Set<String> playing_players = new HashSet<>();
					while (true) {
						try {
							playing_players.add(in.readUTF());
						} catch (Exception e) {
							break;
						}
					}
					kos.start_game(playing_players);
					select_new_server();
					plugin.que_system.clear_ko_que();
				}
			}
		}
	}

	private void select_new_server() {
		selected_server = null;
		Collections.shuffle(ko_servers);
		for (KnockoffServer kos : ko_servers) {
			if (!kos.is_going() && kos.is_online) {
				selected_server = kos;
				return;
			}
		}
		if (logger != null) {
			logger.error("No Knockoff servers are available at the Moment");
		}
	}

	public RegisteredServer get_server_of(Player p) {
		for (KnockoffServer kos : ko_servers) {
			if (kos.contains_player(p)) {
				return kos.rs;
			}
		}
		return null;
	}

	public Component get_servers_status() {
		int registerd = 0;
		int online = 0;
		int games_going = 0;

		for (KnockoffServer kos : ko_servers) {
			registerd += 1;
			if (kos.is_online) {
				online += 1;
			}
			if (kos.is_going()) {
				games_going += 1;
			}
		}

		if (online == games_going) {
			return Component.text("Server status: No Knockoff Server is available atm. \n").color(NamedTextColor.RED)
					.append(Component.text("Registered servers: " + registerd + " | Online servers: " + online
							+ " | Ongoing games: " + games_going).color(NamedTextColor.WHITE));
		} else {
			return Component.text("Server status: Registered servers: " + registerd + " | Online servers: " + online
					+ " | Ongoing games: " + games_going).color(NamedTextColor.WHITE);
		}
	}

}

class KnockoffServer {
	public final RegisteredServer rs;
	private boolean is_game_going;
	public boolean is_online;
	private Set<String> playing_players;

	public KnockoffServer(RegisteredServer rs, ProxyServer server, Velocity_plugin plugin) {
		this.rs = rs;
		is_game_going = false;
		this.playing_players = null;

		// this updates the is_online state regularly
		server.getScheduler().buildTask(plugin, () -> {
			try {
				rs.ping().get(5, TimeUnit.SECONDS);
				is_online = true;
			} catch (Exception e) {
				is_online = false;
			}
		}).repeat(15, TimeUnit.SECONDS)
				.schedule();
	}

	public void start_game(Set<String> playing_players) {
		this.is_game_going = true;
		this.playing_players = playing_players;
	}

	public void game_end() {
		this.is_game_going = false;
		this.playing_players = null;
	}

	public boolean is_going() {
		return is_game_going;
	}

	public boolean contains_player(Player p) {
		if (playing_players != null && playing_players.contains(p.getGameProfile().getName())) {
			return true;
		}
		return false;
	}
}
