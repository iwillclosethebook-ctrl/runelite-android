package net.runelite.client.plugins.autowoodcut;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(AutoWoodcutConfig.GROUP)
public interface AutoWoodcutConfig extends Config
{
        String GROUP = "autowoodcut";
        String TREE_KEY = "tree";
        String KEEP_ITEMS_KEY = "keepItems";
        String CENTER_KEY = "centerTile";

        @ConfigSection(name = "Trees", description = "Which trees to chop", position = 0)
        String treesSection = "trees";

        @ConfigSection(name = "Full inventory", description = "What to do when the inventory fills up", position = 1)
        String fullSection = "full";

        @ConfigSection(name = "Style", description = "How trees are drawn", position = 2)
        String styleSection = "style";

        @ConfigSection(name = "Colors", description = "Tree highlight colors", position = 3)
        String colorsSection = "colors";

        @ConfigSection(name = "Tracking & alerts", description = "Log counter and notifications", position = 4)
        String alertsSection = "alerts";

        @ConfigSection(name = "Automation", description = "Auto-chopping (off by default)", position = 5)
        String automationSection = "automation";

        @ConfigSection(name = "Humanizer", description = "How human the simulated touch input looks", position = 6)
        String humanSection = "human";

        @ConfigSection(name = "Camera", description = "Occasional humanized camera turns", position = 7)
        String cameraSection = "camera";

        @ConfigSection(name = "Heat range", description = "The area around the centre tile that is searched for trees", position = 8)
        String huntSection = "hunt";

        enum FullInventoryMode
        {
                NOTIFY,
                DROP,
                BANK
        }

        // ---- Trees ----

        @ConfigItem(
                keyName = TREE_KEY,
                name = "Tree",
                description = "Tree type to chop. Trees are recognised by name and chop action, so stumps are ignored "
                        + "automatically. The panel lists known spots for the chosen tree.",
                section = treesSection,
                position = 0
        )
        default WoodcuttingTree tree()
        {
                return WoodcuttingTree.TREE;
        }

        // ---- Full inventory ----

