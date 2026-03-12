package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.gamestate.GameStatePlugin;
import net.runelite.client.plugins.gamestate.InventoryState;
import net.runelite.client.plugins.gamestate.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Chains interaction actions into sequences with delays, conditions, and error handling.
 *
 * <p>Example usage:
 * <pre>
 * TaskSequencer seq = new TaskSequencer(interactionPlugin);
 * seq.openTab("INVENTORY")
 *    .delay(200, 400)
 *    .clickInventoryItem("Lobster")
 *    .delay(100, 200)
 *    .openTab("PRAYER")
 *    .waitUntil(() -> prayerTabIsOpen(), 3000)
 *    .clickAt(x, y)
 *    .execute();
 * </pre>
 */
@Slf4j
public class TaskSequencer {

	private final InteractionPlugin interaction;
	private final List<TaskStep> steps = new ArrayList<>();
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicReference<String> lastError = new AtomicReference<>(null);
	private MouseMovementProfile defaultProfile = MouseMovementProfile.NORMAL;
	private boolean stopOnFailure = true;
	private int currentStepIndex = -1;
	private GameStatePlugin gameStatePlugin;

	public TaskSequencer(InteractionPlugin interaction) {
		this.interaction = interaction;
	}

	/**
	 * Set the GameStatePlugin reference for conditional wait steps.
	 */
	public TaskSequencer withGameState(GameStatePlugin gameStatePlugin) {
		this.gameStatePlugin = gameStatePlugin;
		return this;
	}

	/**
	 * Set the default mouse profile for all steps that don't specify one.
	 */
	public TaskSequencer withProfile(MouseMovementProfile profile) {
		this.defaultProfile = profile;
		return this;
	}

	/**
	 * Set the default mouse profile by name.
	 */
	public TaskSequencer withProfile(String profileName) {
		this.defaultProfile = MouseMovementProfile.fromString(profileName);
		return this;
	}

	/**
	 * If true (default), the sequence stops on the first failed step.
	 * If false, it continues executing remaining steps.
	 */
	public TaskSequencer stopOnFailure(boolean stop) {
		this.stopOnFailure = stop;
		return this;
	}

	// ===== Action Steps =====

	/**
	 * Move virtual mouse to canvas coordinates.
	 */
	public TaskSequencer moveMouse(int x, int y) {
		steps.add(new TaskStep("moveMouse(" + x + "," + y + ")", () -> {
			interaction.moveMouseTo(x, y, defaultProfile);
			return true;
		}));
		return this;
	}

	/**
	 * Click at canvas coordinates.
	 */
	public TaskSequencer clickAt(int x, int y) {
		steps.add(new TaskStep("clickAt(" + x + "," + y + ")", () -> {
			interaction.clickAt(x, y, defaultProfile);
			return true;
		}));
		return this;
	}

	/**
	 * Click at canvas coordinates with a specific profile.
	 */
	public TaskSequencer clickAt(int x, int y, MouseMovementProfile profile) {
		steps.add(new TaskStep("clickAt(" + x + "," + y + ")", () -> {
			interaction.clickAt(x, y, profile);
			return true;
		}));
		return this;
	}

	/**
	 * Left-click at current virtual mouse position.
	 */
	public TaskSequencer click() {
		steps.add(new TaskStep("click()", () -> {
			interaction.click();
			return true;
		}));
		return this;
	}

	/**
	 * Right-click at current virtual mouse position.
	 */
	public TaskSequencer rightClick() {
		steps.add(new TaskStep("rightClick()", () -> {
			interaction.rightClick();
			return true;
		}));
		return this;
	}

	/**
	 * Open a player tab by name (e.g., "INVENTORY", "WORN_EQUIPMENT", "PRAYER").
	 */
	public TaskSequencer openTab(String tabName) {
		steps.add(new TaskStep("openTab(" + tabName + ")",
			() -> interaction.openPlayerTab(tabName, defaultProfile)));
		return this;
	}

	/**
	 * Open a player tab by enum.
	 */
	public TaskSequencer openTab(PlayerTab tab) {
		steps.add(new TaskStep("openTab(" + tab.name() + ")",
			() -> interaction.openPlayerTab(tab, defaultProfile)));
		return this;
	}

