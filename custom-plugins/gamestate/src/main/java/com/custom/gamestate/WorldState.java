package com.custom.gamestate;

import lombok.Value;
import net.runelite.api.GameState;

/**
 * Represents the current world/game state
 */
@Value
public class WorldState {
    GameState gameState;
    int gameTick;
    int plane;
    int baseX;
    int baseY;
    int world;
    boolean isInInstance;
    long timestamp;

    public WorldState(GameState gameState, int gameTick, int plane, int baseX, int baseY, int world, boolean isInInstance) {
        this.gameState = gameState;
        this.gameTick = gameTick;
        this.plane = plane;
        this.baseX = baseX;
        this.baseY = baseY;
        this.world = world;
        this.isInInstance = isInInstance;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isLoggedIn() {
        return gameState == GameState.LOGGED_IN;
    }
}
