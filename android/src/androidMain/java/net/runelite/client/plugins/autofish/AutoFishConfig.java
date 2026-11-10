package net.runelite.client.plugins.autofish;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(AutoFishConfig.GROUP)
public interface AutoFishConfig extends Config
{
        String GROUP = "autofish";
        String SPOT_IDS_KEY = "spotNpcIds";
        String FISHING_ACTIONS_KEY = "fishingActions";
        String ACTION_CHOICE_KEY = "fishingActionChoice";
        String DROP_ITEMS_KEY = "dropItems";
        String KEEP_ITEMS_KEY = "keepItems";
        String CENTER_KEY = "centerTile";

        @ConfigSection(name = "Spots", description = "Which NPCs count as fishing spots", position = 0)
        String spotsSection = "spots";

        @ConfigSection(name = "Full inventory", description = "What to do when the inventory fills up", position = 1)
        String fullSection = "full";

        @ConfigSection(name = "Style", description = "How fishing spots are drawn", position = 2)
        String styleSection = "style";

        @ConfigSection(name = "Colors", description = "Spot highlight colors", position = 3)
        String colorsSection = "colors";

        @ConfigSection(name = "Tracking & alerts", description = "Catch counter and notifications", position = 4)
        String alertsSection = "alerts";

        @ConfigSection(name = "Automation", description = "Auto-fishing and auto-dropping (both off by default)", position = 5)
        String automationSection = "automation";

        @ConfigSection(name = "Area", description = "The area around the centre tile that is searched for spots", position = 8)
        String huntSection = "hunt";

        enum FullInventoryMode
        {
                OFF,
                DROP_LISTED,
                DROP_ALL_BUT_KEPT
        }

        // ---- Spots ----

        @ConfigItem(
                keyName = SPOT_IDS_KEY,
                name = "Extra spot IDs",
                description = "Comma-separated fishing spot NPC IDs. Spots are found automatically by their fishing "
                        + "actions (Lure, Bait, Net, ...), so this is rarely needed; entries here are always accepted "
                        + "no matter what their actions or name are.",
                section = spotsSection,
                position = 0
        )
        default String spotNpcIds()
        {
                return "";
        }

        @ConfigItem(
                keyName = FISHING_ACTIONS_KEY,
                name = "Fishing actions",
                description = "Comma-separated menu actions that mark an NPC as a fishing spot.",
                section = spotsSection,
                position = 1
        )
        default String fishingActions()
        {
                return "Lure,Bait,Net,Cage,Harpoon,Small Net,Fish";
        }

        @ConfigItem(
                keyName = ACTION_CHOICE_KEY,
                name = "Fishing action",
                description = "Leave empty to use the spot's first fishing action (usually its left-click option). "
                        + "Set e.g. Bait or Lure to always pick that action; when it is not the left-click option the "
                        + "engine long-presses and picks it from the menu, exactly like a player would.",
                section = spotsSection,
                position = 2
        )
        default String fishingActionChoice()
        {
                return "";
        }

        @ConfigItem(
                keyName = "requireSpotName",
                name = "Spot name check",
                description = "Auto-detected spots must be named 'Fishing spot'. Extra IDs above are always accepted.",
                section = spotsSection,
                position = 3
        )
        default boolean requireSpotName()
        {
                return true;
        }

        // ---- Full inventory ----

        @ConfigItem(
                keyName = "fullInvMode",
                name = "When the inventory is full",
                description = "Notify only: stop fishing and notify. Drop listed items: long-press the items listed "
                        + "below and pick 'Drop' with the same simulated finger. Drop everything except keep list: drop "
                        + "every item that is not in the keep list (put your rod/net there).",
                section = fullSection,
                position = 0
        )
        default FullInventoryMode fullInvMode()
        {
                return FullInventoryMode.OFF;
        }

        @ConfigItem(
                keyName = DROP_ITEMS_KEY,
                name = "Drop items",
                description = "Comma-separated item IDs or exact names to drop, e.g. 317, Raw shrimps. Used in "
                        + "'Drop listed items' mode.",
                section = fullSection,
                position = 1
        )
        default String dropItems()
        {
                return "";
        }

        @ConfigItem(
                keyName = KEEP_ITEMS_KEY,
                name = "Keep items",
                description = "Comma-separated item IDs or exact names to keep, e.g. Fly fishing rod, Feather. "
                        + "Everything else is dropped. Used in 'Drop everything except keep list' mode.",
                section = fullSection,
                position = 2
        )
        default String keepItems()
        {
                return "";
        }

