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
        String FOOD_ITEMS_KEY = "foodItems";
        String WITHDRAW_FOOD_KEY = "withdrawFood";
        String WITHDRAW_ITEMS_KEY = "withdrawItems";
        String KEEP_ITEMS_KEY = "keepItems";

        @ConfigSection(name = "Targets", description = "Which NPCs to highlight", position = 0)
        String targetsSection = "targets";

        String styleSection = "style";

        String colorsSection = "colors";

        @ConfigSection(name = "Tracking & alerts", description = "Kill counter and notifications", position = 3)
        String alertsSection = "alerts";

        @ConfigSection(name = "Automation", description = "Auto-attack and auto-loot (both off by default)", position = 4)
        String automationSection = "automation";

        @ConfigSection(name = "Eating", description = "Auto-eat (off by default)", position = 5)
        String eatSection = "eat";

        @ConfigSection(name = "Banking", description = "Bank trips for loot and food (off by default)", position = 6)
        String bankSection = "bank";

        @ConfigSection(name = "Area", description = "The area around the centre tile that is searched for targets", position = 8)
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

        @ConfigItem(hidden = true, keyName = "hull", name = "Hull", description = "Highlight the NPC's clickbox hull", section = styleSection, position = 0)
        default boolean hull()
        {
                return true;
        }

        @ConfigItem(hidden = true, keyName = "tile", name = "Tile", description = "Highlight the tile under the NPC", section = styleSection, position = 1)
        default boolean tile()
        {
                return false;
        }

        @ConfigItem(hidden = true, keyName = "trueTile", name = "True tile", description = "Highlight the NPC's server-side tile", section = styleSection, position = 2)
        default boolean trueTile()
        {
                return false;
        }

        @ConfigItem(hidden = true, keyName = "outline", name = "Outline", description = "Draw a model outline (heavier on phones)", section = styleSection, position = 3)
        default boolean outline()
        {
                return false;
        }

        @ConfigItem(hidden = true, keyName = "name", name = "Name", description = "Draw the NPC's name above it", section = styleSection, position = 4)
        default boolean name()
        {
                return true;
        }

        @ConfigItem(hidden = true, keyName = "minimap", name = "Minimap name", description = "Draw names on the minimap", section = styleSection, position = 5)
        default boolean minimap()
        {
                return false;
        }

        @Range(min = 1, max = 10)
        @ConfigItem(hidden = true, keyName = "borderWidth", name = "Border width", description = "Highlight line thickness", section = styleSection, position = 6)
        default int borderWidth()
        {
                return 2;
        }

        @Range(max = 4)
        @ConfigItem(hidden = true, keyName = "outlineFeather", name = "Outline feather", description = "Outline softness (0-4)", section = styleSection, position = 7)
        default int outlineFeather()
        {
                return 0;
        }

        @ConfigItem(hidden = true, keyName = "hideDead", name = "Hide dying NPCs", description = "Stop highlighting NPCs that are dying", section = styleSection, position = 8)
        default boolean hideDead()
        {
                return true;
        }

        // ---- Colors ----

        @Alpha
        @ConfigItem(hidden = true, keyName = "targetColor", name = "Current target", description = "NPC you're fighting", section = colorsSection, position = 0)
        default Color targetColor()
        {
                return new Color(255, 40, 40);
        }

        @ConfigItem(hidden = true, keyName = "highlightNext", name = "Mark next target", description = "Use a separate color for the nearest free NPC", section = colorsSection, position = 1)
        default boolean highlightNext()
        {
                return true;
        }

        @Alpha
        @ConfigItem(hidden = true, keyName = "nextColor", name = "Next target", description = "Nearest free matching NPC", section = colorsSection, position = 2)
        default Color nextColor()
        {
                return new Color(255, 220, 0);
        }

        @Alpha
        @ConfigItem(hidden = true, keyName = "otherColor", name = "Other matches", description = "All other matching NPCs", section = colorsSection, position = 3)
        default Color otherColor()
        {
                return new Color(0, 255, 255);
        }

        @Alpha
        @ConfigItem(hidden = true, keyName = "fillColor", name = "Fill", description = "Fill color inside the highlight", section = colorsSection, position = 4)
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
                description = "Attack the next free matching NPC with simulated touch input (input feel is set in the Humanizer plugin). "
                        + "Taps at a fresh spot on the target each time, "
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

        @Range(max = 100)
        @ConfigItem(
                keyName = "lootNowChance",
                name = "Loot right away (%)",
                description = "After each kill the script decides once: loot now (this chance) or fight on and collect the "
                        + "drops later together with the next ones. The decision is kept until that loot run is done.",
                section = automationSection,
                position = 5
        )
        default int lootNowChance()
        {
                return 70;
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

        @Range(min = 1, max = 5)
        @ConfigItem(
                keyName = "targetPool",
                name = "Target pool",
                description = "Pick at random from the N nearest NPCs instead of always the closest (1 = always closest)",
                section = automationSection,
                position = 4
        )
        default int targetPool()
        {
                return 2;
        }

        // ---- Eating ----

        @ConfigItem(
                keyName = "autoEat",
                name = "Auto-eat",
                description = "Eat when HP drops to the threshold below (tap the food in the inventory, or long-press + 'Eat')",
                section = eatSection,
                position = 0
        )
        default boolean autoEat()
        {
                return false;
        }

        @ConfigItem(
                keyName = FOOD_ITEMS_KEY,
                name = "Food",
                description = "Comma-separated food names or IDs, e.g. Lobster, Shark. Empty = anything in the inventory with an 'Eat' option.",
                section = eatSection,
                position = 1
        )
        default String foodItems()
        {
                return "";
        }

        @Range(min = 10, max = 95)
        @ConfigItem(
                keyName = "eatAtPercent",
                name = "Eat at HP (%)",
                description = "Eat when HP falls to this percentage of max HP",
                section = eatSection,
                position = 2
        )
        default int eatAtPercent()
        {
                return 50;
        }

        @Range(max = 25)
        @ConfigItem(
                keyName = "eatJitter",
                name = "Eat threshold wobble (%)",
                description = "The threshold moves up or down by up to this much after every bite, so eating never happens at the same HP",
                section = eatSection,
                position = 3
        )
        default int eatJitter()
        {
                return 8;
        }

        // ---- Banking ----

        @ConfigItem(
                keyName = "bankEnabled",
                name = "Bank trips",
                description = "Walk to the nearest reachable bank (real path: doors, stairs, ladders, out of dungeons), deposit the "
                        + "loot, withdraw food and the items below, then walk back to the fight area",
                section = bankSection,
                position = 0
        )
        default boolean bankEnabled()
        {
                return false;
        }

        @ConfigItem(keyName = "bankWhenFull", name = "Bank when full", description = "Start a bank trip when the inventory is full", section = bankSection, position = 1)
        default boolean bankWhenFull()
        {
                return true;
        }

        @ConfigItem(keyName = "bankWhenOutOfFood", name = "Bank when out of food", description = "Start a bank trip when no food is left (needs 'Withdraw food')", section = bankSection, position = 2)
        default boolean bankWhenOutOfFood()
        {
                return true;
        }

        @Range(max = 90)
        @ConfigItem(
                keyName = "panicHpPercent",
                name = "Retreat at HP (%)",
                description = "With no food left and HP at or below this, leave the fight for the bank right away (0 = never)",
                section = bankSection,
                position = 3
        )
        default int panicHpPercent()
        {
                return 30;
        }

        @ConfigItem(
                keyName = WITHDRAW_FOOD_KEY,
                name = "Withdraw food",
                description = "Food to take out of the bank, as name or ID with an amount, e.g. Lobster x15. The trip fails (and stops) if the bank has none.",
                section = bankSection,
                position = 4
        )
        default String withdrawFood()
        {
                return "";
        }

        @ConfigItem(
                keyName = WITHDRAW_ITEMS_KEY,
                name = "Withdraw items",
                description = "Other items to take out each trip, e.g. Brass key x1, 995 x1000 (name or ID, 'x amount' optional)",
                section = bankSection,
                position = 5
        )
        default String withdrawItems()
        {
                return "";
        }

        @ConfigItem(
                keyName = KEEP_ITEMS_KEY,
                name = "Never deposit",
                description = "Items that stay in the inventory, e.g. Rune pouch, Teleport tab (food and withdraw items are kept automatically). A name matches any item containing it.",
                section = bankSection,
                position = 6
        )
        default String keepItems()
        {
                return "";
        }

        @ConfigItem(
                keyName = "bankPick",
                name = "Bank",
                description = "Which bank to use. 'Same as Humanizer default' uses the one bank set in the Humanizer "
                        + "settings (shared by all scripts); 'Closest bank' picks the nearest usable one within the max "
                        + "distance. A named bank is used at any distance.",
                section = bankSection,
                position = 7
        )
        default net.runelite.client.plugins.gamedata.BankPick bankPick()
        {
                return net.runelite.client.plugins.gamedata.BankPick.SHARED;
        }

        @Range(min = 10, max = 400)
        @ConfigItem(
                keyName = "bankMaxDistance",
                name = "Max bank distance (tiles)",
                description = "Banks further than this (straight line, dungeons measured from the surface above) are not considered",
                section = bankSection,
                position = 7
        )
        default int bankMaxDistance()
        {
                return 150;
        }

        // ---- Area ----

        @ConfigItem(
                keyName = "useHuntRange",
                name = "Limit to area",
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
                name = "Area radius (tiles)",
                description = "Tiles out from the centre tile. 6 gives a 13x13 square. With no centre marked, the square "
                        + "is centred on the tile you stood on when you switched the script on (like Microbot).",
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
                description = "x,y,plane of the area centre. Leave empty to centre on yourself, or right-click the ground "
                        + "(long-press) and pick 'AF-Set area centre' to mark it. 'AF-Clear area centre' removes it.",
                section = huntSection,
                position = 2
        )
        default String centerTile()
        {
                return "";
        }
}
