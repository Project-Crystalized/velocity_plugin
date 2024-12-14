package gg.crystalized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

public class Litestrike_Selector {

	private List<LitestrikeServer> ls_servers = new ArrayList<>();
	private LitestrikeServer selected_server;

	private Logger logger;

	public Litestrike_Selector(ProxyServer server, Logger logger, Velocity_plugin plugin) {
		for (RegisteredServer rs : server.getAllServers()) {
			if (rs.getServerInfo().getName().startsWith("litestrike")) {
				ls_servers.add(new LitestrikeServer(rs));
			}
		}
		select_new_server();
		this.logger = logger;

		// stop server when no player is connected to them
		server.getScheduler().buildTask(plugin, () -> {
			for (LitestrikeServer lss : ls_servers) {
				if (lss.rs.getPlayersConnected().size() == 0 && lss.is_game_going == true) {
					server.getScheduler().buildTask(plugin, () -> lss.is_game_going = false).delay(21, TimeUnit.SECONDS)
							.schedule();
					logger.info("Scheduled a litestrike server to become ready again");
				}
			}
		}).repeat(22, TimeUnit.SECONDS).schedule();
	}

	public RegisteredServer get_selected() {
		if (selected_server.is_game_going) {
			select_new_server();
		}
		return selected_server.rs;
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
			for (LitestrikeServer lss : ls_servers) {
				if (lss.rs == backend.getServer()) {
					lss.is_game_going = true;
					select_new_server();
				}
			}
		}
	}

	private void select_new_server() {
		Collections.shuffle(ls_servers);
		for (LitestrikeServer lss : ls_servers) {
			try {
				if (!lss.is_game_going) {
					lss.rs.ping().get(3, TimeUnit.SECONDS);
					selected_server = lss;
					return;
				}
			} catch (Exception e) {
				continue;
			}
		}
		logger.error("No Litestrike servers are available at the Moment");
	}

}

class LitestrikeServer {
	public final RegisteredServer rs;
	public boolean is_game_going;

	public LitestrikeServer(RegisteredServer rs) {
		this.rs = rs;
		is_game_going = false;
	}
}
