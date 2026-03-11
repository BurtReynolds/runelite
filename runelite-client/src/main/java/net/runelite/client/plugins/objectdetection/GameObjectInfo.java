package net.runelite.client.plugins.objectdetection;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Generic information about any game object
 * Just the raw data - no classification needed
 */
@Value
public class GameObjectInfo {
    int id;
    String name;
    WorldPoint location;
    List<String> actions;
    int plane;
    long firstSeen;
    long lastSeen;

    /**
     * Distance from a location
     */
    public double distanceFrom(WorldPoint otherLocation) {
        return location.distanceTo(otherLocation);
    }

    /**
     * Check if object has specific action
     */
    public boolean hasAction(String action) {
        return actions != null && actions.stream()
                .anyMatch(a -> a != null && a.equalsIgnoreCase(action));
    }

    /**
     * Check if object is stale (not seen recently)
     */
    public boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - lastSeen > maxAgeMs;
    }
}
