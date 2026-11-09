/*
 * Rock, ore and location data taken from Microbot-Hub
 * (https://github.com/chsami/Microbot-Hub, mining/data):
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
package net.runelite.client.plugins.automine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * Mineable rocks: the in-game object name, the ore it gives, the level it needs, and
 * well-known places to find it. Rocks are recognised in the scene by name + "Mine"
 * (a depleted rock is just "Rocks", so it never matches), not by IDs.
 */
public enum MiningRock
{
        TIN("Tin rocks", "Tin ore", 438, 1, "Mine",
                loc("Lumbridge Swamp West Mine", 3149, 3148, false),
                loc("Varrock South West Mine", 3181, 3377, false),
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Dwarven Mine", 3034, 9822, false)),
        COPPER("Copper rocks", "Copper ore", 436, 1, "Mine",
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Lumbridge Swamp West Mine", 3149, 3148, false),
                loc("Varrock South West Mine", 3181, 3377, false),
                loc("Dwarven Mine", 3034, 9822, false)),
        CLAY("Clay rocks", "Clay", 434, 1, "Mine",
                loc("Varrock South West Mine", 3181, 3377, false),
                loc("Dwarven Mine", 3034, 9822, false)),
        IRON("Iron rocks", "Iron ore", 440, 15, "Mine",
                loc("Mining Guild", 3046, 9756, false, Skill.MINING, 60),
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Varrock South East Mine", 3285, 3363, false),
                loc("Dwarven Mine", 3034, 9822, false),
                loc("Ardougne South East Mine", 2704, 3330, true),
                loc("Bandit Camp Mine", 3086, 3763, true)),
        SILVER("Silver rocks", "Silver ore", 442, 20, "Mine",
                loc("Varrock South East Mine", 3285, 3363, false),
                loc("Dwarven Mine", 3034, 9822, false),
                loc("Al Kharid Mine", 3296, 3315, false)),
        COAL("Coal rocks", "Coal", 453, 30, "Mine",
                loc("Mining Guild", 3046, 9756, false, Skill.MINING, 60),
                loc("Dwarven Mine", 3034, 9822, false),
                loc("Barbarian Village Mine", 3081, 3421, false),
                loc("Seers' Village Coal Trucks", 2569, 3462, true)),
        GOLD("Gold rocks", "Gold ore", 444, 40, "Mine",
                loc("Dwarven Mine", 3034, 9822, false),
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Crafting Guild", 2938, 3283, false)),
        GEM("Gem rocks", "Uncut gems", -1, 40, "Mine",
                loc("Shilo Village Gem Mine", 2824, 2997, true, Quest.SHILO_VILLAGE),
                loc("Al Kharid Mine", 3296, 3315, false)),
        MITHRIL("Mithril rocks", "Mithril ore", 447, 55, "Mine",
                loc("Mining Guild", 3046, 9756, false, Skill.MINING, 60),
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Dwarven Mine", 3034, 9822, false),
                loc("Lumbridge Swamp East Mine", 3229, 3148, false)),
        ADAMANTITE("Adamantite rocks", "Adamantite ore", 449, 70, "Mine",
                loc("Mining Guild", 3046, 9756, false, Skill.MINING, 60),
                loc("Al Kharid Mine", 3296, 3315, false),
                loc("Lumbridge Swamp East Mine", 3229, 3148, false),
                loc("Neitiznot Mine", 2335, 3808, true, Quest.THE_FREMENNIK_ISLES)),
        BASALT("Basalt rocks", "Basalt", 22603, 72, "Mine",
                loc("Weiss Basalt Mine", 2857, 3937, true, Quest.MAKING_FRIENDS_WITH_MY_ARM)),
        URT_SALT("Urt salt rocks", "Urt salt", 22597, 72, "Mine"),
        EFH_SALT("Efh salt rocks", "Efh salt", 22595, 72, "Mine"),
        TE_SALT("Te salt rocks", "Te salt", 22593, 72, "Mine"),
        RUNITE("Runite rocks", "Runite ore", 451, 85, "Mine",
                loc("Mining Guild", 3046, 9756, true, Skill.MINING, 60),
                loc("Heroes' Guild Mine", 2916, 3506, true, Quest.HEROES_QUEST),
                loc("Neitiznot Mine", 2335, 3808, true, Quest.THE_FREMENNIK_ISLES),
                loc("Lava Maze (Wilderness)", 3058, 3884, false));

        private final String objectName;
        private final String oreName;
        private final int oreId;
        private final int level;
        private final String action;
        private final List<Location> locations;

        MiningRock(String objectName, String oreName, int oreId, int level, String action, Location... locations)
        {
                this.objectName = objectName;
                this.oreName = oreName;
                this.oreId = oreId;
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

        public String getOreName()
        {
                return oreName;
        }

        public int getOreId()
        {
                return oreId;
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
         * True when an in-game object name belongs to this tree. Exact, case-insensitive.
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
                return false;
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

        /** A known spot for a rock type, with what it takes to get there. */
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
