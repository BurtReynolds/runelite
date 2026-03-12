package net.runelite.client.plugins.interaction;

/**
 * Defines parameters for human-like mouse movement behavior.
 * Fully customizable for anti-ban pattern experimentation.
 *
 * baseDelayMs represents the minimum movement duration in milliseconds.
 * The actual duration scales with distance: totalMs = baseDelayMs + distance * 1.2
 * Typical human mouse movements take 200-600ms.
 */
public class MouseMovementProfile {
    public final double randomness;     // 0.0 - 1.0 (control point offset magnitude)
    public final int baseDelayMs;       // Minimum movement duration (ms)
    public final double variance;       // Timing variance factor (0.0 - 1.0)
    public final boolean overshoot;     // Whether to overshoot target and correct
    public final double fatigueChance;  // Chance of slight miss/drift (0.0 - 1.0)
    public final int jitterRadius;      // Maximum pixel jitter when clicking (px)
    public final double curvature;      // Bezier curve aggression (0.0 - 1.0)

    /**
     * Create a fully customized mouse movement profile.
     *
     * @param randomness Control point offset magnitude (0.0 = straight line, 1.0 = very curved)
     * @param baseDelayMs Minimum movement duration in milliseconds (total time scales with distance)
     * @param variance Timing variance factor (0.0 = consistent, 1.0 = highly variable)
     * @param overshoot Whether to occasionally overshoot target and correct back
     * @param fatigueChance Probability of slight inaccuracy (0.0 = perfect, 1.0 = always imperfect)
     * @param jitterRadius Maximum pixel offset when clicking a target
     * @param curvature How aggressive the curve is (0.0 = gentle, 1.0 = aggressive)
     */
    public MouseMovementProfile(double randomness, int baseDelayMs, double variance,
                                boolean overshoot, double fatigueChance,
                                int jitterRadius, double curvature) {
        this.randomness = clamp(randomness, 0.0, 1.0);
        this.baseDelayMs = Math.max(1, baseDelayMs);
        this.variance = clamp(variance, 0.0, 1.0);
        this.overshoot = overshoot;
        this.fatigueChance = clamp(fatigueChance, 0.0, 1.0);
        this.jitterRadius = Math.max(0, jitterRadius);
        this.curvature = clamp(curvature, 0.0, 1.0);
    }

    /**
     * Create a profile with default jitter and curvature values.
     * For backward compatibility with existing code.
     */
    public MouseMovementProfile(double randomness, int baseDelayMs, double variance,
                                boolean overshoot, double fatigueChance) {
        this(randomness, baseDelayMs, variance, overshoot, fatigueChance, 5, 0.5);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // Preset profiles — baseDelayMs is the minimum duration, actual time = baseDelayMs + distance * 1.2
    public static final MouseMovementProfile FAST =
        new MouseMovementProfile(0.15, 80, 0.15, false, 0.05, 3, 0.3);

    public static final MouseMovementProfile NORMAL =
        new MouseMovementProfile(0.25, 120, 0.25, true, 0.1, 5, 0.5);

    public static final MouseMovementProfile CAREFUL =
        new MouseMovementProfile(0.18, 180, 0.15, false, 0.02, 2, 0.4);

    public static final MouseMovementProfile TIRED =
        new MouseMovementProfile(0.35, 250, 0.4, true, 0.25, 8, 0.6);

    /**
     * Create a custom profile builder for easy customization.
     * Example: MouseMovementProfile.builder().randomness(0.5).build()
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double randomness = 0.25;
        private int baseDelayMs = 120;
        private double variance = 0.25;
        private boolean overshoot = true;
        private double fatigueChance = 0.1;
        private int jitterRadius = 5;
        private double curvature = 0.5;

        public Builder randomness(double val) { this.randomness = val; return this; }
        public Builder baseDelayMs(int val) { this.baseDelayMs = val; return this; }
        public Builder variance(double val) { this.variance = val; return this; }
        public Builder overshoot(boolean val) { this.overshoot = val; return this; }
        public Builder fatigueChance(double val) { this.fatigueChance = val; return this; }
        public Builder jitterRadius(int val) { this.jitterRadius = val; return this; }
        public Builder curvature(double val) { this.curvature = val; return this; }

        public MouseMovementProfile build() {
            return new MouseMovementProfile(randomness, baseDelayMs, variance,
                overshoot, fatigueChance, jitterRadius, curvature);
        }
    }

    public static MouseMovementProfile fromString(String profileName) {
        if (profileName == null) {
            return NORMAL;
        }

        switch (profileName.toUpperCase()) {
            case "FAST":
                return FAST;
            case "CAREFUL":
                return CAREFUL;
            case "TIRED":
                return TIRED;
            default:
                return NORMAL;
        }
    }
}
