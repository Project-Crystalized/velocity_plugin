package gg.crystalized;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
/**
 * Cross-server chat mirror, strictly between the lobby and the minesweeper
 * server. Chat on any other backend (game servers, smp, limbo) is not
 * relayed in or out.
 *
 * Every client chat passes through velocity first, so a PlayerChatEvent
 * listener sees ALL chat regardless of which server the player stands on -
 * no backend -> proxy send path is needed. The relay is sent to every other
 * player's current server over crystalized:main; Paper shows it via its
 * incoming listener ("Chat"), Pumpkin via PlayerCustomPayloadEvent.
 *
 * The lobby's players skip the relay when it is the lobby, because lobby
 * renders its own chat natively (LobbyChatRenderer). Minesweeper
 * suppresses its vanilla broadcast instead and relies on the relayed line,
 * so its players get the same relayed text everyone else sees.
 *
 * Formatting mirrors LobbyChatRenderer: white rank icon + rank-colored name
 * + ": " + message. Rank data comes from the lobby database with the same
 * semantics as lobby Ranks.getRank (rank_id first, else best pay_rank_id).
 *
 * Because velocity relays before the backend validates the message, the
 * relay sanitizes legacy formatting codes and rate limits itself; ranks are
 * cached for RANK_TTL_MILLIS, evicted on login/disconnect, and warmed off
 * the event thread on login.
 */
public class ChatRelay {

	/** Velocity names of the only two servers whose chat is mirrored. */
	static final String LOBBY = "lobby";
	static final String MINESWEEPER = "minesweeper";

	/** How long a cached rank may be reused before it is re-read from the lobby db. */
	static final long RANK_TTL_MILLIS = 5 * 60 * 1000L;

	/** Minimum time between two relayed chat lines from the same player. */
	static final long CHAT_COOLDOWN_MILLIS = 1000L;

	private final ProxyServer server;

	/** Mirrors lobby Ranks values() ordering/ids (0..9). */
	static final String[] COLOR = {
		"#a1a1a1", // 0 rankless
		"#ba1560", // 1 admin
		"#22d87a", // 2 mod
		"#379fe5", // 3 dev
		"#bf750f", // 4 contributor
		"#087544", // 5 sub-project maker
		"#f63c3c", // 6 creator
		"#d3af10", // 7 sun sub
		"#512f7c", // 8 moon one
		"#1a1a1a", // 9 supporter
	};
	static final String[] ICON = {
		"",
		"\uE301",
		"\uE307",
		"\uE303",
		"\uE305",
		"\uE309",
		"\uE30B",
		"\uE30D",
		"\uE30F",
		"\uE311",
	};
	/** Lower wins, mirrors lobby Ranks priority; rankless is last-resort. */
	static final int[] PRIORITY = { 10, 1, 2, 3, 4, 5, 6, 7, 8, 9 };

	/** A cached rank and when it was read, so it can expire. */
	record RankEntry(int rankId, long fetchedAt) {
		boolean isFresh(long now) {
			return now - fetchedAt < RANK_TTL_MILLIS;
		}
	}

	static final ConcurrentHashMap<UUID, RankEntry> rankCache = new ConcurrentHashMap<>();

	/** Last relayed chat timestamp per player, for CHAT_COOLDOWN_MILLIS. */
	static final ConcurrentHashMap<UUID, Long> lastChatAt = new ConcurrentHashMap<>();

	/** Replaced in tests so rank handling can be exercised without a database. */
	static ToIntFunction<UUID> rankLoader = ChatRelay::lookupRank;

	/**
	 * Serializes with full-RGB hex codes in the BungeeCord repeated format
	 * (`§x§b§a§1§5§6§0`). Two traps avoided here: plain `legacySection()` has
	 * hexColours off and down-samples hex colors to the nearest of the 16
	 * named codes (admin's #ba1560 read red), and plain `hexColors()` without
	 * the repeated-character format emits the Kyori style `§xba1560`, which
	 * Pumpkin's legacy parser cannot read and prints the digits as text.
	 * Both receiving ends parse the repeated format exactly: adventure
	 * deserialize on Paper, `TextComponent::from_legacy_string` on Pumpkin
	 * (unit-tested there as `from_legacy_string_hex`).
	 */
	static final LegacyComponentSerializer SECTION_HEX = LegacyComponentSerializer
			.builder()
			.character('§')
			.hexColors()
			.useUnusualXRepeatedCharacterHexFormat()
			.build();

	public ChatRelay(ProxyServer server) {
		this.server = server;
	}

