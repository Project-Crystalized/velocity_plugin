package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

class QueueStatusFormatTest {
	ProxyServer proxy;

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
	}

	Player player() {
		return Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", true,
				"getCurrentServer", Optional.empty()));
	}

	void addBackend(String name, QueueSystem.queueTypes type, QueueSystem.ServerStatus status,
			List<Player> connected) {
		ServerInfo info = new ServerInfo(name, new java.net.InetSocketAddress("127.0.0.1", 25565));
		RegisteredServer server = Mocks.mock(RegisteredServer.class,
				Map.of("getServerInfo", info, "getPlayersConnected", connected));
		GameServer backend = new GameServer(server, type);
		backend.available = status;
		QueueSystem.getQueue(type).servers.add(backend);
	}

	static String rendered(ProxyServer proxy) {
		StringBuilder out = new StringBuilder();
		for (Component line : QueueCommand.formatQueueStatus(proxy)) {
			out.append(GsonComponentSerializer.gson().serialize(line)).append("\n");
		}
		return out.toString();
	}

	@Test
	void emptyStateShowsHeaderAndZeroCounts() {
		String text = rendered(proxy);
		assertTrue(text.contains("-------------"));
		assertTrue(text.contains("Queue system status:"));
		assertTrue(text.contains("0 queued (6 needed / 10 max)"));
		assertTrue(text.contains("0 queued (6 needed / 8 max)"));
		assertTrue(text.contains("0 queued (3 needed / 12 max)"));
		assertFalse(text.contains("starting in"));
	}

	@Test
	void partialFillShowsCountsAndServerStates() {
		addBackend("litestrike_mall", QueueSystem.queueTypes.litestrike,
				QueueSystem.ServerStatus.ONLINE_AVAILABLE, List.of());
		addBackend("litestrike_cargo", QueueSystem.queueTypes.litestrike, QueueSystem.ServerStatus.OFFLINE,
				List.of());
		addBackend("knockoff_elements", QueueSystem.queueTypes.knockoff,
				QueueSystem.ServerStatus.ONLINE_INGAME, List.of(player(), player(), player()));
		GameQueue litestrike = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike);
		litestrike.addPlayerToQueue(player());
		litestrike.addPlayerToQueue(player());
		String text = rendered(proxy);
		assertTrue(text.contains("2 queued (6 needed / 10 max)"));
		assertTrue(text.contains("mall"));
		assertTrue(text.contains("available"));
		assertTrue(text.contains("cargo"));
		assertTrue(text.contains("offline"));
		assertTrue(text.contains("in game (3 players)"));
		assertFalse(text.contains("litestrike_mall"));
		assertFalse(text.contains("litestrike_cargo"));
		assertFalse(text.contains("ONLINE_AVAILABLE"));
		assertFalse(text.contains("ONLINE_INGAME"));
		assertFalse(text.contains("OFFLINE"));
	}

	@Test
	void otherServersListedSeparately() {
		ServerInfo lobbyInfo = new ServerInfo("lobby", new java.net.InetSocketAddress("127.0.0.1", 25565));
		RegisteredServer lobby = Mocks.mock(RegisteredServer.class,
				Map.of("getServerInfo", lobbyInfo, "getPlayersConnected", List.of(player(), player())));
		ProxyServer proxyWithLobby = Mocks.mock(ProxyServer.class,
				Map.of("getAllServers", List.of(lobby), "getScheduler", new FakeScheduler(), "getCommandManager",
						Mocks.mock(CommandManager.class)));
		String text = rendered(proxyWithLobby);
		assertTrue(text.contains("Other servers:"));
		assertTrue(text.contains("lobby"));
		assertTrue(text.contains("2 players"));
	}
}
