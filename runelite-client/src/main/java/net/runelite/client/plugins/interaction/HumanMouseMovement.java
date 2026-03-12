package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implements human-like mouse movement using Bezier curves.
 * Uses virtual mouse events dispatched to the game canvas instead of java.awt.Robot,
 * so the system cursor is not moved.
 */
@Slf4j
public class HumanMouseMovement {
	private final Client client;

	// Tracked virtual mouse position (canvas-relative)
	private int virtualX;
	private int virtualY;

	// Trail history for overlay rendering (thread-safe)
	private static final int TRAIL_MAX_SIZE = 50;
	private final CopyOnWriteArrayList<TrailPoint> trailHistory = new CopyOnWriteArrayList<>();

	public HumanMouseMovement(Client client) {
		this.client = client;
		// Initialize virtual position to center of canvas
		this.virtualX = client.getCanvasWidth() / 2;
		this.virtualY = client.getCanvasHeight() / 2;
	}

	/**
	 * A point in the trail with a timestamp for age-based fading.
	 */
	public static class TrailPoint {
		public final int x;
		public final int y;
		public final long timestamp;

		public TrailPoint(int x, int y, long timestamp) {
			this.x = x;
			this.y = y;
			this.timestamp = timestamp;
		}
	}

	/**
	 * Get a snapshot of the trail history for rendering.
	 */
	public List<TrailPoint> getTrailHistory() {
		return new ArrayList<>(trailHistory);
	}

	/**
	 * Move virtual mouse using Bezier curve from current position to target.
	 * Target coordinates are relative to the game canvas.
	 */
	public void moveMouse(Point target, MouseMovementProfile profile) {
		Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.error("Game canvas not available, cannot move mouse");
			return;
		}

		Point start = new Point(virtualX, virtualY);
		double distance = start.distance(target);

		// For very short distances, just jump there
		if (distance < 3) {
			virtualX = target.x;
			virtualY = target.y;
			dispatchMoveEvent(canvas, target.x, target.y);
			addTrailPoint(target.x, target.y);
			return;
		}

		// Generate control points for cubic Bezier curve
		Point cp1 = generateControlPoint(start, target, 0.25, profile);
		Point cp2 = generateControlPoint(start, target, 0.75, profile);

		// Optionally add overshoot
		Point effectiveTarget = target;
		if (profile.overshoot && Math.random() < 0.25) {
			double overshootDistance = distance * 0.04 * (0.5 + Math.random() * 0.5);
			double angle = Math.atan2(target.y - start.y, target.x - start.x);
			effectiveTarget = new Point(
				target.x + (int) (Math.cos(angle) * overshootDistance),
				target.y + (int) (Math.sin(angle) * overshootDistance)
			);
		}

		// Calculate curve points
		List<Point> path = calculateBezierPath(start, cp1, cp2, effectiveTarget, distance);

		// If we overshot, add correction path back to the real target
		if (effectiveTarget != target) {
			double corrDist = effectiveTarget.distance(target);
			Point correctionCp1 = generateControlPoint(effectiveTarget, target, 0.4, MouseMovementProfile.CAREFUL);
			Point correctionCp2 = generateControlPoint(effectiveTarget, target, 0.6, MouseMovementProfile.CAREFUL);
			List<Point> correction = calculateBezierPath(effectiveTarget, correctionCp1, correctionCp2, target, corrDist);
			path.addAll(correction);
		}

