package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

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

	public HumanMouseMovement(Client client) {
		this.client = client;
		// Initialize virtual position to center of canvas
		this.virtualX = client.getCanvasWidth() / 2;
		this.virtualY = client.getCanvasHeight() / 2;
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

		// Generate control points for cubic Bezier curve
		Point cp1 = generateControlPoint(start, target, 0.3, profile);
		Point cp2 = generateControlPoint(start, target, 0.7, profile);

		// Optionally add overshoot
		Point effectiveTarget = target;
		if (profile.overshoot && Math.random() < 0.3) {
			double overshootDistance = start.distance(target) * 0.05 * (0.5 + Math.random());
			double angle = Math.atan2(target.y - start.y, target.x - start.x);
			effectiveTarget = new Point(
				target.x + (int) (Math.cos(angle) * overshootDistance),
				target.y + (int) (Math.sin(angle) * overshootDistance)
			);
		}

		// Calculate curve points
		List<Point> path = calculateBezierPath(start, cp1, cp2, effectiveTarget);

		// If we overshot, add correction path back to the real target
		if (effectiveTarget != target) {
			Point correctionCp1 = generateControlPoint(effectiveTarget, target, 0.4, MouseMovementProfile.CAREFUL);
			Point correctionCp2 = generateControlPoint(effectiveTarget, target, 0.6, MouseMovementProfile.CAREFUL);
			List<Point> correction = calculateBezierPath(effectiveTarget, correctionCp1, correctionCp2, target);
			path.addAll(correction);
		}

		// Execute movement by dispatching mouse events along path
		executePath(canvas, path, profile);
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

		// Random offset based on distance and randomness setting
		double offsetMagnitude = distance * profile.randomness * (0.2 + Math.random() * 0.3);
		double angle = Math.random() * 2 * Math.PI;

		// Linear interpolation with random offset
		double x = start.x + (end.x - start.x) * t + Math.cos(angle) * offsetMagnitude;
		double y = start.y + (end.y - start.y) * t + Math.sin(angle) * offsetMagnitude;

		return new Point((int) x, (int) y);
	}

	private List<Point> calculateBezierPath(Point p0, Point p1, Point p2, Point p3) {
		List<Point> points = new ArrayList<>();
		int steps = Math.max(10, (int) (p0.distance(p3) / 2)); // ~2 pixels per step

		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double x = cubicBezier(p0.x, p1.x, p2.x, p3.x, t);
			double y = cubicBezier(p0.y, p1.y, p2.y, p3.y, t);
			points.add(new Point((int) x, (int) y));
		}

		return points;
	}

	private double cubicBezier(double p0, double p1, double p2, double p3, double t) {
		double u = 1 - t;
		return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
	}

	private void executePath(Canvas canvas, List<Point> path, MouseMovementProfile profile) {
		for (int i = 0; i < path.size(); i++) {
			Point p = path.get(i);

			// Clamp to canvas bounds
			int clampedX = Math.max(0, Math.min(p.x, client.getCanvasWidth() - 1));
			int clampedY = Math.max(0, Math.min(p.y, client.getCanvasHeight() - 1));

			// Update tracked position and dispatch move event
			virtualX = clampedX;
			virtualY = clampedY;
			dispatchMoveEvent(canvas, clampedX, clampedY);

			// Calculate delay with easing
			double progress = (double) i / path.size();
			int delay = calculateDelay(progress, profile);

			// Occasional fatigue pause
			if (profile.fatigueChance > 0 && Math.random() < profile.fatigueChance * 0.1) {
				delay += (int) (Math.random() * 20);
			}

			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private int calculateDelay(double progress, MouseMovementProfile profile) {
		// Ease-in-out cubic timing function
		double speed = easeInOutCubic(progress);

		// Base delay with randomness
		int baseDelay = profile.baseDelayMs;
		int variance = (int) (baseDelay * profile.variance * (Math.random() - 0.5) * 2);

		return Math.max(1, (int) (baseDelay / Math.max(0.5, speed)) + variance);
	}

	private double easeInOutCubic(double t) {
		return t < 0.5
			? 4 * t * t * t
			: 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
