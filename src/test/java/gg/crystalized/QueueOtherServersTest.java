package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

class QueueOtherServersTest {
	ProxyServer proxy;

	@BeforeEach
	void setup() {
		QueueSystem.queues.clear();
		QueueSystem.lastGameQueue.clear();
		QueueSystem.otherServerStatus.clear();
		Velocity_plugin.logger = Mocks.mock(Logger.class);
		FakeScheduler scheduler = new FakeScheduler();
		proxy = Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", List.of(), "getScheduler", scheduler, "getCommandManager",
						Mocks.mock(CommandManager.class)));
		new QueueSystem(proxy, null);
	}

	ProxyServer proxyWith(List<RegisteredServer> servers) {
		return Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", servers, "getScheduler", new FakeScheduler(), "getCommandManager",
						Mocks.mock(CommandManager.class)));
	}

	RegisteredServer extraServer(String name, Object pingResult, int playerCount) {
		java.util.ArrayList<Player> connected = new java.util.ArrayList<>();
		for (int i = 0; i < playerCount; i++) {
			connected.add(Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID())));
		}
		ServerInfo info = new ServerInfo(name, new java.net.InetSocketAddress("127.0.0.1", 25565));
		return Mocks.mock(RegisteredServer.class,
				Map.of("ping", pingResult, "getServerInfo", info, "getPlayersConnected", connected));
	}

	static void awaitStatus(String name, QueueSystem.ServerStatus want) throws InterruptedException {
		for (int i = 0; i < 200 && QueueSystem.otherServerStatus.get(name) != want; i++) {
			Thread.sleep(10);
		}
	}

	static String rendered(ProxyServer proxy) {
		StringBuilder out = new StringBuilder();
		for (Component line : QueueCommand.formatQueueStatus(proxy)) {
			out.append(GsonComponentSerializer.gson().serialize(line)).append("\n");
		}
		return out.toString();
	}

	@Test
	void failedPingMarksOffline() throws InterruptedException {
		RegisteredServer lobby = extraServer("lobby",
				CompletableFuture.failedFuture(new RuntimeException("Connection refused")), 0);
		QueueSystem.updateOtherServerStatus(proxyWith(List.of(lobby)));
		awaitStatus("lobby", QueueSystem.ServerStatus.OFFLINE);
		assertEquals(QueueSystem.ServerStatus.OFFLINE, QueueSystem.otherServerStatus.get("lobby"));
	}

	@Test
	void successPingMarksOnline() throws InterruptedException {
		RegisteredServer lobby = extraServer("lobby", CompletableFuture.completedFuture(null), 2);
		QueueSystem.updateOtherServerStatus(proxyWith(List.of(lobby)));
		awaitStatus("lobby", QueueSystem.ServerStatus.ONLINE_AVAILABLE);
		assertEquals(QueueSystem.ServerStatus.ONLINE_AVAILABLE, QueueSystem.otherServerStatus.get("lobby"));
	}

	@Test
	void queueServerIsNotTracked() {
		RegisteredServer backend = Mocks.mock(RegisteredServer.class, Map.of());
		GameServer wrapper = new GameServer(backend, QueueSystem.queueTypes.knockoff);
		QueueSystem.getQueue(QueueSystem.queueTypes.knockoff).servers.add(wrapper);
		QueueSystem.updateOtherServerStatus(proxyWith(List.of(backend)));
		assertTrue(QueueSystem.otherServerStatus.isEmpty());
	}

	@Test
	void formatShowsOnlineSuffix() {
		RegisteredServer lobby = extraServer("lobby", CompletableFuture.completedFuture(null), 2);
		QueueSystem.otherServerStatus.put("lobby", QueueSystem.ServerStatus.ONLINE_AVAILABLE);
		String text = rendered(proxyWith(List.of(lobby)));
		assertTrue(text.contains("lobby"));
		assertTrue(text.contains("2 players"));
		assertTrue(text.contains("(online)"));
	}

	@Test
	void formatShowsOfflineSuffix() {
		RegisteredServer lobby = extraServer("lobby",
				CompletableFuture.failedFuture(new RuntimeException("Connection refused")), 0);
		QueueSystem.otherServerStatus.put("lobby", QueueSystem.ServerStatus.OFFLINE);
		String text = rendered(proxyWith(List.of(lobby)));
		assertTrue(text.contains("lobby"));
		assertTrue(text.contains("(offline)"));
	}

	@Test
	void unknownServerRendersWithoutSuffix() {
		RegisteredServer lobby = extraServer("lobby", CompletableFuture.completedFuture(null), 2);
		String text = rendered(proxyWith(List.of(lobby)));
		assertTrue(text.contains("lobby"));
		assertTrue(text.contains("2 players"));
		assertFalse(text.contains("(online)"));
		assertFalse(text.contains("(offline)"));
	}
}
