package net.runelite.client.plugins.eventmonitor;

import lombok.Value;

/**
 * Represents a game event that occurred
 */
@Value
public class GameEvent {
    String type;
    long timestamp;
    GameEventData data;

    public GameEvent(String type, long timestamp, GameEventData data) {
        this.type = type;
        this.timestamp = timestamp;
        this.data = data != null ? data : new GameEventData();
    }
}
