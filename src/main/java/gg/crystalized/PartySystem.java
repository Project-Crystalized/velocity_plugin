package gg.crystalized;

import static net.kyori.adventure.text.Component.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import org.slf4j.Logger;

public class PartySystem {
	public Set<Party> partys = ConcurrentHashMap.newKeySet();

	public PartySystem(ProxyServer server, Logger logger, Velocity_plugin plugin) {
		CommandManager commandManager = server.getCommandManager();
		CommandMeta commandMetaParty = commandManager.metaBuilder("party").aliases("p").plugin(plugin).build();
		commandManager.register(commandMetaParty, new PartyCommand(this, plugin, server));
	}

	public Party get_party_of(Player p) {
		for (Party party : partys) {
			if (party.members.contains(p)) {
				return party;
			}
		}
		return null;
	}

	public void remove_player(Player p) {
		if (p == null) {
			return;
		}
		Party party = get_party_of(p);
		if (party == null) {
			return;
		}
		if (party.members.get(0) == p) {
			Audience.audience(party.members).sendMessage(text("The party leader has left"));
			if (party.members.size() == 1) {
				partys.remove(party);
				return;
			}
			Audience.audience(party.members)
					.sendMessage(text(party.members.get(1).getUsername() + " is the new party leader"));
		}
		party.members.remove(p);
		Audience.audience(party.members)
				.sendMessage(text("Player \"" + p.getUsername() + "\" has left the party"));
	}
}

class Party {
	public final int id;
	public List<Player> members = new ArrayList<>();

	// this is never really cleared, so everyone invited once, can always join
	public List<String> invited = new ArrayList<>();

	public Party(Player p) {
		id = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE - 1);
		members.add(p);
	}

	public boolean is_leader(Player p) {
		return members.get(0) == p;
	}

	public Component render() {
		Component c = text("---------------------------\n")
				.append(text("Party Size: (" + members.size() + ")\n\n"))
				.append(text("Party Members: "));

		for (Player p : members) {
			c = c.append(text(p.getUsername() + ", "));
		}
		c = c.append(text("\nInvited Players: "));
		for (String s : invited) {
			c = c.append(text(s + ", "));
		}
		c = c.append(text("\n---------------------------"));
		return c;
	}
}

class PartyCommand implements SimpleCommand {
	private PartySystem ps;
	private Velocity_plugin plugin;
	private ProxyServer server;