        @ConfigItem(
                keyName = "notifyFull",
                name = "Notify when full",
                description = "Notify when the inventory is full (and, when dropping is on, when nothing is droppable)",
                section = fullSection,
                position = 3
        )
        default Notification notifyFull()
        {
                return Notification.ON;
        }

        @ConfigItem(
                keyName = "shiftDrop",
                name = "Shift-drop",
                description = "Hold shift and tap each item once instead of long-press + 'Drop'. Much faster; items "
                        + "are tapped along a fresh sine wave through the bag every pass. Needs the in-game setting "
                        + "'Shift click to drop items' turned on. Turn off to fall back to long-press dropping.",
                section = fullSection,
                position = 4
        )
        default boolean shiftDrop()
        {
                return true;
        }

        // ---- Style ----

        @ConfigItem(keyName = "hull", name = "Hull", description = "Highlight the spot's clickbox hull", section = styleSection, position = 0)
        default boolean hull()
        {
                return true;
        }

        @ConfigItem(keyName = "tile", name = "Tile", description = "Highlight the tile under the spot", section = styleSection, position = 1)
        default boolean tile()
        {
                return true;
        }

        @ConfigItem(keyName = "trueTile", name = "True tile", description = "Highlight the spot's server-side tile", section = styleSection, position = 2)
        default boolean trueTile()
        {
                return false;
        }

        @ConfigItem(keyName = "outline", name = "Outline", description = "Draw a model outline (heavier on phones)", section = styleSection, position = 3)
        default boolean outline()
        {
                return false;
        }

        @ConfigItem(keyName = "name", name = "Name", description = "Draw the spot's name above it", section = styleSection, position = 4)
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
        @ConfigItem(keyName = "currentColor", name = "Current spot", description = "Spot you're fishing at", section = colorsSection, position = 0)
        default Color currentColor()
        {
                return new Color(255, 40, 40);
        }

        @ConfigItem(keyName = "highlightNext", name = "Mark next spot", description = "Use a separate color for the next spot to be clicked", section = colorsSection, position = 1)
        default boolean highlightNext()
        {
                return true;
        }

        @Alpha
        @ConfigItem(keyName = "nextColor", name = "Next spot", description = "Nearest matching spot", section = colorsSection, position = 2)
        default Color nextColor()
        {
                return new Color(255, 220, 0);
        }

        @Alpha
        @ConfigItem(keyName = "otherColor", name = "Other spots", description = "All other matching spots", section = colorsSection, position = 3)
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

        @ConfigItem(keyName = "showCounter", name = "Catch counter", description = "Show catches and catches/hour", section = alertsSection, position = 0)
        default boolean showCounter()
        {
                return true;
        }

        @ConfigItem(keyName = "notifyCatch", name = "Notify on catch", description = "Notify on every caught fish (counted from chat messages)", section = alertsSection, position = 1)
        default Notification notifyCatch()
        {
                return Notification.OFF;
        }

        @ConfigItem(keyName = "notifyIdle", name = "Notify when idle", description = "Notify when you're not fishing a spot", section = alertsSection, position = 2)
        default Notification notifyIdle()
        {
                return Notification.ON;
        }

        @Range(min = 1, max = 100)
        @ConfigItem(keyName = "idleTicks", name = "Idle delay (ticks)", description = "Game ticks without fishing before the idle alert (1 tick = 0.6s)", section = alertsSection, position = 3)
        default int idleTicks()
        {
                return 5;
        }

        // ---- Automation ----

        @ConfigItem(
                keyName = "autoFish",
                name = "Auto-fish",
                description = "Tap the next spot with simulated touch input. How the input looks (reactions, misclicks, "
                        + "input styles, camera) is set in the Humanizer plugin and shared by every script.",
                section = automationSection,
                position = 0
        )
        default boolean autoFish()
        {
                return false;
        }

        @Range(min = 1, max = 5)
        @ConfigItem(
                keyName = "spotPool",
                name = "Spot pool",
                description = "Pick at random from the N nearest spots instead of always the closest (1 = always closest)",
                section = automationSection,
                position = 1
        )
        default int spotPool()
        {
                return 2;
        }

        // ---- Area ----

        @ConfigItem(
                keyName = "useHuntRange",
                name = "Limit to area",
                description = "Only use spots inside a square around the centre tile. Type 6 in the radius and a 13x13 "
                        + "square around the centre is searched.",
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
                        + "square is centred on the tile you stood on when you switched the script on (like Microbot).",
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
                        + "ground (long-press) and pick 'FISH-Set area centre' to mark it. 'FISH-Clear area centre' removes it.",
                section = huntSection,
                position = 2
        )
        default String centerTile()
        {
                return "";
        }
}