	/**
	 * Click an inventory slot (0-27).
	 */
	public TaskSequencer clickInventorySlot(int slot) {
		steps.add(new TaskStep("clickInventorySlot(" + slot + ")",
			() -> interaction.clickInventorySlot(slot, defaultProfile)));
		return this;
	}

	/**
	 * Click an inventory item by name.
	 */
	public TaskSequencer clickInventoryItem(String itemName) {
		steps.add(new TaskStep("clickInventoryItem(" + itemName + ")",
			() -> interaction.clickInventoryItem(itemName, defaultProfile)));
		return this;
	}

	/**
	 * Right-click an inventory item and select a menu option.
	 */
	public TaskSequencer rightClickInventoryItemAndSelect(String itemName, String option) {
		steps.add(new TaskStep("rightClickInventoryItemAndSelect(" + itemName + "," + option + ")",
			() -> interaction.rightClickInventoryItemAndSelect(itemName, option, defaultProfile)));
		return this;
	}

	// ===== Equipment Steps =====

	/**
	 * Click an equipment slot by name (e.g., "HEAD", "WEAPON", "RING").
	 */
	public TaskSequencer clickEquipmentSlot(String slotName) {
		steps.add(new TaskStep("clickEquipmentSlot(" + slotName + ")",
			() -> interaction.clickEquipmentSlot(slotName, defaultProfile)));
		return this;
	}

	/**
	 * Right-click an equipment slot and select a menu option.
	 */
	public TaskSequencer rightClickEquipmentSlotAndSelect(String slotName, String option) {
		steps.add(new TaskStep("rightClickEquipmentSlotAndSelect(" + slotName + "," + option + ")",
			() -> interaction.rightClickEquipmentSlotAndSelect(slotName, option, defaultProfile)));
		return this;
	}

	/**
	 * Click an equipped item by name.
	 */
	public TaskSequencer clickEquipmentItem(String itemName) {
		steps.add(new TaskStep("clickEquipmentItem(" + itemName + ")",
			() -> interaction.clickEquipmentItem(itemName, defaultProfile)));
		return this;
	}

	/**
	 * Right-click an equipped item and select a menu option.
	 */
	public TaskSequencer rightClickEquipmentItemAndSelect(String itemName, String option) {
		steps.add(new TaskStep("rightClickEquipmentItemAndSelect(" + itemName + "," + option + ")",
			() -> interaction.rightClickEquipmentItemAndSelect(itemName, option, defaultProfile)));
		return this;
	}

	// ===== Dialog Option Steps =====

	/**
	 * Select an option from a dialog options menu (e.g., teleport destination).
	 */
	public TaskSequencer selectDialogOption(String optionText) {
		steps.add(new TaskStep("selectDialogOption(" + optionText + ")",
			() -> interaction.selectDialogOption(optionText, defaultProfile)));
		return this;
	}

	/**
	 * Wait for a dialog options menu to appear, then select an option.
	 */
	public TaskSequencer waitAndSelectDialogOption(String optionText, int timeoutMs) {
		steps.add(new TaskStep("waitAndSelectDialogOption(" + optionText + "," + timeoutMs + "ms)",
			() -> interaction.waitAndSelectDialogOption(optionText, timeoutMs, defaultProfile)));
		return this;
	}

	/**
	 * Select an option from an already-open right-click menu.
	 */
	public TaskSequencer selectMenuOption(String option) {
		steps.add(new TaskStep("selectMenuOption(" + option + ")",
			() -> interaction.selectMenuOption(option, defaultProfile)));
		return this;
	}

	/**
	 * Select an option from an already-open right-click menu with a target filter.
	 */
	public TaskSequencer selectMenuOption(String option, String target) {
		steps.add(new TaskStep("selectMenuOption(" + option + "," + target + ")",
			() -> interaction.selectMenuOption(option, target, defaultProfile)));
		return this;
	}

	/**
	 * Right-click at coordinates and select a menu option.
	 */
	public TaskSequencer rightClickAndSelect(int x, int y, String option) {
		steps.add(new TaskStep("rightClickAndSelect(" + x + "," + y + "," + option + ")",
			() -> interaction.rightClickAndSelect(x, y, option, defaultProfile)));
		return this;
	}