	public PartyCommand(PartySystem ps, Velocity_plugin plugin, ProxyServer server) {
		this.ps = ps;
		this.plugin = plugin;
		this.server = server;
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return false;
		}
		return true;
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (!(invocation.source() instanceof Player)) {
			return List.of();
		}
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return Arrays.asList("list", "invite", "inv", "add", "accept", "join", "kick", "remove", "disband", "leader",
					"transfer", "promote");
		}
		if (args[0].equals("list") || args[0].equals("leave") || args[0].equals("disband")) {
			return List.of();
		}
		if (args[0].equals("kick") || args[0].equals("remove") || args[0].equals("leader") || args[0].equals("transfer")
				|| args[0].equals("promote")) {
			Party party = ps.get_party_of((Player) invocation.source());
			if (party == null) {
				return List.of();
			} else {
				List<String> party_list = new ArrayList<>();
				for (Player p : party.members) {
					party_list.add(p.getUsername());
				}
				return party_list;
			}
		}
		if (args[0].equals("invite") || (args[0].equals("inv")) || (args[0].equals("add")) || (args[0].equals("accept"))
				|| (args[0].equals("join"))) {
			List<String> all_players = new ArrayList<>();
			for (Player p : server.getAllPlayers()) {
				all_players.add(p.getUsername());
			}
			return all_players;
		}
		if (args.length == 1) {
			return Arrays.asList("list", "invite", "inv", "add", "accept", "join", "kick", "remove", "disband", "leader",
					"transfer", "promote");
		}
		return List.of();
	}

	@Override
	public void execute(Invocation invocation) {
		String[] args = invocation.arguments();
		Player executer = (Player) invocation.source();
		if (args.length == 0) {
			return;
		}

		Party party = ps.get_party_of(executer);
		if (args[0].equals("list")) {
			if (party == null) {
				invocation.source().sendMessage(text("You are not in a party"));
			} else {
				invocation.source().sendMessage(party.render());
			}
		} else if (args[0].equals("invite") || args[0].equals("inv") || args[0].equals("add")) {
			if (args.length < 2) {
				return;
			}
			if (party == null) {
				invocation.source().sendMessage(text("Creating a new party"));
				party = new Party(executer);
				ps.partys.add(party);
			}
			Player invited = server.getPlayer(args[1]).orElse(null);
			if (invited == null) {
				invocation.source().sendMessage(text("Couldnt find Player \"" + args[1] + "\""));
				return;
			}
			Audience.audience(party.members).sendMessage(text("Plyer \"" + args[1] + "\" has been invited to your Party"));
			party.invited.remove(args[1]);
			party.invited.add(args[1]);
			invited.sendMessage(
					text("You have been invited to join the party of " + (executer).getUsername() + "\n [Accept]")
							.clickEvent(ClickEvent.runCommand("/party join " + executer.getUsername())));
		} else if (args[0].equals("join") || args[0].equals("accept")) {
			if (args.length < 2) {
				return;
			}
			if (party != null) {
				invocation.source().sendMessage(text("You are already in a party"));
				return;
			}
			Party party_to_join = ps.get_party_of(server.getPlayer(args[1]).orElse(null));
			if (party_to_join == null) {
				invocation.source().sendMessage(text("This player is not in a party rn"));
				return;
			}
			if (!party_to_join.invited.contains(executer.getUsername())) {
				executer.sendMessage(text("You have not been invited to that party"));
				return;
			}
			Audience.audience(party_to_join.members).sendMessage(text("Player \"" + args[1] + "\" has joined your Party"));
			executer.sendMessage(text("You have joined the party of " + args[1]));
			party_to_join.members.add(executer);
			plugin.que_system.remove_player_from_que(executer);
			if (plugin.que_system.ls_que.get_players().contains(party_to_join.members.get(0))) {
				plugin.que_system.ls_que.add_player(executer);
			} else if (plugin.que_system.ko_que.get_players().contains(party_to_join.members.get(0))) {
				plugin.que_system.ko_que.add_player(executer);
			}
		} else if (args[0].equals("kick") || args[0].equals("remove")) {
			if (args.length < 2) {
				return;
			}
			if (!party.is_leader(executer)) {
				invocation.source().sendMessage(text("You are not the leader of the party"));
				return;
			}
			Player player_being_kicked = server.getPlayer(args[1]).orElse(null);
			if (player_being_kicked == null) {
				invocation.source().sendMessage(text("Couldnt find Player \"" + args[1] + "\""));
				return;
			}
			if (player_being_kicked == executer) {
				invocation.source().sendMessage(text("You cant kick yourself"));
				return;
			}
			if (!party.members.contains(player_being_kicked)) {
				invocation.source().sendMessage(text("Player \"" + args[1] + "\" was not in your party"));
				return;
			}
			Audience.audience(party.members)
					.sendMessage(text("Kicking Player \"" + args[1] + "\" from your party"));
			party.invited.remove(args[1]);
			ps.remove_player(player_being_kicked);
			plugin.que_system.remove_player_from_que(player_being_kicked);
		} else if (args[0].equals("disband")) {
			if (party == null) {
				invocation.source().sendMessage(text("You are not in a party"));
				return;
			}
			if (!party.is_leader(executer)) {
				invocation.source().sendMessage(text("You are not the leader of the party"));
				return;
			}
			Audience.audience(party.members).sendMessage(text("Your party has been disbanded"));
			ps.partys.remove(party);
			for (Player p : party.members) {
				plugin.que_system.remove_player_from_que(p);
			}
		} else if (args[0].equals("leave")) {
			if (party == null) {
				invocation.source().sendMessage(text("You are not in a party"));
				return;
			}
			ps.remove_player(executer);
			plugin.que_system.remove_player_from_que(executer);
		} else if (args[0].equals("leader") || args[0].equals("transfer") || args[0].equals("promote")) {
			if (args.length < 2) {
				return;
			}
			if (party == null) {
				invocation.source().sendMessage(text("You are not in a party"));
				return;
			}
			if (!party.is_leader(executer)) {
				invocation.source().sendMessage(text("You are not the leader of the party"));
				return;
			}
			Player promoted_player = server.getPlayer(args[1]).orElse(null);
			if (promoted_player == null) {
				invocation.source().sendMessage(text("Couldnt find Player \"" + args[1] + "\""));
				return;
			}
			int promoted_index = party.members.indexOf(promoted_player);
			Collections.swap(party.members, 0, promoted_index);
			Audience.audience(party.members)
					.sendMessage(text("Promoting \"" + args[1] + "\" to party leader"));
		}
	}
}
