/*
 * Transport data (doors, stairs, ladders, shortcuts) from transports.tsv, as shipped with
 * the ShortestPath plugin by Skretzo and the Microbot project.
 *
 * Copyright (c) 2022, Skretzo; Microbot contributors
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

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;

/**
 * One way from one tile to another that is not a plain step: open a door, climb stairs,
 * squeeze through a shortcut. Only object-based transports with checkable requirements
 * are loaded (no teleports, no item or coin costs).
 */
public final class Transport
{
        private static final String TAG = "Transport";

        public final int originX;
        public final int originY;
        public final int originZ;
        public final int destX;
        public final int destY;
        public final int destZ;
        /** Menu option to use, e.g. "Climb-up" or "Open". */
        public final String option;
        /** Menu target (object name), e.g. "Staircase". */
        public final String target;
        public final int objectId;
        public final int duration;
        final boolean members;
        final Skill[] skills;
        final int[] levels;
        final Quest[] quests;
        final int[][] varbits; // {id, op, value}; op: 0 '=', 1 '>', 2 '<', 3 '&'
        final int[][] varps;

        private Transport(int[] o, int[] d, String option, String target, int objectId, int duration, boolean members,
                Skill[] skills, int[] levels, Quest[] quests, int[][] varbits, int[][] varps)
        {
                this.originX = o[0];
                this.originY = o[1];
                this.originZ = o[2];
                this.destX = d[0];
                this.destY = d[1];
                this.destZ = d[2];
                this.option = option;
                this.target = target;
                this.objectId = objectId;
                this.duration = Math.max(1, duration);
                this.members = members;
                this.skills = skills;
                this.levels = levels;
                this.quests = quests;
                this.varbits = varbits;
                this.varps = varps;
        }

        public WorldPoint origin()
        {
                return new WorldPoint(originX, originY, originZ);
        }

        public WorldPoint destination()
        {
                return new WorldPoint(destX, destY, destZ);
        }

        @Override
        public String toString()
        {
                return option + " " + target + " (" + objectId + ") " + originX + "," + originY + "," + originZ
                        + " -> " + destX + "," + destY + "," + destZ;
        }

        // ------------------------------------------------------------------ loading

        private static volatile List<Transport> all;

        /** Every loaded transport (parsed once from resources). */
        public static List<Transport> all()
        {
                List<Transport> a = all;
                if (a == null)
                {
                        synchronized (Transport.class)
                        {
                                a = all;
                                if (a == null)
                                {
                                        a = load();
                                        all = a;
                                }
                        }
                }
                return a;
        }

