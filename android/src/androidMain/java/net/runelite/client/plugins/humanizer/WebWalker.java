package net.runelite.client.plugins.humanizer;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.gamedata.PathFinder;
import net.runelite.client.plugins.gamedata.Transport;
import net.runelite.client.plugins.gamedata.WorldCollision;

/**
 * Walks the player along a real path (ShortestPath collision map + doors, stairs,
 * ladders and shortcuts), with the same simulated finger as everything else: minimap
 * taps a varying distance ahead (the look-ahead rides a sine wave between the
 * Humanizer's min/max so the rhythm never settles), doors / stairs used with a real tap
 * or long-press, re-planning when knocked off the path, stuck, or refused.
 *
 * <p>Call {@link #start} once, then {@link #tick} every game tick on the client thread
 * until it returns {@link #ARRIVED} or {@link #FAILED}.</p>
 */
public final class WebWalker
{
        public static final int ARRIVED = 0;
        public static final int BUSY = 1;
        public static final int FAILED = 2;

        private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(r ->
        {
                Thread t = new Thread(r, "WebWalker-Path");
                t.setDaemon(true);
                return t;
        });

        private final Client client;
        private final HumanInput input;
        private final String tag;

        private volatile PathFinder.Result pathResult;
        private volatile boolean pathPending;
        private volatile int pathGeneration;
        private boolean waitingForMap;
        private List<PathFinder.Step> path;
        private int pathIndex;
        private List<WorldPoint> targets = Collections.emptyList();
        private int arrive;
        private int maxCost;
        private final Set<Transport> banned = new HashSet<>();
        private List<Transport> usable;
        private long usableAt;
        private Transport activeTransport;
        private int transportTicks;
        private TileObject transportObject;
        private WorldPoint walkGoal;
        private WorldPoint lastPos;
        private int stillTicks;
        private int recomputes;
        private double phase;
        private int wait;
        private int reachedTarget = -1;

        public WebWalker(Client client, HumanInput input, String tag)
        {
                this.client = client;
                this.input = input;
                this.tag = tag;
        }

        /** Starts loading the map data in the background (call from a plugin's startUp). */
        public static void preload()
        {
                WorldCollision.startLoading();
                Thread t = new Thread(Transport::all, "WebWalker-Transports");
                t.setDaemon(true);
                t.start();
        }

        /** Walk toward the nearest of {@code targets}; arrive when within {@code arrive} tiles. */
        public void start(List<WorldPoint> targets, int arrive, int maxCost)
        {
                this.targets = new ArrayList<>(targets);
                this.arrive = arrive;
                this.maxCost = maxCost;
                recomputes = 0;
                banned.clear();
                reachedTarget = -1;
                phase = input.random().nextDouble() * Math.PI * 2.0d;
                requestPath();
        }

        public void stop()
        {
                pathGeneration++;
                path = null;
                pathResult = null;
                pathPending = false;
                waitingForMap = false;
                pathIndex = 0;
                activeTransport = null;
                transportObject = null;
                walkGoal = null;
                lastPos = null;
                stillTicks = 0;
                wait = 0;
                targets = Collections.emptyList();
        }

        public boolean isActive()
        {
                return !targets.isEmpty();
        }

        /** Index into the start() targets the path leads to, or -1 while not known yet. */
        public int targetIndex()
        {
                return reachedTarget;
        }

        /** True when the remaining path is at most {@code tiles} long. */
        public boolean nearEnd(int tiles)
        {
                return path != null && path.size() - 1 - pathIndex <= tiles;
        }

        /** The door / staircase currently being used (so scripts can treat it as a live target). */
        public TileObject transportObject()
        {
                return transportObject;
        }

        /**
         * "I can't reach that!" while using a door / staircase: plan around it.
         *
         * @return true when the walker handled it
         */
        public boolean onCantReach()
        {
                if (activeTransport == null)
                {
                        return false;
                }
                banned.add(activeTransport);
                activeTransport = null;
                transportObject = null;
                reroute();
                return true;
        }

        private static int cheb(WorldPoint a, int x, int y)
        {
                return Math.max(Math.abs(a.getX() - x), Math.abs(a.getY() - y));
        }

