package net.runelite.client.plugins.automine;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(AutoMineConfig.GROUP)
public interface AutoMineConfig extends Config
{
        String GROUP = "automine";
        String ROCK_KEY = "rock";
        String KEEP_ITEMS_KEY = "keepItems";
        String CENTER_KEY = "centerTile";

        @ConfigSection(name = "Rocks", description = "Which rocks to mine", position = 0)
        String rocksSection = "rocks";

        @ConfigSection(name = "Full inventory", description = "What to do when the inventory fills up", position = 1)
        String fullSection = "full";

        @ConfigSection(name = "Style", description = "How rocks are drawn", position = 2)
        String styleSection = "style";

        @ConfigSection(name = "Colors", description = "Rock highlight colors", position = 3)
        String colorsSection = "colors";

        @ConfigSection(name = "Tracking & alerts", description = "Ore counter and notifications", position = 4)
        String alertsSection = "alerts";

        @ConfigSection(name = "Automation", description = "Auto-mining (off by default)", position = 5)
        String automationSection = "automation";

        @ConfigSection(name = "Area", description = "The area around the centre tile that is searched for rocks", position = 8)
        String huntSection = "hunt";

        enum FullInventoryMode
        {
                NOTIFY,
                DROP,
                BANK
        }

        // ---- Rocks ----

        @ConfigItem(
                keyName = ROCK_KEY,
                name = "Rock",
                description = "Rock type to mine. Rocks are recognised by name and mine action, so depleted rocks are ignored "
                        + "automatically. The panel lists known spots for the chosen rock.",
                section = rocksSection,
                position = 0
        )
        default MiningRock rock()
        {
                return MiningRock.COPPER;
        }

        // ---- Full inventory ----

        @ConfigItem(
                keyName = "fullInvMode",
                name = "When the inventory is full",
                description = "Notify: stop and notify. Drop: shift-drop everything except the keep list along a fresh "
                        + "sine wave. Bank: walk to the nearest reachable bank along a real path "
                        + "(doors, stairs, ladders), deposit everything except the keep list, then walk back to the area centre.",
                section = fullSection,
                position = 0
        )
        default FullInventoryMode fullInvMode()
        {
                return FullInventoryMode.BANK;
        }

        @ConfigItem(
                keyName = KEEP_ITEMS_KEY,
                name = "Keep items",
                description = "Comma-separated item IDs or names that are never dropped or deposited. A name matches any "
                        + "item containing it, so 'pickaxe' keeps every pickaxe.",
                section = fullSection,
                position = 1
        )
        default String keepItems()
        {
                return "pickaxe";
        }

        @ConfigItem(
                keyName = "shiftDrop",
                name = "Shift-drop",
                description = "In Drop mode, hold shift and tap each item once instead of long-press + 'Drop'. Needs the "
                        + "in-game setting 'Shift click to drop items'.",
                section = fullSection,
                position = 2
        )
        default boolean shiftDrop()
        {
                return true;
        }

        @ConfigItem(
                keyName = "bankPick",
                name = "Bank",
                description = "Which bank to use. 'Same as Humanizer default' uses the one bank set in the Humanizer "
                        + "settings (shared by all scripts); 'Closest bank' picks the nearest usable one within the max "
                        + "distance. A named bank is used at any distance.",
                section = fullSection,
                position = 3
        )
        default net.runelite.client.plugins.gamedata.BankPick bankPick()
        {
                return net.runelite.client.plugins.gamedata.BankPick.SHARED;
        }

        @Range(min = 10, max = 250)
        @ConfigItem(
                keyName = "bankMaxDistance",
                name = "Max bank distance (tiles)",
                description = "Banks further than this (straight line) are not considered. The walk itself follows a "
                        + "real path through doors, stairs and ladders (the same map data Microbot's web walker uses), "
                        + "so an upstairs bank like Lumbridge works.",
                section = fullSection,
                position = 3
        )
        default int bankMaxDistance()
        {
                return 100;
        }

        @ConfigItem(
                keyName = "notifyFull",
                name = "Notify when full",
                description = "Notify when the inventory is full in Notify mode, or when no bank can be reached",
                section = fullSection,
                position = 4
        )
        default Notification notifyFull()
        {
                return Notification.ON;
        }

        // ---- Style ----

        @ConfigItem(keyName = "hull", name = "Hull", description = "Highlight the rock's clickbox hull", section = styleSection, position = 0)
        default boolean hull()
        {
                return true;
        }

