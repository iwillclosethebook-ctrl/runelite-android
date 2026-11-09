package net.runelite.client.plugins.gamedata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * Shortest walk over the whole-world collision map, including doors, stairs, ladders and
 * shortcuts from {@link Transport}. Plain Dijkstra: a step costs 1 (diagonals too), a
 * transport costs its duration. Pure computation — safe to run off the client thread.
 */
public final class PathFinder
{
        private PathFinder()
        {
        }

        /** One tile of a path; {@code via} is the transport used to arrive here (null = walked). */
        public static final class Step
        {
                public final int x;
                public final int y;
                public final int z;
                public final Transport via;

                Step(int x, int y, int z, Transport via)
                {
                        this.x = x;
                        this.y = y;
                        this.z = z;
                        this.via = via;
                }

                public WorldPoint point()
                {
                        return new WorldPoint(x, y, z);
                }
        }

        public static final class Result
        {
                /** From the start tile (index 0) to the arrival tile. */
                public final List<Step> steps;
                /** Index into the targets list that was reached. */
                public final int target;
                public final int cost;

                Result(List<Step> steps, int target, int cost)
                {
                        this.steps = steps;
                        this.target = target;
                        this.cost = cost;
                }
        }

        private static int key(int x, int y, int z)
        {
                return (x & 0x3FFF) | ((y & 0x3FFF) << 14) | ((z & 0x3) << 28);
        }

        private static int kx(int k)
        {
                return k & 0x3FFF;
        }

        private static int ky(int k)
        {
                return (k >>> 14) & 0x3FFF;
        }

        private static int kz(int k)
        {
                return (k >>> 28) & 0x3;
        }

        private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
        private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

        /**
         * Finds the cheapest path from {@code start} to within {@code arrive} tiles
         * (Chebyshev, same plane) of any of {@code targets}.
         *
         * @param transports transports the player can use (see {@link Transport#usable})
         * @param maxCost    give up beyond this many steps
         * @param banned     transports to ignore (ones that failed in game); may be null
         * @return the path, or null when none is found within {@code maxCost}
         */
        public static Result find(WorldCollision map, List<Transport> transports, WorldPoint start,
                List<WorldPoint> targets, int arrive, int maxCost, Set<Transport> banned)
        {
                if (map == null || start == null || targets.isEmpty() || !map.hasRegion(start.getX(), start.getY()))
                {
                        return null;
                }

                Map<Integer, Integer> goal = new HashMap<>();
                for (int i = 0; i < targets.size(); i++)
                {
                        WorldPoint t = targets.get(i);
                        for (int dx = -arrive; dx <= arrive; dx++)
                        {
                                for (int dy = -arrive; dy <= arrive; dy++)
                                {
                                        goal.putIfAbsent(key(t.getX() + dx, t.getY() + dy, t.getPlane()), i);
                                }
                        }
                }

                Map<Integer, List<Transport>> byOrigin = new HashMap<>();
                for (Transport t : transports)
                {
                        if (banned != null && banned.contains(t))
                        {
                                continue;
                        }
                        byOrigin.computeIfAbsent(key(t.originX, t.originY, t.originZ), k -> new ArrayList<>(2)).add(t);
                }

                IntMap nodes = new IntMap(1 << 17);
                PriorityQueue<Long> open = new PriorityQueue<>();
                int s = key(start.getX(), start.getY(), start.getPlane());
                nodes.put(s, 0, -1, null);
                open.add((long) s & 0xFFFFFFFFL);

                while (!open.isEmpty())
                {
                        long e = open.poll();
                        int cost = (int) (e >>> 32);
                        int node = (int) e;
                        if (cost > nodes.cost(node))
                        {
                                continue;
                        }
                        Integer hit = goal.get(node);
                        if (hit != null)
                        {
                                return new Result(rebuild(nodes, node), hit, cost);
                        }
                        if (cost >= maxCost)
                        {
                                continue;
                        }
                        int x = kx(node);
                        int y = ky(node);
                        int z = kz(node);
                        for (int d = 0; d < 8; d++)
                        {
                                if (!map.canStep(x, y, z, DX[d], DY[d]))
                                {
                                        continue;
                                }
                                relax(nodes, open, key(x + DX[d], y + DY[d], z), cost + 1, node, null);
                        }
                        List<Transport> ts = byOrigin.get(node);
                        if (ts != null)
                        {
                                for (Transport t : ts)
                                {
                                        relax(nodes, open, key(t.destX, t.destY, t.destZ), cost + t.duration, node, t);
                                }
                        }
                        if (nodes.size() > 600_000)
                        {
                                return null; // runaway search
                        }
                }
                return null;
        }

        private static void relax(IntMap nodes, PriorityQueue<Long> open, int next, int cost, int parent, Transport via)
        {
                int known = nodes.cost(next);
                if (known >= 0 && known <= cost)
                {
                        return;
                }
                nodes.put(next, cost, parent, via);
                open.add(((long) cost << 32) | (next & 0xFFFFFFFFL));
        }

        private static List<Step> rebuild(IntMap nodes, int end)
        {
                List<Step> out = new ArrayList<>();
                int n = end;
                Set<Integer> guard = new HashSet<>();
                while (n != -1 && guard.add(n))
                {
                        out.add(new Step(kx(n), ky(n), kz(n), nodes.via(n)));
                        n = nodes.parent(n);
                }
                Collections.reverse(out);
                return out;
        }

        /** Open-addressing int -> (cost, parent, via) map; keys are non-negative. */
        private static final class IntMap
        {
                private int[] keys;
                private int[] costs;
                private int[] parents;
                private Transport[] vias;
                private int size;

                IntMap(int capacity)
                {
                        alloc(capacity);
                }

                private void alloc(int capacity)
                {
                        keys = new int[capacity];
                        java.util.Arrays.fill(keys, -1);
                        costs = new int[capacity];
                        parents = new int[capacity];
                        vias = new Transport[capacity];
                        size = 0;
                }

                int size()
                {
                        return size;
                }

                private int slot(int k)
                {
                        int mask = keys.length - 1;
                        int i = (k * 0x9E3779B1) >>> 7 & mask;
                        while (keys[i] != -1 && keys[i] != k)
                        {
                                i = (i + 1) & mask;
                        }
                        return i;
                }

                int cost(int k)
                {
                        int i = slot(k);
                        return keys[i] == -1 ? -1 : costs[i];
                }

                int parent(int k)
                {
                        int i = slot(k);
                        return keys[i] == -1 ? -1 : parents[i];
                }

                Transport via(int k)
                {
                        int i = slot(k);
                        return keys[i] == -1 ? null : vias[i];
                }

                void put(int k, int cost, int parent, Transport via)
                {
                        if ((size + 1) * 2 > keys.length)
                        {
                                grow();
                        }
                        int i = slot(k);
                        if (keys[i] == -1)
                        {
                                keys[i] = k;
                                size++;
                        }
                        costs[i] = cost;
                        parents[i] = parent;
                        vias[i] = via;
                }

                private void grow()
                {
                        int[] ok = keys;
                        int[] oc = costs;
                        int[] op = parents;
                        Transport[] ov = vias;
                        alloc(ok.length * 2);
                        for (int i = 0; i < ok.length; i++)
                        {
                                if (ok[i] != -1)
                                {
                                        int j = slot(ok[i]);
                                        keys[j] = ok[i];
                                        costs[j] = oc[i];
                                        parents[j] = op[i];
                                        vias[j] = ov[i];
                                        size++;
                                }
                        }
                }
        }
}