	// ===== Sub-menu Steps =====

	/**
	 * Right-click an equipped item, hover over a parent option to open sub-menu, then select from sub-menu.
	 * Used for items like construction cape where "Teleport" has a sub-menu with destinations.
	 */
	public TaskSequencer rightClickEquipmentItemHoverAndSelect(String itemName, String parentOption, String subOption) {
		steps.add(new TaskStep("rightClickEquipmentItemHoverAndSelect(" + itemName + "," + parentOption + "," + subOption + ")",
			() -> interaction.rightClickEquipmentItemHoverAndSelect(itemName, parentOption, subOption, defaultProfile)));
		return this;
	}

	/**
	 * Right-click an inventory item, hover over a parent option to open sub-menu, then select from sub-menu.
	 */
	public TaskSequencer rightClickInventoryItemHoverAndSelect(String itemName, String parentOption, String subOption) {
		steps.add(new TaskStep("rightClickInventoryItemHoverAndSelect(" + itemName + "," + parentOption + "," + subOption + ")",
			() -> interaction.rightClickInventoryItemHoverAndSelect(itemName, parentOption, subOption, defaultProfile)));
		return this;
	}

	/**
	 * Interact with an NPC by name (left-click).
	 */
	public TaskSequencer interactWithNPC(String npcName) {
		steps.add(new TaskStep("interactWithNPC(" + npcName + ")",
			() -> interaction.interactWithNPC(npcName, defaultProfile)));
		return this;
	}

	/**
	 * Interact with an NPC by name and action (right-click + select).
	 */
	public TaskSequencer interactWithNPC(String npcName, String action) {
		steps.add(new TaskStep("interactWithNPC(" + npcName + "," + action + ")",
			() -> interaction.interactWithNPC(npcName, action, defaultProfile)));
		return this;
	}

	/**
	 * Interact with a game object by name.
	 */
	public TaskSequencer interactWithObject(String objectName) {
		steps.add(new TaskStep("interactWithObject(" + objectName + ")",
			() -> interaction.interactWithObject(objectName, defaultProfile)));
		return this;
	}

	/**
	 * Interact with a game object by name and action.
	 */
	public TaskSequencer interactWithObject(String objectName, String action) {
		steps.add(new TaskStep("interactWithObject(" + objectName + "," + action + ")",
			() -> interaction.interactWithObject(objectName, action, defaultProfile)));
		return this;
	}

	// ===== Bank Steps =====

	/**
	 * Click a bank item by name (withdraw with default quantity).
	 */
	public TaskSequencer clickBankItem(String itemName) {
		steps.add(new TaskStep("clickBankItem(" + itemName + ")",
			() -> interaction.clickBankItem(itemName, defaultProfile)));
		return this;
	}

	/**
	 * Right-click a bank item and select an option (e.g., "Withdraw-1", "Withdraw-All").
	 */
	public TaskSequencer rightClickBankItemAndSelect(String itemName, String option) {
		steps.add(new TaskStep("rightClickBankItemAndSelect(" + itemName + "," + option + ")",
			() -> interaction.rightClickBankItemAndSelect(itemName, option, defaultProfile)));
		return this;
	}

	/**
	 * Click a bank inventory item by name (deposit with default quantity).
	 */
	public TaskSequencer clickBankInventoryItem(String itemName) {
		steps.add(new TaskStep("clickBankInventoryItem(" + itemName + ")",
			() -> interaction.clickBankInventoryItem(itemName, defaultProfile)));
		return this;
	}

	/**
	 * Right-click a bank inventory item and select an option (e.g., "Deposit-1", "Deposit-All").
	 */
	public TaskSequencer rightClickBankInventoryItemAndSelect(String itemName, String option) {
		steps.add(new TaskStep("rightClickBankInventoryItemAndSelect(" + itemName + "," + option + ")",
			() -> interaction.rightClickBankInventoryItemAndSelect(itemName, option, defaultProfile)));
		return this;
	}

