package net.runelite.client.plugins.autoagility;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.plugins.humanizer.GameHelpers;
import net.runelite.client.plugins.humanizer.HumanInput;
import net.runelite.client.plugins.humanizer.HumanizerConfig;
import net.runelite.client.plugins.humanizer.SessionLog;
import net.runelite.client.plugins.humanizer.Target;
import net.runelite.client.plugins.humanizer.Targets;
import net.runelite.client.plugins.humanizer.WebWalker;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Rooftop agility (Varrock, Canifis, Seers'), following Microbot's MicroAgility script
 * step for step, with every input through the shared {@link HumanInput} engine:
 *
 * <ol>
 *   <li>eat when HP is at or below the threshold;</li>
 *   <li>pick up a mark of grace on the current roof (reachable, same plane);</li>
 *   <li>on the ground and more than 12 tiles from the start: walk to the start;</li>
 *   <li>find the current obstacle: the course obstacles whose position rule passes for
 *       the player, present in the scene on the player's plane and reachable;</li>
 *   <li>click it only when standing still, or when the XP drop for the last obstacle
 *       has already landed (Microbot's shouldClickObstacle);</li>
 *   <li>wait for completion: XP drop, plane change, HP loss, or 1s standing still after
 *       moving (Microbot's waitForCompletion / isObstacleComplete), 15s timeout.</li>
 * </ol>
 *
 * <p>The XP drop is acted on the instant it arrives (StatChanged), not at the next tick:
 * the next obstacle is tapped straight away, with no reaction pause, and the finger
 * moves at the configured speed (300% by default). No alching.</p>
 */
@PluginDescriptor(
        name = "AutoAgility",
        description = "Rooftop agility (Varrock, Canifis, Seers') with simulated touch input",
        tags = {"agility", "rooftop", "marks", "grace", "mobile", "humanizer", "script"},
        enabledByDefault = false
)
public class AutoAgilityPlugin extends Plugin
{
        private static final String LOG_TAG = "AutoAgility";
        private static final int SCAN_RADIUS = 24;
        private static final long COMPLETION_TIMEOUT_MS = 15000;
        private static final long STILL_DELAY_MS = 1000;

        @Inject
        private Client client;

        @Inject
        private ClientThread clientThread;

        @Inject
        private AutoAgilityConfig config;

        @Inject
        private ConfigManager configManager;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private AutoAgilityOverlay overlay;

        @Inject
        private ItemManager itemManager;

        private HumanInput input;
        private WebWalker walker;
        private final Random random = new Random();

        /** Marks of grace on the ground (tile + world point). */
        private final List<Tile> marks = new ArrayList<>();

        private TileObject currentObstacle;
        private int obstacleIndex = -1;
        private int lastAgilityXp;
        private int laps;
        private String status = "off";

        // completion wait (Microbot's waitForCompletion)
        private boolean waiting;
        private TileObject clicked;
        private int xpAtClick;
        private int planeAtClick;
        private int hpAtClick;
        private long clickedAt;
        private long lastMovingAt;

        private Tile pendingMark;
        private int markWaitTicks;
        private int eatWaitTicks;
        private int walkCooldown;
        private boolean levelWarned;

        @Provides
        AutoAgilityConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoAgilityConfig.class);
        }

        @Override
        protected void startUp()
        {
                SessionLog.INSTANCE.statusSource("AutoAgility", this::getStatus);
                input = new HumanInput(client, clientThread, configManager.getConfig(HumanizerConfig.class), LOG_TAG);
                walker = new WebWalker(client, input, LOG_TAG);
                input.setFocus(() -> currentObstacle != null ? obstacleTarget(currentObstacle) : null,
                        () -> client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation(),
                        () -> SCAN_RADIUS);
                input.setTrainedSkill(Skill.AGILITY);
                applySpeed();
                input.start();
                WebWalker.preload();
                overlayManager.add(overlay);
                clientThread.invoke(() -> lastAgilityXp = client.getSkillExperience(Skill.AGILITY));
        }

        @Override
        protected void shutDown()
        {
                SessionLog.INSTANCE.statusSource("AutoAgility", null);
                input.stop();
                walker.stop();
                overlayManager.remove(overlay);
                marks.clear();
                reset();
        }

        private void reset()
        {
                waiting = false;
                clicked = null;
                currentObstacle = null;
                pendingMark = null;
                markWaitTicks = 0;
                eatWaitTicks = 0;
                levelWarned = false;
        }

        private void applySpeed()
        {
                // HumanInput's base pace is already 1.5x the original.
                input.setSpeedBoost(config.speed() / 100.0d / 1.5d);
        }

        // ---------------------------------------------------------------- events

        @Subscribe
        public void onConfigChanged(ConfigChanged e)
        {
                if (!AutoAgilityConfig.GROUP.equals(e.getGroup()))
                {
                        return;
                }
                if ("speed".equals(e.getKey()))
                {
                        applySpeed();
                }
                if ("autoAgility".equals(e.getKey()) || "course".equals(e.getKey()))
                {
                        clientThread.invoke(() ->
                        {
                                reset();
                                walker.stop();
                                input.reset();
                                lastAgilityXp = client.getSkillExperience(Skill.AGILITY);
                        });
                }
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged e)
        {
                if (e.getGameState() == GameState.LOADING || e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
                {
                        marks.clear();
                }
                if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
                {
                        input.reset();
                        reset();
                }
        }

        @Subscribe
        public void onItemSpawned(ItemSpawned e)
        {
                if (e.getItem().getId() == ItemID.GRACE && !marks.contains(e.getTile()))
                {
                        marks.add(e.getTile());
                }
        }

        @Subscribe
        public void onItemDespawned(ItemDespawned e)
        {
                if (e.getItem().getId() == ItemID.GRACE)
                {
                        marks.remove(e.getTile());
                        if (e.getTile() == pendingMark)
                        {
                                pendingMark = null;
                                markWaitTicks = 0;
                        }
                }
        }

        /** The XP drop: the obstacle is done. Act right now, not at the next tick. */
        @Subscribe
        public void onStatChanged(StatChanged e)
        {
                if (e.getSkill() != Skill.AGILITY || !config.autoAgility())
                {
                        return;
                }
                if (waiting && e.getXp() > xpAtClick)
                {
                        finishWait("xp");
                        if (clicked != null && isLastObstacle(clicked))
                        {
                                laps++;
                        }
                        automate();
                }
        }

        @Subscribe
        public void onClientTick(ClientTick e)
        {
                input.onClientTick();
                // Plane change (climbed up / jumped down) also completes an obstacle: check every frame.
                if (waiting && config.autoAgility() && client.getTopLevelWorldView() != null
                        && client.getTopLevelWorldView().getPlane() != planeAtClick)
                {
                        finishWait("plane");
                        automate();
                }
        }

        @Subscribe
        public void onGameTick(GameTick e)
        {
                Player local = client.getLocalPlayer();
                boolean moving = local != null && (local.getAnimation() != -1 || isMoving(local));
                input.onGameTick(config.autoAgility() && (waiting || moving));
                if (!config.autoAgility())
                {
                        status = "off";
                        updateCurrent();
                        return;
                }
                automate();
        }

        // ---------------------------------------------------------------- the script

        private void automate()
        {
                Player local = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (local == null || wv == null || local.getWorldLocation() == null)
                {
                        return;
                }
                AgilityCourse course = config.course();
                if (client.getRealSkillLevel(Skill.AGILITY) < course.requiredLevel
                        && client.getBoostedSkillLevel(Skill.AGILITY) < course.requiredLevel)
                {
                        status = "need Agility " + course.requiredLevel;
                        if (!levelWarned)
                        {
                                levelWarned = true;
                                input.log("level too low for " + course);
                        }
                        return;
                }

                long now = System.currentTimeMillis();
                if (waiting)
                {
                        if (isMoving(local) || local.getAnimation() != -1)
                        {
                                lastMovingAt = now;
                        }
                        int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
                        if (client.getSkillExperience(Skill.AGILITY) > xpAtClick)
                        {
                                finishWait("xp");
                        }
                        else if (hp < hpAtClick || wv.getPlane() != planeAtClick)
                        {
                                finishWait(hp < hpAtClick ? "hp loss (fell?)" : "plane");
                        }
                        else if (now - lastMovingAt >= STILL_DELAY_MS && now - clickedAt >= STILL_DELAY_MS)
                        {
                                finishWait("stopped moving");
                        }
                        else if (now - clickedAt > COMPLETION_TIMEOUT_MS)
                        {
                                finishWait("timeout");
                        }
                        else
                        {
                                status = "crossing " + obstacleName(clicked);
                                return;
                        }
                }

                if (input.isBusy())
                {
                        return;
                }
                if (eat(local))
                {
                        return;
                }
                if (lootMark(local, wv))
                {
                        return;
                }
                if (walkToStart(local, wv, course))
                {
                        return;
                }
                walker.stop();

                updateCurrent();
                TileObject obstacle = currentObstacle;
                if (obstacle == null)
                {
                        status = "no obstacle in reach";
                        return;
                }

                // Microbot's shouldClickObstacle: while moving / animating only click once
                // the XP for the previous obstacle has landed.
                int xp = client.getSkillExperience(Skill.AGILITY);
                if ((local.getAnimation() != -1 || isMoving(local)) && xp <= lastAgilityXp)
                {
                        status = "waiting to land";
                        return;
                }
                if (xp > lastAgilityXp)
                {
                        lastAgilityXp = xp;
                }

                String action = firstAction(obstacle);
                if (input.interact(obstacleTarget(obstacle), action, true, true, false))
                {
                        waiting = true;
                        clicked = obstacle;
                        xpAtClick = xp;
                        planeAtClick = wv.getPlane();
                        hpAtClick = client.getBoostedSkillLevel(Skill.HITPOINTS);
                        clickedAt = now;
                        lastMovingAt = now;
                        status = action + " " + obstacleName(obstacle);
                        input.log("obstacle " + (obstacleIndex + 1) + "/" + course.obstacles.size() + ": " + action + " " + obstacleName(obstacle));
                }
        }

        private void finishWait(String why)
        {
                waiting = false;
                input.log("obstacle done (" + why + ")");
                lastAgilityXp = Math.max(lastAgilityXp, xpAtClick);
                if ("xp".equals(why))
                {
                        lastAgilityXp = client.getSkillExperience(Skill.AGILITY);
                }
        }

        // ---------------------------------------------------------------- obstacles

        /**
         * Microbot's getCurrentObstacle: obstacles whose position rule passes for the
         * player, present on the player's plane and reachable (ground objects within 2
         * tiles, others within their size + 2). The nearest one wins.
         */
        private void updateCurrent()
        {
                Player local = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (local == null || wv == null || local.getWorldLocation() == null)
                {
                        currentObstacle = null;
                        return;
                }
                AgilityCourse course = config.course();
                WorldPoint me = local.getWorldLocation();
                Set<Integer> ids = new HashSet<>();
                for (AgilityCourse.Obstacle o : course.obstacles)
                {
                        if (o.allows(me))
                        {
                                ids.add(o.objectId);
                        }
                }
                LocalReach reach = LocalReach.compute(client);
                TileObject best = null;
                int bestD = Integer.MAX_VALUE;
                for (TileObject obj : objectsAround(wv, local.getLocalLocation(), ids))
                {
                        if (obj.getPlane() != wv.getPlane() || !reachable(reach, obj))
                        {
                                continue;
                        }
                        int d = obj.getWorldLocation().distanceTo(me);
                        if (d < bestD)
                        {
                                bestD = d;
                                best = obj;
                        }
                }
                currentObstacle = best;
                obstacleIndex = -1;
                if (best != null)
                {
                        for (int i = 0; i < course.obstacles.size(); i++)
                        {
                                if (course.obstacles.get(i).objectId == best.getId())
                                {
                                        obstacleIndex = i;
                                        break;
                                }
                        }
                }
        }

        private List<TileObject> objectsAround(WorldView wv, LocalPoint centre, Set<Integer> ids)
        {
                List<TileObject> out = new ArrayList<>();
                Scene scene = wv.getScene();
                if (scene == null || centre == null || ids.isEmpty())
                {
                        return out;
                }
                Tile[][] tiles = scene.getTiles()[wv.getPlane()];
                int cx = centre.getSceneX();
                int cy = centre.getSceneY();
                for (int x = Math.max(0, cx - SCAN_RADIUS); x <= Math.min(tiles.length - 1, cx + SCAN_RADIUS); x++)
                {
                        for (int y = Math.max(0, cy - SCAN_RADIUS); y <= Math.min(tiles[x].length - 1, cy + SCAN_RADIUS); y++)
                        {
                                Tile t = tiles[x][y];
                                if (t == null)
                                {
                                        continue;
                                }
                                GameObject[] gos = t.getGameObjects();
                                if (gos != null)
                                {
                                        for (GameObject g : gos)
                                        {
                                                if (g != null && ids.contains(g.getId()) && !out.contains(g)
                                                        && g.getSceneMinLocation() != null
                                                        && g.getSceneMinLocation().getX() == x && g.getSceneMinLocation().getY() == y)
                                                {
                                                        out.add(g);
                                                }
                                        }
                                }
                                WallObject w = t.getWallObject();
                                if (w != null && ids.contains(w.getId()))
                                {
                                        out.add(w);
                                }
                                DecorativeObject d = t.getDecorativeObject();
                                if (d != null && ids.contains(d.getId()))
                                {
                                        out.add(d);
                                }
                                GroundObject g = t.getGroundObject();
                                if (g != null && ids.contains(g.getId()))
                                {
                                        out.add(g);
                                }
                        }
                }
                return out;
        }

        /** Microbot's canReach rules: ground objects within 2 tiles, game objects size + 2. */
        private static boolean reachable(LocalReach reach, TileObject obj)
        {
                if (reach == null)
                {
                        return true;
                }
                int minX;
                int minY;
                int sx = 1;
                int sy = 1;
                int pad;
                if (obj instanceof GameObject)
                {
                        GameObject g = (GameObject) obj;
                        if (g.getSceneMinLocation() == null)
                        {
                                return true;
                        }
                        minX = g.getSceneMinLocation().getX();
                        minY = g.getSceneMinLocation().getY();
                        sx = g.sizeX();
                        sy = g.sizeY();
                        pad = 2;
                }
                else
                {
                        LocalPoint lp = obj.getLocalLocation();
                        if (lp == null)
                        {
                                return true;
                        }
                        minX = lp.getSceneX();
                        minY = lp.getSceneY();
                        pad = 2;
                }
                for (int x = minX - pad; x < minX + sx + pad; x++)
                {
                        for (int y = minY - pad; y < minY + sy + pad; y++)
                        {
                                if (reach.tile(x, y))
                                {
                                        return true;
                                }
                        }
                }
                return false;
        }

        private boolean isLastObstacle(TileObject obj)
        {
                List<AgilityCourse.Obstacle> obs = config.course().obstacles;
                return obj != null && obs.get(obs.size() - 1).objectId == obj.getId();
        }

        private String firstAction(TileObject obj)
        {
                ObjectComposition comp = GameHelpers.composition(client, obj.getId());
                if (comp != null && comp.getActions() != null)
                {
                        for (String a : comp.getActions())
                        {
                                if (a != null && !a.isEmpty())
                                {
                                        return a;
                                }
                        }
                }
                return "Cross";
        }

        private String obstacleName(TileObject obj)
        {
                if (obj == null)
                {
                        return "?";
                }
                ObjectComposition comp = GameHelpers.composition(client, obj.getId());
                String name = comp == null ? null : GameHelpers.name(comp);
                return name == null ? "#" + obj.getId() : name;
        }

        private Target obstacleTarget(TileObject obj)
        {
                return Targets.object(obj, () -> obj == currentObstacle || obj == clicked);
        }

        private static boolean isMoving(Player p)
        {
                return p.getPoseAnimation() != p.getIdlePoseAnimation();
        }

        // ---------------------------------------------------------------- marks, food, start

        /** Microbot's lootMarksOfGrace: same plane, reachable within the loot distance. */
        private boolean lootMark(Player local, WorldView wv)
        {
                if (!config.lootMarks() || GameHelpers.inventoryFull(client))
                {
                        return false;
                }
                if (pendingMark != null)
                {
                        if (marks.contains(pendingMark) && ++markWaitTicks < 8)
                        {
                                status = "taking mark of grace";
                                return true;
                        }
                        pendingMark = null;
                        markWaitTicks = 0;
                }
                if (local.getAnimation() != -1 || isMoving(local))
                {
                        return false;
                }
                LocalReach reach = LocalReach.compute(client);
                for (Tile t : new ArrayList<>(marks))
                {
                        if (t.getPlane() != wv.getPlane() || t.getLocalLocation() == null)
                        {
                                continue;
                        }
                        LocalPoint lp = t.getLocalLocation();
                        boolean ok = reach == null;
                        for (int dx = -1; !ok && dx <= 1; dx++)
                        {
                                for (int dy = -1; !ok && dy <= 1; dy++)
                                {
                                        ok = reach.tile(lp.getSceneX() + dx, lp.getSceneY() + dy);
                                }
                        }
                        if (!ok)
                        {
                                continue;
                        }
                        final Tile tile = t;
                        if (input.interact(Targets.groundItem(client, tile, ItemID.GRACE, () -> marks.contains(tile)), "Take", false, true, false))
                        {
                                pendingMark = tile;
                                markWaitTicks = 0;
                                status = "taking mark of grace";
                                input.log("mark of grace at " + tile.getWorldLocation());
                                return true;
                        }
                }
                return false;
        }

        private boolean eat(Player local)
        {
                if (eatWaitTicks > 0)
                {
                        eatWaitTicks--;
                        return true;
                }
                int max = client.getRealSkillLevel(Skill.HITPOINTS);
                int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
                if (config.eatAt() <= 0 || max <= 0 || hp * 100 / max > config.eatAt())
                {
                        return false;
                }
                if (!GameHelpers.openInventoryTab(client, input))
                {
                        return true;
                }
                List<HumanInput.DropTarget> food = input.slotTargets(ComponentID.INVENTORY_CONTAINER, id ->
                {
                        ItemComposition c = itemManager.getItemComposition(id);
                        return c != null && (GameHelpers.hasAction(c.getInventoryActions(), "Eat")
                                || (c.getName() != null && c.getName().toLowerCase().contains("jug of wine")));
                });
                if (food.isEmpty())
                {
                        return false;
                }
                HumanInput.DropTarget slot = food.get(0);
                ItemComposition c = itemManager.getItemComposition(slot.itemId);
                String verb = c != null && c.getName() != null && c.getName().toLowerCase().contains("jug of wine") ? "Drink" : "Eat";
                boolean left = c != null && GameHelpers.isFirstAction(c.getInventoryActions(), verb);
                boolean sent = left ? input.tap(new int[] {slot.x, slot.y}, verb.toLowerCase())
                        : input.longPressPick(new int[] {slot.x, slot.y}, verb);
                if (sent)
                {
                        eatWaitTicks = 3;
                        status = "eating";
                        input.log(verb + " at " + (hp * 100 / max) + "% HP");
                }
                return true;
        }

        /** Microbot's handleWalkToStart: on the ground, at the first obstacle, > 12 tiles from the start. */
        private boolean walkToStart(Player local, WorldView wv, AgilityCourse course)
        {
                if (wv.getPlane() != 0)
                {
                        walker.stop();
                        return false;
                }
                WorldPoint me = local.getWorldLocation();
                boolean nearCourseObstacle = currentObstacle != null && currentObstacle.getWorldLocation().distanceTo(me) <= 12;
                if (me.distanceTo(course.start) <= 12 || nearCourseObstacle && obstacleIndex > 0)
                {
                        return false;
                }
                if (walker.isActive())
                {
                        int r = walker.tick(local);
                        status = "walking to the course start";
                        if (r == WebWalker.ARRIVED || r == WebWalker.FAILED)
                        {
                                walker.stop();
                        }
                        return true;
                }
                if (walkCooldown > 0)
                {
                        walkCooldown--;
                        return true;
                }
                input.log("going back to the course start " + course.start);
                walker.start(java.util.Collections.singletonList(course.start), 2, 800);
                walkCooldown = 5;
                status = "walking to the course start";
                return true;
        }

        // ---------------------------------------------------------------- overlay

        String getStatus()
        {
                if (!config.autoAgility())
                {
                        return "off";
                }
                return status;
        }

        TileObject getCurrentObstacle()
        {
                return currentObstacle;
        }

        int getObstacleIndex()
        {
                return obstacleIndex;
        }

        int getLaps()
        {
                return laps;
        }

        int getMarksVisible()
        {
                return marks.size();
        }

        AutoAgilityConfig getConfig()
        {
                return config;
        }

        HumanInput getInput()
        {
                return input;
        }

        @SuppressWarnings("unused")
        private Random rnd()
        {
                return random;
        }
}
