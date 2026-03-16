package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gamestate.GameStatePlugin;
import net.runelite.client.plugins.objectdetection.GameObjectInfo;
import net.runelite.client.plugins.objectdetection.ObjectDetectionPlugin;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plugin for human-like virtual mouse movement and comprehensive UI/game interaction.
 * Uses canvas event injection (virtual mouse) instead of java.awt.Robot.
 * Supports fixed, resizable-classic, and resizable-modern (bottom-line) layouts.
 */
@Slf4j
@PluginDescriptor(
	name = "Interaction",
	description = "Provides human-like virtual mouse movement and interaction with all UI elements",
	tags = {"api", "interaction", "mouse", "automation"}
)
public class InteractionPlugin extends Plugin {

	@Inject
	private Client client;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WorldService worldService;

	private HumanMouseMovement mouseMovement;
	private ObjectDetectionPlugin objectDetectionPlugin;
	private GameStatePlugin gameStatePlugin;
	private WebWalker webWalker;
	private VirtualMouseOverlay virtualMouseOverlay;
	private AntiBanService antiBanService;
	private BreakHandler breakHandler;

	@Override
	protected void startUp() throws Exception {
		log.info("Interaction Plugin started");
		mouseMovement = new HumanMouseMovement(client);

		// Get ObjectDetectionPlugin instance
		objectDetectionPlugin = getPluginInstance(ObjectDetectionPlugin.class);
		if (objectDetectionPlugin == null) {
			log.warn("ObjectDetection plugin not loaded - object interaction will be limited");
		}

		// Get GameStatePlugin instance
		gameStatePlugin = getPluginInstance(GameStatePlugin.class);
		if (gameStatePlugin == null) {
			log.warn("GameState plugin not loaded - conditional wait steps will be limited");
		}

		// Initialize WebWalker
		webWalker = new WebWalker(client, clientThread, this, mouseMovement);
		webWalker.setObjectDetectionPlugin(objectDetectionPlugin);
		log.info("WebWalker initialized");

		// Initialize virtual mouse overlay
		virtualMouseOverlay = new VirtualMouseOverlay(mouseMovement);
		overlayManager.add(virtualMouseOverlay);
		log.info("Virtual mouse cursor overlay enabled");

		// Initialize anti-ban service
		antiBanService = new AntiBanService(this, client, mouseMovement);
		log.info("Anti-ban service initialized");

		// Initialize break handler (disabled by default)
		breakHandler = new BreakHandler(this);
		log.info("Break handler initialized (disabled by default)");
	}

	@Override
	protected void shutDown() throws Exception {
		if (antiBanService != null) {
			antiBanService.stop();
		}
		if (breakHandler != null) {
			breakHandler.stop();
		}
		if (virtualMouseOverlay != null) {
			overlayManager.remove(virtualMouseOverlay);
		}
		log.info("Interaction Plugin stopped");
	}

	/**
	 * Enable or disable the virtual mouse cursor overlay.
	 */
	public void setVirtualCursorEnabled(boolean enabled) {
		if (virtualMouseOverlay != null) {
			virtualMouseOverlay.setEnabled(enabled);
		}
	}

	/**
	 * Check if the virtual mouse cursor overlay is enabled.
	 */
	public boolean isVirtualCursorEnabled() {
		return virtualMouseOverlay != null && virtualMouseOverlay.isEnabled();
	}

	/**
	 * Get the underlying mouse movement handler (for position queries etc.)
	 */
	public HumanMouseMovement getMouseMovement() {
		return mouseMovement;
	}

	// ===== MOUSE MOVEMENT =====

	/**
	 * Move virtual mouse to specific canvas coordinates
	 */
	public void moveMouseTo(int x, int y, MouseMovementProfile profile) {
		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
	}

	/**
	 * Move virtual mouse to specific canvas coordinates (default profile)
	 */
	public void moveMouseTo(int x, int y) {
		moveMouseTo(x, y, MouseMovementProfile.NORMAL);
	}

	/**
	 * Click at specific canvas coordinates
	 */
	public void clickAt(int x, int y, MouseMovementProfile profile) {
		mouseMovement.moveAndClick(new java.awt.Point(x, y), profile);
	}

	/**
	 * Click at current virtual mouse position
	 */
	public void click() {
		mouseMovement.click();
	}

	/**
	 * Right-click at current virtual mouse position
	 */
	public void rightClick() {
		mouseMovement.rightClick();
	}

	// ===== NPC INTERACTION =====

	/**
	 * Interact with nearest NPC by name (left-click).
	 */
	public boolean interactWithNPC(String npcName, MouseMovementProfile profile) {
		if (objectDetectionPlugin == null) {
			log.error("ObjectDetection plugin not available");
			return false;
		}

		net.runelite.client.plugins.objectdetection.NPCInfo npc = objectDetectionPlugin.getClosestNPC(npcName);
		if (npc == null) {
			log.warn("NPC '{}' not found", npcName);
			return false;
		}

		return clickNPCOnScreen(npc, profile);
	}

	/**
	 * Interact with nearest NPC by name, using a specific action (right-click + select).
	 */
	public boolean interactWithNPC(String npcName, String action, MouseMovementProfile profile) {
		if (objectDetectionPlugin == null) {
			log.error("ObjectDetection plugin not available");
			return false;
		}

		var npcs = objectDetectionPlugin.getNPCsByName(npcName);
		if (npcs.isEmpty()) {
			log.warn("NPC '{}' not found", npcName);
			return false;
		}

		// Find closest NPC that has the requested action
		net.runelite.client.plugins.objectdetection.NPCInfo targetNPC = null;
		double minDistance = Double.MAX_VALUE;
		WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();

		for (net.runelite.client.plugins.objectdetection.NPCInfo npc : npcs) {
			if (npc.hasAction(action)) {
				double distance = npc.distanceFrom(playerLoc);
				if (distance < minDistance) {
					minDistance = distance;
					targetNPC = npc;
				}
			}
		}

		if (targetNPC == null) {
			// Fallback: use closest NPC regardless of action, and right-click + select
			targetNPC = objectDetectionPlugin.getClosestNPC(npcName);
			if (targetNPC == null) {
				log.warn("NPC '{}' with action '{}' not found", npcName, action);
				return false;
			}
		}

		Point screenPoint = getNPCScreenPoint(targetNPC);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for NPC '{}'", npcName);
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 10);
		int jitterY = (int) ((Math.random() - 0.5) * 10);

