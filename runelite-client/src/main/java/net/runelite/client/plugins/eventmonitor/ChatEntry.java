package net.runelite.client.plugins.eventmonitor;

import lombok.Value;

/**
 * Represents a single chat message entry
 */
@Value
public class ChatEntry {
    String type;
    String sender;
    String message;
    long timestamp;
}
