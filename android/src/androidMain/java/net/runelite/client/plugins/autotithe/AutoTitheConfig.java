package net.runelite.client.plugins.autotithe;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(AutoTitheConfig.GROUP)
public interface AutoTitheConfig extends Config
{
        String GROUP = "autotithe";

        @ConfigItem(keyName = "autoTithe", name = "Auto Tithe Farm",
                description = "Play Tithe Farm. Start at the entrance by the seed table with a seed dibber, a spade and "
                        + "8 watering cans (8) or a Gricoller's can (stamina potions optional). Farming 34+.",
                position = 0)
        default boolean autoTithe()
        {
                return false;
        }

        @ConfigItem(keyName = "lanes", name = "Lanes", description = "Which two lanes to farm, counted from the entrance", position = 1)
        default TitheLanes lanes()
        {
                return TitheLanes.LANE_1_2;
        }

        @Range(min = 1, max = 1000)
        @ConfigItem(keyName = "storeAt", name = "Store fruit at", description = "Put fruit in the sack once you hold this many", position = 2)
        default int storeAt()
        {
                return 100;
        }

        @Range(min = 1, max = 99)
        @ConfigItem(keyName = "gricollerRefill", name = "Gricoller's can refill below (%)", description = "Refill the Gricoller's can at the barrel below this charge", position = 3)
        default int gricollerRefill()
        {
                return 30;
        }

        @Range(min = 100, max = 300)
        @ConfigItem(keyName = "speed", name = "Finger speed (%)", description = "Finger speed for this script as a percent of the original pace", position = 4)
        default int speed()
        {
                return 300;
        }

        @ConfigItem(keyName = "highlight", name = "Highlight patches", description = "Outline the lane's patches by state", position = 5)
        default boolean highlight()
        {
                return true;
        }
}
