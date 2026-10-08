package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

class RankedQueueToggleTest {
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
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class),
				QueueSystem.queueTypes.litestrike_ranked);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).servers.add(backend);
	}

	Player player() {
		return Mocks.mock(Player.class,
				Map.of("getUniqueId", UUID.randomUUID(), "isActive", true, "getCurrentServer", Optional.empty()));
	}

	@Test
	void openRankedQueueAcceptsJoins() {
		GameQueue ranked = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked);
		Player p = player();
		ranked.addPlayerToQueue(p);
		assertTrue(ranked.players.contains(p));
	}

	@Test
	void closedRankedQueueRejectsJoins() {
		GameQueue ranked = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked);
		ranked.open = false;
		Player p = player();
		ranked.addPlayerToQueue(p);
		assertFalse(ranked.players.contains(p));
	}

	@Test
	void reopeningRankedQueueAcceptsJoinsAgain() {
		GameQueue ranked = QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked);
		ranked.open = false;
		ranked.open = true;
		Player p = player();
		ranked.addPlayerToQueue(p);
		assertTrue(ranked.players.contains(p));
	}
}