	@Subscribe
	public void onPlayerChat(PlayerChatEvent event) {
		Player p = event.getPlayer();
		String origin = p.getCurrentServer()
			.map(s -> s.getServerInfo().getName())
			.orElse("");
		// Only lobby <-> minesweeper chat is mirrored; also skips players in
		// a server transition (no current server yet).
		if (!origin.equals(LOBBY) && !origin.equals(MINESWEEPER)) {
			return;
		}

		// Velocity relays before the backend can reject the message, so strip
		// legacy codes/control characters and throttle here as well.
		String message = sanitizeMessage(event.getMessage());
		if (message.isEmpty()) {
			return;
		}
		if (!allowChat(p.getUniqueId(), System.currentTimeMillis())) {
			return;
		}

		byte[] payload = chatPayload(origin,
				formatLine(getRank(p.getUniqueId()), p.getUsername(), message));

		for (Player r : server.getAllPlayers()) {
			String target = r.getCurrentServer()
				.map(s -> s.getServerInfo().getName())
				.orElse("");
			// Never deliver to backends outside the pair.
			if (!target.equals(LOBBY) && !target.equals(MINESWEEPER)) {
				continue;
			}
			// The lobby renders its own origin chat natively; minesweeper
			// suppresses vanilla chat, so its players (sender included) get
			// the relayed line from here instead.
			if (target.equals(origin) && origin.equals(LOBBY)) {
				continue;
			}
			r.getCurrentServer().ifPresent(s -> s.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, payload));
		}
	}

	/**
	 * Warms the rank cache off the login thread, and drops anything cached
	 * from the previous session. Without the warm-up the first chat line pays
	 * for a synchronous sqlite query on the connection's event loop.
	 */
	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		rankCache.remove(uuid);
		lastChatAt.remove(uuid);
		server.getScheduler().buildTask(this, () -> getRank(uuid)).schedule();
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		rankCache.remove(uuid);
		lastChatAt.remove(uuid);
	}

	/** Rank id for chat formatting, cached for RANK_TTL_MILLIS. */
	static int getRank(UUID uuid) {
		long now = System.currentTimeMillis();
		RankEntry cached = rankCache.get(uuid);
		if (cached != null && cached.isFresh(now)) {
			return cached.rankId;
		}
		RankEntry loaded = rankCache.compute(uuid, (key, old) -> {
			if (old != null && old.isFresh(now)) {
				return old;
			}
			return new RankEntry(clampRank(rankLoader.applyAsInt(uuid)), now);
		});
		return loaded.rankId;
	}

	/** Reads the rank from the lobby db; failures fall back to rankless. */
	static int lookupRank(UUID uuid) {
		int rank = 0;
		try (Connection conn = DriverManager.getConnection(Databases.LOBBY)) {
			PreparedStatement prep = conn.prepareStatement(
				"SELECT rank_id, pay_rank_id FROM LobbyPlayers WHERE player_uuid = ?;");
			prep.setBytes(1, Databases.uuid_to_bytes(uuid));
			ResultSet set = prep.executeQuery();
			if (set.next()) {
				rank = resolveRank(set.getInt("rank_id"), set.getBytes("pay_rank_id"));
			}
		} catch (SQLException e) {
			Velocity_plugin.logger.info("chat relay rank lookup failed: " + e.getMessage());
		}
		return clampRank(rank);
	}

	/** Mirrors lobby Ranks.getRank(): explicit rank_id first, else best paid rank. */
	static int resolveRank(int rankId, byte[] payRank) {
		return clampRank(rankId != 0 ? rankId : bestPaidRank(payRank));
	}

	/** Best rank in the packed pay_rank_id short[] (2 bytes per entry, big endian). */
	static int bestPaidRank(byte[] payRank) {
		if (payRank == null) {
			return 0;
		}
		int bestPriority = Integer.MAX_VALUE;
		int bestId = 0;
		for (int i = 0; i + 1 < payRank.length; i += 2) {
			int id = ((payRank[i] & 0xFF) << 8) | (payRank[i + 1] & 0xFF);
			if (id < PRIORITY.length && PRIORITY[id] < bestPriority) {
				bestPriority = PRIORITY[id];
				bestId = id;
			}
		}
		return bestId;
	}

	static int clampRank(int rank) {
		return Math.max(0, Math.min(rank, COLOR.length - 1));
	}

	/**
	 * Removes legacy formatting codes and control characters. The relayed line
	 * is rendered as a system message on the receiving end, so an unescaped
	 * '§' from a modified client could otherwise inject formatting.
	 */
	static String sanitizeMessage(String message) {
		StringBuilder out = new StringBuilder(message.length());
		for (int i = 0; i < message.length(); i++) {
			char c = message.charAt(i);
			if (c == '§') {
				// Drop the code character too (§c, §l, §x...), not just the marker.
				if (i + 1 < message.length() && isLegacyCode(message.charAt(i + 1))) {
					i++;
				}
				continue;
			}
			if (c < ' ' || c == '\u007F') {
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static boolean isLegacyCode(char c) {
		char lower = Character.toLowerCase(c);
		return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
				|| lower == 'k' || lower == 'l' || lower == 'm' || lower == 'n'
				|| lower == 'o' || lower == 'r' || lower == 'x';
	}

	/**
	 * Per-player relay cooldown. Velocity relays before the backend's own
	 * anti-spam runs, so this is the only limiter that applies to the mirror.
	 */
	static boolean allowChat(UUID uuid, long now) {
		boolean[] allowed = { false };
		lastChatAt.compute(uuid, (key, last) -> {
			if (last != null && now - last < CHAT_COOLDOWN_MILLIS) {
				return last;
			}
			allowed[0] = true;
			return now;
		});
		return allowed[0];
	}

	/** Renders one chat line exactly like the lobby's LobbyChatRenderer. */
	static String formatLine(int rankId, String username, String message) {
		int id = clampRank(rankId);
		Component line = Component.text(ICON[id]).color(NamedTextColor.WHITE)
			.append(Component.text(" "))
			.append(Component.text(username).color(TextColor.fromHexString(COLOR[id])))
			.append(Component.text(": "))
			.append(Component.text(message));
		return SECTION_HEX.serialize(line);
	}

	/** Payload layout shared with the lobby listener and Pumpkin's relay.rs: ["Chat", origin, line]. */
	static byte[] chatPayload(String origin, String legacyLine) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF("Chat");
		out.writeUTF(origin);
		out.writeUTF(legacyLine);
		return out.toByteArray();
	}
}
