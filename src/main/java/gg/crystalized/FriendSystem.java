package gg.crystalized;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.*;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.BOLD;

class FriendsCommand implements SimpleCommand{

    private FriendSystem fs;
    private Velocity_plugin plugin;
    private ProxyServer server;

    public FriendsCommand(FriendSystem fs, Velocity_plugin plugin, ProxyServer server) {
        this.fs = fs;
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if(invocation.source() instanceof ConsoleCommandSource) {
            return false;
        }
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        if(args.length == 0){
            return Arrays.asList("request", "remove", "list", "accept", "deny");
        }

        if(args[0].equals("request")){
            List<String> allPlayers = new ArrayList<>();
            for(Player p : server.getAllPlayers()){
                allPlayers.add(p.getUsername());
            }
            return allPlayers;
        }

        if(args[0].equals("remove")){
            return FriendSystem.getAllFriendsNames((Player)invocation.source());
        }

        if(args[0].equals("list")){
            return Arrays.asList("online", "offline");
        }

        if(args[0].equals("accept") || args[0].equals("deny")){
            List<String> list = new ArrayList<>();
            for(Player p : Friend.getFriendObject((Player) invocation.source()).currentlyRequesting){
                list.add(p.getUsername());
            }
            return list;
        }
        
        return List.of();
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        Player executer = (Player) invocation.source();
        if (args.length == 0) {
            return;
        }

        if(args[0].equals("request") || args[0].equals("remove") || args[0].equals("accept") || args[0].equals("deny")){
            if(args.length < 2){
                executer.sendMessage(text("Please specify a player").color(RED));
                return;
            }
        }

        if(args[0].equals("list")){

            ArrayList<String> friends = FriendSystem.getAllFriendsNames(executer);

            if(args.length < 2){
                Component message = text("These are all of your friends: ").color(YELLOW);
                for(String s : friends){
                    message = message.append(text("\n" + s));
                }
                executer.sendMessage(message);
                return;
            }

            ArrayList<String> online = new ArrayList<>();
            for(Player p : server.getAllPlayers()){
                if(friends.contains(p.getUsername())){
                    online.add(p.getUsername());
                    friends.remove(p.getUsername());
                }
            }

            if(args[1].equals("online")){
                if(online.isEmpty()){
                    executer.sendMessage(text("None of your friends are currently online.").color(RED));
                    return;
                }
                Component message = text("These friends are currently online: ").color(YELLOW);
                for(String s : online){
                    message = message.append(text("\n" + s));
                }
                executer.sendMessage(message);
                return;
            }

            if(args[1].equals("offline")){
                if(friends.isEmpty()){
                    executer.sendMessage(text("None of your friends are currently offline.").color(RED));
                    return;
                }
                Component message = text("These friends are currently offline: ").color(YELLOW);
                for(String s : friends){
                    message = message.append(text("\n" + s));
                }
                executer.sendMessage(message);
                return;
            }
        }

        if(Objects.equals(args[1], executer.getUsername())){
            executer.sendMessage(text("Please input a player who's not you").color(RED));
            return;
        }

        if(args[0].equals("request")){
            Player requested = null;
            for(Player p : server.getAllPlayers()){
                if(p.getUsername().equals(args[1])){
                    requested = p;
                    break;
                }
            }

            if(requested == null){
                executer.sendMessage(text("There are no players called " + args[1] + " online").color(RED));
                return;
            }
            if(Databases.areFriends(executer, requested)){
                executer.sendMessage(text("You are already friends with " + args[1]).color(RED));
                return;
            }

            if(!Settings.isReceiveAllowed("friends_requests", requested, executer)){
                executer.sendMessage(text("You cannot friend request " + requested.getUsername()).color(RED));
                return;
            }

            executer.sendMessage(text("Sent " + args[1] + " a friend request!").color(YELLOW));
            Friend.getFriendObject(requested).currentlyRequesting.add(executer);

            Component accept;
            Component deny;
            if(!FloodgateApi.getInstance().isFloodgatePlayer(requested.getUniqueId())) {
                accept = translatable("crystalized.generic.accept").color(GREEN).decoration(BOLD, true).clickEvent(ClickEvent.runCommand("/friend accept " + executer.getUsername()));
                deny = translatable("crystalized.generic.deny").color(RED).decoration(BOLD, true).clickEvent(ClickEvent.runCommand("/friend deny " + executer.getUsername()));
            }else{
                accept = text("You can do /accept to accept").color(GREEN);
                deny = text("or /deny to deny").color(RED);
            }
            requested.sendMessage(text(executer.getUsername() + " send you a friend request ").color(YELLOW).append(accept).append(text(" ")).append(deny));
        }

        if(args[0].equals("accept") || args[0].equals("deny")){
            Friend exe = Friend.getFriendObject(executer);
            Player requester = null;
            for(Player p : server.getAllPlayers()){
                if(p.getUsername().equals(args[1])){
                    requester = p;
                    break;
                }
            }
            if(!exe.currentlyRequesting.contains(requester)){
                executer.sendMessage(text("There are no pending friend requests from " + requester.getUsername()).color(RED));
                return;
            }
            if(args[0].equals("accept")){
                Databases.addFriend(executer, requester);
                requester.sendMessage(text(executer.getUsername() + " has accepted your friend request").color(YELLOW));
                executer.sendMessage(text("Accepted friend request from " + requester.getUsername()).color(YELLOW));
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Settings");
                out.writeUTF("player_visibility");
                executer.sendPluginMessage(Velocity_plugin.CRYSTAL_CHANNEL, out.toByteArray());
            }

            if(args[0].equals("deny")) {
                executer.sendMessage(text("Denied friend request from " + requester.getUsername()).color(YELLOW));
            }
            exe.currentlyRequesting.remove(requester);
        }

        if(args[0].equals("remove")){
            ArrayList<Object[]> list = Databases.fetchFriends(executer);
            byte[] uuid = null;
            for (Object[] o : list) {
                if((Databases.fetchPlayerData((byte[]) o[1]).get("player_name")).equals(args[1])){
                    uuid = (byte[])o[1];
                }
            }
            if(!Databases.areFriends(executer, uuid)){
                executer.sendMessage(text(args[1] + " is not your friend").color(RED));
                return;
            }
            Databases.removeFriend(executer, uuid);
            executer.sendMessage(text("Removed " + args[1] + " from friends").color(YELLOW));
        }

    }
}

public class FriendSystem {
    public static ProxyServer server;
    public FriendSystem(ProxyServer server, Velocity_plugin plugin) {
        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMetaParty =
                commandManager.metaBuilder("friend").aliases("f").plugin(plugin).build();
        commandManager.register(commandMetaParty, new FriendsCommand(this, plugin,
                server));
        this.server = server;
    }

    public static ArrayList<String> getAllFriendsNames(Player p) {
        ArrayList<Object[]> list = Databases.fetchFriends(p);
        ArrayList<String> friends = new ArrayList<>();
        for (Object[] o : list) {
            friends.add((String) Databases.fetchPlayerData((byte[]) o[1]).get("player_name"));
        }
        return friends;
    }
}

class Friend {
    public static ArrayList<Friend> allFriends = new ArrayList<>();
    public List<Player> currentlyRequesting = new ArrayList<>();
    Player player;

    public Friend(Player p){
        player = p;
    }

    public static Friend getFriendObject(Player p){
        for(Friend f : allFriends){
            if(f.player.equals(p)){
                return f;
            }
        }
        return null;
    }
}

