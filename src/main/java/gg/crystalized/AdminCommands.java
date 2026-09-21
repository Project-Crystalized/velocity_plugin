package gg.crystalized;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

public class AdminCommands {
	static CompletableFuture<Suggestions> filteredSuggest(SuggestionsBuilder builder, Stream<String> candidates) {
		String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
		candidates.filter(c -> c.toLowerCase(Locale.ROOT).startsWith(partial)).forEach(builder::suggest);
		return builder.buildFuture();
	}


	public static BrigadierCommand createSendCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> sendNode = BrigadierCommand.literalArgumentBuilder("send")
				.requires(source -> !(source instanceof Player) || Velocity_plugin.is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /send <player> <server>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							return filteredSuggest(builder, proxy.getAllPlayers().stream().map(Player::getUsername));
							})
						.then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									return filteredSuggest(builder, proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()));
									})
								.executes(ctx -> {
									String playerName = ctx.getArgument("player", String.class);
									String serverName = ctx.getArgument("server", String.class);
									CommandSource source = ctx.getSource();
									Optional<Player> target = proxy.getPlayer(playerName);
									if (target.isEmpty()) {
										source.sendMessage(text("Player not found: " + playerName).color(RED));
										return Command.SINGLE_SUCCESS;
									}
									Optional<RegisteredServer> destination = proxy.getServer(serverName);
									if (destination.isEmpty()) {
										source.sendMessage(text("Server not found: " + serverName).color(RED));
										return Command.SINGLE_SUCCESS;
									}
									target.get().createConnectionRequest(destination.get()).connect();
									source.sendMessage(text("Sent " + target.get().getUsername() + " to " + destination.get().getServerInfo().getName()).color(NamedTextColor.GREEN));
									return Command.SINGLE_SUCCESS;
								})
						)
				)
				.build();
		return new BrigadierCommand(sendNode);
	}

	public static BrigadierCommand createHubCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> hubNode = BrigadierCommand.literalArgumentBuilder("hub")
				.executes(ctx -> {
					if (ctx.getSource() instanceof Player p) {
						Optional<ServerConnection> opt = p.getCurrentServer();
						if(opt.isEmpty() || opt.get().getServer().getServerInfo().getName().contains("limbo")) {
							return Command.SINGLE_SUCCESS;
						}
						Optional<RegisteredServer> lobby = proxy.getServer("lobby");
						if (lobby.isEmpty()) {
							p.sendMessage(text("Lobby server not found.").color(RED));
							return Command.SINGLE_SUCCESS;
						}
						p.createConnectionRequest(lobby.get()).connect();
					} else {
						ctx.getSource().sendMessage(text("Only players can use this command.").color(RED));
					}
					return Command.SINGLE_SUCCESS;
				})
				.build();
		return new BrigadierCommand(hubNode);
	}

	public static BrigadierCommand createMsgCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> msgNode = BrigadierCommand.literalArgumentBuilder("msg")
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /msg <player> <message>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							return filteredSuggest(builder, proxy.getAllPlayers().stream().map(Player::getUsername));
							})
						.then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
								.executes(ctx -> {
									String targetName = ctx.getArgument("player", String.class);
									String rawMessage = ctx.getArgument("message", String.class);
									CommandSource source = ctx.getSource();
									Player target = proxy.getPlayer(targetName).orElse(null);
									if (target == null) {
										source.sendMessage(translatable("crystalized.proxy.msg.not_found").color(RED));
										return Command.SINGLE_SUCCESS;
									}
									String messengerName = "Console";
									if (source instanceof Player sender) {
										messengerName = sender.getUsername();
										if (!Settings.isAllowed("dms", target, sender)) {
											source.sendMessage(translatable("crystalized.proxy.msg.not_allowed", List.of(Component.text(target.getUsername()))).color(RED));
											return Command.SINGLE_SUCCESS;
										}
									}
									Component message = text(" " + rawMessage);
									source.sendMessage(text("[").append(translatable("crystalized.generic.you")).append(text(" -> " + target.getUsername() + "] ")).append(message).color(NamedTextColor.AQUA));
									target.sendMessage(text("[" + messengerName + " -> ").append(translatable("crystalized.generic.you")).append(text("] ")).append(message).color(NamedTextColor.AQUA));
									return Command.SINGLE_SUCCESS;
								})
						)
				)
				.build();
		return new BrigadierCommand(msgNode);
	}

	public static BrigadierCommand createBroadcastCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> broadcastNode = BrigadierCommand.literalArgumentBuilder("broadcast")
				.requires(source -> !(source instanceof Player) || Velocity_plugin.is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /broadcast <message>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
						.executes(ctx -> {
							String rawMessage = ctx.getArgument("message", String.class);
							Component message = translatable("crystalized.generic.broadcast").color(YELLOW);
							message = message.append(text(rawMessage));
							message = message.append(text("\n"));
							Audience.audience(proxy.getAllPlayers()).sendMessage(message);
							return Command.SINGLE_SUCCESS;
						})
				)
				.build();
		return new BrigadierCommand(broadcastNode);
	}

	public static BrigadierCommand createPlayerinfoCommand(ProxyServer proxy, Velocity_plugin plugin) {
		LiteralCommandNode<CommandSource> playerinfoNode = BrigadierCommand.literalArgumentBuilder("playerinfo")
				.requires(source -> !(source instanceof Player) || Velocity_plugin.is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /playerinfo <player>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							return filteredSuggest(builder, proxy.getAllPlayers().stream().map(Player::getUsername));
							})
						.executes(ctx -> {
							String name = ctx.getArgument("player", String.class);
							CommandSource source = ctx.getSource();
							Player online = proxy.getPlayer(name).orElse(null);
							UUID uuid;
							if (online != null) {
								uuid = online.getUniqueId();
							} else {
								uuid = Databases.getUUID(name);
								if (uuid == null) {
									source.sendMessage(text("Player not found: " + name).color(RED));
									return Command.SINGLE_SUCCESS;
								}
							}
							sendPlayerinfo(source, plugin, name, uuid, online);
							return Command.SINGLE_SUCCESS;
						})
				)
				.build();
		return new BrigadierCommand(playerinfoNode);
	}

	private static void sendPlayerinfo(CommandSource source, Velocity_plugin plugin, String name, UUID uuid, Player online) {
		source.sendMessage(text("Player info: " + name + (online != null ? " (online)" : " (offline)")).color(NamedTextColor.AQUA));
		source.sendMessage(text("UUID: " + uuid));
		if (online != null) {
			source.sendMessage(text("Server: " + online.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("-")));
		}

		HashMap<String, Object> data = Databases.fetchPlayerData(uuid);
		if (data == null) {
			source.sendMessage(text("No database entry.").color(RED));
			return;
		}
		source.sendMessage(text("Account:").color(NamedTextColor.AQUA));
		source.sendMessage(text("  first login: " + formatEpoch(data.get("first_login")) + ", last login: " + formatEpoch(data.get("last_login")) + ", logins: " + str(data.get("times_logged_in"))));
		source.sendMessage(text("  level: " + str(data.get("level")) + ", exp to next: " + str(data.get("exp_to_next_lvl")) + ", money: " + str(data.get("money")) + ", rank: " + str(data.get("rank_id"))));
		source.sendMessage(text("  quest rerolls: " + str(data.get("quest_rerolls")) + ", last quest roll: " + formatEpoch(data.get("last_quest_roll"))));

		ArrayList<Object[]> friendRows = Databases.fetchFriendsWithNames(uuid);
		ArrayList<String> friendNames = new ArrayList<>();
		if (friendRows != null) {
			for (Object[] row : friendRows) {
				if (row[1] != null) {
					friendNames.add((String) row[1]);
				}
			}
		}
		source.sendMessage(text("Friends (" + friendNames.size() + "): " + String.join(", ", friendNames)));

		if (online != null) {
			Party party = plugin.party_system.get_party_of(online);
			if (party == null) {
				source.sendMessage(text("Party: none"));
			} else {
				ArrayList<String> members = new ArrayList<>();
				for (Player m : party.members) {
					members.add(m.getUsername());
				}
				String partyLine = "Party (" + (party.is_leader(online) ? "leader" : "member") + "): " + String.join(", ", members);
				if (!party.invited.isEmpty()) {
					partyLine += " | invited: " + String.join(", ", party.invited);
				}
				source.sendMessage(text(partyLine));
			}
			Friend friendObject = Friend.getFriendObject(online);
			if (friendObject == null || friendObject.currentlyRequesting.isEmpty()) {
				source.sendMessage(text("Pending friend requests: none"));
			} else {
				ArrayList<String> requesting = new ArrayList<>();
				for (Player p : friendObject.currentlyRequesting) {
					requesting.add(p.getUsername());
				}
				source.sendMessage(text("Pending friend requests: " + String.join(", ", requesting)));
			}
			source.sendMessage(text("Queue: " + findQueueState(online)));
		}

		HashMap<String, Object> settings = Databases.fetchSettings(uuid);
		if (settings == null) {
			source.sendMessage(text("Settings: no data"));
		} else {
			source.sendMessage(text("Settings:").color(NamedTextColor.AQUA));
			for (String key : settings.keySet()) {
				if (key.equals("player_uuid")) {
					continue;
				}
				source.sendMessage(text("  " + key + ": " + str(settings.get(key))));
			}
		}

		ArrayList<Object[]> cosmetics = Databases.fetchCosmetics(uuid);
		String wearing = "-";
		ArrayList<String> owned = new ArrayList<>();
		for (Object[] row : cosmetics) {
			owned.add(str(row[0]));
			if (row[1] != null && ((Number) row[1]).intValue() == 1) {
				wearing = str(row[0]);
			}
		}
		source.sendMessage(text("Cosmetics: wearing " + wearing + " | owned (" + owned.size() + "): " + String.join(", ", owned)));

		source.sendMessage(text("Quests:").color(NamedTextColor.AQUA));
		for (Object[] row : Databases.fetchQuests(uuid)) {
			source.sendMessage(text("  " + str(row[0]) + " done=" + yesNo(row[1]) + " claimed=" + yesNo(row[2])));
		}

		source.sendMessage(text("Achievements:").color(NamedTextColor.AQUA));
		for (Object[] row : Databases.fetchAchievements(uuid)) {
			source.sendMessage(text("  " + str(row[0]) + " progress=" + str(row[1]) + " stage=" + str(row[2]) + " done=" + yesNo(row[3]) + " claimed=" + yesNo(row[4])));
		}

		source.sendMessage(text("Parkour:").color(NamedTextColor.AQUA));
		for (Object[] row : Databases.fetchParkourTimes(uuid)) {
			source.sendMessage(text("  " + str(row[0]) + ": " + formatTenths(row[1]) + " (" + formatEpoch(row[2]) + ")"));
		}
	}

	private static String findQueueState(Player online) {
		for (GameQueue q : QueueSystem.queues) {
			if (q.players.contains(online)) {
				return q.type + " (" + q.players.size() + " queued)";
			}
		}
		for (GameQueue q : QueueSystem.queues) {
			for (GameServer gs : q.servers) {
				if (gs.playersInGame.contains(online.getUniqueId())) {
					return "in game on " + gs.server.getServerInfo().getName();
				}
			}
		}
		return "none";
	}

	private static String str(Object o) {
		return o == null ? "-" : o.toString();
	}

	private static String yesNo(Object o) {
		if (o == null) {
			return "-";
		}
		return ((Number) o).intValue() == 1 ? "yes" : "no";
	}

	private static String formatEpoch(Object o) {
		if (o == null) {
			return "-";
		}
		long seconds = ((Number) o).longValue();
		if (seconds <= 0) {
			return "-";
		}
		return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds));
	}

	private static String formatTenths(Object o) {
		if (o == null) {
			return "-";
		}
		long tenths = ((Number) o).longValue();
		return (tenths / 10) + "." + Math.abs(tenths % 10) + "s";
	}
}
