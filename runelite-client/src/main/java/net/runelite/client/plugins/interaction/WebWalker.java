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
				boolean walked = walkTowardEdge(snapshot, current, destination, profile);
				if (!walked) {
					consecutiveMinimapFails++;
					if (consecutiveMinimapFails >= 3) {
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
				sleep(400 + (int)(Math.random() * 400));
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

		if (!clickAlongPath(path, current, profile)) {
			return false;
		}

		waitForPlayerToArrive(target, 8000);
		return true;
	}

	private boolean walkTowardEdge(SceneSnapshot snapshot, WorldPoint current, WorldPoint destination, MouseMovementProfile profile) {
		// Try pathfinding at decreasing ranges
		for (int range = 16; range >= 5; range -= 3) {
			WorldPoint intermediate = getIntermediateTarget(snapshot, current, destination, range);
			List<WorldPoint> path = findPathInScene(snapshot, current, intermediate);
			if (path != null && !path.isEmpty()) {
				log.info("WEB_WALK: Path found ({} tiles) toward {} (range={})", path.size(), intermediate, range);
				if (clickAlongPath(path, current, profile)) {
					waitForPlayerToArrive(intermediate, 10000);
					return true;
				}
			}
		}

		// Fallback: click minimap directly toward destination at decreasing distances
		for (int dist = 14; dist >= 3; dist -= 3) {
			WorldPoint directTarget = getIntermediateTarget(snapshot, current, destination, dist);
			log.info("WEB_WALK: Trying direct minimap click toward {} (dist={})", directTarget, dist);
			if (clickMinimapWithRetry(directTarget, profile)) {
				waitForPlayerToArrive(directTarget, 10000);
				return true;
			}
		}

		log.warn("WEB_WALK: All minimap click attempts failed");
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

				int tentativeG = gScore.getOrDefault(currentKey, Integer.MAX_VALUE)
					+ (dir[0] != 0 && dir[1] != 0 ? 14 : 10);

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

	// ===== Minimap clicking =====

	private boolean clickAlongPath(List<WorldPoint> path, WorldPoint playerPos, MouseMovementProfile profile) {
		// Walk backwards along the path to find the farthest point we can actually click on the minimap
		for (int i = path.size() - 1; i >= 0; i--) {
			WorldPoint wp = path.get(i);
			if (playerPos.distanceTo(wp) <= 1) {
				continue;
			}
			if (clickMinimapWithRetry(wp, profile)) {
				return true;
			}
		}

		// If nothing on the path worked, try a very close point
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

			return Perspective.localToMinimap(client, localPoint);
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

	private void waitForPlayerToArrive(WorldPoint target, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		WorldPoint lastPos = null;
		int stuckCounter = 0;

		sleep(300 + (int)(Math.random() * 200));

		while (System.currentTimeMillis() < deadline && !cancelled) {
			WorldPoint current = getPlayerLocation();
			if (current == null) {
				break;
			}
			int dist = current.distanceTo(target);

			if (dist <= 2) {
				log.debug("WEB_WALK: Arrived near target {} (dist={})", target, dist);
				return;
			}

			if (current.equals(lastPos)) {
				stuckCounter++;
				if (stuckCounter >= 10) {
					log.info("WEB_WALK: Player appears stuck at {}, breaking wait", current);
					return;
				}
			} else {
				stuckCounter = 0;
			}
			lastPos = current;

			sleep(200);
		}

		log.debug("WEB_WALK: Wait for arrival timed out");
	}

	private void waitForMovementToStop(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;

		sleep(600);

		while (System.currentTimeMillis() < deadline && !cancelled) {
			Boolean moving = onClientThread(() ->
				client.getLocalPlayer().getPoseAnimation() != client.getLocalPlayer().getIdlePoseAnimation());
			if (moving == null || !moving) {
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
