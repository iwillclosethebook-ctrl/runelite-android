package net.runelite.client.plugins.humanizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * One set of "how human does the input look" settings, shared by every auto script
 * (AutoFish, AutoFight, AutoWoodcut, AutoMine, ...). Change it here and every script
 * follows.
 */
@ConfigGroup(HumanizerConfig.GROUP)
public interface HumanizerConfig extends Config
{
        String GROUP = "humanizer";

        @ConfigSection(name = "Session log", description = "Always-on log of everything that happens, to share for troubleshooting", position = -2)
        String sessionSection = "session";

        @ConfigSection(name = "Banking", description = "The bank all scripts use", position = -1)
        String bankingSection = "banking";

        @ConfigSection(name = "Reactions", description = "Pauses before simulated taps", position = 0)
        String reactionSection = "reaction";

        @ConfigSection(name = "Input style", description = "How the simulated finger behaves", position = 1)
        String styleSection = "style";

        @ConfigSection(name = "Camera", description = "Humanized camera turns", position = 2)
        String cameraSection = "camera";

        @ConfigSection(name = "Keys & tabs", description = "F-key tab switching, key hold times and XP checks", position = 4)
        String keysSection = "keys";

        @ConfigSection(name = "Walking & typing", description = "Minimap walking and keyboard rhythm", position = 3)
        String walkSection = "walk";

        @ConfigSection(name = "Debug", description = "Logging", position = 5)
        String debugSection = "debug";

        // ---- Reactions ----

        @Range(min = 0, max = 20)
        @ConfigItem(keyName = "reactionMin", name = "Reaction min (ticks)", description = "Shortest pause before an automated tap (1 tick = 0.6s)", section = reactionSection, position = 0)
        default int reactionMin()
        {
                return 2;
        }

        @Range(min = 0, max = 40)
        @ConfigItem(keyName = "reactionMax", name = "Reaction max (ticks)", description = "Longest pause before an automated tap. Clamped to at least the minimum.", section = reactionSection, position = 1)
        default int reactionMax()
        {
                return 6;
        }

        @Range(max = 100)
        @ConfigItem(keyName = "hesitateChance", name = "Hesitate chance (%)", description = "Chance of an extra 1-3 tick pause on top of the reaction delay", section = reactionSection, position = 2)
        default int hesitateChance()
        {
                return 15;
        }

        // ---- Input style ----

        @Range(max = 40)
        @ConfigItem(
                keyName = "misclickChance",
                name = "Ground misclicks (%)",
                description = "Chance a tap lands on the ground next to the target. The correction tap follows 50-97ms "
                        + "later, like a player noticing their slip. The active input style scales this a little. 0 disables.",
                section = styleSection,
                position = 0
        )
        default int misclickChance()
        {
                return 10;
        }

        @ConfigItem(
                keyName = "rotateInputMethod",
                name = "Rotate input style",
                description = "Every few minutes, switch how the simulated finger behaves: quick tapper, cautious "
                        + "menu-opener, hovering wanderer, weary grinder. Tap placement, padding, press duration and "
                        + "misclick rate all change with it.",
                section = styleSection,
                position = 1
        )
        default boolean rotateInputMethod()
        {
                return true;
        }

        @Range(min = 60, max = 900)
        @ConfigItem(keyName = "methodRotationMinSec", name = "Style holds min (s)", description = "Shortest time an input style stays active", section = styleSection, position = 2)
        default int methodRotationMinSec()
        {
                return 180;
        }

        @Range(min = 60, max = 900)
        @ConfigItem(keyName = "methodRotationMaxSec", name = "Style holds max (s)", description = "Longest time an input style stays active. Clamped to at least the minimum.", section = styleSection, position = 3)
        default int methodRotationMaxSec()
        {
                return 240;
        }

        @Range(max = 100)
        @ConfigItem(keyName = "hoverScale", name = "Idle hover (%)", description = "How often the finger idles over the screen while a script waits (100 = the style's own rate, 0 = never)", section = styleSection, position = 4)
        default int hoverScale()
        {
                return 100;
        }

        // ---- Camera ----

        @ConfigItem(
                keyName = "smartCamera",
                name = "Humanized camera",
                description = "Turn the camera now and then like a player resting their thumb on the screen: slow "
                        + "start, sometimes overshooting and settling back, sometimes stopping a little short.",
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
                description = "When what a script needs to tap is off screen, turn the camera to bring it into view — "
                        + "a little to the left or right of it, never dead centre, and never a snap.",
                section = cameraSection,
                position = 1
        )
        default boolean cameraRescue()
        {
                return true;
        }

        @Range(min = 1, max = 50)
        @ConfigItem(keyName = "cameraEveryMin", name = "Turn every (min actions)", description = "Fewest catches / logs / kills between voluntary camera turns", section = cameraSection, position = 2)
        default int cameraEveryMin()
        {
                return 5;
        }

        @Range(min = 1, max = 100)
        @ConfigItem(keyName = "cameraEveryMax", name = "Turn every (max actions)", description = "Most catches / logs / kills between voluntary camera turns", section = cameraSection, position = 3)
        default int cameraEveryMax()
        {
                return 15;
        }

        @Range(min = 200, max = 6000)
        @ConfigItem(keyName = "cameraPanMinMs", name = "Turn time min (ms)", description = "Shortest time a camera turn takes. Bigger turns use the longer end.", section = cameraSection, position = 4)
        default int cameraPanMinMs()
        {
                return 900;
        }

        @Range(min = 200, max = 8000)
        @ConfigItem(keyName = "cameraPanMaxMs", name = "Turn time max (ms)", description = "Longest time a camera turn takes. Clamped to at least the minimum.", section = cameraSection, position = 5)
        default int cameraPanMaxMs()
        {
                return 2200;
        }