        private static List<Transport> load()
        {
                List<Transport> out = new ArrayList<>();
                Map<String, Quest> questsByName = new HashMap<>();
                for (Quest q : Quest.values())
                {
                        questsByName.put(q.getName().toLowerCase(Locale.ROOT), q);
                }
                InputStream raw = Transport.class.getResourceAsStream("transports.tsv");
                if (raw == null)
                {
                        Log.e(TAG, "transports.tsv missing from resources");
                        return Collections.emptyList();
                }
                int skipped = 0;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8)))
                {
                        String line;
                        while ((line = r.readLine()) != null)
                        {
                                if (line.isEmpty() || line.startsWith("#"))
                                {
                                        continue;
                                }
                                String[] c = line.split("\t", -1);
                                try
                                {
                                        Transport t = parse(c, questsByName);
                                        if (t != null)
                                        {
                                                out.add(t);
                                        }
                                        else
                                        {
                                                skipped++;
                                        }
                                }
                                catch (RuntimeException e)
                                {
                                        skipped++;
                                }
                        }
                }
                catch (IOException e)
                {
                        Log.e(TAG, "could not read transports.tsv", e);
                }
                Log.i(TAG, "loaded " + out.size() + " transports (" + skipped + " skipped)");
                return Collections.unmodifiableList(out);
        }

        private static String col(String[] c, int i)
        {
                return i < c.length ? c[i].trim() : "";
        }

        private static int[] point(String s)
        {
                String[] p = s.trim().split("\\s+");
                if (p.length < 3)
                {
                        return null;
                }
                return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        }

        private static Transport parse(String[] c, Map<String, Quest> questsByName)
        {
                int[] o = point(col(c, 0));
                int[] d = point(col(c, 1));
                if (o == null || d == null)
                {
                        return null;
                }
                String[] menu = col(c, 2).split(";");
                if (menu.length < 3)
                {
                        return null;
                }
                int objectId = Integer.parseInt(menu[2].trim());
                // Item and coin costs are not handled.
                if (!col(c, 4).isEmpty() || !col(c, 5).isEmpty())
                {
                        return null;
                }

                List<Skill> skills = new ArrayList<>();
                List<Integer> levels = new ArrayList<>();
                for (String part : col(c, 3).split(";"))
                {
                        String p = part.trim();
                        if (p.isEmpty())
                        {
                                continue;
                        }
                        String[] lv = p.split("\\s+", 2);
                        if (lv.length < 2)
                        {
                                return null;
                        }
                        Skill skill;
                        try
                        {
                                skill = Skill.valueOf(lv[1].trim().toUpperCase(Locale.ROOT));
                        }
                        catch (IllegalArgumentException e)
                        {
                                return null; // combat level, total level, ...: not handled
                        }
                        skills.add(skill);
                        levels.add(Integer.parseInt(lv[0].trim()));
                }

                List<Quest> quests = new ArrayList<>();
                for (String part : col(c, 6).split(";"))
                {
                        String p = part.trim();
                        if (p.isEmpty())
                        {
                                continue;
                        }
                        Quest q = questsByName.get(p.toLowerCase(Locale.ROOT));
                        if (q == null)
                        {
                                return null;
                        }
                        quests.add(q);
                }

                int[][] varbits = vars(col(c, 7));
                int[][] varps = vars(col(c, 8));
                if (varbits == null || varps == null)
                {
                        return null;
                }

                boolean members = col(c, 9).equalsIgnoreCase("Y");
                int duration = 1;
                String dur = col(c, 10);
                if (!dur.isEmpty())
                {
                        duration = Integer.parseInt(dur);
                }

                int[] lv = new int[levels.size()];
                for (int i = 0; i < lv.length; i++)
                {
                        lv[i] = levels.get(i);
                }
                return new Transport(o, d, menu[0].trim(), menu[1].trim(), objectId, duration, members,
                        skills.toArray(new Skill[0]), lv, quests.toArray(new Quest[0]), varbits, varps);
        }

        /** "6027>6;5088=1" -> {{6027,1,6},{5088,0,1}}; null when an operator is not understood. */
        private static int[][] vars(String s)
        {
                if (s.isEmpty())
                {
                        return new int[0][];
                }
                String[] parts = s.split(";");
                int[][] out = new int[parts.length][];
                for (int i = 0; i < parts.length; i++)
                {
                        String p = parts[i].trim();
                        int op = -1;
                        int at = -1;
                        String ops = "=><&";
                        for (int k = 0; k < p.length(); k++)
                        {
                                int idx = ops.indexOf(p.charAt(k));
                                if (idx >= 0)
                                {
                                        op = idx;
                                        at = k;
                                        break;
                                }
                        }
                        if (op < 0)
                        {
                                return null;
                        }
                        out[i] = new int[]{Integer.parseInt(p.substring(0, at).trim()), op, Integer.parseInt(p.substring(at + 1).trim())};
                }
                return out;
        }

        // ------------------------------------------------------------------ requirements

        /**
         * Which transports the player can use right now. Client thread only: reads quest
         * states, skill levels and varbits. Quest states are cached for the call.
         */
        public static List<Transport> usable(Client client)
        {
                List<Transport> out = new ArrayList<>();
                EnumSet<WorldType> types = client.getWorldType();
                boolean membersWorld = types != null && types.contains(WorldType.MEMBERS);
                Map<Quest, QuestState> questCache = new HashMap<>();
                for (Transport t : all())
                {
                        if (t.isUsable(client, membersWorld, questCache))
                        {
                                out.add(t);
                        }
                }
                return out;
        }

        private boolean isUsable(Client client, boolean membersWorld, Map<Quest, QuestState> questCache)
        {
                if (members && !membersWorld)
                {
                        return false;
                }
                for (int i = 0; i < skills.length; i++)
                {
                        if (client.getBoostedSkillLevel(skills[i]) < levels[i])
                        {
                                return false;
                        }
                }
                for (Quest q : quests)
                {
                        QuestState st = questCache.get(q);
                        if (st == null)
                        {
                                st = q.getState(client);
                                questCache.put(q, st);
                        }
                        if (st != QuestState.FINISHED)
                        {
                                return false;
                        }
                }
                for (int[] v : varbits)
                {
                        if (!check(client.getVarbitValue(v[0]), v[1], v[2]))
                        {
                                return false;
                        }
                }
                for (int[] v : varps)
                {
                        if (!check(client.getVarpValue(v[0]), v[1], v[2]))
                        {
                                return false;
                        }
                }
                return true;
        }

        private static boolean check(int actual, int op, int value)
        {
                switch (op)
                {
                        case 0:
                                return actual == value;
                        case 1:
                                return actual > value;
                        case 2:
                                return actual < value;
                        case 3:
                                return (actual & value) != 0;
                        default:
                                return false;
                }
        }
}
