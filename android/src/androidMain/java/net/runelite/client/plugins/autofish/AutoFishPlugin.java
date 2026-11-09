package net.runelite.client.plugins.autofish;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewConfiguration;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.plugins.humanizer.GameHelpers;
import net.runelite.client.plugins.humanizer.HumanInput;
import net.runelite.client.plugins.humanizer.HumanizerConfig;
import net.runelite.client.plugins.humanizer.Target;
import net.runelite.client.plugins.humanizer.Targets;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.mobile.MobileHitTest;
import net.runelite.client.plugins.mobile.MobileInventoryOverlay;
import net.runelite.client.plugins.mobile.MobileScrollbars;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.mp.ui.bridge.ModifierState;

/**
 * Highlights fishing spots, counts catches, and optionally auto-fishes and auto-drops
 * the catch when the inventory fills up.
 *
 * <h2>Input simulation</h2>
 * Automation never calls {@code client.menuAction} or any other internal menu API. Every
 * automated action is performed with the same AWT mouse-event stream a real finger
 * produces on this client: the Compose touch driver in {@code AppAndroid.kt} turns taps
 * into {@code MOUSE_MOVED} / {@code MOUSE_PRESSED} / {@code MOUSE_RELEASED} /
 * {@code MOUSE_CLICKED} bursts on the game canvas, so that is exactly what
 * {@link TouchEngine} replays, on the Android main thread, through the same
 * hit-test/walk-to-listener/local-coordinates path the driver uses.
 *
 * <ul>
 *   <li>Every tap parks the cursor with two {@code MOUSE_MOVED}s first (the driver fires
 *       one from its hover tracker and one when the gesture starts), then emits the
 *       four-event burst at finger-up time — so the press duration is real time spent
 *       with the finger on the glass, and every tap lands at a freshly drawn spot on the
 *       spot (hull-biased, time-nudged, never the same pixel).</li>
 *   <li>Sometimes the tap lands on the water next to the spot; 50-97ms later the
 *       correction tap hits the spot, like a player noticing their slip.</li>
 *   <li>When the wanted fishing action is not the spot's left-click option — or the
 *       active style prefers menus — the engine long-presses (BUTTON3 burst at the
 *       driver's long-press timeout), reads where the client drew the action row, drags
 *       the cursor to it along a randomized sine-wave path (the hover trail that
 *       highlights rows) and clicks it. Dropping works the same way: long-press the
 *       inventory item, walk the finger onto the "Drop" row, click.</li>
 *   <li>Every travelled path — drags, approach trails, even the micro-wobble while the
 *       finger is pressed — is built from trigonometric curves: a sine ripple across the
 *       line of travel, a faster cosine tremor, and a cosine swell along it, all with
 *       amplitudes, frequencies and phases rolled fresh per gesture. Idle wander is a
 *       Lissajous figure (cos on x, sin on y, detuned frequency ratio) that never closes
 *       on itself and never repeats.</li>
 *   <li>The whole behaviour is steered by a rotating "input style" (persona): tap
 *       placement strategy, reaction padding, press duration, misclick rate and menu
 *       preference all change together every few minutes, so the fingerprint of the
 *       input drifts the way a real player's does over a long session.</li>
 * </ul>
 *
 * <h2>Camera</h2>
 * Voluntary camera turns only happen every 5-15 catches. Each turn is animated over
 * roughly a second or two of client ticks with a randomized ease-in velocity curve
 * (starts slow, speeds up), sometimes hesitating mid-turn, sometimes overshooting the
 * target and settling back, sometimes stopping just short of it, and occasionally
 * dragging the pitch along slightly out of phase. A rate-limited "rescue" turn brings a
 * fully off-screen spot back into view when a click is due, but with the same slow
 * humanized motion — never a snap.
 *
 * <h2>Spots</h2>
 * Spots are found by their menu actions (Lure, Bait, Net, Cage, Harpoon, ...), so no ID
 * list needs maintaining; extra IDs can still be listed for odd spots. The spot list is
 * rebuilt from the world view every tick — spots move around constantly, and an
 * incrementally maintained list drifts out of sync with the scene exactly the way a
 * cached target list does in AutoFight.
 */
