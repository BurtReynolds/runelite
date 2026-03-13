package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class AntiBanService {

	private final InteractionPlugin interaction;
	private final Client client;
	private final HumanMouseMovement mouseMovement;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private Thread backgroundThread;

	// Configuration
	private int minIntervalMs = 15000;
	private int maxIntervalMs = 90000;
	private boolean pauseDuringTasks = true;
	private final Map<String, Integer> weights = new ConcurrentHashMap<>();
	private final Map<String, Integer> actionCounts = new ConcurrentHashMap<>();
	private long lastActionTime = 0;
	private String lastActionType = "none";

	// Default weights
	private static final Map<String, Integer> DEFAULT_WEIGHTS = new LinkedHashMap<>();
	static {
		DEFAULT_WEIGHTS.put("mouse_fidget", 30);
		DEFAULT_WEIGHTS.put("camera_nudge", 25);
		DEFAULT_WEIGHTS.put("tab_check", 12);
		DEFAULT_WEIGHTS.put("skill_hover", 10);
		DEFAULT_WEIGHTS.put("hover_random", 8);
		DEFAULT_WEIGHTS.put("idle_pause", 12);
		DEFAULT_WEIGHTS.put("examine_object", 3);
		DEFAULT_WEIGHTS.put("player_lookup", 2);
		DEFAULT_WEIGHTS.put("mouse_off_client", 3);
	}

	// All 23 skill names for random skill hover
	private static final String[] SKILL_NAMES = {
		"Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic", "Runecraft",
		"Hitpoints", "Crafting", "Mining", "Smithing", "Fishing", "Cooking",
		"Firemaking", "Woodcutting", "Agility", "Herblore", "Thieving", "Fletching",
		"Slayer", "Farming", "Construction", "Hunter"
	};

	public AntiBanService(InteractionPlugin interaction, Client client, HumanMouseMovement mouseMovement) {
		this.interaction = interaction;
		this.client = client;
		this.mouseMovement = mouseMovement;
		weights.putAll(DEFAULT_WEIGHTS);
	}

	public void start(int minInterval, int maxInterval, boolean pauseDuringTasks, Map<String, Integer> customWeights) {
		if (running.get()) {
			log.info("Anti-ban already running, stopping first");
			stop();
		}

		this.minIntervalMs = minInterval;
		this.maxIntervalMs = maxInterval;
		this.pauseDuringTasks = pauseDuringTasks;

		weights.clear();
		weights.putAll(DEFAULT_WEIGHTS);
		if (customWeights != null) {
			weights.putAll(customWeights);
		}

		actionCounts.clear();
		running.set(true);

		backgroundThread = new Thread(this::runLoop, "AntiBan-Service");
		backgroundThread.setDaemon(true);
		backgroundThread.start();

		log.info("Anti-ban started: interval={}ms-{}ms, weights={}", minInterval, maxInterval, weights);
	}

	public void stop() {
		running.set(false);
		if (backgroundThread != null) {
			backgroundThread.interrupt();
			backgroundThread = null;
		}
		log.info("Anti-ban stopped");
	}

	public boolean isRunning() {
		return running.get();
	}

	public Map<String, Object> getStatus() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("running", running.get());
		status.put("minIntervalMs", minIntervalMs);
		status.put("maxIntervalMs", maxIntervalMs);
		status.put("pauseDuringTasks", pauseDuringTasks);
		status.put("weights", new LinkedHashMap<>(weights));
		status.put("actionCounts", new LinkedHashMap<>(actionCounts));
		status.put("lastActionTime", lastActionTime);
		status.put("lastActionType", lastActionType);
		return status;
	}

	private void runLoop() {
		Random random = new Random();

		while (running.get()) {
			try {
				// Sleep for random interval
				int sleepMs = minIntervalMs + random.nextInt(Math.max(1, maxIntervalMs - minIntervalMs));
				Thread.sleep(sleepMs);

				if (!running.get()) break;

				// Check if logged in
				if (client.getGameState() != GameState.LOGGED_IN) {
					continue;
				}

				// Pick and execute a random action
				String action = pickWeightedAction(random);
				if (action == null) continue;

				log.info("Anti-ban: performing {}", action);
				executeAction(action);

				actionCounts.merge(action, 1, Integer::sum);
				lastActionTime = System.currentTimeMillis();
				lastActionType = action;

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.warn("Anti-ban action failed", e);
			}
		}

		log.info("Anti-ban loop exited");
	}

	private String pickWeightedAction(Random random) {
		int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
		if (totalWeight <= 0) return null;

		int roll = random.nextInt(totalWeight);
		int cumulative = 0;
		for (Map.Entry<String, Integer> entry : weights.entrySet()) {
			cumulative += entry.getValue();
			if (roll < cumulative) {
				return entry.getKey();
			}
		}
		return null;
	}

	private void executeAction(String action) {
		switch (action) {
			case "mouse_fidget":
				performMouseFidget();
				break;
			case "camera_nudge":
				performCameraNudge();
				break;
			case "tab_check":
				performTabCheck();
				break;
			case "skill_hover":
				performSkillHover();
				break;
			case "hover_random":
				performHoverRandom();
				break;
			case "idle_pause":
				performIdlePause();
				break;
			case "examine_object":
				performExamineObject();
				break;
			case "player_lookup":
				performPlayerLookup();
				break;
			case "mouse_off_client":
				performMouseOffClient();
				break;
			default:
				log.warn("Unknown anti-ban action: {}", action);
		}
	}

	// ===== Individual actions (also callable directly) =====

	public void performMouseFidget() {
		Point pos = mouseMovement.getVirtualPosition();
		int dx = (int) ((Math.random() - 0.5) * 24);
		int dy = (int) ((Math.random() - 0.5) * 24);
		int targetX = Math.max(5, Math.min(pos.x + dx, client.getCanvasWidth() - 5));
		int targetY = Math.max(5, Math.min(pos.y + dy, client.getCanvasHeight() - 5));
		mouseMovement.moveMouse(new Point(targetX, targetY), MouseMovementProfile.CAREFUL);
		log.info("Anti-ban: mouse fidget to ({}, {})", targetX, targetY);
	}

	public void performCameraNudge() {
		int currentYaw = interaction.runOnClientThread(() -> client.getCameraYaw());
		int nudge = 30 + (int) (Math.random() * 70);
		if (Math.random() < 0.5) nudge = -nudge;
		int targetYaw = (currentYaw + nudge) & 0x7FF;
		interaction.setCameraYaw(targetYaw);
		log.info("Anti-ban: camera nudge from {} to {}", currentYaw, targetYaw);
	}

	public void performTabCheck() {
		PlayerTab[] tabs = { PlayerTab.STATS, PlayerTab.QUESTS, PlayerTab.EQUIPMENT,
			PlayerTab.FRIENDS, PlayerTab.OPTIONS, PlayerTab.MUSIC };
		PlayerTab tab = tabs[(int) (Math.random() * tabs.length)];

		interaction.openPlayerTab(tab, MouseMovementProfile.NORMAL);
		sleep(500 + (int) (Math.random() * 1500));

		// Return to inventory
		interaction.openPlayerTab(PlayerTab.INVENTORY, MouseMovementProfile.NORMAL);
		log.info("Anti-ban: checked {} tab", tab);
	}

	public void performSkillHover() {
		// Open stats tab
		interaction.openPlayerTab(PlayerTab.STATS, MouseMovementProfile.NORMAL);
		sleep(300 + (int) (Math.random() * 300));

		// Pick a random skill and hover it
		String skillName = SKILL_NAMES[(int) (Math.random() * SKILL_NAMES.length)];
		// Skill widgets are children of the stats panel, indexed 1-23
		// We use clickSkillByWidgetId but we just want to hover, not click
		// Instead, move mouse to the skill widget location
		int skillIndex = Arrays.asList(SKILL_NAMES).indexOf(skillName);
		// Stats widget group is 320 (InterfaceID.STATS = 320), children 1-23 for skills
		int groupId = 320;
		int childId = skillIndex + 1;

		java.awt.Point skillPoint = interaction.runOnClientThread(() -> {
			net.runelite.api.widgets.Widget widget = client.getWidget(groupId, childId);
			if (widget == null || widget.isHidden()) return null;
			Rectangle bounds = widget.getBounds();
			if (bounds == null) return null;
			int x = (int) (bounds.getX() + bounds.getWidth() / 2);
			int y = (int) (bounds.getY() + bounds.getHeight() / 2);
			return new java.awt.Point(x, y);
		});

		if (skillPoint != null) {
			mouseMovement.moveMouse(skillPoint, MouseMovementProfile.CAREFUL);
			// Hover for 1-3 seconds to see the tooltip
			sleep(1000 + (int) (Math.random() * 2000));
			log.info("Anti-ban: hovered over {} skill", skillName);
		}

		// Return to inventory
		interaction.openPlayerTab(PlayerTab.INVENTORY, MouseMovementProfile.NORMAL);
	}

	public void performHoverRandom() {
		int canvasW = client.getCanvasWidth();
		int canvasH = client.getCanvasHeight();
		int x = 50 + (int) (Math.random() * (canvasW - 100));
		int y = 50 + (int) (Math.random() * (canvasH - 100));
		mouseMovement.moveMouse(new Point(x, y), MouseMovementProfile.NORMAL);
		sleep(200 + (int) (Math.random() * 500));
		log.info("Anti-ban: hovered at ({}, {})", x, y);
	}

	public void performIdlePause() {
		int pauseMs = 3000 + (int) (Math.random() * 12000);
		log.info("Anti-ban: idle pause for {}ms", pauseMs);
		sleep(pauseMs);
	}

	public void performExamineObject() {
		// Find a nearby NPC to examine — all client state access must be on client thread
		String npcName = interaction.runOnClientThread(() -> {
			List<NPC> npcs = client.getNpcs();
			if (npcs == null || npcs.isEmpty()) return null;

			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null) return null;

			LocalPoint playerLocal = localPlayer.getLocalLocation();
			if (playerLocal == null) return null;

			List<NPC> nearbyNpcs = new ArrayList<>();
			for (NPC npc : npcs) {
				if (npc == null || npc.getName() == null) continue;
				LocalPoint npcLocal = npc.getLocalLocation();
				if (npcLocal == null) continue;
				int dist = Math.abs(npcLocal.getSceneX() - playerLocal.getSceneX())
					+ Math.abs(npcLocal.getSceneY() - playerLocal.getSceneY());
				if (dist < 12) {
					nearbyNpcs.add(npc);
				}
			}

			if (nearbyNpcs.isEmpty()) return null;
			return nearbyNpcs.get((int) (Math.random() * nearbyNpcs.size())).getName();
		});

		if (npcName == null) {
			log.info("Anti-ban: no nearby NPCs to examine");
			return;
		}

		boolean success = interaction.rightClickNpcAndSelect(npcName, "Examine", MouseMovementProfile.NORMAL);
		log.info("Anti-ban: examine {} - {}", npcName, success ? "success" : "failed");
	}

	public void performPlayerLookup() {
		// All client state access must be on client thread
		String playerName = interaction.runOnClientThread(() -> {
			List<Player> players = client.getPlayers();
			if (players == null || players.size() <= 1) return null;

			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null) return null;

			LocalPoint myLocal = localPlayer.getLocalLocation();
			if (myLocal == null) return null;

			List<Player> nearbyPlayers = new ArrayList<>();
			for (Player p : players) {
				if (p == null || p == localPlayer || p.getName() == null) continue;
				LocalPoint pLocal = p.getLocalLocation();
				if (pLocal == null) continue;
				int dist = Math.abs(pLocal.getSceneX() - myLocal.getSceneX())
					+ Math.abs(pLocal.getSceneY() - myLocal.getSceneY());
				if (dist < 15) {
					nearbyPlayers.add(p);
				}
			}

			if (nearbyPlayers.isEmpty()) return null;
			return nearbyPlayers.get((int) (Math.random() * nearbyPlayers.size())).getName();
		});

		if (playerName == null) {
			log.info("Anti-ban: no nearby players for lookup");
			return;
		}

		boolean success = interaction.rightClickPlayerAndSelect(playerName, "Lookup", MouseMovementProfile.NORMAL);
		log.info("Anti-ban: lookup player {} - {}", playerName, success ? "success" : "failed");
	}

	public void performMouseOffClient() {
		// Move mouse to edge of canvas
		int canvasW = client.getCanvasWidth();
		int canvasH = client.getCanvasHeight();
		int edge = (int) (Math.random() * 4);
		Point target;
		switch (edge) {
			case 0: target = new Point((int)(Math.random() * canvasW), 2); break;
			case 1: target = new Point((int)(Math.random() * canvasW), canvasH - 2); break;
			case 2: target = new Point(2, (int)(Math.random() * canvasH)); break;
			default: target = new Point(canvasW - 2, (int)(Math.random() * canvasH)); break;
		}
		mouseMovement.moveMouse(target, MouseMovementProfile.FAST);
		sleep(1000 + (int) (Math.random() * 3000));
		log.info("Anti-ban: mouse moved off-client to ({}, {})", target.x, target.y);
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
