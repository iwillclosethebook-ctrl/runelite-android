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
        String CENTER_KEY = "centerTile";

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

        @ConfigSection(name = "Humanizer", description = "How human the simulated touch input looks", position = 5)
        String humanSection = "human";

        @ConfigSection(name = "Camera", description = "Occasional humanized camera turns", position = 6)
        String cameraSection = "camera";

        @ConfigSection(name = "Heat range", description = "The area around the centre tile that is searched for targets", position = 7)
        String huntSection = "hunt";

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
                description = "Attack the next free matching NPC with simulated touch input: taps at a fresh spot on the target each time, "
                        + "occasional ground misclicks corrected after 50-97ms, long-press menu gestures when 'Attack' is not the "
                        + "left-click option, and an input style that rotates every few minutes. Never uses internal menu calls.",
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
                description = "After a counted kill, pick up the items listed below (also via simulated taps)",
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

        @Range(max = 40)
        @ConfigItem(
                keyName = "misclickChance",
                name = "Ground misclicks (%)",
                description = "Chance an attack tap lands on the ground next to the target. The correction tap back onto the "
                        + "target follows 50-97ms later, like a player noticing their slip. The active input style scales this "
                        + "up or down a little. 0 disables.",
                section = humanSection,
                position = 4
        )
        default int misclickChance()
        {
                return 10;
        }

        @ConfigItem(
                keyName = "rotateInputMethod",
                name = "Rotate input style",
                description = "Every few minutes, switch how the simulated finger behaves: quick tapper, cautious menu-opener, "
                        + "hovering wanderer, weary grinder. Tap placement on the target, reaction padding, press duration and "
                        + "misclick rate all change with it.",
                section = humanSection,
                position = 5
        )
        default boolean rotateInputMethod()
        {
                return true;
        }

        @Range(min = 60, max = 900)
        @ConfigItem(
                keyName = "methodRotationMinSec",
                name = "Style holds min (s)",
                description = "Shortest time an input style stays active before switching. Default 180s = 3 minutes. "
                        + "Set 60 with a 240 max for styles that last roughly 1-4 minutes instead.",
                section = humanSection,
                position = 6
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
                position = 7
        )
        default int methodRotationMaxSec()
        {
                return 240;
        }

        @ConfigItem(
                keyName = "logInput",
                name = "Log input simulation",
                description = "Write each dispatched gesture and camera turn to logcat (tag AutoFight) for debugging",
                section = humanSection,
                position = 8
        )
        default boolean logInput()
        {
                return false;
        }

        // ---- Camera ----

        @ConfigItem(
                keyName = "smartCamera",
                name = "Humanized camera",
                description = "Turn the camera like a player resting their thumb on the screen: only once every few kills "
                        + "(see below), always panning slowly with a soft start that speeds up, sometimes overshooting the "
                        + "target and settling back, sometimes stopping just short of it.",
                section = cameraSection,
                position = 0
        )
        default boolean smartCamera()
        {
                return true;
        }

        @ConfigItem(
                keyName = "cameraRescue",
                name = "Turn to off-screen targets",
                description = "When the next target is fully off screen and an attack is due, turn the camera to bring it back "
                        + "into view (rate-limited to once every 15s, still a slow humanized pan, never a snap)",
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
                name = "Turn every (min kills)",
                description = "Fewest kills between voluntary camera turns. A turn still only happens when this many kills "
                        + "have been counted.",
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
                name = "Turn every (max kills)",
                description = "Most kills between voluntary camera turns. Clamped to at least the minimum.",
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
                description = "Only target NPCs inside a square around the centre tile. Type 6 in the radius and a 13x13 "
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
                name = "Heat radius (tiles)",
                description = "Tiles out from the centre tile. 6 gives a 13x13 square. With no centre marked, the square "
                        + "follows you.",
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
                description = "x,y,plane of the heat centre. Leave empty to centre on yourself, or right-click the ground "
                        + "(long-press) and pick 'AF-Set centre' to mark it. 'AF-Clear centre' removes it.",
                section = huntSection,
                position = 2
        )
        default String centerTile()
        {
                return "";
        }
}
