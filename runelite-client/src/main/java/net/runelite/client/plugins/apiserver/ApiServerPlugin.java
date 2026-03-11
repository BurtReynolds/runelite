package net.runelite.client.plugins.apiserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.eventmonitor.EventMonitorPlugin;
import net.runelite.client.plugins.eventmonitor.GameEvent;
import net.runelite.client.plugins.eventmonitor.GameEventListener;
import net.runelite.client.plugins.gamestate.GameStatePlugin;
import net.runelite.client.plugins.gamestate.GameStateManager;
import net.runelite.client.plugins.gamestate.PlayerState;
import net.runelite.client.plugins.gamestate.InventoryState;
import net.runelite.client.plugins.gamestate.WorldState;
import net.runelite.client.plugins.gamestate.NPCInfo;
import net.runelite.client.plugins.objectdetection.ObjectDetectionPlugin;
import net.runelite.client.plugins.objectdetection.GameObjectInfo;
import net.runelite.client.plugins.interaction.InteractionPlugin;
import net.runelite.client.plugins.interaction.MouseMovementProfile;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Server plugin that exposes game state via REST and WebSocket endpoints
 */
@Slf4j
@PluginDescriptor(
    name = "API Server",
    description = "REST and WebSocket API server for external access",
    tags = {"api", "server", "external"}
)
public class ApiServerPlugin extends Plugin {

    @Inject
    private PluginManager pluginManager;

    private Javalin app;
    private Gson gson;
    private GameStatePlugin gameStatePlugin;
    private EventMonitorPlugin eventMonitorPlugin;
    private ObjectDetectionPlugin objectDetectionPlugin;
    private InteractionPlugin interactionPlugin;

    // WebSocket session management
    private final Map<WsContext, Set<String>> wsSessionFilters = new ConcurrentHashMap<>();
    private GameEventListener eventBroadcaster;

