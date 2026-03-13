package net.runelite.client.plugins.gamestate;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class CombatStateManager {

	private final Client client;

	// Real-time cached values (updated via events, not just game tick)
	private volatile int health;
	private volatile int maxHealth;
	private volatile int prayer;
	private volatile int maxPrayer;
	private volatile int specialAttackPercent;
	private volatile boolean specialAttackEnabled;
	private volatile int poisonStatus;
	private volatile int attackStyle;
	private volatile int weaponType;
	private volatile boolean autoRetaliate;
	private volatile boolean quickPrayerActive;
	private volatile boolean isDead;

	// Target tracking
	private volatile String targetName;
	private volatile int targetIndex = -1;
	private volatile int targetHealth;
	private volatile int targetMaxHealth;
	private volatile String targetType;

	// Active prayers
	private volatile List<String> activePrayers = new ArrayList<>();

	// Combat log
	private final List<Map<String, Object>> combatLog = new CopyOnWriteArrayList<>();
	private static final int MAX_COMBAT_LOG = 200;

	// In-combat tracking
	private volatile boolean inCombat;
	private volatile long lastCombatActionTime;
	private static final long COMBAT_TIMEOUT_MS = 6000; // 10 game ticks

	public CombatStateManager(Client client) {
		this.client = client;
	}

	/**
	 * Called on every game tick to sync all state.
	 * This is the fallback that ensures we never have stale data.
	 */
	public void onGameTick() {
		try {
			health = client.getBoostedSkillLevel(Skill.HITPOINTS);
			maxHealth = client.getRealSkillLevel(Skill.HITPOINTS);
			prayer = client.getBoostedSkillLevel(Skill.PRAYER);
			maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
			specialAttackPercent = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
			specialAttackEnabled = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1;
			poisonStatus = client.getVarpValue(VarPlayer.POISON);
			attackStyle = client.getVarpValue(VarPlayer.ATTACK_STYLE);
			weaponType = client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE);
			autoRetaliate = client.getVarpValue(172) == 0; // 0 = on, 1 = off
			quickPrayerActive = client.getVarbitValue(net.runelite.api.gameval.VarbitID.QUICKPRAYER_ACTIVE) == 1;

			// Update active prayers
			List<String> prayers = new ArrayList<>();
			for (Prayer p : Prayer.values()) {
				try {
					if (client.getVarbitValue(p.getVarbit()) == 1) {
						prayers.add(p.name());
					}
				} catch (Exception e) {
					// Some prayer enum values may not have varbits
				}
			}
			activePrayers = prayers;

			// Update target info
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null) {
				isDead = localPlayer.isDead();
				Actor target = localPlayer.getInteracting();
				if (target != null) {
					targetName = target.getName();
					targetHealth = target.getHealthRatio();
					targetMaxHealth = target.getHealthScale();
					if (target instanceof NPC) {
						targetType = "NPC";
						targetIndex = ((NPC) target).getIndex();
					} else if (target instanceof Player) {
						targetType = "PLAYER";
						targetIndex = -1;
					} else {
						targetType = "UNKNOWN";
						targetIndex = -1;
					}
				} else {
					targetName = null;
					targetIndex = -1;
					targetHealth = 0;
					targetMaxHealth = 0;
					targetType = null;
				}
			}

			// Check combat timeout
			if (inCombat && System.currentTimeMillis() - lastCombatActionTime > COMBAT_TIMEOUT_MS) {
				inCombat = false;
				addCombatLogEntry("combat_end", Map.of("reason", "timeout"));
			}
		} catch (Exception e) {
			log.warn("Error updating combat state", e);
		}
	}

	/**
	 * Called when a stat changes (HP, prayer, etc.) — more immediate than game tick.
	 */
	public void onStatChanged(StatChanged event) {
		String skill = event.getSkill().getName();
		if ("Hitpoints".equals(skill)) {
			int oldHealth = health;
			health = event.getBoostedLevel();
			maxHealth = event.getLevel();
			if (health < oldHealth) {
				// Took damage or drained
				addCombatLogEntry("health_changed", Map.of(
					"oldHealth", oldHealth,
					"newHealth", health,
					"maxHealth", maxHealth,
					"delta", health - oldHealth
				));
			}
		} else if ("Prayer".equals(skill)) {
			int oldPrayer = prayer;
			prayer = event.getBoostedLevel();
			maxPrayer = event.getLevel();
			if (prayer != oldPrayer) {
				addCombatLogEntry("prayer_changed", Map.of(
					"oldPrayer", oldPrayer,
					"newPrayer", prayer,
					"maxPrayer", maxPrayer,
					"delta", prayer - oldPrayer
				));
			}
		}
	}

	/**
	 * Called when a hitsplat is applied to any actor.
	 */
	public void onHitsplatApplied(HitsplatApplied event) {
		Actor actor = event.getActor();
		String actorName = actor.getName();
		if (actorName == null) actorName = "Unknown";

		Hitsplat hitsplat = event.getHitsplat();
		int amount = hitsplat.getAmount();
		int type = hitsplat.getHitsplatType();

		Player localPlayer = client.getLocalPlayer();
		boolean isLocalPlayer = (localPlayer != null && actor == localPlayer);
		boolean isTarget = (localPlayer != null && localPlayer.getInteracting() == actor);

		if (isLocalPlayer || isTarget) {
			lastCombatActionTime = System.currentTimeMillis();
			if (!inCombat) {
				inCombat = true;
				addCombatLogEntry("combat_start", Map.of());
			}
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("actor", actorName);
		data.put("isLocalPlayer", isLocalPlayer);
		data.put("isTarget", isTarget);
		data.put("amount", amount);
		data.put("hitsplatType", type);
		data.put("hitsplatName", getHitsplatName(type));
		addCombatLogEntry("hitsplat", data);
	}

	/**
	 * Called when the player's interacting target changes.
	 */
	public void onInteractingChanged(InteractingChanged event) {
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || event.getSource() != localPlayer) return;

		Actor target = event.getTarget();
		if (target != null) {
			String oldTarget = targetName;
			targetName = target.getName();
			targetHealth = target.getHealthRatio();
			targetMaxHealth = target.getHealthScale();

			if (target instanceof NPC) {
				targetType = "NPC";
				targetIndex = ((NPC) target).getIndex();
			} else if (target instanceof Player) {
				targetType = "PLAYER";
				targetIndex = -1;
			}

			addCombatLogEntry("target_changed", Map.of(
				"oldTarget", oldTarget != null ? oldTarget : "none",
				"newTarget", targetName != null ? targetName : "none",
				"targetType", targetType != null ? targetType : "none"
			));
		} else {
			if (targetName != null) {
				addCombatLogEntry("target_lost", Map.of(
					"oldTarget", targetName != null ? targetName : "none"
				));
			}
			targetName = null;
			targetIndex = -1;
			targetHealth = 0;
			targetMaxHealth = 0;
			targetType = null;
		}
	}

	/**
	 * Called when any actor dies.
	 */
	public void onActorDeath(ActorDeath event) {
		Actor actor = event.getActor();
		String actorName = actor.getName();
		if (actorName == null) actorName = "Unknown";

		Player localPlayer = client.getLocalPlayer();
		boolean isLocalPlayer = (localPlayer != null && actor == localPlayer);
		boolean isTarget = (targetName != null && targetName.equals(actorName));

		if (isLocalPlayer) {
			isDead = true;
			inCombat = false;
			addCombatLogEntry("player_death", Map.of());
		}

		if (isTarget) {
			addCombatLogEntry("target_death", Map.of("target", actorName));
			// Don't clear target immediately — let InteractingChanged handle it
		}

		addCombatLogEntry("actor_death", Map.of(
			"actor", actorName,
			"isLocalPlayer", isLocalPlayer,
			"isTarget", isTarget
		));
	}

	/**
	 * Get a full snapshot of the current combat state.
	 */
	public CombatSnapshot getSnapshot() {
		return CombatSnapshot.builder()
			.health(health)
			.maxHealth(maxHealth)
			.prayer(prayer)
			.maxPrayer(maxPrayer)
			.specialAttackPercent(specialAttackPercent)
			.specialAttackEnabled(specialAttackEnabled)
			.poisonStatus(poisonStatus)
			.poisonType(getPoisonType(poisonStatus))
			.activePrayers(new ArrayList<>(activePrayers))
			.quickPrayerActive(quickPrayerActive)
			.targetName(targetName)
			.targetIndex(targetIndex)
			.targetHealth(targetHealth)
			.targetMaxHealth(targetMaxHealth)
			.targetType(targetType)
			.inCombat(inCombat)
			.isDead(isDead)
			.attackStyle(attackStyle)
			.weaponType(weaponType)
			.autoRetaliate(autoRetaliate)
			.timestamp(System.currentTimeMillis())
			.build();
	}

	/**
	 * Get recent combat log entries.
	 */
	public List<Map<String, Object>> getCombatLog(int limit) {
		List<Map<String, Object>> log = new ArrayList<>(combatLog);
		if (log.size() > limit) {
			return log.subList(log.size() - limit, log.size());
		}
		return log;
	}

	public void clearCombatLog() {
		combatLog.clear();
	}

	// === Internal helpers ===

	private void addCombatLogEntry(String type, Map<String, Object> data) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("type", type);
		entry.put("timestamp", System.currentTimeMillis());
		entry.putAll(data);
		combatLog.add(entry);
		if (combatLog.size() > MAX_COMBAT_LOG) {
			combatLog.remove(0);
		}
	}

	private String getPoisonType(int poisonValue) {
		if (poisonValue >= 1000000) return "venom";
		if (poisonValue > 0) return "poison";
		if (poisonValue < -38) return "venom_immune";
		if (poisonValue < 0) return "poison_immune";
		return "none";
	}

	private String getHitsplatName(int type) {
		switch (type) {
			case 0: return "BLOCK";
			case 1: return "DAMAGE";
			case 2: return "DAMAGE_ME";
			case 3: return "DAMAGE_OTHER";
			case 5: return "VENOM";
			case 65: return "POISON";
			case 66: return "DISEASE";
			case 67: return "HEAL";
			default: return "TYPE_" + type;
		}
	}

	// === Getters for conditional waits ===

	public int getHealth() { return health; }
	public int getMaxHealth() { return maxHealth; }
	public int getPrayer() { return prayer; }
	public int getMaxPrayer() { return maxPrayer; }
	public int getSpecialAttackPercent() { return specialAttackPercent; }
	public boolean isInCombat() { return inCombat; }
	public boolean isDead() { return isDead; }
	public String getTargetName() { return targetName; }
}
