package com.custom.gamestate;

import lombok.Value;

/**
 * Represents a single item in the inventory
 */
@Value
public class InventoryItem {
    int slot;
    int id;
    String name;
    int quantity;
    boolean noted;
    boolean stackable;
}