@PluginDescriptor(
        name = "AutoFish",
        description = "Highlight fishing spots, count catches, and auto-fish/auto-drop with simulated touch input and humanized camera turns",
        tags = {"fishing", "spot", "highlight", "counter", "mobile", "id", "drop", "camera", "humanizer"},
        enabledByDefault = false
)
public class AutoFishPlugin extends Plugin
{
        private static final String SET_CENTER = "FISH-Set area centre";
        private static final String CLEAR_CENTER = "FISH-Clear area centre";

        private static final String LOG_TAG = "AutoFish";

        /** Ticks to wait for fishing to actually start before assuming the click was lost. */
        private static final int FISH_WAIT_TICKS = 6;

        /** Attempts on the same spot before pausing and re-picking (e.g. no rod equipped). */
        private static final int MAX_SPOT_ATTEMPTS = 4;

        /** Widget item id the client uses for "nothing in this slot". */
        private static final int EMPTY_ITEM_ID = 6512;

        @Inject
        private Client client;

        @Inject
        private ClientThread clientThread;

        @Inject
        private AutoFishConfig config;

        @Inject
        private ConfigManager configManager;

        @Inject
        private NpcOverlayService npcOverlayService;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private AutoFishOverlay overlay;

        @Inject
        private AutoFishHuntOverlay huntOverlay;

        @Inject
        private Notifier notifier;

        @Inject
        private ItemManager itemManager;

        private final Set<Integer> spotIdsExtra = new HashSet<>();
        private final Set<String> fishingActions = new HashSet<>(); // lowercase
        private HumanInput input;

        private final List<NPC> spots = new ArrayList<>();
        private final java.util.Map<NPC, Long> unreachableUntil = new java.util.HashMap<>();

        private final Set<Integer> dropIds = new HashSet<>();
        private final Set<String> dropNames = new HashSet<>(); // lowercase
        private final Set<Integer> keepIds = new HashSet<>();
        private final Set<String> keepNames = new HashSet<>(); // lowercase

        private final Random random = new Random();

        private NPC currentSpot;
        private NPC nextSpot;

        private int catches;
        private Instant sessionStart;
        private int idleCount;
        private boolean idleNotified;
        private boolean fullNotified;
        /**
         * True from the moment the inventory fills until nothing droppable is left. Keeps
         * the drop loop going after the first items vanish (which un-fills the inventory)
         * so the whole bag is emptied before fishing resumes.
         */
        private boolean dropping;
        /** Droppable count at the previous pass and how many passes in a row made no progress. */
        private int lastDropCount = -1;
        private int stalledDropPasses;

        private int waitTicks;
        /** Set when a tap visibly did nothing; the next attempt goes out at once with a short pad. */
        private boolean missNoticed;
        private NPC pendingSpot;
        private int pendingTicks;
        private NPC lastSpot;
        private int pendingAttempts;

        private WorldPoint huntCenter; // null = centre on the player

        private final Function<NPC, HighlightedNpc> highlighter = this::highlight;