        private void requestPath()
        {
                path = null;
                pathResult = null;
                pathIndex = 0;
                activeTransport = null;
                transportObject = null;
                walkGoal = null;
                lastPos = null;
                stillTicks = 0;
                Player local = client.getLocalPlayer();
                if (local == null || targets.isEmpty())
                {
                        pathPending = false;
                        return;
                }
                final WorldCollision map = WorldCollision.get();
                if (map == null && (WorldCollision.failed() || ++mapWaitRequests > 40))
                {
                        // No world map: head straight for the closest goal rather than stand still.
                        straightFallback(local.getWorldLocation());
                        return;
                }
                if (map == null)
                {
                        waitingForMap = true;
                        pathPending = true;
                        return;
                }
                waitingForMap = false;
                long now = System.currentTimeMillis();
                if (usable == null || now - usableAt > 300_000L)
                {
                        usable = Transport.usable(client);
                        usableAt = now;
                }
                final List<Transport> ts = usable;
                final WorldPoint from = local.getWorldLocation();
                final List<WorldPoint> goals = new ArrayList<>(targets);
                final int arr = arrive;
                final int max = maxCost;
                final Set<Transport> skip = new HashSet<>(banned);
                final int gen = ++pathGeneration;
                pathPending = true;
                PATH_EXECUTOR.execute(() ->
                {
                        PathFinder.Result r = null;
                        try
                        {
                                r = PathFinder.find(map, ts, from, goals, arr, max, skip);
                        }
                        catch (RuntimeException e)
                        {
                                Log.w(tag, "path search failed", e);
                        }
                        if (gen != pathGeneration)
                        {
                                return;
                        }
                        if (r == null && !targets.isEmpty())
                        {
                                // No route in the map data: fall back to a straight line rather than give up.
                                WorldPoint goal = nearestGoal(from, goals);
                                r = PathFinder.straight(from, goal, goals.indexOf(goal));
                                input.log("no mapped path; walking straight toward " + goal);
                        }
                        pathResult = r;
                        pathPending = false;
                        input.log(r == null ? "no path found" : "path: " + r.steps.size() + " tiles, cost " + r.cost);
                });
        }

        private int mapWaitRequests;

        private static WorldPoint nearestGoal(WorldPoint from, List<WorldPoint> goals)
        {
                WorldPoint best = goals.get(0);
                int bestD = Integer.MAX_VALUE;
                for (WorldPoint g : goals)
                {
                        int d = Math.max(Math.abs(g.getX() - from.getX()), Math.abs(g.getY() - from.getY() % 6400));
                        if (g.getPlane() == from.getPlane() && d < bestD)
                        {
                                best = g;
                                bestD = d;
                        }
                }
                return best;
        }

        private void straightFallback(WorldPoint from)
        {
                waitingForMap = false;
                pathPending = false;
                if (from == null || targets.isEmpty())
                {
                        pathResult = null;
                        return;
                }
                WorldPoint goal = nearestGoal(from, targets);
                pathResult = PathFinder.straight(from, goal, targets.indexOf(goal));
                input.log("world map unavailable; walking straight toward " + goal);
        }

        private int reroute()
        {
                if (++recomputes > 5)
                {
                        return FAILED;
                }
                requestPath();
                return BUSY;
        }

