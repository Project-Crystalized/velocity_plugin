package gg.crystalized;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

public class SetRankedCommand implements SimpleCommand {
	private ProxyServer proxyServer;

	public static final MinecraftChannelIdentifier LS_CHANNEL = MinecraftChannelIdentifier.from("crystalized:litestrike");

	public SetRankedCommand(ProxyServer proxyServer) {
		this.proxyServer = proxyServer;
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
		RegisteredServer rs = proxyServer.getServer(invocation.arguments()[0]).orElseThrow();

		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF(invocation.arguments()[1]);
		boolean worked = rs.sendPluginMessage(LS_CHANNEL, out.toByteArray());

		if (worked) {
			invocation.source().sendPlainMessage("sent message \"" + invocation.arguments()[1] + "\" to the server");
		} else {
			invocation.source().sendPlainMessage(
					"ERROR Failed sending the message to the server. If no player is on the server, no PuginMessage can be sent, so maybe join the server.");
		}
	}

	@Override
	public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
		List<String> list = new ArrayList<>();
		if (invocation.arguments().length == 0 || invocation.arguments().length == 1) {
			for (RegisteredServer rs : proxyServer.getAllServers()) {
				list.add(rs.getServerInfo().getName());
			}
		} else if (invocation.arguments().length == 2) {
			list = List.of("ranked_on", "ranked_off");
		}
		return CompletableFuture.completedFuture(list);
	}
}
