package net.runelite.client.plugins.interaction;

import net.runelite.api.widgets.WidgetInfo;

/**
 * Enum representing player menu tabs across all viewport modes
 */
public enum PlayerTab {
    COMBAT(
        WidgetInfo.FIXED_VIEWPORT_COMBAT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB  // No bottom-line variant
    ),
    STATS(
        WidgetInfo.FIXED_VIEWPORT_STATS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB
    ),
    QUESTS(
        WidgetInfo.FIXED_VIEWPORT_QUESTS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_QUESTS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_QUESTS_TAB
    ),
    INVENTORY(
        WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_TAB
    ),
    EQUIPMENT(
        WidgetInfo.FIXED_VIEWPORT_EQUIPMENT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB
    ),
    PRAYER(
        WidgetInfo.FIXED_VIEWPORT_PRAYER_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_PRAYER_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_PRAYER_TAB
    ),
    MAGIC(
        WidgetInfo.FIXED_VIEWPORT_MAGIC_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_MAGIC_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_MAGIC_TAB
    ),
    FRIENDS(
        WidgetInfo.FIXED_VIEWPORT_FRIENDS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB
    ),
    LOGOUT(
        WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB
    ),
    OPTIONS(
        WidgetInfo.FIXED_VIEWPORT_OPTIONS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_OPTIONS_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_OPTIONS_TAB
    ),
    EMOTES(
        WidgetInfo.FIXED_VIEWPORT_EMOTES_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_EMOTES_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_EMOTES_TAB
    ),
    MUSIC(
        WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB,
        WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB
    );

    private final WidgetInfo fixedViewport;
    private final WidgetInfo resizableViewport;
    private final WidgetInfo resizableBottomLine;

    PlayerTab(WidgetInfo fixedViewport, WidgetInfo resizableViewport, WidgetInfo resizableBottomLine) {
        this.fixedViewport = fixedViewport;
        this.resizableViewport = resizableViewport;
        this.resizableBottomLine = resizableBottomLine;
    }

    public WidgetInfo getFixedViewportWidget() {
        return fixedViewport;
    }

    public WidgetInfo getResizableViewportWidget() {
        return resizableViewport;
    }

    public WidgetInfo getResizableBottomLineWidget() {
        return resizableBottomLine;
    }

    /**
     * @deprecated Use getFixedViewportWidget(), getResizableViewportWidget(), or getResizableBottomLineWidget() instead
     */
    @Deprecated
    public WidgetInfo getWidgetInfo() {
        return fixedViewport;
    }
}
