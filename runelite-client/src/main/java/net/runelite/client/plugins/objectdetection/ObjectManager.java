package net.runelite.client.plugins.objectdetection;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages game object and NPC detection and caching
 */
@Slf4j
public class ObjectManager {
    private final Client client;

    // Object caches
    private final Map<String, GameObjectInfo> gameObjects = new ConcurrentHashMap<>();
    private final Map<Integer, NPCInfo> npcs = new ConcurrentHashMap<>();

    // Track previous state for change detection
    private Set<String> previousObjectKeys = new HashSet<>();

    // Event listener
    private ObjectEventListener eventListener;

    // Configuration
    private static final long STALE_OBJECT_AGE_MS = 60000; // 1 minute
    private static final long STALE_NPC_AGE_MS = 30000;    // 30 seconds

    public ObjectManager(Client client) {
        this.client = client;
    }

    public void setEventListener(ObjectEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Update object cache (call on game tick)
     */
    public void update() {
        // Capture current state before update
        Set<String> currentObjectKeys = new HashSet<>(gameObjects.keySet());

        // Update caches
        updateGameObjects();
        updateNPCs();
        cleanupStaleObjects();

        // Detect changes and fire events
        if (eventListener != null) {
            detectObjectChanges(currentObjectKeys);
        }

        // Save current state for next tick
        previousObjectKeys = new HashSet<>(gameObjects.keySet());
    }

    private void detectObjectChanges(Set<String> previousKeys) {
        Set<String> currentKeys = gameObjects.keySet();

        // Detect spawned objects (in current but not in previous)
        for (String key : currentKeys) {
            if (!previousKeys.contains(key)) {
                GameObjectInfo obj = gameObjects.get(key);
                if (obj != null) {
                    eventListener.onObjectSpawned(obj);
                }
            }
        }

        // Detect despawned objects (in previous but not in current)
        for (String key : previousKeys) {
            if (!currentKeys.contains(key)) {
                // Object was despawned - we don't have full info anymore,
                // but we can extract basic info from the key
                eventListener.onObjectDespawned(key);
            }
        }
    }

    private void updateGameObjects() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        Scene scene = client.getScene();
        if (scene == null) {
            return;
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        if (tiles == null || plane >= tiles.length) {
            return;
        }

        for (int x = 0; x < tiles[plane].length; x++) {
            for (int y = 0; y < tiles[plane][x].length; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }

                // Check game objects
                for (GameObject gameObject : tile.getGameObjects()) {
                    if (gameObject != null) {
                        cacheGameObject(gameObject);
                    }
                }

                // Check decorative objects
                DecorativeObject decorativeObject = tile.getDecorativeObject();
                if (decorativeObject != null) {
                    cacheDecorativeObject(decorativeObject);
                }

                // Check wall objects
                WallObject wallObject = tile.getWallObject();
                if (wallObject != null) {
                    cacheWallObject(wallObject);
                }

                // Check ground objects
                GroundObject groundObject = tile.getGroundObject();
                if (groundObject != null) {
                    cacheGroundObject(groundObject);
                }
            }
        }
    }

    private void cacheGameObject(GameObject gameObject) {
        int id = gameObject.getId();
        ObjectComposition composition = client.getObjectDefinition(id);
        if (composition == null) {
            return;
        }

        String name = composition.getName();
        if (name == null || name.equals("null")) {
            return;
        }

        WorldPoint location = gameObject.getWorldLocation();
        String key = getObjectKey(id, location);

        long now = System.currentTimeMillis();
        GameObjectInfo existing = gameObjects.get(key);

        List<String> actions = Arrays.asList(composition.getActions());

        GameObjectInfo info = new GameObjectInfo(
                id,
                name,
                location,
                actions,
                client.getPlane(),
                existing != null ? existing.getFirstSeen() : now,
                now
        );

        gameObjects.put(key, info);
    }

    private void cacheDecorativeObject(DecorativeObject obj) {
        int id = obj.getId();
        ObjectComposition composition = client.getObjectDefinition(id);
        if (composition == null || composition.getName() == null) {
            return;
        }

        WorldPoint location = obj.getWorldLocation();
        String key = getObjectKey(id, location);
        long now = System.currentTimeMillis();
        GameObjectInfo existing = gameObjects.get(key);

        List<String> actions = Arrays.asList(composition.getActions());

        GameObjectInfo info = new GameObjectInfo(
                id,
                composition.getName(),
                location,
                actions,
                client.getPlane(),
                existing != null ? existing.getFirstSeen() : now,
                now
        );

        gameObjects.put(key, info);
    }

    private void cacheWallObject(WallObject obj) {
        int id = obj.getId();
        ObjectComposition composition = client.getObjectDefinition(id);
        if (composition == null || composition.getName() == null) {
            return;
        }

        WorldPoint location = obj.getWorldLocation();
        String key = getObjectKey(id, location);
        long now = System.currentTimeMillis();
        GameObjectInfo existing = gameObjects.get(key);

        List<String> actions = Arrays.asList(composition.getActions());

        GameObjectInfo info = new GameObjectInfo(
                id,
                composition.getName(),
                location,
                actions,
                client.getPlane(),
                existing != null ? existing.getFirstSeen() : now,
                now
        );

        gameObjects.put(key, info);
    }

