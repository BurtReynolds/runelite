package net.runelite.client.plugins.interaction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Map;

import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class BreakHandlerTest {

	@Mock
	private InteractionPlugin interactionPlugin;

	private BreakHandler handler;

	@Before
	public void setUp() {
		handler = new BreakHandler(interactionPlugin);
	}

	@Test
	public void testInitialStateIsDisabled() {
		assertEquals(BreakHandler.State.DISABLED, handler.getState());
		assertFalse(handler.isEnabled());
		assertFalse(handler.shouldPause());
	}

	@Test
	public void testStatusWhenDisabled() {
		Map<String, Object> status = handler.getStatus();
		assertEquals(false, status.get("enabled"));
		assertEquals("DISABLED", status.get("state"));
		assertEquals(0, status.get("breakCount"));
		assertEquals(0L, status.get("totalPlayTimeMs"));
		assertEquals(0L, status.get("totalBreakTimeMs"));
	}

	@Test
	public void testStartTransitionsToPlaying() throws InterruptedException {
		handler.start(1000, 2000, 5000, 10000, false);
		Thread.sleep(100); // Let background thread start

		assertTrue(handler.isEnabled());
		assertEquals(BreakHandler.State.PLAYING, handler.getState());
		assertFalse(handler.shouldPause());
	}

	@Test
	public void testStatusWhenPlaying() throws InterruptedException {
		handler.start(1000, 2000, 5000, 10000, false);
		Thread.sleep(100);

		Map<String, Object> status = handler.getStatus();
		assertEquals(true, status.get("enabled"));
		assertEquals("PLAYING", status.get("state"));
		assertNotNull(status.get("nextBreakAt"));
		assertNotNull(status.get("timeUntilBreakMs"));
	}

	@Test
	public void testStopTransitionsToDisabled() throws InterruptedException {
		handler.start(1000, 2000, 5000, 10000, false);
		Thread.sleep(100);
		assertTrue(handler.isEnabled());

		handler.stop();
		Thread.sleep(100);

		assertFalse(handler.isEnabled());
		assertEquals(BreakHandler.State.DISABLED, handler.getState());
	}

	@Test
	public void testTriggerBreakNow() throws InterruptedException {
		handler.start(1000, 2000, 600000, 1200000, false); // Long play time
		Thread.sleep(100);

		handler.triggerBreakNow();
		Thread.sleep(1500); // Wait for state transition

		// Should be either BREAK_PENDING or ON_BREAK
		BreakHandler.State state = handler.getState();
		assertTrue("Expected BREAK_PENDING or ON_BREAK, got " + state,
			state == BreakHandler.State.BREAK_PENDING ||
			state == BreakHandler.State.ON_BREAK);
		assertTrue(handler.shouldPause());
	}

	@Test
	public void testSkipBreak() throws InterruptedException {
		handler.start(1000, 2000, 600000, 1200000, false);
		Thread.sleep(100);

		handler.triggerBreakNow();
		Thread.sleep(1500); // Wait for BREAK_PENDING/ON_BREAK

		handler.skipBreak();
		Thread.sleep(1500); // Wait for state transition

		// Should be back to PLAYING
		assertEquals(BreakHandler.State.PLAYING, handler.getState());
		assertFalse(handler.shouldPause());
	}

	@Test
	public void testShouldPauseFalseWhenDisabled() {
		assertFalse(handler.shouldPause());
	}

	@Test
	public void testShouldPauseFalseWhenPlaying() throws InterruptedException {
		handler.start(1000, 2000, 600000, 1200000, false);
		Thread.sleep(100);

		assertFalse(handler.shouldPause());
	}

	@Test
	public void testStartStopStart() throws InterruptedException {
		handler.start(1000, 2000, 5000, 10000, false);
		Thread.sleep(100);
		assertTrue(handler.isEnabled());

		handler.stop();
		Thread.sleep(100);
		assertFalse(handler.isEnabled());

		handler.start(2000, 3000, 6000, 12000, true);
		Thread.sleep(100);
		assertTrue(handler.isEnabled());
		assertEquals(BreakHandler.State.PLAYING, handler.getState());
	}

	@Test
	public void testConfigValues() throws InterruptedException {
		handler.start(120000, 300000, 1800000, 3600000, true);
		Thread.sleep(100);

		Map<String, Object> status = handler.getStatus();
		assertEquals(120000, status.get("minBreakMs"));
		assertEquals(300000, status.get("maxBreakMs"));
		assertEquals(1800000, status.get("minPlayMs"));
		assertEquals(3600000, status.get("maxPlayMs"));
		assertEquals(true, status.get("logoutDuringBreak"));
	}

	@Test
	public void testCleanup() throws InterruptedException {
		handler.start(1000, 2000, 5000, 10000, false);
		Thread.sleep(100);
		handler.stop();
		Thread.sleep(100);

		// Should be safe to call stop multiple times
		handler.stop();
		assertEquals(BreakHandler.State.DISABLED, handler.getState());
	}
}
