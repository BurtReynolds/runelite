package com.custom.gamestate;

import lombok.Value;
import java.util.List;

/**
 * Represents the current inventory state
 */
@Value
public class InventoryState {
    List<InventoryItem> items;
    long timestamp;

    public InventoryState(List<InventoryItem> items) {
        this.items = items;
        this.timestamp = System.currentTimeMillis();
    }

    public int getItemCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
