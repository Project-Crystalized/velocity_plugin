package gg.crystalized;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.format.NamedTextColor;

import static net.kyori.adventure.text.Component.text;

import org.slf4j.Logger;

public class QueSystem {
	private Logger logger;
	public GameQue ls_que;
	public GameQue ko_que;
	private Velocity_plugin plugin;

	public static final int LS_NEEDED_TO_START = 6;
	public static final int KO_NEEDED_TO_START = 4;

	public QueSystem(ProxyServer server, Logger logger, Velocity_plugin plugin) {
		this.logger = logger;
		this.plugin = plugin;
		this.ls_que = new GameQue(this, plugin);
		this.ko_que = new GameQue(this, plugin);

		server.getScheduler().buildTask(plugin, () -> {
			show_action_bars();

			if (ls_que.get_players().size() >= LS_NEEDED_TO_START) {
				for (Player p : ls_que.get_players()) {
					plugin.ls_selector.send_player_litestrike(p);
				}
			}
			if (ko_que.get_players().size() >= KO_NEEDED_TO_START) {
				for (Player p : ko_que.get_players()) {
					plugin.ko_selector.send_player_knockoff(p);
				}
			}
		}).repeat(1, TimeUnit.SECONDS).schedule();

	}

	public void remove_player_from_que(Player p) {
		ls_que.remove_player(p);
		ko_que.remove_player(p);
		Party party = plugin.party_system.get_party_of(p);
		if (party != null) {
			for (Player player : party.members) {
				ls_que.remove_player(player);
				ko_que.remove_player(player);
			}
		}
	}

	public boolean is_in_a_que(Player p) {
		if (ls_que.get_players().contains(p) || ko_que.get_players().contains(p)) {
			p.sendMessage(text("You are already qued for a game"));
			return true;
		}
		return false;
	}

	private void show_action_bars() {
		for (Player p : ls_que.get_players()) {
			p.sendActionBar(text("You are in que for Litestrike! ")
					.append(text("(" + ls_que.get_players().size() + "/" + LS_NEEDED_TO_START + ") "))
					.append(text("Run /unque to leave the que")));
		}
		for (Player p : ko_que.get_players()) {
			p.sendActionBar(text("You are in que for Knockoff! ")
					.append(text("(" + ko_que.get_players().size() + "/" + KO_NEEDED_TO_START + ") "))
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

class GameQue {
	private QueSystem qs;
	private Set<Player> players = ConcurrentHashMap.newKeySet();
	private Velocity_plugin plugin;

	public GameQue(QueSystem qs, Velocity_plugin plugin) {
		this.qs = qs;
		this.plugin = plugin;
	}

	public Set<Player> get_players() {
		return Collections.unmodifiableSet(players);
	}

	public void add_player(Player p) {
		if (qs.is_in_a_que(p)) {
			return;
		}
		Party party = plugin.party_system.get_party_of(p);
		if (party != null) {
			if (!party.is_leader(p)) {
				p.sendMessage(text("You must be the party leader to join the que"));
				return;
			}
			players.addAll(party.members);
			Audience.audience(party.members).sendMessage(text("Your party has entered the que"));
		} else {
			players.add(p);
		}
	}

	public void remove_player(Player p) {
		players.remove(p);
	}

	public void clear() {
		players.clear();
	}
}