        /** One tick of walking. Client thread. */
        public int tick(Player local)
        {
                if (targets.isEmpty())
                {
                        return FAILED;
                }
                if (wait > 0)
                {
                        wait--;
                        return BUSY;
                }
                if (waitingForMap)
                {
                        if (WorldCollision.get() == null)
                        {
                                return BUSY;
                        }
                        requestPath();
                        return BUSY;
                }
                if (path == null)
                {
                        if (pathPending)
                        {
                                return BUSY;
                        }
                        PathFinder.Result r = pathResult;
                        if (r == null)
                        {
                                return FAILED;
                        }
                        path = r.steps;
                        pathIndex = 0;
                        reachedTarget = r.target;
                }

                WorldPoint here = local.getWorldLocation();
                if (here == null)
                {
                        return BUSY;
                }
                boolean moving = client.getLocalDestinationLocation() != null;

                int best = -1;
                int bestD = Integer.MAX_VALUE;
                int from = Math.max(0, pathIndex - 3);
                int to = Math.min(path.size() - 1, pathIndex + 40);
                for (int i = from; i <= to; i++)
                {
                        PathFinder.Step st = path.get(i);
                        if (st.z != here.getPlane())
                        {
                                continue;
                        }
                        int d = cheb(here, st.x, st.y);
                        if (d < bestD || (d == bestD && i > best))
                        {
                                best = i;
                                bestD = d;
                        }
                }
                if (best < 0 || bestD > 3)
                {
                        if (activeTransport != null && transportTicks++ < 12)
                        {
                                return BUSY; // climbing / going through
                        }
                        if (activeTransport != null)
                        {
                                banned.add(activeTransport);
                                activeTransport = null;
                        }
                        if (moving)
                        {
                                return BUSY;
                        }
                        return reroute();
                }
                pathIndex = best;
                if (activeTransport != null && transportTicks++ > 2)
                {
                        activeTransport = null;
                        transportObject = null;
                }

                PathFinder.Step last = path.get(path.size() - 1);
                if (last.z == here.getPlane() && cheb(here, last.x, last.y) <= Math.max(1, arrive))
                {
                        return ARRIVED;
                }

                if (lastPos != null && lastPos.equals(here))
                {
                        stillTicks++;
                }
                else
                {
                        stillTicks = 0;
                        lastPos = here;
                }
                if (stillTicks >= 8 && !moving)
                {
                        stillTicks = 0;
                        return reroute();
                }
                if (input.isBusy() || activeTransport != null)
                {
                        return BUSY;
                }

                // A door, staircase or ladder coming up?
                int t = -1;
                for (int i = pathIndex + 1; i < path.size() && i <= pathIndex + 14; i++)
                {
                        if (path.get(i).via != null)
                        {
                                t = i;
                                break;
                        }
                }
                int limit = path.size() - 1;
                if (t >= 0)
                {
                        Transport tr = path.get(t).via;
                        TileObject obj = findTransportObject(tr);
                        if (obj != null)
                        {
                                limit = t - 1;
                                if (t - pathIndex <= 6 || cheb(here, tr.originX, tr.originY) <= 5)
                                {
                                        final TileObject door = obj;
                                        transportObject = door;
                                        Target target = Targets.object(door, () -> door == transportObject);
                                        boolean def = GameHelpers.objectDefaultAction(client, door, tr.option);
                                        if (input.interact(target, tr.option, def, false, false))
                                        {
                                                activeTransport = tr;
                                                transportTicks = 0;
                                                walkGoal = null;
                                                wait = 1 + input.random().nextInt(2);
                                                input.log("transport: " + tr);
                                        }
                                        return BUSY;
                                }
                        }
                        else if (path.get(t).z != here.getPlane())
                        {
                                limit = t - 1;
                                if (cheb(here, tr.originX, tr.originY) <= 2 && !moving)
                                {
                                        // Stairs / ladder not where the map says: plan around it.
                                        banned.add(tr);
                                        return reroute();
                                }
                        }
                        // Same plane and no object found (door already open): walk straight through.
                }

                if (moving && walkGoal != null && cheb(here, walkGoal.getX(), walkGoal.getY()) > 2 + input.random().nextInt(3))
                {
                        return BUSY;
                }
                if (!moving && walkGoal != null && stillTicks < 2)
                {
                        return BUSY;
                }

                HumanizerConfig cfg = input.config();
                int lo = Math.max(3, cfg.walkAheadMin());
                int hi = Math.max(lo, cfg.walkAheadMax());
                phase += 0.7d + input.random().nextDouble() * 0.8d;
                double wave = (Math.sin(phase) + 1.0d) / 2.0d;
                int look = lo + (int) Math.round((hi - lo) * wave) + input.random().nextInt(2);
                for (int j = Math.min(limit, pathIndex + look); j > pathIndex; j--)
                {
                        PathFinder.Step st = path.get(j);
                        if (st.z != here.getPlane())
                        {
                                continue;
                        }
                        int[] pt = input.minimapPoint(st.x, st.y);
                        if (pt == null)
                        {
                                continue;
                        }
                        if (input.tap(pt, "walk"))
                        {
                                walkGoal = st.point();
                                wait = 1 + input.random().nextInt(2);
                        }
                        return BUSY;
                }
                return BUSY;
        }

        /** The door / staircase / ladder a transport uses, near its origin tile; null when not there. */
        private TileObject findTransportObject(Transport tr)
        {
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null || wv.getPlane() != tr.originZ)
                {
                        return null;
                }
                LocalPoint lp = LocalPoint.fromWorld(wv, tr.originX, tr.originY);
                if (lp == null)
                {
                        return null;
                }
                Scene scene = wv.getScene();
                Tile[][][] tiles = scene == null ? null : scene.getTiles();
                if (tiles == null || tr.originZ >= tiles.length)
                {
                        return null;
                }
                Tile[][] layer = tiles[tr.originZ];
                TileObject byName = null;
                int cx = lp.getSceneX();
                int cy = lp.getSceneY();
                for (int x = Math.max(0, cx - 2); x <= Math.min(layer.length - 1, cx + 2); x++)
                {
                        for (int y = Math.max(0, cy - 2); y <= Math.min(layer[x].length - 1, cy + 2); y++)
                        {
                                Tile tile = layer[x][y];
                                if (tile == null)
                                {
                                        continue;
                                }
                                List<TileObject> objs = new ArrayList<>(6);
                                GameObject[] gos = tile.getGameObjects();
                                if (gos != null)
                                {
                                        for (GameObject g : gos)
                                        {
                                                if (g != null)
                                                {
                                                        objs.add(g);
                                                }
                                        }
                                }
                                WallObject wall = tile.getWallObject();
                                if (wall != null)
                                {
                                        objs.add(wall);
                                }
                                DecorativeObject deco = tile.getDecorativeObject();
                                if (deco != null)
                                {
                                        objs.add(deco);
                                }
                                GroundObject ground = tile.getGroundObject();
                                if (ground != null)
                                {
                                        objs.add(ground);
                                }
                                for (TileObject o : objs)
                                {
                                        ObjectComposition comp = GameHelpers.composition(client, o.getId());
                                        if (comp == null || !GameHelpers.hasAction(comp.getActions(), tr.option))
                                        {
                                                continue;
                                        }
                                        if (o.getId() == tr.objectId)
                                        {
                                                return o;
                                        }
                                        if (byName == null && tr.target.equalsIgnoreCase(GameHelpers.name(comp)))
                                        {
                                                byName = o;
                                        }
                                }
                        }
                }
                return byName;
        }
}
