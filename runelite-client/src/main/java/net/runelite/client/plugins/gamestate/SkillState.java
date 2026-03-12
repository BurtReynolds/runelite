package net.runelite.client.plugins.gamestate;

import lombok.Value;

/**
 * Represents a single skill's current state
 */
@Value
public class SkillState {
    String name;
    int level;
    int boostedLevel;
    int xp;
}
