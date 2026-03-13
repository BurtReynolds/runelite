package net.runelite.client.plugins.interaction;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class BreakHandler {

	public enum State {
		DISABLED,
		PLAYING,
		BREAK_PENDING,
		ON_BREAK,
		RESUMING
	}

	private final InteractionPlugin interaction;
	private final AtomicBoolean enabled = new AtomicBoolean(false);
	private Thread backgroundThread;

	// Configuration
	private volatile int minBreakMs = 120_000;   // 2 min
	private volatile int maxBreakMs = 600_000;   // 10 min
	private volatile int minPlayMs = 1_800_000;  // 30 min
	private volatile int maxPlayMs = 3_600_000;  // 60 min
	private volatile boolean logoutDuringBreak = false;

	// State
	private volatile State state = State.DISABLED;
	private volatile long playStartTime;
	private volatile long breakStartTime;
	private volatile long nextBreakAt;
	private volatile long currentBreakDuration;
	private volatile boolean forceBreakNow = false;
	private volatile boolean skipCurrentBreak = false;

	// Stats
	private volatile long totalPlayTimeMs;
	private volatile long totalBreakTimeMs;
	private volatile int breakCount;

	public BreakHandler(InteractionPlugin interaction) {
		this.interaction = interaction;
	}

	public void start(int minBreakMs, int maxBreakMs, int minPlayMs, int maxPlayMs, boolean logoutDuringBreak) {
		if (enabled.get()) {
			log.info("Break handler already running, stopping first");
			stop();
		}

		this.minBreakMs = minBreakMs;
		this.maxBreakMs = maxBreakMs;
		this.minPlayMs = minPlayMs;
		this.maxPlayMs = maxPlayMs;
		this.logoutDuringBreak = logoutDuringBreak;

		totalPlayTimeMs = 0;
		totalBreakTimeMs = 0;
		breakCount = 0;
		forceBreakNow = false;
		skipCurrentBreak = false;

		enabled.set(true);
		state = State.PLAYING;
		playStartTime = System.currentTimeMillis();
		scheduleNextBreak();

		backgroundThread = new Thread(this::runLoop, "BreakHandler");
		backgroundThread.setDaemon(true);
		backgroundThread.start();

		log.info("Break handler started: play={}ms-{}ms, break={}ms-{}ms, logout={}",
			minPlayMs, maxPlayMs, minBreakMs, maxBreakMs, logoutDuringBreak);
	}

	public void stop() {
		enabled.set(false);
		state = State.DISABLED;
		if (backgroundThread != null) {
			backgroundThread.interrupt();
			backgroundThread = null;
		}
		log.info("Break handler stopped");
	}

	public boolean isEnabled() {
		return enabled.get();
	}

	public State getState() {
		return state;
	}

	/**
	 * Check if we should be paused (on break).
	 * Scripts/TaskSequencer should call this periodically.
	 */
	public boolean shouldPause() {
		return enabled.get() && (state == State.ON_BREAK || state == State.BREAK_PENDING);
	}

	/**
	 * Force an immediate break.
	 */
	public void triggerBreakNow() {
		if (!enabled.get()) return;
		forceBreakNow = true;
		log.info("Break forced — will begin on next check");
	}

	/**
	 * Skip the current or pending break.
	 */
	public void skipBreak() {
		if (state == State.ON_BREAK || state == State.BREAK_PENDING) {
			skipCurrentBreak = true;
			log.info("Break skip requested");
		}
	}

	public Map<String, Object> getStatus() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("enabled", enabled.get());
		status.put("state", state.name());
		status.put("logoutDuringBreak", logoutDuringBreak);
		status.put("minBreakMs", minBreakMs);
		status.put("maxBreakMs", maxBreakMs);
		status.put("minPlayMs", minPlayMs);
		status.put("maxPlayMs", maxPlayMs);
		status.put("totalPlayTimeMs", totalPlayTimeMs);
		status.put("totalBreakTimeMs", totalBreakTimeMs);
		status.put("breakCount", breakCount);
		status.put("nextBreakAt", nextBreakAt);
		status.put("currentTime", System.currentTimeMillis());

		if (state == State.PLAYING) {
			long timeUntilBreak = nextBreakAt - System.currentTimeMillis();
			status.put("timeUntilBreakMs", Math.max(0, timeUntilBreak));
		} else if (state == State.ON_BREAK) {
			long breakRemaining = (breakStartTime + currentBreakDuration) - System.currentTimeMillis();
			status.put("breakRemainingMs", Math.max(0, breakRemaining));
			status.put("currentBreakDurationMs", currentBreakDuration);
		}

		return status;
	}

	private void scheduleNextBreak() {
		Random random = new Random();
		int playDuration = minPlayMs + random.nextInt(Math.max(1, maxPlayMs - minPlayMs));
		nextBreakAt = System.currentTimeMillis() + playDuration;
		log.info("Next break scheduled in {}ms (at {})", playDuration, nextBreakAt);
	}

	private void runLoop() {
		while (enabled.get()) {
			try {
				Thread.sleep(1000); // Check every second

				if (!enabled.get()) break;

				switch (state) {
					case PLAYING:
						handlePlayingState();
						break;
					case BREAK_PENDING:
						handleBreakPendingState();
						break;
					case ON_BREAK:
						handleOnBreakState();
						break;
					case RESUMING:
						handleResumingState();
						break;
					default:
						break;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.warn("Break handler error", e);
			}
		}

		state = State.DISABLED;
		log.info("Break handler loop exited");
	}

	private void handlePlayingState() {
		long now = System.currentTimeMillis();
		totalPlayTimeMs += 1000; // Approximate — we sleep 1s per iteration

		if (forceBreakNow || now >= nextBreakAt) {
			forceBreakNow = false;
			state = State.BREAK_PENDING;
			log.info("Break time — transitioning to BREAK_PENDING");
		}
	}

	private void handleBreakPendingState() {
		if (skipCurrentBreak) {
			skipCurrentBreak = false;
			state = State.PLAYING;
			scheduleNextBreak();
			log.info("Break skipped — resuming play");
			return;
		}

		// Start the break
		Random random = new Random();
		currentBreakDuration = minBreakMs + random.nextInt(Math.max(1, maxBreakMs - minBreakMs));
		breakStartTime = System.currentTimeMillis();
		state = State.ON_BREAK;
		breakCount++;

		log.info("Break #{} starting — duration={}ms, logout={}", breakCount, currentBreakDuration, logoutDuringBreak);

		if (logoutDuringBreak) {
			try {
				interaction.openPlayerTab(PlayerTab.LOGOUT, MouseMovementProfile.NORMAL);
				sleep(300 + (int)(Math.random() * 200));
				// Click the logout button
				interaction.clickWidgetByPackedId(
					net.runelite.api.gameval.InterfaceID.Logout.LOGOUT,
					MouseMovementProfile.NORMAL
				);
				log.info("Logged out for break");
			} catch (Exception e) {
				log.warn("Failed to logout for break", e);
			}
		}
	}

	private void handleOnBreakState() {
		totalBreakTimeMs += 1000;

		if (skipCurrentBreak) {
			skipCurrentBreak = false;
			state = State.RESUMING;
			log.info("Break skipped early — resuming");
			return;
		}

		long elapsed = System.currentTimeMillis() - breakStartTime;
		if (elapsed >= currentBreakDuration) {
			state = State.RESUMING;
			log.info("Break #{} complete after {}ms — resuming", breakCount, elapsed);
		}
	}

	private void handleResumingState() {
		// If we logged out, we'd need to log back in here
		// For now, just transition back to playing — scripts handle re-login
		state = State.PLAYING;
		playStartTime = System.currentTimeMillis();
		scheduleNextBreak();
		log.info("Resumed playing — next break at {}", nextBreakAt);
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
