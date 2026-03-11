package net.runelite.client.plugins.objectdetection;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.eventmonitor.EventMonitorPlugin;
import net.runelite.client.plugins.eventmonitor.GameEvent;
import net.runelite.client.plugins.eventmonitor.GameEventData;

import javax.inject.Inject;
import java.util.List;

@Slf4j
@PluginDescriptor(
        name = "Object Detection",
        description = "Detects and caches nearby game objects and NPCs for API access",
        tags = {"api", "objects", "detection"}
)
public class ObjectDetectionPlugin extends Plugin implements ObjectEventListener {

    @Inject
    private Client client;

    @Inject
    private PluginManager pluginManager;

    private ObjectManager objectManager;
    private EventMonitorPlugin eventMonitorPlugin;
    private int lastObjectCount = 0;
    private int lastNPCCount = 0;

    @Override
    protected void startUp() throws Exception {
        log.info("Object Detection Plugin started");
        objectManager = new ObjectManager(client);
        objectManager.setEventListener(this);

        // Get EventMonitorPlugin instance
        eventMonitorPlugin = getPluginInstance(EventMonitorPlugin.class);
        if (eventMonitorPlugin == null) {
            log.warn("EventMonitor plugin not loaded - object spawn/despawn events will not be broadcast");
        }
    }

    private <T extends Plugin> T getPluginInstance(Class<T> pluginClass) {
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (pluginClass.isInstance(plugin)) {
                try {
                    return pluginClass.cast(plugin);
                } catch (Exception e) {
                    log.error("Failed to cast plugin", e);
                }
            }
        }
        return null;
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Object Detection Plugin stopped");
        if (objectManager != null) {
            objectManager.clear();
            objectManager = null;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            log.info("Player logged in, starting object detection");
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            if (objectManager != null) {
                objectManager.clear();
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (objectManager == null) {
            return;
        }

        // Update object cache
        objectManager.update();

        // Log stats periodically (every 10 ticks)
        if (client.getTickCount() % 10 == 0) {
            int objectCount = objectManager.getObjectCount();
            int npcCount = objectManager.getNPCCount();

            if (objectCount != lastObjectCount || npcCount != lastNPCCount) {
                log.debug("Object cache: {} objects, {} NPCs", objectCount, npcCount);
                lastObjectCount = objectCount;
                lastNPCCount = npcCount;
            }
        }
    }

    // Public API methods for other plugins/API server

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    public List<GameObjectInfo> getAllGameObjects() {
        return objectManager != null ? objectManager.getAllGameObjects() : List.of();
    }

    public List<NPCInfo> getAllNPCs() {
        return objectManager != null ? objectManager.getAllNPCs() : List.of();
    }

    public List<GameObjectInfo> getObjectsNearby(int radius) {
        return objectManager != null ? objectManager.getObjectsNearby(radius) : List.of();
    }

    public List<GameObjectInfo> getObjectsByName(String name) {
        return objectManager != null ? objectManager.getObjectsByName(name) : List.of();
    }

    public GameObjectInfo getClosestObjectByName(String name) {
        return objectManager != null ? objectManager.getClosestObjectByName(name) : null;
    }

    public List<GameObjectInfo> getObjectsWithAction(String action) {
        return objectManager != null ? objectManager.getObjectsWithAction(action) : List.of();
    }

    public GameObjectInfo getObjectAt(WorldPoint location) {
        return objectManager != null ? objectManager.getObjectAt(location) : null;
    }

    public GameObjectInfo getClosestObjectWithAction(String action) {
        return objectManager != null ? objectManager.getClosestObjectWithAction(action) : null;
    }

    public List<NPCInfo> getNPCsNearby(int radius) {
        return objectManager != null ? objectManager.getNPCsNearby(radius) : List.of();
    }

    public List<NPCInfo> getNPCsByName(String name) {
        return objectManager != null ? objectManager.getNPCsByName(name) : List.of();
    }

    public NPCInfo getNPCById(int id) {
        return objectManager != null ? objectManager.getNPCById(id) : null;
    }

    public NPCInfo getClosestNPC(String name) {
        return objectManager != null ? objectManager.getClosestNPC(name) : null;
    }

    // ObjectEventListener implementation

    @Override
    public void onObjectSpawned(GameObjectInfo object) {
        log.debug("Object spawned: {} at {}", object.getName(), object.getLocation());

        if (eventMonitorPlugin != null) {
            GameEventData data = new GameEventData();
            data.put("object_id", object.getId());
            data.put("object_name", object.getName());
            data.put("x", object.getLocation().getX());
            data.put("y", object.getLocation().getY());
            data.put("plane", object.getPlane());
            data.put("actions", object.getActions().toString());

            GameEvent event = new GameEvent("object_spawned", System.currentTimeMillis(), data);
            broadcastEventToMonitor(event);
        }
    }

    @Override
    public void onObjectDespawned(String objectKey) {
        log.debug("Object despawned: {}", objectKey);

        if (eventMonitorPlugin != null) {
            GameEventData data = new GameEventData();
            data.put("object_key", objectKey);

            // Parse key to extract basic info
            String[] parts = objectKey.split("_");
            if (parts.length == 4) {
                try {
                    data.put("object_id", Integer.parseInt(parts[0]));
                    data.put("x", Integer.parseInt(parts[1]));
                    data.put("y", Integer.parseInt(parts[2]));
                    data.put("plane", Integer.parseInt(parts[3]));
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse object key: {}", objectKey);
                }
            }

            GameEvent event = new GameEvent("object_despawned", System.currentTimeMillis(), data);
            broadcastEventToMonitor(event);
        }
    }

    private void broadcastEventToMonitor(GameEvent event) {
        try {
            eventMonitorPlugin.broadcastEvent(event);
            log.debug("Successfully broadcast event: {}", event.getType());
        } catch (Exception e) {
            log.error("Failed to broadcast event: {}", event.getType(), e);
        }
    }
}
