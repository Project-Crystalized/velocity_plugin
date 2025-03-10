package gg.crystalized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import static net.kyori.adventure.text.Component.text;

public class Litestrike_Selector implements ServerSelector {

	private List<LitestrikeServer> ls_servers = new ArrayList<>();
	private LitestrikeServer selected_server;

	private Velocity_plugin plugin;
	private ProxyServer server;

	public Litestrike_Selector(ProxyServer server, Velocity_plugin plugin) {
		this.plugin = plugin;
		this.server = server;
		for (RegisteredServer rs : server.getAllServers()) {
			if (rs.getServerInfo().getName().startsWith("litestrike")) {
				ls_servers.add(new LitestrikeServer(rs, server, plugin));
			}
		}
		select_new_server();

		// stop server when no player is connected to them
		server.getScheduler().buildTask(plugin, () -> {
			for (LitestrikeServer lss : ls_servers) {
				if (lss.rs.getPlayersConnected().size() == 0 && lss.is_going()) {
					server.getScheduler().buildTask(plugin, () -> lss.game_end()).delay(21, TimeUnit.SECONDS)
							.schedule();
					Velocity_plugin.logger.info("Scheduled a litestrike server to become ready again");
				}
			}
		}).repeat(22, TimeUnit.SECONDS).schedule();

		// tell the server how many people are in que
		server.getScheduler().buildTask(plugin, () -> {
			if (QueSystem.ls_que == null || selected_server == null) {
				return;
			}
			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeUTF("queue_size");
			out.writeInt(QueSystem.ls_que.size());
			selected_server.rs.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
		}).repeat(5, TimeUnit.SECONDS).schedule();
	}

	public void send_player(Player p) {
		if (selected_server == null || selected_server.is_going() || !selected_server.is_online) {
			select_new_server();
		}
		if (selected_server == null) {
			p.sendMessage(get_servers_status());
			return;
		}
		Party party = plugin.party_system.get_party_of(p);
		if (party != null) {
			if (!party.is_leader(p)) {
				p.sendMessage(text("You must be the party leader to join the que"));
				return;
			}
			for (Player player : party.members) {
				player.createConnectionRequest(selected_server.rs).connect();
			}

			// this is a ugly hack, the first party to join wouldnt be sent,
			// becuase no player is connected yet, so we delay party info sending
			server.getScheduler().buildTask(plugin, () -> {
				selected_server.rs.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, party.update_message().toByteArray());
			}).delay(2, TimeUnit.SECONDS).schedule();
		} else {
			p.createConnectionRequest(selected_server.rs).connect();
		}
	}

	public void send_party_update(Party party) {
		if (selected_server != null) {
			selected_server.rs.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, party.update_message().toByteArray());
		}
	}

	// this is weird, but removes the party from the game_server
	// i should prolly write down how the game servers are supposed to react
	public void send_remove_party(Party party) {
		for (Player p : party.members) {
			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeUTF("Party");
			out.writeUTF(p.getUsername());
			selected_server.rs.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
		}
	}

	@Subscribe
	public void onPluginMessageFromBackend(PluginMessageEvent event) {
		if (!Velocity_plugin.LS_CHANNEL.equals(event.getIdentifier())) {
			return;
		}
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (!(event.getSource() instanceof ServerConnection backend)) {
			return;
		}
		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());

		String message = in.readUTF();
		if (message.contains("start_game")) {
			// Velocity_plugin.logger.error("received plugin message");

			Set<String> playing_players = new HashSet<>();
			while (true) {
				try {
					playing_players.add(in.readUTF());
				} catch (Exception e) {
					break;
				}
			}
			for (LitestrikeServer lss : ls_servers) {
				if (lss.rs == backend.getServer()) {
					lss.start_game(playing_players);
					select_new_server();
					QueSystem.ls_que.clear();
					return;
				}
			}
		}
	}

	private void select_new_server() {
		selected_server = null;
		Collections.shuffle(ls_servers);
		for (LitestrikeServer lss : ls_servers) {
			if (!lss.is_going() && lss.is_online) {
				selected_server = lss;
				return;
			}
		}
		if (Velocity_plugin.logger != null) {
			Velocity_plugin.logger.error("No Litestrike servers are available at the Moment");
		}
	}

	public RegisteredServer get_server_of(Player p) {
		for (LitestrikeServer lss : ls_servers) {
			if (lss.contains_player(p)) {
				return lss.rs;
			}
		}
		return null;
	}

	public Component get_servers_status() {
		int registerd = 0;
		int online = 0;
		int games_going = 0;

		for (LitestrikeServer lss : ls_servers) {
			registerd += 1;
			if (lss.is_online) {
				online += 1;
			}
			if (lss.is_going()) {
				games_going += 1;
			}
		}

		if (online == games_going) {
			return Component.text("Server status: No Litestrike Server is available atm. \n").color(NamedTextColor.RED)
					.append(Component.text("Registered servers: " + registerd + " | Online servers: " + online
							+ " | Ongoing games: " + games_going).color(NamedTextColor.WHITE));
		} else {
			return Component.text("Server status: Registered servers: " + registerd + " | Online servers: " + online
					+ " | Ongoing games: " + games_going).color(NamedTextColor.WHITE);
		}
	}
}

class LitestrikeServer {
	public final RegisteredServer rs;
	private boolean is_game_going;
	public boolean is_online;
	private Set<String> playing_players;

	public LitestrikeServer(RegisteredServer rs, ProxyServer server, Velocity_plugin plugin) {
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

		// Velocity_plugin.logger.error("starting game with:");
		// for (String name : playing_players) {
		// 	Velocity_plugin.logger.error(name);
		// }
	}

	public void game_end() {
		this.is_game_going = false;
		this.playing_players = null;
		// Velocity_plugin.logger.error("end game:");
	}

	public boolean is_going() {
		return is_game_going;
	}

	public boolean contains_player(Player p) {
		// Velocity_plugin.logger.error("player trying to rejoin: " + p.getGameProfile().getName());
		// Velocity_plugin.logger.error("playing players:");
		// if (playing_players != null) {
		// 	for (String name : playing_players) {
		// 		Velocity_plugin.logger.error(name);
		// 	}
		// } else {
		// 	Velocity_plugin.logger.error("playing_players is null");
		// }
		// Velocity_plugin.logger.error("");
		if (playing_players != null && playing_players.contains(p.getGameProfile().getName())) {
			return true;
		}
		return false;
	}
}
