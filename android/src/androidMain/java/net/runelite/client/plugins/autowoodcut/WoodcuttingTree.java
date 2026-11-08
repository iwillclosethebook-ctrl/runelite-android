/*
 * Tree, log and location data taken from Microbot-Hub
 * (https://github.com/chsami/Microbot-Hub, woodcutting/enums):
 *
 * Copyright (c) 2025, chsami
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.autowoodcut;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * Choppable trees: the in-game object name, the log it gives, the level it needs, the
 * chop action, and well-known places to find it. Trees are recognised in the scene by
 * name + chop action (so stumps, which lose the action, never match), not by IDs.
 */
public enum WoodcuttingTree
{
        TREE("Tree", "Logs", 1511, 1, "Chop down",
                loc("Lumbridge", 3192, 3223, false),
                loc("Grand Exchange", 3151, 3231, false),
                loc("Varrock East", 3227, 3457, false),
                loc("Falador", 3002, 3374, false)),
        DYING_TREE("Dying tree", "Logs", 1511, 1, "Chop down"),
        DEAD_TREE("Dead tree", "Logs", 1511, 1, "Chop down"),
        EVERGREEN_TREE("Evergreen tree", "Logs", 1511, 1, "Chop down"),
        JUNGLE_TREE("Jungle tree", "Logs", 1511, 1, "Chop down"),
        BURNT_TREE("Burnt tree", "Charcoal", 973, 1, "Chop down"),
        ACHEY_TREE("Achey tree", "Achey tree logs", 2862, 1, "Chop down"),
        LIGHT_JUNGLE("Light jungle", "Thatch spar light", 6281, 10, "Chop down"),
        OAK("Oak tree", "Oak logs", 1521, 15, "Chop down",
                loc("Lumbridge", 3190, 3247, false),
                loc("Varrock West", 3085, 3481, false),
                loc("Draynor Village", 3103, 3279, false)),
        MEDIUM_JUNGLE("Medium jungle", "Thatch spar med", 6283, 20, "Chop down"),
        WILLOW("Willow tree", "Willow logs", 1519, 30, "Chop down",
                loc("Port Sarim", 3059, 3253, false),
                loc("Draynor Village", 3088, 3235, false),
                loc("Barbarian Outpost", 2532, 3565, false)),
        TEAK("Teak tree", "Teak logs", 6333, 35, "Chop down",
                loc("Castle Wars", 2335, 3048, true),
                loc("Ape Atoll", 2774, 2697, true, Quest.MONKEY_MADNESS_I),
                loc("Mos Le'Harmless", 3832, 3067, true, Quest.CABIN_FEVER)),
        DENSE_JUNGLE("Dense jungle", "Thatch spar dense", 6285, 35, "Chop down"),
        JATOBA("Jatoba tree", "Jatoba logs", 32902, 40, "Chop down"),
        MATURE_JUNIPER("Mature juniper tree", "Juniper logs", 13355, 42, "Chop down"),
        MAPLE("Maple tree", "Maple logs", 1517, 45, "Chop down",
                loc("Seers' Village", 2720, 3465, true)),
        HOLLOW_TREE("Hollow tree", "Bark", 3239, 45, "Chop down"),
        MAHOGANY("Mahogany tree", "Mahogany logs", 6332, 50, "Chop down",
                loc("Ape Atoll", 2716, 2710, true, Quest.MONKEY_MADNESS_I),
                loc("Mos Le'Harmless", 3824, 3053, true, Quest.CABIN_FEVER)),
        ARCTIC_PINE("Arctic pine", "Arctic pine logs", 10810, 54, "Chop down"),
        YEW("Yew tree", "Yew logs", 1515, 60, "Chop down",
                loc("Woodcutting Guild", 1591, 3483, true, Skill.WOODCUTTING, 60),
                loc("Falador", 3052, 3272, false),
                loc("Lumbridge", 3165, 3220, false),
                loc("Seers' Village", 2711, 3463, true)),
        BLISTERWOOD("Blisterwood tree", "Blisterwood logs", 24691, 62, "Chop",
                loc("Darkmeyer", 3631, 3362, true, Quest.SINS_OF_THE_FATHER)),
        SULLIUSCEP("Sulliuscep", "Sulliuscep cap", 21626, 65, "Chop down"),
        CAMPHOR("Camphor tree", "Camphor logs", 32904, 66, "Chop down"),
        MAGIC("Magic tree", "Magic logs", 1513, 75, "Chop down",
                loc("Woodcutting Guild", 1610, 3443, true, Skill.WOODCUTTING, 60),
                loc("Sorcerer's Tower", 2704, 3397, true)),
        IRONWOOD("Ironwood tree", "Ironwood logs", 32907, 80, "Chop down"),
        REDWOOD("Redwood tree", "Redwood logs", 19669, 90, "Cut",
                loc("Woodcutting Guild", 1569, 3493, true, Skill.WOODCUTTING, 60)),
        ROSEWOOD("Rosewood tree", "Rosewood logs", 32910, 92, "Chop down");

        private final String objectName;
        private final String logName;
        private final int logId;
        private final int level;
        private final String action;
        private final List<Location> locations;

        WoodcuttingTree(String objectName, String logName, int logId, int level, String action, Location... locations)
        {
                this.objectName = objectName;
                this.logName = logName;
                this.logId = logId;
                this.level = level;
                this.action = action;
                this.locations = locations.length == 0
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(Arrays.asList(locations));
        }

        public String getObjectName()
        {
                return objectName;
        }

        public String getLogName()
        {
                return logName;
        }

        public int getLogId()
        {
                return logId;
        }

        public int getLevel()
        {
                return level;
        }

        public String getAction()
        {
                return action;
        }

        public List<Location> getLocations()
        {
                return locations;
        }

        /**
         * True when an in-game object name belongs to this tree. Accepts the full name
         * ("Oak tree") and the short form older renders use ("Oak").
         */
        public boolean matchesName(String name)
        {
                if (name == null)
                {
                        return false;
                }
                String n = name.trim().toLowerCase(Locale.ROOT);
                String full = objectName.toLowerCase(Locale.ROOT);
                if (n.equals(full))
                {
                        return true;
                }
                return full.endsWith(" tree") && n.equals(full.substring(0, full.length() - 5));
        }

        @Override
        public String toString()
        {
                return objectName + " (" + level + ")";
        }

        private static Location loc(String name, int x, int y, boolean members)
        {
                return new Location(name, new WorldPoint(x, y, 0), members, null, null, 0);
        }

        private static Location loc(String name, int x, int y, boolean members, Quest quest)
        {
                return new Location(name, new WorldPoint(x, y, 0), members, quest, null, 0);
        }

        private static Location loc(String name, int x, int y, boolean members, Skill skill, int level)
        {
                return new Location(name, new WorldPoint(x, y, 0), members, null, skill, level);
        }

        /** A known spot for a tree type, with what it takes to get there. */
        public static final class Location
        {
                public final String name;
                public final WorldPoint worldPoint;
                public final boolean members;
                public final Quest quest;
                public final Skill skill;
                public final int skillLevel;

                Location(String name, WorldPoint worldPoint, boolean members, Quest quest, Skill skill, int skillLevel)
                {
                        this.name = name;
                        this.worldPoint = worldPoint;
                        this.members = members;
                        this.quest = quest;
                        this.skill = skill;
                        this.skillLevel = skillLevel;
                }
        }
}