	/**
	 * Click the deposit-inventory button.
	 */
	public TaskSequencer depositInventory() {
		steps.add(new TaskStep("depositInventory()",
			() -> interaction.depositInventory(defaultProfile)));
		return this;
	}

	/**
	 * Click the deposit-equipment button.
	 */
	public TaskSequencer depositEquipment() {
		steps.add(new TaskStep("depositEquipment()",
			() -> interaction.depositEquipment(defaultProfile)));
		return this;
	}

	/**
	 * Click a bank tab (0 = all, 1-9 = tabs 1-9).
	 */
	public TaskSequencer clickBankTab(int tab) {
		steps.add(new TaskStep("clickBankTab(" + tab + ")",
			() -> interaction.clickBankTab(tab, defaultProfile)));
		return this;
	}

	/**
	 * Set the bank withdraw quantity mode (1, 5, 10, -1=X, 0=All).
	 */
	public TaskSequencer setBankQuantity(int quantity) {
		steps.add(new TaskStep("setBankQuantity(" + quantity + ")",
			() -> interaction.setBankQuantity(quantity, defaultProfile)));
		return this;
	}

	/**
	 * Toggle note/item withdrawal mode.
	 */
	public TaskSequencer toggleBankNoteMode() {
		steps.add(new TaskStep("toggleBankNoteMode()",
			() -> interaction.toggleBankNoteMode(defaultProfile)));
		return this;
	}

	/**
	 * Search for items in the bank.
	 */
	public TaskSequencer bankSearch(String query) {
		steps.add(new TaskStep("bankSearch(" + query + ")",
			() -> interaction.bankSearch(query, defaultProfile)));
		return this;
	}

	/**
	 * Withdraw X quantity of an item (right-click Withdraw-X, type amount, press enter).
	 */
	public TaskSequencer withdrawX(String itemName, int amount) {
		steps.add(new TaskStep("withdrawX(" + itemName + "," + amount + ")",
			() -> interaction.withdrawX(itemName, amount, defaultProfile)));
		return this;
	}

	/**
	 * Deposit X quantity of an item (right-click Deposit-X, type amount, press enter).
	 */
	public TaskSequencer depositX(String itemName, int amount) {
		steps.add(new TaskStep("depositX(" + itemName + "," + amount + ")",
			() -> interaction.depositX(itemName, amount, defaultProfile)));
		return this;
	}

	/**
	 * Close the bank interface.
	 */
	public TaskSequencer closeBank() {
		steps.add(new TaskStep("closeBank()",
			() -> interaction.closeBank(defaultProfile)));
		return this;
	}

	// ===== Walking Steps =====

	/**
	 * Web walk to a world coordinate using A* pathfinding with obstacle handling.
	 */
	public TaskSequencer walkTo(int x, int y, int plane) {
		steps.add(new TaskStep("walkTo(" + x + "," + y + "," + plane + ")",
			() -> interaction.webWalkTo(
				new net.runelite.api.coords.WorldPoint(x, y, plane), defaultProfile)));
		return this;
	}

	/**
	 * Web walk to a world coordinate (plane 0).
	 */
	public TaskSequencer walkTo(int x, int y) {
		return walkTo(x, y, 0);
	}

	// ===== Timing Steps =====

	/**
	 * Fixed delay in milliseconds.
	 */
	public TaskSequencer delay(int ms) {
		steps.add(new TaskStep("delay(" + ms + "ms)", () -> {
			Thread.sleep(ms);
			return true;
		}));
		return this;
	}

	/**
	 * Random delay between minMs and maxMs (inclusive).
	 */
	public TaskSequencer delay(int minMs, int maxMs) {
		steps.add(new TaskStep("delay(" + minMs + "-" + maxMs + "ms)", () -> {
			int actual = minMs + (int) (Math.random() * (maxMs - minMs));
			Thread.sleep(actual);
			return true;
		}));
		return this;
	}

	// ===== Condition Steps =====

