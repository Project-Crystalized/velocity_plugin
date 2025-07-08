package gg.crystalized;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class Databases {
    public static final String LOBBY = "jdbc:sqlite:" + System.getProperty("user.home") + "/databases/lobby_db.sql";

    public static HashMap<String, Object> fetchPlayerData(Player p){
        try(Connection conn = DriverManager.getConnection(LOBBY)) {
            PreparedStatement prep = conn.prepareStatement("SELECT * FROM LobbyPlayers WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
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
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't get data for " + p.getName() + "UUID: " + p.getUniqueId());
            return null;
        }
    }

    public static HashMap<String, Object> fetchPlayerData(byte[] p) {
        try (Connection conn = DriverManager.getConnection(LOBBY)) {
            PreparedStatement prep = conn.prepareStatement("SELECT * FROM LobbyPlayers WHERE player_uuid = ?;");
            prep.setBytes(1, p);
            ResultSet set = prep.executeQuery();
            set.next();
            ResultSetMetaData data = set.getMetaData();
            int count = data.getColumnCount();
            HashMap<String, Object> map = new HashMap<>();
            for (int i = 1; i <= count; i++) {
                map.put(data.getColumnLabel(i), set.getObject(data.getColumnLabel(i)));
            }
            return map;
        } catch (SQLException e) {
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't get data for " + p.getName() + "UUID: " + p.getUniqueId());
            return null;
        }
    }

    public static ArrayList<Object[]> fetchFriends(Player p){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("SELECT * FROM Friends WHERE player_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            ResultSet set = prep.executeQuery();
            ResultSetMetaData data = set.getMetaData();
            int count = data.getColumnCount();
            ArrayList<Object[]> list = new ArrayList<>();
            while(set.next()) {
                while(set.next()) {
                    Object[] o = new Object[3];
                    for (int i = 1; i <= count; i++) {
                        o[i-1] = set.getObject(data.getColumnLabel(i));
                    }
                    list.add(o);
                }
            }
            return list;
        }catch(SQLException e){
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't get friend data for " + p.getName() + " UUID: " + p.getUniqueId());
            return null;
        }
    }

    public static void addFriend(Player p, Player friend){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("INSERT INTO Friends(player_uuid, friend_uuid, date) VALUES(?, ?, ?);");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, uuid_to_bytes(friend));
            String date = "";
            LocalDate now = LocalDate.now();
            date = date + now.getDayOfMonth() + "-" + now.getMonth() + "-" + now.getYear();
            prep.setString(3, date);
            prep.executeUpdate();
        }catch(SQLException e){
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("failed adding cosmetic to database");
        }
    }

    public static void removeFriend(Player p, byte[] friend){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            PreparedStatement prep = conn.prepareStatement("DELETE FROM Friends WHERE player_uuid = ? AND friend_uuid = ?;");
            prep.setBytes(1, uuid_to_bytes(p));
            prep.setBytes(2, friend);
            prep.executeUpdate();
            prep.setBytes(1, friend);
            prep.setBytes(2, uuid_to_bytes(p));
            prep.executeUpdate();
        }catch(SQLException e){
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("failed adding cosmetic to database");
        }
    }

    public static void updatePlayerNames(Player p){
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            String makeNewEntry = "UPDATE LobbyPlayers SET player_name = ? WHERE player_uuid = ?";
            PreparedStatement prepared = conn.prepareStatement(makeNewEntry);
            prepared.setString(1, p.getUsername());
            prepared.setBytes(2, uuid_to_bytes(p));
            prepared.executeUpdate();
        }catch(SQLException e) {
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't make database entry for " + p.getName() + " UUID: " + p.getUniqueId());
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
        try(Connection conn = DriverManager.getConnection(LOBBY)){
            String makeNewEntry = "UPDATE LobbyPlayers SET online = ? WHERE player_uuid = ?";
            PreparedStatement prepared = conn.prepareStatement(makeNewEntry);
            int on = 0;
            if(online) on = 1;
            prepared.setInt(1, on);
            prepared.setBytes(2, uuid_to_bytes(p));
            prepared.executeUpdate();
        }catch(SQLException e) {
            //Bukkit.getLogger().warning(e.getMessage());
            //Bukkit.getLogger().warning("couldn't make database entry for " + p.getName() + " UUID: " + p.getUniqueId());
        }
    }

    public static byte[] uuid_to_bytes(Player p) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        UUID uuid = p.getUniqueId();
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}
