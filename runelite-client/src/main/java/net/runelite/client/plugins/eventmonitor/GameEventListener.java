package net.runelite.client.plugins.eventmonitor;

/**
 * Interface for listening to game events
 */
public interface GameEventListener {
    void onEvent(GameEvent event);
}
