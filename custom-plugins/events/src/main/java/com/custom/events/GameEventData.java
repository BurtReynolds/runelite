package com.custom.events;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds arbitrary data for a game event
 */
public class GameEventData extends HashMap<String, Object> {

    public GameEventData() {
        super();
    }

    public GameEventData(Map<String, Object> data) {
        super(data);
    }

    public void put(String key, int value) {
        put(key, (Object) value);
    }

    public void put(String key, String value) {
        put(key, (Object) value);
    }

    public void put(String key, boolean value) {
        put(key, (Object) value);
    }

    public void put(String key, long value) {
        put(key, (Object) value);
    }

    public void put(String key, double value) {
        put(key, (Object) value);
    }
}
