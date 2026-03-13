package net.runelite.client.plugins.gamestate;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class CombatSnapshot {
	int health;
	int maxHealth;
	int prayer;
	int maxPrayer;
	int specialAttackPercent;
	boolean specialAttackEnabled;
	int poisonStatus;
	String poisonType;
	List<String> activePrayers;
	boolean quickPrayerActive;

	// Combat target info
	String targetName;
	int targetIndex;
	int targetHealth;
	int targetMaxHealth;
	String targetType;

	// Combat state
	boolean inCombat;
	boolean isDead;
	int attackStyle;
	int weaponType;
	boolean autoRetaliate;

	long timestamp;

	public Map<String, Object> toMap() {
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("health", health);
		map.put("maxHealth", maxHealth);
		map.put("prayer", prayer);
		map.put("maxPrayer", maxPrayer);
		map.put("specialAttackPercent", specialAttackPercent);
		map.put("specialAttackEnabled", specialAttackEnabled);
		map.put("poisonStatus", poisonStatus);
		map.put("poisonType", poisonType);
		map.put("activePrayers", activePrayers);
		map.put("quickPrayerActive", quickPrayerActive);
		map.put("targetName", targetName);
		map.put("targetIndex", targetIndex);
		map.put("targetHealth", targetHealth);
		map.put("targetMaxHealth", targetMaxHealth);
		map.put("targetType", targetType);
		map.put("inCombat", inCombat);
		map.put("isDead", isDead);
		map.put("attackStyle", attackStyle);
		map.put("weaponType", weaponType);
		map.put("autoRetaliate", autoRetaliate);
		map.put("timestamp", timestamp);
		return map;
	}
}
