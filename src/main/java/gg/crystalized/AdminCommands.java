package gg.crystalized;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Optional;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

public class AdminCommands {

	public static BrigadierCommand createSendCommand(ProxyServer proxy) {
		LiteralCommandNode<CommandSource> sendNode = BrigadierCommand.literalArgumentBuilder("send")
				.requires(source -> !(source instanceof Player) || Velocity_plugin.is_admin((Player) source))
				.executes(ctx -> {
					ctx.getSource().sendMessage(text("Usage: /send <player> <server>").color(RED));
					return Command.SINGLE_SUCCESS;
				})
				.then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							proxy.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
							return builder.buildFuture();
						})
						.then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									proxy.getAllServers().forEach(s -> builder.suggest(s.getServerInfo().getName()));
									return builder.buildFuture();
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
							proxy.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
							return builder.buildFuture();
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
}