        @ConfigItem(keyName = "tile", name = "Tile", description = "Highlight the tile under the rock", section = styleSection, position = 1)
        default boolean tile()
        {
                return true;
        }

        @ConfigItem(keyName = "trueTile", name = "True tile", description = "Highlight the rock's tile", section = styleSection, position = 2)
        default boolean trueTile()
        {
                return false;
        }

        @ConfigItem(keyName = "outline", name = "Outline", description = "Draw a model outline (heavier on phones)", section = styleSection, position = 3)
        default boolean outline()
        {
                return false;
        }

        @ConfigItem(keyName = "name", name = "Name", description = "Draw the rock's name above it", section = styleSection, position = 4)
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

        // ---- Colors ----

        @Alpha
        @ConfigItem(keyName = "currentColor", name = "Current rock", description = "Rock you are mining", section = colorsSection, position = 0)
        default Color currentColor()
        {
                return new Color(255, 40, 40);
        }

        @ConfigItem(keyName = "highlightNext", name = "Mark next rock", description = "Use a separate color for the next rock to be clicked", section = colorsSection, position = 1)
        default boolean highlightNext()
        {
                return true;
        }

        @Alpha
        @ConfigItem(keyName = "nextColor", name = "Next rock", description = "Rock that will be clicked next", section = colorsSection, position = 2)
        default Color nextColor()
        {
                return new Color(255, 220, 0);
        }

        @Alpha
        @ConfigItem(keyName = "otherColor", name = "Other rocks", description = "All other matching rocks", section = colorsSection, position = 3)
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

        @ConfigItem(keyName = "showCounter", name = "Ore counter", description = "Show ores and ores/hour", section = alertsSection, position = 0)
        default boolean showCounter()
        {
                return true;
        }

        @ConfigItem(keyName = "notifyOre", name = "Notify on ore", description = "Notify on every ore (counted from chat messages)", section = alertsSection, position = 1)
        default Notification notifyOre()
        {
                return Notification.OFF;
        }

        @ConfigItem(keyName = "notifyIdle", name = "Notify when idle", description = "Notify when you are not mining", section = alertsSection, position = 2)
        default Notification notifyIdle()
        {
                return Notification.ON;
        }

        @Range(min = 1, max = 100)
        @ConfigItem(keyName = "idleTicks", name = "Idle delay (ticks)", description = "Game ticks without mining before the idle alert (1 tick = 0.6s)", section = alertsSection, position = 3)
        default int idleTicks()
        {
                return 5;
        }

        // ---- Automation ----

        @ConfigItem(
                keyName = "autoMine",
                name = "Auto-mine",
                description = "Tap the next rock with simulated touch input. How the input looks (reactions, misclicks, "
                        + "input styles, camera, walking) is set in the Humanizer plugin and shared by every script.",
                section = automationSection,
                position = 0
        )
        default boolean autoMine()
        {
                return false;
        }

        @Range(min = 1, max = 5)
        @ConfigItem(
                keyName = "rockPool",
                name = "Rock pool",
                description = "Pick at random from the N nearest rocks instead of always the closest (1 = always closest)",
                section = automationSection,
                position = 1
        )
        default int rockPool()
        {
                return 2;
        }

        // ---- Area ----

        @ConfigItem(
                keyName = "useHuntRange",
                name = "Limit to area",
                description = "Only use rocks inside a square around the centre tile. Type 6 in the radius and a 13x13 "
                        + "square around the centre is searched. When banking, the plugin walks back to this centre.",
                section = huntSection,
                position = 0
        )
        default boolean useHuntRange()
        {
                return true;
        }

        @Range(min = 1, max = 32)
        @ConfigItem(
                keyName = "huntRadius",
                name = "Area radius (tiles)",
                description = "Tiles out from the centre tile. 6 gives a 13x13 square. With no centre marked, the "
                        + "square follows you.",
                section = huntSection,
                position = 1
        )
        default int huntRadius()
        {
                return 6;
        }

        @ConfigItem(
                keyName = CENTER_KEY,
                name = "Area centre tile",
                description = "x,y,plane of the area centre. Leave empty to centre on yourself, or right-click the "
                        + "ground (long-press) and pick 'WC-Set area centre' to mark it. 'WC-Clear area centre' removes it.",
                section = huntSection,
                position = 2
        )
        default String centerTile()
        {
                return "";
        }
}