	/**
	 * Wait until a condition is true, checking every 100ms.
	 * Fails if the timeout (ms) is reached.
	 */
	public TaskSequencer waitUntil(BooleanSupplier condition, int timeoutMs) {
		steps.add(new TaskStep("waitUntil(timeout=" + timeoutMs + "ms)", () -> {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (!condition.getAsBoolean()) {
				if (System.currentTimeMillis() > deadline) {
					log.warn("waitUntil timed out after {}ms", timeoutMs);
					return false;
				}
				if (cancelled.get()) {
					return false;
				}
				Thread.sleep(100);
			}
			return true;
		}));
		return this;
	}

	/**
	 * Wait until a condition is true, with custom poll interval.
	 */
	public TaskSequencer waitUntil(BooleanSupplier condition, int timeoutMs, int pollIntervalMs) {
		steps.add(new TaskStep("waitUntil(timeout=" + timeoutMs + "ms,poll=" + pollIntervalMs + "ms)", () -> {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (!condition.getAsBoolean()) {
				if (System.currentTimeMillis() > deadline) {
					log.warn("waitUntil timed out after {}ms", timeoutMs);
					return false;
				}
				if (cancelled.get()) {
					return false;
				}
				Thread.sleep(pollIntervalMs);
			}
			return true;
		}));
		return this;
	}

	// ===== Named Conditional Wait Steps =====

