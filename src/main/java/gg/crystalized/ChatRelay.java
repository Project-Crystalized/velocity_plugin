package gg.crystalized;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Cross-server chat mirror, strictly between the lobby and the minesweeper
 * server. Chat on any other backend (game servers, smp, limbo) is untouched.
 *
 * The proxy is the only place every chat line passes through, and it can talk
 * to every client directly, so there is no backend plumbing at all: the event
 * is denied (the backend never broadcasts its own version) and the finished
 * component is sent to every player in the pair. Clients already render proxy
 * messages on both Paper and Pumpkin - the queue and friend notices use the
 * same path.
 *
 * Because the backend's validation no longer runs, this listener owns the
 * rate limit. Rank data is cached for RANK_TTL_MILLIS, evicted on
 * login/disconnect, and warmed off the event thread on login.
 */
public class ChatRelay {

	/** Velocity names of the only two servers whose chat is mirrored. */
	static final String LOBBY = "lobby";
	static final String MINESWEEPER = "minesweeper";

	static final long RANK_TTL_MILLIS = 5 * 60 * 1000L;
	/** One message per proxy tick; extra lines are dropped silently. */
	static final long CHAT_COOLDOWN_MILLIS = 50L;

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
	static final int[] PRIORITY = { 10, 1, 2, 3, 4, 5, 6, 7, 8, 9 };

	record RankEntry(int rankId, long fetchedAt) {
		boolean isFresh(long now) {
			return now - fetchedAt < RANK_TTL_MILLIS;
		}
	}

	static final ConcurrentHashMap<UUID, RankEntry> rankCache = new ConcurrentHashMap<>();

	static final ConcurrentHashMap<UUID, Long> lastChatAt = new ConcurrentHashMap<>();

	static LongSupplier clock = System::currentTimeMillis;

	static ToIntFunction<UUID> rankLoader = ChatRelay::lookupRank;

	private final Object plugin;
	private final ProxyServer server;

	public ChatRelay(Object plugin, ProxyServer server) {
		this.plugin = plugin;
		this.server = server;
	}

	@Subscribe
	public void onPlayerChat(PlayerChatEvent event) {
		// Respect moderation plugins that ran before this listener.
		if (!event.getResult().isAllowed()) {
			return;
		}
		Player p = event.getPlayer();
		String origin = serverName(p);
		if (!isMirrored(origin)) {
			return;
		}
		event.setResult(PlayerChatEvent.ChatResult.denied());

		String message = event.getMessage();
		if (message.isBlank()) {
			return;
		}

		UUID uuid = p.getUniqueId();
		if (!allowChat(uuid, clock.getAsLong())) {
			return;
		}

		Component line = formatLine(getRank(uuid), p.getUsername(), message);
		Component remoteLine = Component.text("[" + origin + "] ", NamedTextColor.DARK_GRAY).append(line);
		for (Player r : server.getAllPlayers()) {
			String target = serverName(r);
			if (isMirrored(target)) {
				r.sendMessage(target.equals(origin) ? line : remoteLine);
			}
		}
		Velocity_plugin.logger.info("(" + origin + ") " + p.getUsername() + ": " + logSafe(message));
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		rankCache.remove(uuid);
		lastChatAt.remove(uuid);
		server.getScheduler().buildTask(plugin, () -> getRank(uuid)).schedule();
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		rankCache.remove(uuid);
		lastChatAt.remove(uuid);
	}

	static String serverName(Player player) {
		return player.getCurrentServer()
			.map(s -> s.getServerInfo().getName())
			.orElse("");
	}

	static boolean isMirrored(String serverName) {
		return serverName.equals(LOBBY) || serverName.equals(MINESWEEPER);
	}

	/** Keeps unescaped control characters out of the proxy log. */
	static String logSafe(String message) {
		StringBuilder out = new StringBuilder(message.length());
		for (int i = 0; i < message.length(); i++) {
			char c = message.charAt(i);
			if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

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

	static int resolveRank(int rankId, byte[] payRank) {
		return clampRank(rankId != 0 ? rankId : bestPaidRank(payRank));
	}

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

	static Component formatLine(int rankId, String username, String message) {
		int id = clampRank(rankId);
		Component name = Component.text(username).color(TextColor.fromHexString(COLOR[id]));
		Component line = ICON[id].isEmpty()
			? name
			: Component.text(ICON[id]).color(NamedTextColor.WHITE).append(Component.text(" ")).append(name);
		return line.append(Component.text(": ")).append(Component.text(message));
	}
}
