package com.custom.gamestate;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.List;

/**
 * Plugin that exposes game state for external API access
 */
@Slf4j
@PluginDescriptor(
    name = "Game State API",
    description = "Exposes game state for external access",
    tags = {"api", "external", "gamestate"}
)
public class GameStatePlugin extends Plugin {

    @Inject
    private Client client;

    @Getter
    private GameStateManager stateManager;

    @Override
    protected void startUp() throws Exception {
        log.info("Game State Plugin started");
        stateManager = new GameStateManager(client);
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Game State Plugin stopped");
        stateManager = null;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (stateManager != null) {
            stateManager.update();
        }
    }

    // Public API methods for other plugins to access
    public PlayerState getPlayerState() {
        return stateManager != null ? stateManager.getPlayerState() : null;
    }

    public InventoryState getInventoryState() {
        return stateManager != null ? stateManager.getInventoryState() : null;
    }

    public WorldState getWorldState() {
        return stateManager != null ? stateManager.getWorldState() : null;
    }

    public List<NPCInfo> getNearbyNPCs() {
        return stateManager != null ? stateManager.getNearbyNPCs() : null;
    }
}
