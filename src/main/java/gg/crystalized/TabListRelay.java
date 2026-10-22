package gg.crystalized;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class TabListRelay {

	static final long REFRESH_MILLIS = 1000L;

	private final Object plugin;
	private final ProxyServer server;

	public TabListRelay(Object plugin, ProxyServer server) {
		this.plugin = plugin;
		this.server = server;
		server.getScheduler()
			.buildTask(plugin, this::refreshAll)
			.repeat(REFRESH_MILLIS, TimeUnit.MILLISECONDS)
			.schedule();
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		server.getScheduler().buildTask(plugin, this::refreshAll).schedule();
	}

	void refreshAll() {
		Collection<Player> shared = sharedPlayers(server.getAllPlayers());
		Map<UUID, Component> names = displayNames(shared);
		for (Player viewer : shared) {
			refresh(viewer, shared, names);
		}
	}

	static Map<UUID, Component> displayNames(Collection<Player> players) {
		Map<UUID, Component> displayNames = new HashMap<>(players.size());
		for (Player player : players) {
			UUID id = player.getUniqueId();
			displayNames.put(id, ChatRelay.formatName(ChatRelay.getRank(id), player.getUsername()));
		}
		return displayNames;
	}

	static Collection<Player> sharedPlayers(Collection<Player> players) {
		return players.stream()
			.filter(player -> ChatRelay.isMirrored(ChatRelay.serverName(player)))
			.toList();
	}

	static void refresh(Player viewer, Collection<Player> shared) {
		refresh(viewer, shared, displayNames(shared));
	}

	static void refresh(Player viewer, Collection<Player> shared, Map<UUID, Component> displayNames) {
		String viewerServer = ChatRelay.serverName(viewer);
		if (!ChatRelay.isMirrored(viewerServer)) {
			return;
		}
		if (viewer.getCurrentServer().isEmpty()) {
			return;
		}

		TabList tabList = viewer.getTabList();
		Component header = header(viewerServer);
		Component footer = footer();
		if (!Objects.equals(header, viewer.getPlayerListHeader())
			|| !Objects.equals(footer, viewer.getPlayerListFooter())) {
			tabList.setHeaderAndFooter(header, footer);
		}

		Set<UUID> sharedIds = new HashSet<>();
		for (Player target : shared) {
			UUID id = target.getUniqueId();
			sharedIds.add(id);
			Component displayName = displayNames.get(id);
			Optional<TabListEntry> entry = tabList.getEntry(id);
			if (entry.isEmpty()) {
				tabList.addEntry(TabListEntry.builder()
					.tabList(tabList)
					.profile(target.getGameProfile())
					.displayName(displayName)
					.latency((int) target.getPing())
					.gameMode(0)
					.listed(true)
					.showHat(true)
					.build());
			} else if (!entry.get().getDisplayNameComponent().equals(Optional.of(displayName))) {
				entry.get().setDisplayName(displayName);
			}
		}

		for (TabListEntry entry : List.copyOf(tabList.getEntries())) {
			if (!sharedIds.contains(entry.getProfile().getId())) {
				tabList.removeEntry(entry.getProfile().getId());
			}
		}
	}

	static Component header(String serverName) {
		return Component.text("\nProject Crystalized " + serverLabel(serverName) + "\n")
			.color(NamedTextColor.LIGHT_PURPLE);
	}

	static Component footer() {
		return Component.text("\ncrystalized.cc\n").color(NamedTextColor.DARK_GRAY);
	}

	static String serverLabel(String serverName) {
		return serverName.equals(ChatRelay.MINESWEEPER) ? "Minesweeper" : "Lobby";
	}
}
