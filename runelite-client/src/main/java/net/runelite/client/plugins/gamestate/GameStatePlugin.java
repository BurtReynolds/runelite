package net.runelite.client.plugins.gamestate;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.*;
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

    @Getter
    private CombatStateManager combatStateManager;

    @Override
    protected void startUp() throws Exception {
        log.info("Game State Plugin started");
        stateManager = new GameStateManager(client);
        combatStateManager = new CombatStateManager(client);
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Game State Plugin stopped");
        stateManager = null;
        combatStateManager = null;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (stateManager != null) {
            stateManager.update();
        }
        if (combatStateManager != null) {
            combatStateManager.onGameTick();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (combatStateManager != null) {
            combatStateManager.onStatChanged(event);
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (combatStateManager != null) {
            combatStateManager.onHitsplatApplied(event);
        }
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        if (combatStateManager != null) {
            combatStateManager.onInteractingChanged(event);
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        if (combatStateManager != null) {
            combatStateManager.onActorDeath(event);
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

    public List<SkillState> getSkills() {
        return stateManager != null ? stateManager.getSkills() : null;
    }

    public SkillState getSkill(String name) {
        return stateManager != null ? stateManager.getSkill(name) : null;
    }

    public List<EquipmentItem> getEquipment() {
        return stateManager != null ? stateManager.getEquipment() : null;
    }
}
