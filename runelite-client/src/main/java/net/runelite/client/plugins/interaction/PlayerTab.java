package net.runelite.client.plugins.interaction;

import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.gameval.InterfaceID;

/**
 * Enum representing player menu tabs across all viewport modes:
 * - Fixed (classic)
 * - Resizable (classic)
 * - Resizable bottom-line (modern)
 *
 * Stone layout (all three modes share the same ordering):
 *   STONE0  = Combat Options
 *   STONE1  = Stats
 *   STONE2  = Quests
 *   STONE3  = Inventory
 *   STONE4  = Worn Equipment
 *   STONE5  = Prayer
 *   STONE6  = Magic
 *   STONE7  = Friends Chat (Clan Chat)
 *   STONE8  = Account Management
 *   STONE9  = Friends List
 *   STONE10 = Logout
 *   STONE11 = Options
 *   STONE12 = Emotes
 *   STONE13 = Music Player
 */
public enum PlayerTab {
	COMBAT(
		WidgetInfo.FIXED_VIEWPORT_COMBAT_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB,
		InterfaceID.ToplevelPreEoc.STONE0
	),
	STATS(
		WidgetInfo.FIXED_VIEWPORT_STATS_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB,
		InterfaceID.ToplevelPreEoc.STONE1
	),
	QUESTS(
		WidgetInfo.FIXED_VIEWPORT_QUESTS_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_QUESTS_TAB,
		InterfaceID.ToplevelPreEoc.STONE2
	),
	INVENTORY(
		WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB,
		InterfaceID.ToplevelPreEoc.STONE3
	),
	EQUIPMENT(
		WidgetInfo.FIXED_VIEWPORT_EQUIPMENT_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB,
		InterfaceID.ToplevelPreEoc.STONE4
	),
	PRAYER(
		WidgetInfo.FIXED_VIEWPORT_PRAYER_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_PRAYER_TAB,
		InterfaceID.ToplevelPreEoc.STONE5
	),
	MAGIC(
		WidgetInfo.FIXED_VIEWPORT_MAGIC_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_MAGIC_TAB,
		InterfaceID.ToplevelPreEoc.STONE6
	),
	FRIENDS_CHAT(
		WidgetInfo.FIXED_VIEWPORT_FRIENDS_CHAT_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_CHAT_TAB,
		InterfaceID.ToplevelPreEoc.STONE7
	),
	ACCOUNT(
		WidgetInfo.FIXED_VIEWPORT_IGNORES_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_IGNORES_TAB,
		InterfaceID.ToplevelPreEoc.STONE8
	),
	FRIENDS(
		WidgetInfo.FIXED_VIEWPORT_FRIENDS_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB,
		InterfaceID.ToplevelPreEoc.STONE9
	),
	LOGOUT(
		WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB,
		InterfaceID.ToplevelPreEoc.STONE10
	),
	OPTIONS(
		WidgetInfo.FIXED_VIEWPORT_OPTIONS_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_OPTIONS_TAB,
		InterfaceID.ToplevelPreEoc.STONE11
	),
	EMOTES(
		WidgetInfo.FIXED_VIEWPORT_EMOTES_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_EMOTES_TAB,
		InterfaceID.ToplevelPreEoc.STONE12
	),
	MUSIC(
		WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB,
		WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB,
		InterfaceID.ToplevelPreEoc.STONE13
	);

	private final WidgetInfo fixedViewport;
	private final WidgetInfo resizableViewport;
	/** Packed widget ID for the bottom-line (modern resizable) tab stone */
	private final int bottomLinePackedId;

	PlayerTab(WidgetInfo fixedViewport, WidgetInfo resizableViewport, int bottomLinePackedId) {
		this.fixedViewport = fixedViewport;
		this.resizableViewport = resizableViewport;
		this.bottomLinePackedId = bottomLinePackedId;
	}

	public WidgetInfo getFixedViewportWidget() {
		return fixedViewport;
	}

	public WidgetInfo getResizableViewportWidget() {
		return resizableViewport;
	}

	/**
	 * Get the packed widget ID for the bottom-line (modern resizable) mode.
	 * Use with client.getWidget(packedId) since not all bottom-line tabs
	 * have named WidgetInfo constants.
	 */
	public int getBottomLinePackedId() {
		return bottomLinePackedId;
	}

	/**
	 * Parse a tab name from a string, case-insensitive.
	 * Supports aliases like "WORN_EQUIPMENT" for EQUIPMENT.
	 */
	public static PlayerTab fromString(String name) {
		if (name == null) {
			return null;
		}

		switch (name.toUpperCase().replace(" ", "_").replace("-", "_")) {
			case "COMBAT":
			case "COMBAT_OPTIONS":
				return COMBAT;
			case "STATS":
			case "SKILLS":
				return STATS;
			case "QUESTS":
			case "QUEST":
			case "QUEST_LIST":
				return QUESTS;
			case "INVENTORY":
				return INVENTORY;
			case "EQUIPMENT":
			case "WORN_EQUIPMENT":
			case "WORN":
				return EQUIPMENT;
			case "PRAYER":
			case "PRAYERS":
				return PRAYER;
			case "MAGIC":
			case "SPELLBOOK":
				return MAGIC;
			case "FRIENDS_CHAT":
			case "CLAN_CHAT":
			case "CLAN":
				return FRIENDS_CHAT;
			case "ACCOUNT":
			case "ACCOUNT_MANAGEMENT":
			case "IGNORES":
				return ACCOUNT;
			case "FRIENDS":
			case "FRIENDS_LIST":
				return FRIENDS;
			case "LOGOUT":
				return LOGOUT;
			case "OPTIONS":
			case "SETTINGS":
				return OPTIONS;
			case "EMOTES":
			case "EMOTE":
				return EMOTES;
			case "MUSIC":
			case "MUSIC_PLAYER":
				return MUSIC;
			default:
				return null;
		}
	}
}
