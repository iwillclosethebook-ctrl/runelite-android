/*
 * Bank locations and access requirements.
 *
 * Coordinates and requirement data are taken from the Microbot project
 * (https://github.com/chsami/Microbot, util/bank/enums/BankLocation.java):
 *
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>, and Microbot contributors
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
package net.runelite.client.plugins.gamedata;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;

/**
 * Every bank in the game with the tile to stand near it, whether it is members-only,
 * and what it takes to use it. Shared data: any script that needs to bank (AutoWoodcut
 * today, AutoFish or AutoFight later) can ask for {@link #nearest}.
 *
 * <p>Quest and skill requirements are checked live; requirements that depend on worn
 * gear or diaries are kept as a {@link #getNote() note} and such banks are skipped by
 * {@link #nearest} unless {@code includeNoted} is set.</p>
 */
public enum BankLocation
{
        ALDARIN(1398, 2927, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        AL_KHARID(3270, 3166, 0, false, null),
        ARCEUUS(1624, 3745, 0, true, null),
        ARDOUGNE_NORTH(2616, 3332, 0, true, null),
        ARDOUGNE_SOUTH(2655, 3283, 0, true, null),
        AUBURNVALE(1413, 3353, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        BARBARIAN_OUTPOST(2536, 3574, 0, true, null),
        BLAST_FURNACE_BANK(1948, 4957, 0, true, null, Q(Quest.THE_GIANT_DWARF, false)),
        BLAST_MINE(1502, 3856, 0, true, null),
        BURGH_DE_ROTT(3495, 3212, 0, true, null, Q(Quest.PRIEST_IN_PERIL, true), Q(Quest.IN_AID_OF_THE_MYREQUE, false)),
        CAMELOT(2725, 3493, 0, true, null),
        CAMODZAAL(2979, 5798, 0, false, null, Q(Quest.BELOW_ICE_MOUNTAIN, true)),
        CAM_TORUM(1453, 9568, 1, true, null, Q(Quest.PERILOUS_MOONS, false)),
        CANIFIS(3512, 3480, 0, true, null, Q(Quest.PRIEST_IN_PERIL, true)),
        CASTLE_WARS(2443, 3083, 0, false, null),
        CATHERBY(2808, 3441, 0, true, null),
        CLAN_HALL(1747, 5476, 0, false, "Clan membership"),
        COOKS_GUILD(3147, 3450, 0, true, "Chef's hat, cooking/max cape or Varrock armour 3+, plus 99 Cooking or the Varrock hard diary"),
        CORSAIR_COVE(2570, 2864, 0, false, null, Q(Quest.THE_CORSAIR_CURSE, true)),
        CRAFTING_GUILD(2936, 3281, 0, true, "Brown/golden apron, crafting or max cape worn, plus 99 Crafting or the Falador hard diary"),
        DARKFROST(1527, 3292, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        DARKMEYER(3604, 3366, 0, true, null, Q(Quest.SINS_OF_THE_FATHER, true)),
        DEEPFIN_POINT(1935, 2755, 0, true, null, S(Skill.SAILING, 67)),
        DEEPFIN_MINE_MID(2011, 9186, 0, true, "Bank has to be built first", S(Skill.SAILING, 67)),
        DEEPFIN_MINE_EAST(2094, 9197, 0, true, "Bank has to be built first", S(Skill.SAILING, 67)),
        DORGESH_KAAN_BANK(2702, 5350, 0, true, null, Q(Quest.DEATH_TO_THE_DORGESHUUN, true)),
        DRAYNOR_VILLAGE(3093, 3245, 0, false, null),
        DUEL_ARENA(3381, 3268, 0, true, null),
        DWARF_MINE_BANK(2837, 10207, 0, true, null, Q(Quest.THE_GIANT_DWARF, false)),
        EDGEVILLE(3094, 3492, 0, false, null),
        FALADOR_EAST(3014, 3355, 0, false, null),
        FALADOR_WEST(2945, 3369, 0, false, null),
        FARMING_GUILD(1253, 3741, 0, true, null, S(Skill.FARMING, 45)),
        FEROX_ENCLAVE(3130, 3631, 0, false, null),
        FISHING_GUILD(2586, 3420, 0, true, null, S(Skill.FISHING, 68)),
        FOSSIL_ISLAND(3739, 3804, 0, true, null, Q(Quest.BONE_VOYAGE, true)),
        FOSSIL_ISLAND_WRECK(3766, 3898, 0, true, null, Q(Quest.BONE_VOYAGE, true)),
        GNOME_BANK(2445, 3425, 1, true, null),
        GNOME_TREE_BANK_SOUTH(2449, 3482, 1, true, null),
        GNOME_TREE_BANK_WEST(2442, 3488, 1, true, null),
        GRAND_EXCHANGE(3166, 3485, 0, false, null),
        GREAT_KOUREND_CASTLE(1612, 3681, 2, true, null),
        GUARDIANS_OF_THE_RIFT(3618, 9473, 0, true, null, Q(Quest.TEMPLE_OF_THE_EYE, true)),
        HALLOWED_SEPULCHRE(2400, 5983, 0, true, null),
        HOSIDIUS(1749, 3599, 0, true, null),
        HOSIDIUS_KITCHEN(1676, 3617, 0, true, null),
        HUNTERS_GUILD(1542, 3041, 0, true, null),
        ISLE_OF_SOULS(2212, 2859, 0, true, null),
        JATIZSO(2416, 3801, 0, true, null, Q(Quest.THE_FREMENNIK_TRIALS, true), Q(Quest.THE_FREMENNIK_ISLES, false)),
        LANDS_END(1512, 3421, 0, true, null),
        LEGENDS_GUILD(2732, 3379, 2, true, null, Q(Quest.LEGENDS_QUEST, true)),
        LLETYA(2353, 3163, 0, true, null, Q(Quest.MOURNINGS_END_PART_I, false)),
        LOVAKENGJ(1526, 3739, 0, true, null),
        LUMBRIDGE_FRONT(3221, 3217, 0, false, "PvP worlds only"),
        LUMBRIDGE_BASEMENT(3218, 9623, 0, true, null, Q(Quest.RECIPE_FOR_DISASTER__ANOTHER_COOKS_QUEST, true)),
        LUMBRIDGE_TOP(3209, 3220, 2, false, null),
        LUNAR_ISLE(2099, 3919, 0, true, "Seal of passage worn unless Dream Mentor is complete", Q(Quest.LUNAR_DIPLOMACY, true)),
        MAGE_TRAINING_ARENA(3366, 3318, 1, true, null),
        MAGE_ARENA(2534, 4712, 0, true, null),
        MINING_GUILD(3013, 9718, 0, true, "Only usable from inside the Mining Guild (regions 12183/12184)", S(Skill.MINING, 60)),
        MISTROCK(1381, 2866, 0, true, null),
        MOR_UL_REK(2541, 5140, 0, true, null),
        MOTHERLOAD(3760, 5666, 0, true, null),
        MOUNT_KARUULM(1324, 3824, 0, true, null),
        MOUNT_QUIDAMORTEM(1254, 3571, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        MYTHS_GUILD(2463, 2847, 1, true, null, Q(Quest.DRAGON_SLAYER_II, true)),
        NARDAH(3428, 2892, 0, true, null),
        NEMUS_RETREAT(1386, 3309, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        NEITIZNOT(2337, 3807, 0, true, null, Q(Quest.THE_FREMENNIK_TRIALS, true), Q(Quest.THE_FREMENNIK_ISLES, true)),
        PEST_CONTROL(2667, 2653, 0, true, null),
        PISCARILIUS(1803, 3790, 0, true, null),
        PISCATORIS_FISHING_COLONY(2330, 3689, 0, true, null, Q(Quest.SWAN_SONG, true)),
        PORT_KHAZARD(2664, 3161, 0, true, null),
        PORT_PHASMATYS(3688, 3467, 0, true, null, Q(Quest.GHOSTS_AHOY, true)),
        PRIFDDINAS_NORTH(3257, 6106, 0, true, null, Q(Quest.SONG_OF_THE_ELVES, true)),
        PRIFDDINAS_SOUTH(3295, 6059, 0, true, null, Q(Quest.SONG_OF_THE_ELVES, true)),
        ROGUES_DEN_EMERALD_BENEDICT(3043, 4973, 1, true, null),
        ROGUES_DEN_CHEST(3040, 4969, 1, true, null),
        RUINS_OF_UNKAH(3156, 2835, 0, true, null),
        SHANTY_PASS(3308, 3120, 0, true, null),
        SHAYZIEN_BANK(1488, 3592, 0, true, null),
        SHAYZIEN_CHEST(1486, 3646, 0, true, null),
        SHILO_VILLAGE(2852, 2954, 0, true, null, Q(Quest.SHILO_VILLAGE, true)),
        SOPHANEM(2799, 5169, 0, true, null, Q(Quest.CONTACT, true)),
        SULPHUR_MINE(1453, 3858, 0, true, null),
        SUNBLEAK_ISLAND(2195, 2314, 0, true, null, S(Skill.SAILING, 72)),
        TAL_TEKLAN(1243, 3121, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        TREE_GNOME_STRONGHOLD_NIEVE(2445, 3424, 1, true, null),
        TZHAAR(2446, 5178, 0, true, null),
        VARLAMORE_EAST(1780, 3094, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        VARLAMORE_WEST(1647, 3118, 0, true, null, Q(Quest.CHILDREN_OF_THE_SUN, true)),
        VARROCK_EAST(3253, 3422, 0, false, null),
        VARROCK_WEST(3183, 3441, 0, false, null),
        VINERY_BANK(1809, 3566, 0, true, null),
        VOLCANO_BANK(3819, 3809, 0, true, null, Q(Quest.BONE_VOYAGE, true)),
        WINTERTODT(1640, 3944, 0, true, null),
        WARRIORS_GUILD(2843, 3542, 0, true, "Attack + Strength of 130 combined, or 99 in either"),
        WOODCUTTING_GUILD(1591, 3479, 0, true, null, S(Skill.WOODCUTTING, 60)),
        YANILLE(2613, 3093, 0, true, null),
        ZANARIS(2383, 4458, 0, true, null, Q(Quest.LOST_CITY, true), Q(Quest.FAIRYTALE_I__GROWING_PAINS, true), Q(Quest.FAIRYTALE_II__CURE_A_QUEEN, false)),
        ZEAH_SAND_BANK(1719, 3465, 0, true, null);

        private final WorldPoint worldPoint;
        private final boolean members;
        private final String note;
        private final List<Requirement> requirements;

        BankLocation(int x, int y, int plane, boolean members, String note, Requirement... requirements)
        {
                this.worldPoint = new WorldPoint(x, y, plane);
                this.members = members;
                this.note = note;
                this.requirements = requirements.length == 0
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(Arrays.asList(requirements));
        }

        public WorldPoint getWorldPoint()
        {
                return worldPoint;
        }

        public boolean isMembers()
        {
                return members;
        }

        /** Extra conditions that are not checked automatically, or null. */
        public String getNote()
        {
                return note;
        }

        public List<Requirement> getRequirements()
        {
                return requirements;
        }

        /** Human-readable name, e.g. "Draynor village". */
        public String getDisplayName()
        {
                String s = name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        /**
         * True when the player can use this bank as far as can be told: members world for
         * members banks, and every quest/skill requirement met. Client thread only.
         */
        public boolean isAccessible(Client client)
        {
                if (members)
                {
                        Set<WorldType> types = client.getWorldType();
                        if (types == null || !types.contains(WorldType.MEMBERS))
                        {
                                return false;
                        }
                }
                for (Requirement r : requirements)
                {
                        if (!r.isMet(client))
                        {
                                return false;
                        }
                }
                return true;
        }

        /**
         * The closest usable bank on the same plane within {@code maxTiles} (Chebyshev
         * distance), or null. Client thread only.
         *
         * @param includeNoted also consider banks with unchecked conditions (see {@link #getNote()})
         */
        public static BankLocation nearest(Client client, WorldPoint from, int maxTiles, boolean includeNoted)
        {
                if (from == null)
                {
                        return null;
                }
                BankLocation best = null;
                int bestDist = Integer.MAX_VALUE;
                for (BankLocation bank : values())
                {
                        WorldPoint wp = bank.worldPoint;
                        if (wp.getPlane() != from.getPlane())
                        {
                                continue;
                        }
                        int d = Math.max(Math.abs(wp.getX() - from.getX()), Math.abs(wp.getY() - from.getY()));
                        if (d > maxTiles || d >= bestDist)
                        {
                                continue;
                        }
                        if (!includeNoted && bank.note != null)
                        {
                                continue;
                        }
                        if (!bank.isAccessible(client))
                        {
                                continue;
                        }
                        best = bank;
                        bestDist = d;
                }
                return best;
        }

        private static Requirement Q(Quest quest, boolean finished)
        {
                return new Requirement(quest, finished, null, 0);
        }

        private static Requirement S(Skill skill, int level)
        {
                return new Requirement(null, false, skill, level);
        }

        /** One quest or skill requirement. */
        public static final class Requirement
        {
                public final Quest quest;
                /** true: quest must be finished; false: quest must at least be started. */
                public final boolean finished;
                public final Skill skill;
                public final int level;

                Requirement(Quest quest, boolean finished, Skill skill, int level)
                {
                        this.quest = quest;
                        this.finished = finished;
                        this.skill = skill;
                        this.level = level;
                }

                public boolean isMet(Client client)
                {
                        if (quest != null)
                        {
                                QuestState state = quest.getState(client);
                                return finished ? state == QuestState.FINISHED : state != QuestState.NOT_STARTED;
                        }
                        if (skill != null)
                        {
                                return client.getRealSkillLevel(skill) >= level;
                        }
                        return true;
                }

                @Override
                public String toString()
                {
                        if (quest != null)
                        {
                                return quest.getName() + (finished ? " (complete)" : " (started)");
                        }
                        return skill == null ? "" : level + " " + skill.getName();
                }
        }
}
