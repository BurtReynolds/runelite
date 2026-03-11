package com.custom.gamestate;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Represents the current state of the local player
 */
@Value
public class PlayerState {
    String name;
    WorldPoint position;
    int plane;
    int health;
    int maxHealth;
    int prayer;
    int maxPrayer;
    int energy;
    int weight;
    int combatLevel;
    int animation;
    boolean isMoving;
    boolean isInteracting;
    long timestamp;

    public int getX() {
        return position != null ? position.getX() : 0;
    }

    public int getY() {
        return position != null ? position.getY() : 0;
    }

    public int getPlane() {
        return position != null ? position.getPlane() : plane;
    }
}
