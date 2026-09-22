package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

class QueueMembershipTest {
	ProxyServer proxy;
	FakeScheduler scheduler;
	GameQueue queue;

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
		queue = QueueSystem.getQueue(QueueSystem.queueTypes.knockoff);
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), QueueSystem.queueTypes.knockoff);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		queue.servers.add(backend);
	}

	Player player(boolean active) {
		return Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", active,
				"getCurrentServer", Optional.empty()));
	}

	void runAllTimers() {
		for (Runnable task : scheduler.repeatTasks(1, TimeUnit.SECONDS)) {
			task.run();
		}
	}

	@Test
	void duplicateAddIsNoOp() {
		Player p = player(true);
		queue.addPlayerToQueue(p);
		queue.addPlayerToQueue(p);
		assertEquals(1, queue.players.size());
	}

	@Test
	void sweepRemovesInactiveKeepsActive() {
		Player active = player(true);
		Player ghost = player(false);
		queue.addPlayerToQueue(active);
		queue.addPlayerToQueue(ghost);
		assertEquals(2, queue.players.size());
		runAllTimers();
		assertEquals(1, queue.players.size());
		assertTrue(queue.players.contains(active));
	}

	@Test
	void removeAbsentIsSilent() {
		Player stranger = player(true);
		queue.removePlayerToQueue(stranger);
		assertEquals(0, queue.players.size());
	}

	@Test
	void removePresentRemoves() {
		Player p = player(true);
		queue.addPlayerToQueue(p);
		queue.removePlayerToQueue(p);
		assertFalse(queue.players.contains(p));
	}

	@Test
	void fullQueueRejects() {
		for (int i = 0; i < 12; i++) {
			queue.addPlayerToQueue(player(true));
		}
		Player extra = player(true);
		queue.addPlayerToQueue(extra);
		assertEquals(12, queue.players.size());
		assertFalse(queue.players.contains(extra));
	}

	@Test
	void noAvailableServerDoesNotAdd() {
		queue.servers.clear();
		Player p = player(true);
		queue.addPlayerToQueue(p);
		assertEquals(0, queue.players.size());
	}

	@Test
	void sendRemovesFromListAndRecordsHistory() {
		Player a = player(true);
		Player b = player(true);
		queue.addPlayerToQueue(a);
		queue.addPlayerToQueue(b);
		queue.sendAllPlayersToServer(QueueSystem.queueTypes.knockoff);
		assertTrue(queue.players.isEmpty());
		assertEquals(QueueSystem.queueTypes.knockoff, QueueSystem.lastGameQueue.get(a.getUniqueId()));
		assertEquals(QueueSystem.queueTypes.knockoff, QueueSystem.lastGameQueue.get(b.getUniqueId()));
	}

	@Test
	void countdownFiresAtThreshold() {
		Player a = player(true);
		Player b = player(true);
		Player c = player(true);
		queue.addPlayerToQueue(a);
		queue.addPlayerToQueue(b);
		queue.addPlayerToQueue(c);
		for (int i = 0; i < 16; i++) {
			runAllTimers();
		}
		assertTrue(queue.players.isEmpty());
		assertEquals(QueueSystem.queueTypes.knockoff, QueueSystem.lastGameQueue.get(a.getUniqueId()));
	}
}