        @Provides
        AutoFishConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoFishConfig.class);
        }

        @Override
        protected void startUp()
        {
                parseSpotIds();
                parseActions();
                parseDrop();
                parseKeep();
                parseCenter();
                input = new HumanInput(client, clientThread, configManager.getConfig(HumanizerConfig.class), LOG_TAG);
                input.setFocus(
                        () -> currentSpot != null ? spotTarget(currentSpot) : nextSpot != null ? spotTarget(nextSpot) : null,
                        () -> huntCenter(client.getLocalPlayer()),
                        () -> config.huntRadius());
                input.setTrainedSkill(net.runelite.api.Skill.FISHING);
                input.start();
                overlayManager.add(overlay);
                overlayManager.add(huntOverlay);
                npcOverlayService.registerHighlighter(highlighter);
                clientThread.invoke(this::rescan);
        }

        @Override
        protected void shutDown()
        {
                input.stop();
                npcOverlayService.unregisterHighlighter(highlighter);
                overlayManager.remove(overlay);
                overlayManager.remove(huntOverlay);
                spots.clear();
                currentSpot = null;
                nextSpot = null;
                clearAutomation();
                resetSession();
        }

        // ---------------------------------------------------------------- state

        private void parseSpotIds()
        {
                spotIdsExtra.clear();
                for (String part : config.spotNpcIds().split("[,\\s]+"))
                {
                        if (part.isEmpty())
                        {
                                continue;
                        }
                        try
                        {
                                spotIdsExtra.add(Integer.parseInt(part.trim()));
                        }
                        catch (NumberFormatException ignored)
                        {
                                // not a number; skip it
                        }
                }
        }

        private void parseActions()
        {
                fishingActions.clear();
                for (String part : config.fishingActions().split(","))
                {
                        String p = part.trim().toLowerCase(Locale.ROOT);
                        if (!p.isEmpty())
                        {
                                fishingActions.add(p);
                        }
                }
        }

        private void parseDrop()
        {
                dropIds.clear();
                dropNames.clear();
                parseItemNamesOrIds(config.dropItems(), dropIds, dropNames);
        }

        private void parseKeep()
        {
                keepIds.clear();
                keepNames.clear();
                parseItemNamesOrIds(config.keepItems(), keepIds, keepNames);
        }

        private void parseItemNamesOrIds(String raw, Set<Integer> ids, Set<String> names)
        {
                for (String part : raw.split(","))
                {
                        String p = part.trim();
                        if (p.isEmpty())
                        {
                                continue;
                        }
                        try
                        {
                                ids.add(Integer.parseInt(p));
                        }
                        catch (NumberFormatException notAnId)
                        {
                                names.add(p.toLowerCase(Locale.ROOT));
                        }
                }
        }

        private boolean isFishingAction(String action)
        {
                return action != null && fishingActions.contains(action.trim().toLowerCase(Locale.ROOT));
        }

        /** True for NPCs that behave like fishing spots: a fishing action in the menu (or a listed id). */
        private boolean isSpot(NPC npc)
        {
                if (npc == null)
                {
                        return false;
                }
                if (spotIdsExtra.contains(npc.getId()))
                {
                        return true;
                }

                NPCComposition comp = npc.getTransformedComposition();
                if (comp == null)
                {
                        comp = npc.getComposition();
                }
                if (comp == null)
                {
                        return false;
                }
                String[] actions = comp.getActions();
                if (actions == null)
                {
                        return false;
                }

                boolean hasFishingAction = false;
                for (String action : actions)
                {
                        if (isFishingAction(action))
                        {
                                hasFishingAction = true;
                                break;
                        }
                }
                if (!hasFishingAction)
                {
                        return false;
                }
                if (!config.requireSpotName())
                {
                        return true;
                }
                String name = comp.getName();
                // Nameless composition (not yet resolved): lean on the actions alone.
                return name == null || name.toLowerCase(Locale.ROOT).contains("fishing spot");
        }

        /**
         * The menu action this plugin uses on the given spot: the configured choice when it
         * exists on the spot, otherwise the spot's first fishing action. Null when the spot
         * has none of them (should not happen for anything {@link #isSpot} accepted).
         */
        private String spotAction(NPC npc)
        {
                NPCComposition comp = npc.getTransformedComposition();
                if (comp == null)
                {
                        comp = npc.getComposition();
                }
                if (comp == null)
                {
                        return null;
                }
                String[] actions = comp.getActions();
                if (actions == null)
                {
                        return null;
                }

                String choice = config.fishingActionChoice().trim();
                if (!choice.isEmpty())
                {
                        for (String action : actions)
                        {
                                if (choice.equalsIgnoreCase(action))
                                {
                                        return action;
                                }
                        }
                        return null; // the wanted action is not on this spot
                }

                for (String action : actions)
                {
                        if (isFishingAction(action))
                        {
                                return action;
                        }
                }
                return null;
        }

        /** True when the given action is the spot's default left-click option (a plain tap uses it). */
        private boolean actionIsDefaultAction(NPC npc, String wanted)
        {
                NPCComposition comp = npc.getTransformedComposition();
                if (comp == null)
                {
                        comp = npc.getComposition();
                }
                if (comp == null || wanted == null)
                {
                        return false;
                }
                String[] actions = comp.getActions();
                if (actions == null)
                {
                        return false;
                }
                for (String action : actions)
                {
                        if (action == null)
                        {
                                continue;
                        }
                        return action.equalsIgnoreCase(wanted);
                }
                return false;
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

        private WorldPoint huntCenter(Player local)
        {
                if (huntCenter != null)
                {
                        return huntCenter;
                }
                return local == null ? null : local.getWorldLocation();
        }

        /** Chebyshev distance, so a radius of 6 is the 13x13 square the player sees. */
        private boolean inHuntRange(NPC npc, WorldPoint center)
        {
                if (!config.useHuntRange() || center == null)
                {
                        return true;
                }
                WorldPoint at = npc.getWorldLocation();
                if (at == null || at.getPlane() != center.getPlane())
                {
                        return false;
                }
                int radius = config.huntRadius();
                return Math.abs(at.getX() - center.getX()) <= radius
                        && Math.abs(at.getY() - center.getY()) <= radius;
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
                return (huntCenter == null ? "self" : center.getX() + "," + center.getY())
                        + " r" + config.huntRadius();
        }

        /** Centre tile for the world-space range overlay; null when not determinable. */
        WorldPoint getHuntCenterWorld()
        {
                if (huntCenter != null)
                {
                        return huntCenter;
                }
                Player local = client.getLocalPlayer();
                return local == null ? null : local.getWorldLocation();
        }

        /** Rebuilds the spot list from the live scene. Cheap, and cannot go stale when spots move. */
        private void refreshSpots()
        {
                spots.clear();
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                        return;
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return;
                }
                WorldPoint center = huntCenter(client.getLocalPlayer());
                LocalReach reach = LocalReach.compute(client);
                for (NPC npc : wv.npcs())
                {
                        if (isSpot(npc) && inHuntRange(npc, center) && reachable(reach, npc))
                        {
                                spots.add(npc);
                        }
                }
        }

        /**
         * True when the player can actually get next to this NPC right now (live collision:
         * closed doors and fences count), and it has not just answered "I can't reach that!".
         */
        private boolean reachable(LocalReach reach, NPC npc)
        {
                Long until = unreachableUntil.get(npc);
                if (until != null)
                {
                        if (until > System.currentTimeMillis())
                        {
                                return false;
                        }
                        unreachableUntil.remove(npc);
                }
                if (reach == null)
                {
                        return true;
                }
                NPCComposition comp = npc.getTransformedComposition();
                int size = comp == null ? 1 : Math.max(1, comp.getSize());
                return reach.canInteractCentred(npc.getLocalLocation(), size);
        }

        /** The game said the last tap's target cannot be reached: leave it alone for a minute. */
        private void markUnreachable()
        {
                NPC target = pendingSpot != null ? pendingSpot : lastSpot;
                if (target == null)
                {
                        return;
                }
                unreachableUntil.put(target, System.currentTimeMillis() + 60_000L);
                if (unreachableUntil.size() > 64)
                {
                        unreachableUntil.clear();
                }
                pendingSpot = null;
                pendingTicks = 0;
                if (input.logging())
                {
                        Log.i(LOG_TAG, "target unreachable, skipping it for a minute");
                }
        }

        private void rescan()
        {
                refreshSpots();
                updateSpots();
                npcOverlayService.rebuild();
        }

        private void clearAutomation()
        {
                waitTicks = 0;
                pendingSpot = null;
                pendingTicks = 0;
                lastSpot = null;
                pendingAttempts = 0;
                dropping = false;
                lastDropCount = -1;
                stalledDropPasses = 0;
        }

        private void updateSpots()
        {
                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        currentSpot = null;
                        nextSpot = null;
                        return;
                }

                // Interacting with a spot = fishing it. Spots are shared: other players fishing
                // the same spot is normal, so no "skip others" rule here.
                NPC fishing = null;
                Actor interacting = local.getInteracting();
                if (interacting instanceof NPC && spots.contains(interacting))
                {
                        fishing = (NPC) interacting;
                }
                currentSpot = fishing;

                // Keep the existing choice while it is still valid, so the highlight does not
                // flicker between equally good candidates every tick.
                if (!isValidNext(nextSpot))
                {
                        nextSpot = pickNext(local);
                }
        }

        private boolean isValidNext(NPC npc)
        {
                return npc != null && npc != currentSpot && spots.contains(npc);
        }

        /** Nearest spot, or a random pick from the nearest few when a spot pool is set. */
        private NPC pickNext(Player local)
        {
                WorldPoint me = local.getWorldLocation();
                List<NPC> candidates = new ArrayList<>();
                for (NPC npc : spots)
                {
                        if (npc != currentSpot)
                        {
                                candidates.add(npc);
                        }
                }
                if (candidates.isEmpty())
                {
                        return null;
                }
                candidates.sort((a, b) -> Integer.compare(
                        a.getWorldLocation().distanceTo(me),
                        b.getWorldLocation().distanceTo(me)));

                int pool = Math.min(Math.max(config.spotPool(), 1), candidates.size());
                return candidates.get(pool <= 1 ? 0 : random.nextInt(pool));
        }

        private HighlightedNpc highlight(NPC npc)
        {
                if (!isSpot(npc))
                {
                        return null;
                }

                Color color;
                if (npc == currentSpot)
                {
                        color = config.currentColor();
                }
                else if (config.highlightNext() && npc == nextSpot)
                {
                        color = config.nextColor();
                }
                else
                {
                        color = config.otherColor();
                }

                return HighlightedNpc.builder()
                        .npc(npc)
                        .highlightColor(color)
                        .fillColor(config.fillColor())
                        .hull(config.hull())
                        .tile(config.tile())
                        .trueTile(config.trueTile())
                        .outline(config.outline())
                        .name(config.name())
                        .nameOnMinimap(config.minimap())
                        .borderWidth((float) config.borderWidth())
                        .outlineFeather(config.outlineFeather())
                        .build();
        }

        private void resetSession()
        {
                catches = 0;
                sessionStart = null;
                idleCount = 0;
                idleNotified = false;
                fullNotified = false;
        }


        // ---------------------------------------------------------------- automation


        /**
         * One decision per tick, on the client thread. The full inventory is handled before
         * anything else (there is no point clicking spots you cannot catch from), and while
         * we are fishing a spot nothing else is attempted.
         */
        private void automate()
        {
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

                boolean full = inventoryFull();
                if (full && config.fullInvMode() != AutoFishConfig.FullInventoryMode.OFF)
                {
                        dropping = true;
                }
                if (config.fullInvMode() == AutoFishConfig.FullInventoryMode.OFF)
                {
                        dropping = false;
                }

                if (full || dropping)
                {
                        pendingSpot = null;
                        pendingTicks = 0;

                        if (!dropping)
                        {
                                if (!fullNotified)
                                {
                                        fullNotified = true;
                                        notifier.notify(config.notifyFull(), "Inventory full");
                                }
                                return;
                        }

                        if (input.isBusy())
                        {
                                return;
                        }
                        if (!GameHelpers.openInventoryTab(client, input))
                        {
                                waitTicks = 1; // tapped the inventory tab; drop once it is open
                                return;
                        }

                        // Every droppable item still in the bag, in this pass's wave order. Keep
                        // making passes until nothing is left; only then go back to fishing.
                        List<HumanInput.DropTarget> plan = config.fullInvMode() == AutoFishConfig.FullInventoryMode.OFF
                                ? Collections.emptyList()
                                : input.slotTargets(ComponentID.INVENTORY_CONTAINER, this::isDroppableItem);
                        if (plan.size() > 1)
                        {
                                input.waveOrder(plan);
                        }
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
                                // Bag emptied: fall through and fish straight away.
                        }
                        else
                        {
                                // Two passes in a row that dropped nothing means shift-click drop is
                                // probably off in the game settings: fall back to long-press drops.
                                stalledDropPasses = plan.size() >= lastDropCount && lastDropCount >= 0 ? stalledDropPasses + 1 : 0;
                                lastDropCount = plan.size();
                                boolean useShift = config.shiftDrop() && stalledDropPasses < 2;
                                if (config.shiftDrop() && !useShift && input.logging())
                                {
                                        Log.w(LOG_TAG, "shift-drop made no progress; using long-press drops (is 'Shift click to drop items' on?)");
                                }
                                boolean scheduled = useShift ? input.shiftDrops(plan) : input.drops(plan);
                                waitTicks = scheduled ? random.nextInt(2) : 1;
                                return;
                        }
                }
                fullNotified = false;

                // Fishing: the click landed, so drop any pending state.
                if (currentSpot != null)
                {
                        pendingSpot = null;
                        pendingTicks = 0;
                        lastSpot = null;
                        pendingAttempts = 0;
                        return;
                }

                // A click was sent but fishing never started (spot moved, click lost, spot taken
                // by nothing at all). Give it a few ticks, then fall through and try again rather
                // than waiting on it forever.
                if (pendingSpot != null)
                {
                        pendingTicks++;
                        if (pendingTicks < FISH_WAIT_TICKS && !tapMissed(local) && spots.contains(pendingSpot))
                        {
                                return;
                        }
                        if (pendingTicks < FISH_WAIT_TICKS)
                        {
                                // Noticed the tap did nothing: fix it straight away.
                                missNoticed = true;
                                waitTicks = 0;
                        }
                        pendingSpot = null;
                        pendingTicks = 0;
                }

                if (config.autoFish() && nextSpot != null)
                {
                        NPC victim = nextSpot;
                        if (victim != lastSpot)
                        {
                                lastSpot = victim;
                                pendingAttempts = 0;
                        }

                        // Safety valve: nothing is sticking to this spot (no rod? action not landing?).
                        // Fidget for a while, then let pickNext choose again.
                        if (pendingAttempts >= MAX_SPOT_ATTEMPTS)
                        {
                                pendingAttempts = 0;
                                nextSpot = null;
                                lastSpot = null;
                                waitTicks = 8 + random.nextInt(16);
                                return;
                        }

                        // A first retry right after a lost click is the classic human double-tap:
                        // short pad, fresh spot on the target.
                        if (input.isBusy())
                        {
                                return;
                        }
                        String wanted = spotAction(victim);
                        if (wanted == null)
                        {
                                return;
                        }
                        boolean quick = missNoticed || input.quickRetry(pendingAttempts);
                        missNoticed = false;
                        // Off-screen spots turn the camera instead (interact returns false; retried next tick).
                        if (input.interact(spotTarget(victim), wanted, actionIsDefaultAction(victim, wanted), quick, true))
                        {
                                pendingSpot = victim;
                                pendingTicks = 0;
                                pendingAttempts++;
                                waitTicks = input.reactionDelay();
                        }
                }
        }

        /**
         * Two ticks after a tap the player should be fishing the spot or at least walking there.
         * Standing still with nothing targeted means the tap was lost.
         */
        private boolean tapMissed(Player local)
        {
                if (pendingTicks < 2 || local == null)
                {
                        return false;
                }
                return local.getInteracting() == null
                        && client.getLocalDestinationLocation() == null
                        && local.getPoseAnimation() == local.getIdlePoseAnimation();
        }

        private boolean inventoryFull()
        {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                if (inv == null)
                {
                        return false;
                }
                Item[] items = inv.getItems();
                if (items == null || items.length < 28)
                {
                        return false;
                }
                for (Item item : items)
                {
                        int id = item == null ? -1 : item.getId();
                        if (id <= 0 || id == EMPTY_ITEM_ID)
                        {
                                return false;
                        }
                }
                return true;
        }

        /** "used/total" for the overlay panel; "-" when the inventory is not loaded. */
        String getInventoryUsage()
        {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                if (inv == null)
                {
                        return "-";
                }
                Item[] items = inv.getItems();
                if (items == null || items.length == 0)
                {
                        return "-";
                }
                int used = 0;
                for (Item item : items)
                {
                        int id = item == null ? -1 : item.getId();
                        if (id > 0 && id != EMPTY_ITEM_ID)
                        {
                                used++;
                        }
                }
                return used + "/" + items.length;
        }



        private boolean isDroppableItem(int rawId)
        {
                int id = itemManager.canonicalize(rawId);
                if (config.fullInvMode() == AutoFishConfig.FullInventoryMode.DROP_LISTED)
                {
                        return dropIds.contains(id)
                                || (!dropNames.isEmpty() && dropNames.contains(itemName(id).toLowerCase(Locale.ROOT)));
                }
                // DROP_ALL_BUT_KEPT
                return !(keepIds.contains(id)
                        || (!keepNames.isEmpty() && keepNames.contains(itemName(id).toLowerCase(Locale.ROOT))));
        }

        private String itemName(int id)
        {
                return Text.removeTags(String.valueOf(itemManager.getItemComposition(id).getName()));
        }

        // ---------------------------------------------------------------- events

        @Subscribe
        public void onConfigChanged(ConfigChanged event)
        {
                if (!AutoFishConfig.GROUP.equals(event.getGroup()))
                {
                        return;
                }
                if (AutoFishConfig.SPOT_IDS_KEY.equals(event.getKey()))
                {
                        parseSpotIds();
                        clientThread.invoke(this::rescan);
                }
                else if (AutoFishConfig.FISHING_ACTIONS_KEY.equals(event.getKey()))
                {
                        parseActions();
                        clientThread.invoke(this::rescan);
                }
                else if (AutoFishConfig.DROP_ITEMS_KEY.equals(event.getKey()))
                {
                        parseDrop();
                }
                else if (AutoFishConfig.KEEP_ITEMS_KEY.equals(event.getKey()))
                {
                        parseKeep();
                }
                else if (AutoFishConfig.CENTER_KEY.equals(event.getKey()))
                {
                        parseCenter();
                        clientThread.invoke(this::rescan);
                }
                else
                {
                        clientThread.invoke(npcOverlayService::rebuild);
                }
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged event)
        {
                if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
                {
                        input.reset();
                        spots.clear();
                        currentSpot = null;
                        nextSpot = null;
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
                        markUnreachable();
                        return;
                }
                if (message.startsWith("You catch "))
                {
                        if (sessionStart == null)
                        {
                                sessionStart = Instant.now();
                        }
                        catches++;
                        notifier.notify(config.notifyCatch(), message + " (" + catches + ")");
                        input.onProgress();
                }
        }

        @Subscribe
        public void onGameTick(GameTick tick)
        {
                NPC prevCurrent = currentSpot;
                NPC prevNext = nextSpot;

                refreshSpots();
                updateSpots();

                if (prevCurrent != currentSpot || prevNext != nextSpot)
                {
                        npcOverlayService.rebuild();
                }

                if (currentSpot != null && sessionStart == null)
                {
                        sessionStart = Instant.now();
                }

                // Idle alert: only while spots are around and a session has started.
                if (currentSpot == null && !spots.isEmpty() && sessionStart != null)
                {
                        idleCount++;
                        if (idleCount >= config.idleTicks() && !idleNotified)
                        {
                                idleNotified = true;
                                notifier.notify(config.notifyIdle(), "You're not fishing");
                        }
                }
                else
                {
                        idleCount = 0;
                        idleNotified = false;
                }

                // Rotates the input style; idles the finger while a catch is in progress.
                input.onGameTick(config.autoFish() && currentSpot != null);

                automate();
        }

        @Subscribe
        public void onClientTick(ClientTick tick)
        {
                input.onClientTick();
        }

        /**
         * Resolves a pending menu scan: locates the wanted row (a fishing action, "Drop",
         * ...) in the open context menu (in stretched canvas space, where taps are
         * dispatched) and hands the point to the waiting gesture. When no such row exists,
         * a safe dismiss point is produced instead so the gesture can tap the menu away.
         */



        @Subscribe
        public void onMenuEntryAdded(MenuEntryAdded event)
        {
                MenuEntry entry = event.getMenuEntry();
                if (entry.getNpc() != null)
                {
                        return;
                }
                addCenterEntries(entry);
        }

        /** Lets the player right-click (long-press) the ground and mark the area centre. */
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
                configManager.setConfiguration(AutoFishConfig.GROUP, AutoFishConfig.CENTER_KEY,
                        point == null ? "" : point.getX() + "," + point.getY() + "," + point.getPlane());
                // ConfigChanged will reparse and rescan.
        }

        // ---------------------------------------------------------------- small helpers


        // ---------------------------------------------------------------- overlay accessors

        String getPersonaLabel()
        {
                return input == null ? "-" : input.getPersonaLabel();
        }

        long getMethodSwitchInSeconds()
        {
                return input == null ? -1 : input.getMethodSwitchInSeconds();
        }

        /** For the Scripts panel: whether automation is on. */
        public boolean isAutomationOn()
        {
                return config.autoFish();
        }

        private Target spotTarget(NPC spot)
        {
                return Targets.npc(spot, () -> spots.contains(spot));
        }

        int getCatches()
        {
                return catches;
        }

        int getSpotCount()
        {
                return spots.size();
        }

        NPC getCurrentSpot()
        {
                return currentSpot;
        }

        NPC getNextSpot()
        {
                return nextSpot;
        }

        double getCatchesPerHour()
        {
                if (sessionStart == null || catches == 0)
                {
                        return 0;
                }
                long ms = Duration.between(sessionStart, Instant.now()).toMillis();
                return ms <= 0 ? 0 : catches * 3_600_000.0 / ms;
        }

        void resetCounter()
        {
                resetSession();
        }
}
