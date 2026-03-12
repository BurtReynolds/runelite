package net.runelite.client.plugins.interaction;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.util.List;

/**
 * Draws a visible cursor at the virtual mouse position so you can see
 * where the automated mouse movement is happening on the game canvas.
 * Includes a fading trail showing the recent path.
 */
public class VirtualMouseOverlay extends Overlay {

	private static final int CURSOR_RADIUS = 6;
	private static final Color CURSOR_FILL = new Color(255, 50, 50, 180);
	private static final Color CURSOR_BORDER = new Color(255, 255, 255, 220);
	private static final Color CROSSHAIR_COLOR = new Color(255, 255, 50, 150);

	// Trail settings
	private static final long TRAIL_MAX_AGE_MS = 800;
	private static final int TRAIL_BASE_ALPHA = 160;
	private static final float TRAIL_MAX_WIDTH = 4.0f;
	private static final float TRAIL_MIN_WIDTH = 1.0f;

	private final HumanMouseMovement mouseMovement;
	private volatile boolean enabled = true;

	public VirtualMouseOverlay(HumanMouseMovement mouseMovement) {
		this.mouseMovement = mouseMovement;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGHEST);
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!enabled || mouseMovement == null) {
			return null;
		}

		Point pos = mouseMovement.getVirtualPosition();
		if (pos == null) {
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Draw trail
		renderTrail(graphics);

		int x = pos.x;
		int y = pos.y;

		// Draw crosshair lines
		graphics.setColor(CROSSHAIR_COLOR);
		graphics.setStroke(new BasicStroke(1));
		graphics.drawLine(x - 12, y, x - CURSOR_RADIUS - 2, y);
		graphics.drawLine(x + CURSOR_RADIUS + 2, y, x + 12, y);
		graphics.drawLine(x, y - 12, x, y - CURSOR_RADIUS - 2);
		graphics.drawLine(x, y + CURSOR_RADIUS + 2, x, y + 12);

		// Draw filled circle
		graphics.setColor(CURSOR_FILL);
		graphics.fillOval(x - CURSOR_RADIUS, y - CURSOR_RADIUS, CURSOR_RADIUS * 2, CURSOR_RADIUS * 2);

		// Draw border
		graphics.setColor(CURSOR_BORDER);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawOval(x - CURSOR_RADIUS, y - CURSOR_RADIUS, CURSOR_RADIUS * 2, CURSOR_RADIUS * 2);

		return null;
	}

	private void renderTrail(Graphics2D graphics) {
		List<HumanMouseMovement.TrailPoint> trail = mouseMovement.getTrailHistory();
		if (trail.size() < 2) {
			return;
		}

		long now = System.currentTimeMillis();

		for (int i = 1; i < trail.size(); i++) {
			HumanMouseMovement.TrailPoint prev = trail.get(i - 1);
			HumanMouseMovement.TrailPoint curr = trail.get(i);

			// Age-based fade: newer points are more opaque
			long age = now - curr.timestamp;
			if (age > TRAIL_MAX_AGE_MS) {
				continue;
			}

			float ageFraction = (float) age / TRAIL_MAX_AGE_MS; // 0 = newest, 1 = oldest
			float fadeFraction = 1.0f - ageFraction;

			// Alpha decreases with age
			int alpha = (int) (TRAIL_BASE_ALPHA * fadeFraction * fadeFraction);
			if (alpha <= 0) {
				continue;
			}

			// Width decreases with age
			float width = TRAIL_MIN_WIDTH + (TRAIL_MAX_WIDTH - TRAIL_MIN_WIDTH) * fadeFraction;

			// Color: red-orange trail that fades
			graphics.setColor(new Color(255, 80, 30, alpha));
			graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.drawLine(prev.x, prev.y, curr.x, curr.y);
		}
	}
}
