package com.custom.gamestate;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Represents information about an NPC
 */
@Value
public class NPCInfo {
    int index;
    int id;
    String name;
    int combatLevel;
    WorldPoint position;
    int health;
    int maxHealth;
    int animation;
    boolean isInteracting;
    long timestamp;

    public int getX() {
        return position != null ? position.getX() : 0;
    }

    public int getY() {
        return position != null ? position.getY() : 0;
    }

    public int getPlane() {
        return position != null ? position.getPlane() : 0;
    }

    public int getHealthPercentage() {
        if (maxHealth <= 0) return 100;
        return (health * 100) / maxHealth;
    }
}
