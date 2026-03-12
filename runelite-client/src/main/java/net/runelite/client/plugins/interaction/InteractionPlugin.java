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
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.Rectangle;
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

	private HumanMouseMovement mouseMovement;
	private ObjectDetectionPlugin objectDetectionPlugin;
	private GameStatePlugin gameStatePlugin;
	private WebWalker webWalker;
	private VirtualMouseOverlay virtualMouseOverlay;

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
	}

	@Override
	protected void shutDown() throws Exception {
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
	 * Interact with nearest object by name and action
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

	private boolean interactWithObject(GameObjectInfo objectInfo, MouseMovementProfile profile) {
		Point screenPoint = getObjectScreenPoint(objectInfo);
		if (screenPoint == null) {
			log.warn("Could not get screen coordinates for object");
			return false;
		}

		int jitterX = (int) ((Math.random() - 0.5) * 10);
		int jitterY = (int) ((Math.random() - 0.5) * 10);

		mouseMovement.moveAndClick(
			new java.awt.Point(screenPoint.getX() + jitterX, screenPoint.getY() + jitterY),
			profile
		);

		log.info("Interacted with object: {} at {}", objectInfo.getName(), objectInfo.getLocation());
		return true;
	}

	private Point getObjectScreenPoint(GameObjectInfo objectInfo) {
		WorldPoint worldLocation = objectInfo.getLocation();

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
				log.info("Found inventory item: {}", itemName);
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

		return runOnClientThread(() -> {
			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible (is the equipment tab open?)", slot);
				return false;
			}
			return clickWidgetInternal(slotWidget, profile);
		});
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
		return runOnClientThread(() -> {
			EquipmentInventorySlot slot = findEquippedItemSlot(itemName);
			if (slot == null) return false;

			int widgetId = getEquipmentSlotWidgetId(slot);
			if (widgetId == -1) return false;

			Widget slotWidget = client.getWidget(widgetId);
			if (slotWidget == null || slotWidget.isHidden()) {
				log.warn("Equipment slot {} widget not visible", slot);
				return false;
			}
			return clickWidgetInternal(slotWidget, profile);
		});
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

			int matchIndex = -1;
			for (int i = 0; i < entries.length; i++) {
				MenuEntry entry = entries[i];
				String entryOption = stripTags(entry.getOption()).toLowerCase();
				String entryTarget = stripTags(entry.getTarget()).toLowerCase();

				boolean optionMatch = entryOption.contains(searchOption);
				boolean targetMatch = searchTarget == null || entryTarget.contains(searchTarget);

				if (optionMatch && targetMatch) {
					matchIndex = i;
					log.debug("Found menu match at index {}: '{}' -> '{}'", i, entry.getOption(), entry.getTarget());
					break;
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
		return runOnClientThread(() -> openPlayerTabInternal(tab, profile));
	}

	private boolean openPlayerTabInternal(PlayerTab tab, MouseMovementProfile profile) {
		Widget tabWidget = findTabWidget(tab);
		if (tabWidget == null) {
			log.warn("Player tab {} widget not found in any viewport mode", tab);
			return false;
		}

		log.debug("Found tab {} widget: id={}", tab, tabWidget.getId());
		return clickWidgetInternal(tabWidget, profile);
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
		return runOnClientThread(() -> {
			Widget prayer = client.getWidget(groupId, childId);
			if (prayer == null || prayer.isHidden()) {
				log.warn("Prayer widget {}.{} not found or hidden", groupId, childId);
				return false;
			}

			log.info("Toggling prayer widget {}.{}", groupId, childId);
			return clickWidgetInternal(prayer, profile);
		});
	}

	// ===== SKILLS INTERACTION =====

	/**
	 * Click a skill by widget ID
	 */
	public boolean clickSkillByWidgetId(int groupId, int childId, MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget skill = client.getWidget(groupId, childId);
			if (skill == null || skill.isHidden()) {
				log.warn("Skill widget {}.{} not found or hidden", groupId, childId);
				return false;
			}

			log.info("Clicking skill widget {}.{}", groupId, childId);
			return clickWidgetInternal(skill, profile);
		});
	}

	// ===== GENERAL WIDGET INTERACTION =====

	/**
	 * Click any widget by WidgetInfo
	 */
	public boolean clickWidgetByInfo(WidgetInfo widgetInfo, MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget widget = client.getWidget(widgetInfo);
			if (widget == null) {
				log.warn("Widget {} not found", widgetInfo);
				return false;
			}
			return clickWidgetInternal(widget, profile);
		});
	}

	/**
	 * Click a specific widget (thread-safe wrapper)
	 */
	public boolean clickWidget(Widget widget, MouseMovementProfile profile) {
		return runOnClientThread(() -> clickWidgetInternal(widget, profile));
	}

	/**
	 * Click a specific widget (internal, must be on client thread)
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

		log.info("Clicked widget at ({}, {})", screenPoint.getX(), screenPoint.getY());
		return true;
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
		return runOnClientThread(() -> {
			Widget bankFrame = client.getWidget(InterfaceID.Bankmain.FRAME);
			if (bankFrame == null || bankFrame.isHidden()) {
				log.warn("Bank is not open");
				return false;
			}

			// The close button is child 11 of the FRAME widget
			Widget[] children = bankFrame.getDynamicChildren();
			if (children != null && children.length > 11) {
				Widget closeButton = children[11];
				if (closeButton != null && !closeButton.isHidden()) {
					return clickWidgetInternal(closeButton, profile);
				}
			}

			// Fallback: try static children
			Widget[] staticChildren = bankFrame.getStaticChildren();
			if (staticChildren != null && staticChildren.length > 11) {
				Widget closeButton = staticChildren[11];
				if (closeButton != null && !closeButton.isHidden()) {
					return clickWidgetInternal(closeButton, profile);
				}
			}

			log.warn("Could not find bank close button");
			return false;
		});
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

		for (Widget child : children) {
			if (child == null || child.isHidden()) continue;
			int itemId = child.getItemId();
			if (itemId == -1 || itemId == 6512) continue;

			ItemComposition comp = client.getItemDefinition(itemId);
			if (comp.getName().toLowerCase().contains(search)) {
				log.info("Found bank item '{}' (actual: '{}') at index {}",
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
	 * Returns true if the item is now visible.
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

		// Scroll in the direction we need to go
		for (int attempt = 0; attempt < 40; attempt++) {
			Boolean visible = runOnClientThread(() -> isBankItemVisible(item));
			if (Boolean.TRUE.equals(visible)) {
				sleep(100);
				return true;
			}

			// Determine scroll direction: if item is below visible area, scroll down (+), else up (-)
			int scrollDirection = runOnClientThread(() -> {
				Widget c = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
				if (c == null) return 0;
				Rectangle cBounds = c.getBounds();
				Rectangle iBounds = item.getBounds();
				if (cBounds == null || iBounds == null) return 0;

				int itemY = (int)(iBounds.getY() + iBounds.getHeight() / 2);
				int containerTop = (int)cBounds.getY();
				int containerBottom = (int)(cBounds.getY() + cBounds.getHeight());

				if (itemY > containerBottom) return 1;   // Need to scroll down
				if (itemY < containerTop) return -1;      // Need to scroll up
				return 0;
			});

			if (scrollDirection == 0) return true;

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

		log.warn("Could not scroll bank item into view after 40 scroll attempts");
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

		// Now click it
		return runOnClientThread(() -> clickWidgetInternal(item, profile));
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
		return runOnClientThread(() -> {
			Widget item = findBankInventoryItemWidget(itemName);
			if (item == null) return false;
			return clickWidgetInternal(item, profile);
		});
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
		return runOnClientThread(() -> {
			Widget depositInv = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
			if (depositInv == null || depositInv.isHidden()) {
				log.warn("Deposit inventory button not visible");
				return false;
			}
			return clickWidgetInternal(depositInv, profile);
		});
	}

	/**
	 * Click the "Deposit worn items" button in the bank interface.
	 */
	public boolean depositEquipment(MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget depositWorn = client.getWidget(InterfaceID.Bankmain.DEPOSITWORN);
			if (depositWorn == null || depositWorn.isHidden()) {
				log.warn("Deposit worn items button not visible");
				return false;
			}
			return clickWidgetInternal(depositWorn, profile);
		});
	}

	/**
	 * Click a bank tab by index (0 = main/all tab, 1-9 = tabs 1-9).
	 * The bank must be open.
	 */
	public boolean clickBankTab(int tabIndex, MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget tabContainer = client.getWidget(InterfaceID.Bankmain.TABS);
			if (tabContainer == null || tabContainer.isHidden()) {
				log.warn("Bank tab container not visible");
				return false;
			}

			Widget[] children = tabContainer.getDynamicChildren();
			if (children == null) {
				log.warn("Bank tab container has no children");
				return false;
			}

			// Tab widgets are dynamic children of the TABS container.
			// Tab 0 (all items) is at index 10, tabs 1-9 start at index 11.
			// Each tab widget takes up 1 slot.
			int widgetIndex = 10 + tabIndex;
			if (widgetIndex >= children.length) {
				log.warn("Bank tab index {} out of range (max children: {})", tabIndex, children.length);
				return false;
			}

			Widget tabWidget = children[widgetIndex];
			if (tabWidget == null || tabWidget.isHidden()) {
				log.warn("Bank tab {} widget is null or hidden", tabIndex);
				return false;
			}

			log.info("Clicking bank tab {}", tabIndex);
			return clickWidgetInternal(tabWidget, profile);
		});
	}

	/**
	 * Set the bank withdraw quantity mode by clicking the appropriate button.
	 * Valid values: 1, 5, 10, -1 (X), 0 (All)
	 */
	public boolean setBankQuantity(int quantity, MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			int widgetId;
			switch (quantity) {
				case 1:  widgetId = InterfaceID.Bankmain.QUANTITY1; break;
				case 5:  widgetId = InterfaceID.Bankmain.QUANTITY5; break;
				case 10: widgetId = InterfaceID.Bankmain.QUANTITY10; break;
				case -1: widgetId = InterfaceID.Bankmain.QUANTITYX; break;
				case 0:  widgetId = InterfaceID.Bankmain.QUANTITYALL; break;
				default:
					log.warn("Invalid bank quantity: {} (valid: 1, 5, 10, -1 for X, 0 for All)", quantity);
					return false;
			}

			Widget quantityWidget = client.getWidget(widgetId);
			if (quantityWidget == null || quantityWidget.isHidden()) {
				log.warn("Bank quantity button not visible for quantity={}", quantity);
				return false;
			}

			log.info("Setting bank quantity to {}", quantity == -1 ? "X" : quantity == 0 ? "All" : quantity);
			return clickWidgetInternal(quantityWidget, profile);
		});
	}

	/**
	 * Toggle the bank note/item withdrawal mode.
	 */
	public boolean toggleBankNoteMode(MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget noteWidget = client.getWidget(InterfaceID.Bankmain.NOTE);
			if (noteWidget == null || noteWidget.isHidden()) {
				log.warn("Bank note toggle button not visible");
				return false;
			}
			log.info("Toggling bank note mode");
			return clickWidgetInternal(noteWidget, profile);
		});
	}

	/**
	 * Click the bank search button to activate search mode.
	 */
	public boolean clickBankSearch(MouseMovementProfile profile) {
		return runOnClientThread(() -> {
			Widget searchWidget = client.getWidget(InterfaceID.Bankmain.SEARCH);
			if (searchWidget == null || searchWidget.isHidden()) {
				log.warn("Bank search button not visible");
				return false;
			}
			log.info("Clicking bank search button");
			return clickWidgetInternal(searchWidget, profile);
		});
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
	 * Withdraw a specific quantity of an item by using right-click "Withdraw-X" and typing the amount.
	 */
	public boolean withdrawX(String itemName, int amount, MouseMovementProfile profile) {
		if (!rightClickBankItemAndSelect(itemName, "Withdraw-X", profile)) {
			return false;
		}

		// Wait for the chatbox input to appear
		sleep(600 + (int)(Math.random() * 300));

		// Type the amount and press enter
		typeText(String.valueOf(amount));
		sleep(100 + (int)(Math.random() * 100));
		pressEnter();

		log.info("Withdrawing {} x {}", amount, itemName);
		return true;
	}

	/**
	 * Deposit a specific quantity of an item from the bank inventory panel
	 * using right-click "Deposit-X" and typing the amount.
	 */
	public boolean depositX(String itemName, int amount, MouseMovementProfile profile) {
		if (!rightClickBankInventoryItemAndSelect(itemName, "Deposit-X", profile)) {
			return false;
		}

		// Wait for the chatbox input to appear
		sleep(600 + (int)(Math.random() * 300));

		// Type the amount and press enter
		typeText(String.valueOf(amount));
		sleep(100 + (int)(Math.random() * 100));
		pressEnter();

		log.info("Depositing {} x {}", amount, itemName);
		return true;
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

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
