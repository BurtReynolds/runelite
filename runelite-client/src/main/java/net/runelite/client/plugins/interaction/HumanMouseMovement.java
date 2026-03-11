package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements human-like mouse movement using Bezier curves
 */
@Slf4j
public class HumanMouseMovement {
    private final Client client;
    private Robot robot;

    public HumanMouseMovement(Client client) {
        this.client = client;
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            log.error("Failed to create Robot for mouse movement", e);
        }
    }

    /**
     * Move mouse using Bezier curve from current position to target
     */
    public void moveMouse(Point target, MouseMovementProfile profile) {
        if (robot == null) {
            log.error("Robot not initialized, cannot move mouse");
            return;
        }

        Point start = getCurrentMousePosition();
        if (start == null) {
            log.warn("Could not get current mouse position");
            return;
        }

        // Generate control points for cubic Bezier curve
        Point cp1 = generateControlPoint(start, target, 0.3, profile);
        Point cp2 = generateControlPoint(start, target, 0.7, profile);

        // Calculate curve points
        List<Point> path = calculateBezierPath(start, cp1, cp2, target);

        // Execute movement with timing
        executePath(path, profile);
    }

    /**
     * Move mouse to target and click
     */
    public void moveAndClick(Point target, MouseMovementProfile profile) {
        moveMouse(target, profile);

        // Random delay before click
        sleep(50 + (int) (Math.random() * 100));

        click();
    }

    /**
     * Click at current mouse position
     */
    public void click() {
        if (robot == null) {
            log.error("Robot not initialized, cannot click");
            return;
        }

        try {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(50 + (int) (Math.random() * 50)); // Random click duration
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Click interrupted", e);
        }
    }

    /**
     * Right-click at current mouse position
     */
    public void rightClick() {
        if (robot == null) {
            log.error("Robot not initialized, cannot right-click");
            return;
        }

        try {
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
            Thread.sleep(50 + (int) (Math.random() * 50));
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Right-click interrupted", e);
        }
    }

    private Point getCurrentMousePosition() {
        try {
            return MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            log.error("Failed to get mouse position", e);
            return null;
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

    private void executePath(List<Point> path, MouseMovementProfile profile) {
        for (int i = 0; i < path.size(); i++) {
            Point p = path.get(i);

            // Move mouse to point
            robot.mouseMove(p.x, p.y);

            // Calculate delay with variance
            double progress = (double) i / path.size();
            int delay = calculateDelay(progress, profile);

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