	/**
	 * Wait until the player is idle (not animating and not moving).
	 */
	public TaskSequencer waitUntilIdle(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && ps.getAnimation() == -1 && !ps.isMoving();
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until the player is not animating.
	 */
	public TaskSequencer waitUntilNotAnimating(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && ps.getAnimation() == -1;
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until the player is moving.
	 */
	public TaskSequencer waitUntilMoving(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && ps.isMoving();
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until the player stops moving.
	 */
	public TaskSequencer waitUntilStopped(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && !ps.isMoving();
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until inventory is full (28 items).
	 */
	public TaskSequencer waitUntilInventoryFull(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			InventoryState inv = gameStatePlugin != null ? gameStatePlugin.getInventoryState() : null;
			return inv != null && inv.getItemCount() >= 28;
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until inventory is not full (< 28 items).
	 */
	public TaskSequencer waitUntilInventoryNotFull(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			InventoryState inv = gameStatePlugin != null ? gameStatePlugin.getInventoryState() : null;
			return inv != null && inv.getItemCount() < 28;
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until inventory contains an item by name.
	 */
	public TaskSequencer waitUntilInventoryContains(String itemName, int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			InventoryState inv = gameStatePlugin != null ? gameStatePlugin.getInventoryState() : null;
			if (inv == null) return false;
			return inv.getItems().stream()
				.anyMatch(item -> item.getName().toLowerCase().contains(itemName.toLowerCase()));
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until inventory is empty.
	 */
	public TaskSequencer waitUntilInventoryEmpty(int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			InventoryState inv = gameStatePlugin != null ? gameStatePlugin.getInventoryState() : null;
			return inv != null && inv.isEmpty();
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until health is above a threshold.
	 */
	public TaskSequencer waitUntilHealthAbove(int threshold, int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && ps.getHealth() > threshold;
		}, timeoutMs, pollMs);
	}

	/**
	 * Wait until health is below a threshold.
	 */
	public TaskSequencer waitUntilHealthBelow(int threshold, int timeoutMs, int pollMs) {
		return waitUntil(() -> {
			PlayerState ps = gameStatePlugin != null ? gameStatePlugin.getPlayerState() : null;
			return ps != null && ps.getHealth() < threshold;
		}, timeoutMs, pollMs);
	}

	/**
	 * Execute a step only if the condition is true; skip otherwise (does not fail).
	 */
	public TaskSequencer doIf(BooleanSupplier condition, Supplier<TaskSequencer> thenBranch) {
		steps.add(new TaskStep("doIf", () -> {
			if (condition.getAsBoolean()) {
				TaskSequencer branch = thenBranch.get();
				TaskResult result = branch.executeSync();
				return result.isSuccess();
			}
			return true; // Condition false = skip, not fail
		}));
		return this;
	}

	/**
	 * Repeat a sub-sequence N times.
	 */
	public TaskSequencer repeat(int times, Supplier<TaskSequencer> body) {
		steps.add(new TaskStep("repeat(" + times + ")", () -> {
			for (int i = 0; i < times; i++) {
				if (cancelled.get()) {
					return false;
				}
				TaskSequencer iteration = body.get();
				TaskResult result = iteration.executeSync();
				if (!result.isSuccess() && stopOnFailure) {
					return false;
				}
			}
			return true;
		}));
		return this;
	}

	/**
	 * Add a custom action step.
	 */
	public TaskSequencer custom(String description, BooleanSupplier action) {
		steps.add(new TaskStep(description, () -> action.getAsBoolean()));
		return this;
	}

	// ===== Execution =====

	/**
	 * Execute the sequence asynchronously. Returns a future with the result.
	 */
	public CompletableFuture<TaskResult> execute() {
		return CompletableFuture.supplyAsync(this::executeSync);
	}

	/**
	 * Execute the sequence synchronously (blocks the calling thread).
	 */
	public TaskResult executeSync() {
		if (running.getAndSet(true)) {
			return new TaskResult(false, 0, steps.size(), "Sequence already running");
		}

		cancelled.set(false);
		lastError.set(null);
		int completed = 0;

		log.info("Starting task sequence with {} steps", steps.size());

		try {
			for (int i = 0; i < steps.size(); i++) {
				if (cancelled.get()) {
					log.info("Task sequence cancelled at step {}/{}", i + 1, steps.size());
					return new TaskResult(false, completed, steps.size(), "Cancelled at step: " + steps.get(i).name);
				}

				currentStepIndex = i;
				TaskStep step = steps.get(i);
				log.debug("Executing step {}/{}: {}", i + 1, steps.size(), step.name);

				try {
					boolean success = step.action.call();
					if (success) {
						completed++;
					} else {
						String msg = "Step failed: " + step.name;
						lastError.set(msg);
						log.warn("Step {}/{} failed: {}", i + 1, steps.size(), step.name);
						if (stopOnFailure) {
							return new TaskResult(false, completed, steps.size(), msg);
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					String msg = "Interrupted at step: " + step.name;
					lastError.set(msg);
					return new TaskResult(false, completed, steps.size(), msg);
				} catch (Exception e) {
					String msg = "Error at step " + step.name + ": " + e.getMessage();
					lastError.set(msg);
					log.error("Step {}/{} threw exception: {}", i + 1, steps.size(), step.name, e);
					if (stopOnFailure) {
						return new TaskResult(false, completed, steps.size(), msg);
					}
				}
			}
		} finally {
			running.set(false);
			currentStepIndex = -1;
		}

		log.info("Task sequence completed: {}/{} steps succeeded", completed, steps.size());
		return new TaskResult(true, completed, steps.size(), null);
	}

	/**
	 * Cancel a running sequence. The current step will finish, then execution stops.
	 */
	public void cancel() {
		cancelled.set(true);
	}

	/**
	 * Check if the sequence is currently running.
	 */
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Get the 0-based index of the currently executing step, or -1 if not running.
	 */
	public int getCurrentStepIndex() {
		return currentStepIndex;
	}

	/**
	 * Get the total number of steps.
	 */
	public int getStepCount() {
		return steps.size();
	}

	/**
	 * Get the last error message, or null if no error.
	 */
	public String getLastError() {
		return lastError.get();
	}

	// ===== Internal =====

	@FunctionalInterface
	private interface ThrowingBooleanSupplier {
		boolean call() throws Exception;
	}

	private static class TaskStep {
		final String name;
		final ThrowingBooleanSupplier action;

		TaskStep(String name, ThrowingBooleanSupplier action) {
			this.name = name;
			this.action = action;
		}
	}

	/**
	 * Result of a task sequence execution.
	 */
	public static class TaskResult {
		private final boolean success;
		private final int completedSteps;
		private final int totalSteps;
		private final String error;

		public TaskResult(boolean success, int completedSteps, int totalSteps, String error) {
			this.success = success;
			this.completedSteps = completedSteps;
			this.totalSteps = totalSteps;
			this.error = error;
		}

		public boolean isSuccess() { return success; }
		public int getCompletedSteps() { return completedSteps; }
		public int getTotalSteps() { return totalSteps; }
		public String getError() { return error; }
	}
}
