package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.server.RegisteredServer;

class RequeueRoutingTest {
	ProxyServer proxy;
	Velocity_plugin plugin;

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
		for (QueueSystem.queueTypes type : new QueueSystem.queueTypes[] { QueueSystem.queueTypes.litestrike,
				QueueSystem.queueTypes.litestrike_ranked }) {
			GameServer backend = new GameServer(Mocks.mock(RegisteredServer.class), type);
			backend.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
			QueueSystem.getQueue(type).servers.add(backend);
		}
		plugin = new Velocity_plugin(proxy, Mocks.mock(Logger.class));
	}

	Player player() {
		return Mocks.mock(Player.class,
				Map.of("getUniqueId", UUID.randomUUID(), "isActive", true, "getCurrentServer", Optional.empty()));
	}

	Player connectablePlayer() {
		ConnectionRequestBuilder.Result result = Mocks.mock(ConnectionRequestBuilder.Result.class,
				Map.of("isSuccessful", true));
		ConnectionRequestBuilder builder = Mocks.mock(ConnectionRequestBuilder.class,
				Map.of("connect", CompletableFuture.completedFuture(result)));
		return Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID(), "isActive", true,
				"getCurrentServer", Optional.empty(), "createConnectionRequest", builder));
	}

	void matchmake(Player p, QueueSystem.queueTypes type) {
		QueueSystem.getQueue(type).addPlayerToQueue(p);
		QueueSystem.getQueue(type).sendAllPlayersToServer(type);
	}

	static String[] decodePluginMessage(byte[] data) {
		ByteArrayDataInput in = ByteStreams.newDataInput(data);
		return new String[] { in.readUTF() };
	}

	static byte[] sentBytes(List<Object[]> sent, int index) {
		return (byte[]) ((Object[]) sent.get(index)[1])[1];
	}

	static void awaitMessages(List<Object[]> sent) throws InterruptedException {
		for (int i = 0; i < 200 && sent.isEmpty(); i++) {
			Thread.sleep(10);
		}
	}

	void connectMessage(Player p, String queue, String third) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF("Connect");
		out.writeUTF(queue);
		out.writeUTF(third);
		ServerConnection connection = Mocks.mock(ServerConnection.class, Map.of("getPlayer", p));
		plugin.onPluginMessageFromBackend(new PluginMessageEvent((ChannelMessageSource) connection, connection,
				Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray()));
	}

	void clearQueues() {
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.clear();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).players.clear();
	}

	@Test
	void rankedHistoryRequeueLandsInRankedQueue() {
		Player p = player();
		matchmake(p, QueueSystem.queueTypes.litestrike_ranked);
		connectMessage(p, "litestrike", "false");
		assertTrue(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).players.contains(p));
		assertFalse(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
	}

	@Test
	void casualHistoryRequeueLandsInCasualQueue() {
		Player p = player();
		matchmake(p, QueueSystem.queueTypes.litestrike);
		connectMessage(p, "litestrike", "false");
		assertTrue(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
	}

	@Test
	void freshJoinWithRankedHistoryStaysCasual() {
		Player p = player();
		matchmake(p, QueueSystem.queueTypes.litestrike_ranked);
		clearQueues();
		connectMessage(p, "litestrike", "true");
		assertTrue(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
		assertFalse(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).players.contains(p));
	}

	@Test
	void unknownHistoryRequeueFallsBackToCasual() {
		Player p = player();
		connectMessage(p, "litestrike", "false");
		assertTrue(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
	}

	void backendMessage(Player p, String first, String second) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF(first);
		out.writeUTF(second);
		ServerConnection connection = Mocks.mock(ServerConnection.class, Map.of("getPlayer", p));
		plugin.onPluginMessageFromBackend(new PluginMessageEvent((ChannelMessageSource) connection, connection,
				Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray()));
	}

	@Test
	void backendLeaveMessageRemovesFromQueue() {
		Player p = player();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).addPlayerToQueue(p);
		assertTrue(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
		backendMessage(p, "queue", "leave");
		assertFalse(QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).players.contains(p));
	}
	@Test
	void disconnectClearsHistory() {
		Player p = player();
		QueueSystem.lastGameQueue.put(p.getUniqueId(), QueueSystem.queueTypes.litestrike_ranked);
		new QueueSystem(proxy, null)
				.onPlayerDisconnect(new DisconnectEvent(p, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
		assertFalse(QueueSystem.lastGameQueue.containsKey(p.getUniqueId()));
	}

	@Test
	void rankedOnSentToBackendOnRankedSend() throws InterruptedException {
		List<Object[]> sent = new ArrayList<>();
		RegisteredServer backend = Mocks.recording(RegisteredServer.class, sent, Map.of());
		GameServer wrapper = new GameServer(backend, QueueSystem.queueTypes.litestrike_ranked);
		wrapper.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).servers.clear();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).servers.add(wrapper);
		Player p = connectablePlayer();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked).addPlayerToQueue(p);
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike_ranked)
				.sendAllPlayersToServer(QueueSystem.queueTypes.litestrike_ranked);
		awaitMessages(sent);
		assertEquals(1, sent.size());
		assertEquals("ranked_on", decodePluginMessage(sentBytes(sent, 0))[0]);
	}

	@Test
	void rankedOffSentToBackendOnCasualSend() throws InterruptedException {
		List<Object[]> sent = new ArrayList<>();
		RegisteredServer backend = Mocks.recording(RegisteredServer.class, sent, Map.of());
		GameServer wrapper = new GameServer(backend, QueueSystem.queueTypes.litestrike);
		wrapper.available = QueueSystem.ServerStatus.ONLINE_AVAILABLE;
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).servers.clear();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).servers.add(wrapper);
		Player p = connectablePlayer();
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).addPlayerToQueue(p);
		QueueSystem.getQueue(QueueSystem.queueTypes.litestrike).sendAllPlayersToServer(QueueSystem.queueTypes.litestrike);
		awaitMessages(sent);
		assertEquals(1, sent.size());
		assertEquals("ranked_off", decodePluginMessage(sentBytes(sent, 0))[0]);
	}
}
