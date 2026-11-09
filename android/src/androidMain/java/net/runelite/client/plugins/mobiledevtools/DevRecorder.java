package net.runelite.client.plugins.mobiledevtools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * Records everything within {@value #RADIUS} tiles of the player, tick by tick, to a JSON
 * Lines file (one JSON object per line), so an encounter can be shared and every ID read
 * back out of it afterwards: the player, every NPC (ID, name, tile, size, animations, spot
 * anims, target, health), other players (anonymised), projectiles (ID, from, to, target),
 * graphics objects, ground items, every object that appears or disappears, hitsplats,
 * deaths, chat and overhead text, sounds, varbits, plus a walkability snapshot of the area
 * at the start. Stopping writes a summary of every unique ID seen and opens the share sheet.
 *
 * <p>Snapshots are built on the client thread and written on a background thread.</p>
 */
public final class DevRecorder
{
        public static final int RADIUS = 20;
        private static final String TAG = "DevRecorder";
        private static final long MAX_BYTES = 150L * 1024 * 1024;

        public static final DevRecorder INSTANCE = new DevRecorder();

        private final ExecutorService writer = Executors.newSingleThreadExecutor(r ->
        {
                Thread t = new Thread(r, "DevRecorder-Write");
                t.setDaemon(true);
                return t;
        });

        /** Set from the UI (any thread); acted on by the plugin on the next game tick. */
        private volatile boolean wantRecording;
        private volatile boolean recording;
        private volatile long startedAt;
        private volatile long bytes;
        private volatile String lastFile;
        private volatile String lastError;

        private BufferedWriter out;
        private File file;
        private int tick;

        // ---- summary (client thread) ----
        private final Map<Integer, String> npcs = new TreeMap<>();
        private final Map<Integer, TreeSet<Integer>> npcAnims = new TreeMap<>();
        private final Map<Integer, TreeSet<Integer>> npcSpotAnims = new TreeMap<>();
        private final TreeSet<Integer> projectiles = new TreeSet<>();
        private final TreeSet<Integer> graphics = new TreeSet<>();
        private final Map<Integer, String> objects = new TreeMap<>();
        private final TreeSet<Integer> items = new TreeSet<>();
        private final TreeSet<Integer> sounds = new TreeSet<>();
        private final TreeSet<Integer> playerAnims = new TreeSet<>();
        private final TreeSet<Integer> playerSpotAnims = new TreeSet<>();
        private final Map<Integer, TreeSet<Long>> varbits = new TreeMap<>();
        private final TreeSet<Integer> regions = new TreeSet<>();
        private final Map<String, Integer> damageTiles = new TreeMap<>();

        private DevRecorder()
        {
        }

        // ------------------------------------------------------------------ controls (any thread)

        public void requestStart()
        {
                wantRecording = true;
        }

        public void requestStop()
        {
                wantRecording = false;
        }

        /** Stop immediately (plugin shutting down): save and share what was recorded. */
        public void stopNow()
        {
                wantRecording = false;
                if (recording)
                {
                        finish();
                }
        }

        public boolean isRecording()
        {
                return recording;
        }

        public boolean isRequested()
        {
                return wantRecording;
        }

        public long elapsedSeconds()
        {
                return recording ? (System.currentTimeMillis() - startedAt) / 1000L : 0L;
        }

        public long bytesWritten()
        {
                return bytes;
        }

        public String lastFile()
        {
                return lastFile;
        }

        public String lastError()
        {
                return lastError;
        }

        /** Re-opens the share sheet for the last finished recording. */
        public void shareLast()
        {
                String f = lastFile;
                if (f != null)
                {
                        share(new File(f), new File(f.replace(".jsonl", "-summary.json")));
                }
        }

        // ------------------------------------------------------------------ plugin hooks (client thread)

        /** Called every game tick by the plugin: starts/stops on request, then snapshots the area. */
        public void onTick(Client client)
        {
                if (wantRecording && !recording)
                {
                        begin(client);
                }
                else if (!wantRecording && recording)
                {
                        finish();
                        return;
                }
                if (!recording)
                {
                        return;
                }
                if (bytes > MAX_BYTES)
                {
                        wantRecording = false;
                        finish();
                        return;
                }
                tick++;
                snapshot(client);
        }

        private static Context context()
        {
                try
                {
                        return (Context) Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                }
                catch (Exception e)
                {
                        return null;
                }
        }

        private void begin(Client client)
        {
                Context ctx = context();
                if (ctx == null)
                {
                        lastError = "No app context";
                        wantRecording = false;
                        return;
                }
                File dir = new File(ctx.getCacheDir(), "recordings");
                if (!dir.isDirectory() && !dir.mkdirs())
                {
                        lastError = "Cannot create " + dir;
                        wantRecording = false;
                        return;
                }
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                file = new File(dir, "recording-" + stamp + ".jsonl");
                try
                {
                        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8), 1 << 16);
                }
                catch (IOException e)
                {
                        lastError = e.getMessage();
                        wantRecording = false;
                        return;
                }
                clearSummary();
                tick = 0;
                bytes = 0;
                startedAt = System.currentTimeMillis();
                lastError = null;
                recording = true;
                Log.i(TAG, "recording to " + file);

                StringBuilder sb = obj("header");
                num(sb, "version", 1);
                num(sb, "radius", RADIUS);
                str(sb, "started", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
                str(sb, "format", "one JSON object per line; 't' = record type, 'tick' = game tick since start");
                write(close(sb));
                writeCollision(client);
                writeAllObjects(client);
                writeAllGroundItems(client);
        }

        private void finish()
        {
                recording = false;
                final File f = file;
                final String summary = buildSummary();
                final BufferedWriter w = out;
                out = null;
                writer.execute(() ->
                {
                        try
                        {
                                if (w != null)
                                {
                                        w.write("{\"t\":\"end\",\"ticks\":" + tick + "}\n");
                                        w.close();
                                }
                                File s = new File(f.getPath().replace(".jsonl", "-summary.json"));
                                try (BufferedWriter sw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(s), StandardCharsets.UTF_8)))
                                {
                                        sw.write(summary);
                                }
                                lastFile = f.getPath();
                                Log.i(TAG, "recording saved: " + f + " (" + bytes + " bytes)");
                                share(f, s);
                        }
                        catch (IOException e)
                        {
                                lastError = e.getMessage();
                                Log.e(TAG, "could not finish recording", e);
                        }
                });
        }

        private void share(File recording, File summary)
        {
                Context ctx = context();
                if (ctx == null || recording == null || !recording.exists())
                {
                        return;
                }
                try
                {
                        String authority = ctx.getPackageName() + ".updates";
                        ArrayList<Uri> uris = new ArrayList<>();
                        uris.add(FileProvider.getUriForFile(ctx, authority, recording));
                        if (summary != null && summary.exists())
                        {
                                uris.add(FileProvider.getUriForFile(ctx, authority, summary));
                        }
                        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        send.setType("text/plain");
                        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        send.putExtra(Intent.EXTRA_SUBJECT, "RuneLite dev recording " + recording.getName());
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(send, "Share recording");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        ctx.startActivity(chooser);
                }
                catch (RuntimeException e)
                {
                        lastError = "Share failed: " + e.getMessage();
                        Log.e(TAG, "share failed", e);
                }
        }

        private void write(String line)
        {
                final BufferedWriter w = out;
                if (w == null)
                {
                        return;
                }
                bytes += line.length() + 1;
                writer.execute(() ->
                {
                        try
                        {
                                w.write(line);
                                w.write('\n');
                        }
                        catch (IOException e)
                        {
                                lastError = e.getMessage();
                        }
                });
        }

        // ------------------------------------------------------------------ per tick

        private boolean near(WorldPoint me, WorldPoint p)
        {
                return me != null && p != null && p.getPlane() == me.getPlane()
                        && Math.abs(p.getX() - me.getX()) <= RADIUS && Math.abs(p.getY() - me.getY()) <= RADIUS;
        }

        /** Real (template) world point for instanced areas, so coordinates match between visits. */
        private static WorldPoint real(Client client, LocalPoint lp)
        {
                if (lp == null)
                {
                        return null;
                }
                try
                {
                        return WorldPoint.fromLocalInstance(client, lp);
                }
                catch (RuntimeException e)
                {
                        return null;
                }
        }

        private void snapshot(Client client)
        {
                Player me = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (me == null || wv == null)
                {
                        return;
                }
                WorldPoint here = me.getWorldLocation();
                WorldPoint hereReal = real(client, me.getLocalLocation());
                if (hereReal != null)
                {
                        regions.add(hereReal.getRegionID());
                }

                StringBuilder sb = obj("tick");
                num(sb, "tick", tick);
                num(sb, "ms", System.currentTimeMillis() - startedAt);
                num(sb, "cycle", client.getGameCycle());
                bool(sb, "instance", client.isInInstancedRegion());

                // ---- player
                sb.append(",\"player\":{");
                point(sb, "pos", here);
                sb.append(',');
                point(sb, "real", hereReal);
                num(sb, "anim", me.getAnimation());
                num(sb, "pose", me.getPoseAnimation());
                spotAnims(sb, me, playerSpotAnims);
                num(sb, "hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
                num(sb, "maxHp", client.getRealSkillLevel(Skill.HITPOINTS));
                num(sb, "prayer", client.getBoostedSkillLevel(Skill.PRAYER));
                num(sb, "run", client.getEnergy() / 100);
                interacting(sb, me.getInteracting());
                activePrayers(sb, client);
                sb.append('}');
                if (me.getAnimation() != -1)
                {
                        playerAnims.add(me.getAnimation());
                }

                // ---- NPCs
                sb.append(",\"npcs\":[");
                boolean first = true;
                for (NPC npc : wv.npcs())
                {
                        if (npc == null || !near(here, npc.getWorldLocation()))
                        {
                                continue;
                        }
                        NPCComposition comp = npc.getTransformedComposition();
                        String name = comp != null ? comp.getName() : npc.getName();
                        name = name == null ? "" : Text.removeTags(name);
                        npcs.put(npc.getId(), name);
                        if (npc.getAnimation() != -1)
                        {
                                npcAnims.computeIfAbsent(npc.getId(), k -> new TreeSet<>()).add(npc.getAnimation());
                        }
                        if (!first)
                        {
                                sb.append(',');
                        }
                        first = false;
                        sb.append('{');
                        sb.append("\"i\":").append(npc.getIndex());
                        num(sb, "id", npc.getId());
                        str(sb, "name", name);
                        num(sb, "lvl", npc.getCombatLevel());
                        num(sb, "size", comp == null ? 1 : comp.getSize());
                        sb.append(',');
                        point(sb, "pos", npc.getWorldLocation());
                        sb.append(',');
                        point(sb, "real", real(client, npc.getLocalLocation()));
                        num(sb, "orient", npc.getOrientation());
                        num(sb, "anim", npc.getAnimation());
                        num(sb, "pose", npc.getPoseAnimation());
                        TreeSet<Integer> spots = npcSpotAnims.computeIfAbsent(npc.getId(), k -> new TreeSet<>());
                        spotAnims(sb, npc, spots);
                        interacting(sb, npc.getInteracting());
                        num(sb, "hpRatio", npc.getHealthRatio());
                        num(sb, "hpScale", npc.getHealthScale());
                        bool(sb, "dead", npc.isDead());
                        if (npc.getOverheadText() != null)
                        {
                                str(sb, "says", npc.getOverheadText());
                        }
                        sb.append('}');
                }
                sb.append(']');

                // ---- other players (anonymised)
                sb.append(",\"players\":[");
                first = true;
                for (Player p : wv.players())
                {
                        if (p == null || p == me || !near(here, p.getWorldLocation()))
                        {
                                continue;
                        }
                        if (!first)
                        {
                                sb.append(',');
                        }
                        first = false;
                        sb.append('{');
                        sb.append("\"i\":").append(p.getId());
                        sb.append(',');
                        point(sb, "pos", p.getWorldLocation());
                        num(sb, "anim", p.getAnimation());
                        spotAnims(sb, p, null);
                        interacting(sb, p.getInteracting());
                        sb.append('}');
                }
                sb.append(']');

                // ---- projectiles
                sb.append(",\"projectiles\":[");
                first = true;
                TreeSet<String> danger = new TreeSet<>();
                for (Projectile pr : client.getProjectiles())
                {
                        if (pr == null)
                        {
                                continue;
                        }
                        WorldPoint to = pr.getTargetPoint();
                        WorldPoint from = pr.getSourcePoint();
                        if (!near(here, to) && !near(here, from))
                        {
                                continue;
                        }
                        projectiles.add(pr.getId());
                        if (!first)
                        {
                                sb.append(',');
                        }
                        first = false;
                        sb.append('{');
                        sb.append("\"id\":").append(pr.getId());
                        sb.append(',');
                        point(sb, "from", from);
                        sb.append(',');
                        point(sb, "to", to);
                        sourceTarget(sb, "src", pr.getSourceActor());
                        sourceTarget(sb, "dst", pr.getTargetActor());
                        num(sb, "startCycle", pr.getStartCycle());
                        num(sb, "endCycle", pr.getEndCycle());
                        num(sb, "remaining", pr.getRemainingCycles());
                        sb.append('}');
                        if (to != null && pr.getTargetActor() == null)
                        {
                                danger.add(to.getX() + "," + to.getY());
                        }
                }
                sb.append(']');

                // ---- graphics objects (spot effects on tiles)
                sb.append(",\"gfx\":[");
                first = true;
                for (GraphicsObject g : wv.getGraphicsObjects())
                {
                        if (g == null)
                        {
                                continue;
                        }
                        WorldPoint at = WorldPoint.fromLocal(client, g.getLocation());
                        if (!near(here, at))
                        {
                                continue;
                        }
                        graphics.add(g.getId());
                        if (!first)
                        {
                                sb.append(',');
                        }
                        first = false;
                        sb.append("{\"id\":").append(g.getId()).append(',');
                        point(sb, "pos", at);
                        num(sb, "startCycle", g.getStartCycle());
                        sb.append('}');
                        danger.add(at.getX() + "," + at.getY());
                }
                sb.append(']');

                // Tiles something lands on this tick (projectile end points with no actor target,
                // graphics objects): the candidates for "dangerous tiles".
                sb.append(",\"danger\":[");
                first = true;
                for (String d : danger)
                {
                        if (!first)
                        {
                                sb.append(',');
                        }
                        first = false;
                        sb.append('"').append(d).append('"');
                }
                sb.append(']');
                write(close(sb));
        }

        private static void activePrayers(StringBuilder sb, Client client)
        {
                sb.append(",\"prayers\":[");
                boolean first = true;
                for (Prayer p : Prayer.values())
                {
                        try
                        {
                                if (client.isPrayerActive(p))
                                {
                                        if (!first)
                                        {
                                                sb.append(',');
                                        }
                                        first = false;
                                        sb.append('"').append(p.name()).append('"');
                                }
                        }
                        catch (RuntimeException ignored)
                        {
                                // prayer not readable on this client
                        }
                }
                sb.append(']');
        }

        // ------------------------------------------------------------------ one-off snapshots

        /** Walkability of the 41x41 area at the start (the four movement-block bits + full block). */
        private void writeCollision(Client client)
        {
                WorldView wv = client.getTopLevelWorldView();
                Player me = client.getLocalPlayer();
                if (wv == null || me == null || me.getLocalLocation() == null)
                {
                        return;
                }
                CollisionData[] maps = wv.getCollisionMaps();
                int plane = wv.getPlane();
                if (maps == null || plane >= maps.length || maps[plane] == null)
                {
                        return;
                }
                int[][] flags = maps[plane].getFlags();
                LocalPoint lp = me.getLocalLocation();
                int cx = lp.getSceneX();
                int cy = lp.getSceneY();
                WorldPoint base = me.getWorldLocation();
                StringBuilder sb = obj("collision");
                str(sb, "about", "flags[dy][dx] for the 41x41 tiles around the start position (CollisionDataFlag bits; 0 = open)");
                sb.append(',');
                point(sb, "origin", new WorldPoint(base.getX() - RADIUS, base.getY() - RADIUS, base.getPlane()));
                sb.append(",\"flags\":[");
                for (int dy = -RADIUS; dy <= RADIUS; dy++)
                {
                        if (dy > -RADIUS)
                        {
                                sb.append(',');
                        }
                        sb.append('[');
                        for (int dx = -RADIUS; dx <= RADIUS; dx++)
                        {
                                if (dx > -RADIUS)
                                {
                                        sb.append(',');
                                }
                                int x = cx + dx;
                                int y = cy + dy;
                                int f = x >= 0 && y >= 0 && x < flags.length && y < flags[x].length ? flags[x][y] : -1;
                                sb.append(f);
                        }
                        sb.append(']');
                }
                sb.append(']');
                write(close(sb));
        }

        private void writeAllObjects(Client client)
        {
                WorldView wv = client.getTopLevelWorldView();
                Player me = client.getLocalPlayer();
                if (wv == null || me == null)
                {
                        return;
                }
                Scene scene = wv.getScene();
                Tile[][][] tiles = scene == null ? null : scene.getTiles();
                if (tiles == null || wv.getPlane() >= tiles.length)
                {
                        return;
                }
                WorldPoint here = me.getWorldLocation();
                for (Tile[] col : tiles[wv.getPlane()])
                {
                        if (col == null)
                        {
                                continue;
                        }
                        for (Tile tile : col)
                        {
                                if (tile == null || !near(here, tile.getWorldLocation()))
                                {
                                        continue;
                                }
                                GameObject[] gos = tile.getGameObjects();
                                if (gos != null)
                                {
                                        for (GameObject g : gos)
                                        {
                                                if (g != null && g.getSceneMinLocation() != null
                                                        && g.getSceneMinLocation().equals(tile.getSceneLocation()))
                                                {
                                                        onObject(client, "game", g, true);
                                                }
                                        }
                                }
                                onObject(client, "wall", tile.getWallObject(), true);
                                onObject(client, "decor", tile.getDecorativeObject(), true);
                                onObject(client, "ground", tile.getGroundObject(), true);
                        }
                }
        }

        private void writeAllGroundItems(Client client)
        {
                WorldView wv = client.getTopLevelWorldView();
                Player me = client.getLocalPlayer();
                if (wv == null || me == null || wv.getScene() == null)
                {
                        return;
                }
                WorldPoint here = me.getWorldLocation();
                for (Tile[] col : wv.getScene().getTiles()[wv.getPlane()])
                {
                        if (col == null)
                        {
                                continue;
                        }
                        for (Tile tile : col)
                        {
                                if (tile == null || tile.getGroundItems() == null || !near(here, tile.getWorldLocation()))
                                {
                                        continue;
                                }
                                for (TileItem item : tile.getGroundItems())
                                {
                                        onItem(client, tile, item, true);
                                }
                        }
                }
        }

        // ------------------------------------------------------------------ events (client thread)

        /** An object spawned (or was there at the start) / despawned. */
        public void onObject(Client client, String kind, TileObject obj, boolean present)
        {
                if (!recording || obj == null)
                {
                        return;
                }
                Player me = client.getLocalPlayer();
                if (me == null || !near(me.getWorldLocation(), obj.getWorldLocation()))
                {
                        return;
                }
                ObjectComposition comp = client.getObjectDefinition(obj.getId());
                String name = comp == null ? "" : Text.removeTags(String.valueOf(comp.getName()));
                objects.put(obj.getId(), name);
                StringBuilder sb = obj(present ? "object+" : "object-");
                num(sb, "tick", tick);
                str(sb, "kind", kind);
                num(sb, "id", obj.getId());
                str(sb, "name", name);
                sb.append(',');
                point(sb, "pos", obj.getWorldLocation());
                if (comp != null && comp.getActions() != null)
                {
                        sb.append(",\"actions\":[");
                        boolean first = true;
                        for (String a : comp.getActions())
                        {
                                if (a == null)
                                {
                                        continue;
                                }
                                if (!first)
                                {
                                        sb.append(',');
                                }
                                first = false;
                                sb.append('"').append(esc(Text.removeTags(a))).append('"');
                        }
                        sb.append(']');
                }
                if (obj instanceof GameObject)
                {
                        num(sb, "sizeX", ((GameObject) obj).sizeX());
                        num(sb, "sizeY", ((GameObject) obj).sizeY());
                }
                write(close(sb));
        }

        public void onItem(Client client, Tile tile, TileItem item, boolean present)
        {
                if (!recording || item == null || tile == null)
                {
                        return;
                }
                Player me = client.getLocalPlayer();
                if (me == null || !near(me.getWorldLocation(), tile.getWorldLocation()))
                {
                        return;
                }
                items.add(item.getId());
                StringBuilder sb = obj(present ? "item+" : "item-");
                num(sb, "tick", tick);
                num(sb, "id", item.getId());
                num(sb, "qty", item.getQuantity());
                sb.append(',');
                point(sb, "pos", tile.getWorldLocation());
                write(close(sb));
        }

        public void onHitsplat(Client client, Actor actor, int amount, int type)
        {
                if (!recording || actor == null)
                {
                        return;
                }
                StringBuilder sb = obj("hit");
                num(sb, "tick", tick);
                actorRef(sb, "on", actor, client);
                num(sb, "amount", amount);
                num(sb, "type", type);
                sb.append(',');
                point(sb, "pos", actor.getWorldLocation());
                write(close(sb));
                if (actor == client.getLocalPlayer() && amount > 0 && actor.getWorldLocation() != null)
                {
                        WorldPoint p = actor.getWorldLocation();
                        damageTiles.merge(p.getX() + "," + p.getY() + "," + p.getPlane(), amount, Integer::sum);
                }
        }

        public void onDeath(Client client, Actor actor)
        {
                if (!recording || actor == null)
                {
                        return;
                }
                StringBuilder sb = obj("death");
                num(sb, "tick", tick);
                actorRef(sb, "who", actor, client);
                sb.append(',');
                point(sb, "pos", actor.getWorldLocation());
                write(close(sb));
        }

        public void onText(String channel, Actor actor, String text, Client client)
        {
                if (!recording || text == null)
                {
                        return;
                }
                StringBuilder sb = obj("text");
                num(sb, "tick", tick);
                str(sb, "channel", channel);
                if (actor != null)
                {
                        actorRef(sb, "who", actor, client);
                }
                str(sb, "text", Text.removeTags(text));
                write(close(sb));
        }

        public void onSound(Client client, int soundId, Actor source, int sceneX, int sceneY, boolean area)
        {
                if (!recording)
                {
                        return;
                }
                sounds.add(soundId);
                StringBuilder sb = obj("sound");
                num(sb, "tick", tick);
                num(sb, "id", soundId);
                bool(sb, "area", area);
                if (source != null)
                {
                        actorRef(sb, "from", source, client);
                }
                write(close(sb));
        }

        public void onVarbit(int varbitId, int varpId, long value)
        {
                if (!recording)
                {
                        return;
                }
                if (varbitId != -1)
                {
                        TreeSet<Long> seen = varbits.computeIfAbsent(varbitId, k -> new TreeSet<>());
                        if (seen.size() < 64)
                        {
                                seen.add(value);
                        }
                }
                StringBuilder sb = obj("var");
                num(sb, "tick", tick);
                num(sb, "varbit", varbitId);
                num(sb, "varp", varpId);
                sb.append(",\"value\":").append(value);
                write(close(sb));
        }

        // ------------------------------------------------------------------ summary

        private void clearSummary()
        {
                npcs.clear();
                npcAnims.clear();
                npcSpotAnims.clear();
                projectiles.clear();
                graphics.clear();
                objects.clear();
                items.clear();
                sounds.clear();
                playerAnims.clear();
                playerSpotAnims.clear();
                varbits.clear();
                regions.clear();
                damageTiles.clear();
        }

        private String buildSummary()
        {
                StringBuilder sb = new StringBuilder("{\n");
                sb.append("  \"ticks\": ").append(tick).append(",\n");
                sb.append("  \"regions\": ").append(regions).append(",\n");
                sb.append("  \"npcs\": {");
                boolean first = true;
                for (Map.Entry<Integer, String> e : npcs.entrySet())
                {
                        sb.append(first ? "\n" : ",\n");
                        first = false;
                        sb.append("    \"").append(e.getKey()).append("\": {\"name\": \"").append(esc(e.getValue())).append("\", \"anims\": ")
                                .append(npcAnims.getOrDefault(e.getKey(), new TreeSet<>()))
                                .append(", \"spotAnims\": ").append(npcSpotAnims.getOrDefault(e.getKey(), new TreeSet<>())).append('}');
                }
                sb.append("\n  },\n");
                sb.append("  \"projectiles\": ").append(projectiles).append(",\n");
                sb.append("  \"graphicsObjects\": ").append(graphics).append(",\n");
                sb.append("  \"playerAnims\": ").append(playerAnims).append(",\n");
                sb.append("  \"playerSpotAnims\": ").append(playerSpotAnims).append(",\n");
                sb.append("  \"groundItems\": ").append(items).append(",\n");
                sb.append("  \"sounds\": ").append(sounds).append(",\n");
                sb.append("  \"objects\": {");
                first = true;
                for (Map.Entry<Integer, String> e : objects.entrySet())
                {
                        sb.append(first ? "\n" : ",\n");
                        first = false;
                        sb.append("    \"").append(e.getKey()).append("\": \"").append(esc(e.getValue())).append('"');
                }
                sb.append("\n  },\n");
                sb.append("  \"varbits\": {");
                first = true;
                for (Map.Entry<Integer, TreeSet<Long>> e : varbits.entrySet())
                {
                        sb.append(first ? "\n" : ",\n");
                        first = false;
                        sb.append("    \"").append(e.getKey()).append("\": ").append(e.getValue());
                }
                sb.append("\n  },\n");
                sb.append("  \"damageTakenByTile\": {");
                first = true;
                for (Map.Entry<String, Integer> e : damageTiles.entrySet())
                {
                        sb.append(first ? "\n" : ",\n");
                        first = false;
                        sb.append("    \"").append(e.getKey()).append("\": ").append(e.getValue());
                }
                sb.append("\n  }\n}\n");
                return sb.toString();
        }

        // ------------------------------------------------------------------ tiny JSON helpers

        private static StringBuilder obj(String type)
        {
                return new StringBuilder(256).append("{\"t\":\"").append(type).append('"');
        }

        private static String close(StringBuilder sb)
        {
                return sb.append('}').toString();
        }

        private static void num(StringBuilder sb, String k, long v)
        {
                sb.append(",\"").append(k).append("\":").append(v);
        }

        private static void bool(StringBuilder sb, String k, boolean v)
        {
                sb.append(",\"").append(k).append("\":").append(v);
        }

        private static void str(StringBuilder sb, String k, String v)
        {
                sb.append(",\"").append(k).append("\":\"").append(esc(v)).append('"');
        }

        /** Appends "k":[x,y,plane] (or null) — callers add the leading comma. */
        private static void point(StringBuilder sb, String k, WorldPoint p)
        {
                sb.append('"').append(k).append("\":");
                if (p == null)
                {
                        sb.append("null");
                }
                else
                {
                        sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getPlane()).append(']');
                }
        }

        private static void spotAnims(StringBuilder sb, Actor a, TreeSet<Integer> collect)
        {
                sb.append(",\"spot\":[");
                boolean first = true;
                try
                {
                        for (ActorSpotAnim s : a.getSpotAnims())
                        {
                                if (s == null)
                                {
                                        continue;
                                }
                                if (!first)
                                {
                                        sb.append(',');
                                }
                                first = false;
                                sb.append(s.getId());
                                if (collect != null)
                                {
                                        collect.add(s.getId());
                                }
                        }
                }
                catch (RuntimeException ignored)
                {
                        // spot anim table not readable
                }
                sb.append(']');
        }

        private static void interacting(StringBuilder sb, Actor target)
        {
                if (target instanceof NPC)
                {
                        sb.append(",\"target\":{\"npc\":").append(((NPC) target).getIndex()).append(",\"id\":").append(((NPC) target).getId()).append('}');
                }
                else if (target instanceof Player)
                {
                        sb.append(",\"target\":{\"player\":").append(((Player) target).getId()).append('}');
                }
        }

        private static void sourceTarget(StringBuilder sb, String k, Actor a)
        {
                if (a instanceof NPC)
                {
                        sb.append(",\"").append(k).append("\":{\"npc\":").append(((NPC) a).getIndex()).append(",\"id\":").append(((NPC) a).getId()).append('}');
                }
                else if (a instanceof Player)
                {
                        sb.append(",\"").append(k).append("\":{\"player\":").append(((Player) a).getId()).append('}');
                }
        }

        private static void actorRef(StringBuilder sb, String k, Actor a, Client client)
        {
                if (a == client.getLocalPlayer())
                {
                        sb.append(",\"").append(k).append("\":\"me\"");
                }
                else
                {
                        sourceTarget(sb, k, a);
                }
        }

        private static String esc(String s)
        {
                if (s == null)
                {
                        return "";
                }
                StringBuilder b = new StringBuilder(s.length() + 8);
                for (int i = 0; i < s.length(); i++)
                {
                        char c = s.charAt(i);
                        switch (c)
                        {
                                case '"':
                                        b.append("\\\"");
                                        break;
                                case '\\':
                                        b.append("\\\\");
                                        break;
                                case '\n':
                                        b.append("\\n");
                                        break;
                                case '\r':
                                        b.append("\\r");
                                        break;
                                case '\t':
                                        b.append("\\t");
                                        break;
                                default:
                                        if (c < 0x20)
                                        {
                                                b.append(String.format("\\u%04x", (int) c));
                                        }
                                        else
                                        {
                                                b.append(c);
                                        }
                        }
                }
                return b.toString();
        }
}
