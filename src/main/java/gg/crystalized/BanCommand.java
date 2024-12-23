package gg.crystalized;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.slf4j.Logger;

public class BanCommand implements SimpleCommand {
	public static final String URL = "jdbc:sqlite:" + System.getProperty("user.home") + "/databases/ban_db.sql";
	private ProxyServer proxy;
	private Logger logger;

	public BanCommand(Logger logger, ProxyServer proxy) {
		this.proxy = proxy;
		this.logger = logger;
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (Exception e) {
			logger.error("" + e);
		}
		String create_ban_table = "CREATE TABLE IF NOT EXISTS BanTable ("
				+ "banned_uuid 		BLOB UNIQUE,"
				+ "banned_since		INTEGER,"
				+ "banned_until		INTEGER,"
				+ "banned_by			BLOB,"
				+ "banned_for			TEXT"
				+ ");";
		// String create_ipban_table = "CREATE TABLE IF NOT EXISTS IPBanTable ("
		// 		+ "banned_ip 			INTEGER UNIQUE,"
		// 		+ "banned_since		INTEGER"
		// 		+ "banned_until		INTEGER"
		// 		+ "banned_by			BLOB"
		// 		+ "banned_for			TEXT"
		// 		+ ");";

		try (Connection conn = DriverManager.getConnection(URL)) {
			Statement stmt = conn.createStatement();
			stmt.execute(create_ban_table);
			// stmt.execute(create_ipban_table);
		} catch (SQLException e) {
			logger.warn(e.getMessage());
			logger.warn("continueing without ban-database");
		}
	}

	public boolean isBanned(UUID p) {
		String is_banned = "SELECT banned_until FROM BanTable WHERE banned_uuid = ?;";
		try (Connection conn = DriverManager.getConnection(URL)) {
			PreparedStatement game_stmt = conn.prepareStatement(is_banned);
			game_stmt.setBytes(1, uuid_to_bytes(p));
			ResultSet res = game_stmt.executeQuery();

			res.next();
			int banned_until = res.getInt("banned_until");
			int current_time = ((int)(System.currentTimeMillis() / 1000));

			if (banned_until == 0) {
				return false;
			}

			if (banned_until > current_time) {
				return true;
			} else {
				String lift_ban = "DELETE FROM BanTable WHERE banned_uuid = ?;";
				PreparedStatement lift_ban_stmt = conn.prepareStatement(lift_ban);
				lift_ban_stmt.setBytes(1, uuid_to_bytes(p));
				lift_ban_stmt.executeUpdate();
				logger.warn("lifted a ban");
				return false;
			}
		} catch (SQLException e) {
			logger.warn(e.getMessage());
			logger.warn("continueing without ban-database");
			return false;
		}
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		Player p = (Player) invocation.source();
		if (p.getUsername().equals("cooltexture")
				|| p.getUsername().equals("Callum_Is_Bad")
				|| p.getUsername().equals("LadyCat_")
				|| p.getUsername().equals("___mira___")) {
			return true;
		}
		return false;
	}

	@Override
	public void execute(Invocation invocation) {
		try {
			String[] args = invocation.arguments();
			Player player = proxy.getPlayer(args[0]).get();
			int duration = 300;
			if (args.length >= 2) {
				duration = get_duration(args[1]);
			}
			String reason = "";
			if (args.length >= 3) {
				reason = args[2];
			}
			invocation.source().sendMessage(Component.text("banning " + args[0] + " for " + duration + "seconds"));
			player.disconnect(Component.text("you have been banned for: " + reason));


			String save_ban = "INSERT INTO BanTable(banned_uuid, banned_since, banned_until, banned_by, banned_for) VALUES(?, unixepoch(), ?, ?, ?)";
			try (Connection conn = DriverManager.getConnection(URL)) {
				PreparedStatement game_stmt = conn.prepareStatement(save_ban);

				game_stmt.setBytes(1, uuid_to_bytes(player));
				game_stmt.setInt(2, ((int)(System.currentTimeMillis() / 1000)) + duration);
				if (invocation.source() instanceof Player) {
					game_stmt.setBytes(3, uuid_to_bytes((Player) invocation.source()));
				}
				game_stmt.setString(4, reason);
				game_stmt.executeUpdate();
			} catch (SQLException e) {
				invocation.source().sendMessage(Component.text(e.getMessage()));
				invocation.source().sendMessage(Component.text("database couldnt be opened :/"));
			}

		} catch (Exception e) {
			invocation.source().sendMessage(Component.text("Error: " + e));
			invocation.source().sendMessage(Component.text("usage: /ban <player_name> <duration, e.g. 1d, 5m> <reason>").color(NamedTextColor.RED));
		}
	}

	private static byte[] uuid_to_bytes(Player p) {
		ByteBuffer bb = ByteBuffer.allocate(16);
		UUID uuid = p.getUniqueId();
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	private static byte[] uuid_to_bytes(UUID uuid) {
		ByteBuffer bb = ByteBuffer.allocate(16);
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	private int get_duration(String s) {
		int value = Integer.parseInt(s.replaceAll("[^0-9]", ""));
		if (s.contains("d")) {
			return value * 60* 60*24;
		}
		if (s.contains("h")) {
			return value * 60 * 60;
		}
		if (s.contains("m")) {
			return value * 60;
		}
		if (s.contains("s")) {
			return value;
		}
		return value;
	}

	@Override
	public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
		List<String> list = new ArrayList<>();
		if (invocation.arguments().length == 0) {
			for (Player p : proxy.getAllPlayers()) {
				list.add(p.getUsername());
			}
		}
		return CompletableFuture.completedFuture(list);
	}

}
