package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRelayTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		ChatRelay.rankCache.clear();
		ChatRelay.lastChatAt.clear();
		ChatRelay.rankLoader = ChatRelay::lookupRank;
	}

	// --- formatting -----------------------------------------------------

	@Test
	void formatsRanklessLineLikeTheLobby() {
		String line = ChatRelay.formatLine(0, "Steve", "hello");
		Component parsed = LegacyComponentSerializer.legacySection().deserialize(line);

		// Ranks.rankless has no icon, so the line starts with the separator space.
		assertEquals(" Steve: hello", plainText(parsed));
		assertEquals(TextColor.fromHexString("#a1a1a1"), colorOf(parsed, "Steve"));
	}

	@Test
	void formatsRankedLineWithIconAndRankColor() {
		String line = ChatRelay.formatLine(1, "Steve", "hello");

		// Wire format must stay the unusual repeated hex form; Pumpkin's parser
		// only understands that one.
		assertTrue(line.contains("§x§b§a§1§5§6§0Steve"), line);

		Component parsed = LegacyComponentSerializer.legacySection().deserialize(line);
		assertEquals("\uE301 Steve: hello", plainText(parsed));
		assertEquals(TextColor.fromHexString("#ba1560"), colorOf(parsed, "Steve"));
	}

	@Test
	void clampsOutOfRangeRankIds() {
		assertEquals(ChatRelay.formatLine(9, "A", "b"), ChatRelay.formatLine(42, "A", "b"));
		assertEquals(ChatRelay.formatLine(0, "A", "b"), ChatRelay.formatLine(-3, "A", "b"));
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

	@Test
	void copyOfTheLobbyRankPriorities() {
		assertEquals(10, ChatRelay.PRIORITY[0]);
		for (int id = 1; id < ChatRelay.PRIORITY.length; id++) {
			assertEquals(id, ChatRelay.PRIORITY[id]);
		}
	}

	// --- sanitizing / throttling ----------------------------------------

	@Test
	void stripsLegacyCodesAndControlCharacters() {
		assertEquals("hi there", ChatRelay.sanitizeMessage("hi §cthere"));
		assertEquals("abc", ChatRelay.sanitizeMessage("a\u0000b\nc\u007F"));
		assertEquals("hi 😀", ChatRelay.sanitizeMessage("hi 😀"));
		assertEquals("", ChatRelay.sanitizeMessage("§c§l"));
		// whole hex sequences go too
		assertEquals("hi", ChatRelay.sanitizeMessage("§x§b§a§1§5§6§0hi"));
		// a marker not followed by a code only loses the marker
		assertEquals("100 off", ChatRelay.sanitizeMessage("100§ off"));
	}

	@Test
	void throttlesRepeatedChatPerPlayer() {
		UUID player = UUID.randomUUID();

		assertTrue(ChatRelay.allowChat(player, 1_000L));
		assertFalse(ChatRelay.allowChat(player, 1_500L));
		assertFalse(ChatRelay.allowChat(player, 1_999L));
		assertTrue(ChatRelay.allowChat(player, 2_000L));

		// other players are unaffected
		assertTrue(ChatRelay.allowChat(UUID.randomUUID(), 1_500L));
	}

	// --- rank cache -----------------------------------------------------

	@Test
	void rankEntriesExpireAtTheTtl() {
		ChatRelay.RankEntry entry = new ChatRelay.RankEntry(1, 5_000L);

		assertTrue(entry.isFresh(5_000L));
		assertTrue(entry.isFresh(5_000L + ChatRelay.RANK_TTL_MILLIS - 1));
		assertFalse(entry.isFresh(5_000L + ChatRelay.RANK_TTL_MILLIS));
	}

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

	// --- payload framing ------------------------------------------------

	@Test
	void payloadFramingMatchesTheBackendReaders() throws Exception {
		byte[] payload = ChatRelay.chatPayload("minesweeper", "§x§b§a§1§5§6§0Steve: hi");

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
		assertEquals("Chat", in.readUTF());
		assertEquals("minesweeper", in.readUTF());
		assertEquals("§x§b§a§1§5§6§0Steve: hi", in.readUTF());
		assertEquals(0, in.available());
	}

	// --- helpers --------------------------------------------------------

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
