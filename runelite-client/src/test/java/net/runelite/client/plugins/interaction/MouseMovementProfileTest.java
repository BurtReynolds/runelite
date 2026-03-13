package net.runelite.client.plugins.interaction;

import org.junit.Test;

import static org.junit.Assert.*;

public class MouseMovementProfileTest {

	@Test
	public void testFromStringDirect() {
		assertEquals(MouseMovementProfile.FAST, MouseMovementProfile.fromString("FAST"));
		assertEquals(MouseMovementProfile.NORMAL, MouseMovementProfile.fromString("NORMAL"));
		assertEquals(MouseMovementProfile.CAREFUL, MouseMovementProfile.fromString("CAREFUL"));
		assertEquals(MouseMovementProfile.TIRED, MouseMovementProfile.fromString("TIRED"));
	}

	@Test
	public void testFromStringCaseInsensitive() {
		assertEquals(MouseMovementProfile.FAST, MouseMovementProfile.fromString("fast"));
		assertEquals(MouseMovementProfile.NORMAL, MouseMovementProfile.fromString("Normal"));
		assertEquals(MouseMovementProfile.TIRED, MouseMovementProfile.fromString("TIRED"));
	}

	@Test
	public void testFromStringNull() {
		// Should default to NORMAL or throw — test actual behavior
		MouseMovementProfile result = MouseMovementProfile.fromString(null);
		assertNotNull(result);
	}

	@Test
	public void testFromStringInvalid() {
		// Should default to NORMAL on invalid input
		MouseMovementProfile result = MouseMovementProfile.fromString("NONEXISTENT");
		assertNotNull(result);
	}

	@Test
	public void testAllPresetsExist() {
		assertNotNull(MouseMovementProfile.FAST);
		assertNotNull(MouseMovementProfile.NORMAL);
		assertNotNull(MouseMovementProfile.CAREFUL);
		assertNotNull(MouseMovementProfile.TIRED);
	}

	@Test
	public void testBuilder() {
		MouseMovementProfile profile = MouseMovementProfile.builder()
			.randomness(0.5)
			.baseDelayMs(200)
			.variance(0.3)
			.overshoot(false)
			.build();
		assertEquals(0.5, profile.randomness, 0.001);
		assertEquals(200, profile.baseDelayMs);
		assertFalse(profile.overshoot);
	}

	@Test
	public void testClampingBounds() {
		MouseMovementProfile profile = new MouseMovementProfile(2.0, -5, 3.0, false, -1.0, -10, 5.0);
		assertEquals(1.0, profile.randomness, 0.001);
		assertEquals(1, profile.baseDelayMs);
		assertEquals(1.0, profile.variance, 0.001);
		assertEquals(0.0, profile.fatigueChance, 0.001);
		assertEquals(0, profile.jitterRadius);
		assertEquals(1.0, profile.curvature, 0.001);
	}
}
