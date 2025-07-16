package gg.crystalized;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.BOLD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;

public class PartySystem {
	public Set<Party> partys = ConcurrentHashMap.newKeySet();

	public PartySystem(ProxyServer server, Velocity_plugin plugin) {
		CommandManager commandManager = server.getCommandManager();
		CommandMeta commandMetaParty =
		commandManager.metaBuilder("party").aliases("p").plugin(plugin).build();
		commandManager.register(commandMetaParty, new PartyCommand(this, plugin,
		server));
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
		Audience.audience(party.members)
				.sendMessage(text("Player \"" + p.getUsername() + "\" has left the party"));
		party.members.remove(p);
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

	public ByteArrayDataOutput update_message() {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF("Party");
		for (Player p : members) {
			out.writeUTF(p.getUsername());
		}
		return out;
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
			return Arrays.asList("list", "invite", "inv", "add", "accept", "join", "kick",
					"remove", "disband", "leader", "transfer", "promote", "leave");
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
			return Arrays.asList("list", "invite", "inv", "add", "accept", "join", "kick",
					"remove", "disband", "leader", "transfer", "promote", "leave");
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

		// for commands that require a player to be specified, get the player
		Player mentioned_player = null;
		if (args[0].equals("invite") || args[0].equals("inv") || args[0].equals("add")
				|| args[0].equals("join") || args[0].equals("accept") || args[0].equals("kick") || args[0].equals("remove")
				|| args[0].equals("leader") || args[0].equals("transfer") || args[0].equals("promote")) {
			if (args.length < 2) {
				invocation.source().sendMessage(text("Please specify a player").color(RED));
				return;
			}
			mentioned_player = server.getPlayer(args[1]).orElse(null);
			if (mentioned_player == null) {
				invocation.source().sendMessage(text("Couldnt find Player \"" + args[1] + "\"").color(RED));
				return;
			}
		}

		Party party = ps.get_party_of(executer);

		// for commands that require you in a party, check that you are
		if (args[0].equals("list") || args[0].equals("disband") || args[0].equals("leave") || args[0].equals("remove")
				|| args[0].equals("kick") || args[0].equals("leader") || args[0].equals("transfer")
				|| args[0].equals("promote")) {
			if (party == null) {
				invocation.source().sendMessage(text("You are not in a party").color(RED));
				return;
			}
		}

		// for commands that require you to be leader, check that you are
		if (args[0].equals("disband") || args[0].equals("remove") || args[0].equals("kick")
				|| args[0].equals("leader") || args[0].equals("transfer") || args[0].equals("promote")) {
			if (!party.is_leader(executer)) {
				invocation.source().sendMessage(text("You are not the leader of the party").color(RED));
				return;
			}
		}

		if (args[0].equals("list")) {
			invocation.source().sendMessage(party.render());

		} else if (args[0].equals("invite") || args[0].equals("inv") || args[0].equals("add")) {
			if (party == null) {
				invocation.source().sendMessage(text("Creating a new party").color(TextColor.color((float)250,(float) 180,(float) 245)));
				party = new Party(executer);
				ps.partys.add(party);
			}
			if (!party.is_leader(executer)) {
				invocation.source().sendMessage(text("You are not the leader of the party").color(RED));
				return;
			}
			Audience.audience(party.members).sendMessage(text("Player \"" + args[1] + "\" has been invited to your Party").color(TextColor.fromHexString("#f299da")));
			party.invited.remove(args[1]);
			party.invited.add(args[1]);
			Component accept = translatable("crystalized.generic.accept").color(GREEN).decoration(BOLD, true).clickEvent(ClickEvent.runCommand("/party join " + executer.getUsername()));
			mentioned_player.sendMessage(
					text("You have been invited to join the party of " + (executer).getUsername()).color(TextColor.fromHexString("#f299da")).append(accept));

		} else if (args[0].equals("join") || args[0].equals("accept")) {
			if (party != null) {
				invocation.source().sendMessage(text("You are already in a party").color(RED));
				return;
			}
			Party party_to_join = ps.get_party_of(server.getPlayer(args[1]).orElse(null));
			if (party_to_join == null) {
				invocation.source().sendMessage(text("This player is currently not in a party").color(RED));
				return;
			}
			if (!party_to_join.invited.contains(executer.getUsername())) {
				executer.sendMessage(text("You have not been invited to that party").color(RED));
				return;
			}
			Audience.audience(party_to_join.members).sendMessage(text("Player \"" + mentioned_player.getUsername() + "\" has joined your Party").color(TextColor.fromHexString("#f299da")));
			executer.sendMessage(text("You have joined the party of " + args[1]).color(TextColor.fromHexString("#f299da")));
			party_to_join.members.add(executer);
			plugin.que_system.remove_player_from_que(executer);
			if (QueSystem.ls_que.contains(party_to_join.members.get(0))) {
				QueSystem.ls_que.add_player(executer);
			} else if (QueSystem.ko_que.contains(party_to_join.members.get(0))) {
				QueSystem.ko_que.add_player(executer);
			}

		} else if (args[0].equals("kick") || args[0].equals("remove")) {
			if (mentioned_player == executer) {
				invocation.source().sendMessage(text("You cant kick yourself").color(RED));
				return;
			}
			if (!party.members.contains(mentioned_player)) {
				invocation.source().sendMessage(text("Player \"" + args[1] + "\" was not in your party").color(RED));
				return;
			}
			Audience.audience(party.members)
					.sendMessage(text("Kicking Player \"" + args[1] + "\" from your party").color(TextColor.fromHexString("#f299da")));
			party.invited.remove(args[1]);
			ps.remove_player(mentioned_player);
			plugin.que_system.remove_player_from_que(mentioned_player);

		} else if (args[0].equals("disband")) {
			Audience.audience(party.members).sendMessage(text("Your party has been disbanded").color(TextColor.fromHexString("#f299da")));
			ps.partys.remove(party);
			for (Player p : party.members) {
				plugin.que_system.remove_player_from_que(p);
			}

		} else if (args[0].equals("leave")) {
			ps.remove_player(executer);
			plugin.que_system.remove_player_from_que(executer);

		} else if (args[0].equals("leader") || args[0].equals("transfer") || args[0].equals("promote")) {
			int promoted_index = party.members.indexOf(mentioned_player);
			Collections.swap(party.members, 0, promoted_index);
			Audience.audience(party.members)
					.sendMessage(text("Promoting \"" + args[1] + "\" to party leader").color(TextColor.fromHexString("#f299da")));
		}

		// we are here if the command succeded, so we now update the party
		if (party != null) {
			plugin.ls_selector.send_party_update(party);
		}

	}
}
