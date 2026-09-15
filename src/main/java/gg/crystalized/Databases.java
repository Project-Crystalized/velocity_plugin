package gg.crystalized;

import com.velocitypowered.api.proxy.Player;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;

public class Databases {
    public static String dbDir() {
        String d = System.getenv("CRYSTALIZED_DB_DIR");
        if (d == null || d.isBlank()) d = System.getProperty("user.home") + "/databases/test_dbs";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(d));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create database directory: " + d, e);
        }
        return d;
    }
    public static final String LOBBY = "jdbc:sqlite:" + dbDir() + "/lobby_db.sql?busy_timeout=5000";

    public static UUID getUUID(String name){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT player_uuid FROM LobbyPlayers WHERE player_name = ?;");
            prep.setString(1, name);
            ResultSet set = prep.executeQuery();
            set.next();
            ByteBuffer bb = ByteBuffer.wrap(set.getBytes("player_uuid"));
            long high = bb.getLong();
            long low = bb.getLong();
            return new UUID(high, low);
        } catch (SQLException e) {
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get uuid for name");
            return null;
        }
    }
    public static boolean isPlayerInDatabase(UUID p){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT COUNT(*) AS count FROM LobbyPlayers WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            if(prep.executeQuery().getInt("count") > 0){
                return true;
            }
            return false;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't check existence in database for UUID: " + p);
            return false;
        }
    }

    public static void deletePlayerData(Player p){
        try (Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("DELETE FROM LobbyPlayers WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.executeUpdate();
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't delete data for " + p.getUsername() + "UUID: " + p.getUniqueId());
        }
    }

    public static void deleteSettings(Player p){
        try (Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("DELETE FROM Settings WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.executeUpdate();
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't delete settings for " + p.getUsername() + "UUID: " + p.getUniqueId());
        }
    }

    public static ArrayList<Object[]> fetchFriendsWithNames(Player p){
        return fetchFriendsWithNames(p.getUniqueId());
    }

    public static ArrayList<Object[]> fetchFriendsWithNames(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT f.friend_uuid, lp.player_name FROM Friends f LEFT JOIN LobbyPlayers lp ON lp.player_uuid = f.friend_uuid WHERE f.player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                Object[] o = new Object[2];
                o[0] = set.getObject(1);
                o[1] = set.getObject(2);
                list.add(o);
            }

            return list;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get friend data for UUID: " + uuid);
            return null;
        }
    }

    public static void addFriend(Player p, Player friend){
        Properties sqlprop = new Properties();
        sqlprop.put("transaction_mode", "IMMEDIATE");
        try(Connection conn = DriverManager.getConnection(LOBBY, sqlprop)){
            conn.setAutoCommit(false);
            PreparedStatement prep = conn.prepareStatement("INSERT INTO Friends(player_uuid, friend_uuid, date) VALUES(?, ?, ?);");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, uuid_to_bytes(friend));
            String date = "";
            LocalDate now = LocalDate.now();
            date = date + now.getDayOfMonth() + " " + styleWord(now.getMonth().toString()) + " " + now.getYear();
            prep.setString(3, date);
            prep.executeUpdate();
            prep.setBytes(1, uuid_to_bytes(friend));
            prep.setBytes(2, uuid_to_bytes(p));
            prep.executeUpdate();
            conn.commit();
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("failed adding friends to database");
        }
    }

    public static void removeFriend(Player p, byte[] friend){
        Properties sqlprop = new Properties();
        sqlprop.put("transaction_mode", "IMMEDIATE");
        try(Connection conn = DriverManager.getConnection(LOBBY, sqlprop)){
            conn.setAutoCommit(false);
            PreparedStatement prep = conn.prepareStatement("DELETE FROM Friends WHERE player_uuid = ? AND friend_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, friend);
            prep.executeUpdate();
            prep.setBytes(1, friend);
            prep.setBytes(2, uuid_to_bytes(p));
            prep.executeUpdate();
            conn.commit();
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("failed removing friends from database");
        }
    }

    public static void updatePlayerNames(Player p){
        Properties sqlprop = new Properties();
        sqlprop.put("transaction_mode", "IMMEDIATE");
        try(Connection conn = DriverManager.getConnection(LOBBY, sqlprop)){
            conn.setAutoCommit(false);
            String makeNewEntry = "UPDATE LobbyPlayers SET player_name = ? WHERE player_uuid = ?";
            PreparedStatement prepared = conn.prepareStatement(makeNewEntry);
            prepared.setString(1, p.getUsername());
            prepared.setBytes(2, uuid_to_bytes(p));
            prepared.executeUpdate();
            conn.commit();
        }catch(SQLException e) {
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't make database entry for " + p.getUsername() + " UUID: " + p.getUniqueId());
        }
    }
    /*
    public static void updateSkin(Player p){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            String makeNewEntry = "UPDATE LobbyPlayers SET skin_url = ? WHERE player_uuid = ?";
            PreparedStatement prepared = conn.prepareStatement(makeNewEntry);
            prepared.setString(1, p.getPlayerSettings().getTextures().getSkin().toString());
            prepared.setBytes(2, uuid_to_bytes(p));
            prepared.executeUpdate();
        }catch(SQLException e) {
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't make database entry for " + p.getName() + " UUID: " + p.getUniqueId());
        }
    }
     */
    public static void setOnline(Player p, boolean online){
        Properties sqlprop = new Properties();
        sqlprop.put("transaction_mode", "IMMEDIATE");
        try(Connection conn = DriverManager.getConnection(LOBBY, sqlprop)){
            conn.setAutoCommit(false);
            String makeNewEntry = "UPDATE LobbyPlayers SET online = ? WHERE player_uuid = ?";
            PreparedStatement prepared = conn.prepareStatement(makeNewEntry);
            int on = 0;
            if(online) on = 1;
            prepared.setInt(1, on);
            prepared.setBytes(2, uuid_to_bytes(p));
            prepared.executeUpdate();
            conn.commit();
        }catch(SQLException e) {
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't set online for " + p.getUsername() + " UUID: " + p.getUniqueId());
        }
    }

    public static byte[] uuid_to_bytes(Player p) {
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

    public static String styleWord(String word){
        word = word.toLowerCase();
        char[] c = word.toCharArray();
        c[0] = Character.toUpperCase(c[0]);
        StringBuilder s = new StringBuilder();
        for(char ch : c){
            s.append(ch);
        }
        return s.toString();
    }

    public static boolean areFriends(Player p, Player friend){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT COUNT(*) AS count FROM Friends WHERE player_uuid = ? AND friend_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, uuid_to_bytes(friend));
            if(prep.executeQuery().getInt("count") > 0){
                return true;
            }
            return false;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't check excistence in database for " + p.getUsername() + " UUID: " + p.getUniqueId());
            return false;
        }
    }

    public static boolean areFriends(Player p, byte[] friend){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT COUNT(*) AS count FROM Friends WHERE player_uuid = ? AND friend_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, friend);
            if(prep.executeQuery().getInt("count") > 0){
                return true;
            }
            return false;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't check excistence in database for " + p.getUsername() + " UUID: " + p.getUniqueId());
            return false;
        }
    }

    public static HashMap<String, Object> fetchSettings(Player p){
        return fetchSettings(p.getUniqueId());
    }

    public static HashMap<String, Object> fetchSettings(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT * FROM Settings WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            set.next();
            ResultSetMetaData data = set.getMetaData();
            int count = data.getColumnCount();
            HashMap<String, Object> map = new HashMap<>();
            for(int i = 1; i <= count; i++){
                map.put(data.getColumnLabel(i), set.getObject(data.getColumnLabel(i)));
            }
            return map;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get settings data for UUID: " + uuid);
            return null;
        }
    }

    public static HashMap<String, Object> fetchPlayerData(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT * FROM LobbyPlayers WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            set.next();
            ResultSetMetaData data = set.getMetaData();
            int count = data.getColumnCount();
            HashMap<String, Object> map = new HashMap<>();
            for(int i = 1; i <= count; i++){
                map.put(data.getColumnLabel(i), set.getObject(data.getColumnLabel(i)));
            }
            return map;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get player data for UUID: " + uuid);
            return null;
        }
    }

    public static ArrayList<Object[]> fetchCosmetics(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT cosmetic_id, currently_wearing FROM Cosmetics WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                list.add(new Object[]{set.getObject(1), set.getObject(2)});
            }
            return list;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get cosmetics data for UUID: " + uuid);
            return new ArrayList<>();
        }
    }

    public static ArrayList<Object[]> fetchQuests(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT quest, done, claimed FROM Quests WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                list.add(new Object[]{set.getObject(1), set.getObject(2), set.getObject(3)});
            }
            return list;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get quests data for UUID: " + uuid);
            return new ArrayList<>();
        }
    }

    public static ArrayList<Object[]> fetchAchievements(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT internal_name, progress, stage, done, claimed FROM Achievements WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                list.add(new Object[]{set.getObject(1), set.getObject(2), set.getObject(3), set.getObject(4), set.getObject(5)});
            }
            return list;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get achievements data for UUID: " + uuid);
            return new ArrayList<>();
        }
    }

    public static ArrayList<Object[]> fetchParkourTimes(UUID uuid){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT course, best_time, date FROM ParkourTimes WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(uuid));
            ResultSet set = prep.executeQuery();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                list.add(new Object[]{set.getObject(1), set.getObject(2), set.getObject(3)});
            }
            return list;
        }catch(SQLException e){
            Velocity_plugin.logger.info(e.getMessage());
            Velocity_plugin.logger.info("couldn't get parkour data for UUID: " + uuid);
            return new ArrayList<>();
        }
    }
}