        @ConfigItem(
                keyName = "fullInvMode",
                name = "When the inventory is full",
                description = "Notify: stop and notify. Drop: shift-drop everything except the keep list along a fresh "
                        + "sine wave. Bank: walk to the nearest reachable bank along a real path "
                        + "(doors, stairs, ladders), deposit everything except the keep list, then walk back to the heat centre.",
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
                        + "item containing it, so 'axe' keeps every axe.",
                section = fullSection,
                position = 1
        )
        default String keepItems()
        {
                return "axe";
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

        @ConfigItem(keyName = "hull", name = "Hull", description = "Highlight the tree's clickbox hull", section = styleSection, position = 0)
        default boolean hull()
        {
                return true;
        }

        @ConfigItem(keyName = "tile", name = "Tile", description = "Highlight the tile under the tree", section = styleSection, position = 1)
        default boolean tile()
        {
                return true;
        }

        @ConfigItem(keyName = "trueTile", name = "True tile", description = "Highlight the tree's tile", section = styleSection, position = 2)
        default boolean trueTile()
        {
                return false;
        }

        @ConfigItem(keyName = "outline", name = "Outline", description = "Draw a model outline (heavier on phones)", section = styleSection, position = 3)
        default boolean outline()
        {
                return false;
        }

        @ConfigItem(keyName = "name", name = "Name", description = "Draw the tree's name above it", section = styleSection, position = 4)
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
        @ConfigItem(keyName = "currentColor", name = "Current tree", description = "Tree you are chopping", section = colorsSection, position = 0)
        default Color currentColor()
        {
                return new Color(255, 40, 40);
        }

        @ConfigItem(keyName = "highlightNext", name = "Mark next tree", description = "Use a separate color for the next tree to be clicked", section = colorsSection, position = 1)
        default boolean highlightNext()
        {
                return true;
        }

        @Alpha
        @ConfigItem(keyName = "nextColor", name = "Next tree", description = "Tree that will be clicked next", section = colorsSection, position = 2)
        default Color nextColor()
        {
                return new Color(255, 220, 0);
        }

        @Alpha
        @ConfigItem(keyName = "otherColor", name = "Other trees", description = "All other matching trees", section = colorsSection, position = 3)
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

        @ConfigItem(keyName = "showCounter", name = "Log counter", description = "Show logs and logs/hour", section = alertsSection, position = 0)
        default boolean showCounter()
        {
                return true;
        }

        @ConfigItem(keyName = "notifyLog", name = "Notify on log", description = "Notify on every log (counted from chat messages)", section = alertsSection, position = 1)
        default Notification notifyLog()
        {
                return Notification.OFF;
        }

        @ConfigItem(keyName = "notifyIdle", name = "Notify when idle", description = "Notify when you are not chopping", section = alertsSection, position = 2)
        default Notification notifyIdle()
        {
                return Notification.ON;
        }

        @Range(min = 1, max = 100)
        @ConfigItem(keyName = "idleTicks", name = "Idle delay (ticks)", description = "Game ticks without chopping before the idle alert (1 tick = 0.6s)", section = alertsSection, position = 3)
        default int idleTicks()
        {
                return 5;
        }

        // ---- Automation ----

        @ConfigItem(
                keyName = "autoChop",
                name = "Auto-chop",
                description = "Tap the next tree with simulated touch input: looks before every tap (re-aims when the "
                        + "tap would not chop that tree), fixes lost taps straight away, occasional ground misclicks "
                        + "corrected after 50-97ms, long-press menu gestures when needed, and an input style that rotates "
                        + "every few minutes. Never uses internal menu calls.",
                section = automationSection,
                position = 0
        )
        default boolean autoChop()
        {
                return false;
        }

        @Range(min = 1, max = 5)
        @ConfigItem(
                keyName = "treePool",
                name = "Tree pool",
                description = "Pick at random from the N nearest trees instead of always the closest (1 = always closest)",
                section = automationSection,
                position = 1
        )
        default int treePool()
        {
                return 2;
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

        @Range(max = 40)
        @ConfigItem(
                keyName = "misclickChance",
                name = "Ground misclicks (%)",
                description = "Chance a chop tap lands on the ground next to the tree. The correction tap back onto "
                        + "the tree follows 50-97ms later, like a player noticing their slip. The active input style "
                        + "scales this up or down a little. 0 disables.",
                section = humanSection,
                position = 3
        )
        default int misclickChance()
        {
                return 10;
        }

        @ConfigItem(
                keyName = "rotateInputMethod",
                name = "Rotate input style",
                description = "Every few minutes, switch how the simulated finger behaves: quick tapper, cautious "
                        + "menu-opener, hovering wanderer, weary grinder. Tap placement on the tree, reaction padding, "
                        + "press duration and misclick rate all change with it.",
                section = humanSection,
                position = 4
        )
        default boolean rotateInputMethod()
        {
                return true;
        }

        @Range(min = 60, max = 900)
        @ConfigItem(
                keyName = "methodRotationMinSec",
                name = "Style holds min (s)",
                description = "Shortest time an input style stays active before switching. Default 180s = 3 minutes.",
                section = humanSection,
                position = 5
        )
        default int methodRotationMinSec()
        {
                return 180;
        }

        @Range(min = 60, max = 900)
        @ConfigItem(
                keyName = "methodRotationMaxSec",
                name = "Style holds max (s)",
                description = "Longest time an input style stays active. Clamped to at least the minimum. Default 240s = 4 minutes.",
                section = humanSection,
                position = 6
        )
        default int methodRotationMaxSec()
        {
                return 240;
        }

        @ConfigItem(
                keyName = "logInput",
                name = "Log input simulation",
                description = "Write each dispatched gesture and camera turn to logcat (tag AutoWoodcut) for debugging",
                section = humanSection,
                position = 7
        )
        default boolean logInput()
        {
                return false;
        }

        // ---- Camera ----

        @ConfigItem(
                keyName = "smartCamera",
                name = "Humanized camera",
                description = "Turn the camera like a player resting their thumb on the screen: only once every few "
                        + "logs (see below), always panning slowly with a soft start that speeds up, sometimes "
                        + "overshooting the target and settling back, sometimes stopping just short of it.",
                section = cameraSection,
                position = 0
        )
        default boolean smartCamera()
        {
                return true;
        }

        @ConfigItem(
                keyName = "cameraRescue",
                name = "Turn to off-screen trees",
                description = "When the next tree is fully off screen and a click is due, turn the camera to bring it "
                        + "back into view (rate-limited to once every 15s, still a slow humanized pan, never a snap)",
                section = cameraSection,
                position = 1
        )
        default boolean cameraRescue()
        {
                return true;
        }

        @Range(min = 1, max = 50)
        @ConfigItem(
                keyName = "cameraEveryMin",
                name = "Turn every (min logs)",
                description = "Fewest logs between voluntary camera turns.",
                section = cameraSection,
                position = 2
        )
        default int cameraEveryMin()
        {
                return 5;
        }

        @Range(min = 1, max = 100)
        @ConfigItem(
                keyName = "cameraEveryMax",
                name = "Turn every (max logs)",
                description = "Most logs between voluntary camera turns. Clamped to at least the minimum.",
                section = cameraSection,
                position = 3
        )
        default int cameraEveryMax()
        {
                return 15;
        }

        @Range(min = 200, max = 6000)
        @ConfigItem(
                keyName = "cameraPanMinMs",
                name = "Turn time min (ms)",
                description = "Shortest time a camera turn takes. Bigger turns use the longer end of the range.",
                section = cameraSection,
                position = 4
        )
        default int cameraPanMinMs()
        {
                return 900;
        }

        @Range(min = 200, max = 8000)
        @ConfigItem(
                keyName = "cameraPanMaxMs",
                name = "Turn time max (ms)",
                description = "Longest time a camera turn takes. Clamped to at least the minimum.",
                section = cameraSection,
                position = 5
        )
        default int cameraPanMaxMs()
        {
                return 2200;
        }

        // ---- Heat range ----

        @ConfigItem(
                keyName = "useHuntRange",
                name = "Limit to heat range",
                description = "Only use trees inside a square around the centre tile. Type 6 in the radius and a 13x13 "
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
                name = "Heat radius (tiles)",
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
                name = "Heat centre tile",
                description = "x,y,plane of the heat centre. Leave empty to centre on yourself, or right-click the "
                        + "ground (long-press) and pick 'WC-Set centre' to mark it. 'WC-Clear centre' removes it.",
                section = huntSection,
                position = 2
        )
        default String centerTile()
        {
                return "";
        }
}
