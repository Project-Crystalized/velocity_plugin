package gg.crystalized;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Scanner;

import static gg.crystalized.FriendSystem.server;

public class KeyGenCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        if(!(invocation.source() instanceof ConsoleCommandSource)){
            invocation.source().sendRichMessage("<dark_red>You don't have permission to do this.</dark_red>");
            return;
        }
        int amount = Integer.parseInt(invocation.arguments()[0]);
        ArrayList<String> keys = new ArrayList<>();
        for(int i = 0; i < amount; i++) {
            String key = generateKey();
            while (!isUnique(key)) {
                key = generateKey();
            }
            keys.add(key);
        }
        for(String key : keys) {
            Velocity_plugin.logger.info(key);
        }
        addToFile(keys);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return SimpleCommand.super.hasPermission(invocation);
    }

    public static String generateKey(){
        final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for(int i = 0; i < 10; i++) {
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        }
        return sb.toString();
    }

    public static boolean isUnique(String key){
        try {
            File file = new File(System.getProperty("user.home") + "/databases/active_keys.txt");
            file.createNewFile();
            Scanner sc = new Scanner(file);
            while(sc.hasNextLine()){
                if(sc.nextLine().replace("\n", "").equals(key)){
                    return false;
                }
            }
            sc.close();
            return true;
        }catch(IOException e ){
            Velocity_plugin.logger.info("failed to check unique key");
            Velocity_plugin.logger.info(e.getMessage());
        }
        return false;
    }

    public static void addToFile(ArrayList<String> keys){
        try {
            File file = new File(System.getProperty("user.home") + "/databases/active_keys.txt");
            BufferedReader reader = new BufferedReader(new FileReader(file));

            String currentLine;
            while ((currentLine = reader.readLine()) != null) {
                keys.add(currentLine);
            }
            writeToFile(keys);
        }catch(IOException e){
            Velocity_plugin.logger.info("failed to add to file");
            Velocity_plugin.logger.info(e.getMessage());
        }
    }

    public static void writeToFile(ArrayList<String> keys){
        try {
            File file = new File(System.getProperty("user.home") + "/databases/active_keys.txt");
            FileWriter writer = new FileWriter(file);
            for (String key : keys) {
                writer.write(key + "\n");
            }
            writer.close();
        }catch(IOException e){
            Velocity_plugin.logger.info("failed to writeToFile");
            Velocity_plugin.logger.info(e.getMessage());
        }
    }
}
class KeyCommand implements SimpleCommand{

    @Override
    public void execute(Invocation invocation) {
        if(!(invocation.source() instanceof Player p)){
            return;
        }
        if(invocation.arguments().length == 0){
            invocation.source().sendRichMessage("<dark_aqua>Please input your access key.</dark_aqua>");
            return;
        }
        String key = invocation.arguments()[0].replace("\n", "");
        if(KeyGenCommand.isUnique(key)){
            invocation.source().sendRichMessage("<dark_red>This key doesn't exist or has already been used.</dark_red>");
            return;
        }

        if(Databases.isPlayerInDatabase(p.getUniqueId())){
            invocation.source().sendRichMessage("<dark_red>You already have access.</dark_red>");
            return;
        }
        deleteKey(key);

        Optional<RegisteredServer> s = server.getServer("lobby");
        if(s.isEmpty()) return;
        p.createConnectionRequest(s.get()).connect();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return SimpleCommand.super.hasPermission(invocation);
    }

    public static void deleteKey(String key){
        try {
            File file = new File(System.getProperty("user.home") + "/databases/active_keys.txt");

            BufferedReader reader = new BufferedReader(new FileReader(file));

            String currentLine;
            ArrayList<String> lines = new ArrayList<>();
            while ((currentLine = reader.readLine()) != null) {
                String trimmedLine = currentLine.trim();
                if (trimmedLine.equals(key)) continue;
                lines.add(currentLine);
            }
            new FileWriter(file, false).close();
            KeyGenCommand.writeToFile(lines);
            reader.close();
        }catch(IOException e){
            Velocity_plugin.logger.info("failed to delete line from file");
            Velocity_plugin.logger.info(e.getMessage());
        }
    }
}
