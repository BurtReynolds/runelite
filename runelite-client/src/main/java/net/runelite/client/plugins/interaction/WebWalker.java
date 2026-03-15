package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.objectdetection.GameObjectInfo;
import net.runelite.client.plugins.objectdetection.ObjectDetectionPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Slf4j
public class WebWalker {

	private static final int SCENE_SIZE = Constants.SCENE_SIZE;
	private static final int MOVEMENT_BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	private static final Set<String> GATE_ACTIONS = Set.of("Open", "open", "Push-through", "Pay-toll(10gp)");
	private static final Set<String> OBSTACLE_NAMES_LOWER = Set.of(
		"door", "gate", "large door", "castle door", "garden gate"
	);
	private static final int WALL_PROXIMITY_PENALTY_NEAR = 8;  // per blocked neighbor within 1 tile
	private static final int WALL_PROXIMITY_PENALTY_FAR = 3;   // per blocked tile within 2 tiles
	private static final int MAX_PATH_LENGTH = 500;
	private static final int MAX_WALK_ATTEMPTS = 200;
	private static final int MAX_MINIMAP_RETRIES = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final InteractionPlugin interaction;
	private final HumanMouseMovement mouseMovement;
	private ObjectDetectionPlugin objectDetectionPlugin;

	private volatile boolean cancelled = false;

	public WebWalker(Client client, ClientThread clientThread, InteractionPlugin interaction, HumanMouseMovement mouseMovement) {
		this.client = client;
		this.clientThread = clientThread;
		this.interaction = interaction;
		this.mouseMovement = mouseMovement;
	}

	public void setObjectDetectionPlugin(ObjectDetectionPlugin plugin) {
		this.objectDetectionPlugin = plugin;
	}

