package net.runelite.client.plugins.autofight;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(AutoFightConfig.GROUP)
public interface AutoFightConfig extends Config
{
	String GROUP = "autofight";
	String NPC_IDS_KEY = "npcIds";
	String LOOT_ITEMS_KEY = "lootItems";

	@ConfigSection(name = "Targets", description = "Which NPCs to highlight", position = 0)
	String targetsSection = "targets";

	@ConfigSection(name = "Style", description = "How highlighted NPCs are drawn", position = 1)
	String styleSection = "style";

	@ConfigSection(name = "Colors", description = "Highlight colors", position = 2)
	String colorsSection = "colors";

	@ConfigSection(name = "Tracking & alerts", description = "Kill counter and notifications", position = 3)
	String alertsSection = "alerts";

	@ConfigSection(name = "Automation", description = "Auto-attack and auto-loot (both off by default)", position = 4)
	String automationSection = "automation";

	@ConfigSection(name = "Humanizer", description = "Randomness applied to automated actions", position = 5)
	String humanSection = "human";

	@ConfigSection(name = "Camera", description = "Keep the target on screen", position = 6)
	String cameraSection = "camera";

	// ---- Targets ----

	@ConfigItem(
		keyName = NPC_IDS_KEY,
		name = "NPC IDs",
		description = "Comma-separated NPC IDs to highlight, e.g. 3029, 3031. Long-press an NPC and pick 'AF-Tag' to add it here.",
		section = targetsSection,
		position = 0
	)
	default String npcIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "tagMenuOption",
		name = "Long-press tag option",
		description = "Add 'AF-Tag' / 'AF-Untag' to NPC menus (no Shift key needed)",
		section = targetsSection,
		position = 1
	)
	default boolean tagMenuOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showIdInMenu",
		name = "Show NPC ID in menu",
		description = "Append the NPC ID to the target name in menus, to help you find IDs",
		section = targetsSection,
		position = 2
	)
	default boolean showIdInMenu()
	{
		return false;
	}

	@ConfigItem(
		keyName = "skipOthersFights",
		name = "Skip NPCs fighting others",
		description = "Don't mark an NPC as 'next' if it is already in combat with another player",
		section = targetsSection,
		position = 3
	)
	default boolean skipOthersFights()
	{
		return true;
	}

	// ---- Style ----

	@ConfigItem(keyName = "hull", name = "Hull", description = "Highlight the NPC's clickbox hull", section = styleSection, position = 0)
	default boolean hull()
	{
		return true;
	}

	@ConfigItem(keyName = "tile", name = "Tile", description = "Highlight the tile under the NPC", section = styleSection, position = 1)
	default boolean tile()
	{
		return false;
	}

	@ConfigItem(keyName = "trueTile", name = "True tile", description = "Highlight the NPC's server-side tile", section = styleSection, position = 2)
	default boolean trueTile()
	{
		return false;
	}

	@ConfigItem(keyName = "outline", name = "Outline", description = "Draw a model outline (heavier on phones)", section = styleSection, position = 3)
	default boolean outline()
	{
		return false;
	}

	@ConfigItem(keyName = "name", name = "Name", description = "Draw the NPC's name above it", section = styleSection, position = 4)
	default boolean name()
	{
		return true;
	}

	@ConfigItem(keyName = "minimap", name = "Minimap name", description = "Draw names on the minimap", section = styleSection, position = 5)
	default boolean minimap()
	{
		return false;
	}

	@Range(min = 1, max = 10)
	@ConfigItem(keyName = "borderWidth", name = "Border width", description = "Highlight line thickness", section = styleSection, position = 6)
	default int borderWidth()
	{
		return 2;
	}

	@Range(max = 4)
	@ConfigItem(keyName = "outlineFeather", name = "Outline feather", description = "Outline softness (0-4)", section = styleSection, position = 7)
	default int outlineFeather()
	{
		return 0;
	}

	@ConfigItem(keyName = "hideDead", name = "Hide dying NPCs", description = "Stop highlighting NPCs that are dying", section = styleSection, position = 8)
	default boolean hideDead()
	{
		return true;
	}

	// ---- Colors ----

	@Alpha
	@ConfigItem(keyName = "targetColor", name = "Current target", description = "NPC you're fighting", section = colorsSection, position = 0)
	default Color targetColor()
	{
		return new Color(255, 40, 40);
	}

	@ConfigItem(keyName = "highlightNext", name = "Mark next target", description = "Use a separate color for the nearest free NPC", section = colorsSection, position = 1)
	default boolean highlightNext()
	{
		return true;
	}

	@Alpha
	@ConfigItem(keyName = "nextColor", name = "Next target", description = "Nearest free matching NPC", section = colorsSection, position = 2)
	default Color nextColor()
	{
		return new Color(255, 220, 0);
	}

	@Alpha
	@ConfigItem(keyName = "otherColor", name = "Other matches", description = "All other matching NPCs", section = colorsSection, position = 3)
	default Color otherColor()
	{
		return new Color(0, 255, 255);
	}

	@Alpha
	@ConfigItem(keyName = "fillColor", name = "Fill", description = "Fill color inside the highlight", section = colorsSection, position = 4)
	default Color fillColor()
	{
		return new Color(0, 0, 0, 40);
	}

	// ---- Tracking & alerts ----

	@ConfigItem(keyName = "showCounter", name = "Kill counter", description = "Show kills and kills/hour", section = alertsSection, position = 0)
	default boolean showCounter()
	{
		return true;
	}

	@ConfigItem(keyName = "notifyKill", name = "Notify on kill", description = "Notify when your target dies", section = alertsSection, position = 1)
	default Notification notifyKill()
	{
		return Notification.OFF;
	}

	@ConfigItem(keyName = "notifyIdle", name = "Notify when idle", description = "Notify when you're not fighting a matching NPC", section = alertsSection, position = 2)
	default Notification notifyIdle()
	{
		return Notification.ON;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(keyName = "idleTicks", name = "Idle delay (ticks)", description = "Game ticks out of combat before the idle alert (1 tick = 0.6s)", section = alertsSection, position = 3)
	default int idleTicks()
	{
		return 5;
	}

	@ConfigItem(keyName = "lowHp", name = "Low HP alert", description = "Notify when HP drops to or below this (0 = off)", section = alertsSection, position = 4)
	default int lowHp()
	{
		return 0;
	}

	// ---- Automation ----

	@ConfigItem(
		keyName = "autoAttack",
		name = "Auto-attack",
		description = "When not fighting, invoke 'Attack' on the next free matching NPC",
		section = automationSection,
		position = 0
	)
	default boolean autoAttack()
	{
		return false;
	}

	@ConfigItem(
		keyName = "autoLoot",
		name = "Auto-loot",
		description = "After a counted kill, pick up the items listed below",
		section = automationSection,
		position = 1
	)
	default boolean autoLoot()
	{
		return false;
	}

	@ConfigItem(
		keyName = LOOT_ITEMS_KEY,
		name = "Loot items",
		description = "Comma-separated item IDs or exact names, e.g. 526, Feather. Empty = loot nothing.",
		section = automationSection,
		position = 2
	)
	default String lootItems()
	{
		return "";
	}

	@Range(min = 1, max = 15)
	@ConfigItem(
		keyName = "lootRadius",
		name = "Loot radius",
		description = "Tiles around you to search for loot",
		section = automationSection,
		position = 3
	)
	default int lootRadius()
	{
		return 8;
	}

	// ---- Humanizer ----

	@Range(min = 0, max = 20)
	@ConfigItem(
		keyName = "reactionMin",
		name = "Reaction min (ticks)",
		description = "Shortest pause before an automated click (1 tick = 0.6s)",
		section = humanSection,
		position = 0
	)
	default int reactionMin()
	{
		return 2;
	}

	@Range(min = 0, max = 40)
	@ConfigItem(
		keyName = "reactionMax",
		name = "Reaction max (ticks)",
		description = "Longest pause before an automated click. Clamped to at least the minimum.",
		section = humanSection,
		position = 1
	)
	default int reactionMax()
	{
		return 6;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "hesitateChance",
		name = "Hesitate chance (%)",
		description = "Chance of an extra 1-3 tick pause on top of the reaction delay",
		section = humanSection,
		position = 2
	)
	default int hesitateChance()
	{
		return 15;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "targetPool",
		name = "Target pool",
		description = "Pick at random from the N nearest NPCs instead of always the closest (1 = always closest)",
		section = humanSection,
		position = 3
	)
	default int targetPool()
	{
		return 2;
	}

	// ---- Camera ----

	@ConfigItem(
		keyName = "smartCamera",
		name = "Smart camera",
		description = "Rotate the camera to bring the target back on screen when it drifts out of view",
		section = cameraSection,
		position = 0
	)
	default boolean smartCamera()
	{
		return false;
	}

	@Range(min = 0, max = 40)
	@ConfigItem(
		keyName = "cameraMargin",
		name = "Edge margin (%)",
		description = "How close to the screen edge the target may get before the camera turns",
		section = cameraSection,
		position = 1
	)
	default int cameraMargin()
	{
		return 20;
	}
}
