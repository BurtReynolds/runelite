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
import net.runelite.client.plugins.gamestate.SkillState;
import net.runelite.client.plugins.gamestate.EquipmentItem;
import net.runelite.client.plugins.eventmonitor.ChatEntry;
import net.runelite.client.plugins.objectdetection.ObjectDetectionPlugin;
import net.runelite.client.plugins.objectdetection.GameObjectInfo;
import net.runelite.client.plugins.interaction.InteractionPlugin;
import net.runelite.client.plugins.interaction.MouseMovementProfile;
import net.runelite.client.plugins.interaction.PlayerTab;
import net.runelite.client.plugins.interaction.TaskSequencer;
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

    // Active task sequence (only one at a time)
    private volatile TaskSequencer activeSequence;

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
        app.get("/api/v1/player/skills", this::handleGetSkills);
        app.get("/api/v1/player/skills/{name}", this::handleGetSkillByName);
        app.get("/api/v1/player/equipment", this::handleGetEquipment);

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
        app.post("/api/v1/interaction/npc/interact", this::handleNPCInteract);

        // Equipment interaction endpoints
        app.post("/api/v1/interaction/equipment/click", this::handleEquipmentClick);
        app.post("/api/v1/interaction/equipment/item/click", this::handleEquipmentItemClick);
        app.post("/api/v1/interaction/equipment/select", this::handleEquipmentSelect);
        app.post("/api/v1/interaction/equipment/item/select", this::handleEquipmentItemSelect);

        // Dialog option endpoints
        app.post("/api/v1/interaction/dialog/select", this::handleDialogSelect);
        app.get("/api/v1/interaction/dialog/options", this::handleGetDialogOptions);
        app.get("/api/v1/interaction/dialog/debug", this::handleDebugDialogWidgets);

        // Sub-menu interaction endpoints
        app.post("/api/v1/interaction/equipment/item/submenu-select", this::handleEquipmentItemSubMenuSelect);
        app.post("/api/v1/interaction/inventory/item/submenu-select", this::handleInventoryItemSubMenuSelect);

        // Menu interaction endpoints
        app.post("/api/v1/interaction/menu/select", this::handleMenuSelect);
        app.post("/api/v1/interaction/menu/right-click-select", this::handleRightClickAndSelect);
        app.get("/api/v1/interaction/menu/options", this::handleGetMenuOptions);

        // Virtual cursor overlay
        app.post("/api/v1/interaction/cursor/toggle", this::handleCursorToggle);
        app.get("/api/v1/interaction/cursor/status", this::handleCursorStatus);

        // Bank interaction endpoints
        app.get("/api/v1/bank/items", this::handleGetBankItems);
        app.get("/api/v1/bank/status", this::handleBankStatus);
        app.get("/api/v1/bank/debug", this::handleBankDebug);
        app.post("/api/v1/bank/close", this::handleBankClose);
        app.post("/api/v1/bank/deposit-inventory", this::handleBankDepositInventory);
        app.post("/api/v1/bank/deposit-equipment", this::handleBankDepositEquipment);
        app.post("/api/v1/bank/tab", this::handleBankTab);
        app.post("/api/v1/bank/quantity", this::handleBankQuantity);
        app.post("/api/v1/bank/note-mode", this::handleBankNoteMode);
        app.post("/api/v1/bank/search", this::handleBankSearch);
        app.post("/api/v1/bank/withdraw", this::handleBankWithdraw);
        app.post("/api/v1/bank/deposit", this::handleBankDeposit);

        // Web walking endpoints
        app.post("/api/v1/interaction/walk", this::handleWebWalk);
        app.post("/api/v1/interaction/walk/cancel", this::handleWebWalkCancel);
        app.get("/api/v1/interaction/walk/debug", this::handleWebWalkDebug);

        // Task sequencer endpoints
        app.post("/api/v1/interaction/task/execute", this::handleTaskExecute);
        app.get("/api/v1/interaction/task/status", this::handleTaskStatus);
        app.post("/api/v1/interaction/task/cancel", this::handleTaskCancel);

        // Chat endpoints
        app.get("/api/v1/chat/recent", this::handleGetRecentChat);

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
        info.put("version", "1.3.0");

        Map<String, String> endpoints = new HashMap<>();

        // Game state endpoints
        endpoints.put("health", "GET /api/v1/health");
        endpoints.put("player", "GET /api/v1/player");
        endpoints.put("inventory", "GET /api/v1/player/inventory");
        endpoints.put("stats", "GET /api/v1/player/stats");
        endpoints.put("position", "GET /api/v1/player/position");
        endpoints.put("world", "GET /api/v1/world");
        endpoints.put("npcs", "GET /api/v1/world/npcs");
        endpoints.put("skills", "GET /api/v1/player/skills");
        endpoints.put("skillByName", "GET /api/v1/player/skills/{name}");
        endpoints.put("equipment", "GET /api/v1/player/equipment");

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
        endpoints.put("npcInteract", "POST /api/v1/interaction/npc/interact {npcName, action?, profile?}");

        // Equipment interaction endpoints
        endpoints.put("equipmentClick", "POST /api/v1/interaction/equipment/click {slot, profile?}");
        endpoints.put("equipmentItemClick", "POST /api/v1/interaction/equipment/item/click {itemName, profile?}");
        endpoints.put("equipmentSelect", "POST /api/v1/interaction/equipment/select {slot, option, profile?}");
        endpoints.put("equipmentItemSelect", "POST /api/v1/interaction/equipment/item/select {itemName, option, profile?}");

        // Sub-menu interaction endpoints
        endpoints.put("equipmentItemSubMenuSelect", "POST /api/v1/interaction/equipment/item/submenu-select {itemName, parentOption, subOption, profile?}");
        endpoints.put("inventoryItemSubMenuSelect", "POST /api/v1/interaction/inventory/item/submenu-select {itemName, parentOption, subOption, profile?}");

        // Dialog option endpoints
        endpoints.put("dialogSelect", "POST /api/v1/interaction/dialog/select {option, timeoutMs?, profile?}");
        endpoints.put("dialogOptions", "GET /api/v1/interaction/dialog/options");

        // Menu endpoints
        endpoints.put("menuSelect", "POST /api/v1/interaction/menu/select {option, target?, profile?}");
        endpoints.put("menuRightClickSelect", "POST /api/v1/interaction/menu/right-click-select {x, y, option, target?, profile?}");
        endpoints.put("menuOptions", "GET /api/v1/interaction/menu/options");

        // Web walking endpoints
        endpoints.put("webWalk", "POST /api/v1/interaction/walk {x, y, plane?, profile?}");
        endpoints.put("webWalkCancel", "POST /api/v1/interaction/walk/cancel");
        endpoints.put("webWalkDebug", "GET /api/v1/interaction/walk/debug?x=&y=&plane=");

        // Task sequencer endpoints
        endpoints.put("taskExecute", "POST /api/v1/interaction/task/execute {steps: [...], profile?, stopOnFailure?}");
        endpoints.put("taskStatus", "GET /api/v1/interaction/task/status");
        endpoints.put("taskCancel", "POST /api/v1/interaction/task/cancel");

        // Bank endpoints
        endpoints.put("bankItems", "GET /api/v1/bank/items");
        endpoints.put("bankStatus", "GET /api/v1/bank/status");
        endpoints.put("bankDebug", "GET /api/v1/bank/debug");
        endpoints.put("bankClose", "POST /api/v1/bank/close");
        endpoints.put("bankDepositInventory", "POST /api/v1/bank/deposit-inventory");
        endpoints.put("bankDepositEquipment", "POST /api/v1/bank/deposit-equipment");
        endpoints.put("bankTab", "POST /api/v1/bank/tab {tab}");
        endpoints.put("bankQuantity", "POST /api/v1/bank/quantity {quantity}");
        endpoints.put("bankNoteMode", "POST /api/v1/bank/note-mode");
        endpoints.put("bankSearch", "POST /api/v1/bank/search {query}");
        endpoints.put("bankWithdraw", "POST /api/v1/bank/withdraw {itemName, quantity?, option?}");
        endpoints.put("bankDeposit", "POST /api/v1/bank/deposit {itemName, quantity?, option?}");

        // Chat endpoints
        endpoints.put("chatRecent", "GET /api/v1/chat/recent?limit=50&type=GAMEMESSAGE");

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

            String tabName = (String) body.get("tab");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            PlayerTab tab = PlayerTab.fromString(tabName);
            if (tab == null) {
                ctx.status(400).json(createError("Unknown tab: " + tabName
                    + ". Valid tabs: COMBAT, STATS, QUESTS, INVENTORY, EQUIPMENT (or WORN_EQUIPMENT), "
                    + "PRAYER, MAGIC, FRIENDS_CHAT, ACCOUNT, FRIENDS, LOGOUT, OPTIONS, EMOTES, MUSIC"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.openPlayerTab(tab, profile);

            ctx.json(Map.of("success", success, "tab", tab.name()));
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

    // ===== Menu Interaction Handlers =====

    private void handleMenuSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String option = (String) body.get("option");
            String target = (String) body.get("target");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (option == null || option.trim().isEmpty()) {
                ctx.status(400).json(createError("'option' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.selectMenuOption(option, target, profile);

            ctx.json(Map.of("success", success, "option", option,
                "target", target != null ? target : ""));
        } catch (Exception e) {
            log.error("Error selecting menu option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleRightClickAndSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int x = ((Number) body.get("x")).intValue();
            int y = ((Number) body.get("y")).intValue();
            String option = (String) body.get("option");
            String target = (String) body.get("target");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (option == null || option.trim().isEmpty()) {
                ctx.status(400).json(createError("'option' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickAndSelect(x, y, option, target, profile);

            ctx.json(Map.of("success", success, "x", x, "y", y, "option", option));
        } catch (Exception e) {
            log.error("Error with right-click and select", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Equipment Interaction Handlers =====

    private void handleEquipmentClick(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String slot = (String) body.get("slot");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (slot == null || slot.trim().isEmpty()) {
                ctx.status(400).json(createError("'slot' is required (e.g., HEAD, WEAPON, RING)"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.clickEquipmentSlot(slot, profile);

            ctx.json(Map.of("success", success, "slot", slot));
        } catch (Exception e) {
            log.error("Error clicking equipment slot", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleEquipmentItemClick(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.clickEquipmentItem(itemName, profile);

            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            log.error("Error clicking equipment item", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleEquipmentSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String slot = (String) body.get("slot");
            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (slot == null || slot.trim().isEmpty()) {
                ctx.status(400).json(createError("'slot' is required"));
                return;
            }
            if (option == null || option.trim().isEmpty()) {
                ctx.status(400).json(createError("'option' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickEquipmentSlotAndSelect(slot, option, profile);

            ctx.json(Map.of("success", success, "slot", slot, "option", option));
        } catch (Exception e) {
            log.error("Error selecting equipment option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleEquipmentItemSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            if (option == null || option.trim().isEmpty()) {
                ctx.status(400).json(createError("'option' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickEquipmentItemAndSelect(itemName, option, profile);

            ctx.json(Map.of("success", success, "itemName", itemName, "option", option));
        } catch (Exception e) {
            log.error("Error selecting equipment item option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Sub-Menu Handlers =====

    private void handleEquipmentItemSubMenuSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            String parentOption = (String) body.get("parentOption");
            String subOption = (String) body.get("subOption");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            if (parentOption == null || parentOption.trim().isEmpty()) {
                ctx.status(400).json(createError("'parentOption' is required"));
                return;
            }
            if (subOption == null || subOption.trim().isEmpty()) {
                ctx.status(400).json(createError("'subOption' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickEquipmentItemHoverAndSelect(
                itemName, parentOption, subOption, profile);

            ctx.json(Map.of("success", success, "itemName", itemName,
                "parentOption", parentOption, "subOption", subOption));
        } catch (Exception e) {
            log.error("Error selecting equipment item sub-menu option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleInventoryItemSubMenuSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            String parentOption = (String) body.get("parentOption");
            String subOption = (String) body.get("subOption");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            if (parentOption == null || parentOption.trim().isEmpty()) {
                ctx.status(400).json(createError("'parentOption' is required"));
                return;
            }
            if (subOption == null || subOption.trim().isEmpty()) {
                ctx.status(400).json(createError("'subOption' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickInventoryItemHoverAndSelect(
                itemName, parentOption, subOption, profile);

            ctx.json(Map.of("success", success, "itemName", itemName,
                "parentOption", parentOption, "subOption", subOption));
        } catch (Exception e) {
            log.error("Error selecting inventory item sub-menu option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Dialog Option Handlers =====

    private void handleDialogSelect(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            int timeoutMs = body.containsKey("timeoutMs")
                ? ((Number) body.get("timeoutMs")).intValue()
                : 0;

            if (option == null || option.trim().isEmpty()) {
                ctx.status(400).json(createError("'option' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success;

            if (timeoutMs > 0) {
                success = interactionPlugin.waitAndSelectDialogOption(option, timeoutMs, profile);
            } else {
                success = interactionPlugin.selectDialogOption(option, profile);
            }

            ctx.json(Map.of("success", success, "option", option));
        } catch (Exception e) {
            log.error("Error selecting dialog option", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleGetDialogOptions(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        String[] options = interactionPlugin.getDialogOptions();
        ctx.json(Map.of(
            "dialogOpen", options.length > 0,
            "count", options.length,
            "options", options
        ));
    }

    private void handleDebugDialogWidgets(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        ctx.json(interactionPlugin.debugScanDialogWidgets());
    }

    private void handleGetMenuOptions(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        String[] options = interactionPlugin.getMenuOptions();
        ctx.json(Map.of(
            "menuOpen", options.length > 0,
            "count", options.length,
            "options", options
        ));
    }

    // ===== Web Walking Handlers =====

    private volatile java.util.concurrent.CompletableFuture<Boolean> activeWalkFuture = null;

    private void handleWebWalk(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int x = ((Number) body.get("x")).intValue();
            int y = ((Number) body.get("y")).intValue();
            int plane = body.containsKey("plane") ? ((Number) body.get("plane")).intValue() : 0;
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            WorldPoint destination = new WorldPoint(x, y, plane);

            activeWalkFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                interactionPlugin.webWalkTo(destination, profile));

            ctx.json(Map.of(
                "success", true,
                "message", "Web walk started",
                "destination", Map.of("x", x, "y", y, "plane", plane)
            ));
        } catch (Exception e) {
            log.error("Error starting web walk", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleWebWalkCancel(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        interactionPlugin.cancelWebWalk();
        ctx.json(Map.of("success", true, "message", "Web walk cancel requested"));
    }

    private void handleWebWalkDebug(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        WorldPoint dest = null;
        String xParam = ctx.queryParam("x");
        String yParam = ctx.queryParam("y");
        if (xParam != null && yParam != null) {
            int x = Integer.parseInt(xParam);
            int y = Integer.parseInt(yParam);
            int plane = ctx.queryParamAsClass("plane", Integer.class).getOrDefault(0);
            dest = new WorldPoint(x, y, plane);
        }

        ctx.json(interactionPlugin.getWebWalker().getDebugInfo(dest));
    }

    // ===== Task Sequencer Handlers =====

    @SuppressWarnings("unchecked")
    private void handleTaskExecute(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        if (activeSequence != null && activeSequence.isRunning()) {
            ctx.status(409).json(createError("A task sequence is already running. Cancel it first or wait for it to finish."));
            return;
        }

        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            boolean stopOnFail = body.containsKey("stopOnFailure")
                ? (Boolean) body.get("stopOnFailure")
                : true;

            List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) body.get("steps");
            if (stepDefs == null || stepDefs.isEmpty()) {
                ctx.status(400).json(createError("'steps' array is required and must not be empty"));
                return;
            }

            TaskSequencer seq = interactionPlugin.createTaskSequence()
                .withProfile(profileName)
                .stopOnFailure(stopOnFail);

            // Parse each step
            for (Map<String, Object> stepDef : stepDefs) {
                String action = (String) stepDef.get("action");
                if (action == null) {
                    ctx.status(400).json(createError("Each step must have an 'action' field"));
                    return;
                }

                if (!buildStep(seq, action, stepDef)) {
                    ctx.status(400).json(createError("Unknown action: " + action));
                    return;
                }
            }

            activeSequence = seq;

            // Execute async
            seq.execute().thenAccept(result -> {
                log.info("Task sequence finished: success={}, completed={}/{}",
                    result.isSuccess(), result.getCompletedSteps(), result.getTotalSteps());
            });

            ctx.json(Map.of(
                "success", true,
                "message", "Task sequence started",
                "totalSteps", stepDefs.size()
            ));
        } catch (Exception e) {
            log.error("Error starting task sequence", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private boolean buildStep(TaskSequencer seq, String action, Map<String, Object> params) {
        switch (action.toLowerCase()) {
            case "move_mouse":
                seq.moveMouse(
                    ((Number) params.get("x")).intValue(),
                    ((Number) params.get("y")).intValue()
                );
                return true;
            case "click":
                if (params.containsKey("x") && params.containsKey("y")) {
                    seq.clickAt(
                        ((Number) params.get("x")).intValue(),
                        ((Number) params.get("y")).intValue()
                    );
                } else {
                    seq.click();
                }
                return true;
            case "right_click":
                seq.rightClick();
                return true;
            case "open_tab":
                seq.openTab((String) params.get("tab"));
                return true;
            case "click_inventory_slot":
                seq.clickInventorySlot(((Number) params.get("slot")).intValue());
                return true;
            case "click_inventory_item":
                seq.clickInventoryItem((String) params.get("itemName"));
                return true;
            case "right_click_inventory_select":
                seq.rightClickInventoryItemAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("option")
                );
                return true;
            case "select_menu_option":
                String target = (String) params.get("target");
                if (target != null) {
                    seq.selectMenuOption((String) params.get("option"), target);
                } else {
                    seq.selectMenuOption((String) params.get("option"));
                }
                return true;
            case "right_click_and_select":
                seq.rightClickAndSelect(
                    ((Number) params.get("x")).intValue(),
                    ((Number) params.get("y")).intValue(),
                    (String) params.get("option")
                );
                return true;
            case "interact_object":
                String objAction = (String) params.get("objectAction");
                if (objAction != null) {
                    seq.interactWithObject((String) params.get("objectName"), objAction);
                } else {
                    seq.interactWithObject((String) params.get("objectName"));
                }
                return true;
            case "interact_npc":
                String npcAction = (String) params.get("npcAction");
                if (npcAction != null) {
                    seq.interactWithNPC((String) params.get("npcName"), npcAction);
                } else {
                    seq.interactWithNPC((String) params.get("npcName"));
                }
                return true;
            case "click_equipment_slot":
                seq.clickEquipmentSlot((String) params.get("slot"));
                return true;
            case "right_click_equipment_slot_select":
                seq.rightClickEquipmentSlotAndSelect(
                    (String) params.get("slot"),
                    (String) params.get("option")
                );
                return true;
            case "click_equipment_item":
                seq.clickEquipmentItem((String) params.get("itemName"));
                return true;
            case "right_click_equipment_item_select":
                seq.rightClickEquipmentItemAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("option")
                );
                return true;
            case "select_dialog_option":
                seq.selectDialogOption((String) params.get("option"));
                return true;
            case "wait_and_select_dialog_option":
                seq.waitAndSelectDialogOption(
                    (String) params.get("option"),
                    getIntParam(params, "timeoutMs", 5000)
                );
                return true;
            case "right_click_equipment_item_hover_select":
                seq.rightClickEquipmentItemHoverAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("parentOption"),
                    (String) params.get("subOption")
                );
                return true;
            case "right_click_inventory_item_hover_select":
                seq.rightClickInventoryItemHoverAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("parentOption"),
                    (String) params.get("subOption")
                );
                return true;
            case "walk_to":
                seq.walkTo(
                    ((Number) params.get("x")).intValue(),
                    ((Number) params.get("y")).intValue(),
                    getIntParam(params, "plane", 0)
                );
                return true;
            // Bank steps
            case "click_bank_item":
                seq.clickBankItem((String) params.get("itemName"));
                return true;
            case "right_click_bank_item_select":
                seq.rightClickBankItemAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("option")
                );
                return true;
            case "click_bank_inventory_item":
                seq.clickBankInventoryItem((String) params.get("itemName"));
                return true;
            case "right_click_bank_inventory_item_select":
                seq.rightClickBankInventoryItemAndSelect(
                    (String) params.get("itemName"),
                    (String) params.get("option")
                );
                return true;
            case "deposit_inventory":
                seq.depositInventory();
                return true;
            case "deposit_equipment":
                seq.depositEquipment();
                return true;
            case "click_bank_tab":
                seq.clickBankTab(((Number) params.get("tab")).intValue());
                return true;
            case "set_bank_quantity":
                seq.setBankQuantity(((Number) params.get("quantity")).intValue());
                return true;
            case "toggle_bank_note_mode":
                seq.toggleBankNoteMode();
                return true;
            case "bank_search":
                seq.bankSearch((String) params.get("query"));
                return true;
            case "withdraw_x":
                seq.withdrawX(
                    (String) params.get("itemName"),
                    ((Number) params.get("amount")).intValue()
                );
                return true;
            case "deposit_x":
                seq.depositX(
                    (String) params.get("itemName"),
                    ((Number) params.get("amount")).intValue()
                );
                return true;
            case "close_bank":
                seq.closeBank();
                return true;
            case "delay":
                if (params.containsKey("maxMs")) {
                    seq.delay(
                        ((Number) params.get("minMs")).intValue(),
                        ((Number) params.get("maxMs")).intValue()
                    );
                } else {
                    seq.delay(((Number) params.get("ms")).intValue());
                }
                return true;
            // Conditional wait steps
            case "wait_until_idle":
                seq.waitUntilIdle(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_not_animating":
                seq.waitUntilNotAnimating(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_moving":
                seq.waitUntilMoving(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_stopped":
                seq.waitUntilStopped(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_inventory_full":
                seq.waitUntilInventoryFull(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_inventory_not_full":
                seq.waitUntilInventoryNotFull(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_inventory_contains":
                seq.waitUntilInventoryContains(
                    (String) params.get("itemName"),
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_inventory_empty":
                seq.waitUntilInventoryEmpty(
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_health_above":
                seq.waitUntilHealthAbove(
                    ((Number) params.get("threshold")).intValue(),
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            case "wait_until_health_below":
                seq.waitUntilHealthBelow(
                    ((Number) params.get("threshold")).intValue(),
                    getIntParam(params, "timeoutMs", 10000),
                    getIntParam(params, "pollMs", 200)
                );
                return true;
            default:
                return false;
        }
    }

    private void handleTaskStatus(Context ctx) {
        if (activeSequence == null) {
            ctx.json(Map.of(
                "running", false,
                "message", "No task sequence has been started"
            ));
            return;
        }

        Map<String, Object> status = new HashMap<>();
        status.put("running", activeSequence.isRunning());
        status.put("currentStep", activeSequence.getCurrentStepIndex());
        status.put("totalSteps", activeSequence.getStepCount());
        status.put("error", activeSequence.getLastError());

        ctx.json(status);
    }

    private void handleTaskCancel(Context ctx) {
        if (activeSequence == null || !activeSequence.isRunning()) {
            ctx.json(Map.of("success", false, "message", "No task sequence is currently running"));
            return;
        }

        activeSequence.cancel();
        ctx.json(Map.of("success", true, "message", "Task sequence cancellation requested"));
    }

    // ===== Cursor Overlay Handlers =====

    private void handleCursorToggle(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        boolean current = interactionPlugin.isVirtualCursorEnabled();
        interactionPlugin.setVirtualCursorEnabled(!current);
        ctx.json(Map.of("enabled", !current));
    }

    private void handleCursorStatus(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        ctx.json(Map.of("enabled", interactionPlugin.isVirtualCursorEnabled()));
    }

    // ===== Bank Handlers =====

    private void handleGetBankItems(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        if (!interactionPlugin.isBankOpen()) {
            ctx.status(400).json(createError("Bank is not open"));
            return;
        }

        var items = interactionPlugin.getBankItems();
        ctx.json(Map.of(
            "bankOpen", true,
            "count", items.size(),
            "items", items
        ));
    }

    private void handleBankStatus(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        ctx.json(Map.of("bankOpen", interactionPlugin.isBankOpen()));
    }

    private void handleBankDebug(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        ctx.json(interactionPlugin.getBankDebugInfo());
    }

    private void handleBankClose(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new HashMap<>();
        try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

        String profileName = (String) body.getOrDefault("profile", "NORMAL");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

        boolean success = interactionPlugin.closeBank(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleBankDepositInventory(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new HashMap<>();
        try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

        String profileName = (String) body.getOrDefault("profile", "NORMAL");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

        boolean success = interactionPlugin.depositInventory(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleBankDepositEquipment(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new HashMap<>();
        try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

        String profileName = (String) body.getOrDefault("profile", "NORMAL");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

        boolean success = interactionPlugin.depositEquipment(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleBankTab(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int tab = ((Number) body.get("tab")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            if (tab < 0 || tab > 9) {
                ctx.status(400).json(createError("Tab must be 0-9 (0=all, 1-9=tabs)"));
                return;
            }

            boolean success = interactionPlugin.clickBankTab(tab, profile);
            ctx.json(Map.of("success", success, "tab", tab));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleBankQuantity(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            int quantity = ((Number) body.get("quantity")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success = interactionPlugin.setBankQuantity(quantity, profile);
            ctx.json(Map.of("success", success, "quantity", quantity));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleBankNoteMode(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new HashMap<>();
        try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

        String profileName = (String) body.getOrDefault("profile", "NORMAL");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

        boolean success = interactionPlugin.toggleBankNoteMode(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleBankSearch(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String query = (String) body.get("query");
            if (query == null || query.trim().isEmpty()) {
                ctx.status(400).json(createError("'query' is required"));
                return;
            }

            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success = interactionPlugin.bankSearch(query, profile);
            ctx.json(Map.of("success", success, "query", query));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleBankWithdraw(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }

            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            // Check for explicit option override (e.g., "Withdraw-All", "Withdraw-5")
            String option = (String) body.get("option");
            if (option != null && !option.trim().isEmpty()) {
                boolean success = interactionPlugin.rightClickBankItemAndSelect(itemName, option, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "option", option));
                return;
            }

            // Check for quantity-based withdrawal
            int quantity = getIntParam(body, "quantity", 0);
            if (quantity > 0) {
                boolean success = interactionPlugin.withdrawX(itemName, quantity, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "quantity", quantity));
            } else {
                // Default: left-click (withdraw current default quantity)
                boolean success = interactionPlugin.clickBankItem(itemName, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "action", "default click"));
            }
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleBankDeposit(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String itemName = (String) body.get("itemName");
            if (itemName == null || itemName.trim().isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }

            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            // Check for explicit option override
            String option = (String) body.get("option");
            if (option != null && !option.trim().isEmpty()) {
                boolean success = interactionPlugin.rightClickBankInventoryItemAndSelect(itemName, option, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "option", option));
                return;
            }

            // Check for quantity-based deposit
            int quantity = getIntParam(body, "quantity", 0);
            if (quantity > 0) {
                boolean success = interactionPlugin.depositX(itemName, quantity, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "quantity", quantity));
            } else {
                // Default: left-click (deposit current default quantity)
                boolean success = interactionPlugin.clickBankInventoryItem(itemName, profile);
                ctx.json(Map.of("success", success, "itemName", itemName, "action", "default click"));
            }
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Skills Handlers =====

    private void handleGetSkills(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        List<SkillState> skills = gameStatePlugin.getSkills();
        if (skills == null) {
            ctx.status(404).json(createError("Skills data not available"));
            return;
        }

        ctx.json(Map.of(
            "count", skills.size(),
            "skills", skills
        ));
    }

    private void handleGetSkillByName(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        String name = ctx.pathParam("name");
        SkillState skill = gameStatePlugin.getSkill(name);
        if (skill == null) {
            ctx.status(404).json(createError("Skill not found: " + name));
            return;
        }

        ctx.json(skill);
    }

    // ===== Equipment Handlers =====

    private void handleGetEquipment(Context ctx) {
        if (gameStatePlugin == null) {
            ctx.status(503).json(createError("GameState plugin not loaded"));
            return;
        }

        List<EquipmentItem> equipment = gameStatePlugin.getEquipment();
        if (equipment == null) {
            ctx.status(404).json(createError("Equipment data not available"));
            return;
        }

        ctx.json(Map.of(
            "count", equipment.size(),
            "items", equipment
        ));
    }

    // ===== NPC Interaction Handler =====

    private void handleNPCInteract(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String npcName = (String) body.get("npcName");
            String action = (String) body.get("action");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");

            if (npcName == null || npcName.trim().isEmpty()) {
                ctx.status(400).json(createError("'npcName' is required"));
                return;
            }

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success;

            if (action != null && !action.trim().isEmpty()) {
                success = interactionPlugin.interactWithNPC(npcName, action, profile);
            } else {
                success = interactionPlugin.interactWithNPC(npcName, profile);
            }

            ctx.json(Map.of("success", success, "npcName", npcName,
                "action", action != null ? action : "default"));
        } catch (Exception e) {
            log.error("Error interacting with NPC", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Chat Handler =====

    private void handleGetRecentChat(Context ctx) {
        if (eventMonitorPlugin == null) {
            ctx.status(503).json(createError("EventMonitor plugin not loaded"));
            return;
        }

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        String type = ctx.queryParam("type");

        if (limit < 1 || limit > 500) {
            ctx.status(400).json(createError("Limit must be between 1 and 500"));
            return;
        }

        List<ChatEntry> messages = eventMonitorPlugin.getRecentChat(limit, type);
        ctx.json(Map.of(
            "count", messages.size(),
            "limit", limit,
            "type", type != null ? type : "all",
            "messages", messages
        ));
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
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