    private void cacheGroundObject(GroundObject obj) {
        int id = obj.getId();
        ObjectComposition composition = client.getObjectDefinition(id);
        if (composition == null || composition.getName() == null) {
            return;
        }

        WorldPoint location = obj.getWorldLocation();
        String key = getObjectKey(id, location);
        long now = System.currentTimeMillis();
        GameObjectInfo existing = gameObjects.get(key);

        List<String> actions = Arrays.asList(composition.getActions());

        GameObjectInfo info = new GameObjectInfo(
                id,
                composition.getName(),
                location,
                actions,
                client.getPlane(),
                existing != null ? existing.getFirstSeen() : now,
                now
        );

        gameObjects.put(key, info);
    }

    private void updateNPCs() {
        long now = System.currentTimeMillis();

        for (NPC npc : client.getNpcs()) {
            if (npc == null) {
                continue;
            }

            int index = npc.getIndex();
            NPCInfo existing = npcs.get(index);

            NPCComposition composition = npc.getTransformedComposition();
            if (composition == null) {
                continue;
            }

            List<String> actions = Arrays.asList(composition.getActions());

            NPCInfo info = new NPCInfo(
                    npc.getId(),
                    index,
                    npc.getName(),
                    npc.getWorldLocation(),
                    actions,
                    npc.getCombatLevel(),
                    npc.getHealthRatio(),
                    npc.getHealthScale(),
                    npc.getInteracting() != null,
                    npc.getOverheadText(),
                    npc.getAnimation(),
                    existing != null ? existing.getFirstSeen() : now,
                    now
            );

            npcs.put(index, info);
        }
    }

    private void cleanupStaleObjects() {
        gameObjects.entrySet().removeIf(entry ->
                entry.getValue().isStale(STALE_OBJECT_AGE_MS));
        npcs.entrySet().removeIf(entry ->
                entry.getValue().isStale(STALE_NPC_AGE_MS));
    }

    // Query methods

    public List<GameObjectInfo> getAllGameObjects() {
        return new ArrayList<>(gameObjects.values());
    }

    public List<NPCInfo> getAllNPCs() {
        return new ArrayList<>(npcs.values());
    }

    public List<GameObjectInfo> getObjectsNearby(int radius) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return Collections.emptyList();
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        return gameObjects.values().stream()
                .filter(obj -> obj.distanceFrom(playerLoc) <= radius)
                .collect(Collectors.toList());
    }

    public List<GameObjectInfo> getObjectsByName(String name) {
        return gameObjects.values().stream()
                .filter(obj -> obj.getName().equalsIgnoreCase(name))
                .collect(Collectors.toList());
    }

    public GameObjectInfo getClosestObjectByName(String name) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return null;
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        return gameObjects.values().stream()
                .filter(obj -> obj.getName().equalsIgnoreCase(name))
                .min(Comparator.comparingDouble(obj -> obj.distanceFrom(playerLoc)))
                .orElse(null);
    }

    public List<GameObjectInfo> getObjectsWithAction(String action) {
        return gameObjects.values().stream()
                .filter(obj -> obj.hasAction(action))
                .collect(Collectors.toList());
    }

    public GameObjectInfo getObjectAt(WorldPoint location) {
        return gameObjects.values().stream()
                .filter(obj -> obj.getLocation().equals(location))
                .findFirst()
                .orElse(null);
    }

    public GameObjectInfo getClosestObjectWithAction(String action) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return null;
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        return gameObjects.values().stream()
                .filter(obj -> obj.hasAction(action))
                .min(Comparator.comparingDouble(obj -> obj.distanceFrom(playerLoc)))
                .orElse(null);
    }

    public List<NPCInfo> getNPCsNearby(int radius) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return Collections.emptyList();
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        return npcs.values().stream()
                .filter(npc -> npc.distanceFrom(playerLoc) <= radius)
                .collect(Collectors.toList());
    }

    public List<NPCInfo> getNPCsByName(String name) {
        return npcs.values().stream()
                .filter(npc -> npc.getName().equalsIgnoreCase(name))
                .collect(Collectors.toList());
    }

    public NPCInfo getNPCById(int id) {
        return npcs.values().stream()
                .filter(npc -> npc.getId() == id)
                .findFirst()
                .orElse(null);
    }

    public NPCInfo getClosestNPC(String name) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return null;
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        return npcs.values().stream()
                .filter(npc -> npc.getName().equalsIgnoreCase(name))
                .min(Comparator.comparingDouble(npc -> npc.distanceFrom(playerLoc)))
                .orElse(null);
    }

    public int getObjectCount() {
        return gameObjects.size();
    }

    public int getNPCCount() {
        return npcs.size();
    }

    public void clear() {
        gameObjects.clear();
        npcs.clear();
    }

    private String getObjectKey(int id, WorldPoint location) {
        return String.format("%d_%d_%d_%d", id, location.getX(), location.getY(), location.getPlane());
    }
}
