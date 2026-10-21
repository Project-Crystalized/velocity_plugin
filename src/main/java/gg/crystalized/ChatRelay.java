package gg.crystalized;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
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
 */
public class ChatRelay {

	/** Velocity names of the only two servers whose chat is mirrored. */
	static final String LOBBY = "lobby";
	static final String MINESWEEPER = "minesweeper";

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

	static final ConcurrentHashMap<UUID, Integer> rankCache = new ConcurrentHashMap<>();

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

		int rankId = getRank(p.getUniqueId());
		Component line = Component.text(ICON[rankId]).color(NamedTextColor.WHITE)
			.append(Component.text(" "))
			.append(Component.text(p.getUsername()).color(TextColor.fromHexString(COLOR[rankId])))
			.append(Component.text(": "))
			.append(Component.text(event.getMessage()));
		String legacy = SECTION_HEX.serialize(line);

		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF("Chat");
		out.writeUTF(origin);
		out.writeUTF(legacy);
		byte[] payload = out.toByteArray();

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

	/** Rank id mirror of lobby Ranks.getRank(): rank_id column first, else best pay_rank_id byte. */
	static int getRank(UUID uuid) {
		Integer cached = rankCache.get(uuid);
		if (cached != null) {
			return cached;
		}
		int rank = 0;
		try (Connection conn = DriverManager.getConnection(Databases.LOBBY)) {
			PreparedStatement prep = conn.prepareStatement(
				"SELECT rank_id, pay_rank_id FROM LobbyPlayers WHERE player_uuid = ?;");
			prep.setBytes(1, Databases.uuid_to_bytes(uuid));
			ResultSet set = prep.executeQuery();
			if (set.next()) {
				int rankId = set.getInt("rank_id");
				byte[] payRank = set.getBytes("pay_rank_id");
				if (rankId != 0) {
					rank = rankId;
				} else {
					int bestPriority = Integer.MAX_VALUE;
					int bestId = 0;
					// pay_rank_id is a packed short[] (2 bytes per entry, big endian)
					for (int i = 0; i + 1 < payRank.length; i += 2) {
						int id = ((payRank[i] & 0xFF) << 8) | (payRank[i + 1] & 0xFF);
						if (id < PRIORITY.length && PRIORITY[id] < bestPriority) {
							bestPriority = PRIORITY[id];
							bestId = id;
						}
					}
					rank = bestId;
				}
			}
		} catch (SQLException e) {
			Velocity_plugin.logger.info("chat relay rank lookup failed: " + e.getMessage());
		}
		rank = Math.min(rank, COLOR.length - 1);
		rankCache.put(uuid, rank);
		return rank;
	}
}
