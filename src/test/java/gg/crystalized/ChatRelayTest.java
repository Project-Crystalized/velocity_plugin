package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class ChatRelayTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		ChatRelay.rankCache.clear();
		ChatRelay.lastChatAt.clear();
		ChatRelay.clock = System::currentTimeMillis;
		ChatRelay.rankLoader = ChatRelay::lookupRank;
		Velocity_plugin.logger = Mocks.mock(Logger.class);
	}

	// --- formatting -----------------------------------------------------

	@Test
	void formatsRanklessLineWithoutIcon() {
		Component line = ChatRelay.formatLine(0, "Steve", "hello");

		assertEquals("Steve: hello", plainText(line));
		assertEquals(TextColor.fromHexString("#a1a1a1"), colorOf(line, "Steve"));
	}

	@Test
	void formatsRankedLineWithIconAndRankColor() {
		Component line = ChatRelay.formatLine(1, "Steve", "hello");

		assertEquals("\uE301 Steve: hello", plainText(line));
		assertEquals(TextColor.fromHexString("#ba1560"), colorOf(line, "Steve"));
	}

	@Test
	void clampsOutOfRangeRankIds() {
		assertEquals(ChatRelay.formatLine(9, "A", "b"), ChatRelay.formatLine(42, "A", "b"));
		assertEquals(ChatRelay.formatLine(0, "A", "b"), ChatRelay.formatLine(-3, "A", "b"));
	}

	// --- relay ----------------------------------------------------------

	@Test
	void relaysChatToBothServersAndDeniesTheBackendBroadcast() {
		long[] now = { 10_000L };
		ChatRelay.clock = () -> now[0];
		ChatRelay.rankLoader = uuid -> 0;

		List<Object[]> senderCalls = new ArrayList<>();
		List<Object[]> lobbyCalls = new ArrayList<>();
		Player sender = playerOn("minesweeper", senderCalls, "Steve");
		Player lobbyPlayer = playerOn("lobby", lobbyCalls, "Alex");
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of(
				"getAllPlayers", List.of(lobbyPlayer, sender),
				"getScheduler", new FakeScheduler()));
		ChatRelay relay = new ChatRelay(proxy, proxy);
		PlayerChatEvent event = new PlayerChatEvent(sender, "hello");

		relay.onPlayerChat(event);

		assertFalse(event.getResult().isAllowed());
		assertEquals(1, countCalls(lobbyCalls, "sendMessage"));
		// the sender sees the same proxy line instead of a backend broadcast
		assertEquals(1, countCalls(senderCalls, "sendMessage"));

		Component remote = sentMessage(lobbyCalls);
		assertEquals("[minesweeper] Steve: hello", plainText(remote));
		assertEquals(TextColor.fromHexString("#a1a1a1"), colorOf(remote, "Steve"));
		assertEquals(NamedTextColor.DARK_GRAY, colorOf(remote, "[minesweeper] "));
		// same-server viewers, including the sender, get no origin tag
		assertEquals("Steve: hello", plainText(sentMessage(senderCalls)));

		// throttled: denied and dropped silently, no extra message to the sender
		now[0] += 49;
		event = new PlayerChatEvent(sender, "hello");
		relay.onPlayerChat(event);
		assertFalse(event.getResult().isAllowed());
		assertEquals(1, countCalls(lobbyCalls, "sendMessage"));
		assertEquals(1, countCalls(senderCalls, "sendMessage"));

		// one proxy tick later it goes through again
		now[0] += 1;
		relay.onPlayerChat(new PlayerChatEvent(sender, "hello"));
		assertEquals(2, countCalls(lobbyCalls, "sendMessage"));
		assertEquals(2, countCalls(senderCalls, "sendMessage"));
	}

	@Test
	void leavesNonMirroredServersAlone() {
		List<Object[]> senderCalls = new ArrayList<>();
		List<Object[]> lobbyCalls = new ArrayList<>();
		Player sender = playerOn("smp", senderCalls, "Steve");
		Player lobbyPlayer = playerOn("lobby", lobbyCalls, "Alex");
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of(
				"getAllPlayers", List.of(lobbyPlayer),
				"getScheduler", new FakeScheduler()));
		ChatRelay relay = new ChatRelay(proxy, proxy);
		PlayerChatEvent event = new PlayerChatEvent(sender, "hello");

		relay.onPlayerChat(event);

		assertTrue(event.getResult().isAllowed());
		assertEquals(0, countCalls(lobbyCalls, "sendMessage"));
		assertEquals(0, countCalls(senderCalls, "sendMessage"));
	}

	@Test
	void respectsEarlierDenials() {
		List<Object[]> lobbyCalls = new ArrayList<>();
		Player sender = playerOn("lobby", new ArrayList<>(), "Steve");
		Player lobbyPlayer = playerOn("lobby", lobbyCalls, "Alex");
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of(
				"getAllPlayers", List.of(lobbyPlayer),
				"getScheduler", new FakeScheduler()));
		ChatRelay relay = new ChatRelay(proxy, proxy);
		PlayerChatEvent event = new PlayerChatEvent(sender, "hello");
		event.setResult(PlayerChatEvent.ChatResult.denied());

		relay.onPlayerChat(event);

		assertEquals(0, countCalls(lobbyCalls, "sendMessage"));
	}

	@Test
	void logSafeStripsControlCharacters() {
		assertEquals("abc", ChatRelay.logSafe("a\nb\u0000c\u007F"));
		assertEquals("ab", ChatRelay.logSafe("a\u2028b\u2029"));
		// section signs are literal chat text, not log control characters
		assertEquals("§c hi", ChatRelay.logSafe("§c hi"));
	}

	// --- rank resolution ------------------------------------------------

	@Test
	void explicitRankWinsOverPaidRanks() {
		assertEquals(3, ChatRelay.resolveRank(3, paid(2)));
		assertEquals(7, ChatRelay.resolveRank(0, paid(7)));
		assertEquals(0, ChatRelay.resolveRank(0, null));
		assertEquals(9, ChatRelay.resolveRank(42, null));
		assertEquals(0, ChatRelay.resolveRank(-4, null));
	}

	@Test
	void picksBestPaidRankAndIgnoresUnknownOrOddData() {
		assertEquals(0, ChatRelay.bestPaidRank(null));
		assertEquals(0, ChatRelay.bestPaidRank(new byte[0]));
		assertEquals(7, ChatRelay.bestPaidRank(paid(7)));
		// mod (priority 2) beats sun_sub (7)
		assertEquals(2, ChatRelay.bestPaidRank(paid(7, 2)));
		// sun_sub (7) beats moon_one (8) and supporter (9)
		assertEquals(7, ChatRelay.bestPaidRank(paid(9, 8, 7)));
		assertEquals(0, ChatRelay.bestPaidRank(paid(20)));
		// trailing half entry is ignored
		assertEquals(7, ChatRelay.bestPaidRank(new byte[] { 0, 7, 0 }));
	}

	// --- throttling -----------------------------------------------------

	@Test
	void throttlesRepeatedChatPerPlayer() {
		UUID player = UUID.randomUUID();

		assertTrue(ChatRelay.allowChat(player, 1_000L));
		assertFalse(ChatRelay.allowChat(player, 1_049L));
		assertTrue(ChatRelay.allowChat(player, 1_050L));
		assertFalse(ChatRelay.allowChat(player, 1_099L));
		assertTrue(ChatRelay.allowChat(player, 1_100L));

		// other players are unaffected
		assertTrue(ChatRelay.allowChat(UUID.randomUUID(), 1_049L));
	}

	// --- rank cache -----------------------------------------------------

	@Test
	void freshCacheEntryAvoidsTheLoader() {
		UUID player = UUID.randomUUID();
		ChatRelay.rankCache.put(player, new ChatRelay.RankEntry(3, System.currentTimeMillis()));
		ChatRelay.rankLoader = uuid -> {
			fail("rank loader should not run for a fresh cache entry");
			return 0;
		};

		assertEquals(3, ChatRelay.getRank(player));
	}

	@Test
	void staleCacheEntryIsReloadedOnceAndClamped() {
		UUID player = UUID.randomUUID();
		AtomicInteger loads = new AtomicInteger();
		ChatRelay.rankCache.put(player,
				new ChatRelay.RankEntry(2, System.currentTimeMillis() - ChatRelay.RANK_TTL_MILLIS - 1));
		ChatRelay.rankLoader = uuid -> {
			loads.incrementAndGet();
			return 99;
		};

		assertEquals(9, ChatRelay.getRank(player));
		assertEquals(1, loads.get());

		// the refreshed value is cached
		assertEquals(9, ChatRelay.getRank(player));
		assertEquals(1, loads.get());
	}

	@Test
	void rankPrefetchIsScheduledByTheOwningPlugin() {
		FakeScheduler scheduler = new FakeScheduler();
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of("getScheduler", scheduler));
		Object plugin = new Object();
		ChatRelay relay = new ChatRelay(plugin, proxy);
		Player player = Mocks.mock(Player.class, Map.of("getUniqueId", UUID.randomUUID()));

		relay.onPostLogin(new PostLoginEvent(player));

		assertEquals(1, scheduler.tasks.size());
		assertSame(plugin, scheduler.tasks.get(0).plugin());
	}

	// --- helpers --------------------------------------------------------

	static Player playerOn(String server, List<Object[]> calls, String username) {
		ServerConnection connection = Mocks.mock(ServerConnection.class,
				Map.of("getServerInfo", new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565))));
		return Mocks.recording(Player.class, calls, Map.of(
				"getUniqueId", UUID.randomUUID(),
				"getUsername", username,
				"getCurrentServer", Optional.of(connection)));
	}

	static Component sentMessage(List<Object[]> calls) {
		Object[] call = calls.stream()
			.filter(c -> c[0].equals("sendMessage"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no message was sent"));
		return (Component) ((Object[]) call[1])[0];
	}

	static long countCalls(List<Object[]> calls, String method) {
		return calls.stream().filter(call -> call[0].equals(method)).count();
	}

	static byte[] paid(int... ids) {
		byte[] packed = new byte[ids.length * 2];
		for (int i = 0; i < ids.length; i++) {
			packed[i * 2] = (byte) (ids[i] >> 8);
			packed[i * 2 + 1] = (byte) ids[i];
		}
		return packed;
	}

	static String plainText(Component component) {
		StringBuilder out = new StringBuilder();
		appendText(component, out);
		return out.toString();
	}

	static void appendText(Component component, StringBuilder out) {
		if (component instanceof TextComponent text) {
			out.append(text.content());
		}
		for (Component child : component.children()) {
			appendText(child, out);
		}
	}

	static TextColor colorOf(Component component, String content) {
		if (component instanceof TextComponent text && text.content().equals(content)) {
			return text.color();
		}
		for (Component child : component.children()) {
			TextColor found = colorOf(child, content);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
