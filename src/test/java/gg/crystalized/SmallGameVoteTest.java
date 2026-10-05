package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class SmallGameVoteTest {
	ProxyServer proxy;
	FakeScheduler scheduler;
	GameQueue queue;
	CommandDispatcher<CommandSource> dispatcher;

	@BeforeEach
	void setup() {
		QueueSystem.queues.clear();
		QueueSystem.lastGameQueue.clear();
		Velocity_plugin.logger = Mocks.mock(Logger.class);
		scheduler = new FakeScheduler();
		proxy = Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", List.of(), "getScheduler", scheduler, "getCommandManager",
						Mocks.mock(CommandManager.class)));
		new QueueSystem(proxy, null);
		queue = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike);
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), QueueSystem.queueTypes.litestrike);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		queue.servers.add(backend);
		dispatcher = new CommandDispatcher<>();
		dispatcher.getRoot().addChild(QueueCommand.createBrigadierCommand(proxy).getNode());
	}

	Player voter(List<List<Object[]>> inboxes) {
		ConnectionRequestBuilder.Result result = Mocks.mock(ConnectionRequestBuilder.Result.class,
				Map.of("isSuccessful", true));
		ConnectionRequestBuilder builder = Mocks.mock(ConnectionRequestBuilder.class,
				Map.of("connect", CompletableFuture.completedFuture(result)));
		List<Object[]> calls = new ArrayList<>();
		Map<String, Object> stubs = new HashMap<>();
		stubs.put("getUniqueId", UUID.randomUUID());
		stubs.put("isActive", true);
		stubs.put("getUsername", "voter");
		stubs.put("getCurrentServer", Optional.empty());
		stubs.put("createConnectionRequest", builder);
		Player p = Mocks.recording(Player.class, calls, stubs);
		inboxes.add(calls);
		return p;
	}

	void tick(int seconds) {
		for (int i = 0; i < seconds; i++) {
			for (Runnable task : scheduler.repeatTasks(1, TimeUnit.SECONDS)) {
				task.run();
			}
		}
	}

	static boolean messaged(List<Object[]> calls, String fragment) {
		return calls.stream().anyMatch(c -> c[0].equals("sendMessage") && ((Object[]) c[1])[0].toString().contains(fragment));
	}

	static boolean called(List<Object[]> calls, String method) {
		return calls.stream().anyMatch(c -> c[0].equals(method));
	}

	void vote(Player p, String choice) throws Exception {
		dispatcher.execute("queue vote " + choice, p);
	}

	@Test
	void fourWaitingSixtySecondsPrompts() {
		List<List<Object[]>> inboxes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			queue.addPlayerToQueue(voter(inboxes));
		}
		tick(60);
		for (List<Object[]> inbox : inboxes) {
			assertTrue(messaged(inbox, "smallgame.ask"));
		}
	}

	@Test
	void unanimousYesStartsGame() throws Exception {
		List<Player> voters = new ArrayList<>();
		List<List<Object[]>> inboxes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Player p = voter(inboxes);
			voters.add(p);
			queue.addPlayerToQueue(p);
		}
		tick(60);
		for (Player p : voters) {
			vote(p, "yes");
		}
		tick(1);
		assertTrue(queue.players.isEmpty());
		for (List<Object[]> inbox : inboxes) {
			assertTrue(called(inbox, "createConnectionRequest"));
		}
	}

	@Test
	void singleNoFailsVote() throws Exception {
		List<Player> voters = new ArrayList<>();
		List<List<Object[]>> inboxes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Player p = voter(inboxes);
			voters.add(p);
			queue.addPlayerToQueue(p);
		}
		tick(60);
		vote(voters.get(0), "no");
		tick(1);
		assertEquals(4, queue.players.size());
		for (List<Object[]> inbox : inboxes) {
			assertTrue(messaged(inbox, "smallgame.failed"));
			assertFalse(called(inbox, "createConnectionRequest"));
		}
	}

	@Test
	void timeoutFailsVote() throws Exception {
		List<Player> voters = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Player p = voter(new ArrayList<>());
			voters.add(p);
			queue.addPlayerToQueue(p);
		}
		tick(60);
		for (int i = 0; i < 3; i++) {
			vote(voters.get(i), "yes");
		}
		tick(20);
		assertEquals(4, queue.players.size());
	}

	@Test
	void rankedQueueNeverPrompts() {
		GameQueue ranked = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked);
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), QueueSystem.queueTypes.litestrike_ranked);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		ranked.servers.add(backend);
		List<List<Object[]>> inboxes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			ranked.addPlayerToQueue(voter(inboxes));
		}
		tick(70);
		for (List<Object[]> inbox : inboxes) {
			assertFalse(messaged(inbox, "smallgame.ask"));
		}
	}

	@Test
	void membershipChurnResetsWait() {
		List<Player> voters = new ArrayList<>();
		List<List<Object[]>> inboxes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Player p = voter(inboxes);
			voters.add(p);
			queue.addPlayerToQueue(p);
		}
		tick(59);
		queue.players.remove(voters.get(0));
		tick(1);
		queue.addPlayerToQueue(voter(inboxes));
		tick(59);
		for (List<Object[]> inbox : inboxes) {
			assertFalse(messaged(inbox, "smallgame.ask"));
		}
	}
}
