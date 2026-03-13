package net.runelite.client.plugins.gamestate;

import net.runelite.api.*;
import net.runelite.api.events.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class CombatStateManagerTest {

	@Mock
	private Client client;

	@Mock
	private Player localPlayer;

	private CombatStateManager manager;

	@Before
	public void setUp() {
		manager = new CombatStateManager(client);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.isDead()).thenReturn(false);
		when(localPlayer.getInteracting()).thenReturn(null);
	}

	@Test
	public void testInitialState() {
		CombatSnapshot snapshot = manager.getSnapshot();
		assertEquals(0, snapshot.getHealth());
		assertEquals(0, snapshot.getMaxHealth());
		assertEquals(0, snapshot.getPrayer());
		assertEquals(0, snapshot.getMaxPrayer());
		assertEquals(0, snapshot.getSpecialAttackPercent());
		assertFalse(snapshot.isInCombat());
		assertFalse(snapshot.isDead());
		assertNull(snapshot.getTargetName());
		assertEquals("none", snapshot.getPoisonType());
	}

	@Test
	public void testGameTickUpdatesHealth() {
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(75);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(80);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(50);
		when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(60);
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT)).thenReturn(500); // 50%
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.ATTACK_STYLE)).thenReturn(1);
		when(client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE)).thenReturn(4);
		when(client.getVarpValue(172)).thenReturn(0); // auto-retaliate on

		manager.onGameTick();

		CombatSnapshot snapshot = manager.getSnapshot();
		assertEquals(75, snapshot.getHealth());
		assertEquals(80, snapshot.getMaxHealth());
		assertEquals(50, snapshot.getPrayer());
		assertEquals(60, snapshot.getMaxPrayer());
		assertEquals(50, snapshot.getSpecialAttackPercent());
		assertTrue(snapshot.isAutoRetaliate());
		assertEquals("none", snapshot.getPoisonType());
	}

	@Test
	public void testPoisonTypeCalculation() {
		when(client.getBoostedSkillLevel(any())).thenReturn(50);
		when(client.getRealSkillLevel(any())).thenReturn(50);
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.ATTACK_STYLE)).thenReturn(0);
		when(client.getVarbitValue(anyInt())).thenReturn(0);
		when(client.getVarpValue(172)).thenReturn(0);

		// Test poison
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(5);
		manager.onGameTick();
		assertEquals("poison", manager.getSnapshot().getPoisonType());

		// Test venom
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(1000006);
		manager.onGameTick();
		assertEquals("venom", manager.getSnapshot().getPoisonType());

		// Test poison immune
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(-10);
		manager.onGameTick();
		assertEquals("poison_immune", manager.getSnapshot().getPoisonType());

		// Test venom immune
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(-50);
		manager.onGameTick();
		assertEquals("venom_immune", manager.getSnapshot().getPoisonType());

		// Test none
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(0);
		manager.onGameTick();
		assertEquals("none", manager.getSnapshot().getPoisonType());
	}

	@Test
	public void testStatChangedUpdatesHealthImmediately() {
		// StatChanged(skill, xp, level, boostedLevel)
		// level = max level, boostedLevel = current level
		StatChanged event = new StatChanged(Skill.HITPOINTS, 0, 80, 70);
		manager.onStatChanged(event);

		assertEquals(70, manager.getHealth());
		assertEquals(80, manager.getMaxHealth());
	}

	@Test
	public void testStatChangedUpdatesPrayer() {
		// StatChanged(skill, xp, level, boostedLevel)
		StatChanged event = new StatChanged(Skill.PRAYER, 0, 60, 40);
		manager.onStatChanged(event);

		assertEquals(40, manager.getPrayer());
	}

	@Test
	public void testHealthChangeLoggedOnDamage() {
		// Set initial health: level=80(max), boostedLevel=80(current)
		StatChanged initial = new StatChanged(Skill.HITPOINTS, 0, 80, 80);
		manager.onStatChanged(initial);

		// Take damage: level=80(max), boostedLevel=65(current)
		StatChanged damage = new StatChanged(Skill.HITPOINTS, 0, 80, 65);
		manager.onStatChanged(damage);

		List<Map<String, Object>> log = manager.getCombatLog(10);
		assertTrue(log.size() >= 1);
		Map<String, Object> lastEntry = log.get(log.size() - 1);
		assertEquals("health_changed", lastEntry.get("type"));
		assertEquals(-15, lastEntry.get("delta"));
	}

	@Test
	public void testHitsplatStartsCombat() {
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("Goblin");
		when(localPlayer.getInteracting()).thenReturn(npc);

		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getAmount()).thenReturn(10);
		when(hitsplat.getHitsplatType()).thenReturn(1);

		HitsplatApplied event = new HitsplatApplied();
		event.setActor(npc);
		event.setHitsplat(hitsplat);

		manager.onHitsplatApplied(event);

		List<Map<String, Object>> log = manager.getCombatLog(10);
		boolean hasCombatStart = log.stream().anyMatch(e -> "combat_start".equals(e.get("type")));
		boolean hasHitsplat = log.stream().anyMatch(e -> "hitsplat".equals(e.get("type")));
		assertTrue("Should log combat_start", hasCombatStart);
		assertTrue("Should log hitsplat", hasHitsplat);
	}

	@Test
	public void testInteractingChangedUpdatesTarget() {
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("Lesser demon");
		when(npc.getIndex()).thenReturn(42);
		when(npc.getHealthRatio()).thenReturn(100);
		when(npc.getHealthScale()).thenReturn(128);

		// InteractingChanged is @Value (final) — use constructor
		InteractingChanged event = new InteractingChanged(localPlayer, npc);

		manager.onInteractingChanged(event);

		assertEquals("Lesser demon", manager.getTargetName());

		List<Map<String, Object>> log = manager.getCombatLog(10);
		boolean hasTargetChanged = log.stream().anyMatch(e -> "target_changed".equals(e.get("type")));
		assertTrue(hasTargetChanged);
	}

	@Test
	public void testTargetLostWhenInteractingCleared() {
		// Set a target first
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("Goblin");
		when(npc.getIndex()).thenReturn(1);
		when(npc.getHealthRatio()).thenReturn(100);
		when(npc.getHealthScale()).thenReturn(128);

		InteractingChanged setTarget = new InteractingChanged(localPlayer, npc);
		manager.onInteractingChanged(setTarget);

		assertEquals("Goblin", manager.getTargetName());

		// Clear target
		InteractingChanged clearTarget = new InteractingChanged(localPlayer, null);
		manager.onInteractingChanged(clearTarget);

		assertNull(manager.getTargetName());
	}

	@Test
	public void testActorDeathLogsEvent() {
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("Goblin");

		// ActorDeath is @Value (final) — use constructor
		ActorDeath event = new ActorDeath(npc);

		manager.onActorDeath(event);

		List<Map<String, Object>> log = manager.getCombatLog(10);
		boolean hasActorDeath = log.stream().anyMatch(e -> "actor_death".equals(e.get("type")));
		assertTrue(hasActorDeath);
	}

	@Test
	public void testPlayerDeathSetsFlag() {
		when(localPlayer.getName()).thenReturn("TestPlayer");

		ActorDeath event = new ActorDeath(localPlayer);

		manager.onActorDeath(event);

		assertTrue(manager.isDead());
		assertFalse(manager.isInCombat());
	}

	@Test
	public void testCombatLogTrimming() {
		for (int i = 0; i < 250; i++) {
			// Set health to 80: level=80(max), boostedLevel=80(current)
			StatChanged event = new StatChanged(Skill.HITPOINTS, 0, 80, 80);
			manager.onStatChanged(event);
			// Take damage: level=80(max), boostedLevel=70(current)
			StatChanged damage = new StatChanged(Skill.HITPOINTS, 0, 80, 70);
			manager.onStatChanged(damage);
			// Reset: level=80(max), boostedLevel=80(current)
			StatChanged heal = new StatChanged(Skill.HITPOINTS, 0, 80, 80);
			manager.onStatChanged(heal);
		}

		List<Map<String, Object>> log = manager.getCombatLog(300);
		assertTrue("Combat log should be trimmed to max size", log.size() <= 200);
	}

	@Test
	public void testCombatLogClear() {
		// Set health to 80 first, then take damage to create a log entry
		StatChanged initial = new StatChanged(Skill.HITPOINTS, 0, 80, 80);
		manager.onStatChanged(initial);

		StatChanged damage = new StatChanged(Skill.HITPOINTS, 0, 80, 65);
		manager.onStatChanged(damage);

		assertFalse(manager.getCombatLog(10).isEmpty());

		manager.clearCombatLog();

		assertTrue(manager.getCombatLog(10).isEmpty());
	}

	@Test
	public void testSnapshotToMap() {
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(75);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(80);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(50);
		when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(60);
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT)).thenReturn(750);
		when(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.POISON)).thenReturn(0);
		when(client.getVarpValue(VarPlayer.ATTACK_STYLE)).thenReturn(0);
		when(client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE)).thenReturn(0);
		when(client.getVarpValue(172)).thenReturn(0);

		manager.onGameTick();

		CombatSnapshot snapshot = manager.getSnapshot();
		Map<String, Object> map = snapshot.toMap();

		assertEquals(75, map.get("health"));
		assertEquals(80, map.get("maxHealth"));
		assertEquals(50, map.get("prayer"));
		assertEquals(60, map.get("maxPrayer"));
		assertEquals(75, map.get("specialAttackPercent"));
		assertEquals("none", map.get("poisonType"));
		assertNotNull(map.get("timestamp"));
		assertNotNull(map.get("activePrayers"));
	}
}
