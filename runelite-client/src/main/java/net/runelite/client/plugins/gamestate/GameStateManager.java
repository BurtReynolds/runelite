package net.runelite.client.plugins.gamestate;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages and caches the current game state
 */
@Slf4j
public class GameStateManager {
    private final Client client;

    private PlayerState cachedPlayerState;
    private InventoryState cachedInventoryState;
    private WorldState cachedWorldState;
    private List<NPCInfo> cachedNearbyNPCs = new ArrayList<>();

    private long lastUpdate = 0;

    public GameStateManager(Client client) {
        this.client = client;
    }

    /**
     * Update all cached state. Called on game tick.
     */
    public void update() {
        long now = System.currentTimeMillis();

        try {
            updatePlayerState();
            updateInventoryState();
            updateWorldState();
            updateNearbyNPCs();

            lastUpdate = now;
        } catch (Exception e) {
            log.error("Error updating game state", e);
        }
    }

    private void updatePlayerState() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            cachedPlayerState = null;
            return;
        }

        WorldPoint position = localPlayer.getWorldLocation();
        int health = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHealth = client.getRealSkillLevel(Skill.HITPOINTS);
        int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        int energy = client.getEnergy();
        int weight = client.getWeight();

        // Check if player has an interacting target (NPC, player, etc.)
        boolean hasInteractingTarget = localPlayer.getInteracting() != null;

        cachedPlayerState = new PlayerState(
            localPlayer.getName(),
            position,
            position != null ? position.getPlane() : 0,
            health,
            maxHealth,
            prayer,
            maxPrayer,
            energy,
            weight,
            localPlayer.getCombatLevel(),
            localPlayer.getAnimation(),
            localPlayer.getPoseAnimation() != localPlayer.getIdlePoseAnimation(),  // isMoving
            hasInteractingTarget,
            System.currentTimeMillis()
        );
    }

    private void updateInventoryState() {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) {
            cachedInventoryState = new InventoryState(new ArrayList<>());
            return;
        }

        Item[] items = inventory.getItems();
        List<InventoryItem> inventoryItems = new ArrayList<>();

        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            if (item.getId() != -1) {
                ItemComposition itemComp = client.getItemDefinition(item.getId());
                inventoryItems.add(new InventoryItem(
                    i,
                    item.getId(),
                    itemComp.getName(),
                    item.getQuantity(),
                    itemComp.getNote() != -1,
                    itemComp.isStackable()
                ));
            }
        }

        cachedInventoryState = new InventoryState(inventoryItems);
    }

    private void updateWorldState() {
        int world = client.getWorld();
        boolean isInInstance = client.isInInstancedRegion();

        cachedWorldState = new WorldState(
            client.getGameState(),
            client.getTickCount(),
            client.getPlane(),
            client.getBaseX(),
            client.getBaseY(),
            world,
            isInInstance
        );
    }

    private void updateNearbyNPCs() {
        List<NPC> npcs = client.getNpcs();
        if (npcs == null) {
            cachedNearbyNPCs = new ArrayList<>();
            return;
        }

        // Include ALL NPCs, even those without names (use ID as fallback)
        cachedNearbyNPCs = npcs.stream()
            .filter(npc -> npc != null)
            .map(this::createNPCInfo)
            .collect(Collectors.toList());
    }

    private NPCInfo createNPCInfo(NPC npc) {
        int healthRatio = npc.getHealthRatio();
        int healthScale = npc.getHealthScale();

        // Estimate health (not exact unless we know max HP)
        int estimatedHealth = healthScale > 0 ? healthRatio : 0;
        int estimatedMaxHealth = healthScale > 0 ? healthScale : 0;

        // Use name if available, otherwise use "NPC-{id}"
        String npcName = npc.getName();
        if (npcName == null || npcName.isEmpty()) {
            npcName = "NPC-" + npc.getId();
        }

        // Check if NPC has an interacting target
        boolean hasInteractingTarget = npc.getInteracting() != null;

        return new NPCInfo(
            npc.getIndex(),
            npc.getId(),
            npcName,
            npc.getCombatLevel(),
            npc.getWorldLocation(),
            estimatedHealth,
            estimatedMaxHealth,
            npc.getAnimation(),
            hasInteractingTarget,
            System.currentTimeMillis()
        );
    }

    // Public getters
    public PlayerState getPlayerState() {
        return cachedPlayerState;
    }

    public InventoryState getInventoryState() {
        return cachedInventoryState;
    }

    public WorldState getWorldState() {
        return cachedWorldState;
    }

    public List<NPCInfo> getNearbyNPCs() {
        return new ArrayList<>(cachedNearbyNPCs);
    }

    public long getLastUpdateTime() {
        return lastUpdate;
    }
}
