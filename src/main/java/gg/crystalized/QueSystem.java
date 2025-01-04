package gg.crystalized;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.format.NamedTextColor;

import static net.kyori.adventure.text.Component.text;

import org.slf4j.Logger;

public class QueSystem {
	private Logger logger;
	private Set<Player> ls_que = ConcurrentHashMap.newKeySet();
	private Set<Player> ko_que = ConcurrentHashMap.newKeySet();

	public static final int LS_NEEDED_TO_START = 6;
	public static final int KO_NEEDED_TO_START = 4;

	public QueSystem(ProxyServer server, Logger logger, Velocity_plugin plugin) {
		this.logger = logger;

		server.getScheduler().buildTask(plugin, () -> {
			show_action_bars();

			if (ls_que.size() >= LS_NEEDED_TO_START) {
				for (Player p : ls_que) {
					plugin.ls_selector.send_player_litestrike(p);
				}
			}
			if (ko_que.size() >= KO_NEEDED_TO_START) {
				for (Player p : ko_que) {
					// TODO
				}
			}
		}).repeat(1, TimeUnit.SECONDS).schedule();

	}

	public void clear_ls_que() {
		ls_que.clear();
	}

	public void clear_ko_que() {
		ko_que.clear();
	}

	public void add_player_ls(Player p) {
		if (is_in_a_que(p)) {
			return;
		}
		ls_que.add(p);
	}

	public void add_player_ko(Player p) {
		if (is_in_a_que(p)) {
			return;
		}
		ko_que.add(p);
	}

	public void remove_player_from_que(Player p) {
		ls_que.remove(p);
		ko_que.remove(p);
	}

	private boolean is_in_a_que(Player p) {
		if (ls_que.contains(p) || ko_que.contains(p)) {
			p.sendMessage(text("You are already qued for a game"));
			return true;
		}
		return false;
	}

	private void show_action_bars() {
		for (Player p : ls_que) {
			p.sendActionBar(text("You are in que for Litestrike! ")
					.append(text("(" + ls_que.size() + "/" + LS_NEEDED_TO_START + ") "))
					.append(text("Run /unque to leave the que")));
		}
		for (Player p : ko_que) {
			p.sendActionBar(text("You are in que for Knockoff! ")
					.append(text("(" + ko_que.size() + "/" + KO_NEEDED_TO_START + ") "))
					.append(text("Run /unque to leave the que")));
		}
	}
}

class UnqueCommand implements SimpleCommand {
	private Velocity_plugin plugin;

	public UnqueCommand(Velocity_plugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void execute(Invocation invocation) {
		if (!(invocation.source() instanceof Player)) {
			invocation.source().sendMessage(text("Only players can execute this command!").color(NamedTextColor.RED));
			return;
		}
		plugin.que_system.remove_player_from_que((Player) invocation.source());
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
