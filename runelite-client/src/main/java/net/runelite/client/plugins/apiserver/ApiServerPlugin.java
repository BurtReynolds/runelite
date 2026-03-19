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
        app.get("/api/v1/objects/at", this::handleGetObjectAtLocation);
        app.get("/api/v1/objects/id/{id}", this::handleGetObjectsById);
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

        // Skill guide endpoints
        app.get("/api/v1/interaction/skill-guide/status", this::handleSkillGuideStatus);
        app.post("/api/v1/interaction/skill-guide/close", this::handleSkillGuideClose);

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

        // Camera control endpoints
        app.get("/api/v1/camera", this::handleGetCamera);
        app.post("/api/v1/camera/yaw", this::handleSetCameraYaw);
        app.post("/api/v1/camera/pitch", this::handleSetCameraPitch);
        app.post("/api/v1/camera/direction", this::handleSetCameraDirection);
        app.post("/api/v1/camera/look-at", this::handleCameraLookAt);
        app.get("/api/v1/camera/zoom", this::handleGetCameraZoom);
        app.post("/api/v1/camera/zoom", this::handleSetCameraZoom);

        // Run energy endpoints
        app.get("/api/v1/player/run", this::handleGetRunState);
        app.post("/api/v1/player/run/toggle", this::handleToggleRun);

        // Ground items endpoints
        app.get("/api/v1/ground-items/nearby", this::handleGetGroundItemsNearby);
        app.post("/api/v1/interaction/ground-item/click", this::handleClickGroundItem);
        app.post("/api/v1/interaction/ground-item/take", this::handleTakeGroundItem);

        // Logout / World hop / Login endpoints
        app.get("/api/v1/world/current", this::handleGetCurrentWorld);
        app.get("/api/v1/world/list", this::handleGetWorldList);
        app.get("/api/v1/player/login-state", this::handleGetLoginState);
        app.post("/api/v1/interaction/logout", this::handleLogout);
        app.post("/api/v1/interaction/hop-world", this::handleHopWorld);
        app.post("/api/v1/interaction/login", this::handleLogin);

        // Bank pin endpoints
        app.get("/api/v1/bank-pin/status", this::handleGetBankPinStatus);
        app.post("/api/v1/bank-pin/enter", this::handleEnterBankPin);

        // Chat endpoints
        app.get("/api/v1/chat/recent", this::handleGetRecentChat);

        // Prayer endpoints
        app.get("/api/v1/prayer", this::handleGetPrayerState);
        app.post("/api/v1/prayer/toggle", this::handleTogglePrayerByName);
        app.get("/api/v1/prayer/active", this::handleIsPrayerActive);
        app.post("/api/v1/prayer/quick-toggle", this::handleToggleQuickPrayer);

        // Spellbook endpoints
        app.get("/api/v1/spellbook", this::handleGetSpellbookState);
        app.post("/api/v1/spellbook/cast", this::handleCastSpell);
        app.post("/api/v1/spellbook/cast-on-item", this::handleCastSpellOnItem);

        // Combat endpoints
        app.get("/api/v1/combat", this::handleGetCombatState);
        app.post("/api/v1/combat/style", this::handleSetCombatStyle);
        app.post("/api/v1/combat/retaliate", this::handleToggleAutoRetaliate);
        app.get("/api/v1/combat/special", this::handleGetSpecialAttack);
        app.post("/api/v1/combat/special", this::handleActivateSpecialAttack);

        // Player interaction endpoints
        app.post("/api/v1/interaction/player/lookup", this::handlePlayerLookup);

        // Anti-ban endpoints
        app.post("/api/v1/antiban/start", this::handleAntiBanStart);
        app.post("/api/v1/antiban/stop", this::handleAntiBanStop);
        app.get("/api/v1/antiban/status", this::handleAntiBanStatus);
        app.post("/api/v1/antiban/trigger", this::handleAntiBanTrigger);

        // Idle management endpoints
        app.get("/api/v1/player/idle", this::handleGetIdleState);
        app.post("/api/v1/player/idle/reset", this::handleResetIdleTicks);

        // Make / crafting menu endpoints
        app.get("/api/v1/make/status", this::handleGetMakeStatus);
        app.post("/api/v1/make/select", this::handleMakeSelect);
        app.post("/api/v1/make/quantity", this::handleSetMakeQuantity);

        // Shop endpoints
        app.get("/api/v1/shop/items", this::handleGetShopItems);
        app.get("/api/v1/shop/status", this::handleGetShopStatus);
        app.post("/api/v1/shop/buy", this::handleShopBuy);
        app.post("/api/v1/shop/sell", this::handleShopSell);
        app.post("/api/v1/shop/quantity", this::handleSetShopQuantity);
        app.post("/api/v1/shop/close", this::handleCloseShop);

        // Use item on X endpoints
        app.post("/api/v1/interaction/use-item-on-item", this::handleUseItemOnItem);
        app.post("/api/v1/interaction/use-item-on-object", this::handleUseItemOnObject);
        app.post("/api/v1/interaction/use-item-on-npc", this::handleUseItemOnNPC);

        // Deposit box endpoints
        app.get("/api/v1/deposit-box/items", this::handleGetDepositBoxItems);
        app.get("/api/v1/deposit-box/status", this::handleGetDepositBoxStatus);
        app.post("/api/v1/deposit-box/deposit", this::handleDepositBoxDeposit);
        app.post("/api/v1/deposit-box/deposit-inventory", this::handleDepositBoxDepositInventory);
        app.post("/api/v1/deposit-box/deposit-equipment", this::handleDepositBoxDepositEquipment);
        app.post("/api/v1/deposit-box/deposit-loot", this::handleDepositBoxDepositLoot);
        app.post("/api/v1/deposit-box/quantity", this::handleSetDepositBoxQuantity);
        app.post("/api/v1/deposit-box/close", this::handleCloseDepositBox);

        // Minimap click endpoints
        app.post("/api/v1/interaction/minimap/click", this::handleMinimapClick);

        // Grand Exchange endpoints
        app.get("/api/v1/ge/status", this::handleGetGEStatus);
        app.get("/api/v1/ge/offers", this::handleGetGEOffers);
        app.post("/api/v1/ge/buy", this::handleGEBuy);
        app.post("/api/v1/ge/sell", this::handleGESell);
        app.post("/api/v1/ge/collect", this::handleGECollect);
        app.post("/api/v1/ge/abort", this::handleGEAbort);
        app.post("/api/v1/ge/close", this::handleGEClose);
        app.post("/api/v1/ge/back", this::handleGEBack);
        app.post("/api/v1/ge/confirm", this::handleGEConfirm);
        app.post("/api/v1/ge/price", this::handleGESetPrice);
        app.post("/api/v1/ge/quantity", this::handleGESetQuantity);
        app.post("/api/v1/ge/search", this::handleGESearch);

        // Enhanced combat state endpoints
        app.get("/api/v1/combat/state", this::handleGetCombatSnapshot);
        app.get("/api/v1/combat/log", this::handleGetCombatLog);

        // Break handler endpoints
        app.post("/api/v1/break/start", this::handleBreakStart);
        app.post("/api/v1/break/stop", this::handleBreakStop);
        app.get("/api/v1/break/status", this::handleBreakStatus);
        app.post("/api/v1/break/trigger", this::handleBreakTrigger);
        app.post("/api/v1/break/skip", this::handleBreakSkip);

        // Spirit tree & fairy ring endpoints
        app.post("/api/v1/interaction/spirit-tree", this::handleSpiritTreeTravel);
        app.post("/api/v1/interaction/fairy-ring", this::handleFairyRingTravel);
        app.get("/api/v1/fairy-ring/state", this::handleGetFairyRingState);
        app.get("/api/v1/debug/widget-scan", this::handleDebugWidgetScan);

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

        // Camera endpoints
        endpoints.put("camera", "GET /api/v1/camera");
        endpoints.put("cameraYaw", "POST /api/v1/camera/yaw {yaw}");
        endpoints.put("cameraPitch", "POST /api/v1/camera/pitch {pitch}");
        endpoints.put("cameraDirection", "POST /api/v1/camera/direction {direction}");
        endpoints.put("cameraLookAt", "POST /api/v1/camera/look-at {x, y, plane?}");
        endpoints.put("cameraZoomGet", "GET /api/v1/camera/zoom");
        endpoints.put("cameraZoomSet", "POST /api/v1/camera/zoom {zoom, speed?}");

        // Run energy endpoints
        endpoints.put("runState", "GET /api/v1/player/run");
        endpoints.put("toggleRun", "POST /api/v1/player/run/toggle");

        // Ground items endpoints
        endpoints.put("groundItemsNearby", "GET /api/v1/ground-items/nearby?radius=10");
        endpoints.put("clickGroundItem", "POST /api/v1/interaction/ground-item/click {itemName, profile?}");
        endpoints.put("takeGroundItem", "POST /api/v1/interaction/ground-item/take {itemName, profile?}");

        // Logout / World hop endpoints
        endpoints.put("currentWorld", "GET /api/v1/world/current");
        endpoints.put("worldList", "GET /api/v1/world/list");
        endpoints.put("loginState", "GET /api/v1/player/login-state");
        endpoints.put("logout", "POST /api/v1/interaction/logout");
        endpoints.put("hopWorld", "POST /api/v1/interaction/hop-world {world}");
        endpoints.put("login", "POST /api/v1/interaction/login {username, password}");
        endpoints.put("bankPinStatus", "GET /api/v1/bank-pin/status");
        endpoints.put("bankPinEnter", "POST /api/v1/bank-pin/enter {pin}");

        // Chat endpoints
        endpoints.put("chatRecent", "GET /api/v1/chat/recent?limit=50&type=GAMEMESSAGE");

        // Prayer endpoints
        endpoints.put("prayerState", "GET /api/v1/prayer");
        endpoints.put("prayerToggle", "POST /api/v1/prayer/toggle {prayer, profile?}");
        endpoints.put("prayerActive", "GET /api/v1/prayer/active?prayer=PROTECT_FROM_MELEE");
        endpoints.put("quickPrayerToggle", "POST /api/v1/prayer/quick-toggle");

        // Spellbook endpoints
        endpoints.put("spellbookState", "GET /api/v1/spellbook");
        endpoints.put("castSpell", "POST /api/v1/spellbook/cast {spell, profile?}");
        endpoints.put("castSpellOnItem", "POST /api/v1/spellbook/cast-on-item {spell, item, profile?}");

        // Combat endpoints
        endpoints.put("combatState", "GET /api/v1/combat");
        endpoints.put("combatStyle", "POST /api/v1/combat/style {style}");
        endpoints.put("autoRetaliate", "POST /api/v1/combat/retaliate");
        endpoints.put("specialAttackGet", "GET /api/v1/combat/special");
        endpoints.put("specialAttackActivate", "POST /api/v1/combat/special");

        // Player interaction
        endpoints.put("playerLookup", "POST /api/v1/interaction/player/lookup {playerName}");

        // Anti-ban endpoints
        endpoints.put("antiBanStart", "POST /api/v1/antiban/start {minIntervalMs?, maxIntervalMs?, weights?}");
        endpoints.put("antiBanStop", "POST /api/v1/antiban/stop");
        endpoints.put("antiBanStatus", "GET /api/v1/antiban/status");
        endpoints.put("antiBanTrigger", "POST /api/v1/antiban/trigger {action}");

        // Idle management
        endpoints.put("idleState", "GET /api/v1/player/idle");
        endpoints.put("idleReset", "POST /api/v1/player/idle/reset");

        // Grand Exchange endpoints
        endpoints.put("geStatus", "GET /api/v1/ge/status");
        endpoints.put("geOffers", "GET /api/v1/ge/offers");
        endpoints.put("geBuy", "POST /api/v1/ge/buy {slot, item, quantity, price, profile?}");
        endpoints.put("geSell", "POST /api/v1/ge/sell {slot, item, quantity, price, profile?}");
        endpoints.put("geCollect", "POST /api/v1/ge/collect {profile?}");
        endpoints.put("geAbort", "POST /api/v1/ge/abort {slot, profile?}");
        endpoints.put("geClose", "POST /api/v1/ge/close {profile?}");
        endpoints.put("geBack", "POST /api/v1/ge/back {profile?}");
        endpoints.put("geConfirm", "POST /api/v1/ge/confirm {profile?}");
        endpoints.put("geSetPrice", "POST /api/v1/ge/price {price, profile?}");
        endpoints.put("geSetQuantity", "POST /api/v1/ge/quantity {quantity, profile?}");
        endpoints.put("geSearch", "POST /api/v1/ge/search {item}");

        // Enhanced combat state
        endpoints.put("combatSnapshot", "GET /api/v1/combat/state");
        endpoints.put("combatLog", "GET /api/v1/combat/log?limit=50");

        // Break handler
        endpoints.put("breakStart", "POST /api/v1/break/start {minBreakMs, maxBreakMs, minPlayMs, maxPlayMs, logoutDuringBreak?}");
        endpoints.put("breakStop", "POST /api/v1/break/stop");
        endpoints.put("breakStatus", "GET /api/v1/break/status");
        endpoints.put("breakTrigger", "POST /api/v1/break/trigger");
        endpoints.put("breakSkip", "POST /api/v1/break/skip");
        endpoints.put("spiritTreeTravel", "POST /api/v1/interaction/spirit-tree {destination, profile?}");
        endpoints.put("fairyRingTravel", "POST /api/v1/interaction/fairy-ring {code, profile?}");
        endpoints.put("fairyRingState", "GET /api/v1/fairy-ring/state");

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

    private void handleGetObjectAtLocation(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        Integer x = ctx.queryParamAsClass("x", Integer.class).getOrDefault(null);
        Integer y = ctx.queryParamAsClass("y", Integer.class).getOrDefault(null);
        Integer plane = ctx.queryParamAsClass("plane", Integer.class).getOrDefault(0);

        if (x == null || y == null) {
            ctx.status(400).json(createError("'x' and 'y' query parameters are required"));
            return;
        }

        GameObjectInfo object = objectDetectionPlugin.getObjectAtLocation(x, y, plane);
        if (object == null) {
            ctx.status(404).json(createError("No object found at (" + x + ", " + y + ", " + plane + ")"));
            return;
        }

        ctx.json(object);
    }

    private void handleGetObjectsById(Context ctx) {
        if (objectDetectionPlugin == null) {
            ctx.status(503).json(createError("ObjectDetection plugin not loaded"));
            return;
        }

        int id = ctx.pathParamAsClass("id", Integer.class).get();
        List<GameObjectInfo> objects = objectDetectionPlugin.getObjectsById(id);
        ctx.json(Map.of(
            "id", id,
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

            // Check for location-based interaction (x, y, plane)
            Object xObj = body.get("x");
            Object yObj = body.get("y");

            if (xObj != null && yObj != null) {
                int x = ((Number) xObj).intValue();
                int y = ((Number) yObj).intValue();
                int plane = body.containsKey("plane") ? ((Number) body.get("plane")).intValue() : 0;

                boolean success;
                if (action != null && !action.trim().isEmpty()) {
                    success = interactionPlugin.interactWithObjectAtLocation(x, y, plane, action, profile);
                } else {
                    success = interactionPlugin.interactWithObjectAtLocation(x, y, plane, profile);
                }

                ctx.json(Map.of("success", success,
                    "x", x, "y", y, "plane", plane,
                    "action", action != null ? action : "default"));
                return;
            }

            // Name-based interaction (existing behavior)
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

    private void handleSkillGuideStatus(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        boolean open = interactionPlugin.isSkillGuideOpen();
        ctx.json(Map.of("open", open));
    }

    private void handleSkillGuideClose(Context ctx) {
        if (interactionPlugin == null) {
            ctx.status(503).json(createError("Interaction plugin not loaded"));
            return;
        }

        String profileStr = "NORMAL";
        try {
            var body = ctx.bodyAsClass(java.util.Map.class);
            if (body != null && body.containsKey("profile")) {
                profileStr = (String) body.get("profile");
            }
        } catch (Exception ignored) {}

        MouseMovementProfile profile = MouseMovementProfile.fromString(profileStr);
        boolean closed = interactionPlugin.closeSkillGuide(profile);
        ctx.json(Map.of("success", closed));
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

            // Cancel any existing walk before starting a new one
            if (activeWalkFuture != null && !activeWalkFuture.isDone()) {
                interactionPlugin.cancelWebWalk();
                try { activeWalkFuture.get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }

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
            case "set_camera_yaw":
                seq.setCameraYaw(((Number) params.get("yaw")).intValue());
                return true;
            case "set_camera_pitch":
                seq.setCameraPitch(((Number) params.get("pitch")).intValue());
                return true;
            case "set_camera_direction":
                seq.setCameraDirection((String) params.get("direction"));
                return true;
            case "look_at_tile":
                seq.lookAtTile(
                    ((Number) params.get("x")).intValue(),
                    ((Number) params.get("y")).intValue(),
                    params.containsKey("plane") ? ((Number) params.get("plane")).intValue() : 0
                );
                return true;
            case "set_camera_zoom":
                double zoomSpeed = params.containsKey("speed") ? ((Number) params.get("speed")).doubleValue() : 1.0;
                seq.setCameraZoom(((Number) params.get("zoom")).intValue(), zoomSpeed);
                return true;
            case "toggle_run":
                seq.toggleRun();
                return true;
            case "click_ground_item":
                seq.clickGroundItem((String) params.get("itemName"));
                return true;
            case "take_ground_item":
                seq.takeGroundItem((String) params.get("itemName"));
                return true;
            case "logout":
                seq.logout();
                return true;
            case "hop_world":
                seq.hopWorld(((Number) params.get("world")).intValue());
                return true;
            case "toggle_prayer":
                seq.togglePrayer((String) params.get("prayer"));
                return true;
            case "toggle_quick_prayer":
                seq.toggleQuickPrayer();
                return true;
            case "cast_spell":
                seq.castSpell((String) params.get("spell"));
                return true;
            case "cast_spell_on_item":
                seq.castSpellOnItem((String) params.get("spell"), (String) params.get("item"));
                return true;
            case "set_combat_style":
                seq.setCombatStyle(((Number) params.get("style")).intValue());
                return true;
            case "toggle_auto_retaliate":
                seq.toggleAutoRetaliate();
                return true;
            case "activate_special_attack":
                seq.activateSpecialAttack();
                return true;
            case "antiban_start":
                int abMin = params.containsKey("minIntervalMs") ? ((Number) params.get("minIntervalMs")).intValue() : 15000;
                int abMax = params.containsKey("maxIntervalMs") ? ((Number) params.get("maxIntervalMs")).intValue() : 90000;
                seq.antiBanStart(abMin, abMax);
                return true;
            case "antiban_stop":
                seq.antiBanStop();
                return true;
            case "random_sleep":
                int sleepMin = ((Number) params.get("min")).intValue();
                int sleepMax = ((Number) params.get("max")).intValue();
                seq.randomSleep(sleepMin, sleepMax);
                return true;
            case "reset_idle":
                seq.resetIdle();
                return true;
            // Make menu steps
            case "select_make_option":
                String makeOpt = (String) params.get("option");
                int makeIdx = params.containsKey("index") ? ((Number) params.get("index")).intValue() : -1;
                seq.selectMakeOption(makeOpt, makeIdx);
                return true;
            case "set_make_quantity":
                seq.setMakeQuantity(((Number) params.get("quantity")).intValue());
                return true;
            // Shop steps
            case "click_shop_item":
                seq.clickShopItem((String) params.get("itemName"));
                return true;
            case "right_click_shop_item_select":
                seq.rightClickShopItemAndSelect((String) params.get("itemName"), (String) params.get("option"));
                return true;
            case "click_shop_inventory_item":
                seq.clickShopInventoryItem((String) params.get("itemName"));
                return true;
            case "right_click_shop_inventory_item_select":
                seq.rightClickShopInventoryItemAndSelect((String) params.get("itemName"), (String) params.get("option"));
                return true;
            case "set_shop_quantity":
                seq.setShopQuantity(((Number) params.get("quantity")).intValue());
                return true;
            case "close_shop":
                seq.closeShop();
                return true;
            // Use item steps
            case "use_item_on_item":
                seq.useItemOnItem((String) params.get("sourceItem"), (String) params.get("targetItem"));
                return true;
            case "use_item_on_object":
                seq.useItemOnObject((String) params.get("itemName"), (String) params.get("objectName"));
                return true;
            case "use_item_on_npc":
                seq.useItemOnNPC((String) params.get("itemName"), (String) params.get("npcName"));
                return true;
            // Deposit box steps
            case "click_deposit_box_item":
                seq.clickDepositBoxItem((String) params.get("itemName"));
                return true;
            case "right_click_deposit_box_item_select":
                seq.rightClickDepositBoxItemAndSelect((String) params.get("itemName"), (String) params.get("option"));
                return true;
            case "deposit_box_deposit_inventory":
                seq.depositBoxDepositInventory();
                return true;
            case "deposit_box_deposit_equipment":
                seq.depositBoxDepositEquipment();
                return true;
            case "deposit_box_deposit_loot":
                seq.depositBoxDepositLoot();
                return true;
            case "set_deposit_box_quantity":
                seq.setDepositBoxQuantity(((Number) params.get("quantity")).intValue());
                return true;
            case "close_deposit_box":
                seq.closeDepositBox();
                return true;
            // Minimap click step
            case "click_minimap":
                if (params.containsKey("dx") || params.containsKey("dy")) {
                    int mmDx = params.containsKey("dx") ? ((Number) params.get("dx")).intValue() : 0;
                    int mmDy = params.containsKey("dy") ? ((Number) params.get("dy")).intValue() : 0;
                    seq.clickMinimapRelative(mmDx, mmDy);
                } else {
                    int mmX = ((Number) params.get("x")).intValue();
                    int mmY = ((Number) params.get("y")).intValue();
                    int mmPlane = params.containsKey("plane") ? ((Number) params.get("plane")).intValue() : 0;
                    seq.clickMinimap(mmX, mmY, mmPlane);
                }
                return true;

            // Grand Exchange steps
            case "ge_click_slot":
                seq.geClickSlot(((Number) params.get("slot")).intValue());
                return true;
            case "ge_buy":
                seq.geBuy(((Number) params.get("slot")).intValue());
                return true;
            case "ge_sell":
                seq.geSell(((Number) params.get("slot")).intValue());
                return true;
            case "ge_search":
                seq.geSearch((String) params.get("item"));
                return true;
            case "ge_set_price":
                seq.geSetPrice(((Number) params.get("price")).intValue());
                return true;
            case "ge_set_quantity":
                seq.geSetQuantity(((Number) params.get("quantity")).intValue());
                return true;
            case "ge_confirm":
                seq.geConfirm();
                return true;
            case "ge_collect":
                seq.geCollect();
                return true;
            case "ge_abort":
                seq.geAbort(((Number) params.get("slot")).intValue());
                return true;
            case "ge_close":
                seq.geClose();
                return true;
            case "ge_back":
                seq.geBack();
                return true;

            // Break check step
            case "break_check":
                seq.breakCheck();
                return true;

            // Enhanced combat waits
            case "wait_until_prayer_above":
                seq.waitUntilPrayerAbove(
                    ((Number) params.get("threshold")).intValue(),
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 10000
                );
                return true;
            case "wait_until_prayer_below":
                seq.waitUntilPrayerBelow(
                    ((Number) params.get("threshold")).intValue(),
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 10000
                );
                return true;
            case "wait_until_special_above":
                seq.waitUntilSpecialAbove(
                    ((Number) params.get("threshold")).intValue(),
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 60000
                );
                return true;
            case "wait_until_not_in_combat":
                seq.waitUntilNotInCombat(
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 30000
                );
                return true;
            case "wait_until_target_dead":
                seq.waitUntilTargetDead(
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 30000
                );
                return true;

            // Login / Bank pin steps
            case "login":
                seq.login((String) params.get("username"), (String) params.get("password"));
                return true;
            case "enter_bank_pin":
                seq.enterBankPin((String) params.get("pin"));
                return true;
            case "wait_until_logged_in":
                seq.waitUntilLoggedIn(
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 30000
                );
                return true;
            case "wait_until_bank_pin_open":
                seq.waitUntilBankPinOpen(
                    params.containsKey("timeout") ? ((Number) params.get("timeout")).longValue() : 15000
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

    // ===== CAMERA HANDLERS =====

    private void handleGetCamera(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getCameraState());
    }

    private void handleSetCameraYaw(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int yaw = ((Number) body.get("yaw")).intValue();
            interactionPlugin.setCameraYaw(yaw);
            ctx.json(Map.of("success", true, "yaw", yaw));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleSetCameraPitch(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int pitch = ((Number) body.get("pitch")).intValue();
            interactionPlugin.setCameraPitch(pitch);
            ctx.json(Map.of("success", true, "pitch", pitch));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleSetCameraDirection(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String direction = (String) body.get("direction");
            if (direction == null || direction.isEmpty()) {
                ctx.status(400).json(createError("'direction' is required (north/south/east/west or degrees 0-359)"));
                return;
            }
            interactionPlugin.setCameraDirection(direction);
            ctx.json(Map.of("success", true, "direction", direction));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleCameraLookAt(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int x = ((Number) body.get("x")).intValue();
            int y = ((Number) body.get("y")).intValue();
            int plane = body.containsKey("plane") ? ((Number) body.get("plane")).intValue() : 0;
            WorldPoint target = new WorldPoint(x, y, plane);
            interactionPlugin.lookAtTile(target);
            ctx.json(Map.of("success", true, "target", Map.of("x", x, "y", y, "plane", plane)));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleGetCameraZoom(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(Map.of("zoom", interactionPlugin.getCameraZoom()));
    }

    private void handleSetCameraZoom(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int zoom = ((Number) body.get("zoom")).intValue();
            double speed = body.containsKey("speed") ? ((Number) body.get("speed")).doubleValue() : 1.0;
            interactionPlugin.setCameraZoom(zoom, speed);
            ctx.json(Map.of("success", true, "zoom", zoom, "speed", speed));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== RUN ENERGY HANDLERS =====

    private void handleGetRunState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getRunState());
    }

    private void handleToggleRun(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            String profileName = "NORMAL";
            try {
                @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body.containsKey("profile")) profileName = (String) body.get("profile");
            } catch (Exception ignored) {}

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.toggleRun(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== GROUND ITEMS HANDLERS =====

    private void handleGetGroundItemsNearby(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        int radius = ctx.queryParamAsClass("radius", Integer.class).getOrDefault(10);
        ctx.json(interactionPlugin.getGroundItemsNearby(radius));
    }

    private void handleClickGroundItem(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            if (itemName == null || itemName.isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.clickGroundItem(itemName, profile);
            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleTakeGroundItem(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            if (itemName == null || itemName.isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickGroundItemAndSelect(itemName, "Take", profile);
            ctx.json(Map.of("success", success, "itemName", itemName, "action", "Take"));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== LOGOUT / WORLD HOP HANDLERS =====

    private void handleGetLoginState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(Map.of("state", interactionPlugin.getLoginState()));
    }

    private void handleGetCurrentWorld(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(Map.of("world", interactionPlugin.getCurrentWorld()));
    }

    private void handleGetWorldList(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getWorldList());
    }

    private void handleLogout(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            String profileName = "NORMAL";
            try {
                @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body.containsKey("profile")) profileName = (String) body.get("profile");
            } catch (Exception ignored) {}

            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.logout(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleHopWorld(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int world = ((Number) body.get("world")).intValue();
            MouseMovementProfile profile = MouseMovementProfile.fromString(
                (String) body.getOrDefault("profile", "NORMAL"));
            boolean success = interactionPlugin.hopWorld(world, profile);
            ctx.json(Map.of("success", success, "world", world));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== Spirit Tree & Fairy Ring Handlers =====

    private void handleSpiritTreeTravel(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String destination = (String) body.get("destination");
            if (destination == null || destination.isEmpty()) {
                ctx.status(400).json(createError("'destination' is required (e.g., 'Grand Exchange', 'Gnome Stronghold')"));
                return;
            }
            MouseMovementProfile profile = MouseMovementProfile.fromString(
                (String) body.getOrDefault("profile", "NORMAL"));
            log.info("Spirit tree travel request: destination='{}', profile='{}'", destination, profile);
            boolean success = interactionPlugin.travelSpiritTree(destination, profile);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("success", success);
            response.put("destination", destination);
            ctx.json(response);
        } catch (Exception e) {
            log.error("Spirit tree travel error", e);
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleFairyRingTravel(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String code = (String) body.get("code");
            if (code == null || code.length() != 3) {
                ctx.status(400).json(createError("'code' is required (3-letter fairy ring code, e.g., 'DKR', 'CKS')"));
                return;
            }
            MouseMovementProfile profile = MouseMovementProfile.fromString(
                (String) body.getOrDefault("profile", "NORMAL"));
            boolean success = interactionPlugin.travelFairyRing(code.toUpperCase(), profile);
            ctx.json(Map.of("success", success, "code", code.toUpperCase()));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleGetFairyRingState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getFairyRingState());
    }

    private void handleDebugWidgetScan(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            int minGroup = 0;
            int maxGroup = 900;
            String minStr = ctx.queryParam("min");
            String maxStr = ctx.queryParam("max");
            if (minStr != null) try { minGroup = Integer.parseInt(minStr); } catch (NumberFormatException ignored) {}
            if (maxStr != null) try { maxGroup = Integer.parseInt(maxStr); } catch (NumberFormatException ignored) {}
            ctx.json(interactionPlugin.debugWidgetGroupScan(minGroup, maxGroup));
        } catch (Exception e) {
            log.error("Debug widget scan error", e);
            ctx.status(500).json(createError("Scan error: " + e.getMessage()));
        }
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

    // ===== PRAYER HANDLERS =====

    private void handleGetPrayerState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getPrayerState());
    }

    private void handleTogglePrayerByName(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String prayer = (String) body.get("prayer");
            if (prayer == null || prayer.isEmpty()) {
                ctx.status(400).json(createError("'prayer' is required (e.g., PROTECT_FROM_MELEE, PIETY)"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.togglePrayer(prayer, profile);
            ctx.json(Map.of("success", success, "prayer", prayer));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleIsPrayerActive(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String prayer = ctx.queryParam("prayer");
        if (prayer == null || prayer.isEmpty()) {
            ctx.status(400).json(createError("'prayer' query param required"));
            return;
        }
        boolean active = interactionPlugin.isPrayerActive(prayer);
        ctx.json(Map.of("prayer", prayer, "active", active));
    }

    private void handleToggleQuickPrayer(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            MouseMovementProfile profile = MouseMovementProfile.NORMAL;
            boolean success = interactionPlugin.toggleQuickPrayer(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Error: " + e.getMessage()));
        }
    }

    // ===== SPELLBOOK HANDLERS =====

    private void handleGetSpellbookState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getSpellbookState());
    }

    private void handleCastSpell(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String spell = (String) body.get("spell");
            if (spell == null || spell.isEmpty()) {
                ctx.status(400).json(createError("'spell' is required (spell name, e.g., 'Varrock Teleport')"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.castSpellByName(spell, profile);
            ctx.json(Map.of("success", success, "spell", spell));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleCastSpellOnItem(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String spell = (String) body.get("spell");
            String item = (String) body.get("item");
            if (spell == null || item == null) {
                ctx.status(400).json(createError("'spell' and 'item' are required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.castSpellOnItem(spell, item, profile);
            ctx.json(Map.of("success", success, "spell", spell, "item", item));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== COMBAT HANDLERS =====

    private void handleGetCombatState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getCombatState());
    }

    private void handleSetCombatStyle(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int style = ((Number) body.get("style")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setCombatStyle(style, profile);
            ctx.json(Map.of("success", success, "style", style));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleToggleAutoRetaliate(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            MouseMovementProfile profile = MouseMovementProfile.NORMAL;
            boolean success = interactionPlugin.toggleAutoRetaliate(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Error: " + e.getMessage()));
        }
    }

    private void handleGetSpecialAttack(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getSpecialAttackState());
    }

    private void handleActivateSpecialAttack(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            MouseMovementProfile profile = MouseMovementProfile.NORMAL;
            boolean success = interactionPlugin.activateSpecialAttack(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Error: " + e.getMessage()));
        }
    }

    // ===== PLAYER INTERACTION HANDLERS =====

    private void handlePlayerLookup(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String playerName = (String) body.get("playerName");
            if (playerName == null || playerName.isEmpty()) {
                ctx.status(400).json(createError("'playerName' is required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.rightClickPlayerAndSelect(playerName, "Lookup", profile);
            ctx.json(Map.of("success", success, "playerName", playerName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== ANTI-BAN HANDLERS =====

    @SuppressWarnings("unchecked")
    private void handleAntiBanStart(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int minInterval = body.containsKey("minIntervalMs") ? ((Number) body.get("minIntervalMs")).intValue() : 15000;
            int maxInterval = body.containsKey("maxIntervalMs") ? ((Number) body.get("maxIntervalMs")).intValue() : 90000;
            boolean pauseDuringTasks = body.containsKey("pauseDuringTasks") ? (Boolean) body.get("pauseDuringTasks") : true;

            Map<String, Integer> weights = null;
            if (body.containsKey("weights")) {
                Map<String, Object> rawWeights = (Map<String, Object>) body.get("weights");
                weights = new java.util.HashMap<>();
                for (Map.Entry<String, Object> entry : rawWeights.entrySet()) {
                    weights.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }

            interactionPlugin.getAntiBanService().start(minInterval, maxInterval, pauseDuringTasks, weights);
            ctx.json(Map.of("success", true, "minIntervalMs", minInterval, "maxIntervalMs", maxInterval));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleAntiBanStop(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        interactionPlugin.getAntiBanService().stop();
        ctx.json(Map.of("success", true));
    }

    private void handleAntiBanStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getAntiBanService().getStatus());
    }

    private void handleAntiBanTrigger(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String action = (String) body.get("action");
            if (action == null || action.isEmpty()) {
                ctx.status(400).json(createError("'action' is required (mouse_fidget, camera_nudge, tab_check, skill_hover, hover_random, idle_pause, examine_object, player_lookup, mouse_off_client)"));
                return;
            }

            net.runelite.client.plugins.interaction.AntiBanService abs = interactionPlugin.getAntiBanService();
            switch (action) {
                case "mouse_fidget": abs.performMouseFidget(); break;
                case "camera_nudge": abs.performCameraNudge(); break;
                case "tab_check": abs.performTabCheck(); break;
                case "skill_hover": abs.performSkillHover(); break;
                case "hover_random": abs.performHoverRandom(); break;
                case "idle_pause": abs.performIdlePause(); break;
                case "examine_object": abs.performExamineObject(); break;
                case "player_lookup": abs.performPlayerLookup(); break;
                case "mouse_off_client": abs.performMouseOffClient(); break;
                default:
                    ctx.status(400).json(createError("Unknown action: " + action));
                    return;
            }
            ctx.json(Map.of("success", true, "action", action));
        } catch (Exception e) {
            ctx.status(400).json(createError("Error: " + e.getMessage()));
        }
    }

    // ===== IDLE MANAGEMENT HANDLERS =====

    private void handleGetIdleState(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getIdleState());
    }

    private void handleResetIdleTicks(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        interactionPlugin.resetIdleTicks();
        ctx.json(Map.of("success", true));
    }

    // ===== MAKE / CRAFTING MENU HANDLERS =====

    private void handleGetMakeStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getMakeMenuStatus());
    }

    private void handleMakeSelect(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String optionName = (String) body.get("option");
            int optionIndex = body.containsKey("index") ? ((Number) body.get("index")).intValue() : -1;
            if (optionName == null && optionIndex < 0) {
                ctx.status(400).json(createError("'option' (name) or 'index' (0-based) is required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.selectMakeOption(optionName, optionIndex, profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleSetMakeQuantity(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int quantity = ((Number) body.get("quantity")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setMakeQuantity(quantity, profile);
            ctx.json(Map.of("success", success, "quantity", quantity));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== SHOP HANDLERS =====

    private void handleGetShopStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(Map.of("open", interactionPlugin.isShopOpen()));
    }

    private void handleGetShopItems(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        var items = interactionPlugin.getShopItems();
        ctx.json(Map.of("count", items.size(), "items", items));
    }

    private void handleShopBuy(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            if (itemName == null || itemName.isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success;
            if (option != null && !option.isEmpty()) {
                success = interactionPlugin.rightClickShopItemAndSelect(itemName, option, profile);
            } else {
                success = interactionPlugin.clickShopItem(itemName, profile);
            }
            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleShopSell(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            if (itemName == null || itemName.isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success;
            if (option != null && !option.isEmpty()) {
                success = interactionPlugin.rightClickShopInventoryItemAndSelect(itemName, option, profile);
            } else {
                success = interactionPlugin.clickShopInventoryItem(itemName, profile);
            }
            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleSetShopQuantity(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int quantity = ((Number) body.get("quantity")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setShopQuantity(quantity, profile);
            ctx.json(Map.of("success", success, "quantity", quantity));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleCloseShop(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = ctx.queryParam("profile");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName != null ? profileName : "NORMAL");
        boolean success = interactionPlugin.closeShop(profile);
        ctx.json(Map.of("success", success));
    }

    // ===== USE ITEM ON X HANDLERS =====

    private void handleUseItemOnItem(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String sourceItem = (String) body.get("sourceItem");
            String targetItem = (String) body.get("targetItem");
            if (sourceItem == null || targetItem == null) {
                ctx.status(400).json(createError("'sourceItem' and 'targetItem' are required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.useItemOnItem(sourceItem, targetItem, profile);
            ctx.json(Map.of("success", success, "sourceItem", sourceItem, "targetItem", targetItem));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleUseItemOnObject(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            String objectName = (String) body.get("objectName");
            if (itemName == null || objectName == null) {
                ctx.status(400).json(createError("'itemName' and 'objectName' are required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.useItemOnObject(itemName, objectName, profile);
            ctx.json(Map.of("success", success, "itemName", itemName, "objectName", objectName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleUseItemOnNPC(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            String npcName = (String) body.get("npcName");
            if (itemName == null || npcName == null) {
                ctx.status(400).json(createError("'itemName' and 'npcName' are required"));
                return;
            }
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.useItemOnNPC(itemName, npcName, profile);
            ctx.json(Map.of("success", success, "itemName", itemName, "npcName", npcName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== DEPOSIT BOX HANDLERS =====

    private void handleGetDepositBoxStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(Map.of("open", interactionPlugin.isDepositBoxOpen()));
    }

    private void handleGetDepositBoxItems(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        var items = interactionPlugin.getDepositBoxItems();
        ctx.json(Map.of("count", items.size(), "items", items));
    }

    private void handleDepositBoxDeposit(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String itemName = (String) body.get("itemName");
            if (itemName == null || itemName.isEmpty()) {
                ctx.status(400).json(createError("'itemName' is required"));
                return;
            }
            String option = (String) body.get("option");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success;
            if (option != null && !option.isEmpty()) {
                success = interactionPlugin.rightClickDepositBoxItemAndSelect(itemName, option, profile);
            } else {
                success = interactionPlugin.clickDepositBoxItem(itemName, profile);
            }
            ctx.json(Map.of("success", success, "itemName", itemName));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleDepositBoxDepositInventory(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = ctx.queryParam("profile");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName != null ? profileName : "NORMAL");
        boolean success = interactionPlugin.depositBoxDepositInventory(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleDepositBoxDepositEquipment(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = ctx.queryParam("profile");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName != null ? profileName : "NORMAL");
        boolean success = interactionPlugin.depositBoxDepositEquipment(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleDepositBoxDepositLoot(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = ctx.queryParam("profile");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName != null ? profileName : "NORMAL");
        boolean success = interactionPlugin.depositBoxDepositLootingBag(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleSetDepositBoxQuantity(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int quantity = ((Number) body.get("quantity")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setDepositBoxQuantity(quantity, profile);
            ctx.json(Map.of("success", success, "quantity", quantity));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleCloseDepositBox(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = ctx.queryParam("profile");
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName != null ? profileName : "NORMAL");
        boolean success = interactionPlugin.closeDepositBox(profile);
        ctx.json(Map.of("success", success));
    }

    // ===== MINIMAP CLICK HANDLERS =====

    private void handleMinimapClick(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success;
            if (body.containsKey("dx") || body.containsKey("dy")) {
                int dx = body.containsKey("dx") ? ((Number) body.get("dx")).intValue() : 0;
                int dy = body.containsKey("dy") ? ((Number) body.get("dy")).intValue() : 0;
                success = interactionPlugin.clickMinimapRelative(dx, dy, profile);
            } else if (body.containsKey("x") && body.containsKey("y")) {
                int x = ((Number) body.get("x")).intValue();
                int y = ((Number) body.get("y")).intValue();
                int plane = body.containsKey("plane") ? ((Number) body.get("plane")).intValue() : 0;
                success = interactionPlugin.clickMinimap(x, y, plane, profile);
            } else {
                ctx.status(400).json(createError("Provide 'x'+'y' (world coords) or 'dx'+'dy' (relative tiles)"));
                return;
            }
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== GRAND EXCHANGE HANDLERS =====

    private void handleGetGEStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        Map<String, Object> status = new HashMap<>();
        status.put("open", interactionPlugin.isGrandExchangeOpen());
        status.put("offers", interactionPlugin.getGrandExchangeOffers());
        ctx.json(status);
    }

    private void handleGetGEOffers(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getGrandExchangeOffers());
    }

    @SuppressWarnings("unchecked")
    private void handleGEBuy(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int slot = ((Number) body.get("slot")).intValue();
            String item = (String) body.get("item");
            int quantity = ((Number) body.get("quantity")).intValue();
            int price = ((Number) body.get("price")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            // Click buy on the slot
            boolean success = interactionPlugin.clickGrandExchangeBuy(slot, profile);
            if (!success) {
                ctx.json(Map.of("success", false, "error", "Failed to click buy on slot " + slot));
                return;
            }

            // Search for the item
            Thread.sleep(600 + (int)(Math.random() * 300));
            interactionPlugin.searchGrandExchangeItem(item);

            // Set quantity
            Thread.sleep(400 + (int)(Math.random() * 200));
            interactionPlugin.setGrandExchangeQuantity(quantity, profile);

            // Set price
            Thread.sleep(400 + (int)(Math.random() * 200));
            interactionPlugin.setGrandExchangePrice(price, profile);

            // Confirm
            Thread.sleep(300 + (int)(Math.random() * 200));
            success = interactionPlugin.confirmGrandExchangeOffer(profile);

            ctx.json(Map.of("success", success, "slot", slot, "item", item, "quantity", quantity, "price", price));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGESell(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int slot = ((Number) body.get("slot")).intValue();
            String item = (String) body.get("item");
            int quantity = body.containsKey("quantity") ? ((Number) body.get("quantity")).intValue() : 0;
            int price = body.containsKey("price") ? ((Number) body.get("price")).intValue() : 0;
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            // Click sell on the slot
            boolean success = interactionPlugin.clickGrandExchangeSell(slot, profile);
            if (!success) {
                ctx.json(Map.of("success", false, "error", "Failed to click sell on slot " + slot));
                return;
            }

            // Click the item from inventory in the GE side panel
            Thread.sleep(600 + (int)(Math.random() * 300));
            // The item should be in the inventory panel on the side — click it by name
            interactionPlugin.clickInventoryItem(item, profile);

            // Set quantity if specified
            if (quantity > 0) {
                Thread.sleep(400 + (int)(Math.random() * 200));
                interactionPlugin.setGrandExchangeQuantity(quantity, profile);
            }

            // Set price if specified
            if (price > 0) {
                Thread.sleep(400 + (int)(Math.random() * 200));
                interactionPlugin.setGrandExchangePrice(price, profile);
            }

            // Confirm
            Thread.sleep(300 + (int)(Math.random() * 200));
            success = interactionPlugin.confirmGrandExchangeOffer(profile);

            ctx.json(Map.of("success", success, "slot", slot, "item", item));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleGECollect(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            String profileName = "NORMAL";
            try {
                @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
                profileName = (String) body.getOrDefault("profile", "NORMAL");
            } catch (Exception ignored) {}
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.collectGrandExchangeOffers(profile);
            ctx.json(Map.of("success", success));
        } catch (Exception e) {
            ctx.status(400).json(createError("Error: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGEAbort(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int slot = ((Number) body.get("slot")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.abortGrandExchangeOffer(slot, profile);
            ctx.json(Map.of("success", success, "slot", slot));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleGEClose(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = "NORMAL";
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            profileName = (String) body.getOrDefault("profile", "NORMAL");
        } catch (Exception ignored) {}
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
        boolean success = interactionPlugin.closeGrandExchange(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleGEBack(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = "NORMAL";
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            profileName = (String) body.getOrDefault("profile", "NORMAL");
        } catch (Exception ignored) {}
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
        boolean success = interactionPlugin.grandExchangeBack(profile);
        ctx.json(Map.of("success", success));
    }

    private void handleGEConfirm(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        String profileName = "NORMAL";
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body = ctx.bodyAsClass(Map.class);
            profileName = (String) body.getOrDefault("profile", "NORMAL");
        } catch (Exception ignored) {}
        MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
        boolean success = interactionPlugin.confirmGrandExchangeOffer(profile);
        ctx.json(Map.of("success", success));
    }

    @SuppressWarnings("unchecked")
    private void handleGESetPrice(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int price = ((Number) body.get("price")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setGrandExchangePrice(price, profile);
            ctx.json(Map.of("success", success, "price", price));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGESetQuantity(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int quantity = ((Number) body.get("quantity")).intValue();
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);
            boolean success = interactionPlugin.setGrandExchangeQuantity(quantity, profile);
            ctx.json(Map.of("success", success, "quantity", quantity));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGESearch(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String item = (String) body.get("item");
            if (item == null || item.isEmpty()) {
                ctx.status(400).json(createError("'item' is required"));
                return;
            }
            boolean success = interactionPlugin.searchGrandExchangeItem(item);
            ctx.json(Map.of("success", success, "item", item));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== ENHANCED COMBAT STATE HANDLERS =====

    private void handleGetCombatSnapshot(Context ctx) {
        if (gameStatePlugin == null) { ctx.status(503).json(createError("GameState plugin not loaded")); return; }
        net.runelite.client.plugins.gamestate.CombatStateManager combatMgr = gameStatePlugin.getCombatStateManager();
        if (combatMgr == null) { ctx.status(503).json(createError("Combat state manager not initialized")); return; }
        ctx.json(combatMgr.getSnapshot().toMap());
    }

    private void handleGetCombatLog(Context ctx) {
        if (gameStatePlugin == null) { ctx.status(503).json(createError("GameState plugin not loaded")); return; }
        net.runelite.client.plugins.gamestate.CombatStateManager combatMgr = gameStatePlugin.getCombatStateManager();
        if (combatMgr == null) { ctx.status(503).json(createError("Combat state manager not initialized")); return; }
        String limitStr = ctx.queryParam("limit");
        int limit = 50;
        if (limitStr != null) {
            try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
        }
        ctx.json(combatMgr.getCombatLog(limit));
    }

    // ===== BREAK HANDLER =====

    @SuppressWarnings("unchecked")
    private void handleBreakStart(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int minBreakMs = ((Number) body.get("minBreakMs")).intValue();
            int maxBreakMs = ((Number) body.get("maxBreakMs")).intValue();
            int minPlayMs = ((Number) body.get("minPlayMs")).intValue();
            int maxPlayMs = ((Number) body.get("maxPlayMs")).intValue();
            boolean logoutDuringBreak = body.containsKey("logoutDuringBreak") ? (Boolean) body.get("logoutDuringBreak") : false;

            interactionPlugin.getBreakHandler().start(minBreakMs, maxBreakMs, minPlayMs, maxPlayMs, logoutDuringBreak);
            ctx.json(Map.of("success", true, "minBreakMs", minBreakMs, "maxBreakMs", maxBreakMs,
                "minPlayMs", minPlayMs, "maxPlayMs", maxPlayMs, "logoutDuringBreak", logoutDuringBreak));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    private void handleBreakStop(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        interactionPlugin.getBreakHandler().stop();
        ctx.json(Map.of("success", true));
    }

    private void handleBreakStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        ctx.json(interactionPlugin.getBreakHandler().getStatus());
    }

    private void handleBreakTrigger(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        interactionPlugin.getBreakHandler().triggerBreakNow();
        ctx.json(Map.of("success", true, "message", "Break triggered"));
    }

    private void handleBreakSkip(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        interactionPlugin.getBreakHandler().skipBreak();
        ctx.json(Map.of("success", true, "message", "Break skip requested"));
    }

    // ===== LOGIN HANDLERS =====

    @SuppressWarnings("unchecked")
    private void handleLogin(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

            String username = (String) body.get("username");
            String password = (String) body.get("password");
            String accountId = (String) body.getOrDefault("account", "default");

            boolean success;
            if (username != null && password != null) {
                // Explicit credentials provided
                success = interactionPlugin.login(username, password);
            } else {
                // Use stored credentials from .env
                success = interactionPlugin.loginWithStoredCredentials(accountId);
            }
            ctx.json(Map.of("success", success, "account", accountId));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }

    // ===== BANK PIN HANDLERS =====

    private void handleGetBankPinStatus(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        boolean open = interactionPlugin.isBankPinOpen();
        ctx.json(Map.of("open", open));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterBankPin(Context ctx) {
        if (interactionPlugin == null) { ctx.status(503).json(createError("Interaction plugin not loaded")); return; }
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            try { body = ctx.bodyAsClass(Map.class); } catch (Exception ignored) {}

            String pin = (String) body.get("pin");
            String accountId = (String) body.getOrDefault("account", "default");
            String profileName = (String) body.getOrDefault("profile", "NORMAL");
            MouseMovementProfile profile = MouseMovementProfile.fromString(profileName);

            boolean success;
            if (pin != null && pin.length() == 4) {
                // Explicit pin provided
                success = interactionPlugin.enterBankPin(pin, profile);
            } else {
                // Use stored pin from .env
                success = interactionPlugin.enterStoredBankPin(accountId, profile);
            }
            ctx.json(Map.of("success", success, "account", accountId));
        } catch (Exception e) {
            ctx.status(400).json(createError("Invalid request: " + e.getMessage()));
        }
    }
}
