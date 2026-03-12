package net.runelite.client.plugins.gamestate;

import lombok.Value;

/**
 * Represents a single equipped item
 */
@Value
public class EquipmentItem {
    String slot;
    int id;
    String name;
    int quantity;
}