    @Override
    protected void startUp() throws Exception {
        log.info("API Server Plugin starting...");

        // Initialize Gson
        gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

        // Get references to other plugins
        gameStatePlugin = getPluginInstance(GameStatePlugin.class);
        eventMonitorPlugin = getPluginInstance(EventMonitorPlugin.class);
        objectDetectionPlugin = getPluginInstance(ObjectDetectionPlugin.class);
        interactionPlugin = getPluginInstance(InteractionPlugin.class);

        if (gameStatePlugin == null) {
            log.warn("GameState plugin not loaded - game state endpoints will return null");
        }

        if (eventMonitorPlugin == null) {
            log.warn("EventMonitor plugin not loaded - event endpoints will be limited");
        }

        if (objectDetectionPlugin == null) {
            log.warn("ObjectDetection plugin not loaded - object detection endpoints will be limited");
        }

        if (interactionPlugin == null) {
            log.warn("Interaction plugin not loaded - interaction endpoints will be disabled");
        }

        // Start Javalin server
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.prefer405over404 = true;
            config.http.generateEtags = true;
        }).start(7070);

        // Register HTTP routes
        registerRoutes();

        // Register WebSocket routes
        registerWebSocketRoutes();

        // Set up event broadcasting to WebSocket clients
        if (eventMonitorPlugin != null) {
            eventBroadcaster = this::broadcastEventToWebSockets;
            eventMonitorPlugin.addEventListener(eventBroadcaster);
            log.info("WebSocket event broadcasting enabled");
        }

        log.info("API Server started on http://localhost:7070");
        log.info("WebSocket endpoint: ws://localhost:7070/ws/events");
        log.info("Try: curl http://localhost:7070/api/v1/health");
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("API Server Plugin stopping...");

        // Remove event listener
        if (eventMonitorPlugin != null && eventBroadcaster != null) {
            eventMonitorPlugin.removeEventListener(eventBroadcaster);
            eventBroadcaster = null;
        }

        // Close all WebSocket connections
        wsSessionFilters.keySet().forEach(ctx -> {
            try {
                ctx.session.close();
            } catch (Exception e) {
                log.warn("Error closing WebSocket session", e);
            }
        });
        wsSessionFilters.clear();

        if (app != null) {
            app.stop();
            app = null;
        }

        log.info("API Server stopped");
    }

    private void registerRoutes() {
        // Health check endpoint
        app.get("/api/v1/health", this::handleHealth);

        // Game state endpoints
        app.get("/api/v1/player", this::handleGetPlayer);
        app.get("/api/v1/player/inventory", this::handleGetInventory);
        app.get("/api/v1/player/stats", this::handleGetStats);
        app.get("/api/v1/player/position", this::handleGetPosition);

        // World state endpoints
        app.get("/api/v1/world", this::handleGetWorld);
        app.get("/api/v1/world/npcs", this::handleGetNPCs);

        // Object detection endpoints
        app.get("/api/v1/objects/all", this::handleGetAllObjects);
        app.get("/api/v1/objects/nearby", this::handleGetObjectsNearby);
        app.get("/api/v1/objects/name/{name}", this::handleGetObjectsByName);
        app.get("/api/v1/objects/closest", this::handleGetClosestObjectByName);
        app.get("/api/v1/objects/action/{action}", this::handleGetObjectsWithAction);
        app.get("/api/v1/objects/stats", this::handleGetObjectStats);

        // Event endpoints
        app.get("/api/v1/events/recent", this::handleGetRecentEvents);
        app.get("/api/v1/events/recent/{limit}", this::handleGetRecentEventsWithLimit);

        // Interaction endpoints
        app.post("/api/v1/interaction/mouse/move", this::handleMouseMove);
        app.post("/api/v1/interaction/mouse/click", this::handleMouseClick);
        app.post("/api/v1/interaction/inventory/click", this::handleInventoryClick);
        app.post("/api/v1/interaction/inventory/item/click", this::handleInventoryItemClick);
        app.post("/api/v1/interaction/tab/open", this::handleOpenTab);
        app.post("/api/v1/interaction/prayer/toggle", this::handleTogglePrayer);
        app.post("/api/v1/interaction/object/interact", this::handleObjectInteract);

        // Root endpoint
        app.get("/", this::handleRoot);

        log.info("Registered {} routes", app.jettyServer().server().getHandlers().length);
    }

    private void handleHealth(Context ctx) {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("timestamp", System.currentTimeMillis());
        health.put("gameStatePlugin", gameStatePlugin != null ? "loaded" : "not loaded");
        health.put("eventMonitorPlugin", eventMonitorPlugin != null ? "loaded" : "not loaded");

        ctx.json(health);
    }

    private void handleRoot(Context ctx) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "RuneLite API Server");
        info.put("version", "1.2.0");

        Map<String, String> endpoints = new HashMap<>();

        // Game state endpoints
        endpoints.put("health", "GET /api/v1/health");
        endpoints.put("player", "GET /api/v1/player");
        endpoints.put("inventory", "GET /api/v1/player/inventory");
        endpoints.put("stats", "GET /api/v1/player/stats");
        endpoints.put("position", "GET /api/v1/player/position");
        endpoints.put("world", "GET /api/v1/world");
        endpoints.put("npcs", "GET /api/v1/world/npcs");

        // Object detection endpoints
        endpoints.put("allObjects", "GET /api/v1/objects/all");
        endpoints.put("objectsNearby", "GET /api/v1/objects/nearby?radius=10");
        endpoints.put("objectsByName", "GET /api/v1/objects/name/{name}");
        endpoints.put("closestObject", "GET /api/v1/objects/closest?name={name}");
        endpoints.put("objectsByAction", "GET /api/v1/objects/action/{action}");
        endpoints.put("objectStats", "GET /api/v1/objects/stats");

        // Event endpoints
        endpoints.put("events", "GET /api/v1/events/recent");
        endpoints.put("eventsLimit", "GET /api/v1/events/recent/{limit}");

        // Interaction endpoints (POST)
        endpoints.put("mouseMove", "POST /api/v1/interaction/mouse/move {x, y, profile?}");
        endpoints.put("mouseClick", "POST /api/v1/interaction/mouse/click {x, y, profile?}");
        endpoints.put("inventoryClick", "POST /api/v1/interaction/inventory/click {slot, profile?}");
        endpoints.put("inventoryItemClick", "POST /api/v1/interaction/inventory/item/click {itemName, profile?}");
        endpoints.put("openTab", "POST /api/v1/interaction/tab/open {tab, profile?}");
        endpoints.put("togglePrayer", "POST /api/v1/interaction/prayer/toggle {groupId, childId, profile?}");
        endpoints.put("objectInteract", "POST /api/v1/interaction/object/interact {objectName, action?, profile?}");

        info.put("endpoints", endpoints);
        info.put("websocket", "ws://localhost:7070/ws/events");
        ctx.json(info);
    }

    private void handleGetPlayer(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        GameStateManager manager = gameStatePlugin.getStateManager();
        if (manager == null) {
            ctx.status(503).json(createError("GameState manager not initialized"));
            return;
        }

        PlayerState playerState = manager.getPlayerState();
        if (playerState == null) {
            ctx.status(404).json(createError("Player data not available (not logged in?)"));
            return;
        }

        ctx.json(playerState);
    }

    private void handleGetInventory(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        GameStateManager manager = gameStatePlugin.getStateManager();
        if (manager == null) {
            ctx.status(503).json(createError("GameState manager not initialized"));
            return;
        }

        InventoryState inventory = manager.getInventoryState();
        if (inventory == null) {
            ctx.status(404).json(createError("Inventory data not available"));
            return;
        }

        ctx.json(inventory);
    }

    private void handleGetStats(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        PlayerState playerState = gameStatePlugin.getStateManager().getPlayerState();
        if (playerState == null) {
            ctx.status(404).json(createError("Player data not available"));
            return;
        }

        // Extract just the stats portion
        Map<String, Object> stats = new HashMap<>();
        stats.put("health", playerState.getHealth());
        stats.put("maxHealth", playerState.getMaxHealth());
        stats.put("prayer", playerState.getPrayer());
        stats.put("maxPrayer", playerState.getMaxPrayer());
        stats.put("energy", playerState.getEnergy());
        stats.put("weight", playerState.getWeight());
        stats.put("combatLevel", playerState.getCombatLevel());

        ctx.json(stats);
    }

    private void handleGetPosition(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        PlayerState playerState = gameStatePlugin.getStateManager().getPlayerState();
        if (playerState == null) {
            ctx.status(404).json(createError("Player data not available"));
            return;
        }

        Map<String, Object> position = new HashMap<>();
        if (playerState.getPosition() != null) {
            position.put("x", playerState.getPosition().getX());
            position.put("y", playerState.getPosition().getY());
            position.put("plane", playerState.getPlane());
            position.put("regionID", playerState.getPosition().getRegionID());
        }
        position.put("isMoving", playerState.isMoving());
        position.put("isInteracting", playerState.isInteracting());
        position.put("animation", playerState.getAnimation());

        ctx.json(position);
    }

    private void handleGetWorld(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        WorldState worldState = gameStatePlugin.getStateManager().getWorldState();
        if (worldState == null) {
            ctx.status(404).json(createError("World data not available"));
            return;
        }

        ctx.json(worldState);
    }

    private void handleGetNPCs(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        List<NPCInfo> npcs = gameStatePlugin.getStateManager().getNearbyNPCs();
        if (npcs == null) {
            ctx.status(404).json(createError("NPC data not available"));
            return;
        }

        ctx.json(Map.of(
            "count", npcs.size(),
            "npcs", npcs
        ));
    }

    // Object Detection Handlers

    private void handleGetAllObjects(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        List<GameObjectInfo> objects = objectDetectionPlugin.getAllGameObjects();
        ctx.json(Map.of(
            "count", objects.size(),
            "objects", objects
        ));
    }

    private void handleGetObjectsNearby(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        int radius = ctx.queryParamAsClass("radius", Integer.class).getOrDefault(10);
        if (radius < 1 || radius > 100) {
            ctx.status(400).json(createError("Radius must be between 1 and 100"));
            return;
        }

        List<GameObjectInfo> objects = objectDetectionPlugin.getObjectsNearby(radius);
        ctx.json(Map.of(
            "radius", radius,
            "count", objects.size(),
            "objects", objects
        ));
    }

    private void handleGetObjectsByName(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        String name = ctx.pathParam("name");
        if (name == null || name.trim().isEmpty()) {
            ctx.status(400).json(createError("Object name is required"));
            return;
        }

        List<GameObjectInfo> objects = objectDetectionPlugin.getObjectsByName(name);
        ctx.json(Map.of(
            "name", name,
            "count", objects.size(),
            "objects", objects
        ));
    }

    private void handleGetClosestObjectByName(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        String name = ctx.queryParam("name");
        if (name == null || name.trim().isEmpty()) {
            ctx.status(400).json(createError("Object name query parameter is required"));
            return;
        }

        GameObjectInfo object = objectDetectionPlugin.getClosestObjectByName(name);
        if (object == null) {
            ctx.status(404).json(createError("No object found with name: " + name));
            return;
        }

        ctx.json(object);
    }

    private void handleGetObjectsWithAction(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        String action = ctx.pathParam("action");
        if (action == null || action.trim().isEmpty()) {
            ctx.status(400).json(createError("Action is required"));
            return;
        }

        List<GameObjectInfo> objects = objectDetectionPlugin.getObjectsWithAction(action);
        ctx.json(Map.of(
            "action", action,
            "count", objects.size(),
            "objects", objects
        ));
    }

    private void handleGetObjectStats(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        int objectCount = objectDetectionPlugin.getObjectManager() != null
            ? objectDetectionPlugin.getObjectManager().getObjectCount()
            : 0;
        int npcCount = objectDetectionPlugin.getObjectManager() != null
            ? objectDetectionPlugin.getObjectManager().getNPCCount()
            : 0;

        ctx.json(Map.of(
            "cached_objects", objectCount,
            "cached_npcs", npcCount,
            "timestamp", System.currentTimeMillis()
        ));
    }

    private void handleGetRecentEvents(Context ctx) {
        handleGetRecentEventsWithLimit(ctx, 100);
    }

    private void handleGetRecentEventsWithLimit(Context ctx) {
        String limitStr = ctx.pathParam("limit");
        int limit = 100;

        try {
            limit = Integer.parseInt(limitStr);
            if (limit < 1 || limit > 1000) {
                ctx.status(400).json(createError("Limit must be between 1 and 1000"));
                return;
            }
        } catch (NumberFormatException e) {
            ctx.status(400).json(createError("Invalid limit parameter"));
            return;
        }

        handleGetRecentEventsWithLimit(ctx, limit);
    }

    private void handleGetRecentEventsWithLimit(Context ctx, int limit) {
        if (eventMonitorPlugin == null) {
            ctx.status(503).json(createError("EventMonitor plugin not loaded"));
            return;
        }

        List<GameEvent> events = eventMonitorPlugin.getRecentEvents(limit);

        ctx.json(Map.of(
            "count", events.size(),
            "limit", limit,
            "events", events
        ));
    }

    // Interaction Handlers

    private void handleMouseMove(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int x = ((Number) body.get("x")).intValue();
            int y = ((Number) body.get("y")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            interactionPlugin.moveMouseTo(x, y, profile);

            ctx.json(Map.of("success", true, "x", x, "y", y, "profile", profileName));
        } catch (Exception e) {
            log.error("Error moving mouse", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleMouseClick(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int x = ((Number) body.get("x")).intValue();
            int y = ((Number) body.get("y")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            interactionPlugin.clickAt(x, y, profile);

            ctx.json(Map.of("success", true, "x", x, "y", y, "profile", profileName));
        } catch (Exception e) {
            log.error("Error clicking mouse", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleInventoryClick(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int slot = ((Number) body.get("slot")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.clickInventorySlot(slot, profile);

            ctx.json(Map.of("success", success, "slot", slot));
        } catch (Exception e) {
            log.error("Error clicking inventory", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleInventoryItemClick(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.clickInventoryItem(itemName, profile);

            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            log.error("Error clicking inventory item", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleOpenTab(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String tab = (String) body.get("tab");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = false;

            switch (tab.toUpperCase()) {
                case "EQUIPMENT":
                    success = interactionPlugin.openEquipment(profile);
                    break;
                case "STATS":
                    success = interactionPlugin.openStats(profile);
                    break;
                case "QUESTS":
                    success = interactionPlugin.openQuests(profile);
                    break;
                case "PRAYER":
                case "PRAYERS":
                    success = interactionPlugin.openPrayers(profile);
                    break;
                case "MAGIC":
                    success = interactionPlugin.openMagic(profile);
                    break;
                default:
                    ctx.status(400).json(createError("Unknown tab: " + tab));
                    return;
            }

            ctx.json(Map.of("success", success, "tab", tab));
        } catch (Exception e) {
            log.error("Error opening tab", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleTogglePrayer(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int groupId = ((Number) body.get("groupId")).intValue();
            int childId = ((Number) body.get("childId")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.togglePrayerByWidgetId(groupId, childId, profile);

            ctx.json(Map.of("success", success, "groupId", groupId, "childId", childId));
        } catch (Exception e) {
            log.error("Error toggling prayer", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleObjectInteract(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String objectName = (String) body.get("objectName");
            String action = (String) body.get("action");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success;

            if (action != null && !action.trim().isEmpty()) {
                success = interactionPlugin.interactWithObject(objectName, action, profile);
            } else {
                success = interactionPlugin.interactWithObject(objectName, profile);
            }

            ctx.json(Map.of("success", success, "objectName", objectName,
                "action", action != null ? action : "default"));
        } catch (Exception e) {
            log.error("Error interacting with object", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private Map<String, Object> createError(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }

    private <T extends Plugin> T getPluginInstance(Class<T> pluginClass) {
        if (pluginManager == null) {
            return null;
        }

        try {
            for (Plugin plugin : pluginManager.getPlugins()) {
                if (pluginClass.isInstance(plugin)) {
                    return pluginClass.cast(plugin);
                }
            }
        } catch (Exception e) {
            log.error("Error getting plugin instance: {}", pluginClass.getSimpleName(), e);
        }

        return null;
    }

    private void registerWebSocketRoutes() {
        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> {
                log.info("WebSocket client connected: {}", ctx.session.getRemoteAddress());
                // Initialize with no filters (receive all events)
                wsSessionFilters.put(ctx, new HashSet<>());

                // Send welcome message
                Map<String, Object> welcome = new HashMap<>();
                welcome.put("type", "connected");
                welcome.put("message", "Connected to RuneLite Event Stream");
                welcome.put("timestamp", System.currentTimeMillis());
                ctx.send(gson.toJson(welcome));
            });

            ws.onMessage(ctx -> {
                try {
                    // Parse incoming message as JSON
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = gson.fromJson(ctx.message(), Map.class);
                    String type = (String) message.get("type");

                    if ("subscribe".equals(type)) {
                        // Update event filters for this session
                        @SuppressWarnings("unchecked")
                        List<String> events = (List<String>) message.get("events");
                        if (events != null) {
                            wsSessionFilters.put(ctx, new HashSet<>(events));
                            log.info("Client subscribed to {} event types", events.size());

                            Map<String, Object> response = new HashMap<>();
                            response.put("type", "subscribed");
                            response.put("events", events);
                            ctx.send(gson.toJson(response));
                        }
                    } else if ("unsubscribe".equals(type)) {
                        // Clear filters (receive all events)
                        wsSessionFilters.put(ctx, new HashSet<>());

                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "unsubscribed");
                        ctx.send(gson.toJson(response));
                    }
                } catch (Exception e) {
                    log.error("Error processing WebSocket message", e);
                }
            });

            ws.onClose(ctx -> {
                log.info("WebSocket client disconnected: {}", ctx.session.getRemoteAddress());
                wsSessionFilters.remove(ctx);
            });

            ws.onError(ctx -> {
                log.error("WebSocket error for client {}: {}",
                    ctx.session.getRemoteAddress(),
                    ctx.error() != null ? ctx.error().getMessage() : "unknown error");
                wsSessionFilters.remove(ctx);
            });
        });

        log.info("WebSocket route registered: /ws/events");
    }

    private void broadcastEventToWebSockets(GameEvent event) {
        if (wsSessionFilters.isEmpty()) {
            return; // No connected clients
        }

        String eventJson = gson.toJson(event);

        // Broadcast to all connected clients (respecting their filters)
        wsSessionFilters.forEach((ctx, filters) -> {
            try {
                // If no filters set, send all events
                // If filters set, only send matching events
                if (filters.isEmpty() || filters.contains(event.getType())) {
                    ctx.send(eventJson);
                }
            } catch (Exception e) {
                log.warn("Error broadcasting event to WebSocket client", e);
            }
        });
    }
}
