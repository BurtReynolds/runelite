package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.objectdetection.GameObjectInfo;
import net.runelite.client.plugins.objectdetection.ObjectDetectionPlugin;

import javax.inject.Inject;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plugin for human-like mouse movement and comprehensive UI/game interaction.
 * Supports objects, NPCs, inventory, player menu, prayers, skills, and all UI widgets.
 */
@Slf4j
@PluginDescriptor(
    name = "Interaction",
    description = "Provides human-like mouse movement and interaction with all UI elements",
    tags = {"api", "interaction", "mouse", "automation"}
)
public class InteractionPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private ClientThread clientThread;

    private HumanMouseMovement mouseMovement;
    private ObjectDetectionPlugin objectDetectionPlugin;

    @Override
    protected void startUp() throws Exception {
        log.info("Interaction Plugin started");
        mouseMovement = new HumanMouseMovement(client);

        // Get ObjectDetectionPlugin instance
        objectDetectionPlugin = getPluginInstance(ObjectDetectionPlugin.class);
        if (objectDetectionPlugin == null) {
            log.warn("ObjectDetection plugin not loaded - object interaction will be limited");
        }
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Interaction Plugin stopped");
    }

    // Public API methods

    /**
     * Move mouse to specific screen coordinates
     */
    public void moveMouseTo(int x, int y, MouseMovementProfile profile) {
        mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
    }

    /**
     * Move mouse to specific screen coordinates (using default profile)
     */
    public void moveMouseTo(int x, int y) {
        moveMouseTo(x, y, MouseMovementProfile.NORMAL);
    }

    /**
     * Click at specific screen coordinates
     */
    public void clickAt(int x, int y, MouseMovementProfile profile) {
        moveMouseTo(x, y, profile);
        sleep(50 + (int) (Math.random() * 100));
        mouseMovement.click();
    }

    /**
     * Click at current mouse position
     */
    public void click() {
        mouseMovement.click();
    }

    /**
     * Right-click at current mouse position
     */
    public void rightClick() {
        mouseMovement.rightClick();
    }

    /**
     * Interact with nearest object by name
     */
    public boolean interactWithObject(String objectName, MouseMovementProfile profile) {
        if (objectDetectionPlugin == null) {
            log.error("ObjectDetection plugin not available");
            return false;
        }

        GameObjectInfo object = objectDetectionPlugin.getClosestObjectByName(objectName);
        if (object == null) {
            log.warn("Object '{}' not found", objectName);
            return false;
        }

        return interactWithObject(object, profile);
    }

    /**
     * Interact with nearest object by name and action
     */
    public boolean interactWithObject(String objectName, String action, MouseMovementProfile profile) {
        if (objectDetectionPlugin == null) {
            log.error("ObjectDetection plugin not available");
            return false;
        }

        // Find objects with this name
        var objects = objectDetectionPlugin.getObjectsByName(objectName);
        if (objects.isEmpty()) {
            log.warn("Object '{}' not found", objectName);
            return false;
        }

        // Filter by action
        GameObjectInfo targetObject = null;
        double minDistance = Double.MAX_VALUE;
        WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();

        for (GameObjectInfo obj : objects) {
            if (obj.hasAction(action)) {
                double distance = obj.distanceFrom(playerLoc);
                if (distance < minDistance) {
                    minDistance = distance;
                    targetObject = obj;
                }
            }
        }

        if (targetObject == null) {
            log.warn("Object '{}' with action '{}' not found", objectName, action);
            return false;
        }

        return interactWithObject(targetObject, profile);
    }

    /**
     * Interact with closest object matching action
     */
    public boolean interactWithAction(String action, MouseMovementProfile profile) {
        if (objectDetectionPlugin == null) {
            log.error("ObjectDetection plugin not available");
            return false;
        }

        GameObjectInfo object = objectDetectionPlugin.getClosestObjectWithAction(action);
        if (object == null) {
            log.warn("Object with action '{}' not found", action);
            return false;
        }

        return interactWithObject(object, profile);
    }

    /**
     * Interact with a specific GameObjectInfo
     */
    private boolean interactWithObject(GameObjectInfo objectInfo, MouseMovementProfile profile) {
        // Get the object's screen position
        Point screenPoint = getObjectScreenPoint(objectInfo);
        if (screenPoint == null) {
            log.warn("Could not get screen coordinates for object");
            return false;
        }

        // Add random offset (jitter) within reasonable bounds
        int jitterX = (int) ((Math.random() - 0.5) * 10);
        int jitterY = (int) ((Math.random() - 0.5) * 10);

        // Move and click
        mouseMovement.moveAndClick(
            new java.awt.Point(screenPoint.getX() + jitterX, screenPoint.getY() + jitterY),
            profile
        );

        log.info("Interacted with object: {} at {}", objectInfo.getName(), objectInfo.getLocation());
        return true;
    }

    /**
     * Get screen coordinates for a game object
     */
    private Point getObjectScreenPoint(GameObjectInfo objectInfo) {
        WorldPoint worldLocation = objectInfo.getLocation();

        // Convert world coordinates to local scene coordinates
        int sceneX = worldLocation.getX() - client.getBaseX();
        int sceneY = worldLocation.getY() - client.getBaseY();

        if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) {
            return null;
        }

        // Get local point
        net.runelite.api.coords.LocalPoint localPoint = net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
        if (localPoint == null) {
            return null;
        }

        // Convert to canvas/screen point
        return net.runelite.api.Perspective.localToCanvas(client, localPoint, client.getPlane());
    }

    /**
     * Walk to world coordinates by clicking minimap
     */
    public void walkTo(WorldPoint destination, MouseMovementProfile profile) {
        // Get minimap position for destination
        Point minimapPoint = getMinimapPoint(destination);
        if (minimapPoint == null) {
            log.warn("Destination not on minimap: {}", destination);
            return;
        }

        // Click on minimap
        mouseMovement.moveAndClick(
            new java.awt.Point(minimapPoint.getX(), minimapPoint.getY()),
            profile
        );

        log.info("Walking to: {}", destination);
    }

    // ===== INVENTORY INTERACTION =====

    /**
     * Click an inventory slot by index (0-27)
     */
    public boolean clickInventorySlot(int slot, MouseMovementProfile profile) {
        return runOnClientThread(() -> clickInventorySlotInternal(slot, profile));
    }

    private boolean clickInventorySlotInternal(int slot, MouseMovementProfile profile) {
        if (slot < 0 || slot > 27) {
            log.error("Invalid inventory slot: {} (must be 0-27)", slot);
            return false;
        }

        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            log.warn("Inventory widget not visible");
            return false;
        }

        Widget[] items = inventoryWidget.getDynamicChildren();
        if (items == null || slot >= items.length) {
            log.warn("Inventory slot {} not found", slot);
            return false;
        }

        return clickWidgetInternal(items[slot], profile);
    }

    /**
     * Click an inventory item by name (clicks first match)
     */
    public boolean clickInventoryItem(String itemName, MouseMovementProfile profile) {
        return runOnClientThread(() -> clickInventoryItemInternal(itemName, profile));
    }

    private boolean clickInventoryItemInternal(String itemName, MouseMovementProfile profile) {
        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            log.warn("Inventory widget not visible");
            return false;
        }

        Widget[] items = inventoryWidget.getDynamicChildren();
        if (items == null) {
            return false;
        }

        for (Widget item : items) {
            if (item != null && item.getName() != null && item.getName().contains(itemName)) {
                log.info("Found inventory item: {} at slot", itemName);
                return clickWidgetInternal(item, profile);
            }
        }

        log.warn("Inventory item '{}' not found", itemName);
        return false;
    }

    /**
     * Right-click an inventory item to open menu
     */
    public boolean rightClickInventoryItem(String itemName) {
        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            log.warn("Inventory widget not visible");
            return false;
        }

        Widget[] items = inventoryWidget.getDynamicChildren();
        if (items == null) {
            return false;
        }

        for (Widget item : items) {
            if (item != null && item.getName() != null && item.getName().contains(itemName)) {
                Point screenPoint = getWidgetScreenPoint(item);
                if (screenPoint != null) {
                    mouseMovement.moveMouse(
                        new java.awt.Point(screenPoint.getX(), screenPoint.getY()),
                        MouseMovementProfile.NORMAL
                    );
                    sleep(50 + (int) (Math.random() * 100));
                    mouseMovement.rightClick();
                    return true;
                }
            }
        }

        log.warn("Inventory item '{}' not found", itemName);
        return false;
    }

    // ===== PLAYER MENU INTERACTION =====

    /**
     * Open a tab in the player menu (equipment, stats, quest, inventory, etc.)
     * Automatically detects viewport mode (fixed vs resizable)
     * This method can be called from any thread (e.g., HTTP thread)
     */
    public boolean openPlayerTab(PlayerTab tab, MouseMovementProfile profile) {
        return runOnClientThread(() -> openPlayerTabInternal(tab, profile));
    }

    /**
     * Internal implementation that runs on client thread
     */
    private boolean openPlayerTabInternal(PlayerTab tab, MouseMovementProfile profile) {
        Widget tabWidget = findTabWidget(tab);
        if (tabWidget == null) {
            log.warn("Player tab {} widget not found in any viewport mode", tab);
            return false;
        }

        log.debug("Found tab {} widget: {}", tab, tabWidget.getId());
        return clickWidgetInternal(tabWidget, profile);
    }

    /**
     * Find tab widget across all viewport modes (fixed, resizable, resizable-modern)
     */
    private Widget findTabWidget(PlayerTab tab) {
        // Try fixed viewport first
        Widget widget = client.getWidget(tab.getFixedViewportWidget());
        if (widget != null && !widget.isHidden()) {
            log.debug("Using fixed viewport for tab {}", tab);
            return widget;
        }

        // Try resizable viewport (classic)
        widget = client.getWidget(tab.getResizableViewportWidget());
        if (widget != null && !widget.isHidden()) {
            log.debug("Using resizable viewport for tab {}", tab);
            return widget;
        }

        // Try resizable viewport (modern/bottom-line)
        widget = client.getWidget(tab.getResizableBottomLineWidget());
        if (widget != null && !widget.isHidden()) {
            log.debug("Using resizable bottom-line viewport for tab {}", tab);
            return widget;
        }

        log.warn("Tab {} not found in any viewport mode (fixed/resizable/bottom-line)", tab);
        return null;
    }

    /**
     * Click the equipment tab
     */
    public boolean openEquipment(MouseMovementProfile profile) {
        return openPlayerTab(PlayerTab.EQUIPMENT, profile);
    }

    /**
     * Click the stats tab
     */
    public boolean openStats(MouseMovementProfile profile) {
        return openPlayerTab(PlayerTab.STATS, profile);
    }

    /**
     * Click the quest tab
     */
    public boolean openQuests(MouseMovementProfile profile) {
        return openPlayerTab(PlayerTab.QUESTS, profile);
    }

    /**
     * Click the prayer tab
     */
    public boolean openPrayers(MouseMovementProfile profile) {
        return openPlayerTab(PlayerTab.PRAYER, profile);
    }

    /**
     * Click the magic tab
     */
    public boolean openMagic(MouseMovementProfile profile) {
        return openPlayerTab(PlayerTab.MAGIC, profile);
    }

    // ===== PRAYER INTERACTION =====
    // Note: Prayer widget content area not exposed in WidgetInfo
    // Users should open prayer tab then use clickWidget() with specific prayer widget IDs

    /**
     * Toggle a prayer by widget ID
     * Note: Prayer content widget not exposed in WidgetInfo, use specific IDs
     */
    public boolean togglePrayerByWidgetId(int groupId, int childId, MouseMovementProfile profile) {
        Widget prayer = client.getWidget(groupId, childId);
        if (prayer == null || prayer.isHidden()) {
            log.warn("Prayer widget {}.{} not found or hidden", groupId, childId);
            return false;
        }

        log.info("Toggling prayer widget {}.{}", groupId, childId);
        return clickWidget(prayer, profile);
    }

    // ===== SKILLS INTERACTION =====
    // Note: Skills container exists but individual skill widgets need specific IDs

    /**
     * Click a skill by widget ID
     */
    public boolean clickSkillByWidgetId(int groupId, int childId, MouseMovementProfile profile) {
        Widget skill = client.getWidget(groupId, childId);
        if (skill == null || skill.isHidden()) {
            log.warn("Skill widget {}.{} not found or hidden", groupId, childId);
            return false;
        }

        log.info("Clicking skill widget {}.{}", groupId, childId);
        return clickWidget(skill, profile);
    }

    // ===== GENERAL WIDGET INTERACTION =====

    /**
     * Click any widget by WidgetInfo
     */
    public boolean clickWidgetByInfo(WidgetInfo widgetInfo, MouseMovementProfile profile) {
        return runOnClientThread(() -> clickWidgetByInfoInternal(widgetInfo, profile));
    }

    private boolean clickWidgetByInfoInternal(WidgetInfo widgetInfo, MouseMovementProfile profile) {
        Widget widget = client.getWidget(widgetInfo);
        if (widget == null) {
            log.warn("Widget {} not found", widgetInfo);
            return false;
        }

        return clickWidgetInternal(widget, profile);
    }

    /**
     * Click a specific widget (thread-safe wrapper)
     */
    public boolean clickWidget(Widget widget, MouseMovementProfile profile) {
        return runOnClientThread(() -> clickWidgetInternal(widget, profile));
    }

    /**
     * Click a specific widget (internal implementation on client thread)
     */
    private boolean clickWidgetInternal(Widget widget, MouseMovementProfile profile) {
        if (widget == null || widget.isHidden()) {
            log.warn("Widget is null or hidden");
            return false;
        }

        Point screenPoint = getWidgetScreenPoint(widget);
        if (screenPoint == null) {
            log.warn("Could not get screen coordinates for widget");
            return false;
        }

        // Add jitter from profile
        int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
        int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

        mouseMovement.moveAndClick(
            new java.awt.Point(screenPoint.getX() + jitterX, screenPoint.getY() + jitterY),
            profile
        );

        log.info("Clicked widget at screen position: ({}, {})", screenPoint.getX(), screenPoint.getY());
        return true;
    }

    /**
     * Helper method to run code on client thread and wait for result
     */
    private <T> T runOnClientThread(java.util.function.Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();

        clientThread.invoke(() -> {
            try {
                T result = supplier.get();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            // Wait up to 5 seconds for the operation to complete
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error executing on client thread", e);
            throw new RuntimeException("Failed to execute on client thread", e);
        }
    }

    /**
     * Get center screen coordinates for a widget
     */
    private Point getWidgetScreenPoint(Widget widget) {
        if (widget == null) {
            return null;
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null) {
            return null;
        }

        // Return center point of widget
        int x = (int) (bounds.getX() + bounds.getWidth() / 2);
        int y = (int) (bounds.getY() + bounds.getHeight() / 2);

        return new Point(x, y);
    }

    /**
     * Get minimap screen coordinates for world point
     */
    private Point getMinimapPoint(WorldPoint worldPoint) {
        // Convert WorldPoint to LocalPoint
        net.runelite.api.coords.LocalPoint localPoint =
            net.runelite.api.coords.LocalPoint.fromWorld(client, worldPoint);

        if (localPoint == null) {
            return null;
        }

        // Convert LocalPoint to minimap screen coordinates
        return net.runelite.api.Perspective.localToMinimap(client, localPoint);
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

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
