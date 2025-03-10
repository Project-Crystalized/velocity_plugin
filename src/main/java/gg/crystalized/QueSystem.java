package gg.crystalized;

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

public class QueSystem {
	public static GameQue ls_que;
	public static GameQue ko_que;
	private Velocity_plugin plugin;
	private ProxyServer server;

	public static final int LS_NEEDED_TO_START = 6;
	public static final int KO_NEEDED_TO_START = 4;

	public static final int LS_MAX_PLAYERS = 8;
	public static final int KO_MAX_PLAYERS = 16;

	public QueSystem(ProxyServer server, Velocity_plugin plugin) {
		this.plugin = plugin;
		ls_que = new GameQue(this, plugin, LS_MAX_PLAYERS);
		ko_que = new GameQue(this, plugin, KO_MAX_PLAYERS);
		this.server = server;

		server.getScheduler().buildTask(plugin, () -> {
			show_action_bars();

			if (ls_que.size() >= LS_NEEDED_TO_START && ls_que.size() % 2 == 0) {
				ls_que.send_players(plugin.ls_selector);
			}
			if (ko_que.size() >= KO_NEEDED_TO_START) {
				ko_que.send_players(plugin.ko_selector);
			}
		}).repeat(1, TimeUnit.SECONDS).schedule();

	}

	public void remove_player_from_que(Player p) {
		if (ls_que.remove_player(p) || ko_que.remove_player(p)) {
			p.createConnectionRequest(server.getServer("lobby").get()).connect();
		}
		Party party = plugin.party_system.get_party_of(p);
		if (party != null) {
			for (Player player : party.members) {
				if (ls_que.remove_player(player) || ko_que.remove_player(player)) {
					player.createConnectionRequest(server.getServer("lobby").get()).connect();
				}
			}
		}
	}

	public boolean is_in_a_que(Player p) {
		if (ls_que.contains(p) || ko_que.contains(p)) {
			p.sendMessage(text("You are already queued for a game"));
			return true;
		}
		return false;
	}

	private void show_action_bars() {
		ls_que.get_players().sendActionBar(text("You are in queue for Litestrike! ")
				.append(text("(" + ls_que.size() + "/" + LS_NEEDED_TO_START + ") "))
				.append(text("Run /unque to leave the queue")));
		ko_que.get_players().sendActionBar(text("You are in queue for Knockoff! ")
				.append(text("(" + ko_que.size() + "/" + KO_NEEDED_TO_START + ") "))
				.append(text("Run /unque to leave the queue")));
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
	private int max_players;

	public GameQue(QueSystem qs, Velocity_plugin plugin, int max_players) {
		this.qs = qs;
		this.plugin = plugin;
		this.max_players = max_players;
	}

	public Audience get_players() {
		return Audience.audience(players);
	}

	public int size() {
		return players.size();
	}

	public boolean contains(Player p) {
		return players.contains(p);
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
			if (party.members.size() + players.size() > max_players) {
				p.sendMessage(text("You party is too large to join the current que, please wait for a bit."));
				return;
			}
			players.addAll(party.members);
			Audience.audience(party.members).sendMessage(text("Your party has entered the que"));
		} else {
			if (players.size() >= max_players) {
				p.sendMessage(text("The que is already full, a game is starting rn. Please wait a few seconds."));
				return;
			}
			players.add(p);
		}
	}

	public boolean remove_player(Player p) {
		return players.remove(p);
	}

	public void clear() {
		players.clear();
	}

	public void send_players(ServerSelector ss) {
		for (Player p : players) {
			ss.send_player(p);
		}
	}
}