		// Execute movement by dispatching mouse events along path
		executePath(canvas, path, profile, distance);
	}

	/**
	 * Move virtual mouse to target and click.
	 */
	public void moveAndClick(Point target, MouseMovementProfile profile) {
		moveMouse(target, profile);

		// Random delay before click
		sleep(50 + (int) (Math.random() * 100));

		click();
	}

	/**
	 * Left-click at current virtual mouse position.
	 */
	public void click() {
		Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.error("Game canvas not available, cannot click");
			return;
		}

		long now = System.currentTimeMillis();

		// Press
		dispatchMouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now,
			MouseEvent.BUTTON1, MouseEvent.BUTTON1_DOWN_MASK);

		// Random hold duration
		int holdMs = 50 + (int) (Math.random() * 50);
		sleep(holdMs);

		now = System.currentTimeMillis();

		// Release
		dispatchMouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now,
			MouseEvent.BUTTON1, 0);

		// Click (press + release generates a click event)
		dispatchMouseEvent(canvas, MouseEvent.MOUSE_CLICKED, now,
			MouseEvent.BUTTON1, 0);
	}

	/**
	 * Right-click at current virtual mouse position.
	 */
	public void rightClick() {
		Canvas canvas = client.getCanvas();
		if (canvas == null) {
			log.error("Game canvas not available, cannot right-click");
			return;
		}

		long now = System.currentTimeMillis();

		dispatchMouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now,
			MouseEvent.BUTTON3, MouseEvent.BUTTON3_DOWN_MASK);

		int holdMs = 50 + (int) (Math.random() * 50);
		sleep(holdMs);

		now = System.currentTimeMillis();

		dispatchMouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now,
			MouseEvent.BUTTON3, 0);

		dispatchMouseEvent(canvas, MouseEvent.MOUSE_CLICKED, now,
			MouseEvent.BUTTON3, 0);
	}

	/**
	 * Get the current virtual mouse position (canvas-relative).
	 */
	public Point getVirtualPosition() {
		return new Point(virtualX, virtualY);
	}

	// ===== Internal helpers =====

	private void addTrailPoint(int x, int y) {
		trailHistory.add(new TrailPoint(x, y, System.currentTimeMillis()));
		// Trim old points
		while (trailHistory.size() > TRAIL_MAX_SIZE) {
			trailHistory.remove(0);
		}
	}

	private void dispatchMouseEvent(Canvas canvas, int id, long when,
									int button, int modifiers) {
		try {
			MouseEvent event = new MouseEvent(
				canvas,
				id,
				when,
				modifiers,
				virtualX,
				virtualY,
				1,       // click count
				id == MouseEvent.MOUSE_PRESSED && button == MouseEvent.BUTTON3, // popupTrigger for right-click press
				button
			);
			canvas.dispatchEvent(event);
		} catch (Exception e) {
			log.error("Failed to dispatch mouse event (id={})", id, e);
		}
	}

	private void dispatchMoveEvent(Canvas canvas, int x, int y) {
		try {
			MouseEvent event = new MouseEvent(
				canvas,
				MouseEvent.MOUSE_MOVED,
				System.currentTimeMillis(),
				0,
				x,
				y,
				0,
				false,
				MouseEvent.NOBUTTON
			);
			canvas.dispatchEvent(event);
		} catch (Exception e) {
			log.error("Failed to dispatch mouse move event", e);
		}
	}

	private Point generateControlPoint(Point start, Point end, double t,
										MouseMovementProfile profile) {
		double distance = start.distance(end);

		// Offset perpendicular to the line between start and end for natural curves
		double lineAngle = Math.atan2(end.y - start.y, end.x - start.x);
		// Perpendicular offset with slight randomness in direction
		double perpAngle = lineAngle + Math.PI / 2 * (Math.random() < 0.5 ? 1 : -1);
		// Add a small random deviation to the perpendicular angle (up to +/- 30 degrees)
		perpAngle += (Math.random() - 0.5) * Math.PI / 3;

		double offsetMagnitude = distance * profile.randomness * (0.15 + Math.random() * 0.25);

		// Linear interpolation with perpendicular offset
		double x = start.x + (end.x - start.x) * t + Math.cos(perpAngle) * offsetMagnitude;
		double y = start.y + (end.y - start.y) * t + Math.sin(perpAngle) * offsetMagnitude;

		return new Point((int) x, (int) y);
	}

	private List<Point> calculateBezierPath(Point p0, Point p1, Point p2, Point p3, double distance) {
		List<Point> points = new ArrayList<>();
		// Ensure enough steps for smooth movement: at least 60 steps, ~1.5px per step for longer moves
		int steps = Math.max(60, (int) (distance / 1.5));
		// Cap at reasonable maximum
		steps = Math.min(steps, 500);

		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double x = cubicBezier(p0.x, p1.x, p2.x, p3.x, t);
			double y = cubicBezier(p0.y, p1.y, p2.y, p3.y, t);
			points.add(new Point((int) x, (int) y));
		}

		// Remove duplicate consecutive points
		List<Point> deduped = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			if (i == 0 || points.get(i).x != points.get(i - 1).x || points.get(i).y != points.get(i - 1).y) {
				deduped.add(points.get(i));
			}
		}

		return deduped;
	}

	private double cubicBezier(double p0, double p1, double p2, double p3, double t) {
		double u = 1 - t;
		return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
	}

	private void executePath(Canvas canvas, List<Point> path, MouseMovementProfile profile, double totalDistance) {
		if (path.isEmpty()) {
			return;
		}

		// Calculate total movement duration using Fitts' Law inspired model
		// Human mouse movements typically take 150-500ms depending on distance
		double baseDuration = profile.baseDelayMs + totalDistance * 0.8;
		// Add slight randomness to total duration (+/- 15%)
		double durationVariance = 1.0 + (Math.random() - 0.5) * 0.3 * profile.variance;
		double totalDurationMs = baseDuration * durationVariance;
		// Clamp to reasonable range
		totalDurationMs = Math.max(100, Math.min(totalDurationMs, 1200));

		int numSteps = path.size();

		// Pre-compute per-step delays using velocity easing
		// Human movement: accelerate quickly, fast in middle, decelerate at end
		// We use the inverse approach: compute velocity multiplier per step,
		// then normalize so total time matches totalDurationMs
		double[] rawDelays = new double[numSteps];
		double rawTotal = 0;
		for (int i = 0; i < numSteps; i++) {
			double t = (double) i / Math.max(1, numSteps - 1);
			// Velocity profile: bell curve peaking in the middle
			// At t=0 and t=1, velocity is low (slow), at t=0.5 velocity is high (fast)
			// delay = 1/velocity, so high velocity = low delay
			double velocity = velocityProfile(t);
			rawDelays[i] = 1.0 / Math.max(0.15, velocity);
			rawTotal += rawDelays[i];
		}

		// Normalize delays so they sum to totalDurationMs
		double scale = totalDurationMs / rawTotal;

		for (int i = 0; i < numSteps; i++) {
			Point p = path.get(i);

			// Clamp to canvas bounds
			int clampedX = Math.max(0, Math.min(p.x, client.getCanvasWidth() - 1));
			int clampedY = Math.max(0, Math.min(p.y, client.getCanvasHeight() - 1));

			// Update tracked position and dispatch move event
			virtualX = clampedX;
			virtualY = clampedY;
			dispatchMoveEvent(canvas, clampedX, clampedY);
			addTrailPoint(clampedX, clampedY);

			if (i < numSteps - 1) {
				double delayMs = rawDelays[i] * scale;

				// Occasional micro-pause for human realism (not at start/end)
				if (i > numSteps * 0.2 && i < numSteps * 0.8
					&& profile.fatigueChance > 0 && Math.random() < profile.fatigueChance * 0.02) {
					delayMs += 10 + Math.random() * 25;
				}

				int sleepMs = (int) Math.round(delayMs);
				if (sleepMs >= 2) {
					try {
						Thread.sleep(sleepMs);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				} else if (delayMs > 0.1) {
					// Sub-millisecond: busy-wait for precision
					long targetNanos = System.nanoTime() + (long) (delayMs * 1_000_000);
					while (System.nanoTime() < targetNanos) {
						Thread.yield();
					}
				}
			}
		}
	}

	/**
	 * Human-like velocity profile for mouse movement.
	 * Returns a value 0-1 representing relative speed at position t along the path.
	 * Shape: slow start, quick ramp to fast, sustained fast through middle, slow approach at end.
	 * This mimics real human mouse behavior where the hand accelerates quickly,
	 * maintains speed, then decelerates to precisely land on the target.
	 */
	private double velocityProfile(double t) {
		// Asymmetric bell: faster ramp-up, longer sustain, gradual slowdown
		// Using a piecewise function:
		//   0.0-0.15: quick acceleration (cubic ease-in)
		//   0.15-0.75: fast sustained movement
		//   0.75-1.0: deceleration to target (quadratic ease-out)
		if (t < 0.15) {
			// Quick acceleration phase
			double phase = t / 0.15;
			return 0.1 + 0.9 * phase * phase;
		} else if (t < 0.75) {
			// Sustained fast movement with slight natural variation
			return 1.0;
		} else {
			// Deceleration phase — slows down as we approach target
			double phase = (t - 0.75) / 0.25;
			return 1.0 - 0.85 * phase * phase;
		}
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