	public void cancel() {
		this.cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	// ===== Client thread helper =====

	private <T> T onClientThread(Supplier<T> supplier) {
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invoke(() -> {
			try {
				future.complete(supplier.get());
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			log.error("WEB_WALK: Error executing on client thread", e);
			return null;
		}
	}

	// ===== Snapshot of client state for off-thread use =====

	private static class SceneSnapshot {
		final int[][] flags;
		final int baseX;
		final int baseY;
		final int plane;
		final WorldPoint playerPos;

		SceneSnapshot(int[][] flags, int baseX, int baseY, int plane, WorldPoint playerPos) {
			this.flags = flags;
			this.baseX = baseX;
			this.baseY = baseY;
			this.plane = plane;
			this.playerPos = playerPos;
		}
	}

	private SceneSnapshot takeSceneSnapshot() {
		return onClientThread(() -> {
			CollisionData[] collisionMaps = client.getCollisionMaps();
			if (collisionMaps == null) {
				return null;
			}
			int plane = client.getPlane();
			int[][] flags = collisionMaps[plane].getFlags();
			int baseX = client.getBaseX();
			int baseY = client.getBaseY();
			WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();

			int[][] flagsCopy = new int[flags.length][];
			for (int i = 0; i < flags.length; i++) {
				flagsCopy[i] = flags[i].clone();
			}

			return new SceneSnapshot(flagsCopy, baseX, baseY, plane, playerPos);
		});
	}

	private SceneSnapshot takeSceneSnapshotWithRetry() {
		for (int i = 0; i < 5; i++) {
			SceneSnapshot snap = takeSceneSnapshot();
			if (snap != null) {
				return snap;
			}
			log.info("WEB_WALK: Scene snapshot null (scene loading?), retrying in 600ms...");
			sleep(600);
		}
		return null;
	}

	private WorldPoint getPlayerLocation() {
		return onClientThread(() -> client.getLocalPlayer().getWorldLocation());
	}

	// ===== Main walk logic =====

	public boolean walkTo(WorldPoint destination, MouseMovementProfile profile) {
		cancelled = false;

		WorldPoint start = getPlayerLocation();
		if (start == null) {
			log.warn("WEB_WALK: Could not get player location");
			return false;
		}

		log.info("WEB_WALK: Starting walk from {} to {} (distance={})",
			start, destination, start.distanceTo(destination));

		if (start.distanceTo(destination) <= 1) {
			log.info("WEB_WALK: Already at destination");
			return true;
		}

		int attempts = 0;
		int consecutiveMinimapFails = 0;
		WorldPoint lastPosition = null;
		int stuckAtSamePos = 0;

		while (!cancelled && attempts < MAX_WALK_ATTEMPTS) {
			attempts++;

			SceneSnapshot snapshot = takeSceneSnapshotWithRetry();
			if (snapshot == null) {
				log.warn("WEB_WALK: Could not get scene snapshot after retries");
				return false;
			}

			WorldPoint current = snapshot.playerPos;
			int dist = current.distanceTo(destination);

			log.info("WEB_WALK: Attempt {}/{}, current={}, distance={}, scene base=({},{})",
				attempts, MAX_WALK_ATTEMPTS, current, dist, snapshot.baseX, snapshot.baseY);

			if (dist <= 1) {
				log.info("WEB_WALK: Reached destination after {} attempts", attempts);
				return true;
			}

			// Track if we're stuck at the same position
			if (current.equals(lastPosition)) {
				stuckAtSamePos++;
				if (stuckAtSamePos >= 3) {
					log.info("WEB_WALK: Stuck at {} for {} attempts, trying random camera nudge and shorter walk", current, stuckAtSamePos);
					// Nudge camera slightly to reset any click issues
					sleep(500 + (int)(Math.random() * 500));
				}
			} else {
				stuckAtSamePos = 0;
			}
			lastPosition = current;

			boolean destInScene = isInScene(snapshot, destination);

			if (destInScene && dist <= 20) {
				// Destination is close and in scene - try direct A* pathing
				if (!walkTowardTarget(snapshot, current, destination, profile)) {
					log.info("WEB_WALK: No direct path found, checking for doors/obstacles");
					boolean handledObstacle = handleObstacleAlongPath(current, destination, profile);
					if (handledObstacle) {
						waitForMovementToStop(5000);
						consecutiveMinimapFails = 0;
						continue;
					}
					log.warn("WEB_WALK: Cannot find path to destination and no obstacles to handle");
					return false;
				}
				consecutiveMinimapFails = 0;
			} else {
				// If stuck, try shorter distances first
				boolean walked;
				if (stuckAtSamePos >= 2) {
					log.info("WEB_WALK: Using shorter walk distance due to stuck detection");
					walked = walkTowardEdgeShort(snapshot, current, destination, profile);
				} else {
					walked = walkTowardEdge(snapshot, current, destination, profile);
				}
				if (!walked) {
					consecutiveMinimapFails++;
					if (consecutiveMinimapFails >= 5) {
						boolean handledObstacle = handleObstacleAlongPath(current,
							getIntermediateTarget(snapshot, current, destination, 3), profile);
						if (handledObstacle) {
							waitForMovementToStop(5000);
							consecutiveMinimapFails = 0;
							continue;
						}
						log.warn("WEB_WALK: Stuck after {} consecutive minimap failures", consecutiveMinimapFails);
						return false;
					}
					log.info("WEB_WALK: Minimap click failed, waiting for scene to settle...");
					sleep(1000);
					continue;
				}
				consecutiveMinimapFails = 0;
				sleep(200 + (int)(Math.random() * 200));
			}
		}

		if (cancelled) {
			log.info("WEB_WALK: Walk cancelled");
			return false;
		}

		log.warn("WEB_WALK: Exceeded max attempts ({})", MAX_WALK_ATTEMPTS);
		return false;
	}

	private boolean walkTowardTarget(SceneSnapshot snapshot, WorldPoint current, WorldPoint target, MouseMovementProfile profile) {
		List<WorldPoint> path = findPathInScene(snapshot, current, target);
		if (path == null || path.isEmpty()) {
			return false;
		}

		if (!clickNextWaypoint(snapshot, path, current, profile)) {
			return false;
		}

		waitAndMonitor(target, target, 8000);
		return true;
	}

	private boolean walkTowardEdge(SceneSnapshot snapshot, WorldPoint current, WorldPoint destination, MouseMovementProfile profile) {
		// Try pathfinding at decreasing ranges
		for (int range = 16; range >= 3; range -= 2) {
			WorldPoint intermediate = getIntermediateTarget(snapshot, current, destination, range);
			List<WorldPoint> path = findPathInScene(snapshot, current, intermediate);
			if (path != null && !path.isEmpty()) {
				log.info("WEB_WALK: Path found ({} tiles) toward {} (range={})", path.size(), intermediate, range);
				if (clickNextWaypoint(snapshot, path, current, profile)) {
					waitAndMonitor(intermediate, destination, 10000);
					return true;
				}
			}
		}

		log.warn("WEB_WALK: All pathfinding attempts failed");
		return false;
	}

	/**
	 * Shorter-distance variant of walkTowardEdge, used when the player appears stuck.
	 * Tries closer targets that are more likely to produce valid minimap clicks.
	 */
	private boolean walkTowardEdgeShort(SceneSnapshot snapshot, WorldPoint current, WorldPoint destination, MouseMovementProfile profile) {
		// Try shorter pathfinding ranges
		for (int range = 8; range >= 3; range -= 1) {
			WorldPoint intermediate = getIntermediateTarget(snapshot, current, destination, range);
			List<WorldPoint> path = findPathInScene(snapshot, current, intermediate);
			if (path != null && !path.isEmpty()) {
				log.info("WEB_WALK: Short path found ({} tiles) toward {} (range={})", path.size(), intermediate, range);
				if (clickNextWaypoint(snapshot, path, current, profile)) {
					waitAndMonitor(intermediate, destination, 8000);
					return true;
				}
			}
		}

		return false;
	}

	// ===== Scene utilities =====

	private boolean isInScene(SceneSnapshot snapshot, WorldPoint wp) {
		int sceneX = wp.getX() - snapshot.baseX;
		int sceneY = wp.getY() - snapshot.baseY;
		return isValidSceneTile(sceneX, sceneY);
	}

	// ===== A* Pathfinding =====

	List<WorldPoint> findPathInScene(SceneSnapshot snapshot, WorldPoint start, WorldPoint end) {
		int[][] flags = snapshot.flags;
		int baseX = snapshot.baseX;
		int baseY = snapshot.baseY;
		int plane = snapshot.plane;

		int startSceneX = start.getX() - baseX;
		int startSceneY = start.getY() - baseY;
		int endSceneX = end.getX() - baseX;
		int endSceneY = end.getY() - baseY;

		if (!isValidSceneTile(startSceneX, startSceneY) || !isValidSceneTile(endSceneX, endSceneY)) {
			log.warn("WEB_WALK: Start or end outside scene bounds. start=({},{}), end=({},{}) base=({},{})",
				startSceneX, startSceneY, endSceneX, endSceneY, baseX, baseY);
			return null;
		}

		log.debug("WEB_WALK: A* from scene({},{}) to scene({},{})", startSceneX, startSceneY, endSceneX, endSceneY);

		Map<Long, Long> cameFrom = new HashMap<>();
		Map<Long, Integer> gScore = new HashMap<>();
		PriorityQueue<long[]> openSet = new PriorityQueue<>(Comparator.comparingLong(a -> a[1]));

		long startKey = tileKey(startSceneX, startSceneY);
		long endKey = tileKey(endSceneX, endSceneY);
		gScore.put(startKey, 0);

		int h = heuristic(startSceneX, startSceneY, endSceneX, endSceneY);
		openSet.add(new long[]{startKey, h});

		int[][] directions = {
			{0, 1}, {1, 0}, {0, -1}, {-1, 0},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
		};

		Set<Long> closedSet = new HashSet<>();

		while (!openSet.isEmpty()) {
			long[] current = openSet.poll();
			long currentKey = current[0];

			if (currentKey == endKey) {
				return reconstructPath(cameFrom, endKey, baseX, baseY, plane);
			}

			if (closedSet.contains(currentKey)) {
				continue;
			}
			closedSet.add(currentKey);

			int cx = (int)(currentKey >> 16);
			int cy = (int)(currentKey & 0xFFFF);

			for (int[] dir : directions) {
				int nx = cx + dir[0];
				int ny = cy + dir[1];

				if (!isValidSceneTile(nx, ny)) {
					continue;
				}

				if (!canTravel(flags, cx, cy, dir[0], dir[1])) {
					continue;
				}

				long neighborKey = tileKey(nx, ny);
				if (closedSet.contains(neighborKey)) {
					continue;
				}

				int moveCost = (dir[0] != 0 && dir[1] != 0 ? 14 : 10);
				int wallPenalty = wallProximityCost(flags, nx, ny);
				int tentativeG = gScore.getOrDefault(currentKey, Integer.MAX_VALUE)
					+ moveCost + wallPenalty;

				if (tentativeG < gScore.getOrDefault(neighborKey, Integer.MAX_VALUE)) {
					cameFrom.put(neighborKey, currentKey);
					gScore.put(neighborKey, tentativeG);
					int f = tentativeG + heuristic(nx, ny, endSceneX, endSceneY);
					openSet.add(new long[]{neighborKey, f});
				}
			}
		}

		log.info("WEB_WALK: A* found no path from ({},{}) to ({},{})", startSceneX, startSceneY, endSceneX, endSceneY);
		return null;
	}

	private boolean canTravel(int[][] flags, int fromX, int fromY, int dx, int dy) {
		int toX = fromX + dx;
		int toY = fromY + dy;

		if ((flags[toX][toY] & MOVEMENT_BLOCKED) != 0) {
			return false;
		}

		if (dx == 0 && dy == 1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0;
		}
		if (dx == 0 && dy == -1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0;
		}
		if (dx == 1 && dy == 0) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0;
		}
		if (dx == -1 && dy == 0) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0;
		}

		if (dx == 1 && dy == 1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST) == 0
				&& (flags[fromX + 1][fromY] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_WEST)) == 0
				&& (flags[fromX][fromY + 1] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH)) == 0;
		}
		if (dx == 1 && dy == -1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST) == 0
				&& (flags[fromX + 1][fromY] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_WEST)) == 0
				&& (flags[fromX][fromY - 1] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_NORTH)) == 0;
		}
		if (dx == -1 && dy == 1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST) == 0
				&& (flags[fromX - 1][fromY] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_EAST)) == 0
				&& (flags[fromX][fromY + 1] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH)) == 0;
		}
		if (dx == -1 && dy == -1) {
			return (flags[toX][toY] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST) == 0
				&& (flags[fromX - 1][fromY] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_EAST)) == 0
				&& (flags[fromX][fromY - 1] & (MOVEMENT_BLOCKED | CollisionDataFlag.BLOCK_MOVEMENT_NORTH)) == 0;
		}

		return false;
	}

	private int wallProximityCost(int[][] flags, int sceneX, int sceneY) {
		int cost = 0;

		// Check 1-tile radius (immediate neighbors) — heavy penalty
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (dx == 0 && dy == 0) continue;
				int nx = sceneX + dx;
				int ny = sceneY + dy;
				if (!isValidSceneTile(nx, ny) || (flags[nx][ny] & MOVEMENT_BLOCKED) != 0) {
					cost += WALL_PROXIMITY_PENALTY_NEAR;
				}
			}
		}

		// Check 2-tile radius (outer ring only) — lighter penalty
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				// Skip the inner 1-tile ring (already counted above)
				if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) continue;
				int nx = sceneX + dx;
				int ny = sceneY + dy;
				if (!isValidSceneTile(nx, ny) || (flags[nx][ny] & MOVEMENT_BLOCKED) != 0) {
					cost += WALL_PROXIMITY_PENALTY_FAR;
				}
			}
		}

		return cost;
	}

	private List<WorldPoint> reconstructPath(Map<Long, Long> cameFrom, long endKey, int baseX, int baseY, int plane) {
		List<WorldPoint> path = new ArrayList<>();
		long current = endKey;

		while (cameFrom.containsKey(current)) {
			int sx = (int)(current >> 16);
			int sy = (int)(current & 0xFFFF);
			path.add(new WorldPoint(sx + baseX, sy + baseY, plane));
			current = cameFrom.get(current);
		}

		Collections.reverse(path);

		if (path.size() > MAX_PATH_LENGTH) {
			log.warn("WEB_WALK: Path truncated from {} to {} tiles", path.size(), MAX_PATH_LENGTH);
			path = new ArrayList<>(path.subList(0, MAX_PATH_LENGTH));
		}

		return path;
	}

	private long tileKey(int sceneX, int sceneY) {
		return ((long) sceneX << 16) | (sceneY & 0xFFFF);
	}

	private int heuristic(int x1, int y1, int x2, int y2) {
		int dx = Math.abs(x1 - x2);
		int dy = Math.abs(y1 - y2);
		return 10 * Math.max(dx, dy) + 4 * Math.min(dx, dy);
	}

	private boolean isValidSceneTile(int sceneX, int sceneY) {
		return sceneX >= 0 && sceneX < SCENE_SIZE && sceneY >= 0 && sceneY < SCENE_SIZE;
	}

	// ===== Path waypoint extraction =====

	/**
	 * Extract waypoints from an A* path. Waypoints are placed at direction changes
	 * and at regular intervals along straight segments. This ensures we never click
	 * a tile on the far side of a wall — we follow the path's turns.
	 *
	 * @param path       the full A* path (ordered from start toward end)
	 * @param maxSpacing max tiles between waypoints on straight segments
	 * @return list of waypoints (subset of path points)
	 */
	private List<WorldPoint> extractWaypoints(List<WorldPoint> path, int maxSpacing) {
		List<WorldPoint> waypoints = new ArrayList<>();
		if (path.isEmpty()) {
			return waypoints;
		}

		int lastWaypointIdx = 0;
		int prevDx = 0;
		int prevDy = 0;

		for (int i = 1; i < path.size(); i++) {
			int dx = Integer.signum(path.get(i).getX() - path.get(i - 1).getX());
			int dy = Integer.signum(path.get(i).getY() - path.get(i - 1).getY());

			boolean directionChanged = (i > 1) && (dx != prevDx || dy != prevDy);
			boolean maxDistReached = (i - lastWaypointIdx) >= maxSpacing;

			if (directionChanged) {
				// Place waypoint at the tile BEFORE the turn (last tile of the straight segment)
				waypoints.add(path.get(i - 1));
				lastWaypointIdx = i - 1;
			} else if (maxDistReached) {
				waypoints.add(path.get(i));
				lastWaypointIdx = i;
			}

			prevDx = dx;
			prevDy = dy;
		}

		// Always include the final path point
		WorldPoint last = path.get(path.size() - 1);
		if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(last)) {
			waypoints.add(last);
		}

		return waypoints;
	}

	// ===== Minimap clicking =====

	/**
	 * Check if a world point is safe to click — meaning neither the tile itself
	 * nor any of its immediate neighbors are blocked. This prevents minimap clicks
	 * from landing on or next to walls, which can cause the game engine to resolve
	 * the click to the wrong side.
	 */
	private boolean isSafeClickTile(SceneSnapshot snapshot, WorldPoint wp) {
		int sceneX = wp.getX() - snapshot.baseX;
		int sceneY = wp.getY() - snapshot.baseY;

		// Check the tile itself and all 8 neighbors
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				int nx = sceneX + dx;
				int ny = sceneY + dy;
				if (!isValidSceneTile(nx, ny)) {
					return false;
				}
				if ((snapshot.flags[nx][ny] & MOVEMENT_BLOCKED) != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Given a waypoint on a path, find the closest safe click target by walking
	 * backwards along the path. Returns the waypoint itself if it's safe, or a
	 * nearby path tile that has no blocked neighbors.
	 */
	private WorldPoint findSafeClickTarget(SceneSnapshot snapshot, List<WorldPoint> path, WorldPoint waypoint, WorldPoint playerPos) {
		if (isSafeClickTile(snapshot, waypoint)) {
			return waypoint;
		}

		// Find the waypoint's index in the path
		int waypointIdx = -1;
		for (int i = path.size() - 1; i >= 0; i--) {
			if (path.get(i).equals(waypoint)) {
				waypointIdx = i;
				break;
			}
		}

		if (waypointIdx < 0) {
			return waypoint; // not found in path, return as-is
		}

		// Walk backwards along the path to find a safe tile
		for (int i = waypointIdx - 1; i >= 0; i--) {
			WorldPoint candidate = path.get(i);
			if (candidate.distanceTo(playerPos) <= 1) {
				break; // too close to player, stop searching
			}
			if (isSafeClickTile(snapshot, candidate)) {
				log.debug("WEB_WALK: Waypoint {} near wall, using safer tile {} ({} tiles back)",
					waypoint, candidate, waypointIdx - i);
				return candidate;
			}
		}

		// No safe tile found — return original (better than nothing)
		log.debug("WEB_WALK: No safe alternative for waypoint {}, using as-is", waypoint);
		return waypoint;
	}

	/**
	 * Click the best waypoint on the path. Picks the farthest waypoint that is
	 * within minimap range, ensuring we follow direction changes and never click
	 * across walls. Waypoints near walls are shifted backwards along the path
	 * to a safe tile.
	 */
	private boolean clickNextWaypoint(List<WorldPoint> path, WorldPoint playerPos, MouseMovementProfile profile) {
		return clickNextWaypoint(null, path, playerPos, profile);
	}

	private boolean clickNextWaypoint(SceneSnapshot snapshot, List<WorldPoint> path, WorldPoint playerPos, MouseMovementProfile profile) {
		// Extract waypoints: direction changes + max 12-tile straight segments
		List<WorldPoint> waypoints = extractWaypoints(path, 12);

		if (waypoints.isEmpty()) {
			return false;
		}

		log.debug("WEB_WALK: Path has {} tiles, {} waypoints", path.size(), waypoints.size());

		// Try waypoints from farthest to nearest — but these are at turns/intervals,
		// so even the "farthest" one follows the path correctly
		for (int i = waypoints.size() - 1; i >= 0; i--) {
			WorldPoint wp = waypoints.get(i);
			if (playerPos.distanceTo(wp) <= 1) {
				continue;
			}

			// If we have collision data, ensure the click target is safe (not near walls)
			WorldPoint clickTarget = wp;
			if (snapshot != null) {
				clickTarget = findSafeClickTarget(snapshot, path, wp, playerPos);
				if (clickTarget.distanceTo(playerPos) <= 1) {
					continue; // safe target is too close to player
				}
			}

			if (clickMinimapWithRetry(clickTarget, profile)) {
				return true;
			}
		}

		// Last resort: click a close point on the raw path
		if (path.size() >= 2) {
			WorldPoint close = path.get(Math.min(3, path.size() - 1));
			return clickMinimapWithRetry(close, profile);
		}

		return false;
	}

	private boolean clickMinimapWithRetry(WorldPoint target, MouseMovementProfile profile) {
		for (int retry = 0; retry < MAX_MINIMAP_RETRIES; retry++) {
			Point minimapPoint = resolveMinimapPoint(target);
			if (minimapPoint != null) {
				int jitterX = (int)((Math.random() - 0.5) * 6);
				int jitterY = (int)((Math.random() - 0.5) * 6);

				log.info("WEB_WALK: Clicking minimap at screen({},{}) for world {}",
					minimapPoint.getX() + jitterX, minimapPoint.getY() + jitterY, target);

				mouseMovement.moveAndClick(
					new java.awt.Point(minimapPoint.getX() + jitterX, minimapPoint.getY() + jitterY),
					profile
				);
				return true;
			}

			if (retry < MAX_MINIMAP_RETRIES - 1) {
				log.debug("WEB_WALK: Minimap point null for {}, retrying ({}/{})", target, retry + 1, MAX_MINIMAP_RETRIES);
				sleep(300);
			}
		}

		log.debug("WEB_WALK: Could not resolve minimap point for {} after {} retries", target, MAX_MINIMAP_RETRIES);
		return false;
	}

	private Point resolveMinimapPoint(WorldPoint worldPoint) {
		return onClientThread(() -> {
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null) {
				return null;
			}

			LocalPoint localPoint = LocalPoint.fromWorld(wv, worldPoint);
			if (localPoint == null) {
				return null;
			}

			Point minimapPoint = Perspective.localToMinimap(client, localPoint);
			if (minimapPoint == null) {
				return null;
			}

			// Validate the point is within the safe clickable area of the minimap.
			// The minimap is circular, so points near the corners of the draw area
			// are outside the clickable region. We check against a safe radius that
			// is smaller than the full minimap draw area to avoid edge clicks.
			net.runelite.api.widgets.Widget minimapWidget;
			if (client.isResized()) {
				if (client.getVarbitValue(net.runelite.api.gameval.VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1) {
					minimapWidget = client.getWidget(net.runelite.api.widgets.WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
				} else {
					minimapWidget = client.getWidget(net.runelite.api.widgets.WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
				}
			} else {
				minimapWidget = client.getWidget(net.runelite.api.widgets.WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
			}

			if (minimapWidget == null) {
				return minimapPoint;
			}

			Point loc = minimapWidget.getCanvasLocation();
			int centerX = loc.getX() + minimapWidget.getWidth() / 2;
			int centerY = loc.getY() + minimapWidget.getHeight() / 2;
			int dx = minimapPoint.getX() - centerX;
			int dy = minimapPoint.getY() - centerY;
			double dist = Math.sqrt(dx * dx + dy * dy);

			// Use 80% of the minimap radius as the safe zone
			double safeRadius = Math.min(minimapWidget.getWidth(), minimapWidget.getHeight()) / 2.0 * 0.80;
			if (dist > safeRadius) {
				log.debug("WEB_WALK: Minimap point ({},{}) too close to edge (dist={}, safeRadius={}), rejecting",
					minimapPoint.getX(), minimapPoint.getY(), (int) dist, (int) safeRadius);
				return null;
			}

			return minimapPoint;
		});
	}

	// ===== Cross-region walking =====

	private WorldPoint getIntermediateTarget(SceneSnapshot snapshot, WorldPoint current, WorldPoint destination, int range) {
		int dx = destination.getX() - current.getX();
		int dy = destination.getY() - current.getY();

		double dist = Math.sqrt(dx * dx + dy * dy);

		int targetX, targetY;
		if (dist > 0) {
			targetX = current.getX() + (int)(dx / dist * range);
			targetY = current.getY() + (int)(dy / dist * range);
		} else {
			targetX = current.getX();
			targetY = current.getY();
		}

		// Clamp to scene bounds with a safe margin
		int sceneX = targetX - snapshot.baseX;
		int sceneY = targetY - snapshot.baseY;

		sceneX = Math.max(5, Math.min(SCENE_SIZE - 6, sceneX));
		sceneY = Math.max(5, Math.min(SCENE_SIZE - 6, sceneY));

		// If the target tile is blocked, search nearby for a walkable tile
		if ((snapshot.flags[sceneX][sceneY] & MOVEMENT_BLOCKED) != 0) {
			int bestX = sceneX;
			int bestY = sceneY;
			double bestDist = Double.MAX_VALUE;
			for (int r = 1; r <= 3; r++) {
				for (int ox = -r; ox <= r; ox++) {
					for (int oy = -r; oy <= r; oy++) {
						int nx = sceneX + ox;
						int ny = sceneY + oy;
						if (isValidSceneTile(nx, ny) && (snapshot.flags[nx][ny] & MOVEMENT_BLOCKED) == 0) {
							double d = Math.sqrt(ox * ox + oy * oy);
							if (d < bestDist) {
								bestDist = d;
								bestX = nx;
								bestY = ny;
							}
						}
					}
				}
				if (bestDist < Double.MAX_VALUE) break;
			}
			sceneX = bestX;
			sceneY = bestY;
		}

		targetX = sceneX + snapshot.baseX;
		targetY = sceneY + snapshot.baseY;

		return new WorldPoint(targetX, targetY, snapshot.plane);
	}

	// ===== Door/obstacle handling =====

	private boolean handleObstacleAlongPath(WorldPoint current, WorldPoint destination, MouseMovementProfile profile) {
		if (objectDetectionPlugin == null) {
			log.warn("WEB_WALK: ObjectDetectionPlugin not available for door handling");
			return false;
		}

		int dx = Integer.signum(destination.getX() - current.getX());
		int dy = Integer.signum(destination.getY() - current.getY());

		for (int range = 1; range <= 3; range++) {
			int checkX = current.getX() + dx * range;
			int checkY = current.getY() + dy * range;
			WorldPoint checkPoint = new WorldPoint(checkX, checkY, current.getPlane());

			GameObjectInfo obstacle = objectDetectionPlugin.getObjectAt(checkPoint);
			if (obstacle != null && isInteractableObstacle(obstacle)) {
				String action = getObstacleAction(obstacle);
				if (action != null) {
					log.info("WEB_WALK: Found obstacle '{}' at {} with action '{}', interacting",
						obstacle.getName(), checkPoint, action);
					boolean success = interaction.interactWithObject(obstacle.getName(), action, profile);
					if (success) {
						sleep(800 + (int)(Math.random() * 600));
						return true;
					}
				}
			}
		}

		List<GameObjectInfo> nearbyDoors = new ArrayList<>();
		for (String doorName : OBSTACLE_NAMES_LOWER) {
			List<GameObjectInfo> objects = objectDetectionPlugin.getObjectsByName(doorName);
			for (GameObjectInfo obj : objects) {
				if (isInteractableObstacle(obj) && obj.distanceFrom(current) <= 5) {
					nearbyDoors.add(obj);
				}
			}
		}

		if (!nearbyDoors.isEmpty()) {
			nearbyDoors.sort(Comparator.comparingDouble(o -> o.distanceFrom(current)));
			GameObjectInfo closest = nearbyDoors.get(0);
			String action = getObstacleAction(closest);
			if (action != null) {
				log.info("WEB_WALK: Opening nearby obstacle '{}' at {} with action '{}'",
					closest.getName(), closest.getLocation(), action);
				boolean success = interaction.interactWithObject(closest.getName(), action, profile);
				if (success) {
					sleep(800 + (int)(Math.random() * 600));
					return true;
				}
			}
		}

		log.info("WEB_WALK: No interactable obstacles found near path");
		return false;
	}

	private boolean isInteractableObstacle(GameObjectInfo obj) {
		if (obj.getActions() == null) {
			return false;
		}
		String nameLower = obj.getName().toLowerCase();
		if (!OBSTACLE_NAMES_LOWER.contains(nameLower)) {
			return false;
		}
		return obj.getActions().stream().anyMatch(a -> a != null && GATE_ACTIONS.contains(a));
	}

	private String getObstacleAction(GameObjectInfo obj) {
		if (obj.getActions() == null) {
			return null;
		}
		for (String action : obj.getActions()) {
			if (action != null && GATE_ACTIONS.contains(action)) {
				return action;
			}
		}
		return null;
	}

	// ===== Movement waiting =====

	private boolean isPlayerMoving() {
		Boolean moving = onClientThread(() ->
			client.getLocalPlayer().getPoseAnimation() != client.getLocalPlayer().getIdlePoseAnimation());
		return Boolean.TRUE.equals(moving);
	}

	/**
	 * Wait for the player to arrive at an intermediate target, but also monitor
	 * distance to the final destination. If during movement the player becomes
	 * closer to the destination than the intermediate target is, break out early
	 * so the main loop can re-path from the new, better position.
	 */
	private void waitAndMonitor(WorldPoint intermediateTarget, WorldPoint finalDestination, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		WorldPoint lastPos = null;
		int stuckCounter = 0;
		int intermediateDistToDest = intermediateTarget.distanceTo(finalDestination);

		sleep(200 + (int)(Math.random() * 150));

		while (System.currentTimeMillis() < deadline && !cancelled) {
			WorldPoint current = getPlayerLocation();
			if (current == null) {
				break;
			}

			int distToIntermediate = current.distanceTo(intermediateTarget);
			int distToDest = current.distanceTo(finalDestination);

			// Reached the intermediate target
			if (distToIntermediate <= 2) {
				log.debug("WEB_WALK: Arrived near intermediate target {} (dist={})", intermediateTarget, distToIntermediate);
				return;
			}

			// Reached the final destination
			if (distToDest <= 1) {
				log.debug("WEB_WALK: Arrived at final destination during transit");
				return;
			}

			// Mid-movement re-evaluation: if player is now closer to the destination
			// than the intermediate target is, break out early to re-path from here.
			// This handles cases like the GE where walking through an entrance
			// puts you closer to the center than your original click target.
			if (distToDest < intermediateDistToDest - 2 && isPlayerMoving()) {
				log.info("WEB_WALK: Player closer to destination ({}) than intermediate target ({}) — re-pathing",
					distToDest, intermediateDistToDest);
				return;
			}

			// Stuck detection
			if (current.equals(lastPos)) {
				stuckCounter++;
				if (!isPlayerMoving() && stuckCounter >= 5) {
					log.info("WEB_WALK: Player stopped at {} (not at target), breaking wait", current);
					return;
				}
			} else {
				stuckCounter = 0;
			}
			lastPos = current;

			sleep(200);
		}

		log.debug("WEB_WALK: Wait timed out");
	}

	private void waitForMovementToStop(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;

		sleep(400);

		while (System.currentTimeMillis() < deadline && !cancelled) {
			if (!isPlayerMoving()) {
				return;
			}
			sleep(100);
		}
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ===== Debug info =====

	public Map<String, Object> getDebugInfo(WorldPoint destination) {
		return onClientThread(() -> {
			Map<String, Object> info = new HashMap<>();
			WorldPoint current = client.getLocalPlayer().getWorldLocation();
			info.put("currentPosition", Map.of("x", current.getX(), "y", current.getY(), "plane", current.getPlane()));

			int baseX = client.getBaseX();
			int baseY = client.getBaseY();
			info.put("baseX", baseX);
			info.put("baseY", baseY);
			info.put("sceneSize", SCENE_SIZE);
			info.put("isMoving", client.getLocalPlayer().getPoseAnimation() != client.getLocalPlayer().getIdlePoseAnimation());

			if (destination != null) {
				int destSceneX = destination.getX() - baseX;
				int destSceneY = destination.getY() - baseY;
				boolean inScene = isValidSceneTile(destSceneX, destSceneY);

				info.put("destination", Map.of("x", destination.getX(), "y", destination.getY(), "plane", destination.getPlane()));
				info.put("distance", current.distanceTo(destination));
				info.put("destinationInScene", inScene);
				info.put("destSceneCoords", Map.of("x", destSceneX, "y", destSceneY));

				if (inScene) {
					CollisionData[] collisionMaps = client.getCollisionMaps();
					if (collisionMaps != null) {
						int plane = client.getPlane();
						int[][] flags = collisionMaps[plane].getFlags();
						int[][] flagsCopy = new int[flags.length][];
						for (int i = 0; i < flags.length; i++) {
							flagsCopy[i] = flags[i].clone();
						}
						SceneSnapshot snap = new SceneSnapshot(flagsCopy, baseX, baseY, plane, current);
						List<WorldPoint> path = findPathInScene(snap, current, destination);
						info.put("pathFound", path != null && !path.isEmpty());
						info.put("pathLength", path != null ? path.size() : 0);
					}
				}
			}

			return info;
		});
	}
}
