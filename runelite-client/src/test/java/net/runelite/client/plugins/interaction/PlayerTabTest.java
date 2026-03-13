package net.runelite.client.plugins.interaction;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlayerTabTest {

	@Test
	public void testFromStringDirect() {
		assertEquals(PlayerTab.COMBAT, PlayerTab.fromString("COMBAT"));
		assertEquals(PlayerTab.STATS, PlayerTab.fromString("STATS"));
		assertEquals(PlayerTab.QUESTS, PlayerTab.fromString("QUESTS"));
		assertEquals(PlayerTab.INVENTORY, PlayerTab.fromString("INVENTORY"));
		assertEquals(PlayerTab.EQUIPMENT, PlayerTab.fromString("EQUIPMENT"));
		assertEquals(PlayerTab.PRAYER, PlayerTab.fromString("PRAYER"));
		assertEquals(PlayerTab.MAGIC, PlayerTab.fromString("MAGIC"));
		assertEquals(PlayerTab.LOGOUT, PlayerTab.fromString("LOGOUT"));
		assertEquals(PlayerTab.OPTIONS, PlayerTab.fromString("OPTIONS"));
	}

	@Test
	public void testFromStringCaseInsensitive() {
		assertEquals(PlayerTab.COMBAT, PlayerTab.fromString("combat"));
		assertEquals(PlayerTab.INVENTORY, PlayerTab.fromString("Inventory"));
		assertEquals(PlayerTab.PRAYER, PlayerTab.fromString("PRAYER"));
	}

	@Test
	public void testFromStringAliases() {
		assertEquals(PlayerTab.EQUIPMENT, PlayerTab.fromString("WORN_EQUIPMENT"));
		assertEquals(PlayerTab.STATS, PlayerTab.fromString("SKILLS"));
		assertEquals(PlayerTab.MAGIC, PlayerTab.fromString("SPELLBOOK"));
	}

	@Test
	public void testFromStringInvalidReturnsNull() {
		assertNull(PlayerTab.fromString("NONEXISTENT"));
	}

	@Test
	public void testFromStringNullReturnsNull() {
		assertNull(PlayerTab.fromString(null));
	}

	@Test
	public void testAllEnumValuesExist() {
		// Verify all expected tabs exist
		PlayerTab[] values = PlayerTab.values();
		assertTrue(values.length >= 14); // At least 14 tabs
	}
}
