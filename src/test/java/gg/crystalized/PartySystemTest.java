package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

class PartySystemTest {
	ProxyServer proxy;
	GameQueue queue;

	@BeforeEach
	void setup() {
		QueueSystem.queues.clear();
		QueueSystem.lastGameQueue.clear();
		Velocity_plugin.logger = Mocks.mock(Logger.class);
		FakeScheduler scheduler = new FakeScheduler();
		proxy = Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", List.of(), "getScheduler", scheduler, "getCommandManager",
						Mocks.mock(CommandManager.class)));
		new QueueSystem(proxy, null);
		queue = QueueSystem.getQueue(QueueSystem.queueTypes.knockoff);
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), QueueSystem.queueTypes.knockoff);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		queue.servers.add(backend);
	}

	Player player(String name) {
		return Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", true,
				"getCurrentServer", Optional.empty(), "getUsername", name));
	}

	ProxyServer serverWith(Player target) {
		return Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", List.of(), "getScheduler", new FakeScheduler(), "getCommandManager",
						Mocks.mock(CommandManager.class), "getPlayer",
						target == null ? Optional.empty() : Optional.of(target)));
	}

	PartySystem partyWith(Player... members) {
		PartySystem ps = new PartySystem(proxy, null);
		Party party = new Party(members[0]);
		for (int i = 1; i < members.length; i++) {
			party.members.add(members[i]);
		}
		ps.partys.add(party);
		return ps;
	}

	void execute(PartySystem ps, ProxyServer server, Player source, String... args) {
		PartyCommand command = new PartyCommand(ps, null, server);
		SimpleCommand.Invocation invocation = Mocks.mock(SimpleCommand.Invocation.class,
				Map.of("source", source, "arguments", args));
		command.execute(invocation);
	}

	@Test
	void disbandDequeuesEveryMember() {
		Player leader = player("leader");
		Player member = player("member1");
		Player member2 = player("member2");
		PartySystem ps = partyWith(leader, member, member2);
		queue.addPlayerToQueue(leader);
		queue.addPlayerToQueue(member);
		queue.addPlayerToQueue(member2);
		assertEquals(3, queue.players.size());
		execute(ps, proxy, leader, "disband");
		assertTrue(queue.players.isEmpty());
	}

	@Test
	void leaveRemovesLeaverFromPartyAndQueue() {
		Player leader = player("leader");
		Player member = player("member1");
		PartySystem ps = partyWith(leader, member);
		queue.addPlayerToQueue(member);
		execute(ps, proxy, member, "leave");
		assertEquals(List.of(leader), ps.get_party_of(leader).members);
		assertFalse(queue.players.contains(member));
	}

	@Test
	void kickRemovesMentionedPlayerKeepsRest() {
		Player leader = player("leader");
		Player member = player("member1");
		Player member2 = player("member2");
		PartySystem ps = partyWith(leader, member, member2);
		queue.addPlayerToQueue(member);
		execute(ps, serverWith(member), leader, "kick", "member1");
		assertEquals(List.of(leader, member2), ps.get_party_of(leader).members);
		assertFalse(queue.players.contains(member));
	}

	@Test
	void joinAddsJoinerToPartyAndSyncsLeaderQueue() {
		Player leader = player("leader");
		Player joiner = player("joiner");
		PartySystem ps = partyWith(leader);
		ps.get_party_of(leader).invited.add("joiner");
		queue.addPlayerToQueue(leader);
		execute(ps, serverWith(leader), joiner, "join", "leader");
		assertEquals(List.of(leader, joiner), ps.get_party_of(leader).members);
		assertTrue(queue.players.contains(joiner));
	}

	@Test
	void promoteSwapsLeader() {
		Player leader = player("leader");
		Player member = player("member1");
		PartySystem ps = partyWith(leader, member);
		execute(ps, serverWith(member), leader, "leader", "member1");
		assertEquals(member, ps.get_party_of(leader).members.get(0));
		assertEquals(leader, ps.get_party_of(leader).members.get(1));
	}

	@Test
	void removePlayerDissolvesSingleMemberParty() {
		Player leader = player("leader");
		PartySystem ps = partyWith(leader);
		ps.remove_player(leader);
		assertNull(ps.get_party_of(leader));
		assertTrue(ps.partys.isEmpty());
	}

	@Test
	void removePlayerPromotesSecondMemberOnLeaderLeave() {
		Player leader = player("leader");
		Player member = player("member1");
		Player member2 = player("member2");
		PartySystem ps = partyWith(leader, member, member2);
		ps.remove_player(leader);
		assertEquals(List.of(member, member2), ps.get_party_of(member).members);
		assertTrue(ps.get_party_of(member).is_leader(member));
	}

	@Test
	void removePlayerRemovesOrdinaryMember() {
		Player leader = player("leader");
		Player member = player("member1");
		Player member2 = player("member2");
		PartySystem ps = partyWith(leader, member, member2);
		ps.remove_player(member);
		assertEquals(List.of(leader, member2), ps.get_party_of(leader).members);
	}
}
