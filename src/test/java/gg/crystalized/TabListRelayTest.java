package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ChatSession;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.GameProfile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class TabListRelayTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		ChatRelay.rankCache.clear();
		ChatRelay.lastChatAt.clear();
		ChatRelay.clock = System::currentTimeMillis;
		ChatRelay.rankLoader = uuid -> 0;
		Velocity_plugin.logger = Mocks.mock(Logger.class);
	}

	@Test
	void usesTheLobbyHeaderAndFooterOnBothServers() {
		assertEquals("\nProject Crystalized Lobby\n", plainText(TabListRelay.header(ChatRelay.LOBBY)));
		assertEquals("\nProject Crystalized Minesweeper\n",
				plainText(TabListRelay.header(ChatRelay.MINESWEEPER)));
		assertEquals("\ncrystalized.cc\n", plainText(TabListRelay.footer()));
		assertEquals(NamedTextColor.LIGHT_PURPLE, colorOf(TabListRelay.header(ChatRelay.LOBBY),
				"\nProject Crystalized Lobby\n"));
		assertEquals(NamedTextColor.DARK_GRAY, colorOf(TabListRelay.footer(), "\ncrystalized.cc\n"));
	}

	@Test
	void formatsTabNamesLikeChatNames() {
		Component rankless = ChatRelay.formatName(0, "Steve");
		assertEquals("Steve", plainText(rankless));
		assertEquals(TextColor.fromHexString("#a1a1a1"), colorOf(rankless, "Steve"));

		Component admin = ChatRelay.formatName(1, "Steve");
		assertEquals("\uE301 Steve", plainText(admin));
		assertEquals(TextColor.fromHexString("#ba1560"), colorOf(admin, "Steve"));
	}

	@Test
	void addsPlayersFromTheOtherSharedServerAndStylesExistingEntries() {
		UUID viewerId = UUID.randomUUID();
		UUID lobbyId = UUID.randomUUID();
		UUID minesweeperId = UUID.randomUUID();
		ChatRelay.rankLoader = uuid -> uuid.equals(minesweeperId) ? 1 : 0;

		FakeTabList tabList = new FakeTabList();
		Player viewer = player(ChatRelay.LOBBY, tabList, viewerId, "Viewer");
		Player lobbyPlayer = player(ChatRelay.LOBBY, new FakeTabList(), lobbyId, "LobbyPal");
		Player minesweeperPlayer = player(ChatRelay.MINESWEEPER, new FakeTabList(), minesweeperId, "MsPal");

		tabList.addEntry(entry(tabList, viewerId, "Viewer", null));
		tabList.addEntry(entry(tabList, lobbyId, "LobbyPal", null));

		TabListRelay.refresh(viewer, List.of(viewer, lobbyPlayer, minesweeperPlayer));

		assertEquals(3, tabList.entries.size());
		assertTrue(tabList.containsEntry(minesweeperId));
		assertEquals("Viewer", plainText(displayName(tabList, viewerId)));
		assertEquals("LobbyPal", plainText(displayName(tabList, lobbyId)));
		assertEquals("\uE301 MsPal", plainText(displayName(tabList, minesweeperId)));
		assertEquals(TabListRelay.header(ChatRelay.LOBBY), tabList.header);
		assertEquals(TabListRelay.footer(), tabList.footer);
		assertEquals(1, tabList.headerSets);
	}

	@Test
	void removesEntriesForPlayersOutsideTheSharedServers() {
		UUID viewerId = UUID.randomUUID();
		UUID smpId = UUID.randomUUID();
		FakeTabList tabList = new FakeTabList();
		Player viewer = player(ChatRelay.LOBBY, tabList, viewerId, "Viewer");
		tabList.addEntry(entry(tabList, viewerId, "Viewer", null));
		tabList.addEntry(entry(tabList, smpId, "SmpPal", null));

		TabListRelay.refresh(viewer, List.of(viewer));

		assertFalse(tabList.containsEntry(smpId));
		assertTrue(tabList.containsEntry(viewerId));
		assertEquals(1, tabList.removes);
	}

	@Test
	void refreshesEveryViewerOnBothServers() {
		FakeTabList lobbyTab = new FakeTabList();
		FakeTabList minesweeperTab = new FakeTabList();
		Player lobbyPlayer = player(ChatRelay.LOBBY, lobbyTab, UUID.randomUUID(), "LobbyPal");
		Player minesweeperPlayer = player(ChatRelay.MINESWEEPER, minesweeperTab, UUID.randomUUID(), "MsPal");
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of(
				"getAllPlayers", List.of(lobbyPlayer, minesweeperPlayer),
				"getScheduler", new FakeScheduler()));

		new TabListRelay(proxy, proxy).refreshAll();

		assertEquals(2, lobbyTab.entries.size());
		assertEquals(2, minesweeperTab.entries.size());
		assertEquals(TabListRelay.header(ChatRelay.LOBBY), lobbyTab.header);
		assertEquals(TabListRelay.header(ChatRelay.MINESWEEPER), minesweeperTab.header);
	}

	@Test
	void ignoresPlayersWithoutAServerAndNonSharedServers() {
		FakeTabList disconnectedTab = new FakeTabList();
		Player disconnected = player(null, disconnectedTab, UUID.randomUUID(), "Offline");
		FakeTabList smpTab = new FakeTabList();
		Player smpPlayer = player("smp", smpTab, UUID.randomUUID(), "SmpPal");

		TabListRelay.refresh(disconnected, List.of(disconnected, smpPlayer));
		TabListRelay.refresh(smpPlayer, List.of(disconnected, smpPlayer));

		assertEquals(0, disconnectedTab.headerSets);
		assertEquals(0, disconnectedTab.adds);
		assertEquals(0, smpTab.headerSets);
		assertEquals(0, smpTab.adds);
	}

	@Test
	void doesNotRepeatPacketsWhenTheTabListIsAlreadyCorrect() {
		UUID id = UUID.randomUUID();
		FakeTabList tabList = new FakeTabList();
		Player viewer = player(ChatRelay.LOBBY, tabList, id, "Viewer");
		tabList.header = TabListRelay.header(ChatRelay.LOBBY);
		tabList.footer = TabListRelay.footer();
		FakeEntry entry = entry(tabList, id, "Viewer", ChatRelay.formatName(0, "Viewer"));
		tabList.addEntry(entry);
		tabList.adds = 0;
		tabList.removes = 0;

		TabListRelay.refresh(viewer, List.of(viewer));

		assertEquals(0, tabList.headerSets);
		assertEquals(0, tabList.adds);
		assertEquals(0, tabList.removes);
		assertEquals(0, entry.displayNameSets);
	}

	@Test
	void includesOnlyLobbyAndMinesweeperInTheSharedList() {
		Player lobby = player(ChatRelay.LOBBY, new FakeTabList(), UUID.randomUUID(), "LobbyPal");
		Player minesweeper = player(ChatRelay.MINESWEEPER, new FakeTabList(), UUID.randomUUID(), "MsPal");
		Player smp = player("smp", new FakeTabList(), UUID.randomUUID(), "SmpPal");

		Set<UUID> ids = TabListRelay.sharedPlayers(List.of(lobby, minesweeper, smp)).stream()
			.map(Player::getUniqueId)
			.collect(Collectors.toSet());

		assertEquals(Set.of(lobby.getUniqueId(), minesweeper.getUniqueId()), ids);
	}

	@Test
	void refreshTaskIsOwnedByThePlugin() {
		FakeScheduler scheduler = new FakeScheduler();
		ProxyServer proxy = Mocks.mock(ProxyServer.class, Map.of("getScheduler", scheduler));
		Object plugin = new Object();

		new TabListRelay(plugin, proxy);

		assertEquals(1, scheduler.tasks.size());
		assertSame(plugin, scheduler.tasks.get(0).plugin());
		assertEquals(TabListRelay.REFRESH_MILLIS, scheduler.tasks.get(0).repeat());
		assertEquals(TimeUnit.MILLISECONDS, scheduler.tasks.get(0).unit());
	}

	static Player player(String server, FakeTabList tabList, UUID id, String name) {
		GameProfile profile = profile(id, name);
		Map<String, Object> connectionStubs = new LinkedHashMap<>();
		connectionStubs.put("getServerInfo",
				server == null ? null : new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
		ServerConnection connection = Mocks.mock(ServerConnection.class, connectionStubs);

		return (Player) Proxy.newProxyInstance(
				TabListRelayTest.class.getClassLoader(),
				new Class<?>[] { Player.class },
				(proxy, method, args) -> {
					if (method.getName().equals("equals")) {
						return proxy == args[0];
					}
					if (method.getName().equals("hashCode")) {
						return System.identityHashCode(proxy);
					}
					if (method.getName().equals("toString")) {
						return name;
					}
					return switch (method.getName()) {
						case "getUniqueId" -> id;
						case "getUsername" -> name;
						case "getCurrentServer" -> server == null ? Optional.empty() : Optional.of(connection);
						case "getTabList" -> tabList;
						case "getGameProfile" -> profile;
						case "getPing" -> 42L;
						case "getPlayerListHeader" -> tabList.header;
						case "getPlayerListFooter" -> tabList.footer;
						default -> null;
					};
				});
	}

	static FakeEntry entry(FakeTabList tabList, UUID id, String name, Component displayName) {
		return new FakeEntry(tabList, profile(id, name), displayName, 42, 0, true, 0, true);
	}

	static GameProfile profile(UUID id, String name) {
		return new GameProfile(id, name, List.of());
	}

	static Component displayName(FakeTabList tabList, UUID id) {
		return tabList.getEntry(id).orElseThrow().getDisplayNameComponent().orElseThrow();
	}

	static String plainText(Component component) {
		StringBuilder out = new StringBuilder();
		appendText(component, out);
		return out.toString();
	}

	static void appendText(Component component, StringBuilder out) {
		if (component instanceof TextComponent text) {
			out.append(text.content());
		}
		for (Component child : component.children()) {
			appendText(child, out);
		}
	}

	static TextColor colorOf(Component component, String content) {
		if (component instanceof TextComponent text && text.content().equals(content)) {
			return text.color();
		}
		for (Component child : component.children()) {
			TextColor found = colorOf(child, content);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	static final class FakeTabList implements TabList {
		final Map<UUID, TabListEntry> entries = new LinkedHashMap<>();
		Component header;
		Component footer;
		int headerSets;
		int adds;
		int removes;

		@Override
		public void setHeaderAndFooter(Component header, Component footer) {
			this.header = header;
			this.footer = footer;
			headerSets++;
		}

		@Override
		public void clearHeaderAndFooter() {
			header = null;
			footer = null;
		}

		@Override
		public void addEntry(TabListEntry entry) {
			entries.put(entry.getProfile().getId(), entry);
			adds++;
		}

		@Override
		public Optional<TabListEntry> removeEntry(UUID id) {
			removes++;
			return Optional.ofNullable(entries.remove(id));
		}

		@Override
		public boolean containsEntry(UUID id) {
			return entries.containsKey(id);
		}

		@Override
		public Optional<TabListEntry> getEntry(UUID id) {
			return Optional.ofNullable(entries.get(id));
		}

		@Override
		public Collection<TabListEntry> getEntries() {
			return List.copyOf(entries.values());
		}

		@Override
		public void clearAll() {
			entries.clear();
		}

		@Override
		public TabListEntry buildEntry(GameProfile profile, Component displayName, int latency, int gameMode,
				ChatSession chatSession, boolean listed, int listOrder, boolean showHat) {
			return new FakeEntry(this, profile, displayName, latency, gameMode, listed, listOrder, showHat);
		}
	}

	static final class FakeEntry implements TabListEntry {
		private final TabList tabList;
		private final GameProfile profile;
		private Component displayName;
		private int latency;
		private int gameMode;
		private boolean listed;
		private int listOrder;
		private boolean showHat;
		int displayNameSets;

		FakeEntry(TabList tabList, GameProfile profile, Component displayName, int latency, int gameMode,
				boolean listed, int listOrder, boolean showHat) {
			this.tabList = tabList;
			this.profile = profile;
			this.displayName = displayName;
			this.latency = latency;
			this.gameMode = gameMode;
			this.listed = listed;
			this.listOrder = listOrder;
			this.showHat = showHat;
		}

		@Override
		public ChatSession getChatSession() {
			return null;
		}

		@Override
		public TabList getTabList() {
			return tabList;
		}

		@Override
		public GameProfile getProfile() {
			return profile;
		}

		@Override
		public Optional<Component> getDisplayNameComponent() {
			return Optional.ofNullable(displayName);
		}

		@Override
		public TabListEntry setDisplayName(Component displayName) {
			this.displayName = displayName;
			displayNameSets++;
			return this;
		}

		@Override
		public int getLatency() {
			return latency;
		}

		@Override
		public TabListEntry setLatency(int latency) {
			this.latency = latency;
			return this;
		}

		@Override
		public int getGameMode() {
			return gameMode;
		}

		@Override
		public TabListEntry setGameMode(int gameMode) {
			this.gameMode = gameMode;
			return this;
		}

		@Override
		public boolean isListed() {
			return listed;
		}

		@Override
		public TabListEntry setListed(boolean listed) {
			this.listed = listed;
			return this;
		}

		@Override
		public int getListOrder() {
			return listOrder;
		}

		@Override
		public TabListEntry setListOrder(int listOrder) {
			this.listOrder = listOrder;
			return this;
		}

		@Override
		public boolean isShowHat() {
			return showHat;
		}

		@Override
		public TabListEntry setShowHat(boolean showHat) {
			this.showHat = showHat;
			return this;
		}
	}
}
