package net.runelite.client.plugins.automine;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.plugins.humanizer.BankSession;
import net.runelite.client.plugins.humanizer.GameHelpers;
import net.runelite.client.plugins.humanizer.HumanInput;
import net.runelite.client.plugins.humanizer.HumanizerConfig;
import net.runelite.client.plugins.humanizer.Target;
import net.runelite.client.plugins.humanizer.Targets;
import net.runelite.client.plugins.humanizer.WebWalker;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

/**
 * Highlights rocks, counts ores, and optionally auto-mines, then banks or drops the ores
 * when the inventory fills up.
 *
 * <p>All input goes through the shared {@link HumanInput} engine (settings in the
 * Humanizer plugin): every tap is a replayed finger gesture aimed at a fresh point on the
 * rock, checked before it is committed, with off-screen rocks brought into view by a
 * slow, slightly off-centre camera turn. Only rocks the player can actually walk up to
 * are considered. Banking walks a real path ({@link WebWalker}) to the nearest reachable
 * bank and back.</p>
 */
@PluginDescriptor(
        name = "AutoMine",
        description = "Highlight rocks, count ores, and auto-mine/bank/drop with simulated touch input",
        tags = {"mining", "rock", "ore", "bank", "highlight", "counter", "mobile", "humanizer", "script"},
        enabledByDefault = false
)
public class AutoMinePlugin extends Plugin
{
        private static final String SET_CENTER = "MINE-Set area centre";
        private static final String CLEAR_CENTER = "MINE-Clear area centre";
        private static final String SELECT_ROCK = "MINE-Select rock";
        private static final String UNSELECT_ROCK = "MINE-Unselect rock";
        /** Rock tiles the user picked; empty = use the area. */
        private final java.util.Set<WorldPoint> selectedRocks = new java.util.LinkedHashSet<>();
        private static final String LOG_TAG = "AutoMine";

        /** Ticks to wait for a mine to start before trying again. */
        private static final int MINE_WAIT_TICKS = 6;

        /** After this many failed attempts on one rock, pause and let pickNext choose again. */
        private static final int MAX_ROCK_ATTEMPTS = 4;

        /** Mine actions recognised on any rock, besides the selected rock's own action. */
        private static final String[] MINE_ACTIONS = {"Mine"};

        @Inject
        private Client client;

        @Inject
        private ClientThread clientThread;

        @Inject
        private AutoMineConfig config;

        @Inject
        private ConfigManager configManager;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private AutoMineOverlay overlay;

        @Inject
        private AutoMineRockOverlay rockOverlay;

        @Inject
        private AutoMineHuntOverlay huntOverlay;

        @Inject
        private Notifier notifier;

        @Inject
        private ItemManager itemManager;

        private HumanInput input;
        private WebWalker walker;
        private BankSession bankSession;

        private final List<GameObject> rocks = new ArrayList<>();
        private GameHelpers.ItemList keepList = new GameHelpers.ItemList("", true, 1);
        private final Random random = new Random();
        private final Map<String, Long> unreachableUntil = new HashMap<>();
        private LocalReach reach;

        private GameObject currentRock;
        private GameObject nextRock;
        /** The rock the last mine tap went to; becomes the current rock once the pickaxe swings. */
        private GameObject miningRock;
        private int tickCount;
        private int lastAnimTick = -100;

        private int ores;
        private Instant sessionStart;
        private int idleCount;
        private boolean idleNotified;
        private boolean fullNotified;

        /** True from a full inventory (Drop mode) until nothing droppable is left. */
        private boolean dropping;
        private int lastDropCount = -1;
        private int stalledDropPasses;

        private int waitTicks;
        private boolean missNoticed;
        private GameObject pendingRock;
        private int pendingTicks;
        private GameObject lastRock;
        private int pendingAttempts;

        private WorldPoint huntCenter; // manual centre from the settings, or null
        /** Where the player stood when the script was switched on (Microbot's centerLocation). */
        private WorldPoint startAnchor;

        /** Walking back from the bank to the work area. */
        private boolean returning;
        private WorldPoint returnPoint;
        /** No usable bank: stay put until the inventory is no longer full. */
        private boolean bankBlocked;
        private int bankBlockedTicks;

