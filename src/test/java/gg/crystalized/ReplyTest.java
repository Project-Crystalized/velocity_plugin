package gg.crystalized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplyTest {
	Player alice;
	Player bob;
	UUID aliceId;
	UUID bobId;

	static byte[] uuidToBytes(UUID uuid) {
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	static void allowDms(UUID uuid) throws Exception {
		try (Connection conn = DriverManager.getConnection(Databases.LOBBY)) {
			PreparedStatement del = conn.prepareStatement("DELETE FROM Settings WHERE player_uuid = ?;");
			del.setBytes(1, uuidToBytes(uuid));
			del.executeUpdate();
			PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO Settings(player_uuid, dms, textures, show_players, height, friends_requests, party_requests) VALUES (?, 1, 1, 1, 0.5, 1, 1);");
			ins.setBytes(1, uuidToBytes(uuid));
			ins.executeUpdate();
		}
	}

	@BeforeEach
	void setup() throws Exception {
		AdminCommands.lastDmPartner.clear();
		aliceId = UUID.randomUUID();
		bobId = UUID.randomUUID();
		allowDms(aliceId);
		allowDms(bobId);
		alice = Mocks.mock(Player.class, Map.of("getUsername", "alice", "getUniqueId", aliceId));
		bob = Mocks.mock(Player.class, Map.of("getUsername", "bob", "getUniqueId", bobId));
	}

	@Test
	void dmRecordsBothDirections() {
		List<Object[]> calls = new java.util.ArrayList<>();
		Player spyBob = Mocks.recording(Player.class, calls, Map.of("getUsername", "bob", "getUniqueId", bobId));
		AdminCommands.sendDirectMessage(alice, spyBob, "hi");
		assertEquals(bobId, AdminCommands.lastDmPartner.get(aliceId));
		assertEquals(aliceId, AdminCommands.lastDmPartner.get(bobId));
		assertTrue(calls.stream().anyMatch(c -> c[0].equals("sendMessage")));
	}

	@Test
	void forgetRemovesKeyAndValues() {
		AdminCommands.lastDmPartner.put(aliceId, bobId);
		AdminCommands.lastDmPartner.put(bobId, aliceId);
		AdminCommands.forgetDmPartner(aliceId);
		assertNull(AdminCommands.lastDmPartner.get(aliceId));
		assertNull(AdminCommands.lastDmPartner.get(bobId));
		assertTrue(AdminCommands.lastDmPartner.isEmpty());
	}

	@Test
	void consoleSendRecordsNothing() {
		ConsoleCommandSource console = Mocks.mock(ConsoleCommandSource.class);
		AdminCommands.sendDirectMessage(console, bob, "hi");
		assertTrue(AdminCommands.lastDmPartner.isEmpty());
	}
}
