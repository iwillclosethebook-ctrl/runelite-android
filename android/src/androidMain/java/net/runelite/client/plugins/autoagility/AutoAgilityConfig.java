package net.runelite.client.plugins.autoagility;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(AutoAgilityConfig.GROUP)
public interface AutoAgilityConfig extends Config
{
        String GROUP = "autoagility";

        @ConfigItem(
                keyName = "autoAgility",
                name = "Auto agility",
                description = "Run the selected course: take each obstacle the moment the last one is done, pick up marks of "
                        + "grace, eat when low. Start near the course. Uses the Humanizer's simulated touch input.",
                position = 0
        )
        default boolean autoAgility()
        {
                return false;
        }

        @ConfigItem(
                keyName = "course",
                name = "Course",
                description = "Which rooftop course to run",
                position = 1
        )
        default AgilityCourse course()
        {
                return AgilityCourse.CANIFIS;
        }

        @Range(min = 0, max = 99)
        @ConfigItem(
                keyName = "eatAt",
                name = "Eat at (HP %)",
                description = "Eat any food in the inventory below this hitpoints percent. 0 = never eat.",
                position = 2
        )
        default int eatAt()
        {
                return 20;
        }

        @ConfigItem(
                keyName = "lootMarks",
                name = "Pick up marks of grace",
                description = "Take marks of grace on the roof you are on",
                position = 3
        )
        default boolean lootMarks()
        {
                return true;
        }

        @Range(min = 100, max = 300)
        @ConfigItem(
                keyName = "speed",
                name = "Finger speed (%)",
                description = "Finger movement speed for this script, as a percent of the original pace (300 = three times as fast)",
                position = 4
        )
        default int speed()
        {
                return 300;
        }
}
