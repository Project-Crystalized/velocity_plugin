package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class RejoinNotifyTest {
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
		return Mocks.mock(Player.class,
				Map.of("getUniqueId", UUID.randomUUID(), "isActive", true, "getCurrentServer", Optional.empty()));
	}

	GameServer backend(QueueSystem.queueTypes type) {
		GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), type);
		backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		QueueSystem.getQueue(type).servers.add(backend);
		return backend;
	}

	@Test
	void litestrikeGameIsRejoinable() {
		Player p = player();
		GameServer backend = backend(QueueSystem.queueTypes.litestrike);
		backend.playersInGame.add(p.getUniqueId());
		assertEquals(Optional.of(backend.server), QueueCommand.findRejoinableServer(p));
	}

	@Test
	void rankedGameIsRejoinable() {
		Player p = player();
		GameServer backend = backend(QueueSystem.queueTypes.litestrike_ranked);
		backend.playersInGame.add(p.getUniqueId());
		assertEquals(Optional.of(backend.server), QueueCommand.findRejoinableServer(p));
	}

	@Test
	void nonLitestrikeGameIsNotAdvertised() {
		Player p = player();
		GameServer backend = backend(QueueSystem.queueTypes.knockoff);
		backend.playersInGame.add(p.getUniqueId());
		assertTrue(QueueCommand.findRejoinableServer(p).isEmpty());
	}

	@Test
	void noGameIsNotAdvertised() {
		backend(QueueSystem.queueTypes.litestrike);
		assertTrue(QueueCommand.findRejoinableServer(player()).isEmpty());
	}
}
