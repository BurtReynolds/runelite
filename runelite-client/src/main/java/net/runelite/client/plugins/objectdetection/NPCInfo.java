package net.runelite.client.plugins.objectdetection;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Enhanced NPC information
 */
@Value
public class NPCInfo {
    int id;
    int index;
    String name;
    WorldPoint location;
    List<String> actions;
    int combatLevel;
    int healthRatio;
    int healthScale;
    boolean isInteracting;
    String overheadText;
    int animation;
    long firstSeen;
    long lastSeen;

    /**
     * Distance from local player
     */
    public double distanceFrom(WorldPoint playerLocation) {
        return location.distanceTo(playerLocation);
    }

    /**
     * Check if NPC has specific action
     */
    public boolean hasAction(String action) {
        return actions != null && actions.stream()
                .anyMatch(a -> a.equalsIgnoreCase(action));
    }

    /**
     * Get estimated health percentage
     */
    public int getHealthPercentage() {
        if (healthScale == 0) {
            return 100;
        }
        return (int) ((double) healthRatio / healthScale * 100);
    }

    /**
     * Check if NPC is alive (has health)
     */
    public boolean isAlive() {
        return healthRatio > 0 || healthScale == 0;
    }

    /**
     * Check if object is stale (not seen recently)
     */
    public boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - lastSeen > maxAgeMs;
    }
}
