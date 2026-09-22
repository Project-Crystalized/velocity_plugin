package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
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

	static String[] decodePluginMessage(byte[] data) {
		ByteArrayDataInput in = ByteStreams.newDataInput(data);
		return new String[] { in.readUTF(), in.readUTF() };
	}

	static byte[] sentBytes(List<Object[]> sent, int index) {
		return (byte[]) ((Object[]) sent.get(index)[1])[1];
	}

	static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
			Thread.sleep(10);
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
	void removePresentRemovesAndNotifies() {
		List<Object[]> sent = new ArrayList<>();
		Player p = Mocks.recording(Player.class, sent,
				Map.of("getUniqueId", UUID.randomUUID(), "isActive", true, "getCurrentServer", Optional.empty()));
		queue.addPlayerToQueue(p);
		sent.clear();
		queue.removePlayerToQueue(p);
		assertFalse(queue.players.contains(p));
		assertEquals(1, sent.stream().filter(call -> call[0].equals("sendMessage")).count());
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

	@Test
	void evenParityBlocksOddCountFiresEven() {
		GameQueue evens = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike);
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class),
				QueueSystem.queueTypes.litestrike);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		evens.servers.add(backend);
		for (int i = 0; i < 7; i++) {
			evens.addPlayerToQueue(player(true));
		}
		for (int i = 0; i < 20; i++) {
			runAllTimers();
		}
		assertEquals(7, evens.players.size());
		evens.addPlayerToQueue(player(true));
		for (int i = 0; i < 16; i++) {
			runAllTimers();
		}
		assertTrue(evens.players.isEmpty());
	}

	GameServer statusServer(Object pingResult, List<Player> connected) {		RegisteredServer server = Mocks.mock(RegisteredServer.class,
				Map.of("ping", pingResult, "getPlayersConnected", connected));
		GameServer backend = new GameServer(server, QueueSystem.queueTypes.knockoff);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		return backend;
	}

	@Test
	void statusOfflineOnPingFailure() throws InterruptedException {
		GameServer backend = statusServer(
				CompletableFuture.failedFuture(new RuntimeException("Connection refused")), List.of());
		backend.updateServerStatus();
		awaitCondition(() -> backend.available == QueueSystem.ServerStatus.OFFLINE);
		assertEquals(QueueSystem.ServerStatus.OFFLINE, backend.available);
	}

	@Test
	void statusAvailableWhenEmptiedAfterGame() throws InterruptedException {
		Player p = player(true);
		GameServer backend = statusServer(CompletableFuture.completedFuture(null),
				List.of());
		backend.available = QueueSystem.ServerStatus.ONLINE_INGAME;
		backend.isGoing = true;
		backend.playersInGame.add(p.getUniqueId());
		backend.updateServerStatus();
		awaitCondition(() -> backend.available == QueueSystem.ServerStatus.ONLINE_AVAILABLE);
		assertEquals(QueueSystem.ServerStatus.ONLINE_AVAILABLE, backend.available);
		assertFalse(backend.isGoing);
		assertTrue(backend.playersInGame.isEmpty());
	}

	@Test
	void statusInGameWhenOccupiedAndGoing() throws InterruptedException {
		Player p = player(true);
		GameServer backend = statusServer(CompletableFuture.completedFuture(null),
				List.of(p));
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		backend.isGoing = true;
		backend.updateServerStatus();
		awaitCondition(() -> backend.available == QueueSystem.ServerStatus.ONLINE_INGAME);
		assertEquals(QueueSystem.ServerStatus.ONLINE_INGAME, backend.available);
	}

	@Test
	void statusAvailableWhenOccupiedIdle() throws InterruptedException {
		Player p = player(true);
		GameServer backend = statusServer(CompletableFuture.completedFuture(null),
				List.of(p));
		backend.available = QueueSystem.ServerStatus.OFFLINE;
		backend.isGoing = false;
		backend.updateServerStatus();
		awaitCondition(() -> backend.available == QueueSystem.ServerStatus.ONLINE_AVAILABLE);
		assertEquals(QueueSystem.ServerStatus.ONLINE_AVAILABLE, backend.available);
	}

	@Test
	void enterNotifySentToCurrentServer() {
		List<Object[]> sent = new ArrayList<>();
		ServerConnection connection = Mocks.recording(ServerConnection.class, sent, Map.of());
		Player p = Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", true,
				"getCurrentServer", Optional.of(connection)));
		queue.addPlayerToQueue(p);
		assertEquals(1, sent.size());
		String[] message = decodePluginMessage(sentBytes(sent, 0));
		assertEquals("queue", message[0]);
		assertEquals("enter", message[1]);
	}

	@Test
	void leaveNotifySentOnRemoveAll() {
		List<Object[]> sent = new ArrayList<>();
		ServerConnection connection = Mocks.recording(ServerConnection.class, sent, Map.of());
		Player p = Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", true,
				"getCurrentServer", Optional.of(connection)));
		queue.addPlayerToQueue(p);
		sent.clear();
		QueueSystem.removeFromAllQueues(p);
		assertEquals(1, sent.size());
		String[] message = decodePluginMessage(sentBytes(sent, 0));
		assertEquals("queue", message[0]);
		assertEquals("leave", message[1]);
		assertTrue(queue.players.isEmpty());
	}
}