		return rightClickAndSelect(
			screenPoint.getX() + jitterX,
			screenPoint.getY() + jitterY,
			action, npcName, profile
		);
	}

	private boolean clickNPCOnScreen(net.runelite.client.plugins.objectdetection.NPCInfo npc, MouseMovementProfile profile) {
		Point screenPoint = getNPCScreenPoint(npc);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for NPC '{}'", npc.getName());
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 10);
		int jitterY = (int) ((Math.random() - 0.5) * 10);

		mouseMovement.moveAndClick(
			new java.awt.Point(screenPoint.getX() + jitterX, screenPoint.getY() + jitterY),
			profile
		);

		log.info("Interacted with NPC: {} at {}", npc.getName(), npc.getLocation());
		return true;
	}

	private Point getNPCScreenPoint(net.runelite.client.plugins.objectdetection.NPCInfo npcInfo) {
		WorldPoint worldLocation = npcInfo.getLocation();

		int sceneX = worldLocation.getX() - client.getBaseX();
		int sceneY = worldLocation.getY() - client.getBaseY();

		if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) {
			return null;
		}

		net.runelite.api.coords.LocalPoint localPoint =
			net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
		if (localPoint == null) {
			return null;
		}

		return net.runelite.api.Perspective.localToCanvas(client, localPoint, client.getPlane());
	}

	// ===== OBJECT INTERACTION =====

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
	 * Interact with nearest object by name and action.
	 * If the nearest candidate's live right-click menu doesn't contain the exact action,
	 * tries the next nearest candidate (handles stale cache entries).
	 */
	public boolean interactWithObject(String objectName, String action, MouseMovementProfile profile) {
		if (objectDetectionPlugin == null) {
			log.error("ObjectDetection plugin not available");
			return false;
		}

		var objects = objectDetectionPlugin.getObjectsByName(objectName);
		if (objects.isEmpty()) {
			log.warn("Object '{}' not found", objectName);
			return false;
		}

		WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();

		// Collect all candidates with the cached action, sorted by distance
		java.util.List<GameObjectInfo> candidates = new java.util.ArrayList<>();
		for (GameObjectInfo obj : objects) {
			if (obj.hasAction(action)) {
				candidates.add(obj);
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble((GameObjectInfo obj) -> obj.distanceFrom(playerLoc)));

		if (candidates.isEmpty()) {
			log.warn("Object '{}' with action '{}' not found", objectName, action);
			return false;
		}

		// Try each candidate — if the live menu doesn't have the exact action, try the next one
		for (GameObjectInfo candidate : candidates) {
			log.info("Trying object '{}' at {} (distance: {})", candidate.getName(), candidate.getLocation(), (int) candidate.distanceFrom(playerLoc));
			boolean selected = rightClickObjectAndSelect(candidate, action, profile);
			if (selected) {
				return true;
			}
			// Menu was dismissed, try the next candidate
			log.info("Action '{}' not available on object at {} — trying next candidate", action, candidate.getLocation());
			sleep(200 + (int) (Math.random() * 300));
		}

		log.warn("No object '{}' had action '{}' in its live menu", objectName, action);
		return false;
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

	private boolean interactWithObject(GameObjectInfo objectInfo, MouseMovementProfile profile) {
		Point screenPoint = getObjectScreenPoint(objectInfo);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for object");
			return false;
		}

		mouseMovement.moveAndClick(
			new java.awt.Point(screenPoint.getX(), screenPoint.getY()),
			profile
		);

		log.info("Interacted with object: {} at {}", objectInfo.getName(), objectInfo.getLocation());
		return true;
	}

	/**
	 * Right-click an object and select a specific action from the context menu.
	 * Uses exact match only — "Light" will NOT match "Re-light".
	 * Use this when the desired action is NOT the left-click default.
	 */
	private boolean rightClickObjectAndSelect(GameObjectInfo objectInfo, String action, MouseMovementProfile profile) {
		Point screenPoint = getObjectScreenPoint(objectInfo);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for object");
			return false;
		}

		int x = screenPoint.getX();
		int y = screenPoint.getY();

		// Move to position and right-click
		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
		sleep(50 + (int) (Math.random() * 80));
		mouseMovement.rightClick();

		// Wait for menu to open
		if (!waitForMenuOpen(2000)) {
			log.warn("Right-click menu did not open within timeout for object '{}'", objectInfo.getName());
			return false;
		}

		sleep(50 + (int) (Math.random() * 80));

		// Use exact match to prevent "Light" matching "Re-light"
		boolean selected = selectMenuOptionExact(action, objectInfo.getName(), profile);
		if (selected) {
			log.info("Right-click selected '{}' on object: {} at {}", action, objectInfo.getName(), objectInfo.getLocation());
		} else {
			log.info("Exact match for '{}' not found on object: {} — dismissing menu", action, objectInfo.getName());
			// Dismiss the menu by pressing Escape
			dismissMenu();
		}
		return selected;
	}

	/**
	 * Find nearest object by name that has the given action, then right-click and select it.
	 */
	private boolean findAndRightClickObject(String objectName, String action, MouseMovementProfile profile) {
		if (objectDetectionPlugin == null) return false;

		var objects = objectDetectionPlugin.getObjectsByName(objectName);
		if (objects.isEmpty()) {
			log.warn("Object '{}' not found", objectName);
			return false;
		}

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

		return rightClickObjectAndSelect(targetObject, action, profile);
	}

	/**
	 * Get a random screen point within the clickbox of a game object.
	 * Falls back to localToCanvas if the live object or clickbox can't be found.
	 * All scene/widget access runs on the client thread.
	 */
	private Point getObjectScreenPoint(GameObjectInfo objectInfo) {
		return runOnClientThread(() -> {
			WorldPoint worldLocation = objectInfo.getLocation();

			int sceneX = worldLocation.getX() - client.getBaseX();
			int sceneY = worldLocation.getY() - client.getBaseY();

			if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) {
				return null;
			}

			// Try to find the live GameObject in the scene and use its clickbox
			net.runelite.api.Scene scene = client.getScene();
			if (scene != null) {
				net.runelite.api.Tile[][][] tiles = scene.getTiles();
				int plane = client.getPlane();
				if (tiles != null && plane < tiles.length) {
					// Search the object's tile and neighboring tiles (large objects span multiple)
					for (int dx = -1; dx <= 3; dx++) {
						for (int dy = -1; dy <= 3; dy++) {
							int tx = sceneX + dx;
							int ty = sceneY + dy;
							if (tx < 0 || tx >= 104 || ty < 0 || ty >= 104) continue;
							net.runelite.api.Tile tile = tiles[plane][tx][ty];
							if (tile == null) continue;
							for (net.runelite.api.GameObject gameObject : tile.getGameObjects()) {
								if (gameObject == null) continue;
								if (gameObject.getId() == objectInfo.getId()) {
									java.awt.Shape clickbox = gameObject.getClickbox();
									if (clickbox != null) {
										java.awt.Rectangle bounds = clickbox.getBounds();
										if (bounds.width > 0 && bounds.height > 0) {
											// Pick a random point within the inner 80% of the clickbox
											int marginX = (int)(bounds.width * 0.1);
											int marginY = (int)(bounds.height * 0.1);
											int rx = bounds.x + marginX + (int)(Math.random() * (bounds.width - 2 * marginX));
											int ry = bounds.y + marginY + (int)(Math.random() * (bounds.height - 2 * marginY));
											log.debug("Using clickbox for '{}': bounds={}x{} at ({},{}), click=({},{})",
												objectInfo.getName(), bounds.width, bounds.height, bounds.x, bounds.y, rx, ry);
											return new Point(rx, ry);
										}
									}
								}
							}
						}
					}
				}
			}

			// Fallback: project the single tile coordinate to screen
			log.debug("Clickbox not available for '{}', falling back to localToCanvas", objectInfo.getName());
			net.runelite.api.coords.LocalPoint localPoint =
				net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
			if (localPoint == null) {
				return null;
			}

			return net.runelite.api.Perspective.localToCanvas(client, localPoint, client.getPlane());
		});
	}

	/**
	 * Walk to world coordinates by clicking minimap
	 */
	public void walkTo(WorldPoint destination, MouseMovementProfile profile) {
		Point minimapPoint = getMinimapPoint(destination);
		if (minimapPoint == null) {
			log.warn("Destination not on minimap: {}", destination);
			return;
		}

		mouseMovement.moveAndClick(
			new java.awt.Point(minimapPoint.getX(), minimapPoint.getY()),
			profile
		);

		log.info("Walking to: {}", destination);
	}

	/**
	 * Web walk to any world coordinate using A* pathfinding, cross-region walking, and obstacle handling.
	 */
	public boolean webWalkTo(WorldPoint destination, MouseMovementProfile profile) {
		return webWalker.walkTo(destination, profile);
	}

	/**
	 * Cancel an in-progress web walk.
	 */
	public void cancelWebWalk() {
		if (webWalker != null) {
			webWalker.cancel();
		}
	}

	/**
	 * Get the WebWalker instance for debug info.
	 */
	public WebWalker getWebWalker() {
		return webWalker;
	}

	// ===== INVENTORY INTERACTION =====

	/**
	 * Click an inventory slot by index (0-27)
	 */
	public boolean clickInventorySlot(int slot, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			if (slot < 0 || slot > 27) {
				log.error("Invalid inventory slot: {} (must be 0-27)", slot);
				return null;
			}

			Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
			if (inventoryWidget == null || inventoryWidget.isHidden()) {
				log.warn("Inventory widget not visible");
				return null;
			}

			Widget[] items = inventoryWidget.getDynamicChildren();
			if (items == null || slot >= items.length) {
				log.warn("Inventory slot {} not found", slot);
				return null;
			}

			return getWidgetClickPoint(items[slot], profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click an inventory item by name (clicks first match)
	 */
	public boolean clickInventoryItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
			if (inventoryWidget == null || inventoryWidget.isHidden()) {
				log.warn("Inventory widget not visible");
				return null;
			}

			Widget[] items = inventoryWidget.getDynamicChildren();
			if (items == null) {
				return null;
			}

			for (Widget item : items) {
				if (item != null && item.getName() != null && item.getName().contains(itemName)) {
					log.info("Found inventory item: {}", itemName);
					return getWidgetClickPoint(item, profile);
				}
			}

			log.warn("Inventory item '{}' not found", itemName);
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click an inventory item to open menu
	 */
	public boolean rightClickInventoryItem(String itemName) {
		return runOnClientThread(() -> {
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
		});
	}

	// ===== EQUIPMENT INTERACTION =====

	/**
	 * Maps EquipmentInventorySlot to the packed widget ID for that slot
	 * in the Worn Items interface. Slots 6 (ARMS), 8 (HAIR), and 11 (JAW)
	 * don't have clickable widgets in the UI.
	 */
	private static int getEquipmentSlotWidgetId(EquipmentInventorySlot slot) {
		switch (slot) {
			case HEAD:   return InterfaceID.Wornitems.SLOT0;
			case CAPE:   return InterfaceID.Wornitems.SLOT1;
			case AMULET: return InterfaceID.Wornitems.SLOT2;
			case WEAPON: return InterfaceID.Wornitems.SLOT3;
			case BODY:   return InterfaceID.Wornitems.SLOT4;
			case SHIELD: return InterfaceID.Wornitems.SLOT5;
			case LEGS:   return InterfaceID.Wornitems.SLOT7;
			case GLOVES: return InterfaceID.Wornitems.SLOT9;
			case BOOTS:  return InterfaceID.Wornitems.SLOT10;
			case RING:   return InterfaceID.Wornitems.SLOT12;
			case AMMO:   return InterfaceID.Wornitems.SLOT13;
			default:     return -1;
		}
	}

	/**
	 * Parse equipment slot name to EquipmentInventorySlot.
	 * Case-insensitive. Accepts names like "HEAD", "WEAPON", "RING", etc.
	 */
	private static EquipmentInventorySlot parseEquipmentSlot(String slotName) {
		if (slotName == null) return null;
		try {
			return EquipmentInventorySlot.valueOf(slotName.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Click an equipment slot by slot name (e.g., "HEAD", "WEAPON", "RING").
	 * The equipment tab must be open.
	 */
	public boolean clickEquipmentSlot(String slotName, MouseMovementProfile profile) {
		EquipmentInventorySlot slot = parseEquipmentSlot(slotName);
		if (slot == null) {
			log.warn("Unknown equipment slot: '{}'", slotName);
			return false;
		}
		return clickEquipmentSlot(slot, profile);
	}

	/**
	 * Click an equipment slot by enum.
	 */
	public boolean clickEquipmentSlot(EquipmentInventorySlot slot, MouseMovementProfile profile) {
		int widgetId = getEquipmentSlotWidgetId(slot);
		if (widgetId == -1) {
			log.warn("Equipment slot {} has no clickable widget", slot);
			return false;
		}

		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible (is the equipment tab open?)", slot);
				return null;
			}
			return getWidgetClickPoint(slotWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click an equipment slot and select a menu option.
	 * Useful for actions like "Remove", "Teleport", "Rub", "Operate", etc.
	 */
	public boolean rightClickEquipmentSlotAndSelect(String slotName, String option, MouseMovementProfile profile) {
		EquipmentInventorySlot slot = parseEquipmentSlot(slotName);
		if (slot == null) {
			log.warn("Unknown equipment slot: '{}'", slotName);
			return false;
		}
		return rightClickEquipmentSlotAndSelect(slot, option, profile);
	}

	/**
	 * Right-click an equipment slot and select a menu option by enum.
	 */
	public boolean rightClickEquipmentSlotAndSelect(EquipmentInventorySlot slot, String option, MouseMovementProfile profile) {
		int widgetId = getEquipmentSlotWidgetId(slot);
		if (widgetId == -1) {
			log.warn("Equipment slot {} has no clickable widget", slot);
			return false;
		}

		// Step 1: Get widget coords on client thread
		Point slotPoint = runOnClientThread(() -> {
			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible", slot);
				return null;
			}
			return getWidgetScreenPoint(slotWidget);
		});

		if (slotPoint == null) {
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		// Step 2: Right-click + wait for menu + select — off the client thread
		return rightClickAndSelect(
			slotPoint.getX() + jitterX,
			slotPoint.getY() + jitterY,
			option, null, profile
		);
	}

	/**
	 * Find which equipment slot contains an item by name, using the WORN item container.
	 * Returns the EquipmentInventorySlot, or null if not found.
	 * Must be called on the client thread.
	 */
	private EquipmentInventorySlot findEquippedItemSlot(String itemName) {
		ItemContainer equipment = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
		if (equipment == null) {
			log.warn("Equipment container not available");
			return null;
		}

		Item[] items = equipment.getItems();
		String search = itemName.toLowerCase();

		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values()) {
			int idx = slot.getSlotIdx();
			if (idx < items.length && items[idx].getId() != -1) {
				ItemComposition itemComp = client.getItemDefinition(items[idx].getId());
				if (itemComp.getName().toLowerCase().contains(search)) {
					log.info("Found equipped item '{}' (actual: '{}') in slot {}",
						itemName, itemComp.getName(), slot);
					return slot;
				}
			}
		}

		log.warn("Equipped item '{}' not found in any slot", itemName);
		return null;
	}

	/**
	 * Click an equipped item by item name. Searches all equipment slots
	 * for an item whose name contains the search string.
	 */
	public boolean clickEquipmentItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			EquipmentInventorySlot slot = findEquippedItemSlot(itemName);
			if (slot == null) return null;

			int widgetId = getEquipmentSlotWidgetId(slot);
			if (widgetId == -1) return null;

			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible", slot);
				return null;
			}
			return getWidgetClickPoint(slotWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click an equipped item by name and select a menu option.
	 */
	public boolean rightClickEquipmentItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		// Step 1: Find item slot coords on client thread
		Point itemPoint = runOnClientThread(() -> {
			EquipmentInventorySlot slot = findEquippedItemSlot(itemName);
			if (slot == null) return null;

			int widgetId = getEquipmentSlotWidgetId(slot);
			if (widgetId == -1) return null;

			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible", slot);
				return null;
			}
			return getWidgetScreenPoint(slotWidget);
		});

		if (itemPoint == null) {
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		// Step 2: Right-click + wait for menu + select — off the client thread
		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, itemName, profile
		);
	}

	// ===== DIALOG OPTION SELECTION =====

	/**
	 * Known dialog widget IDs that can contain clickable text options.
	 * Checked in order of specificity.
	 */
	private static final int[] DIALOG_WIDGET_IDS = {
		InterfaceID.Chatmenu.OPTIONS,              // Standard "Select an Option" dialog
		InterfaceID.Skillmulti.BOTTOM,             // Multi-option dialogs with icons
		InterfaceID.Chatbox.MES_LAYER,             // Chatbox message layer (used by various popups)
	};

	/**
	 * Find a visible dialog widget that has clickable text children.
	 * Checks multiple known dialog widget types.
	 * Must be called on client thread.
	 *
	 * @return the dialog widget with children, or null if none found
	 */
	private Widget findDialogWidget() {
		for (int widgetId : DIALOG_WIDGET_IDS) {
			Widget w = client.getWidget(widgetId);
			if (w != null && !w.isHidden()) {
				java.util.List<Widget> textWidgets = getDialogOptionWidgets(w);
				if (!textWidgets.isEmpty()) {
					log.debug("Found dialog widget: 0x{} with {} text children",
						Integer.toHexString(widgetId), textWidgets.size());
					return w;
				}
			}
		}

		// Fallback: scan all chatbox-area group IDs for visible widgets with text children
		// This catches construction cape teleport and other OSRS-native popups
		int[] fallbackGroupIds = {
			InterfaceID.CHATMENU,         // 219
			InterfaceID.CHATBOX,          // 162
			InterfaceID.SKILLMULTI,       // 270
			InterfaceID.GRAPHICAL_MULTI,  // 140
		};

		for (int groupId : fallbackGroupIds) {
			// Check children 0 through 20 in each group
			for (int childId = 0; childId <= 20; childId++) {
				Widget w = client.getWidget(groupId, childId);
				if (w != null && !w.isHidden()) {
					java.util.List<Widget> textWidgets = getDialogOptionWidgets(w);
					if (textWidgets.size() >= 2) { // Need at least 2 text options to be a dialog
						log.info("Found dialog widget via fallback scan: group={}, child={} with {} text options",
							groupId, childId, textWidgets.size());
						return w;
					}
				}
			}
		}

		return null;
	}

	/**
	 * Get all text options from a dialog widget, checking both static and dynamic children.
	 * Must be called on client thread.
	 */
	private java.util.List<Widget> getDialogOptionWidgets(Widget dialogWidget) {
		java.util.List<Widget> result = new java.util.ArrayList<>();
		if (dialogWidget == null) return result;

		// Check static children first
		Widget[] children = dialogWidget.getChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && child.getText() != null && !child.getText().isEmpty()
					&& !child.isHidden()) {
					result.add(child);
				}
			}
		}

		// Also check dynamic children
		Widget[] dynChildren = dialogWidget.getDynamicChildren();
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				if (child != null && child.getText() != null && !child.getText().isEmpty()
					&& !child.isHidden()) {
					result.add(child);
				}
			}
		}

		return result;
	}

	/**
	 * Debug: brute-force scan widget groups by ID range.
	 * For each group, checks children 0-30 and reports text, sub-children, and actions.
	 * This bypasses getWidgetRoots() which may not enumerate all groups.
	 */
	public java.util.Map<String, Object> debugWidgetGroupScan(int minGroup, int maxGroup) {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> groups = new java.util.ArrayList<>();

			for (int groupId = minGroup; groupId <= maxGroup; groupId++) {
				Widget w0 = client.getWidget(groupId, 0);
				if (w0 == null) continue;

				java.util.Map<String, Object> groupInfo = new java.util.LinkedHashMap<>();
				groupInfo.put("groupId", groupId);
				groupInfo.put("hidden", w0.isHidden());

				java.util.List<java.util.Map<String, Object>> childInfoList = new java.util.ArrayList<>();
				for (int childId = 0; childId <= 30; childId++) {
					Widget w = client.getWidget(groupId, childId);
					if (w == null) continue;

					java.util.Map<String, Object> ci = new java.util.LinkedHashMap<>();
					ci.put("childId", childId);
					ci.put("hidden", w.isHidden());
					ci.put("type", w.getType());
					ci.put("width", w.getWidth());
					ci.put("height", w.getHeight());

					String text = w.getText();
					if (text != null && !text.isEmpty()) ci.put("text", text);

					Widget[] staticCh = w.getChildren();
					Widget[] dynCh = w.getDynamicChildren();
					int staticCount = staticCh != null ? staticCh.length : 0;
					int dynCount = dynCh != null ? dynCh.length : 0;
					if (staticCount > 0) ci.put("staticChildren", staticCount);
					if (dynCount > 0) ci.put("dynamicChildren", dynCount);

					java.util.List<String> texts = new java.util.ArrayList<>();
					if (staticCh != null) {
						for (int i = 0; i < Math.min(staticCh.length, 20); i++) {
							if (staticCh[i] != null && staticCh[i].getText() != null && !staticCh[i].getText().isEmpty()) {
								texts.add("s" + i + ": " + staticCh[i].getText());
							}
						}
					}
					if (dynCh != null) {
						for (int i = 0; i < Math.min(dynCh.length, 20); i++) {
							if (dynCh[i] != null && dynCh[i].getText() != null && !dynCh[i].getText().isEmpty()) {
								texts.add("d" + i + ": " + dynCh[i].getText());
							}
						}
					}
					if (!texts.isEmpty()) ci.put("childTexts", texts);

					java.util.List<String> actions = new java.util.ArrayList<>();
					if (staticCh != null) {
						for (int i = 0; i < Math.min(staticCh.length, 20); i++) {
							if (staticCh[i] != null) {
								String[] acts = staticCh[i].getActions();
								if (acts != null) {
									for (String a : acts) {
										if (a != null && !a.isEmpty()) actions.add("s" + i + ": " + a);
									}
								}
							}
						}
					}
					if (!actions.isEmpty()) ci.put("childActions", actions);

					childInfoList.add(ci);
				}

				groupInfo.put("children", childInfoList);
				groups.add(groupInfo);
			}

			java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
			report.put("scannedRange", minGroup + "-" + maxGroup);
			report.put("groupsFound", groups.size());
			report.put("groups", groups);
			return report;
		});
	}

	/**
	 * Debug: exhaustively scan ALL visible widget roots and their children to find
	 * any widget containing clickable text options. This walks the entire widget tree
	 * rather than only checking known group IDs, ensuring we catch any dialog type.
	 */
	public java.util.Map<String, Object> debugScanDialogWidgets() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
			java.util.List<java.util.Map<String, Object>> foundWidgets = new java.util.ArrayList<>();

			Widget[] roots = client.getWidgetRoots();
			if (roots == null) {
				report.put("error", "No widget roots available");
				return report;
			}

			for (Widget root : roots) {
				if (root == null || root.isHidden()) continue;
				scanWidgetForText(root, foundWidgets, 0);
			}

			report.put("visibleWidgetsWithText", foundWidgets);
			report.put("count", foundWidgets.size());
			return report;
		});
	}

	/**
	 * Recursively scan a widget and its children for text content.
	 * Only includes widgets that have text themselves or have children with text.
	 */
	private void scanWidgetForText(Widget widget, java.util.List<java.util.Map<String, Object>> results, int depth) {
		if (widget == null || widget.isHidden() || depth > 4) return;

		java.util.List<String> childTexts = new java.util.ArrayList<>();

		Widget[] children = widget.getChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty()) {
					childTexts.add(child.getText());
				}
			}
		}

		Widget[] dynChildren = widget.getDynamicChildren();
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty()) {
					childTexts.add("[dyn] " + child.getText());
				}
			}
		}

		// Only report widgets that have 2+ text children (dialog-like)
		if (childTexts.size() >= 2) {
			java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
			entry.put("widgetId", String.format("0x%08x", widget.getId()));
			entry.put("groupId", widget.getId() >> 16);
			entry.put("childId", widget.getId() & 0xFFFF);

			String ownText = widget.getText();
			if (ownText != null && !ownText.isEmpty()) {
				entry.put("text", ownText);
			}

			entry.put("childTexts", childTexts);
			entry.put("depth", depth);
			results.add(entry);
		}

		// Recurse into children
		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null) {
			for (Widget child : staticChildren) {
				scanWidgetForText(child, results, depth + 1);
			}
		}
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				scanWidgetForText(child, results, depth + 1);
			}
		}
	}

	/**
	 * Select an option from a dialog/chat option menu (e.g., teleport destination picker).
	 * These appear as a list of clickable text options in the chatbox area.
	 * Checks multiple dialog widget types (standard chatmenu, skill multi, etc.).
	 * Case-insensitive substring match.
	 *
	 * @param optionText the text to match (e.g., "Rimmington", "Al Kharid")
	 * @param profile    mouse movement profile
	 * @return true if the option was found and clicked
	 */
	public boolean selectDialogOption(String optionText, MouseMovementProfile profile) {
		// Step 1: Find the dialog option widget on client thread
		Point optionPoint = runOnClientThread(() -> {
			Widget dialogWidget = findDialogWidget();
			if (dialogWidget == null) {
				log.warn("No dialog options menu is open (checked {} widget types)", DIALOG_WIDGET_IDS.length);
				return null;
			}

			java.util.List<Widget> optionWidgets = getDialogOptionWidgets(dialogWidget);
			if (optionWidgets.isEmpty()) {
				log.warn("Dialog widget found but has no text options");
				return null;
			}

			String search = optionText.toLowerCase();
			for (Widget child : optionWidgets) {
				String text = child.getText();
				if (text != null && text.toLowerCase().contains(search)) {
					log.debug("Found dialog option: '{}' at index {}", text, child.getIndex());
					return getWidgetScreenPoint(child);
				}
			}

			// Log available options for debugging
			StringBuilder available = new StringBuilder();
			for (Widget child : optionWidgets) {
				if (available.length() > 0) available.append(", ");
				available.append(child.getText());
			}
			log.warn("Dialog option '{}' not found. Available: [{}]", optionText, available);
			return null;
		});

		if (optionPoint == null) {
			return false;
		}

		// Step 2: Click off the client thread
		int jitterX = (int) ((Math.random() - 0.5) * 8);
		int jitterY = (int) ((Math.random() - 0.5) * 4);

		mouseMovement.moveAndClick(
			new java.awt.Point(optionPoint.getX() + jitterX, optionPoint.getY() + jitterY),
			profile
		);
		log.info("Selected dialog option '{}'", optionText);
		return true;
	}

	/**
	 * Wait for a dialog option menu to appear, then select an option.
	 *
	 * @param optionText the option text to click
	 * @param timeoutMs  how long to wait for the dialog to appear
	 * @param profile    mouse movement profile
	 * @return true if the option was selected
	 */
	public boolean waitAndSelectDialogOption(String optionText, int timeoutMs, MouseMovementProfile profile) {
		if (!waitForDialogOptions(timeoutMs)) {
			log.warn("Dialog options did not appear within {}ms", timeoutMs);
			return false;
		}
		sleep(50 + (int) (Math.random() * 80));
		return selectDialogOption(optionText, profile);
	}

	/**
	 * Check if a dialog options menu is currently open.
	 * Checks multiple known dialog widget types.
	 */
	public boolean isDialogOptionOpen() {
		return runOnClientThread(() -> findDialogWidget() != null);
	}

	/**
	 * Get the list of currently visible dialog options.
	 * Checks multiple known dialog widget types.
	 */
	public String[] getDialogOptions() {
		return runOnClientThread(() -> {
			Widget dialogWidget = findDialogWidget();
			if (dialogWidget == null) {
				return new String[0];
			}

			java.util.List<Widget> optionWidgets = getDialogOptionWidgets(dialogWidget);
			java.util.List<String> options = new java.util.ArrayList<>();
			for (Widget child : optionWidgets) {
				options.add(child.getText());
			}
			return options.toArray(new String[0]);
		});
	}

	/**
	 * Poll until a dialog options menu appears.
	 * Checks multiple known dialog widget types.
	 * On timeout, performs an exhaustive widget scan and logs all visible
	 * widgets with text children for debugging unknown dialog types.
	 */
	private boolean waitForDialogOptions(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			Boolean open = runOnClientThread(() -> findDialogWidget() != null);
			if (Boolean.TRUE.equals(open)) {
				return true;
			}
			sleep(100);
		}

		// On timeout, do an exhaustive scan and log everything visible for debugging
		log.warn("Dialog not found after {}ms — performing exhaustive widget scan...", timeoutMs);
		runOnClientThread(() -> {
			Widget[] roots = client.getWidgetRoots();
			if (roots == null) {
				log.warn("  No widget roots available");
				return null;
			}

			int visibleRoots = 0;
			for (Widget root : roots) {
				if (root == null || root.isHidden()) continue;
				visibleRoots++;
				logWidgetWithTextChildren(root, 0);
			}
			log.info("  Exhaustive scan complete: {} visible root widgets", visibleRoots);
			return null;
		});

		return false;
	}

	/**
	 * Log a widget and its children that have text content. Used for debugging
	 * to identify which widget type a popup dialog uses.
	 */
	private void logWidgetWithTextChildren(Widget widget, int depth) {
		if (widget == null || widget.isHidden() || depth > 3) return;

		java.util.List<String> childTexts = new java.util.ArrayList<>();

		Widget[] children = widget.getChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty()) {
					childTexts.add(child.getText());
				}
			}
		}

		Widget[] dynChildren = widget.getDynamicChildren();
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty()) {
					childTexts.add("[dyn] " + child.getText());
				}
			}
		}

		if (childTexts.size() >= 2) {
			log.info("  DIALOG CANDIDATE: widget=0x{} group={} child={} texts={}",
				String.format("%08x", widget.getId()),
				widget.getId() >> 16,
				widget.getId() & 0xFFFF,
				childTexts);
		}

		// Recurse
		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null) {
			for (Widget child : staticChildren) {
				logWidgetWithTextChildren(child, depth + 1);
			}
		}
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				logWidgetWithTextChildren(child, depth + 1);
			}
		}
	}

	// ===== RIGHT-CLICK SUB-MENU INTERACTION =====

	/**
	 * Hover over a menu option to trigger a sub-menu, then click an option from the sub-menu.
	 * Used for items like the construction cape where hovering over "Teleport" opens
	 * a sub-menu with destination options.
	 *
	 * @param parentOption the menu option to hover over (e.g., "Teleport")
	 * @param subOption    the sub-menu option to click (e.g., "Rimmington")
	 * @param profile      mouse movement profile
	 * @return true if the sub-menu option was found and clicked
	 */
	public boolean selectSubMenuOption(String parentOption, String subOption, MouseMovementProfile profile) {
		return selectSubMenuOption(parentOption, null, subOption, profile);
	}

	/**
	 * Hover over a menu option (with target filter) to trigger a sub-menu,
	 * then click an option from the sub-menu.
	 *
	 * Sub-menus in OSRS are accessed via MenuEntry.getSubMenu() — they are a
	 * separate Menu object attached to a parent MenuEntry, NOT additional entries
	 * in the main menu. When the mouse hovers over a parent entry that has a
	 * sub-menu, the game renders the sub-menu to the side.
	 *
	 * @param parentOption the menu option to hover over (e.g., "Teleport")
	 * @param parentTarget optional target filter for the parent entry
	 * @param subOption    the sub-menu option to click (e.g., "Rimmington")
	 * @param profile      mouse movement profile
	 * @return true if the sub-menu option was found and clicked
	 */
	public boolean selectSubMenuOption(String parentOption, String parentTarget, String subOption, MouseMovementProfile profile) {
		// Step 1: Find the parent menu entry position on client thread
		java.awt.Point hoverTarget = runOnClientThread(() -> {
			if (!client.isMenuOpen()) {
				log.warn("Cannot hover menu option '{}' - menu is not open", parentOption);
				return null;
			}

			Menu menu = client.getMenu();
			MenuEntry[] entries = menu.getMenuEntries();
			if (entries == null || entries.length == 0) {
				log.warn("Menu is open but has no entries");
				return null;
			}

			String searchOption = parentOption.toLowerCase();
			String searchTarget = parentTarget != null ? parentTarget.toLowerCase() : null;

			int matchIndex = -1;
			for (int i = 0; i < entries.length; i++) {
				MenuEntry entry = entries[i];
				String entryOption = stripTags(entry.getOption()).toLowerCase();
				String entryTarget = stripTags(entry.getTarget()).toLowerCase();

				boolean optionMatch = entryOption.contains(searchOption);
				boolean targetMatch = searchTarget == null || entryTarget.contains(searchTarget);

				if (optionMatch && targetMatch) {
					matchIndex = i;
					Menu sub = entry.getSubMenu();
					log.info("Found parent menu entry at index {}: '{}' -> '{}' (hasSubMenu={})",
						i, entry.getOption(), entry.getTarget(), sub != null);
					break;
				}
			}

			if (matchIndex < 0) {
				log.warn("Parent menu option '{}' not found in {} entries", parentOption, entries.length);
				return null;
			}

			// Calculate the hover position for this entry
			int menuX = menu.getMenuX();
			int menuY = menu.getMenuY();
			int menuWidth = menu.getMenuWidth();

			int visualRow = entries.length - 1 - matchIndex;
			int entryY = menuY + MENU_HEADER_HEIGHT + (visualRow * MENU_ENTRY_HEIGHT) + (MENU_ENTRY_HEIGHT / 2);
			int entryX = menuX + (menuWidth / 2);

			int jitterX = (int) ((Math.random() - 0.5) * Math.min(menuWidth * 0.3, 15));
			int jitterY = (int) ((Math.random() - 0.5) * 4);

			return new java.awt.Point(entryX + jitterX, entryY + jitterY);
		});

		if (hoverTarget == null) {
			return false;
		}

		// Step 2: Hover over the parent entry (move mouse without clicking)
		mouseMovement.moveMouse(hoverTarget, profile);
		log.info("Hovering over parent menu option '{}' at ({}, {})", parentOption, hoverTarget.x, hoverTarget.y);

		// Step 3: Wait for sub-menu to render after hover
		sleep(300 + (int) (Math.random() * 200));

		// Step 4: Find and click the sub-menu option.
		// Sub-menus are a separate Menu object on the parent MenuEntry (getSubMenu()),
		// with their own position/size and entries array.
		java.awt.Point subClickTarget = runOnClientThread(() -> {
			if (!client.isMenuOpen()) {
				log.warn("Menu closed while waiting for sub-menu");
				return null;
			}

			Menu menu = client.getMenu();
			MenuEntry[] entries = menu.getMenuEntries();
			if (entries == null || entries.length == 0) {
				return null;
			}

			// Find the parent entry again to access its sub-menu
			String searchOption = parentOption.toLowerCase();
			String searchTarget = parentTarget != null ? parentTarget.toLowerCase() : null;
			MenuEntry parentEntry = null;

			for (MenuEntry entry : entries) {
				String entryOption = stripTags(entry.getOption()).toLowerCase();
				String entryTarget = stripTags(entry.getTarget()).toLowerCase();
				boolean optionMatch = entryOption.contains(searchOption);
				boolean targetMatch = searchTarget == null || entryTarget.contains(searchTarget);
				if (optionMatch && targetMatch) {
					parentEntry = entry;
					break;
				}
			}

			if (parentEntry == null) {
				log.warn("Parent entry '{}' no longer in menu", parentOption);
				return null;
			}

			Menu subMenu = parentEntry.getSubMenu();
			if (subMenu == null) {
				log.warn("Parent entry '{}' has no sub-menu attached", parentOption);
				return null;
			}

			MenuEntry[] subEntries = subMenu.getMenuEntries();
			if (subEntries == null || subEntries.length == 0) {
				log.warn("Sub-menu for '{}' has no entries", parentOption);
				return null;
			}

			String searchSub = subOption.toLowerCase();
			int subMatchIndex = -1;

			for (int i = 0; i < subEntries.length; i++) {
				MenuEntry subEntry = subEntries[i];
				String subEntryOption = stripTags(subEntry.getOption()).toLowerCase();
				String subEntryTarget = stripTags(subEntry.getTarget()).toLowerCase();

				if (subEntryOption.contains(searchSub) || subEntryTarget.contains(searchSub)) {
					subMatchIndex = i;
					log.info("Found sub-menu option at index {}: '{}' -> '{}'",
						i, subEntry.getOption(), subEntry.getTarget());
					break;
				}
			}

			if (subMatchIndex < 0) {
				StringBuilder available = new StringBuilder();
				for (MenuEntry subEntry : subEntries) {
					if (available.length() > 0) available.append(", ");
					available.append(stripTags(subEntry.getOption())).append(" ")
						.append(stripTags(subEntry.getTarget()));
				}
				log.warn("Sub-menu option '{}' not found in {} sub-entries. Available: [{}]",
					subOption, subEntries.length, available);
				return null;
			}

			// Use the sub-menu's own position coordinates
			int subMenuX = subMenu.getMenuX();
			int subMenuY = subMenu.getMenuY();
			int subMenuWidth = subMenu.getMenuWidth();

			int visualRow = subEntries.length - 1 - subMatchIndex;
			int entryY = subMenuY + MENU_HEADER_HEIGHT + (visualRow * MENU_ENTRY_HEIGHT) + (MENU_ENTRY_HEIGHT / 2);
			int entryX = subMenuX + (subMenuWidth / 2);

			int jitterX = (int) ((Math.random() - 0.5) * Math.min(subMenuWidth * 0.4, 20));
			int jitterY = (int) ((Math.random() - 0.5) * 6);

			log.info("Sub-menu at ({},{}) size {}x{}, clicking entry at ({},{})",
				subMenuX, subMenuY, subMenuWidth, subMenu.getMenuHeight(),
				entryX + jitterX, entryY + jitterY);

			return new java.awt.Point(entryX + jitterX, entryY + jitterY);
		});

		if (subClickTarget == null) {
			log.warn("Sub-menu option '{}' not found after hovering over '{}'", subOption, parentOption);
			return false;
		}

		// Step 5: Click the sub-menu option
		sleep(50 + (int) (Math.random() * 80));
		mouseMovement.moveAndClick(subClickTarget, profile);
		log.info("Selected sub-menu option '{}' from parent '{}'", subOption, parentOption);
		return true;
	}

	/**
	 * Right-click at coordinates, hover over a parent option to open sub-menu, then click sub-option.
	 */
	public boolean rightClickHoverAndSelect(int x, int y, String parentOption, String subOption, MouseMovementProfile profile) {
		return rightClickHoverAndSelect(x, y, parentOption, null, subOption, profile);
	}

	/**
	 * Right-click at coordinates, hover over a parent option (with target filter) to open sub-menu,
	 * then click sub-option.
	 */
	public boolean rightClickHoverAndSelect(int x, int y, String parentOption, String parentTarget, String subOption, MouseMovementProfile profile) {
		// Move to position and right-click
		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
		sleep(50 + (int) (Math.random() * 80));
		mouseMovement.rightClick();

		// Wait for menu to open
		if (!waitForMenuOpen(2000)) {
			log.warn("Right-click menu did not open within timeout");
			return false;
		}

		sleep(50 + (int) (Math.random() * 80));

		// Hover + select from sub-menu
		return selectSubMenuOption(parentOption, parentTarget, subOption, profile);
	}

	/**
	 * Right-click an equipped item, hover over a menu option to open sub-menu, then select from sub-menu.
	 * Used for items like construction cape where hovering "Teleport" opens a destination sub-menu.
	 */
	public boolean rightClickEquipmentItemHoverAndSelect(String itemName, String parentOption, String subOption, MouseMovementProfile profile) {
		// Step 1: Find item slot coords on client thread
		Point itemPoint = runOnClientThread(() -> {
			EquipmentInventorySlot slot = findEquippedItemSlot(itemName);
			if (slot == null) return null;

			int widgetId = getEquipmentSlotWidgetId(slot);
			if (widgetId == -1) return null;

			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible", slot);
				return null;
			}
			return getWidgetScreenPoint(slotWidget);
		});

		if (itemPoint == null) {
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		return rightClickHoverAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			parentOption, null, subOption, profile
		);
	}

	/**
	 * Right-click an inventory item, hover over a menu option to open sub-menu, then select from sub-menu.
	 */
	public boolean rightClickInventoryItemHoverAndSelect(String itemName, String parentOption, String subOption, MouseMovementProfile profile) {
		// Step 1: Find the item widget's screen coords on the client thread
		Point itemPoint = runOnClientThread(() -> {
			Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
			if (inventoryWidget == null || inventoryWidget.isHidden()) {
				log.warn("Inventory widget not visible");
				return null;
			}

			Widget[] items = inventoryWidget.getDynamicChildren();
			if (items == null) return null;

			for (Widget item : items) {
				if (item != null && item.getName() != null && item.getName().contains(itemName)) {
					return getWidgetScreenPoint(item);
				}
			}

			log.warn("Inventory item '{}' not found", itemName);
			return null;
		});

		if (itemPoint == null) {
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * defaultJitter() * 2);
		int jitterY = (int) ((Math.random() - 0.5) * defaultJitter() * 2);

		return rightClickHoverAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			parentOption, null, subOption, profile
		);
	}

	// ===== RIGHT-CLICK MENU INTERACTION =====

	/** Height of the "Choose Option" header in the right-click menu */
	private static final int MENU_HEADER_HEIGHT = 19;
	/** Height of each menu entry row */
	private static final int MENU_ENTRY_HEIGHT = 15;

	/**
	 * Select a menu option by its option text (e.g., "Use", "Drop", "Examine").
	 * The right-click menu must already be open. Case-insensitive partial match.
	 *
	 * @param optionText the option to click (e.g., "Drop")
	 * @param profile    mouse movement profile
	 * @return true if the option was found and clicked
	 */
	public boolean selectMenuOption(String optionText, MouseMovementProfile profile) {
		return selectMenuOption(optionText, null, profile);
	}

	/**
	 * Select a menu option using exact match only (no substring fallback).
	 * Use this when substring matching could select the wrong action
	 * (e.g., "Light" matching "Re-light").
	 *
	 * @param optionText the exact option text to match (case-insensitive)
	 * @param targetText optional target text (substring match)
	 * @param profile    mouse movement profile
	 * @return true if the option was found and clicked
	 */
	public boolean selectMenuOptionExact(String optionText, String targetText, MouseMovementProfile profile) {
		return selectMenuOptionInternal(optionText, targetText, profile, true);
	}

	/**
	 * Select a menu option by option text and target text.
	 * Both are matched case-insensitively as substring. Useful when multiple
	 * entries share the same option (e.g., "Use" on different items).
	 *
	 * Reads menu state on the client thread, then clicks off it so the game
	 * can process the click event.
	 *
	 * @param optionText the option to click (e.g., "Use")
	 * @param targetText the target to match (e.g., "Lobster")
	 * @param profile    mouse movement profile
	 * @return true if the option was found and clicked
	 */
	public boolean selectMenuOption(String optionText, String targetText, MouseMovementProfile profile) {
		return selectMenuOptionInternal(optionText, targetText, profile, false);
	}

	private boolean selectMenuOptionInternal(String optionText, String targetText, MouseMovementProfile profile, boolean exactOnly) {
		// Step 1: Read menu state on client thread to find click coordinates
		java.awt.Point clickTarget = runOnClientThread(() -> {
			if (!client.isMenuOpen()) {
				log.warn("Cannot select menu option '{}' - menu is not open", optionText);
				return null;
			}

			Menu menu = client.getMenu();
			MenuEntry[] entries = menu.getMenuEntries();
			if (entries == null || entries.length == 0) {
				log.warn("Menu is open but has no entries");
				return null;
			}

			String searchOption = optionText.toLowerCase();
			String searchTarget = targetText != null ? targetText.toLowerCase() : null;

			// First pass: look for exact option match
			int matchIndex = -1;
			for (int i = 0; i < entries.length; i++) {
				MenuEntry entry = entries[i];
				String entryOption = stripTags(entry.getOption()).toLowerCase();
				String entryTarget = stripTags(entry.getTarget()).toLowerCase();

				boolean optionMatch = entryOption.equals(searchOption);
				boolean targetMatch = searchTarget == null || entryTarget.contains(searchTarget);

				if (optionMatch && targetMatch) {
					matchIndex = i;
					log.debug("Found exact menu match at index {}: '{}' -> '{}'", i, entry.getOption(), entry.getTarget());
					break;
				}
			}

			// Second pass: fall back to substring match if no exact match (unless exactOnly)
			if (matchIndex < 0 && !exactOnly) {
				for (int i = 0; i < entries.length; i++) {
					MenuEntry entry = entries[i];
					String entryOption = stripTags(entry.getOption()).toLowerCase();
					String entryTarget = stripTags(entry.getTarget()).toLowerCase();

					boolean optionMatch = entryOption.contains(searchOption);
					boolean targetMatch = searchTarget == null || entryTarget.contains(searchTarget);

					if (optionMatch && targetMatch) {
						matchIndex = i;
						log.debug("Found substring menu match at index {}: '{}' -> '{}'", i, entry.getOption(), entry.getTarget());
						break;
					}
				}
			}

			if (matchIndex < 0) {
				log.warn("Menu option '{}'{} not found in {} entries",
					optionText,
					targetText != null ? " (target: " + targetText + ")" : "",
					entries.length);
				return null;
			}

			// Calculate screen coordinates for this menu entry
			// Menu entries are displayed in reverse order: last entry in array is at the top
			int menuX = menu.getMenuX();
			int menuY = menu.getMenuY();
			int menuWidth = menu.getMenuWidth();

			int visualRow = entries.length - 1 - matchIndex;
			int entryY = menuY + MENU_HEADER_HEIGHT + (visualRow * MENU_ENTRY_HEIGHT) + (MENU_ENTRY_HEIGHT / 2);
			int entryX = menuX + (menuWidth / 2);

			int jitterX = (int) ((Math.random() - 0.5) * Math.min(menuWidth * 0.4, 20));
			int jitterY = (int) ((Math.random() - 0.5) * 6);

			return new java.awt.Point(entryX + jitterX, entryY + jitterY);
		});

		if (clickTarget == null) {
			return false;
		}

		// Step 2: Click off the client thread so the game can process it
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Selected menu option '{}' at ({}, {})", optionText, clickTarget.x, clickTarget.y);
		return true;
	}

	/**
	 * Right-click at coordinates and then select a menu option.
	 * Combines right-click + menu selection into a single operation.
	 *
	 * @param x          canvas X coordinate to right-click
	 * @param y          canvas Y coordinate to right-click
	 * @param option     the menu option to select
	 * @param profile    mouse movement profile
	 * @return true if the option was selected
	 */
	public boolean rightClickAndSelect(int x, int y, String option, MouseMovementProfile profile) {
		return rightClickAndSelect(x, y, option, null, profile);
	}

	/**
	 * Right-click at coordinates and then select a menu option with target filter.
	 */
	public boolean rightClickAndSelect(int x, int y, String option, String target, MouseMovementProfile profile) {
		// Move to position and right-click
		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
		sleep(50 + (int) (Math.random() * 80));
		mouseMovement.rightClick();

		// Poll for menu to actually open (game processes events on tick)
		if (!waitForMenuOpen(2000)) {
			log.warn("Right-click menu did not open within timeout");
			return false;
		}

		// Small human-like pause before selecting
		sleep(50 + (int) (Math.random() * 80));

		// Select the option
		return selectMenuOption(option, target, profile);
	}

	/**
	 * Wait for the right-click menu to open, polling every 50ms.
	 * @param timeoutMs maximum time to wait
	 * @return true if menu opened, false if timed out
	 */
	private boolean waitForMenuOpen(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!client.isMenuOpen()) {
			if (System.currentTimeMillis() > deadline) {
				return false;
			}
			sleep(50);
		}
		return true;
	}

	/**
	 * Dismiss an open right-click menu by sending Escape key.
	 */
	private void dismissMenu() {
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null) return;

		java.awt.event.KeyEvent press = new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_PRESSED,
			System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED
		);
		java.awt.event.KeyEvent release = new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_RELEASED,
			System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED
		);
		canvas.dispatchEvent(press);
		sleep(30);
		canvas.dispatchEvent(release);
		sleep(100);
	}

	/**
	 * Check if a skill guide dialog is currently open (either V1 or V2).
	 */
	public boolean isSkillGuideOpen() {
		return runOnClientThread(() -> {
			// Check SkillGuideV2 (group 860)
			Widget v2 = client.getWidget(InterfaceID.SKILL_GUIDE_V2, 0);
			if (v2 != null && !v2.isHidden()) return true;
			// Check SkillGuide V1 (group 214)
			Widget v1 = client.getWidget(InterfaceID.SKILL_GUIDE, 0);
			return v1 != null && !v1.isHidden();
		});
	}

	/**
	 * Close the skill guide dialog if it's open.
	 * Tries SkillGuideV2 close button first, then V1, then falls back to Escape.
	 */
	public boolean closeSkillGuide(MouseMovementProfile profile) {
		// Try V2 close button (group 860, child 4)
		Boolean v2Open = runOnClientThread(() -> {
			Widget v2 = client.getWidget(InterfaceID.SKILL_GUIDE_V2, 0);
			return v2 != null && !v2.isHidden();
		});
		if (Boolean.TRUE.equals(v2Open)) {
			log.info("Closing SkillGuideV2 dialog");
			return clickWidgetByPackedId(InterfaceID.SkillGuideV2.CLOSE, profile);
		}

		// Try V1 close button (group 214, child 30)
		Boolean v1Open = runOnClientThread(() -> {
			Widget v1 = client.getWidget(InterfaceID.SKILL_GUIDE, 0);
			return v1 != null && !v1.isHidden();
		});
		if (Boolean.TRUE.equals(v1Open)) {
			log.info("Closing SkillGuide V1 dialog");
			return clickWidgetByPackedId(InterfaceID.SkillGuide.CLOSE, profile);
		}

		log.info("No skill guide dialog open");
		return false;
	}

	/**
	 * Right-click a widget and select a menu option.
	 */
	public boolean rightClickWidgetAndSelect(Widget widget, String option, MouseMovementProfile profile) {
		return rightClickWidgetAndSelect(widget, option, null, profile);
	}

	/**
	 * Right-click a widget and select a menu option with target filter.
	 * Gets widget coordinates on client thread, then does the right-click + wait + select
	 * OFF the client thread so the game can process the click event.
	 */
	public boolean rightClickWidgetAndSelect(Widget widget, String option, String target, MouseMovementProfile profile) {
		// Step 1: Get widget coordinates on client thread
		Point screenPoint = runOnClientThread(() -> {
			if (widget == null || widget.isHidden()) {
				log.warn("Widget is null or hidden");
				return null;
			}
			return getWidgetScreenPoint(widget);
		});

		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for widget");
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		// Step 2: Right-click + wait for menu + select — all off the client thread
		return rightClickAndSelect(
			screenPoint.getX() + jitterX,
			screenPoint.getY() + jitterY,
			option, target, profile
		);
	}

	/**
	 * Right-click an inventory item and select a specific menu option (e.g., "Drop", "Use", "Examine").
	 * Looks up the item widget on the client thread, then does right-click + select off it.
	 */
	public boolean rightClickInventoryItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		// Step 1: Find the item widget's screen coords on the client thread
		Point itemPoint = runOnClientThread(() -> {
			Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
			if (inventoryWidget == null || inventoryWidget.isHidden()) {
				log.warn("Inventory widget not visible");
				return null;
			}

			Widget[] items = inventoryWidget.getDynamicChildren();
			if (items == null) {
				return null;
			}

			for (Widget item : items) {
				if (item != null && item.getName() != null && item.getName().contains(itemName)) {
					return getWidgetScreenPoint(item);
				}
			}

			log.warn("Inventory item '{}' not found", itemName);
			return null;
		});

		if (itemPoint == null) {
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * defaultJitter() * 2);
		int jitterY = (int) ((Math.random() - 0.5) * defaultJitter() * 2);

		// Step 2: Right-click + wait for menu + select — off the client thread
		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, itemName, MouseMovementProfile.NORMAL
		);
	}

	private int defaultJitter() {
		return MouseMovementProfile.NORMAL.jitterRadius;
	}

	/**
	 * Get all currently visible menu entries (menu must be open).
	 * Useful for inspecting what options are available.
	 */
	public String[] getMenuOptions() {
		return runOnClientThread(() -> {
			if (!client.isMenuOpen()) {
				return new String[0];
			}

			MenuEntry[] entries = client.getMenu().getMenuEntries();
			if (entries == null) {
				return new String[0];
			}

			String[] options = new String[entries.length];
			for (int i = 0; i < entries.length; i++) {
				options[i] = stripTags(entries[i].getOption()) + " " + stripTags(entries[i].getTarget());
			}
			return options;
		});
	}

	/**
	 * Strip color/formatting tags from menu text (e.g., {@code <col=ff9040>Lobster</col>} → {@code Lobster})
	 */
	private static String stripTags(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("<[^>]+>", "").trim();
	}

	// ===== PLAYER TAB INTERACTION =====

	/**
	 * Open a tab in the player menu.
	 * Automatically detects viewport mode (fixed / resizable-classic / resizable-modern).
	 * This method can be called from any thread.
	 */
	public boolean openPlayerTab(PlayerTab tab, MouseMovementProfile profile) {
		// Check if this tab is already active — don't click if it is
		Boolean alreadyOpen = runOnClientThread(() -> {
			int currentTab = client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
			return currentTab == tab.ordinal();
		});
		if (Boolean.TRUE.equals(alreadyOpen)) {
			log.debug("Tab {} is already active, skipping click", tab);
			return true;
		}

		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget tabWidget = findTabWidget(tab);
			if (tabWidget == null) {
				log.warn("Player tab {} widget not found in any viewport mode", tab);
				return null;
			}

			log.debug("Found tab {} widget: id={}", tab, tabWidget.getId());
			return getWidgetClickPoint(tabWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Find tab widget across all viewport modes:
	 *   1. Fixed (classic)
	 *   2. Resizable (classic)
	 *   3. Resizable bottom-line (modern)
	 */
	private Widget findTabWidget(PlayerTab tab) {
		// Try fixed viewport
		Widget widget = client.getWidget(tab.getFixedViewportWidget());
		if (widget != null && !widget.isHidden()) {
			log.debug("Using fixed viewport for tab {}", tab);
			return widget;
		}

		// Try resizable viewport (classic)
		widget = client.getWidget(tab.getResizableViewportWidget());
		if (widget != null && !widget.isHidden()) {
			log.debug("Using resizable classic viewport for tab {}", tab);
			return widget;
		}

		// Try resizable viewport bottom-line (modern) using packed widget ID
		widget = client.getWidget(tab.getBottomLinePackedId());
		if (widget != null && !widget.isHidden()) {
			log.debug("Using resizable modern (bottom-line) viewport for tab {}", tab);
			return widget;
		}

		log.warn("Tab {} not found in any viewport mode (fixed/resizable-classic/resizable-modern)", tab);
		return null;
	}

	/**
	 * Open a player tab by name string.
	 * Supports aliases like "WORN_EQUIPMENT", "WORN", "SKILLS", "SPELLBOOK", etc.
	 */
	public boolean openPlayerTab(String tabName, MouseMovementProfile profile) {
		PlayerTab tab = PlayerTab.fromString(tabName);
		if (tab == null) {
			log.warn("Unknown tab name: '{}'", tabName);
			return false;
		}
		return openPlayerTab(tab, profile);
	}

	// Convenience methods for common tabs

	public boolean openEquipment(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.EQUIPMENT, profile);
	}

	public boolean openStats(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.STATS, profile);
	}

	public boolean openQuests(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.QUESTS, profile);
	}

	public boolean openPrayers(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.PRAYER, profile);
	}

	public boolean openMagic(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.MAGIC, profile);
	}

	public boolean openInventory(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.INVENTORY, profile);
	}

	public boolean openCombat(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.COMBAT, profile);
	}

	public boolean openFriends(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.FRIENDS, profile);
	}

	public boolean openOptions(MouseMovementProfile profile) {
		return openPlayerTab(PlayerTab.OPTIONS, profile);
	}

	// ===== PRAYER INTERACTION =====

	/**
	 * Toggle a prayer by widget ID
	 */
	public boolean togglePrayerByWidgetId(int groupId, int childId, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget prayer = client.getWidget(groupId, childId);
			if (prayer == null || prayer.isHidden()) {
				log.warn("Prayer widget {}.{} not found or hidden", groupId, childId);
				return null;
			}

			log.info("Toggling prayer widget {}.{}", groupId, childId);
			return getWidgetClickPoint(prayer, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	// ===== SKILLS INTERACTION =====

	/**
	 * Click a skill by widget ID
	 */
	public boolean clickSkillByWidgetId(int groupId, int childId, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget skill = client.getWidget(groupId, childId);
			if (skill == null || skill.isHidden()) {
				log.warn("Skill widget {}.{} not found or hidden", groupId, childId);
				return null;
			}

			log.info("Clicking skill widget {}.{}", groupId, childId);
			return getWidgetClickPoint(skill, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	// ===== GENERAL WIDGET INTERACTION =====

	/**
	 * Click any widget by WidgetInfo
	 */
	public boolean clickWidgetByInfo(WidgetInfo widgetInfo, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget widget = client.getWidget(widgetInfo);
			if (widget == null) {
				log.warn("Widget {} not found", widgetInfo);
				return null;
			}
			return getWidgetClickPoint(widget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click a widget by its packed ID (group << 16 | child).
	 */
	public boolean clickWidgetByPackedId(int packedId, MouseMovementProfile profile) {
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) {
				log.warn("Widget {}.{} not found or hidden", groupId, childId);
				return null;
			}
			return getWidgetClickPoint(widget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click a specific widget (thread-safe wrapper).
	 * Gets coordinates on client thread, moves/clicks off it so the game can render.
	 */
	public boolean clickWidget(Widget widget, MouseMovementProfile profile) {
		return clickWidgetSafe(widget, profile);
	}

	/**
	 * Safe widget click: reads coordinates on client thread, performs mouse
	 * movement and click OFF the client thread so the game keeps rendering.
	 * This is the standard way to click any widget.
	 */
	private boolean clickWidgetSafe(Widget widget, MouseMovementProfile profile) {
		// Step 1: Get click target coordinates on client thread
		java.awt.Point clickTarget = runOnClientThread(() -> getWidgetClickPoint(widget, profile));
		if (clickTarget == null) {
			return false;
		}

		// Step 2: Move and click OFF the client thread (allows game to render during movement)
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Clicked widget at ({}, {})", clickTarget.x, clickTarget.y);
		return true;
	}

	/**
	 * Get the click target point for a widget (must be called on client thread).
	 * Returns null if the widget is null, hidden, or has no screen position.
	 */
	private java.awt.Point getWidgetClickPoint(Widget widget, MouseMovementProfile profile) {
		if (widget == null || widget.isHidden()) {
			log.warn("Widget is null or hidden");
			return null;
		}

		Point screenPoint = getWidgetScreenPoint(widget);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for widget");
			return null;
		}

		// Add jitter from profile
		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		return new java.awt.Point(screenPoint.getX() + jitterX, screenPoint.getY() + jitterY);
	}

	// ===== BANK INTERACTION =====

	/**
	 * Check if the bank interface is currently open.
	 */
	public boolean isBankOpen() {
		return runOnClientThread(() -> {
			Widget bankContainer = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			return bankContainer != null && !bankContainer.isHidden();
		});
	}

	/**
	 * Close the bank interface by clicking the close button (top-right X).
	 */
	public boolean closeBank(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget bankFrame = client.getWidget(InterfaceID.Bankmain.FRAME);
			if (bankFrame == null || bankFrame.isHidden()) {
				log.warn("Bank is not open");
				return null;
			}

			// The close button is child 11 of the FRAME widget
			Widget[] children = bankFrame.getDynamicChildren();
			if (children != null && children.length > 11) {
				Widget closeButton = children[11];
				if (closeButton != null && !closeButton.isHidden()) {
					return getWidgetClickPoint(closeButton, profile);
				}
			}

			// Fallback: try static children
			Widget[] staticChildren = bankFrame.getStaticChildren();
			if (staticChildren != null && staticChildren.length > 11) {
				Widget closeButton = staticChildren[11];
				if (closeButton != null && !closeButton.isHidden()) {
					return getWidgetClickPoint(closeButton, profile);
				}
			}

			log.warn("Could not find bank close button");
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Get all items currently visible in the bank.
	 * Returns a list of maps with id, name, quantity, and slot index.
	 */
	public java.util.List<java.util.Map<String, Object>> getBankItems() {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();

			ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
			if (bankContainer == null) {
				log.warn("Bank container not available (is the bank open?)");
				return items;
			}

			Item[] bankItems = bankContainer.getItems();
			for (int i = 0; i < bankItems.length; i++) {
				Item item = bankItems[i];
				if (item.getId() == -1 || item.getId() == 6512) { // 6512 = placeholder
					continue;
				}

				ItemComposition comp = client.getItemDefinition(item.getId());
				java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
				entry.put("slot", i);
				entry.put("id", item.getId());
				entry.put("name", comp.getName());
				entry.put("quantity", item.getQuantity());
				items.add(entry);
			}

			return items;
		});
	}

	/**
	 * Find a bank item by name. Returns the widget for that item, or null.
	 * Must be called on client thread.
	 */
	private Widget findBankItemWidget(String itemName) {
		Widget bankItemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bankItemContainer == null || bankItemContainer.isHidden()) {
			log.warn("Bank items widget not visible");
			return null;
		}

		Widget[] children = bankItemContainer.getDynamicChildren();
		if (children == null) {
			return null;
		}

		String search = itemName.toLowerCase();

		// First pass: exact name match (case-insensitive)
		for (Widget child : children) {
			if (child == null || child.isHidden()) continue;
			int itemId = child.getItemId();
			if (itemId == -1 || itemId == 6512) continue;

			ItemComposition comp = client.getItemDefinition(itemId);
			if (comp.getName().toLowerCase().equals(search)) {
				log.info("Found bank item '{}' (exact match) at index {}",
					comp.getName(), child.getIndex());
				return child;
			}
		}

		// Second pass: partial match (contains) as fallback
		for (Widget child : children) {
			if (child == null || child.isHidden()) continue;
			int itemId = child.getItemId();
			if (itemId == -1 || itemId == 6512) continue;

			ItemComposition comp = client.getItemDefinition(itemId);
			if (comp.getName().toLowerCase().contains(search)) {
				log.info("Found bank item '{}' (partial match: '{}') at index {}",
					itemName, comp.getName(), child.getIndex());
				return child;
			}
		}

		log.warn("Bank item '{}' not found in visible items", itemName);
		return null;
	}

	/**
	 * Check if a bank item widget is within the visible scroll area.
	 * Must be called on client thread.
	 */
	private boolean isBankItemVisible(Widget item) {
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (container == null) return false;

		Rectangle containerBounds = container.getBounds();
		Rectangle itemBounds = item.getBounds();
		if (containerBounds == null || itemBounds == null) return false;

		// Item is visible if its vertical center is within the container bounds
		int itemCenterY = (int)(itemBounds.getY() + itemBounds.getHeight() / 2);
		return itemCenterY >= containerBounds.getY() && itemCenterY <= containerBounds.getY() + containerBounds.getHeight();
	}

	/**
	 * Scroll a bank item into view by dispatching mouse wheel events on the bank container.
	 * Scrolls until the item is near the center of the visible area so that right-click
	 * menus have room to open without going off-screen.
	 * Returns true if the item is now visible and centered.
	 */
	private boolean scrollBankItemIntoView(Widget item, MouseMovementProfile profile) {
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (container == null) return false;

		// Move mouse over the bank item container area first
		Rectangle containerBounds = runOnClientThread(() -> {
			Widget c = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
			return c != null ? c.getBounds() : null;
		});
		if (containerBounds == null) return false;

		int centerX = (int)(containerBounds.getX() + containerBounds.getWidth() / 2);
		int centerY = (int)(containerBounds.getY() + containerBounds.getHeight() / 2);
		mouseMovement.moveMouse(new java.awt.Point(centerX, centerY), profile);
		sleep(100 + (int)(Math.random() * 100));

		// Scroll until the item is near the vertical center of the container.
		// We use a "center zone" — the middle 60% of the container height.
		// This ensures right-click menus have room to open above or below.
		for (int attempt = 0; attempt < 50; attempt++) {
			int scrollDirection = runOnClientThread(() -> {
				Widget c = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
				if (c == null) return 0;
				Rectangle cBounds = c.getBounds();
				Rectangle iBounds = item.getBounds();
				if (cBounds == null || iBounds == null) return 0;

				int itemCenterY = (int)(iBounds.getY() + iBounds.getHeight() / 2);
				int containerTop = (int)cBounds.getY();
				int containerHeight = (int)cBounds.getHeight();

				// Define center zone: 20% to 80% of container height
				int zoneTop = containerTop + (int)(containerHeight * 0.20);
				int zoneBottom = containerTop + (int)(containerHeight * 0.80);

				if (itemCenterY > zoneBottom) return 1;   // Item below center zone, scroll down
				if (itemCenterY < zoneTop) return -1;      // Item above center zone, scroll up
				return 0;                                   // Item is in the center zone
			});

			if (scrollDirection == 0) {
				sleep(100);
				return true;
			}

			// Dispatch mouse wheel event
			java.awt.Canvas canvas = client.getCanvas();
			if (canvas == null) return false;

			int wheelRotation = scrollDirection * 3; // Scroll 3 notches at a time
			canvas.dispatchEvent(new java.awt.event.MouseWheelEvent(
				canvas,
				java.awt.event.MouseWheelEvent.MOUSE_WHEEL,
				System.currentTimeMillis(),
				0,
				centerX, centerY,
				0, false,
				java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
				3, wheelRotation
			));

			sleep(80 + (int)(Math.random() * 60));
		}

		// Fallback: even if not perfectly centered, check if at least visible
		Boolean visible = runOnClientThread(() -> isBankItemVisible(item));
		if (Boolean.TRUE.equals(visible)) {
			return true;
		}

		log.warn("Could not scroll bank item into view after 50 scroll attempts");
		return false;
	}

	/**
	 * Click a bank item by name (left-click = withdraw default quantity).
	 * Scrolls the item into view if needed.
	 */
	public boolean clickBankItem(String itemName, MouseMovementProfile profile) {
		// Find the item widget on client thread
		Widget item = runOnClientThread(() -> findBankItemWidget(itemName));
		if (item == null) return false;

		// Check if visible, scroll if needed
		Boolean visible = runOnClientThread(() -> isBankItemVisible(item));
		if (!Boolean.TRUE.equals(visible)) {
			log.info("Bank item '{}' not in visible area, scrolling...", itemName);
			if (!scrollBankItemIntoView(item, profile)) {
				return false;
			}
		}

		// Get screen point on client thread, then click off it
		java.awt.Point clickTarget = runOnClientThread(() -> getWidgetClickPoint(item, profile));
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click a bank item and select a specific option (e.g., "Withdraw-1", "Withdraw-5",
	 * "Withdraw-10", "Withdraw-All", "Withdraw-X", "Examine").
	 * Scrolls the item into view if needed.
	 */
	public boolean rightClickBankItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		// Find the item widget on client thread
		Widget item = runOnClientThread(() -> findBankItemWidget(itemName));
		if (item == null) return false;

		// Check if visible, scroll if needed
		Boolean visible = runOnClientThread(() -> isBankItemVisible(item));
		if (!Boolean.TRUE.equals(visible)) {
			log.info("Bank item '{}' not in visible area, scrolling...", itemName);
			if (!scrollBankItemIntoView(item, profile)) {
				return false;
			}
		}

		// Get screen point after scrolling
		Point itemPoint = runOnClientThread(() -> getWidgetScreenPoint(item));
		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, null, profile
		);
	}

	/**
	 * Find a bank inventory item (bottom panel) by name.
	 * Must be called on client thread.
	 */
	private Widget findBankInventoryItemWidget(String itemName) {
		Widget bankInvWidget = client.getWidget(InterfaceID.Bankside.ITEMS);
		if (bankInvWidget == null || bankInvWidget.isHidden()) {
			log.warn("Bank inventory widget not visible");
			return null;
		}

		Widget[] children = bankInvWidget.getDynamicChildren();
		if (children == null) return null;

		String search = itemName.toLowerCase();
		for (Widget child : children) {
			if (child == null || child.isHidden()) continue;
			int itemId = child.getItemId();
			if (itemId == -1) continue;

			ItemComposition comp = client.getItemDefinition(itemId);
			if (comp.getName().toLowerCase().contains(search)) {
				log.info("Found bank inventory item '{}' (actual: '{}') at index {}",
					itemName, comp.getName(), child.getIndex());
				return child;
			}
		}

		log.warn("Bank inventory item '{}' not found", itemName);
		return null;
	}

	/**
	 * Click an item in the bank inventory panel (deposit it with default quantity).
	 */
	public boolean clickBankInventoryItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget item = findBankInventoryItemWidget(itemName);
			if (item == null) return null;
			return getWidgetClickPoint(item, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click a bank inventory item and select an option (e.g., "Deposit-1", "Deposit-All").
	 */
	public boolean rightClickBankInventoryItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		Point itemPoint = runOnClientThread(() -> {
			Widget item = findBankInventoryItemWidget(itemName);
			if (item == null) return null;
			return getWidgetScreenPoint(item);
		});

		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);

		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, null, profile
		);
	}

	/**
	 * Click the "Deposit inventory" button in the bank interface.
	 */
	public boolean depositInventory(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget depositInv = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
			if (depositInv == null || depositInv.isHidden()) {
				log.warn("Deposit inventory button not visible");
				return null;
			}
			return getWidgetClickPoint(depositInv, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click the "Deposit worn items" button in the bank interface.
	 */
	public boolean depositEquipment(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget depositWorn = client.getWidget(InterfaceID.Bankmain.DEPOSITWORN);
			if (depositWorn == null || depositWorn.isHidden()) {
				log.warn("Deposit worn items button not visible");
				return null;
			}
			return getWidgetClickPoint(depositWorn, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click a bank tab by index (0 = main/all tab, 1-9 = tabs 1-9).
	 * The bank must be open.
	 */
	public boolean clickBankTab(int tabIndex, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget tabContainer = client.getWidget(InterfaceID.Bankmain.TABS);
			if (tabContainer == null || tabContainer.isHidden()) {
				log.warn("Bank tab container not visible");
				return null;
			}

			Widget[] children = tabContainer.getDynamicChildren();
			if (children == null) {
				log.warn("Bank tab container has no children");
				return null;
			}

			// Tab widgets are dynamic children of the TABS container.
			// Tab 0 (all items) is at index 10, tabs 1-9 start at index 11.
			// Each tab widget takes up 1 slot.
			int widgetIndex = 10 + tabIndex;
			if (widgetIndex >= children.length) {
				log.warn("Bank tab index {} out of range (max children: {})", tabIndex, children.length);
				return null;
			}

			Widget tabWidget = children[widgetIndex];
			if (tabWidget == null || tabWidget.isHidden()) {
				log.warn("Bank tab {} widget is null or hidden", tabIndex);
				return null;
			}

			log.info("Clicking bank tab {}", tabIndex);
			return getWidgetClickPoint(tabWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Set the bank withdraw quantity mode by clicking the appropriate button.
	 * Valid values: 1, 5, 10, -1 (X), 0 (All)
	 */
	public boolean setBankQuantity(int quantity, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			int widgetId;
			switch (quantity) {
				case 1:  widgetId = InterfaceID.Bankmain.QUANTITY1; break;
				case 5:  widgetId = InterfaceID.Bankmain.QUANTITY5; break;
				case 10: widgetId = InterfaceID.Bankmain.QUANTITY10; break;
				case -1: widgetId = InterfaceID.Bankmain.QUANTITYX; break;
				case 0:  widgetId = InterfaceID.Bankmain.QUANTITYALL; break;
				default:
					log.warn("Invalid bank quantity: {} (valid: 1, 5, 10, -1 for X, 0 for All)", quantity);
					return null;
			}

			Widget quantityWidget = client.getWidget(widgetId);
			if (quantityWidget == null || quantityWidget.isHidden()) {
				log.warn("Bank quantity button not visible for quantity={}", quantity);
				return null;
			}

			log.info("Setting bank quantity to {}", quantity == -1 ? "X" : quantity == 0 ? "All" : quantity);
			return getWidgetClickPoint(quantityWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Toggle the bank note/item withdrawal mode.
	 */
	public boolean toggleBankNoteMode(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget noteWidget = client.getWidget(InterfaceID.Bankmain.NOTE);
			if (noteWidget == null || noteWidget.isHidden()) {
				log.warn("Bank note toggle button not visible");
				return null;
			}
			log.info("Toggling bank note mode");
			return getWidgetClickPoint(noteWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Click the bank search button to activate search mode.
	 */
	public boolean clickBankSearch(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget searchWidget = client.getWidget(InterfaceID.Bankmain.SEARCH);
			if (searchWidget == null || searchWidget.isHidden()) {
				log.warn("Bank search button not visible");
				return null;
			}
			log.info("Clicking bank search button");
			return getWidgetClickPoint(searchWidget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Search for an item in the bank by typing text into the search box.
	 * This clicks the search button, waits for the input to activate, then types the query.
	 */
	public boolean bankSearch(String query, MouseMovementProfile profile) {
		if (!isBankOpen()) {
			log.warn("Bank is not open");
			return false;
		}

		// Click the search button
		if (!clickBankSearch(profile)) {
			return false;
		}

		// Wait for search input to activate
		sleep(400 + (int)(Math.random() * 200));

		// Type the search query by sending key events
		typeText(query);

		// Wait for results to filter
		sleep(300 + (int)(Math.random() * 200));

		log.info("Bank search for '{}'", query);
		return true;
	}

	/**
	 * Type text into the currently focused input (bank search, withdraw-X dialog, etc.)
	 * by dispatching KeyEvent objects to the game canvas.
	 */
	public void typeText(String text) {
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.warn("Cannot type text - canvas is null");
			return;
		}

		for (char c : text.toCharArray()) {
			// KEY_TYPED event for each character
			canvas.dispatchEvent(new java.awt.event.KeyEvent(
				canvas,
				java.awt.event.KeyEvent.KEY_TYPED,
				System.currentTimeMillis(),
				0,
				java.awt.event.KeyEvent.VK_UNDEFINED,
				c
			));
			sleep(30 + (int)(Math.random() * 50));
		}
	}

	/**
	 * Press the Enter key (used to confirm withdraw-X amounts, search, etc.).
	 */
	public void pressEnter() {
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null) return;

		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas,
			java.awt.event.KeyEvent.KEY_PRESSED,
			System.currentTimeMillis(),
			0,
			java.awt.event.KeyEvent.VK_ENTER,
			'\n'
		));
		sleep(30 + (int)(Math.random() * 30));
		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas,
			java.awt.event.KeyEvent.KEY_RELEASED,
			System.currentTimeMillis(),
			0,
			java.awt.event.KeyEvent.VK_ENTER,
			'\n'
		));
	}

	/**
	 * Withdraw a specific quantity of an item.
	 * Right-clicks the item once, then checks the menu:
	 *   - If "Withdraw-{amount}" exists (last X matches), clicks it directly.
	 *   - Otherwise clicks "Withdraw-X" and types the amount in the chatbox.
	 */
	public boolean withdrawX(String itemName, int amount, MouseMovementProfile profile) {
		// Find and right-click the bank item
		Widget item = runOnClientThread(() -> findBankItemWidget(itemName));
		if (item == null) return false;

		Boolean visible = runOnClientThread(() -> isBankItemVisible(item));
		if (!Boolean.TRUE.equals(visible)) {
			log.info("Bank item '{}' not in visible area, scrolling...", itemName);
			if (!scrollBankItemIntoView(item, profile)) {
				return false;
			}
		}

		Point itemPoint = runOnClientThread(() -> getWidgetScreenPoint(item));
		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int x = itemPoint.getX() + jitterX;
		int y = itemPoint.getY() + jitterY;

		// Right-click the item
		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
		sleep(50 + (int)(Math.random() * 80));
		mouseMovement.rightClick();

		if (!waitForMenuOpen(2000)) {
			log.warn("Right-click menu did not open for bank item '{}'", itemName);
			return false;
		}
		sleep(50 + (int)(Math.random() * 80));

		// Try the direct "Withdraw-{amount}" option first
		String directOption = "Withdraw-" + amount;
		if (selectMenuOption(directOption, null, profile)) {
			log.info("Withdrew {} x {} via direct menu option", amount, itemName);
			return true;
		}

		// Menu is still open — try "Withdraw-X" and type the amount
		if (selectMenuOption("Withdraw-X", null, profile)) {
			sleep(600 + (int)(Math.random() * 300));
			typeText(String.valueOf(amount));
			sleep(100 + (int)(Math.random() * 100));
			pressEnter();
			log.info("Withdrew {} x {} via Withdraw-X input", amount, itemName);
			return true;
		}

		// Neither option found — dismiss menu
		log.warn("Could not find withdraw option for {} x '{}'", amount, itemName);
		dismissMenu();
		return false;
	}

	/**
	 * Deposit a specific quantity of an item from the bank inventory panel.
	 * Right-clicks the item once, then checks the menu:
	 *   - If "Deposit-{amount}" exists (last X matches), clicks it directly.
	 *   - Otherwise clicks "Deposit-X" and types the amount in the chatbox.
	 */
	public boolean depositX(String itemName, int amount, MouseMovementProfile profile) {
		// Find and right-click the bank inventory item
		Widget item = runOnClientThread(() -> findBankInventoryItemWidget(itemName));
		if (item == null) return false;

		Point itemPoint = runOnClientThread(() -> getWidgetScreenPoint(item));
		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int jitterY = (int) ((Math.random() - 0.5) * profile.jitterRadius * 2);
		int x = itemPoint.getX() + jitterX;
		int y = itemPoint.getY() + jitterY;

		mouseMovement.moveMouse(new java.awt.Point(x, y), profile);
		sleep(50 + (int)(Math.random() * 80));
		mouseMovement.rightClick();

		if (!waitForMenuOpen(2000)) {
			log.warn("Right-click menu did not open for bank inventory item '{}'", itemName);
			return false;
		}
		sleep(50 + (int)(Math.random() * 80));

		// Try the direct "Deposit-{amount}" option first
		String directOption = "Deposit-" + amount;
		if (selectMenuOption(directOption, null, profile)) {
			log.info("Deposited {} x {} via direct menu option", amount, itemName);
			return true;
		}

		// Menu is still open — try "Deposit-X" and type the amount
		if (selectMenuOption("Deposit-X", null, profile)) {
			sleep(600 + (int)(Math.random() * 300));
			typeText(String.valueOf(amount));
			sleep(100 + (int)(Math.random() * 100));
			pressEnter();
			log.info("Deposited {} x {} via Deposit-X input", amount, itemName);
			return true;
		}

		log.warn("Could not find deposit option for {} x '{}'", amount, itemName);
		dismissMenu();
		return false;
	}

	/**
	 * Get debug info about the bank widget state.
	 */
	public java.util.Map<String, Object> getBankDebugInfo() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();

			Widget bankUniverse = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			info.put("bankOpen", bankUniverse != null && !bankUniverse.isHidden());

			Widget bankItems = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (bankItems != null && !bankItems.isHidden()) {
				Widget[] children = bankItems.getDynamicChildren();
				info.put("visibleItemWidgets", children != null ? children.length : 0);
			}

			ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
			if (bankContainer != null) {
				Item[] items = bankContainer.getItems();
				int count = 0;
				for (Item item : items) {
					if (item.getId() != -1 && item.getId() != 6512) count++;
				}
				info.put("totalBankItems", count);
				info.put("totalBankSlots", items.length);
			}

			Widget bankInv = client.getWidget(InterfaceID.Bankside.ITEMS);
			info.put("bankInventoryVisible", bankInv != null && !bankInv.isHidden());

			// Check quantity buttons
			for (int q : new int[]{1, 5, 10}) {
				int wid;
				switch (q) {
					case 1: wid = InterfaceID.Bankmain.QUANTITY1; break;
					case 5: wid = InterfaceID.Bankmain.QUANTITY5; break;
					default: wid = InterfaceID.Bankmain.QUANTITY10; break;
				}
				Widget w = client.getWidget(wid);
				info.put("quantity" + q + "Visible", w != null && !w.isHidden());
			}

			Widget noteW = client.getWidget(InterfaceID.Bankmain.NOTE);
			info.put("noteButtonVisible", noteW != null && !noteW.isHidden());

			Widget searchW = client.getWidget(InterfaceID.Bankmain.SEARCH);
			info.put("searchButtonVisible", searchW != null && !searchW.isHidden());

			return info;
		});
	}

	// ===== TASK SEQUENCER =====

	/**
	 * Create a new task sequencer for chaining actions.
	 */
	public TaskSequencer createTaskSequence() {
		return new TaskSequencer(this).withGameState(gameStatePlugin);
	}

	/**
	 * Create a new task sequencer with a specific default profile.
	 */
	public TaskSequencer createTaskSequence(MouseMovementProfile profile) {
		return new TaskSequencer(this).withGameState(gameStatePlugin).withProfile(profile);
	}

	// ===== Helpers =====

	<T> T runOnClientThread(java.util.function.Supplier<T> supplier) {
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
			return future.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			log.error("Error executing on client thread", e);
			throw new RuntimeException("Failed to execute on client thread", e);
		}
	}

	private Point getWidgetScreenPoint(Widget widget) {
		if (widget == null) {
			return null;
		}

		Rectangle bounds = widget.getBounds();
		if (bounds == null) {
			return null;
		}

		int x = (int) (bounds.getX() + bounds.getWidth() / 2);
		int y = (int) (bounds.getY() + bounds.getHeight() / 2);

		return new Point(x, y);
	}

	// ===== CAMERA CONTROL =====

	/**
	 * Get the current camera state (yaw, pitch).
	 * Yaw: 0-2047 (JAU, Jagex Angle Units, 1/1024 of a revolution = ~0.35 degrees)
	 * Pitch: typically 128 (max up) to 383 (max down)
	 */
	public java.util.Map<String, Object> getCameraState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("yaw", client.getCameraYaw());
			state.put("pitch", client.getCameraPitch());
			state.put("yawTarget", client.getCameraYawTarget());
			state.put("pitchTarget", client.getCameraPitchTarget());
			state.put("yawDegrees", Math.round(client.getCameraYaw() * 360.0 / 2048));
			state.put("pitchDegrees", Math.round(client.getCameraPitch() * 360.0 / 2048));
			state.put("zoom", client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG));
			return state;
		});
	}

	/**
	 * Smoothly rotate the camera yaw using arrow key simulation.
	 * @param yaw 0-2047 in JAU (0=North, 512=East, 1024=South, 1536=West)
	 */
	public void setCameraYaw(int yaw) {
		rotateCameraSmooth(yaw & 0x7FF);
	}

	/**
	 * Smoothly tilt the camera pitch using arrow key simulation (VK_UP / VK_DOWN).
	 * @param pitch typically 128 (looking up) to 383 (looking down)
	 */
	public void setCameraPitch(int pitch) {
		int clamped = Math.max(128, Math.min(383, pitch));
		tiltCameraSmooth(clamped);
	}

	/**
	 * Rotate the camera to face a specific compass direction.
	 * Uses arrow key events for smooth, human-like rotation.
	 * @param direction "north", "south", "east", "west", or degrees (0-359)
	 */
	public void setCameraDirection(String direction) {
		int targetYaw;
		switch (direction.toLowerCase()) {
			case "north": targetYaw = 0; break;
			case "east":  targetYaw = 512; break;
			case "south": targetYaw = 1024; break;
			case "west":  targetYaw = 1536; break;
			default:
				try {
					int degrees = Integer.parseInt(direction);
					targetYaw = (int) (degrees * 2048.0 / 360) & 0x7FF;
				} catch (NumberFormatException e) {
					log.warn("Invalid camera direction: {}", direction);
					return;
				}
		}
		setCameraYaw(targetYaw);
	}

	/**
	 * Smoothly rotate the camera toward a world point so the target is roughly centered on screen.
	 * Calculates the yaw angle from the player to the target tile.
	 */
	public void lookAtTile(WorldPoint target) {
		WorldPoint playerLoc = runOnClientThread(() -> client.getLocalPlayer().getWorldLocation());
		if (playerLoc == null) return;

		int dx = target.getX() - playerLoc.getX();
		int dy = target.getY() - playerLoc.getY();

		// OSRS yaw: 0=North(+Y), 512=East(+X), 1024=South(-Y), 1536=West(-X)
		// atan2 gives angle from +X axis counterclockwise, we need CW from +Y
		double angleRad = Math.atan2(dx, dy); // Note: atan2(x, y) gives CW from +Y
		int yaw = (int) (angleRad * 2048.0 / (2 * Math.PI)) & 0x7FF;

		setCameraYaw(yaw);
	}

	/**
	 * Smoothly rotate camera yaw via arrow key simulation (VK_LEFT / VK_RIGHT).
	 * Chooses the shortest rotation direction around the 0-2047 wrap-around.
	 * Holds the key pressed while polling camera yaw each tick, releasing when close to target.
	 */
	private void rotateCameraSmooth(int targetYaw) {
		Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.error("Canvas not available for camera rotation");
			return;
		}

		int currentYaw = runOnClientThread(() -> client.getCameraYaw());

		// Calculate signed shortest distance in JAU (wraps around 2048)
		int diff = targetYaw - currentYaw;
		// Normalize to [-1024, 1024)
		if (diff > 1024) diff -= 2048;
		if (diff < -1024) diff += 2048;

		int absDiff = Math.abs(diff);
		if (absDiff < 16) {
			// Already close enough, no need to rotate
			return;
		}

		// Choose key: positive diff = rotate right (VK_RIGHT), negative = rotate left (VK_LEFT)
		int keyCode = diff > 0 ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT;
		char keyChar = KeyEvent.CHAR_UNDEFINED;

		// Dispatch KEY_PRESSED to start rotation
		canvas.dispatchEvent(new KeyEvent(
			canvas,
			KeyEvent.KEY_PRESSED,
			System.currentTimeMillis(),
			0,
			keyCode,
			keyChar
		));

		try {
			// Poll camera position until we reach the target (or timeout)
			long startTime = System.currentTimeMillis();
			long maxDurationMs = Math.max(200, (long) (absDiff * 2.5)); // Rough estimate, ~2.5ms per JAU
			maxDurationMs = Math.min(maxDurationMs, 5000); // Safety cap at 5 seconds
			int threshold = 32; // Close enough threshold in JAU (~5.6 degrees)

			int prevYaw = currentYaw;
			boolean overshoot = false;

			while (System.currentTimeMillis() - startTime < maxDurationMs) {
				sleep(16); // ~1 game tick polling rate (60fps)

				int nowYaw = runOnClientThread(() -> client.getCameraYaw());

				// Check if we're close to target
				int remaining = targetYaw - nowYaw;
				if (remaining > 1024) remaining -= 2048;
				if (remaining < -1024) remaining += 2048;

				if (Math.abs(remaining) < threshold) {
					break; // Reached target
				}

				// Check for overshoot: if remaining changed sign, we passed the target
				int prevRemaining = targetYaw - prevYaw;
				if (prevRemaining > 1024) prevRemaining -= 2048;
				if (prevRemaining < -1024) prevRemaining += 2048;

				if ((prevRemaining > 0 && remaining < 0) || (prevRemaining < 0 && remaining > 0)) {
					overshoot = true;
					break;
				}

				prevYaw = nowYaw;
			}

			if (overshoot) {
				log.debug("Camera yaw overshot target, close enough");
			}
		} finally {
			// Always release the key
			canvas.dispatchEvent(new KeyEvent(
				canvas,
				KeyEvent.KEY_RELEASED,
				System.currentTimeMillis(),
				0,
				keyCode,
				keyChar
			));
		}

		log.debug("Camera yaw rotation complete: target={}, actual={}",
			targetYaw, runOnClientThread(() -> client.getCameraYaw()));
	}

	/**
	 * Smoothly tilt camera pitch via arrow key simulation (VK_UP / VK_DOWN).
	 * Dispatches key events on the canvas and polls until the pitch reaches the target.
	 * Self-corrects direction if the initial guess is wrong.
	 */
	private void tiltCameraSmooth(int targetPitch) {
		Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.error("Canvas not available for camera pitch");
			return;
		}

		int currentPitch = runOnClientThread(() -> client.getCameraPitch());
		int diff = targetPitch - currentPitch;

		log.info("Camera pitch tilt: current={}, target={}, diff={}", currentPitch, targetPitch, diff);

		if (Math.abs(diff) < 5) {
			log.info("Camera pitch already at target");
			return;
		}

		// OSRS pitch: 128 = camera low/horizontal, 383 = camera high/top-down
		// Try VK_UP for positive diff first — will self-correct if wrong
		int keyCode = diff > 0 ? KeyEvent.VK_UP : KeyEvent.VK_DOWN;

		log.info("Dispatching {} key for pitch adjustment (diff={})", keyCode == KeyEvent.VK_UP ? "VK_UP" : "VK_DOWN", diff);

		canvas.dispatchEvent(new KeyEvent(
			canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
			keyCode, KeyEvent.CHAR_UNDEFINED
		));

		try {
			long startTime = System.currentTimeMillis();
			int absDiff = Math.abs(diff);
			long maxDurationMs = Math.max(800, (long) (absDiff * 15));
			maxDurationMs = Math.min(maxDurationMs, 6000);
			int threshold = 8;
			int staleCount = 0;
			int prevPitch = currentPitch;
			boolean directionCorrected = false;

			while (System.currentTimeMillis() - startTime < maxDurationMs) {
				sleep(20);

				int nowPitch = runOnClientThread(() -> client.getCameraPitch());
				int remaining = targetPitch - nowPitch;

				if (Math.abs(remaining) < threshold) {
					log.info("Camera pitch reached target: actual={}, target={}", nowPitch, targetPitch);
					break;
				}

				// Check if pitch is moving the wrong direction and self-correct
				if (!directionCorrected && nowPitch != currentPitch) {
					boolean movingRight = (diff > 0 && nowPitch > currentPitch) || (diff < 0 && nowPitch < currentPitch);
					if (!movingRight) {
						log.info("Camera pitch moving wrong direction (now={}, was={}), flipping key", nowPitch, currentPitch);
						// Release current key
						canvas.dispatchEvent(new KeyEvent(
							canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
							keyCode, KeyEvent.CHAR_UNDEFINED
						));
						sleep(30);
						// Flip to opposite key
						keyCode = (keyCode == KeyEvent.VK_UP) ? KeyEvent.VK_DOWN : KeyEvent.VK_UP;
						canvas.dispatchEvent(new KeyEvent(
							canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
							keyCode, KeyEvent.CHAR_UNDEFINED
						));
						directionCorrected = true;
						prevPitch = nowPitch;
						continue;
					}
					directionCorrected = true; // Correct direction confirmed
				}

				// Overshoot detection (only after direction is confirmed)
				if (directionCorrected && prevPitch != currentPitch) {
					int prevRemaining = targetPitch - prevPitch;
					if (prevRemaining != 0 && remaining != 0 &&
						((prevRemaining > 0 && remaining < 0) || (prevRemaining < 0 && remaining > 0))) {
						log.info("Camera pitch overshot: actual={}, target={}", nowPitch, targetPitch);
						break;
					}
				}

				if (nowPitch == prevPitch) {
					staleCount++;
					if (staleCount > 15) {
						log.warn("Camera pitch not responding after {}ms, stuck at {}",
							System.currentTimeMillis() - startTime, nowPitch);
						break;
					}
				} else {
					staleCount = 0;
				}

				prevPitch = nowPitch;
			}
		} finally {
			canvas.dispatchEvent(new KeyEvent(
				canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
				keyCode, KeyEvent.CHAR_UNDEFINED
			));
		}

		int finalPitch = runOnClientThread(() -> client.getCameraPitch());
		log.info("Camera pitch tilt complete: target={}, actual={}", targetPitch, finalPitch);
	}

	/**
	 * Get the current camera zoom level.
	 * Higher values = more zoomed in. Typical range ~170 (far out) to ~1400 (close up).
	 */
	public int getCameraZoom() {
		return runOnClientThread(() -> client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG));
	}

	/**
	 * Smoothly zoom the camera with default speed (1.0).
	 * @param zoom target zoom value (higher = more zoomed in, typical range ~170-1004)
	 */
	public void setCameraZoom(int zoom) {
		setCameraZoom(zoom, 1.0);
	}

	/**
	 * Smoothly zoom the camera by stepping through intermediate zoom values
	 * using the game's CAMERA_DO_ZOOM script, with human-like timing.
	 * @param zoom target zoom value (higher = more zoomed in, typical range ~170-1004)
	 * @param speed speed multiplier (1.0 = default, 2.0 = twice as fast, 0.5 = half speed)
	 */
	public void setCameraZoom(int zoom, double speed) {
		int clamped = Math.max(0, zoom);
		double clampedSpeed = Math.max(0.1, Math.min(5.0, speed));
		zoomCameraSmooth(clamped, clampedSpeed);
	}

	/**
	 * Smoothly zoom camera via incremental CAMERA_DO_ZOOM script calls.
	 * Steps through intermediate zoom values with eased timing to produce
	 * natural-looking zoom like a human scrolling the mouse wheel.
	 * @param speed multiplier for zoom speed (1.0 = default, higher = faster)
	 */
	private void zoomCameraSmooth(int targetZoom, double speedMultiplier) {
		int currentZoom = runOnClientThread(() -> client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG));
		int diff = targetZoom - currentZoom;

		log.info("Camera zoom: current={}, target={}, diff={}, speed={}", currentZoom, targetZoom, diff, speedMultiplier);

		if (Math.abs(diff) < 10) {
			log.info("Camera zoom already at target");
			return;
		}

		int absDiff = Math.abs(diff);
		// Step size scales with speed — faster speed = bigger steps = fewer total steps
		int stepSize = (int)(25 * Math.max(1.0, speedMultiplier));
		int totalSteps = Math.max(1, absDiff / stepSize);
		// Cap steps so we don't take forever on huge zooms
		totalSteps = Math.min(totalSteps, 40);

		for (int i = 1; i <= totalSteps; i++) {
			double t = (double) i / totalSteps;
			int intermediateZoom = currentZoom + (int)(diff * t);
			intermediateZoom = Math.max(0, intermediateZoom);

			final int zoomValue = intermediateZoom;
			clientThread.invokeLater(() -> client.runScript(ScriptID.CAMERA_DO_ZOOM, zoomValue, zoomValue));

			// Human-like delay between steps: faster in the middle, slower at start/end
			double easeSpeed;
			if (t < 0.2) {
				double phase = t / 0.2;
				easeSpeed = 0.5 + 2.5 * phase;
			} else if (t < 0.7) {
				easeSpeed = 3.0;
			} else {
				double phase = (t - 0.7) / 0.3;
				easeSpeed = 3.0 - 2.5 * phase;
			}
			// Base delay: ~33ms at max ease speed, ~200ms at min ease speed
			// Then divided by the user speed multiplier
			int delayMs = (int)(100.0 / (easeSpeed * speedMultiplier));
			delayMs += (int)(Math.random() * 10);
			// Floor at 10ms to avoid spamming
			delayMs = Math.max(10, delayMs);

			sleep(delayMs);
		}

		// Final precise set to exact target
		clientThread.invokeLater(() -> client.runScript(ScriptID.CAMERA_DO_ZOOM, targetZoom, targetZoom));
		sleep(50);

		int finalZoom = runOnClientThread(() -> client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG));
		log.info("Camera zoom complete: target={}, actual={}, steps={}, speed={}", targetZoom, finalZoom, totalSteps, speedMultiplier);
	}

	/**
	 * Check if a world point is currently visible on screen (within the game viewport).
	 */
	public boolean isOnScreen(WorldPoint worldPoint) {
		Boolean result = runOnClientThread(() -> {
			int sceneX = worldPoint.getX() - client.getBaseX();
			int sceneY = worldPoint.getY() - client.getBaseY();
			if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) return false;

			net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
			if (lp == null) return false;

			Point sp = net.runelite.api.Perspective.localToCanvas(client, lp, client.getPlane());
			if (sp == null) return false;

			int canvasW = client.getCanvasWidth();
			int canvasH = client.getCanvasHeight();
			return sp.getX() >= 0 && sp.getX() < canvasW && sp.getY() >= 0 && sp.getY() < canvasH;
		});
		return Boolean.TRUE.equals(result);
	}

	// ===== RUN ENERGY =====

	/**
	 * Get current run energy (0-10000, where 10000 = 100%).
	 */
	public int getRunEnergy() {
		return runOnClientThread(() -> client.getEnergy());
	}

	/**
	 * Check if run is currently enabled by reading the VarPlayer.
	 */
	public boolean isRunEnabled() {
		return runOnClientThread(() -> client.getVarpValue(173) == 1);
	}

	/**
	 * Toggle run on/off by clicking the run orb on the minimap.
	 */
	public boolean toggleRun(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget runOrb = client.getWidget(net.runelite.api.widgets.WidgetInfo.MINIMAP_TOGGLE_RUN_ORB);
			if (runOrb == null || runOrb.isHidden()) {
				log.warn("Run orb widget not visible");
				return null;
			}
			return getWidgetClickPoint(runOrb, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Get run state info.
	 */
	public java.util.Map<String, Object> getRunState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("energy", client.getEnergy());
			state.put("energyPercent", client.getEnergy() / 100);
			state.put("enabled", client.getVarpValue(173) == 1);
			state.put("weight", client.getWeight());
			return state;
		});
	}

	// ===== GROUND ITEMS =====

	/**
	 * Get all ground items at a specific world location.
	 */
	public java.util.List<java.util.Map<String, Object>> getGroundItemsAt(WorldPoint location) {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();

			int sceneX = location.getX() - client.getBaseX();
			int sceneY = location.getY() - client.getBaseY();
			if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) return items;

			net.runelite.api.Scene scene = client.getScene();
			net.runelite.api.Tile tile = scene.getTiles()[client.getPlane()][sceneX][sceneY];
			if (tile == null) return items;

			java.util.List<net.runelite.api.TileItem> groundItems = tile.getGroundItems();
			if (groundItems == null) return items;

			for (net.runelite.api.TileItem gi : groundItems) {
				ItemComposition comp = client.getItemDefinition(gi.getId());
				java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
				entry.put("id", gi.getId());
				entry.put("name", comp.getName());
				entry.put("quantity", gi.getQuantity());
				entry.put("x", location.getX());
				entry.put("y", location.getY());
				entry.put("plane", location.getPlane());
				items.add(entry);
			}
			return items;
		});
	}

	/**
	 * Get all ground items near the player within a radius.
	 */
	public java.util.List<java.util.Map<String, Object>> getGroundItemsNearby(int radius) {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> allItems = new java.util.ArrayList<>();
			WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
			int plane = client.getPlane();

			net.runelite.api.Scene scene = client.getScene();
			net.runelite.api.Tile[][][] tiles = scene.getTiles();

			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					int sceneX = playerLoc.getX() - client.getBaseX() + dx;
					int sceneY = playerLoc.getY() - client.getBaseY() + dy;
					if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) continue;

					net.runelite.api.Tile tile = tiles[plane][sceneX][sceneY];
					if (tile == null) continue;

					java.util.List<net.runelite.api.TileItem> groundItems = tile.getGroundItems();
					if (groundItems == null) continue;

					for (net.runelite.api.TileItem gi : groundItems) {
						ItemComposition comp = client.getItemDefinition(gi.getId());
						java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
						entry.put("id", gi.getId());
						entry.put("name", comp.getName());
						entry.put("quantity", gi.getQuantity());
						entry.put("x", playerLoc.getX() + dx);
						entry.put("y", playerLoc.getY() + dy);
						entry.put("plane", plane);
						entry.put("distance", Math.max(Math.abs(dx), Math.abs(dy)));
						allItems.add(entry);
					}
				}
			}

			// Sort by distance
			allItems.sort((a, b) -> Integer.compare(
				((Number) a.get("distance")).intValue(),
				((Number) b.get("distance")).intValue()
			));

			return allItems;
		});
	}

	/**
	 * Click a ground item by name near the player.
	 * Finds the closest tile with that item and clicks it on screen.
	 */
	public boolean clickGroundItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
			int plane = client.getPlane();
			net.runelite.api.Scene scene = client.getScene();
			net.runelite.api.Tile[][][] tiles = scene.getTiles();

			// Search outward for the item
			for (int r = 0; r <= 15; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dy = -r; dy <= r; dy++) {
						if (Math.abs(dx) != r && Math.abs(dy) != r) continue; // Only check perimeter
						int sceneX = playerLoc.getX() - client.getBaseX() + dx;
						int sceneY = playerLoc.getY() - client.getBaseY() + dy;
						if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) continue;

						net.runelite.api.Tile tile = tiles[plane][sceneX][sceneY];
						if (tile == null) continue;

						java.util.List<net.runelite.api.TileItem> groundItems = tile.getGroundItems();
						if (groundItems == null) continue;

						for (net.runelite.api.TileItem gi : groundItems) {
							ItemComposition comp = client.getItemDefinition(gi.getId());
							if (comp.getName() != null && comp.getName().toLowerCase().contains(itemName.toLowerCase())) {
								net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
								if (lp == null) continue;
								Point sp = net.runelite.api.Perspective.localToCanvas(client, lp, plane);
								if (sp == null) continue;

								int jitterX = (int) ((Math.random() - 0.5) * 8);
								int jitterY = (int) ((Math.random() - 0.5) * 8);
								return new java.awt.Point(sp.getX() + jitterX, sp.getY() + jitterY);
							}
						}
					}
				}
			}

			log.warn("Ground item '{}' not found nearby", itemName);
			return null;
		});

		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Clicked ground item '{}'", itemName);
		return true;
	}

	/**
	 * Right-click a ground item and select an action (e.g., "Take").
	 */
	public boolean rightClickGroundItemAndSelect(String itemName, String action, MouseMovementProfile profile) {
		java.awt.Point itemPoint = runOnClientThread(() -> {
			WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
			int plane = client.getPlane();
			net.runelite.api.Scene scene = client.getScene();
			net.runelite.api.Tile[][][] tiles = scene.getTiles();

			for (int r = 0; r <= 15; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dy = -r; dy <= r; dy++) {
						if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
						int sceneX = playerLoc.getX() - client.getBaseX() + dx;
						int sceneY = playerLoc.getY() - client.getBaseY() + dy;
						if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104) continue;

						net.runelite.api.Tile tile = tiles[plane][sceneX][sceneY];
						if (tile == null) continue;

						java.util.List<net.runelite.api.TileItem> groundItems = tile.getGroundItems();
						if (groundItems == null) continue;

						for (net.runelite.api.TileItem gi : groundItems) {
							ItemComposition comp = client.getItemDefinition(gi.getId());
							if (comp.getName() != null && comp.getName().toLowerCase().contains(itemName.toLowerCase())) {
								net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromScene(sceneX, sceneY);
								if (lp == null) continue;
								Point sp = net.runelite.api.Perspective.localToCanvas(client, lp, plane);
								if (sp == null) continue;
								return new java.awt.Point(sp.getX(), sp.getY());
							}
						}
					}
				}
			}
			return null;
		});

		if (itemPoint == null) {
			log.warn("Ground item '{}' not found nearby for right-click", itemName);
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 8);
		int jitterY = (int) ((Math.random() - 0.5) * 8);

		return rightClickAndSelect(
			itemPoint.x + jitterX, itemPoint.y + jitterY,
			action, itemName, profile
		);
	}

	// ===== LOGOUT / WORLD HOP =====

	/**
	 * Get the current game state (LOGGED_IN, LOGIN_SCREEN, etc).
	 */
	public String getLoginState() {
		return runOnClientThread(() -> client.getGameState().name());
	}

	/**
	 * Get the current world number.
	 */
	public int getCurrentWorld() {
		return runOnClientThread(() -> client.getWorld());
	}

	/**
	 * Logout by clicking the logout button.
	 * Opens the logout tab first if needed.
	 */
	public boolean logout(MouseMovementProfile profile) {
		// First open the logout tab
		if (!openPlayerTab(PlayerTab.LOGOUT, profile)) {
			log.warn("Could not open logout tab");
			return false;
		}
		sleep(300 + (int) (Math.random() * 200));

		// Click the logout button
		java.awt.Point clickTarget = runOnClientThread(() -> {
			// Try the logout button widget
			Widget logoutBtn = client.getWidget(InterfaceID.Logout.LOGOUT);
			if (logoutBtn != null && !logoutBtn.isHidden()) {
				return getWidgetClickPoint(logoutBtn, profile);
			}
			log.warn("Logout button widget not found");
			return null;
		});

		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Clicked logout button");
		return true;
	}

	/**
	 * Hop to a specific world.
	 */
	public boolean hopWorld(int worldNumber, MouseMovementProfile profile) {
		Boolean result = runOnClientThread(() -> {
			net.runelite.api.World[] worlds = client.getWorldList();
			if (worlds == null) {
				log.warn("World list not available");
				return false;
			}

			for (net.runelite.api.World world : worlds) {
				if (world.getId() == worldNumber) {
					client.hopToWorld(world);
					log.info("Hopping to world {}", worldNumber);
					return true;
				}
			}

			log.warn("World {} not found in world list", worldNumber);
			return false;
		});
		return Boolean.TRUE.equals(result);
	}

	/**
	 * Get available worlds info.
	 */
	public java.util.List<java.util.Map<String, Object>> getWorldList() {
		java.util.List<java.util.Map<String, Object>> worldList = new java.util.ArrayList<>();
		WorldResult worldResult = worldService.getWorlds();
		if (worldResult == null) {
			log.warn("WorldService returned null — world list not yet fetched");
			return worldList;
		}

		for (net.runelite.http.api.worlds.World world : worldResult.getWorlds()) {
			java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
			info.put("id", world.getId());
			info.put("playerCount", world.getPlayers());
			info.put("location", world.getLocation());
			info.put("region", world.getRegion());
			info.put("activity", world.getActivity());
			info.put("types", world.getTypes().toString());
			worldList.add(info);
		}
		return worldList;
	}

	// ===== SPIRIT TREE & FAIRY RING =====

	// Fairy ring dial mappings: varbit value → letter
	private static final String[] FAIRY_LEFT_DIAL = {"A", "D", "C", "B"};
	private static final String[] FAIRY_MIDDLE_DIAL = {"I", "L", "K", "J"};
	private static final String[] FAIRY_RIGHT_DIAL = {"P", "S", "R", "Q"};

	/**
	 * Travel via a spirit tree. Clicks the nearest spirit tree with "Travel" action,
	 * waits for the destination dialog, and selects the specified destination.
	 *
	 * @param destination the destination name (substring match, e.g., "Grand Exchange", "Gnome Stronghold")
	 * @param profile     mouse movement profile
	 * @return true if the travel was initiated
	 */
	public boolean travelSpiritTree(String destination, MouseMovementProfile profile) {
		log.info("Spirit tree travel to '{}'", destination);

		// Click the spirit tree object — try multiple name+action combinations
		// Regular spirit trees use "Travel"; POH spiritual fairy tree uses "Tree"
		String[] spiritTreeNames = {"Spirit tree", "Spirit Tree", "Spiritual fairy tree", "Spiritual Fairy Tree"};
		String[] spiritTreeActions = {"Travel", "Tree"};
		boolean clicked = false;
		for (String action : spiritTreeActions) {
			for (String name : spiritTreeNames) {
				clicked = interactWithObject(name, action, profile);
				if (clicked) {
					log.info("Clicked spirit tree '{}' with action '{}'", name, action);
					break;
				}
			}
			if (clicked) break;
		}
		if (!clicked) {
			// Last resort: find any nearby object with spirit/fairy in name and a travel-like action
			log.info("Trying fallback: searching all nearby objects for spirit tree");
			if (objectDetectionPlugin != null) {
				var nearby = objectDetectionPlugin.getObjectsNearby(20);
				for (var obj : nearby) {
					String objNameLower = obj.getName().toLowerCase();
					if (objNameLower.contains("spirit") || objNameLower.contains("fairy tree")) {
						log.info("Found candidate: '{}' at {} actions={}", obj.getName(), obj.getLocation(), obj.getActions());
						for (String action : new String[]{"Travel", "Tree"}) {
							if (obj.hasAction(action)) {
								clicked = interactWithObject(obj, profile);
								if (clicked) {
									log.info("Clicked spirit tree via fallback: '{}' action '{}'", obj.getName(), action);
									break;
								}
							}
						}
						if (clicked) break;
					}
				}
			}
		}
		if (!clicked) {
			log.warn("Could not find or click a Spirit tree");
			return false;
		}

		// Wait for the spirit tree location list to appear and select destination
		sleep(1500 + (int)(Math.random() * 500));
		boolean selected = waitAndSelectSpiritTreeDestination(destination, 8000, profile);
		if (!selected) {
			log.warn("Failed to select spirit tree destination '{}'", destination);
			return false;
		}

		log.info("Spirit tree travel to '{}' initiated", destination);
		return true;
	}

	/**
	 * Wait for the spirit tree location list interface to appear, then click the destination.
	 * The spirit tree uses a custom widget (NOT a standard chatbox dialog).
	 * We find it dynamically by scanning widget groups 0-900 for one containing
	 * "Spirit Tree Locations" in its children's text.
	 */
	private boolean waitAndSelectSpiritTreeDestination(String destination, int timeoutMs, MouseMovementProfile profile) {
		long deadline = System.currentTimeMillis() + timeoutMs;

		// Step 1: Poll until we find the spirit tree widget by searching for its title text
		Point clickPoint = null;
		while (System.currentTimeMillis() < deadline) {
			clickPoint = runOnClientThread(() -> {
				// Scan widget groups to find the spirit tree interface
				for (int groupId = 0; groupId <= 900; groupId++) {
					Widget w0 = client.getWidget(groupId, 0);
					if (w0 == null || w0.isHidden()) continue;

					// Check children of this group for "Spirit Tree Locations" title
					boolean isSpiritTreeInterface = false;
					for (int childId = 0; childId <= 10; childId++) {
						Widget w = client.getWidget(groupId, childId);
						if (w == null) continue;

						// Check the widget's own text and its sub-children for the title
						if (containsText(w, "Spirit Tree Locations")) {
							isSpiritTreeInterface = true;
							break;
						}
					}

					if (!isSpiritTreeInterface) continue;
					log.info("Found spirit tree interface in widget group {}", groupId);

					// Now search this group's children for the destination text
					String search = destination.toLowerCase();
					for (int childId = 0; childId <= 30; childId++) {
						Widget parent = client.getWidget(groupId, childId);
						if (parent == null || parent.isHidden()) continue;

						// Search static children
						Widget[] children = parent.getChildren();
						if (children != null) {
							for (Widget child : children) {
								if (child != null && !child.isHidden() && child.getText() != null) {
									String raw = child.getText().replaceAll("<[^>]+>", "");
									if (raw.toLowerCase().contains(search)) {
										log.info("Found spirit tree destination '{}' in group {} widget text: '{}'",
											destination, groupId, raw);
										return getWidgetScreenPoint(child);
									}
								}
							}
						}

						// Search dynamic children
						Widget[] dynChildren = parent.getDynamicChildren();
						if (dynChildren != null) {
							for (Widget child : dynChildren) {
								if (child != null && !child.isHidden() && child.getText() != null) {
									String raw = child.getText().replaceAll("<[^>]+>", "");
									if (raw.toLowerCase().contains(search)) {
										log.info("Found spirit tree destination '{}' in group {} dynamic widget: '{}'",
											destination, groupId, raw);
										return getWidgetScreenPoint(child);
									}
								}
							}
						}
					}

					// If we found the interface but not the destination, log available options
					StringBuilder available = new StringBuilder();
					for (int childId = 0; childId <= 30; childId++) {
						Widget parent = client.getWidget(groupId, childId);
						if (parent == null || parent.isHidden()) continue;
						Widget[] children = parent.getChildren();
						if (children != null) {
							for (Widget child : children) {
								if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty()) {
									String raw = child.getText().replaceAll("<[^>]+>", "");
									if (!raw.isEmpty()) {
										if (available.length() > 0) available.append(", ");
										available.append(raw);
									}
								}
							}
						}
					}
					log.warn("Spirit tree destination '{}' not found in group {}. Available: [{}]", destination, groupId, available);
					return null;
				}
				return null;
			});

			if (clickPoint != null) break;
			sleep(200);
		}

		if (clickPoint == null) {
			log.warn("Spirit tree interface did not appear or destination '{}' not found within {}ms", destination, timeoutMs);
			return false;
		}

		sleep(100 + (int)(Math.random() * 100));

		// Step 2: Click the destination
		int jitterX = (int) ((Math.random() - 0.5) * 8);
		int jitterY = (int) ((Math.random() - 0.5) * 4);
		mouseMovement.moveAndClick(
			new java.awt.Point(clickPoint.getX() + jitterX, clickPoint.getY() + jitterY),
			profile
		);
		return true;
	}

	/**
	 * Check if a widget or any of its immediate children contain the given text (case-insensitive).
	 */
	private boolean containsText(Widget widget, String text) {
		String search = text.toLowerCase();
		if (widget.getText() != null && widget.getText().toLowerCase().contains(search)) return true;

		Widget[] children = widget.getChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && child.getText() != null && child.getText().toLowerCase().contains(search)) return true;
			}
		}
		Widget[] dynChildren = widget.getDynamicChildren();
		if (dynChildren != null) {
			for (Widget child : dynChildren) {
				if (child != null && child.getText() != null && child.getText().toLowerCase().contains(search)) return true;
			}
		}
		return false;
	}

	/**
	 * Travel via a fairy ring by dialing a 3-letter code and confirming.
	 * Clicks the nearest fairy ring with "Configure" action, sets each dial
	 * to the correct letter, then clicks confirm.
	 *
	 * @param code    3-letter fairy ring code (e.g., "DKR", "CKS", "AJR")
	 * @param profile mouse movement profile
	 * @return true if the teleport was initiated
	 */
	public boolean travelFairyRing(String code, MouseMovementProfile profile) {
		if (code == null || code.length() != 3) {
			log.warn("Invalid fairy ring code: '{}' (must be 3 letters)", code);
			return false;
		}
		String upper = code.toUpperCase();
		log.info("Fairy ring travel to code '{}'", upper);

		// Find the target dial positions for each letter
		int targetLeft = findDialPosition(FAIRY_LEFT_DIAL, String.valueOf(upper.charAt(0)));
		int targetMiddle = findDialPosition(FAIRY_MIDDLE_DIAL, String.valueOf(upper.charAt(1)));
		int targetRight = findDialPosition(FAIRY_RIGHT_DIAL, String.valueOf(upper.charAt(2)));

		if (targetLeft < 0 || targetMiddle < 0 || targetRight < 0) {
			log.warn("Invalid fairy ring code '{}': letters must be from A/B/C/D, I/J/K/L, P/Q/R/S", upper);
			return false;
		}

		// Click the fairy ring — always right-click to select Configure/Ring-configure
		// Left-click on regular fairy rings goes to Zanaris; left-click on spiritual fairy tree does Travel
		String[] fairyNames = {"Fairy ring", "Spiritual fairy tree", "Spiritual Fairy Tree"};
		String[] configureActions = {"Configure", "Ring-configure"};
		boolean clicked = false;

		for (String action : configureActions) {
			for (String name : fairyNames) {
				clicked = findAndRightClickObject(name, action, profile);
				if (clicked) { log.info("Right-click selected '{}' on '{}'", action, name); break; }
			}
			if (clicked) break;
		}
		if (!clicked) {
			log.warn("Could not find or click a Fairy ring");
			return false;
		}

		// Wait for the fairy ring interface to open
		sleep(1500 + (int)(Math.random() * 500));
		if (!waitForFairyRingInterface(8000)) {
			log.warn("Fairy ring interface did not open");
			return false;
		}
		sleep(300 + (int)(Math.random() * 200));

		// Set each dial to the correct position
		if (!setFairyDial(1, targetLeft, InterfaceID.Fairyrings._1_CLOCKWISE,
				InterfaceID.Fairyrings._1_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_1, profile)) {
			return false;
		}
		sleep(200 + (int)(Math.random() * 150));

		if (!setFairyDial(2, targetMiddle, InterfaceID.Fairyrings._2_CLOCKWISE,
				InterfaceID.Fairyrings._2_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_2, profile)) {
			return false;
		}
		sleep(200 + (int)(Math.random() * 150));

		if (!setFairyDial(3, targetRight, InterfaceID.Fairyrings._3_CLOCKWISE,
				InterfaceID.Fairyrings._3_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_3, profile)) {
			return false;
		}
		sleep(300 + (int)(Math.random() * 200));

		// Click confirm/teleport
		java.awt.Point confirmPoint = runOnClientThread(() -> {
			Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
			if (confirm != null && !confirm.isHidden()) {
				return getWidgetClickPoint(confirm, profile);
			}
			return null;
		});
		if (confirmPoint == null) {
			log.warn("Fairy ring confirm button not found");
			return false;
		}
		mouseMovement.moveAndClick(confirmPoint, profile);
		log.info("Fairy ring teleport to '{}' confirmed", upper);
		return true;
	}

	/**
	 * Travel via a fairy ring using its travel log (for previously visited codes).
	 * Clicks "Last-destination" on the fairy ring, or opens the log and selects
	 * the code from there.
	 */
	public boolean travelFairyRingFromLog(String code, MouseMovementProfile profile) {
		if (code == null || code.length() != 3) {
			log.warn("Invalid fairy ring code: '{}'", code);
			return false;
		}
		String upper = code.toUpperCase();
		log.info("Fairy ring travel from log to '{}'", upper);

		// Click fairy ring — always right-click to select Configure/Ring-configure
		String[] fairyNames = {"Fairy ring", "Spiritual fairy tree", "Spiritual Fairy Tree"};
		String[] configureActions = {"Configure", "Ring-configure"};
		boolean clicked = false;

		for (String action : configureActions) {
			for (String name : fairyNames) {
				clicked = findAndRightClickObject(name, action, profile);
				if (clicked) { log.info("Right-click selected '{}' on '{}'", action, name); break; }
			}
			if (clicked) break;
		}
		if (!clicked) {
			log.warn("Could not find or click a Fairy ring");
			return false;
		}

		sleep(1500 + (int)(Math.random() * 500));
		if (!waitForFairyRingInterface(8000)) {
			log.warn("Fairy ring interface did not open");
			return false;
		}
		sleep(300 + (int)(Math.random() * 200));

		// Try to find and click the code in the travel log
		java.awt.Point logEntry = runOnClientThread(() -> {
			// The fairy ring log entries are widgets in the FairyringsLog group
			// Each code has a widget with actions like "Use code"
			int logGroup = InterfaceID.FAIRYRINGS_LOG;
			Widget logRoot = client.getWidget(logGroup, 0);
			if (logRoot == null || logRoot.isHidden()) {
				return null;
			}
			// Search for the code widget by checking children
			Widget frame = client.getWidget(InterfaceID.FairyringsLog.FRAME);
			if (frame == null) return null;
			Widget[] children = frame.getDynamicChildren();
			if (children == null) children = frame.getStaticChildren();
			if (children == null) return null;

			String search = upper;
			for (Widget child : children) {
				if (child == null || child.isHidden()) continue;
				String text = child.getText();
				String[] actions = child.getActions();
				if (text != null && text.contains(search) && actions != null) {
					for (String action : actions) {
						if (action != null && action.contains("Use code")) {
							return getWidgetClickPoint(child, profile);
						}
					}
				}
			}
			return null;
		});

		if (logEntry != null) {
			mouseMovement.moveAndClick(logEntry, profile);
			log.info("Selected fairy ring code '{}' from travel log", upper);
			return true;
		}

		// Fallback: set the dials manually
		log.info("Code '{}' not found in travel log, setting dials manually", upper);
		return travelFairyRingByDials(upper, profile);
	}

	private boolean travelFairyRingByDials(String code, MouseMovementProfile profile) {
		int targetLeft = findDialPosition(FAIRY_LEFT_DIAL, String.valueOf(code.charAt(0)));
		int targetMiddle = findDialPosition(FAIRY_MIDDLE_DIAL, String.valueOf(code.charAt(1)));
		int targetRight = findDialPosition(FAIRY_RIGHT_DIAL, String.valueOf(code.charAt(2)));

		if (targetLeft < 0 || targetMiddle < 0 || targetRight < 0) {
			return false;
		}

		if (!setFairyDial(1, targetLeft, InterfaceID.Fairyrings._1_CLOCKWISE,
				InterfaceID.Fairyrings._1_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_1, profile)) {
			return false;
		}
		sleep(200 + (int)(Math.random() * 150));

		if (!setFairyDial(2, targetMiddle, InterfaceID.Fairyrings._2_CLOCKWISE,
				InterfaceID.Fairyrings._2_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_2, profile)) {
			return false;
		}
		sleep(200 + (int)(Math.random() * 150));

		if (!setFairyDial(3, targetRight, InterfaceID.Fairyrings._3_CLOCKWISE,
				InterfaceID.Fairyrings._3_ANTICLOCKWISE, net.runelite.api.gameval.VarbitID.FAIRYRING_3, profile)) {
			return false;
		}
		sleep(300 + (int)(Math.random() * 200));

		java.awt.Point confirmPoint = runOnClientThread(() -> {
			Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
			if (confirm != null && !confirm.isHidden()) {
				return getWidgetClickPoint(confirm, profile);
			}
			return null;
		});
		if (confirmPoint == null) {
			log.warn("Fairy ring confirm button not found");
			return false;
		}
		mouseMovement.moveAndClick(confirmPoint, profile);
		log.info("Fairy ring teleport to '{}' confirmed via dials", code);
		return true;
	}

	private int findDialPosition(String[] dial, String letter) {
		for (int i = 0; i < dial.length; i++) {
			if (dial[i].equalsIgnoreCase(letter)) {
				return i;
			}
		}
		return -1;
	}

	private boolean waitForFairyRingInterface(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			Boolean open = runOnClientThread(() -> {
				Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
				return confirm != null && !confirm.isHidden();
			});
			if (Boolean.TRUE.equals(open)) {
				return true;
			}
			sleep(200);
		}
		return false;
	}

	/**
	 * Set a fairy ring dial to the target position by clicking clockwise/anticlockwise.
	 * Each dial has 4 positions (0-3). Finds the shortest rotation direction.
	 */
	private boolean setFairyDial(int dialNum, int targetPos, int cwPackedId, int ccwPackedId,
								  int varbitId, MouseMovementProfile profile) {
		for (int attempt = 0; attempt < 8; attempt++) {
			Integer currentPos = runOnClientThread(() -> client.getVarbitValue(varbitId));
			if (currentPos == null) {
				log.warn("Could not read fairy ring dial {} position", dialNum);
				return false;
			}

			if (currentPos == targetPos) {
				log.debug("Fairy dial {} already at position {} — done", dialNum, targetPos);
				return true;
			}

			// Calculate shortest rotation: clockwise vs anticlockwise
			int cwSteps = (targetPos - currentPos + 4) % 4;
			int ccwSteps = (currentPos - targetPos + 4) % 4;
			boolean clockwise = cwSteps <= ccwSteps;
			int packedId = clockwise ? cwPackedId : ccwPackedId;

			log.debug("Fairy dial {}: current={}, target={}, rotating {} ({} steps)",
				dialNum, currentPos, targetPos, clockwise ? "CW" : "CCW",
				clockwise ? cwSteps : ccwSteps);

			java.awt.Point clickPt = runOnClientThread(() -> {
				int groupId = packedId >> 16;
				int childId = packedId & 0xFFFF;
				Widget btn = client.getWidget(groupId, childId);
				if (btn != null && !btn.isHidden()) {
					return getWidgetClickPoint(btn, profile);
				}
				return null;
			});

			if (clickPt == null) {
				log.warn("Fairy ring dial {} rotation button not found", dialNum);
				return false;
			}

			mouseMovement.moveAndClick(clickPt, profile);
			sleep(600 + (int)(Math.random() * 300));
		}

		log.warn("Failed to set fairy ring dial {} after 8 attempts", dialNum);
		return false;
	}

	/**
	 * Get the current fairy ring code from dial varbits.
	 */
	public java.util.Map<String, Object> getFairyRingState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			int left = client.getVarbitValue(net.runelite.api.gameval.VarbitID.FAIRYRING_1);
			int middle = client.getVarbitValue(net.runelite.api.gameval.VarbitID.FAIRYRING_2);
			int right = client.getVarbitValue(net.runelite.api.gameval.VarbitID.FAIRYRING_3);
			String currentCode = FAIRY_LEFT_DIAL[left] + FAIRY_MIDDLE_DIAL[middle] + FAIRY_RIGHT_DIAL[right];
			state.put("currentCode", currentCode);
			state.put("leftDial", left);
			state.put("middleDial", middle);
			state.put("rightDial", right);

			Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
			state.put("interfaceOpen", confirm != null && !confirm.isHidden());
			return state;
		});
	}

	// ===== LOGIN =====

	/**
	 * Get stored credentials from system properties.
	 * Supports default (rs.username) and numbered accounts (rs.account1.username).
	 */
	public java.util.Map<String, String> getStoredCredentials(String accountId) {
		java.util.Map<String, String> creds = new java.util.LinkedHashMap<>();
		String prefix;
		if (accountId == null || accountId.isEmpty() || "default".equalsIgnoreCase(accountId)) {
			prefix = "rs.";
		} else {
			prefix = "rs.account" + accountId + ".";
		}
		String username = System.getProperty(prefix + "username");
		String password = System.getProperty(prefix + "password");
		String bankPin = System.getProperty(prefix + "bankpin");
		String world = System.getProperty(prefix + "world");

		if (username != null) creds.put("username", username);
		if (password != null) creds.put("password", password);
		if (bankPin != null) creds.put("bankPin", bankPin);
		if (world != null) creds.put("world", world);
		return creds;
	}

	/**
	 * Login using stored credentials from system properties (.env file).
	 * @param accountId null/"default" for RS_USERNAME, or "1","2",etc for ACCOUNT1_USERNAME
	 */
	public boolean loginWithStoredCredentials(String accountId) {
		java.util.Map<String, String> creds = getStoredCredentials(accountId);
		String username = creds.get("username");
		String password = creds.get("password");
		if (username == null || password == null) {
			log.warn("No stored credentials found for account '{}'. Set RS_USERNAME/RS_PASSWORD in .env", accountId);
			return false;
		}
		return login(username, password);
	}

	/**
	 * Enter bank pin using stored credentials from system properties.
	 */
	public boolean enterStoredBankPin(String accountId, MouseMovementProfile profile) {
		java.util.Map<String, String> creds = getStoredCredentials(accountId);
		String pin = creds.get("bankPin");
		if (pin == null) {
			log.warn("No stored bank pin found for account '{}'. Set RS_BANK_PIN in .env", accountId);
			return false;
		}
		return enterBankPin(pin, profile);
	}

	/**
	 * Login with the given username and password.
	 * Sets credentials on the client and clicks the login button.
	 * Only works when on the LOGIN_SCREEN game state.
	 */
	public boolean login(String username, String password) {
		String gameState = getLoginState();
		if (!"LOGIN_SCREEN".equals(gameState) && !"LOGIN_SCREEN_AUTHENTICATOR".equals(gameState)) {
			log.warn("Cannot login — game state is {}", gameState);
			return false;
		}

		// Set credentials via the client API
		runOnClientThread(() -> {
			client.setUsername(username);
			client.setPassword(password);
			return null;
		});

		sleep(200 + (int)(Math.random() * 100));

		// Press Enter to trigger login
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null) return false;

		// First Enter sets username field if needed, second triggers login
		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ENTER, '\n'
		));
		sleep(50);
		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ENTER, '\n'
		));
		sleep(300 + (int)(Math.random() * 200));

		// Press Enter again to submit
		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ENTER, '\n'
		));
		sleep(50);
		canvas.dispatchEvent(new java.awt.event.KeyEvent(
			canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
			java.awt.event.KeyEvent.VK_ENTER, '\n'
		));

		log.info("Login credentials submitted for user '{}', waiting for LOGGED_IN state...", username);

		// Wait for LOGGED_IN state (the welcome/lobby screen)
		long deadline = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < deadline) {
			sleep(1000);
			String state = getLoginState();
			if ("LOGGED_IN".equals(state)) {
				break;
			}
		}

		if (!"LOGGED_IN".equals(getLoginState())) {
			log.warn("Login did not reach LOGGED_IN state within 30s, current state: {}", getLoginState());
			return false;
		}

		// Dismiss the "Click here to play" welcome screen if present
		sleep(1000 + (int)(Math.random() * 500));
		dismissWelcomeScreen();

		log.info("Login complete for user '{}'", username);
		return true;
	}

	/**
	 * Check if the "Click here to play" welcome screen is visible and dismiss it.
	 * This screen appears after credentials are accepted but before the game world loads.
	 */
	private void dismissWelcomeScreen() {
		for (int attempt = 0; attempt < 5; attempt++) {
			Boolean welcomeVisible = runOnClientThread(() -> {
				Widget welcome = client.getWidget(InterfaceID.WELCOME_SCREEN, 0);
				return welcome != null && !welcome.isHidden();
			});

			if (welcomeVisible == null || !welcomeVisible) {
				log.info("Welcome screen not visible (attempt {}), game world should be loaded", attempt);
				return;
			}

			log.info("Welcome screen detected (attempt {}), clicking PLAY button...", attempt);

			// Click the PLAY widget — the "CLICK HERE TO PLAY" button in the middle row.
			// Note: BOTTOM (child 4) is the news scroll at the bottom, not the play button.
			// The actual play button is WelcomeScreen.PLAY (child 0x48 = 72).
			java.awt.Point playButton = runOnClientThread(() -> {
				int packedId = InterfaceID.WelcomeScreen.PLAY;
				int groupId = packedId >> 16;
				int childId = packedId & 0xFFFF;
				Widget playWidget = client.getWidget(groupId, childId);
				if (playWidget != null && !playWidget.isHidden()) {
					log.info("Found PLAY widget, bounds: x={} y={} w={} h={}",
						playWidget.getCanvasLocation().getX(), playWidget.getCanvasLocation().getY(),
						playWidget.getWidth(), playWidget.getHeight());
					return getWidgetClickPoint(playWidget, MouseMovementProfile.FAST);
				}
				log.warn("PLAY widget not found or hidden");
				return null;
			});

			if (playButton != null) {
				mouseMovement.moveAndClick(playButton, MouseMovementProfile.FAST);
			} else {
				log.warn("Could not find PLAY button on welcome screen");
			}

			sleep(2000 + (int)(Math.random() * 1000));
		}
	}

	// ===== BANK PIN =====

	/**
	 * Check if the bank pin interface is currently open.
	 */
	public boolean isBankPinOpen() {
		return runOnClientThread(() -> {
			Widget pinWidget = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
			return pinWidget != null && !pinWidget.isHidden();
		});
	}

	/**
	 * Enter a 4-digit bank pin.
	 * The bank pin keypad has buttons A-J (0-9) but their positions are scrambled.
	 * We read the text label on each button to find which widget corresponds to which digit.
	 */
	public boolean enterBankPin(String pin, MouseMovementProfile profile) {
		if (pin == null || pin.length() != 4) {
			log.warn("Bank pin must be exactly 4 digits, got: '{}'", pin);
			return false;
		}

		if (!isBankPinOpen()) {
			log.warn("Bank pin interface is not open");
			return false;
		}

		int keypadGroup = InterfaceID.BANKPIN_KEYPAD;
		// Button widgets: A=0x10, B=0x12, C=0x14, D=0x16, E=0x18, F=0x1a, G=0x1c, H=0x1e, I=0x20, J=0x22
		// These correspond to child indices 16,18,20,22,24,26,28,30,32,34
		// Each button has a text child (the _GRAPHIC0 sibling) that shows the digit number
		int[] buttonChildIds = {0x10, 0x12, 0x14, 0x16, 0x18, 0x1a, 0x1c, 0x1e, 0x20, 0x22};

		for (int digitIndex = 0; digitIndex < 4; digitIndex++) {
			if (!isBankPinOpen()) {
				log.warn("Bank pin closed unexpectedly at digit {}", digitIndex + 1);
				return false;
			}

			char targetDigit = pin.charAt(digitIndex);
			int targetNumber = targetDigit - '0';
			if (targetNumber < 0 || targetNumber > 9) {
				log.warn("Invalid pin digit: '{}'", targetDigit);
				return false;
			}

			final int digitIdx = digitIndex;
			java.awt.Point buttonPoint = runOnClientThread(() -> {
				// Each button widget has text showing its current number
				for (int btnChildId : buttonChildIds) {
					Widget button = client.getWidget(keypadGroup, btnChildId);
					if (button == null || button.isHidden()) continue;

					String btnText = button.getText();
					if (btnText != null) {
						String clean = btnText.replaceAll("<[^>]+>", "").trim();
						try {
							int btnNumber = Integer.parseInt(clean);
							if (btnNumber == targetNumber) {
								log.info("Pin digit {} ({}): found at button child {}", digitIdx + 1, targetNumber, btnChildId);
								return getWidgetClickPoint(button, profile);
							}
						} catch (NumberFormatException ignored) {}
					}

					// Also check child widgets for the text
					Widget[] children = button.getDynamicChildren();
					if (children != null) {
						for (Widget child : children) {
							if (child == null) continue;
							String childText = child.getText();
							if (childText != null) {
								String clean = childText.replaceAll("<[^>]+>", "").trim();
								try {
									int btnNumber = Integer.parseInt(clean);
									if (btnNumber == targetNumber) {
										log.info("Pin digit {} ({}): found in child text at button child {}", digitIdx + 1, targetNumber, btnChildId);
										return getWidgetClickPoint(button, profile);
									}
								} catch (NumberFormatException ignored) {}
							}
						}
					}
				}
				log.warn("Could not find button for digit {} (number {})", digitIdx + 1, targetNumber);
				return null;
			});

			if (buttonPoint == null) return false;
			mouseMovement.moveAndClick(buttonPoint, profile);
			sleep(300 + (int)(Math.random() * 200));

			// Move mouse away from buttons so hover doesn't obscure the next digit's text.
			// The pin keypad scrambles after each click, and if the cursor stays on a button,
			// the hover effect hides the number (shows "Select" tooltip instead).
			java.awt.Point neutralPoint = runOnClientThread(() -> {
				Widget pinWidget = client.getWidget(keypadGroup, 0);
				if (pinWidget != null) {
					java.awt.Rectangle bounds = pinWidget.getBounds();
					if (bounds != null) {
						// Pick a random spot along the edges of the pin dialog (outside the button grid)
						java.util.Random rng = new java.util.Random();
						int side = rng.nextInt(3); // 0=right, 1=top, 2=bottom
						int x, y;
						if (side == 0) {
							// Right edge area
							x = bounds.x + bounds.width - 10 - rng.nextInt(30);
							y = bounds.y + 30 + rng.nextInt(Math.max(1, bounds.height - 60));
						} else if (side == 1) {
							// Top area (above buttons)
							x = bounds.x + 30 + rng.nextInt(Math.max(1, bounds.width - 60));
							y = bounds.y + 10 + rng.nextInt(20);
						} else {
							// Bottom area (below buttons)
							x = bounds.x + 30 + rng.nextInt(Math.max(1, bounds.width - 60));
							y = bounds.y + bounds.height - 10 - rng.nextInt(20);
						}
						return new java.awt.Point(x, y);
					}
				}
				return null;
			});
			if (neutralPoint != null) {
				mouseMovement.moveMouse(neutralPoint, profile);
			}
			sleep(300 + (int)(Math.random() * 200)); // Pin interface has a delay between digits
		}

		log.info("Bank pin entered successfully");
		return true;
	}

	private Point getMinimapPoint(WorldPoint worldPoint) {
		net.runelite.api.coords.LocalPoint localPoint =
			net.runelite.api.coords.LocalPoint.fromWorld(client, worldPoint);

		if (localPoint == null) {
			return null;
		}

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

	// ===== PRAYER BY NAME =====

	/**
	 * Toggle a prayer by its enum name (e.g., "PROTECT_FROM_MELEE", "PIETY", "RIGOUR").
	 * Automatically opens the prayer tab first.
	 */
	public boolean togglePrayer(String prayerName, MouseMovementProfile profile) {
		// Normalize the prayer name: "Thick Skin" -> "THICK_SKIN", "THICK_SKIN" stays as is
		String enumName = prayerName.trim().toUpperCase().replace(' ', '_');

		// Validate the prayer name
		try {
			net.runelite.api.Prayer.valueOf(enumName);
		} catch (IllegalArgumentException e) {
			log.warn("Unknown prayer: {} (normalized: {})", prayerName, enumName);
			return false;
		}

		// Open prayer tab
		if (!openPlayerTab(PlayerTab.PRAYER, profile)) {
			log.warn("Could not open prayer tab");
			return false;
		}
		sleep(300 + (int)(Math.random() * 200));

		// Convert enum name to display name: THICK_SKIN -> "Thick Skin"
		String displayName = prayerNameToDisplay(enumName);

		// Search prayer widgets by name — children 9-38 of prayerbook group
		int groupId = 0x021d; // InterfaceID.Prayerbook group
		java.awt.Point clickTarget = runOnClientThread(() -> {
			for (int child = 9; child <= 38; child++) {
				net.runelite.api.widgets.Widget widget = client.getWidget(groupId, child);
				if (widget == null || widget.isHidden()) continue;
				String name = widget.getName();
				if (name != null) {
					// Widget names may contain tags like <col=ff981f>Protect from Melee</col>
					String cleanName = name.replaceAll("<[^>]+>", "").trim();
					if (cleanName.equalsIgnoreCase(displayName)) {
						log.info("Found prayer '{}' at widget {}.{}", displayName, groupId, child);
						return getWidgetClickPoint(widget, profile);
					}
				}
			}
			log.warn("Prayer widget not found for '{}'", displayName);
			return null;
		});

		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	private String prayerNameToDisplay(String enumName) {
		// PROTECT_FROM_MELEE -> Protect from Melee
		// RP_ANCIENT_STRENGTH -> Ancient Strength (strip RP_ prefix)
		String name = enumName.toUpperCase();
		if (name.startsWith("RP_")) {
			name = name.substring(3);
		}
		String[] words = name.toLowerCase().split("_");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			if (i > 0) sb.append(" ");
			// Capitalize first letter, keep small words lowercase for "from", "of" etc
			String word = words[i];
			if (word.equals("from") || word.equals("of")) {
				sb.append(word);
			} else {
				sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
	}

	/**
	 * Check if a prayer is currently active.
	 */
	public boolean isPrayerActive(String prayerName) {
		try {
			net.runelite.api.Prayer prayer = net.runelite.api.Prayer.valueOf(prayerName.toUpperCase());
			return runOnClientThread(() -> client.getVarbitValue(prayer.getVarbit()) == 1);
		} catch (IllegalArgumentException e) {
			log.warn("Unknown prayer: {}", prayerName);
			return false;
		}
	}

	/**
	 * Get the state of all prayers.
	 */
	public java.util.Map<String, Object> getPrayerState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			java.util.List<String> activePrayers = new java.util.ArrayList<>();

			for (net.runelite.api.Prayer prayer : net.runelite.api.Prayer.values()) {
				if (client.getVarbitValue(prayer.getVarbit()) == 1) {
					activePrayers.add(prayer.name());
				}
			}

			state.put("activePrayers", activePrayers);
			state.put("prayerPoints", client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER));
			state.put("maxPrayer", client.getRealSkillLevel(net.runelite.api.Skill.PRAYER));
			state.put("quickPrayerActive", client.getVarbitValue(net.runelite.api.gameval.VarbitID.QUICKPRAYER_ACTIVE) == 1);
			return state;
		});
	}

	/**
	 * Toggle quick prayers on/off by clicking the quick prayer orb.
	 */
	public boolean toggleQuickPrayer(MouseMovementProfile profile) {
		return clickWidgetByInfo(net.runelite.api.widgets.WidgetInfo.MINIMAP_QUICK_PRAYER_ORB, profile);
	}

	// ===== SPELLBOOK INTERACTION =====

	/**
	 * Cast a spell by clicking its widget in the spellbook.
	 * Opens the magic tab first.
	 * @param spellWidgetPackedId packed widget ID (e.g., InterfaceID.MagicSpellbook.VARROCK_TELEPORT)
	 */
	public boolean castSpell(int spellWidgetPackedId, MouseMovementProfile profile) {
		if (!openPlayerTab(PlayerTab.MAGIC, profile)) {
			log.warn("Could not open magic tab");
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		int groupId = spellWidgetPackedId >> 16;
		int childId = spellWidgetPackedId & 0xFFFF;

		java.awt.Point clickTarget = runOnClientThread(() -> {
			net.runelite.api.widgets.Widget spell = client.getWidget(groupId, childId);
			if (spell == null || spell.isHidden()) {
				log.warn("Spell widget {}.{} not found or hidden", groupId, childId);
				return null;
			}
			return getWidgetClickPoint(spell, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Cast spell widget {}.{}", groupId, childId);
		return true;
	}

	/**
	 * Cast a spell by name. Searches the spellbook widget children for a matching name.
	 */
	public boolean castSpellByName(String spellName, MouseMovementProfile profile) {
		if (!openPlayerTab(PlayerTab.MAGIC, profile)) {
			log.warn("Could not open magic tab");
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		// Spellbook group is 218 (InterfaceID.MAGIC_SPELLBOOK)
		int groupId = 218;
		String searchName = spellName.toLowerCase().trim();

		java.awt.Point clickTarget = runOnClientThread(() -> {
			net.runelite.api.widgets.Widget spellbook = client.getWidget(groupId, 3); // SPELLLAYER
			if (spellbook == null) {
				log.warn("Spellbook widget not found");
				return null;
			}

			// Search static children of the spellbook group for matching spell name
			for (int childIdx = 4; childIdx < 200; childIdx++) {
				net.runelite.api.widgets.Widget spell = client.getWidget(groupId, childIdx);
				if (spell == null || spell.isHidden()) continue;
				String name = spell.getName();
				if (name != null && name.toLowerCase().contains(searchName)) {
					log.info("Found spell '{}' at widget {}.{}", name, groupId, childIdx);
					return getWidgetClickPoint(spell, profile);
				}
			}

			log.warn("Spell '{}' not found in spellbook", spellName);
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Cast spell: {}", spellName);
		return true;
	}

	/**
	 * Cast a spell on an inventory item (e.g., High Alchemy).
	 * Clicks the spell first, then clicks the inventory item.
	 */
	public boolean castSpellOnItem(String spellName, String itemName, MouseMovementProfile profile) {
		if (!castSpellByName(spellName, profile)) {
			return false;
		}
		sleep(200 + (int)(Math.random() * 200));

		// Now click the inventory item
		if (!openPlayerTab(PlayerTab.INVENTORY, profile)) {
			return false;
		}
		sleep(150 + (int)(Math.random() * 100));

		return clickInventoryItem(itemName, profile);
	}

	/**
	 * Get info about the current spellbook.
	 */
	public java.util.Map<String, Object> getSpellbookState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			// Spellbook varbit: 0=standard, 1=ancient, 2=lunar, 3=arceuus
			int spellbookId = client.getVarbitValue(4070);
			String[] names = {"standard", "ancient", "lunar", "arceuus"};
			state.put("spellbook", spellbookId < names.length ? names[spellbookId] : "unknown");
			state.put("spellbookId", spellbookId);
			return state;
		});
	}

	// ===== COMBAT STYLE / AUTOCAST =====

	/**
	 * Set combat style by index (0-3).
	 * 0 = first style, 1 = second, 2 = third, 3 = fourth.
	 */
	public boolean setCombatStyle(int styleIndex, MouseMovementProfile profile) {
		if (styleIndex < 0 || styleIndex > 3) {
			log.warn("Invalid combat style index: {} (must be 0-3)", styleIndex);
			return false;
		}

		if (!openPlayerTab(PlayerTab.COMBAT, profile)) {
			log.warn("Could not open combat tab");
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		net.runelite.api.widgets.WidgetInfo[] styles = {
			net.runelite.api.widgets.WidgetInfo.COMBAT_STYLE_ONE,
			net.runelite.api.widgets.WidgetInfo.COMBAT_STYLE_TWO,
			net.runelite.api.widgets.WidgetInfo.COMBAT_STYLE_THREE,
			net.runelite.api.widgets.WidgetInfo.COMBAT_STYLE_FOUR,
		};

		return clickWidgetByInfo(styles[styleIndex], profile);
	}

	/**
	 * Toggle auto-retaliate.
	 */
	public boolean toggleAutoRetaliate(MouseMovementProfile profile) {
		if (!openPlayerTab(PlayerTab.COMBAT, profile)) {
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		// Retaliate widget
		int packedId = net.runelite.api.gameval.InterfaceID.CombatInterface.RETALIATE;
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point clickTarget = runOnClientThread(() -> {
			net.runelite.api.widgets.Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) return null;
			return getWidgetClickPoint(widget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Get current combat state.
	 */
	public java.util.Map<String, Object> getCombatState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("attackStyle", client.getVarpValue(net.runelite.api.VarPlayer.ATTACK_STYLE));
			state.put("weaponType", client.getVarbitValue(net.runelite.api.Varbits.EQUIPPED_WEAPON_TYPE));
			state.put("autoRetaliate", client.getVarpValue(172) == 0); // 0 = on, 1 = off
			state.put("specialAttackPercent", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
			state.put("specialAttackEnabled", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_ENABLED) == 1);

			// Enhanced fields
			state.put("health", client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS));
			state.put("maxHealth", client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));
			state.put("prayer", client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER));
			state.put("maxPrayer", client.getRealSkillLevel(net.runelite.api.Skill.PRAYER));

			int poisonVal = client.getVarpValue(net.runelite.api.VarPlayer.POISON);
			state.put("poisonStatus", poisonVal);
			if (poisonVal >= 1000000) state.put("poisonType", "venom");
			else if (poisonVal > 0) state.put("poisonType", "poison");
			else if (poisonVal < -38) state.put("poisonType", "venom_immune");
			else if (poisonVal < 0) state.put("poisonType", "poison_immune");
			else state.put("poisonType", "none");

			net.runelite.api.Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null) {
				state.put("isDead", localPlayer.isDead());
				net.runelite.api.Actor target = localPlayer.getInteracting();
				if (target != null) {
					state.put("inCombat", true);
					state.put("targetName", target.getName());
					state.put("targetHealth", target.getHealthRatio());
					state.put("targetMaxHealth", target.getHealthScale());
				} else {
					state.put("inCombat", false);
				}
			}

			return state;
		});
	}

	// ===== SPECIAL ATTACK =====

	/**
	 * Activate special attack by clicking the spec bar in the combat tab.
	 */
	public boolean activateSpecialAttack(MouseMovementProfile profile) {
		if (!openPlayerTab(PlayerTab.COMBAT, profile)) {
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		int packedId = net.runelite.api.gameval.InterfaceID.CombatInterface.SPECIAL_ATTACK;
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point clickTarget = runOnClientThread(() -> {
			net.runelite.api.widgets.Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) {
				// Try spec orb as fallback
				net.runelite.api.widgets.Widget orb = client.getWidget(net.runelite.api.widgets.WidgetInfo.MINIMAP_SPEC_ORB);
				if (orb == null || orb.isHidden()) return null;
				return getWidgetClickPoint(orb, profile);
			}
			return getWidgetClickPoint(widget, profile);
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		log.info("Special attack activated");
		return true;
	}

	/**
	 * Get special attack state.
	 */
	public java.util.Map<String, Object> getSpecialAttackState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("percent", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
			state.put("enabled", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_ENABLED) == 1);
			return state;
		});
	}

	// ===== PLAYER INTERACTION =====

	/**
	 * Right-click a nearby player and select a menu option (e.g., "Lookup", "Trade", "Follow").
	 */
	public boolean rightClickPlayerAndSelect(String playerName, String action, MouseMovementProfile profile) {
		java.awt.Point screenPoint = runOnClientThread(() -> {
			for (net.runelite.api.Player p : client.getPlayers()) {
				if (p == null || p.getName() == null) continue;
				if (p.getName().equalsIgnoreCase(playerName)) {
					net.runelite.api.coords.LocalPoint lp = p.getLocalLocation();
					if (lp == null) return null;
					Point sp = net.runelite.api.Perspective.localToCanvas(client, lp, client.getPlane(), p.getLogicalHeight() / 2);
					return sp != null ? new java.awt.Point(sp.getX(), sp.getY()) : null;
				}
			}
			return null;
		});

		if (screenPoint == null) {
			log.warn("Player '{}' not found on screen", playerName);
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 10);
		int jitterY = (int) ((Math.random() - 0.5) * 10);

		return rightClickAndSelect(screenPoint.x + jitterX, screenPoint.y + jitterY, action, playerName, profile);
	}

	/**
	 * Right-click a nearby NPC and select a menu option (e.g., "Examine", "Talk-to").
	 */
	public boolean rightClickNpcAndSelect(String npcName, String action, MouseMovementProfile profile) {
		return interactWithNPC(npcName, action, profile);
	}

	// ===== ANTI-BAN =====

	public AntiBanService getAntiBanService() {
		return antiBanService;
	}

	// ===== BREAK HANDLER =====

	public BreakHandler getBreakHandler() {
		return breakHandler;
	}

	// ===== IDLE TICK MANAGEMENT =====

	/**
	 * Get current idle tick counts.
	 */
	public java.util.Map<String, Object> getIdleState() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("mouseIdleTicks", client.getMouseIdleTicks());
			state.put("keyboardIdleTicks", client.getKeyboardIdleTicks());
			state.put("idleTimeout", client.getIdleTimeout());
			state.put("mouseLastPressedMs", client.getMouseLastPressedMillis());
			return state;
		});
	}

	/**
	 * Reset idle ticks by dispatching a tiny mouse movement.
	 */
	public void resetIdleTicks() {
		java.awt.Point pos = mouseMovement.getVirtualPosition();
		// Tiny 1px nudge
		int nx = Math.min(pos.x + 1, client.getCanvasWidth() - 1);
		mouseMovement.moveMouse(new java.awt.Point(nx, pos.y), MouseMovementProfile.FAST);
		log.info("Idle ticks reset via mouse nudge");
	}

	// ===== SCRIPTING UTILITIES =====

	/**
	 * Sleep for a random duration with a weighted distribution (more likely near the middle).
	 */
	public void randomSleep(int minMs, int maxMs) {
		// Gaussian-ish distribution centered between min and max
		double mean = (minMs + maxMs) / 2.0;
		double stddev = (maxMs - minMs) / 4.0;
		int sleepMs = (int) (mean + new java.util.Random().nextGaussian() * stddev);
		sleepMs = Math.max(minMs, Math.min(maxMs, sleepMs));
		sleep(sleepMs);
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ===== MAKE / CRAFTING MENU =====

	/**
	 * Widget IDs for Skillmulti item slots (A through R = up to 18 items).
	 */
	private static final int[] SKILLMULTI_ITEM_WIDGETS = {
		InterfaceID.Skillmulti.A, InterfaceID.Skillmulti.B, InterfaceID.Skillmulti.C,
		InterfaceID.Skillmulti.D, InterfaceID.Skillmulti.E, InterfaceID.Skillmulti.F,
		InterfaceID.Skillmulti.G, InterfaceID.Skillmulti.H, InterfaceID.Skillmulti.I,
		InterfaceID.Skillmulti.J, InterfaceID.Skillmulti.K, InterfaceID.Skillmulti.L,
		InterfaceID.Skillmulti.M, InterfaceID.Skillmulti.N, InterfaceID.Skillmulti.O,
		InterfaceID.Skillmulti.P, InterfaceID.Skillmulti.Q, InterfaceID.Skillmulti.R,
	};

	/**
	 * Get the status and options of any open make/crafting menu.
	 * Checks Skillmulti (modern make-X), Chatmenu (text options), and GraphicalMulti.
	 */
	public java.util.Map<String, Object> getMakeMenuStatus() {
		return runOnClientThread(() -> {
			java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();

			// Check Skillmulti (the modern make-X interface)
			Widget skillmultiBottom = client.getWidget(InterfaceID.Skillmulti.BOTTOM);
			if (skillmultiBottom != null && !skillmultiBottom.isHidden()) {
				result.put("open", true);
				result.put("type", "skillmulti");

				// Get title
				Widget titleWidget = client.getWidget(InterfaceID.Skillmulti.TITLE);
				if (titleWidget != null && titleWidget.getText() != null) {
					result.put("title", stripTags(titleWidget.getText()));
				}

				// Get available items
				java.util.List<java.util.Map<String, Object>> options = new java.util.ArrayList<>();
				for (int i = 0; i < SKILLMULTI_ITEM_WIDGETS.length; i++) {
					int packedId = SKILLMULTI_ITEM_WIDGETS[i];
					int groupId = packedId >> 16;
					int childId = packedId & 0xFFFF;
					Widget itemWidget = client.getWidget(groupId, childId);
					if (itemWidget == null || itemWidget.isHidden()) continue;

					// The item widget has children: child 0 = icon, child with text = name
					String name = null;
					Widget[] children = itemWidget.getDynamicChildren();
					if (children != null) {
						for (Widget child : children) {
							if (child != null && child.getText() != null && !child.getText().isEmpty()) {
								name = stripTags(child.getText());
								break;
							}
						}
					}
					// Also check static children
					if (name == null) {
						Widget[] staticChildren = itemWidget.getStaticChildren();
						if (staticChildren != null) {
							for (Widget child : staticChildren) {
								if (child != null && child.getText() != null && !child.getText().isEmpty()) {
									name = stripTags(child.getText());
									break;
								}
							}
						}
					}
					// Fall back to widget name
					if (name == null && itemWidget.getName() != null && !itemWidget.getName().isEmpty()) {
						name = stripTags(itemWidget.getName());
					}

					if (name != null && !name.isEmpty()) {
						java.util.Map<String, Object> opt = new java.util.LinkedHashMap<>();
						opt.put("index", i);
						opt.put("name", name);
						options.add(opt);
					}
				}
				result.put("options", options);
				return result;
			}

			// Check Chatmenu (text dialog options)
			Widget chatmenuOptions = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
			if (chatmenuOptions != null && !chatmenuOptions.isHidden()) {
				java.util.List<Widget> textWidgets = getDialogOptionWidgets(chatmenuOptions);
				if (!textWidgets.isEmpty()) {
					result.put("open", true);
					result.put("type", "chatmenu");
					java.util.List<java.util.Map<String, Object>> options = new java.util.ArrayList<>();
					for (int i = 0; i < textWidgets.size(); i++) {
						Widget tw = textWidgets.get(i);
						java.util.Map<String, Object> opt = new java.util.LinkedHashMap<>();
						opt.put("index", i);
						opt.put("name", stripTags(tw.getText()));
						options.add(opt);
					}
					result.put("options", options);
					return result;
				}
			}

			// Check GraphicalMulti (old 2-choice dialog)
			Widget gm2a = client.getWidget(InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2A);
			Widget gm2b = client.getWidget(InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2B);
			if (gm2a != null && !gm2a.isHidden() && gm2b != null && !gm2b.isHidden()) {
				result.put("open", true);
				result.put("type", "graphical_multi");
				java.util.List<java.util.Map<String, Object>> options = new java.util.ArrayList<>();
				String nameA = gm2a.getText() != null ? stripTags(gm2a.getText()) : "Option A";
				String nameB = gm2b.getText() != null ? stripTags(gm2b.getText()) : "Option B";
				options.add(java.util.Map.of("index", 0, "name", nameA));
				options.add(java.util.Map.of("index", 1, "name", nameB));
				result.put("options", options);
				return result;
			}

			result.put("open", false);
			return result;
		});
	}

	/**
	 * Select a make menu option by name (substring match) or by index.
	 */
	public boolean selectMakeOption(String optionName, int optionIndex, MouseMovementProfile profile) {
		// First determine what type of make menu is open
		java.util.Map<String, Object> status = getMakeMenuStatus();
		if (!(Boolean) status.getOrDefault("open", false)) {
			log.warn("No make menu is open");
			return false;
		}

		String type = (String) status.get("type");

		if ("chatmenu".equals(type)) {
			// Use existing dialog selection
			if (optionName != null) {
				return selectDialogOption(optionName, profile);
			} else {
				@SuppressWarnings("unchecked")
				java.util.List<java.util.Map<String, Object>> options =
					(java.util.List<java.util.Map<String, Object>>) status.get("options");
				if (optionIndex >= 0 && optionIndex < options.size()) {
					return selectDialogOption((String) options.get(optionIndex).get("name"), profile);
				}
			}
			return false;
		}

		if ("skillmulti".equals(type)) {
			return selectSkillmultiOption(optionName, optionIndex, profile);
		}

		if ("graphical_multi".equals(type)) {
			return selectGraphicalMultiOption(optionName, optionIndex, profile);
		}

		log.warn("Unknown make menu type: {}", type);
		return false;
	}

	private boolean selectSkillmultiOption(String optionName, int optionIndex, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			// If searching by name, find the matching widget
			if (optionName != null) {
				String search = optionName.toLowerCase();
				for (int packedId : SKILLMULTI_ITEM_WIDGETS) {
					int groupId = packedId >> 16;
					int childId = packedId & 0xFFFF;
					Widget itemWidget = client.getWidget(groupId, childId);
					if (itemWidget == null || itemWidget.isHidden()) continue;

					String name = getSkillmultiItemName(itemWidget);
					if (name != null && name.toLowerCase().contains(search)) {
						log.info("Found skillmulti option '{}' matching '{}'", name, optionName);
						return getWidgetClickPoint(itemWidget, profile);
					}
				}
				log.warn("Skillmulti option '{}' not found", optionName);
				return null;
			}

			// By index
			if (optionIndex >= 0 && optionIndex < SKILLMULTI_ITEM_WIDGETS.length) {
				int packedId = SKILLMULTI_ITEM_WIDGETS[optionIndex];
				int groupId = packedId >> 16;
				int childId = packedId & 0xFFFF;
				Widget itemWidget = client.getWidget(groupId, childId);
				if (itemWidget != null && !itemWidget.isHidden()) {
					return getWidgetClickPoint(itemWidget, profile);
				}
			}
			log.warn("Skillmulti option index {} not found", optionIndex);
			return null;
		});

		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	private String getSkillmultiItemName(Widget itemWidget) {
		// Check dynamic children for text
		Widget[] children = itemWidget.getDynamicChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && child.getText() != null && !child.getText().isEmpty()) {
					return stripTags(child.getText());
				}
			}
		}
		// Check static children
		Widget[] staticChildren = itemWidget.getStaticChildren();
		if (staticChildren != null) {
			for (Widget child : staticChildren) {
				if (child != null && child.getText() != null && !child.getText().isEmpty()) {
					return stripTags(child.getText());
				}
			}
		}
		// Fall back to widget name
		if (itemWidget.getName() != null && !itemWidget.getName().isEmpty()) {
			return stripTags(itemWidget.getName());
		}
		return null;
	}

	private boolean selectGraphicalMultiOption(String optionName, int optionIndex, MouseMovementProfile profile) {
		int targetPackedId;
		if (optionName != null) {
			// Match by text
			String search = optionName.toLowerCase();
			Boolean matchA = runOnClientThread(() -> {
				Widget w = client.getWidget(InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2A);
				return w != null && !w.isHidden() && w.getText() != null
					&& stripTags(w.getText()).toLowerCase().contains(search);
			});
			targetPackedId = Boolean.TRUE.equals(matchA)
				? InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2A
				: InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2B;
		} else {
			targetPackedId = optionIndex == 0
				? InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2A
				: InterfaceID.GraphicalMulti.GRAPHICAL_MULTI_2B;
		}
		return clickWidgetByPackedId(targetPackedId, profile);
	}

	/**
	 * Set the make menu quantity (Skillmulti only).
	 */
	public boolean setMakeQuantity(int quantity, MouseMovementProfile profile) {
		int widgetId;
		switch (quantity) {
			case 1:  widgetId = InterfaceID.Skillmulti._1; break;
			case 5:  widgetId = InterfaceID.Skillmulti._5; break;
			case 10: widgetId = InterfaceID.Skillmulti._10; break;
			case -1: widgetId = InterfaceID.Skillmulti.X; break;
			case 0:  widgetId = InterfaceID.Skillmulti.ALL; break;
			default:
				log.warn("Invalid make quantity: {} (use 1, 5, 10, -1=X, 0=All)", quantity);
				return false;
		}
		return clickWidgetByPackedId(widgetId, profile);
	}

	// ===== SHOP INTERACTION =====

	/**
	 * Check if the shop interface is currently open.
	 */
	public boolean isShopOpen() {
		return runOnClientThread(() -> {
			Widget shopWidget = client.getWidget(InterfaceID.Shopmain.ITEMS);
			return shopWidget != null && !shopWidget.isHidden();
		});
	}

	/**
	 * Get all items currently in the shop.
	 */
	public java.util.List<java.util.Map<String, Object>> getShopItems() {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
			Widget shopWidget = client.getWidget(InterfaceID.Shopmain.ITEMS);
			if (shopWidget == null || shopWidget.isHidden()) return items;

			Widget[] children = shopWidget.getDynamicChildren();
			if (children == null) return items;

			for (int i = 0; i < children.length; i++) {
				Widget child = children[i];
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
				item.put("slot", i);
				item.put("id", child.getItemId());
				item.put("name", def != null ? def.getName() : "Unknown");
				item.put("quantity", child.getItemQuantity());
				items.add(item);
			}
			return items;
		});
	}

	/**
	 * Click a shop item by name (left-click = buy default quantity).
	 */
	public boolean clickShopItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget shopWidget = client.getWidget(InterfaceID.Shopmain.ITEMS);
			if (shopWidget == null || shopWidget.isHidden()) {
				log.warn("Shop not open");
				return null;
			}

			Widget[] children = shopWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					log.info("Found shop item: {} (id={})", def.getName(), child.getItemId());
					return getWidgetClickPoint(child, profile);
				}
			}

			log.warn("Shop item '{}' not found", itemName);
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click a shop item and select an option (e.g., "Buy 5", "Buy 10", "Buy 50", "Value").
	 */
	public boolean rightClickShopItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		Point itemPoint = runOnClientThread(() -> {
			Widget shopWidget = client.getWidget(InterfaceID.Shopmain.ITEMS);
			if (shopWidget == null || shopWidget.isHidden()) {
				log.warn("Shop not open");
				return null;
			}

			Widget[] children = shopWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					return getWidgetScreenPoint(child);
				}
			}

			log.warn("Shop item '{}' not found", itemName);
			return null;
		});

		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * defaultJitter() * 2);
		int jitterY = (int) ((Math.random() - 0.5) * defaultJitter() * 2);

		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, itemName, profile
		);
	}

	/**
	 * Click a shop inventory item by name (left-click = sell default quantity).
	 * Shop inventory is the player's inventory panel shown beside the shop.
	 */
	public boolean clickShopInventoryItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget shopInvWidget = client.getWidget(InterfaceID.Shopside.ITEMS);
			if (shopInvWidget == null || shopInvWidget.isHidden()) {
				log.warn("Shop inventory panel not open");
				return null;
			}

			Widget[] children = shopInvWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					log.info("Found shop inventory item: {}", def.getName());
					return getWidgetClickPoint(child, profile);
				}
			}

			log.warn("Shop inventory item '{}' not found", itemName);
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click a shop inventory item and select an option (e.g., "Sell 1", "Sell 5", "Sell 10", "Sell 50", "Value").
	 */
	public boolean rightClickShopInventoryItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		Point itemPoint = runOnClientThread(() -> {
			Widget shopInvWidget = client.getWidget(InterfaceID.Shopside.ITEMS);
			if (shopInvWidget == null || shopInvWidget.isHidden()) {
				log.warn("Shop inventory panel not open");
				return null;
			}

			Widget[] children = shopInvWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					return getWidgetScreenPoint(child);
				}
			}

			log.warn("Shop inventory item '{}' not found", itemName);
			return null;
		});

		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * defaultJitter() * 2);
		int jitterY = (int) ((Math.random() - 0.5) * defaultJitter() * 2);

		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, itemName, profile
		);
	}

	/**
	 * Set the shop quantity mode (1, 5, 10, 50).
	 */
	public boolean setShopQuantity(int quantity, MouseMovementProfile profile) {
		int widgetId;
		switch (quantity) {
			case 1:  widgetId = InterfaceID.Shopmain.QUANTITY1; break;
			case 5:  widgetId = InterfaceID.Shopmain.QUANTITY5; break;
			case 10: widgetId = InterfaceID.Shopmain.QUANTITY10; break;
			case 50: widgetId = InterfaceID.Shopmain.QUANTITY50; break;
			default:
				log.warn("Invalid shop quantity: {} (must be 1, 5, 10, or 50)", quantity);
				return false;
		}
		return clickWidgetByPackedId(widgetId, profile);
	}

	/**
	 * Close the shop interface by clicking the close button.
	 */
	public boolean closeShop(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget frameWidget = client.getWidget(InterfaceID.Shopmain.FRAME);
			if (frameWidget == null || frameWidget.isHidden()) {
				log.warn("Shop frame not found");
				return null;
			}
			// Close button is dynamic child 11 of the frame (same pattern as bank)
			Widget[] children = frameWidget.getDynamicChildren();
			if (children != null && children.length > 11) {
				Widget closeBtn = children[11];
				if (closeBtn != null && !closeBtn.isHidden()) {
					return getWidgetClickPoint(closeBtn, profile);
				}
			}
			// Fallback: try static children
			Widget[] staticChildren = frameWidget.getStaticChildren();
			if (staticChildren != null) {
				for (Widget child : staticChildren) {
					if (child != null && !child.isHidden()) {
						String[] actions = child.getActions();
						if (actions != null) {
							for (String action : actions) {
								if ("Close".equals(action)) {
									return getWidgetClickPoint(child, profile);
								}
							}
						}
					}
				}
			}
			log.warn("Shop close button not found");
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	// ===== USE ITEM ON ITEM / OBJECT / NPC =====

	/**
	 * Use one inventory item on another inventory item.
	 * Right-clicks the first item, selects "Use", then clicks the second item.
	 */
	public boolean useItemOnItem(String sourceItem, String targetItem, MouseMovementProfile profile) {
		// Step 1: Open inventory if needed
		if (!openPlayerTab(PlayerTab.INVENTORY, profile)) {
			log.warn("Could not open inventory tab");
			return false;
		}
		sleep(100 + (int)(Math.random() * 100));

		// Step 2: Right-click source item and select "Use"
		if (!rightClickInventoryItemAndSelect(sourceItem, "Use", profile)) {
			log.warn("Could not select 'Use' on '{}'", sourceItem);
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		// Step 3: Click the target item
		if (!clickInventoryItem(targetItem, profile)) {
			log.warn("Could not click target item '{}'", targetItem);
			return false;
		}

		log.info("Used '{}' on '{}'", sourceItem, targetItem);
		return true;
	}

	/**
	 * Use an inventory item on a game object.
	 * Right-clicks the item, selects "Use", then clicks the game object.
	 */
	public boolean useItemOnObject(String itemName, String objectName, MouseMovementProfile profile) {
		// Step 1: Open inventory if needed
		if (!openPlayerTab(PlayerTab.INVENTORY, profile)) {
			log.warn("Could not open inventory tab");
			return false;
		}
		sleep(100 + (int)(Math.random() * 100));

		// Step 2: Right-click item and select "Use"
		if (!rightClickInventoryItemAndSelect(itemName, "Use", profile)) {
			log.warn("Could not select 'Use' on '{}'", itemName);
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		// Step 3: Click the game object
		if (!interactWithObject(objectName, profile)) {
			log.warn("Could not click object '{}'", objectName);
			return false;
		}

		log.info("Used '{}' on object '{}'", itemName, objectName);
		return true;
	}

	/**
	 * Use an inventory item on an NPC.
	 * Right-clicks the item, selects "Use", then clicks the NPC.
	 */
	public boolean useItemOnNPC(String itemName, String npcName, MouseMovementProfile profile) {
		// Step 1: Open inventory if needed
		if (!openPlayerTab(PlayerTab.INVENTORY, profile)) {
			log.warn("Could not open inventory tab");
			return false;
		}
		sleep(100 + (int)(Math.random() * 100));

		// Step 2: Right-click item and select "Use"
		if (!rightClickInventoryItemAndSelect(itemName, "Use", profile)) {
			log.warn("Could not select 'Use' on '{}'", itemName);
			return false;
		}
		sleep(150 + (int)(Math.random() * 150));

		// Step 3: Click the NPC
		if (!interactWithNPC(npcName, profile)) {
			log.warn("Could not click NPC '{}'", npcName);
			return false;
		}

		log.info("Used '{}' on NPC '{}'", itemName, npcName);
		return true;
	}

	// ===== DEPOSIT BOX INTERACTION =====

	/**
	 * Check if the deposit box interface is currently open.
	 */
	public boolean isDepositBoxOpen() {
		return runOnClientThread(() -> {
			Widget dbWidget = client.getWidget(InterfaceID.BankDepositbox.CONTENTS);
			return dbWidget != null && !dbWidget.isHidden();
		});
	}

	/**
	 * Get items in the deposit box inventory view.
	 */
	public java.util.List<java.util.Map<String, Object>> getDepositBoxItems() {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
			Widget invWidget = client.getWidget(InterfaceID.BankDepositbox.INVENTORY);
			if (invWidget == null || invWidget.isHidden()) return items;

			Widget[] children = invWidget.getDynamicChildren();
			if (children == null) return items;

			for (int i = 0; i < children.length; i++) {
				Widget child = children[i];
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
				item.put("slot", i);
				item.put("id", child.getItemId());
				item.put("name", def != null ? def.getName() : "Unknown");
				item.put("quantity", child.getItemQuantity());
				items.add(item);
			}
			return items;
		});
	}

	/**
	 * Click a deposit box inventory item by name (left-click = deposit default quantity).
	 */
	public boolean clickDepositBoxItem(String itemName, MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget invWidget = client.getWidget(InterfaceID.BankDepositbox.INVENTORY);
			if (invWidget == null || invWidget.isHidden()) {
				log.warn("Deposit box inventory not open");
				return null;
			}

			Widget[] children = invWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					log.info("Found deposit box item: {}", def.getName());
					return getWidgetClickPoint(child, profile);
				}
			}

			log.warn("Deposit box item '{}' not found", itemName);
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Right-click a deposit box item and select an option (e.g., "Deposit-1", "Deposit-5", "Deposit-All").
	 */
	public boolean rightClickDepositBoxItemAndSelect(String itemName, String option, MouseMovementProfile profile) {
		Point itemPoint = runOnClientThread(() -> {
			Widget invWidget = client.getWidget(InterfaceID.BankDepositbox.INVENTORY);
			if (invWidget == null || invWidget.isHidden()) {
				log.warn("Deposit box inventory not open");
				return null;
			}

			Widget[] children = invWidget.getDynamicChildren();
			if (children == null) return null;

			for (Widget child : children) {
				if (child == null || child.getItemId() <= 0) continue;
				ItemComposition def = client.getItemDefinition(child.getItemId());
				if (def != null && def.getName().toLowerCase().contains(itemName.toLowerCase())) {
					return getWidgetScreenPoint(child);
				}
			}

			log.warn("Deposit box item '{}' not found", itemName);
			return null;
		});

		if (itemPoint == null) return false;

		int jitterX = (int) ((Math.random() - 0.5) * defaultJitter() * 2);
		int jitterY = (int) ((Math.random() - 0.5) * defaultJitter() * 2);

		return rightClickAndSelect(
			itemPoint.getX() + jitterX,
			itemPoint.getY() + jitterY,
			option, itemName, profile
		);
	}

	/**
	 * Click the deposit-inventory button in the deposit box.
	 */
	public boolean depositBoxDepositInventory(MouseMovementProfile profile) {
		return clickWidgetByPackedId(InterfaceID.BankDepositbox.DEPOSIT_INV, profile);
	}

	/**
	 * Click the deposit-equipment button in the deposit box.
	 */
	public boolean depositBoxDepositEquipment(MouseMovementProfile profile) {
		return clickWidgetByPackedId(InterfaceID.BankDepositbox.DEPOSIT_WORN, profile);
	}

	/**
	 * Click the deposit-looting-bag button in the deposit box.
	 */
	public boolean depositBoxDepositLootingBag(MouseMovementProfile profile) {
		return clickWidgetByPackedId(InterfaceID.BankDepositbox.DEPOSIT_LOOTINGBAG, profile);
	}

	/**
	 * Set deposit box quantity mode (1, 5, 10, -1=X, 0=All).
	 */
	public boolean setDepositBoxQuantity(int quantity, MouseMovementProfile profile) {
		int widgetId;
		switch (quantity) {
			case 1:  widgetId = InterfaceID.BankDepositbox._1; break;
			case 5:  widgetId = InterfaceID.BankDepositbox._5; break;
			case 10: widgetId = InterfaceID.BankDepositbox._10; break;
			case -1: widgetId = InterfaceID.BankDepositbox.X; break;
			case 0:  widgetId = InterfaceID.BankDepositbox.ALL; break;
			default:
				log.warn("Invalid deposit box quantity: {} (use 1, 5, 10, -1=X, 0=All)", quantity);
				return false;
		}
		return clickWidgetByPackedId(widgetId, profile);
	}

	/**
	 * Close the deposit box.
	 */
	public boolean closeDepositBox(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget frameWidget = client.getWidget(InterfaceID.BankDepositbox.FRAME);
			if (frameWidget == null || frameWidget.isHidden()) {
				log.warn("Deposit box frame not found");
				return null;
			}
			Widget[] children = frameWidget.getDynamicChildren();
			if (children != null && children.length > 11) {
				Widget closeBtn = children[11];
				if (closeBtn != null && !closeBtn.isHidden()) {
					return getWidgetClickPoint(closeBtn, profile);
				}
			}
			Widget[] staticChildren = frameWidget.getStaticChildren();
			if (staticChildren != null) {
				for (Widget child : staticChildren) {
					if (child != null && !child.isHidden()) {
						String[] actions = child.getActions();
						if (actions != null) {
							for (String action : actions) {
								if ("Close".equals(action)) {
									return getWidgetClickPoint(child, profile);
								}
							}
						}
					}
				}
			}
			log.warn("Deposit box close button not found");
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	// ===== MINIMAP CLICK =====

	/**
	 * Click a world coordinate on the minimap. Returns false if the point is out of minimap range (~20 tiles).
	 */
	public boolean clickMinimap(int worldX, int worldY, int plane, MouseMovementProfile profile) {
		Point minimapPoint = runOnClientThread(() -> {
			net.runelite.api.WorldView wv = client.getTopLevelWorldView();
			if (wv == null) return null;

			net.runelite.api.coords.LocalPoint localPoint = net.runelite.api.coords.LocalPoint.fromWorld(
				wv, new WorldPoint(worldX, worldY, plane));
			if (localPoint == null) return null;

			return net.runelite.api.Perspective.localToMinimap(client, localPoint);
		});

		if (minimapPoint == null) {
			log.warn("Cannot click minimap for ({}, {}, {}) — out of range or not in scene", worldX, worldY, plane);
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 4);
		int jitterY = (int) ((Math.random() - 0.5) * 4);

		log.info("Clicking minimap at screen({},{}) for world({},{},{})",
			minimapPoint.getX() + jitterX, minimapPoint.getY() + jitterY, worldX, worldY, plane);

		mouseMovement.moveAndClick(
			new java.awt.Point(minimapPoint.getX() + jitterX, minimapPoint.getY() + jitterY),
			profile
		);
		return true;
	}

	/**
	 * Click a relative offset on the minimap from the player's current position.
	 * dx/dy are in tiles (e.g., dx=5, dy=0 = 5 tiles east).
	 */
	public boolean clickMinimapRelative(int dx, int dy, MouseMovementProfile profile) {
		WorldPoint playerPos = runOnClientThread(() -> client.getLocalPlayer().getWorldLocation());
		if (playerPos == null) return false;
		return clickMinimap(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getPlane(), profile);
	}

	// ===== GRAND EXCHANGE INTERACTION =====

	/**
	 * Check if the Grand Exchange interface is open.
	 */
	public boolean isGrandExchangeOpen() {
		return runOnClientThread(() -> {
			Widget geWidget = client.getWidget(InterfaceID.GE_OFFERS, 0);
			return geWidget != null && !geWidget.isHidden();
		});
	}

	/**
	 * Get all 8 GE offer slot states using the RuneLite API.
	 */
	public java.util.List<java.util.Map<String, Object>> getGrandExchangeOffers() {
		return runOnClientThread(() -> {
			java.util.List<java.util.Map<String, Object>> offers = new java.util.ArrayList<>();
			net.runelite.api.GrandExchangeOffer[] geOffers = client.getGrandExchangeOffers();
			if (geOffers == null) return offers;

			for (int i = 0; i < geOffers.length; i++) {
				net.runelite.api.GrandExchangeOffer offer = geOffers[i];
				java.util.Map<String, Object> offerMap = new java.util.LinkedHashMap<>();
				offerMap.put("slot", i);
				if (offer == null) {
					offerMap.put("state", "EMPTY");
				} else {
					offerMap.put("state", offer.getState().name());
					offerMap.put("itemId", offer.getItemId());
					if (offer.getItemId() > 0) {
						try {
							offerMap.put("itemName", client.getItemDefinition(offer.getItemId()).getName());
						} catch (Exception e) {
							offerMap.put("itemName", "Unknown");
						}
					}
					offerMap.put("price", offer.getPrice());
					offerMap.put("totalQuantity", offer.getTotalQuantity());
					offerMap.put("quantitySold", offer.getQuantitySold());
					offerMap.put("spent", offer.getSpent());
				}
				offers.add(offerMap);
			}
			return offers;
		});
	}

	/**
	 * Click a GE offer slot (0-7) to select it.
	 */
	public boolean clickGrandExchangeSlot(int slot, MouseMovementProfile profile) {
		if (slot < 0 || slot > 7) {
			log.warn("Invalid GE slot: {}", slot);
			return false;
		}
		// INDEX_0 through INDEX_7
		int packedId = InterfaceID.GeOffers.INDEX_0 + slot;
		return clickWidgetByPackedId(packedId, profile);
	}

	/**
	 * Click the "Buy" button in the GE setup panel.
	 * Must have a slot selected first (click an empty slot).
	 */
	public boolean clickGrandExchangeBuy(int slot, MouseMovementProfile profile) {
		if (!isGrandExchangeOpen()) {
			log.warn("GE is not open");
			return false;
		}
		// Click the slot first
		if (!clickGrandExchangeSlot(slot, profile)) return false;
		sleep(300 + (int)(Math.random() * 200));

		// The buy button is the first child of the slot widget — look for "Buy" action
		// In practice, clicking an empty slot opens the buy/sell choice
		// The buy icon is a child widget with "Buy offer" or similar
		// We need to find the buy button within the slot
		int geGroup = InterfaceID.GE_OFFERS;
		java.awt.Point buyPoint = runOnClientThread(() -> {
			// The buy button is at known position within each slot
			// Each slot has a buy and sell button as children
			// Slot widgets are INDEX_0 + slot, buy button is typically child 0
			Widget slotWidget = client.getWidget(geGroup, 7 + slot); // INDEX_0 = child 7
			if (slotWidget == null) return null;

			Widget[] children = slotWidget.getDynamicChildren();
			if (children == null) children = slotWidget.getStaticChildren();
			if (children != null) {
				for (Widget child : children) {
					if (child == null) continue;
					String[] actions = child.getActions();
					if (actions != null) {
						for (String action : actions) {
							if (action != null && action.toLowerCase().contains("buy")) {
								return getWidgetClickPoint(child, profile);
							}
						}
					}
				}
			}
			// Fallback: right-click the slot and select "Create Buy offer"
			return null;
		});

		if (buyPoint != null) {
			mouseMovement.moveAndClick(buyPoint, profile);
			return true;
		}

		// Fallback: right-click the slot and select buy
		return rightClickWidgetAndSelect(InterfaceID.GeOffers.INDEX_0 + slot, "Buy offer", profile);
	}

	/**
	 * Click the "Sell" button in the GE setup panel.
	 */
	public boolean clickGrandExchangeSell(int slot, MouseMovementProfile profile) {
		if (!isGrandExchangeOpen()) {
			log.warn("GE is not open");
			return false;
		}
		if (!clickGrandExchangeSlot(slot, profile)) return false;
		sleep(300 + (int)(Math.random() * 200));

		int geGroup = InterfaceID.GE_OFFERS;
		java.awt.Point sellPoint = runOnClientThread(() -> {
			Widget slotWidget = client.getWidget(geGroup, 7 + slot);
			if (slotWidget == null) return null;

			Widget[] children = slotWidget.getDynamicChildren();
			if (children == null) children = slotWidget.getStaticChildren();
			if (children != null) {
				for (Widget child : children) {
					if (child == null) continue;
					String[] actions = child.getActions();
					if (actions != null) {
						for (String action : actions) {
							if (action != null && action.toLowerCase().contains("sell")) {
								return getWidgetClickPoint(child, profile);
							}
						}
					}
				}
			}
			return null;
		});

		if (sellPoint != null) {
			mouseMovement.moveAndClick(sellPoint, profile);
			return true;
		}

		return rightClickWidgetAndSelect(InterfaceID.GeOffers.INDEX_0 + slot, "Sell offer", profile);
	}

	/**
	 * Search for an item in the GE by name.
	 * Must be in the buy setup screen.
	 * Types the name, waits for results, then clicks the exact match from the search results list.
	 */
	public boolean searchGrandExchangeItem(String itemName, MouseMovementProfile profile) {
		sleep(300 + (int)(Math.random() * 200));
		typeText(itemName);
		sleep(800 + (int)(Math.random() * 400));

		// Search results are dynamic children of Chatbox.MES_LAYER_SCROLLCONTENTS
		int searchResultsPackedId = InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS;
		int groupId = searchResultsPackedId >> 16;
		int childId = searchResultsPackedId & 0xFFFF;

		java.awt.Point matchPoint = runOnClientThread(() -> {
			Widget resultsWidget = client.getWidget(groupId, childId);
			if (resultsWidget == null || resultsWidget.isHidden()) {
				log.warn("GE search results widget not found");
				return null;
			}

			Widget[] children = resultsWidget.getDynamicChildren();
			if (children == null || children.length == 0) {
				log.warn("GE search results has no children");
				return null;
			}

			// Search for exact name match first
			for (Widget child : children) {
				if (child == null || child.isHidden()) continue;
				String text = child.getText();
				if (text != null) {
					String cleanText = text.replaceAll("<[^>]+>", "").trim();
					if (cleanText.equalsIgnoreCase(itemName)) {
						log.info("Found exact GE search match: '{}'", cleanText);
						return getWidgetClickPoint(child, profile);
					}
				}
				// Also check item name via itemId
				int itemId = child.getItemId();
				if (itemId > 0) {
					String name = client.getItemDefinition(itemId).getName();
					if (name != null && name.equalsIgnoreCase(itemName)) {
						log.info("Found exact GE search match by itemId: '{}' (id={})", name, itemId);
						return getWidgetClickPoint(child, profile);
					}
				}
			}

			// Log what we found for debugging
			log.warn("No exact match for '{}' in GE search results. Found {} children:", itemName, children.length);
			for (int i = 0; i < Math.min(children.length, 10); i++) {
				Widget child = children[i];
				if (child == null) continue;
				String text = child.getText();
				int itemId = child.getItemId();
				String itemDefName = itemId > 0 ? client.getItemDefinition(itemId).getName() : null;
				log.warn("  Result[{}]: text='{}', itemId={}, itemDefName='{}'", i, text, itemId, itemDefName);
			}

			return null;
		});

		if (matchPoint != null) {
			mouseMovement.moveAndClick(matchPoint, profile);
			sleep(300 + (int)(Math.random() * 200));
			return true;
		}

		// Fallback: press Enter to select the first result
		log.warn("Exact match not found for '{}', falling back to Enter (first result)", itemName);
		pressEnter();
		sleep(300 + (int)(Math.random() * 200));
		return true;
	}

	/**
	 * Overload without profile for backwards compatibility.
	 */
	public boolean searchGrandExchangeItem(String itemName) {
		return searchGrandExchangeItem(itemName, MouseMovementProfile.NORMAL);
	}

	/**
	 * Set the price in the GE offer setup.
	 * Clicks the price input area, clears it, and types the new price.
	 */
	public boolean setGrandExchangePrice(int price, MouseMovementProfile profile) {
		// Click the price area — it's within the SETUP section
		int packedId = InterfaceID.GeOffers.SETUP_MARKETPRICE;
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		// Right-click the price area and select "Enter price"
		// Or click the price text to get the input dialog
		java.awt.Point pricePoint = runOnClientThread(() -> {
			Widget priceWidget = client.getWidget(groupId, childId);
			if (priceWidget == null || priceWidget.isHidden()) return null;
			return getWidgetClickPoint(priceWidget, profile);
		});

		if (pricePoint == null) {
			log.warn("Could not find GE price widget");
			return false;
		}

		mouseMovement.moveAndClick(pricePoint, profile);
		sleep(300 + (int)(Math.random() * 200));

		// Type the price
		typeText(String.valueOf(price));
		sleep(100 + (int)(Math.random() * 100));
		pressEnter();
		sleep(200 + (int)(Math.random() * 100));
		return true;
	}

	/**
	 * Set the quantity in the GE offer setup.
	 */
	public boolean setGrandExchangeQuantity(int quantity, MouseMovementProfile profile) {
		// The quantity widget is in the SETUP area
		// We need to click the quantity text to get an input dialog
		int geGroup = InterfaceID.GE_OFFERS;
		java.awt.Point qtyPoint = runOnClientThread(() -> {
			// SETUP is child 0x1a = 26
			Widget setupWidget = client.getWidget(geGroup, 26);
			if (setupWidget == null || setupWidget.isHidden()) return null;

			// Look for the quantity input area within setup children
			Widget[] children = setupWidget.getStaticChildren();
			if (children != null) {
				for (Widget child : children) {
					if (child == null) continue;
					String[] actions = child.getActions();
					if (actions != null) {
						for (String action : actions) {
							if (action != null && action.toLowerCase().contains("quantity")) {
								return getWidgetClickPoint(child, profile);
							}
						}
					}
				}
			}
			return null;
		});

		if (qtyPoint != null) {
			mouseMovement.moveAndClick(qtyPoint, profile);
			sleep(300 + (int)(Math.random() * 200));
			typeText(String.valueOf(quantity));
			sleep(100 + (int)(Math.random() * 100));
			pressEnter();
			return true;
		}

		log.warn("Could not find GE quantity widget");
		return false;
	}

	/**
	 * Confirm the current GE offer.
	 */
	public boolean confirmGrandExchangeOffer(MouseMovementProfile profile) {
		return clickWidgetByPackedId(InterfaceID.GeOffers.SETUP_CONFIRM, profile);
	}

	/**
	 * Collect all completed GE offers.
	 * Tries the COLLECTALL button first. If not visible, tries individual slot collect buttons.
	 */
	public boolean collectGrandExchangeOffers(MouseMovementProfile profile) {
		// Try the main COLLECTALL button first
		int packedId = InterfaceID.GeOffers.COLLECTALL;
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point collectPoint = runOnClientThread(() -> {
			Widget widget = client.getWidget(groupId, childId);
			if (widget != null && !widget.isHidden()) {
				// Check dynamic children — the actual clickable button may be a child
				Widget[] dynChildren = widget.getDynamicChildren();
				if (dynChildren != null && dynChildren.length > 0) {
					for (Widget child : dynChildren) {
						if (child != null && !child.isHidden()) {
							String[] actions = child.getActions();
							if (actions != null) {
								for (String action : actions) {
									if (action != null && action.toLowerCase().contains("collect")) {
										log.info("Found collect button as dynamic child: action='{}'", action);
										return getWidgetClickPoint(child, profile);
									}
								}
							}
						}
					}
				}
				// Check static children
				Widget[] statChildren = widget.getStaticChildren();
				if (statChildren != null && statChildren.length > 0) {
					for (Widget child : statChildren) {
						if (child != null && !child.isHidden()) {
							String[] actions = child.getActions();
							if (actions != null) {
								for (String action : actions) {
									if (action != null && action.toLowerCase().contains("collect")) {
										log.info("Found collect button as static child: action='{}'", action);
										return getWidgetClickPoint(child, profile);
									}
								}
							}
						}
					}
				}
				// The widget itself might be clickable
				String[] actions = widget.getActions();
				if (actions != null) {
					for (String action : actions) {
						if (action != null && action.toLowerCase().contains("collect")) {
							log.info("COLLECTALL widget is directly clickable: action='{}'", action);
							return getWidgetClickPoint(widget, profile);
						}
					}
				}
				log.info("COLLECTALL widget found but no collect action. Clicking it anyway.");
				return getWidgetClickPoint(widget, profile);
			}
			log.warn("COLLECTALL widget (group={}, child={}) not found or hidden", groupId, childId);

			// Fallback: look for collect buttons on individual slots
			// When viewing a specific slot, DETAILS_COLLECT is visible
			Widget detailsCollect = client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT >> 16, InterfaceID.GeOffers.DETAILS_COLLECT & 0xFFFF);
			if (detailsCollect != null && !detailsCollect.isHidden()) {
				log.info("Using DETAILS_COLLECT widget as fallback");
				return getWidgetClickPoint(detailsCollect, profile);
			}

			return null;
		});

		if (collectPoint == null) {
			log.warn("Could not find any collect button in GE");
			return false;
		}

		mouseMovement.moveAndClick(collectPoint, profile);
		log.info("Clicked GE collect button");
		return true;
	}

	/**
	 * Abort a GE offer by right-clicking the slot and selecting "Abort offer".
	 */
	public boolean abortGrandExchangeOffer(int slot, MouseMovementProfile profile) {
		if (slot < 0 || slot > 7) return false;
		int packedId = InterfaceID.GeOffers.INDEX_0 + slot;

		// Right-click the slot widget and select "Abort offer"
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point slotPoint = runOnClientThread(() -> {
			Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) return null;
			return getWidgetClickPoint(widget, profile);
		});

		if (slotPoint == null) return false;

		return rightClickAndSelect(slotPoint.x, slotPoint.y, "Abort offer", null, profile);
	}

	/**
	 * View the details/status of a specific GE slot by clicking it.
	 */
	public boolean viewGrandExchangeSlot(int slot, MouseMovementProfile profile) {
		if (slot < 0 || slot > 7) return false;
		int packedId = InterfaceID.GeOffers.INDEX_0 + slot;

		// Left-click to view
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point point = runOnClientThread(() -> {
			Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) return null;
			return getWidgetClickPoint(widget, profile);
		});

		if (point == null) return false;
		mouseMovement.moveAndClick(point, profile);
		return true;
	}

	/**
	 * Close the Grand Exchange interface.
	 */
	public boolean closeGrandExchange(MouseMovementProfile profile) {
		java.awt.Point clickTarget = runOnClientThread(() -> {
			Widget geFrame = client.getWidget(InterfaceID.GeOffers.FRAME);
			if (geFrame == null || geFrame.isHidden()) {
				log.warn("GE is not open (FRAME widget not found)");
				return null;
			}

			// Search dynamic children for the close button (has "Close" action)
			Widget[] children = geFrame.getDynamicChildren();
			if (children != null) {
				for (int i = children.length - 1; i >= 0; i--) {
					Widget child = children[i];
					if (child == null || child.isHidden()) continue;
					String[] actions = child.getActions();
					if (actions != null) {
						for (String action : actions) {
							if ("Close".equals(action)) {
								log.info("Found GE close button at dynamic child {}", i);
								return getWidgetClickPoint(child, profile);
							}
						}
					}
				}
				// Fallback: try child 11 (standard OSRS close button index)
				if (children.length > 11) {
					Widget closeButton = children[11];
					if (closeButton != null && !closeButton.isHidden()) {
						log.info("Using GE close button at dynamic child 11 (fallback)");
						return getWidgetClickPoint(closeButton, profile);
					}
				}
			}

			// Fallback: try static children
			Widget[] staticChildren = geFrame.getStaticChildren();
			if (staticChildren != null) {
				for (int i = staticChildren.length - 1; i >= 0; i--) {
					Widget child = staticChildren[i];
					if (child == null || child.isHidden()) continue;
					String[] actions = child.getActions();
					if (actions != null) {
						for (String action : actions) {
							if ("Close".equals(action)) {
								log.info("Found GE close button at static child {}", i);
								return getWidgetClickPoint(child, profile);
							}
						}
					}
				}
			}

			log.warn("Could not find GE close button");
			return null;
		});
		if (clickTarget == null) return false;
		mouseMovement.moveAndClick(clickTarget, profile);
		return true;
	}

	/**
	 * Go back from the detail/setup view to the main GE overview.
	 */
	public boolean grandExchangeBack(MouseMovementProfile profile) {
		return clickWidgetByPackedId(InterfaceID.GeOffers.BACK, profile);
	}

	/**
	 * Right-click a packed widget ID and select a menu option.
	 */
	private boolean rightClickWidgetAndSelect(int packedId, String option, MouseMovementProfile profile) {
		int groupId = packedId >> 16;
		int childId = packedId & 0xFFFF;

		java.awt.Point point = runOnClientThread(() -> {
			Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) return null;
			return getWidgetClickPoint(widget, profile);
		});

		if (point == null) return false;
		return rightClickAndSelect(point.x, point.y, option, null, profile);
	}
}