        @Provides
        AutoMineConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoMineConfig.class);
        }

        @Override
        protected void startUp()
        {
                net.runelite.client.plugins.humanizer.SessionLog.INSTANCE.statusSource("AutoMine", this::getStatus);
                input = new HumanInput(client, clientThread, configManager.getConfig(HumanizerConfig.class), LOG_TAG);
                walker = new WebWalker(client, input, LOG_TAG);
                bankSession = new BankSession(client, itemManager, input, walker);
                input.setFocus(
                        () -> currentRock != null ? rockTarget(currentRock) : nextRock != null ? rockTarget(nextRock) : null,
                        () -> huntCenter(client.getLocalPlayer()),
                        () -> config.huntRadius());
                parseKeep();
                parseCenter();
                parseSelectedRocks();
                input.setTrainedSkill(net.runelite.api.Skill.MINING);
                input.start();
                WebWalker.preload();
                overlayManager.add(overlay);
                overlayManager.add(rockOverlay);
                overlayManager.add(huntOverlay);
                clientThread.invoke(this::rescan);
        }

        @Override
        protected void shutDown()
        {
                net.runelite.client.plugins.humanizer.SessionLog.INSTANCE.statusSource("AutoMine", null);
                input.stop();
                overlayManager.remove(overlay);
                overlayManager.remove(rockOverlay);
                overlayManager.remove(huntOverlay);
                rocks.clear();
                currentRock = null;
                nextRock = null;
                miningRock = null;
                clearAutomation();
                resetSession();
        }

        // ---------------------------------------------------------------- state

        private void parseKeep()
        {
                keepList = new GameHelpers.ItemList(config.keepItems(), true, 1);
        }

        private boolean isKept(int itemId)
        {
                return keepList.contains(itemManager, itemId);
        }

        private void parseCenter()
        {
                huntCenter = null;
                String raw = config.centerTile().trim();
                if (raw.isEmpty())
                {
                        return;
                }
                String[] parts = raw.split("[,\\s]+");
                if (parts.length < 2)
                {
                        return;
                }
                try
                {
                        huntCenter = new WorldPoint(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                parts.length >= 3 ? Integer.parseInt(parts[2]) : 0);
                }
                catch (NumberFormatException ignored)
                {
                        // unparseable; stay centred on the player
                }
        }

        /**
         * The area centre, the way Microbot's AIO fighter does it: the manually set tile, else
         * the tile the player stood on when the script was switched on. It never follows the player.
         */
        private void parseSelectedRocks()
        {
                selectedRocks.clear();
                for (String part : config.selectedRocks().split(";"))
                {
                        String[] xyz = part.trim().split("[,\\s]+");
                        if (xyz.length < 2)
                        {
                                continue;
                        }
                        try
                        {
                                selectedRocks.add(new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
                                        xyz.length >= 3 ? Integer.parseInt(xyz[2]) : 0));
                        }
                        catch (NumberFormatException ignored)
                        {
                                // skip junk
                        }
                }
        }

        private void saveSelectedRocks()
        {
                StringBuilder b = new StringBuilder();
                for (WorldPoint wp : selectedRocks)
                {
                        if (b.length() > 0)
                        {
                                b.append(';');
                        }
                        b.append(wp.getX()).append(',').append(wp.getY()).append(',').append(wp.getPlane());
                }
                configManager.setConfiguration(AutoMineConfig.GROUP, AutoMineConfig.ROCKS_KEY, b.toString());
        }

        /** A rock that still has ore: named "... rocks" (a mined-out one is just "Rocks") with a mine action. */
        private boolean isSelectedRock(GameObject obj)
        {
                if (!selectedRocks.contains(obj.getWorldLocation()))
                {
                        return false;
                }
                ObjectComposition comp = GameHelpers.composition(client, obj.getId());
                String name = comp == null ? null : GameHelpers.name(comp);
                if (name == null || name.trim().equalsIgnoreCase("Rocks"))
                {
                        return false;
                }
                return rockAction(obj) != null;
        }

        private WorldPoint huntCenter(Player local)
        {
                if (huntCenter != null)
                {
                        return huntCenter;
                }
                if (startAnchor != null)
                {
                        return startAnchor;
                }
                if ((bankSession != null && bankSession.isActive() || returning) && returnPoint != null)
                {
                        return returnPoint;
                }
                return local == null ? null : local.getWorldLocation();
        }

        /** Sets / clears the start anchor as the script is switched on / off. Client thread. */
        private void updateAnchor(Player local)
        {
                if (!config.autoMine())
                {
                        startAnchor = null;
                }
                else if (startAnchor == null && local != null && local.getWorldLocation() != null)
                {
                        startAnchor = local.getWorldLocation();
                        input.log("area centre = start tile " + startAnchor);
                }
        }

        /** True when the player is outside the area (more than the radius from its centre). */
        private boolean outsideArea(Player local)
        {
                WorldPoint c = huntCenter(local);
                WorldPoint here = local.getWorldLocation();
                if (!config.useHuntRange() || c == null || here == null || !selectedRocks.isEmpty())
                {
                        return false;
                }
                return c.getPlane() != here.getPlane()
                        || Math.max(Math.abs(here.getX() - c.getX()), Math.abs(here.getY() - c.getY())) > config.huntRadius();
        }

        String getCenterLabel()
        {
                if (!config.useHuntRange())
                {
                        return null;
                }
                WorldPoint center = huntCenter(client.getLocalPlayer());
                if (center == null)
                {
                        return "-";
                }
                return (huntCenter == null ? "start " : "") + center.getX() + "," + center.getY()
                        + " r" + config.huntRadius();
        }

        WorldPoint getHuntCenterWorld()
        {
                return huntCenter(client.getLocalPlayer());
        }

        private Target rockTarget(GameObject rock)
        {
                return Targets.object(rock, () -> rocks.contains(rock));
        }

        private boolean isRock(GameObject obj)
        {
                ObjectComposition comp = GameHelpers.composition(client, obj.getId());
                if (comp == null || !config.rock().matchesName(GameHelpers.name(comp)))
                {
                        return false;
                }
                return rockAction(obj) != null;
        }

        /** The mine action to use on this rock (the configured rock's own action first), or null. */
        private String rockAction(GameObject obj)
        {
                ObjectComposition comp = GameHelpers.composition(client, obj.getId());
                if (comp == null)
                {
                        return null;
                }
                String own = config.rock().getAction();
                if (GameHelpers.hasAction(comp.getActions(), own))
                {
                        return own;
                }
                for (String a : MINE_ACTIONS)
                {
                        if (GameHelpers.hasAction(comp.getActions(), a))
                        {
                                return a;
                        }
                }
                return null;
        }

        private static String objectKey(GameObject obj)
        {
                WorldPoint wp = obj.getWorldLocation();
                return obj.getId() + "@" + wp;
        }

        private boolean isMarkedUnreachable(GameObject obj)
        {
                Long until = unreachableUntil.get(objectKey(obj));
                if (until == null)
                {
                        return false;
                }
                if (until > System.currentTimeMillis())
                {
                        return true;
                }
                unreachableUntil.remove(objectKey(obj));
                return false;
        }

        private void markUnreachable(GameObject obj)
        {
                if (unreachableUntil.size() > 64)
                {
                        unreachableUntil.clear();
                }
                unreachableUntil.put(objectKey(obj), System.currentTimeMillis() + 120_000L);
                input.log("unreachable, skipping for 2 minutes: " + objectKey(obj));
        }

        /** Rebuilds the rock list from the live scene. Depleted rocks lose their mine action and drop out. */
        private void refreshRocks()
        {
                rocks.clear();
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                        return;
                }
                WorldView wv = client.getTopLevelWorldView();
                Player local = client.getLocalPlayer();
                if (wv == null || local == null || local.getLocalLocation() == null)
                {
                        return;
                }
                reach = LocalReach.compute(client);
                WorldPoint centre = huntCenter(local);
                LocalPoint centreLocal = centre == null ? null : LocalPoint.fromWorld(wv, centre);
                boolean ranged = config.useHuntRange() && centreLocal != null;
                int radius = ranged ? config.huntRadius() + 2 : 15;
                if (!selectedRocks.isEmpty())
                {
                        // The user picked the rocks: only those, wherever they are, any ore.
                        for (GameObject obj : GameHelpers.gameObjectsAround(client, local.getLocalLocation(), 24))
                        {
                                if (isSelectedRock(obj) && !isMarkedUnreachable(obj) && GameHelpers.canInteract(reach, obj))
                                {
                                        rocks.add(obj);
                                }
                        }
                        return;
                }
                for (GameObject obj : GameHelpers.gameObjectsAround(client, ranged ? centreLocal : local.getLocalLocation(), radius))
                {
                        if (ranged && !inHuntRange(obj, centre))
                        {
                                continue;
                        }
                        if (isRock(obj) && !isMarkedUnreachable(obj) && GameHelpers.canInteract(reach, obj))
                        {
                                rocks.add(obj);
                        }
                }
        }

        /** Chebyshev distance, so a radius of 6 is the 13x13 square the player sees. */
        private boolean inHuntRange(GameObject obj, WorldPoint center)
        {
                WorldPoint at = obj.getWorldLocation();
                if (at == null || at.getPlane() != center.getPlane())
                {
                        return false;
                }
                int radius = config.huntRadius();
                return Math.abs(at.getX() - center.getX()) <= radius
                        && Math.abs(at.getY() - center.getY()) <= radius;
        }

        private void rescan()
        {
                refreshRocks();
                updateRocks();
        }

        private void clearAutomation()
        {
                waitTicks = 0;
                pendingRock = null;
                pendingTicks = 0;
                lastRock = null;
                pendingAttempts = 0;
                missNoticed = false;
                dropping = false;
                lastDropCount = -1;
                stalledDropPasses = 0;
                if (bankSession != null)
                {
                        bankSession.stop();
                }
                if (walker != null)
                {
                        walker.stop();
                }
                returning = false;
                bankBlocked = false;
        }

        /** Which rock is being mined (the last tapped one, while the pickaxe swings) and which comes next. */
        private void updateRocks()
        {
                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        currentRock = null;
                        nextRock = null;
                        return;
                }
                if (local.getAnimation() != -1)
                {
                        lastAnimTick = tickCount;
                }
                if (miningRock != null && !rocks.contains(miningRock))
                {
                        // Depleted (depleted rock) or out of range: the swing that is still playing is not a mine.
                        miningRock = null;
                        lastAnimTick = -100;
                }
                boolean swinging = tickCount - lastAnimTick <= 2;
                GameObject mined = null;
                if (swinging)
                {
                        mined = miningRock != null ? miningRock : adjacentRock(local);
                }
                currentRock = mined;
                if (mined != null)
                {
                        miningRock = mined;
                }
                if (!isValidNext(nextRock))
                {
                        nextRock = pickNext(local);
                }
        }

        private GameObject adjacentRock(Player local)
        {
                WorldPoint me = local.getWorldLocation();
                GameObject best = null;
                int bestDist = Integer.MAX_VALUE;
                for (GameObject t : rocks)
                {
                        int d = t.getWorldLocation().distanceTo(me);
                        if (d <= 2 && d < bestDist)
                        {
                                best = t;
                                bestDist = d;
                        }
                }
                return best;
        }

        private boolean isValidNext(GameObject obj)
        {
                return obj != null && obj != currentRock && rocks.contains(obj);
        }

        private GameObject pickNext(Player local)
        {
                WorldPoint me = local.getWorldLocation();
                List<GameObject> candidates = new ArrayList<>();
                for (GameObject t : rocks)
                {
                        if (t != currentRock)
                        {
                                candidates.add(t);
                        }
                }
                if (candidates.isEmpty())
                {
                        return null;
                }
                candidates.sort((a, b) -> Integer.compare(
                        a.getWorldLocation().distanceTo(me),
                        b.getWorldLocation().distanceTo(me)));
                int pool = Math.min(Math.max(config.rockPool(), 1), candidates.size());
                return candidates.get(pool <= 1 ? 0 : random.nextInt(pool));
        }

        private void resetSession()
        {
                ores = 0;
                sessionStart = null;
                idleCount = 0;
                idleNotified = false;
                fullNotified = false;
        }

        // ---------------------------------------------------------------- automation

        /**
         * One decision per tick, on the client thread: a bank trip or the walk back comes
         * first, then a full inventory, and while a rock is being mined nothing else is tried.
         */
        private void automate()
        {
                updateAnchor(client.getLocalPlayer());
                // A blocked bank trip is retried after ~30s instead of standing still for good.
                if (bankBlocked && ++bankBlockedTicks > 50)
                {
                        bankBlocked = false;
                        bankBlockedTicks = 0;
                }
                else if (!bankBlocked)
                {
                        bankBlockedTicks = 0;
                }
                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        return;
                }
                if (waitTicks > 0)
                {
                        waitTicks--;
                        return;
                }
                if (!config.autoMine())
                {
                        if (bankSession.isActive() || returning)
                        {
                                clearAutomation();
                        }
                        if (GameHelpers.inventoryFull(client) && !fullNotified)
                        {
                                fullNotified = true;
                                notifier.notify(config.notifyFull(), "Inventory full");
                        }
                        else if (!GameHelpers.inventoryFull(client))
                        {
                                fullNotified = false;
                        }
                        return;
                }

                if (bankSession.isActive())
                {
                        int r = bankSession.tick(local, reach);
                        if (r == BankSession.DONE)
                        {
                                returning = true;
                                walker.start(Collections.singletonList(returnPoint), 2, config.bankMaxDistance() * 3 + 120);
                                waitTicks = 1 + random.nextInt(3);
                        }
                        else if (r == BankSession.FAILED)
                        {
                                bankBlocked = true;
                                notifier.notify(config.notifyFull(), String.valueOf(bankSession.failure()));
                        }
                        return;
                }
                if (returning)
                {
                        WorldPoint here = local.getWorldLocation();
                        boolean inRange = returnPoint == null || (returnPoint.getPlane() == here.getPlane()
                                && Math.max(Math.abs(here.getX() - returnPoint.getX()), Math.abs(here.getY() - returnPoint.getY()))
                                <= Math.max(2, config.huntRadius() / 2));
                        int w = inRange && !rocks.isEmpty() ? WebWalker.ARRIVED : walker.tick(local);
                        if (w == WebWalker.ARRIVED)
                        {
                                returning = false;
                                walker.stop();
                                nextRock = null;
                                waitTicks = input.reactionDelay();
                        }
                        else if (w == WebWalker.FAILED)
                        {
                                returning = false;
                                walker.stop();
                                bankBlocked = true;
                                notifier.notify(config.notifyFull(), "No walkable route back to the rocks");
                        }
                        return;
                }

                boolean full = GameHelpers.inventoryFull(client);
                AutoMineConfig.FullInventoryMode mode = config.fullInvMode();
                if (!full)
                {
                        bankBlocked = false;
                }

                if (full && !dropping)
                {
                        pendingRock = null;
                        pendingTicks = 0;
                        if (mode == AutoMineConfig.FullInventoryMode.DROP)
                        {
                                dropping = true;
                        }
                        else if (mode == AutoMineConfig.FullInventoryMode.BANK && !bankBlocked)
                        {
                                returnPoint = huntCenter(local);
                                if (!bankSession.begin(local, config.bankMaxDistance(), this::isKept, Collections.emptyList(), chosenBank()))
                                {
                                        bankBlocked = true;
                                        notifier.notify(config.notifyFull(), "Inventory full — " + bankSession.failure());
                                }
                                else
                                {
                                        waitTicks = input.reactionDelay();
                                }
                                return;
                        }
                        else
                        {
                                if (!fullNotified)
                                {
                                        fullNotified = true;
                                        notifier.notify(config.notifyFull(), "Inventory full");
                                }
                                return;
                        }
                }

                if (dropping)
                {
                        if (input.isBusy())
                        {
                                return;
                        }
                        if (!GameHelpers.openInventoryTab(client, input))
                        {
                                waitTicks = 1; // tapped the inventory tab; drop once it is open
                                return;
                        }
                        List<HumanInput.DropTarget> plan = input.slotTargets(net.runelite.api.widgets.ComponentID.INVENTORY_CONTAINER,
                                id -> !isKept(id));
                        if (plan.isEmpty())
                        {
                                dropping = false;
                                lastDropCount = -1;
                                stalledDropPasses = 0;
                                if (full)
                                {
                                        if (!fullNotified)
                                        {
                                                fullNotified = true;
                                                notifier.notify(config.notifyFull(), "Inventory full — nothing droppable");
                                        }
                                        return;
                                }
                        }
                        else
                        {
                                if (plan.size() > 1)
                                {
                                        input.waveOrder(plan);
                                }
                                stalledDropPasses = plan.size() >= lastDropCount && lastDropCount >= 0 ? stalledDropPasses + 1 : 0;
                                lastDropCount = plan.size();
                                boolean useShift = config.shiftDrop() && stalledDropPasses < 2;
                                boolean scheduled = useShift ? input.shiftDrops(plan) : input.drops(plan);
                                waitTicks = scheduled ? random.nextInt(2) : 1;
                                return;
                        }
                }
                fullNotified = false;

                // Mining: the tap landed, so drop any pending state, and rest the finger on the
                // next rock so it is tapped the moment this one runs out.
                if (currentRock != null)
                {
                        pendingRock = null;
                        pendingTicks = 0;
                        lastRock = null;
                        pendingAttempts = 0;
                        if (config.hoverNextRock() && nextRock != null && !input.isBusy())
                        {
                                input.park(rockTarget(nextRock));
                        }
                        return;
                }

                // A tap was sent but mining never started. A tap that left the player idle and
                // not walking is noticed after two ticks and fixed at once.
                if (pendingRock != null)
                {
                        pendingTicks++;
                        if (pendingTicks < MINE_WAIT_TICKS && !tapMissed(local) && rocks.contains(pendingRock))
                        {
                                return;
                        }
                        if (pendingTicks < MINE_WAIT_TICKS)
                        {
                                missNoticed = true;
                                waitTicks = 0;
                        }
                        pendingRock = null;
                        pendingTicks = 0;
                }

                // Nothing to mine here and outside the area: walk back to its centre.
                if (nextRock == null && currentRock == null && !input.isBusy() && outsideArea(local))
                {
                        returnPoint = huntCenter(local);
                        returning = true;
                        walker.start(Collections.singletonList(returnPoint), 1, 600);
                        input.log("outside the area: walking back to " + returnPoint);
                        return;
                }

                if (nextRock != null && !input.isBusy())
                {
                        GameObject victim = nextRock;
                        if (victim != lastRock)
                        {
                                lastRock = victim;
                                pendingAttempts = 0;
                        }
                        if (pendingAttempts >= MAX_ROCK_ATTEMPTS)
                        {
                                pendingAttempts = 0;
                                nextRock = null;
                                lastRock = null;
                                waitTicks = 8 + random.nextInt(16);
                                return;
                        }
                        String action = rockAction(victim);
                        if (action == null)
                        {
                                return;
                        }
                        boolean quick = missNoticed || input.quickRetry(pendingAttempts) || input.isParkedOn(rockTarget(victim));
                        missNoticed = false;
                        boolean def = GameHelpers.objectDefaultAction(client, victim, action);
                        if (input.interact(rockTarget(victim), action, def, quick, true))
                        {
                                pendingRock = victim;
                                miningRock = victim;
                                pendingTicks = 0;
                                pendingAttempts++;
                                waitTicks = input.reactionDelay();
                        }
                }
        }

        /** Two ticks after a mine tap the player should be swinging or walking; idle means the tap was lost. */
        private boolean tapMissed(Player local)
        {
                if (pendingTicks < 2 || local == null)
                {
                        return false;
                }
                return local.getAnimation() == -1
                        && client.getLocalDestinationLocation() == null
                        && local.getPoseAnimation() == local.getIdlePoseAnimation();
        }

        String getStatus()
        {
                if (bankSession != null && bankSession.isActive())
                {
                        return bankSession.status();
                }
                if (returning)
                {
                        return "Walking back";
                }
                if (dropping)
                {
                        return "Dropping";
                }
                if (bankBlocked)
                {
                        return "Bank blocked: " + (bankSession.failure() == null ? "?" : bankSession.failure());
                }
                return currentRock != null ? "Mining" : "Idle";
        }

        String getInventoryUsage()
        {
                return (28 - GameHelpers.freeSlots(client)) + "/28";
        }

        // ---------------------------------------------------------------- events

        @Subscribe
        public void onConfigChanged(ConfigChanged event)
        {
                if (!AutoMineConfig.GROUP.equals(event.getGroup()))
                {
                        return;
                }
                if (AutoMineConfig.KEEP_ITEMS_KEY.equals(event.getKey()))
                {
                        parseKeep();
                }
                else if (AutoMineConfig.ROCKS_KEY.equals(event.getKey()))
                {
                        parseSelectedRocks();
                        clientThread.invoke(this::rescan);
                }
                else if (AutoMineConfig.CENTER_KEY.equals(event.getKey()))
                {
                        parseCenter();
                        clientThread.invoke(this::rescan);
                }
                else if (AutoMineConfig.ROCK_KEY.equals(event.getKey()))
                {
                        clientThread.invoke(this::rescan);
                }
                else if ("autoMine".equals(event.getKey()) || "fullInvMode".equals(event.getKey()))
                {
                        clientThread.invoke(this::clearAutomation);
                }
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged event)
        {
                if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
                {
                        input.reset();
                        rocks.clear();
                        currentRock = null;
                        nextRock = null;
                        miningRock = null;
                        clearAutomation();
                }
        }

        @Subscribe
        public void onChatMessage(ChatMessage event)
        {
                if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
                {
                        return;
                }
                String message = Text.removeTags(String.valueOf(event.getMessage()));
                if (message.startsWith("I can't reach that"))
                {
                        if (bankSession.isActive())
                        {
                                bankSession.onCantReach();
                        }
                        else if (returning)
                        {
                                walker.onCantReach();
                        }
                        else
                        {
                                GameObject t = pendingRock != null ? pendingRock : lastRock;
                                if (t != null)
                                {
                                        markUnreachable(t);
                                        pendingRock = null;
                                        pendingTicks = 0;
                                        if (nextRock == t)
                                        {
                                                nextRock = null;
                                        }
                                }
                        }
                        return;
                }
                if (message.startsWith("You manage to mine ") || message.startsWith("You just found "))
                {
                        if (sessionStart == null)
                        {
                                sessionStart = Instant.now();
                        }
                        ores++;
                        notifier.notify(config.notifyOre(), message + " (" + ores + ")");
                        input.onProgress();
                }
        }

        @Subscribe
        public void onGameTick(GameTick tick)
        {
                tickCount++;
                refreshRocks();
                updateRocks();

                if (currentRock != null && sessionStart == null)
                {
                        sessionStart = Instant.now();
                }

                boolean busyElsewhere = bankSession.isActive() || returning || dropping;
                if (currentRock == null && !rocks.isEmpty() && sessionStart != null && !busyElsewhere)
                {
                        idleCount++;
                        if (idleCount >= config.idleTicks() && !idleNotified)
                        {
                                idleNotified = true;
                                notifier.notify(config.notifyIdle(), "You're not mining");
                        }
                }
                else
                {
                        idleCount = 0;
                        idleNotified = false;
                }

                input.onGameTick(config.autoMine() && currentRock != null);
                automate();
        }

        @Subscribe
        public void onClientTick(ClientTick tick)
        {
                input.onClientTick();
        }

        @Subscribe
        public void onMenuEntryAdded(MenuEntryAdded event)
        {
                MenuEntry entry = event.getMenuEntry();
                if (entry.getNpc() != null)
                {
                        return;
                }
                addRockEntries(entry);
                addCenterEntries(entry);
        }

        /** Long-press a rock: "MINE-Select rock" / "MINE-Unselect rock". */
        private void addRockEntries(MenuEntry entry)
        {
                String option = entry.getOption() == null ? "" : Text.removeTags(entry.getOption());
                if (!"Mine".equalsIgnoreCase(option))
                {
                        return;
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return;
                }
                LocalPoint lp = LocalPoint.fromScene(entry.getParam0(), entry.getParam1(), wv);
                // The menu entry carries the rock's exact scene tile (its south-west corner):
                // pick that rock, not a neighbouring one of the same type.
                GameObject rock = null;
                int bestD = Integer.MAX_VALUE;
                for (GameObject obj : GameHelpers.gameObjectsAround(client, lp, 2))
                {
                        if (obj.getId() != entry.getIdentifier())
                        {
                                continue;
                        }
                        net.runelite.api.Point min = obj.getSceneMinLocation();
                        int d = min == null ? 99
                                : Math.abs(min.getX() - entry.getParam0()) + Math.abs(min.getY() - entry.getParam1());
                        if (d < bestD)
                        {
                                bestD = d;
                                rock = obj;
                        }
                }
                if (rock == null)
                {
                        return;
                }
                final WorldPoint at = rock.getWorldLocation();
                final boolean selected = selectedRocks.contains(at);
                client.createMenuEntry(-1)
                        .setOption(selected ? UNSELECT_ROCK : SELECT_ROCK)
                        .setTarget(entry.getTarget())
                        .setType(MenuAction.RUNELITE)
                        .onClick(e ->
                        {
                                if (selected)
                                {
                                        selectedRocks.remove(at);
                                }
                                else
                                {
                                        selectedRocks.add(at);
                                }
                                saveSelectedRocks();
                        });
        }

        /** Lets the player long-press the ground and mark the area centre. */
        private void addCenterEntries(MenuEntry entry)
        {
                if (!config.useHuntRange() || entry.getType() != MenuAction.WALK)
                {
                        return;
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return;
                }
                final WorldPoint point = WorldPoint.fromScene(wv, entry.getParam0(), entry.getParam1(), wv.getPlane());
                if (point == null)
                {
                        return;
                }
                client.createMenuEntry(-1)
                        .setOption(SET_CENTER)
                        .setTarget("")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> setCenter(point));
                if (huntCenter != null)
                {
                        client.createMenuEntry(-1)
                                .setOption(CLEAR_CENTER)
                                .setTarget("")
                                .setType(MenuAction.RUNELITE)
                                .onClick(e -> setCenter(null));
                }
        }

        private void setCenter(WorldPoint point)
        {
                configManager.setConfiguration(AutoMineConfig.GROUP, AutoMineConfig.CENTER_KEY,
                        point == null ? "" : point.getX() + "," + point.getY() + "," + point.getPlane());
        }

        // ---------------------------------------------------------------- overlay accessors

        int getOres()
        {
                return ores;
        }

        int getRockCount()
        {
                return rocks.size();
        }

        List<GameObject> getRocks()
        {
                return rocks;
        }

        GameObject getCurrentRock()
        {
                return currentRock;
        }

        GameObject getNextRock()
        {
                return nextRock;
        }

        double getOresPerHour()
        {
                if (sessionStart == null || ores == 0)
                {
                        return 0;
                }
                long ms = Duration.between(sessionStart, Instant.now()).toMillis();
                return ms <= 0 ? 0 : ores * 3_600_000.0 / ms;
        }

        String getPersonaLabel()
        {
                return input == null ? "-" : input.getPersonaLabel();
        }

        long getMethodSwitchInSeconds()
        {
                return input == null ? -1 : input.getMethodSwitchInSeconds();
        }

        /** The closest known spot for the selected rock, e.g. "Draynor Village (12)", or null. */
        String getNearestKnownSpot()
        {
                Player local = client.getLocalPlayer();
                if (local == null || local.getWorldLocation() == null)
                {
                        return null;
                }
                WorldPoint me = local.getWorldLocation();
                MiningRock.Location best = null;
                int bestDist = Integer.MAX_VALUE;
                for (MiningRock.Location loc : config.rock().getLocations())
                {
                        int d = loc.worldPoint.distanceTo2D(me);
                        if (d < bestDist)
                        {
                                best = loc;
                                bestDist = d;
                        }
                }
                return best == null ? null : best.name + " (" + bestDist + ")";
        }

        void resetCounter()
        {
                resetSession();
        }

        /** For the Scripts panel: whether automation is on. */
        public boolean isAutomationOn()
        {
                return config.autoMine();
        }

        /** The bank the user picked (this script's setting, or the shared Humanizer default); null = closest. */
        private net.runelite.client.plugins.gamedata.BankLocation chosenBank()
        {
                return net.runelite.client.plugins.gamedata.BankPick.resolve(config.bankPick(), input.config().defaultBank());
        }
}
