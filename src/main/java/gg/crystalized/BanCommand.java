package gg.crystalized;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import static gg.crystalized.Databases.uuid_to_bytes;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

public class BanCommand implements SimpleCommand {
	public static final String URL = "jdbc:sqlite:" + System.getProperty("user.home") + "/databases/ban_db.sql";
	private ProxyServer proxy;

	public BanCommand(ProxyServer proxy) {
		this.proxy = proxy;
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (Exception e) {
			Velocity_plugin.logger.error("" + e);
		}
		String create_ban_table = "CREATE TABLE IF NOT EXISTS BanTable ("
				+ "banned_uuid 		BLOB UNIQUE,"
				+ "banned_since		INTEGER,"
				+ "banned_until		INTEGER,"
				+ "banned_by			BLOB,"
				+ "banned_for			TEXT"
				+ ");";

		try (Connection conn = DriverManager.getConnection(URL)) {
			Statement stmt = conn.createStatement();
			stmt.execute(create_ban_table);
		} catch (SQLException e) {
			Velocity_plugin.logger.warn(e.getMessage());
			Velocity_plugin.logger.warn("continueing without ban-database");
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
			int current_time = ((int) (System.currentTimeMillis() / 1000));

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
				Velocity_plugin.logger.warn("lifted a ban");
				return false;
			}
		} catch (SQLException e) {
			Velocity_plugin.logger.warn(e.getMessage());
			Velocity_plugin.logger.warn("continueing without ban-database");
			return false;
		}
	}

	public static String getBannedUntil(UUID p){
		try (Connection conn = DriverManager.getConnection(URL)) {
			PreparedStatement game_stmt = conn.prepareStatement("SELECT banned_until FROM BanTable WHERE banned_uuid = ?;");
			game_stmt.setBytes(1, uuid_to_bytes(p));
			ResultSet res = game_stmt.executeQuery();
			res.next();
			int banned_until = res.getInt("banned_until");
			return get_duration(banned_until - (System.currentTimeMillis() / 1000));
		} catch (SQLException e) {
			Velocity_plugin.logger.warn(e.getMessage());
			Velocity_plugin.logger.warn("couldn't get ban metadata");
		}
		return "unknown";
	}

	public static String getBannedFor(UUID p){
		try (Connection conn = DriverManager.getConnection(URL)) {
			PreparedStatement game_stmt = conn.prepareStatement("SELECT banned_for FROM BanTable WHERE banned_uuid = ?;");
			game_stmt.setBytes(1, uuid_to_bytes(p));
			ResultSet res = game_stmt.executeQuery();
			res.next();
			return res.getString("banned_for");
		} catch (SQLException e) {
			Velocity_plugin.logger.warn(e.getMessage());
			Velocity_plugin.logger.warn("couldn't get ban metadata");
		}
		return "no reason specified";
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		Player p = (Player) invocation.source();
		return Velocity_plugin.is_admin(p);
	}

	@Override
	public void execute(Invocation invocation) {
		try {
			String[] args = invocation.arguments();
			Player player = null;
			UUID uuid = null;

			if(proxy.getPlayer(args[0]).isPresent()){
				player = proxy.getPlayer(args[0]).get();
				uuid = player.getUniqueId();
			}else {
				uuid = Databases.getUUID(args[0]);
			}

			int duration = 300;
			if (args.length >= 2) {
				duration = get_duration(args[1]);
			}
			String reason = "";
			if (args.length >= 3) {
				for (String arg : args) {
					if (arg == args[0] || arg == args[1])
						continue;
					reason += arg;
				}
			}
			invocation.source().sendMessage(Component.text("banning " + args[0] + " for " + duration + "seconds"));
			if(player != null) player.disconnect(Component.translatable("crystalized.proxy.ban").append(Component.text(reason)));

			String save_ban = "INSERT INTO BanTable(banned_uuid, banned_since, banned_until, banned_by, banned_for) VALUES(?, unixepoch(), ?, ?, ?)";
			try (Connection conn = DriverManager.getConnection(URL)) {
				PreparedStatement game_stmt = conn.prepareStatement(save_ban);

				game_stmt.setBytes(1, uuid_to_bytes(uuid));
				game_stmt.setInt(2, ((int) (System.currentTimeMillis() / 1000)) + duration);
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
			invocation.source().sendMessage(
					Component.text("usage: /ban <player_name> <duration, e.g. 1d, 5m> <reason>").color(RED));
		}
	}

	private static byte[] uuid_to_bytes(Player p) {
		ByteBuffer bb = ByteBuffer.allocate(16);
		UUID uuid = p.getUniqueId();
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	public static byte[] uuid_to_bytes(UUID uuid) {
		ByteBuffer bb = ByteBuffer.allocate(16);
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	private int get_duration(String s) {
		int value = Integer.parseInt(s.replaceAll("[^0-9]", ""));
		if (s.contains("d")) {
			return value * 60 * 60 * 24;
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

	private static String get_duration(long s) {
		if (s >= 86400) {
			return s / 60 / 60 / 24 + "d";
		}
		if (s >= 3600) {
			return s / 60 / 60 + "h";
		}
		if (s >= 60) {
			return s / 60 + "m";
		}
		return s + "s";
	}

	@Override
	public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
		List<String> list = new ArrayList<>();
		if (invocation.arguments().length == 0 || invocation.arguments().length == 1) {
			for (Player p : proxy.getAllPlayers()) {
				list.add(p.getUsername());
			}
		} else if (invocation.arguments().length == 2) {
			list = List.of("5m", "1h", "1d");
		} else if (invocation.arguments().length == 3) {
			list = List.of("cheating", "bad language", "inappropriate skin", "whatever reason you like");
		}
		return CompletableFuture.completedFuture(list);
	}

}

class UnbanCommand implements SimpleCommand{
	private ProxyServer proxy;
	public UnbanCommand(ProxyServer proxy){
		this.proxy = proxy;
	}
	@Override
	public void execute(Invocation invocation) {
		String[] args = invocation.arguments();
		Player player = null;
		UUID uuid = null;

		if(proxy.getPlayer(args[0]).isPresent()){
			player = proxy.getPlayer(args[0]).get();
			uuid = player.getUniqueId();
		}else {
			uuid = Databases.getUUID(args[0]);
		}

		if(uuid == null){
			invocation.source().sendMessage(Component.text("Couldn't find player").color(RED));
		}

		if(!Velocity_plugin.ban_command.isBanned(uuid)){
			invocation.source().sendMessage(Component.text("This player isn't banned").color(RED));
		}

		try (Connection conn = DriverManager.getConnection(BanCommand.URL)){
			String lift_ban = "DELETE FROM BanTable WHERE banned_uuid = ?;";
			PreparedStatement lift_ban_stmt = conn.prepareStatement(lift_ban);
			lift_ban_stmt.setBytes(1, BanCommand.uuid_to_bytes(uuid));
			lift_ban_stmt.executeUpdate();
        } catch (SQLException e) {
			invocation.source().sendMessage(Component.text("Couldn't unban player").color(RED));
			invocation.source().sendMessage(Component.text(e.getMessage()).color(RED));
        }
        Velocity_plugin.logger.warn("lifted a ban");
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		if (invocation.source() instanceof ConsoleCommandSource) {
			return true;
		}
		Player p = (Player) invocation.source();
		return Velocity_plugin.is_admin(p);
	}
}