        @Range(min = 0, max = 30)
        @ConfigItem(keyName = "rescueOffsetMinDeg", name = "View offset min (°)", description = "How far to the side of an off-screen target the camera settles", section = cameraSection, position = 6)
        default int rescueOffsetMinDeg()
        {
                return 6;
        }

        @Range(min = 0, max = 30)
        @ConfigItem(keyName = "rescueOffsetMaxDeg", name = "View offset max (°)", description = "Clamped to at least the minimum", section = cameraSection, position = 7)
        default int rescueOffsetMaxDeg()
        {
                return 15;
        }

        // ---- Walking & typing ----

        @Range(min = 4, max = 16)
        @ConfigItem(keyName = "walkAheadMin", name = "Minimap step min (tiles)", description = "Shortest distance ahead a minimap tap goes while walking a path", section = walkSection, position = 0)
        default int walkAheadMin()
        {
                return 6;
        }

        @Range(min = 4, max = 18)
        @ConfigItem(keyName = "walkAheadMax", name = "Minimap step max (tiles)", description = "Longest distance ahead a minimap tap goes. Clamped to at least the minimum.", section = walkSection, position = 1)
        default int walkAheadMax()
        {
                return 13;
        }

        @Range(min = 30, max = 600)
        @ConfigItem(keyName = "typeMinMs", name = "Typing min (ms/key)", description = "Fastest gap between typed characters (bank search, amounts)", section = walkSection, position = 2)
        default int typeMinMs()
        {
                return 70;
        }

        @Range(min = 30, max = 900)
        @ConfigItem(keyName = "typeMaxMs", name = "Typing max (ms/key)", description = "Slowest gap between typed characters. Clamped to at least the minimum.", section = walkSection, position = 3)
        default int typeMaxMs()
        {
                return 210;
        }

        // ---- Session log ----

        @ConfigItem(keyName = "sessionLog", name = "Session log on", description = "Keep a running text log of everything: your taps and the scripts' (with finger paths and keys), menu clicks, chat, XP, inventory, interfaces, position, script status, errors. Share it from the Scripts panel.", section = sessionSection, position = 0)
        default boolean sessionLog()
        {
                return true;
        }

        @ConfigItem(keyName = "sessionLogMoves", name = "Log finger paths", description = "Include every finger movement point of each gesture (bigger log)", section = sessionSection, position = 1)
        default boolean sessionLogMoves()
        {
                return true;
        }

        @Range(min = 8, max = 500)
        @ConfigItem(keyName = "sessionLogMaxMb", name = "Keep up to (MB)", description = "Oldest log files are deleted beyond this total", section = sessionSection, position = 2)
        default int sessionLogMaxMb()
        {
                return 60;
        }

        // ---- Banking ----

        @ConfigItem(keyName = "defaultBank", name = "Default bank (all scripts)", description = "The bank every script uses when its own Bank setting is 'Same as Humanizer default'. 'Closest bank' = nearest usable one. The list is every bank in the game (from Microbot's bank data).", section = bankingSection, position = 0)
        default net.runelite.client.plugins.gamedata.BankPick defaultBank()
        {
                return net.runelite.client.plugins.gamedata.BankPick.CLOSEST;
        }

        // ---- Keys & tabs ----

        @ConfigItem(keyName = "useFKeys", name = "Use F-keys for tabs", description = "Switch side tabs with the F-key you bound to them in the game's settings (read from the game), like a desktop player. Unbound tabs are tapped instead.", section = keysSection, position = 0)
        default boolean useFKeys()
        {
                return true;
        }

        @Range(min = 0, max = 100)
        @ConfigItem(keyName = "fKeyChance", name = "F-key vs tap (%)", description = "How often a bound tab is opened with its F-key instead of tapping the tab button", section = keysSection, position = 1)
        default int fKeyChance()
        {
                return 80;
        }

        @Range(min = 25, max = 400)
        @ConfigItem(keyName = "keyHoldMinMs", name = "Key hold min (ms)", description = "Shortest time a key stays pressed down (press and release are separate events, like a real keyboard)", section = keysSection, position = 2)
        default int keyHoldMinMs()
        {
                return 55;
        }

        @Range(min = 25, max = 600)
        @ConfigItem(keyName = "keyHoldMaxMs", name = "Key hold max (ms)", description = "Longest time a key stays pressed down. Clamped to at least the minimum.", section = keysSection, position = 3)
        default int keyHoldMaxMs()
        {
                return 140;
        }

        @ConfigItem(keyName = "xpChecks", name = "Check XP now and then", description = "Every few minutes, while the script waits, open the Skills tab, look at the skill being trained, then go back to the inventory", section = keysSection, position = 4)
        default boolean xpChecks()
        {
                return true;
        }

        @Range(min = 1, max = 120)
        @ConfigItem(keyName = "xpCheckMinMin", name = "XP check every min (minutes)", description = "Shortest time between XP checks", section = keysSection, position = 5)
        default int xpCheckMinMin()
        {
                return 4;
        }

        @Range(min = 1, max = 240)
        @ConfigItem(keyName = "xpCheckMaxMin", name = "XP check every max (minutes)", description = "Longest time between XP checks. Clamped to at least the minimum.", section = keysSection, position = 6)
        default int xpCheckMaxMin()
        {
                return 13;
        }

        // ---- Debug ----

        @ConfigItem(keyName = "logInput", name = "Log input simulation", description = "Write each gesture, camera turn and walk step to logcat (tags AutoFish, AutoFight, ...)", section = debugSection, position = 0)
        default boolean logInput()
        {
                return false;
        }
}
