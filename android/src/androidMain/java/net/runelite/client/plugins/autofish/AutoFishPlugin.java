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
        tags = {"fishing", "spot", "highlight", "counter", "mobile", "id", "drop", "camera", "humanizer"}
)
public class AutoFishPlugin extends Plugin
{
        private static final String SET_CENTER = "FISH-Set centre";
        private static final String CLEAR_CENTER = "FISH-Clear centre";

        private static final String LOG_TAG = "AutoFish";

        /** Ticks to wait for fishing to actually start before assuming the click was lost. */
        private static final int FISH_WAIT_TICKS = 6;

        /** Attempts on the same spot before pausing and re-picking (e.g. no rod equipped). */
        private static final int MAX_SPOT_ATTEMPTS = 4;

        /** Camera yaw is measured in JAU14: 2^14 units to a full turn. */
        private static final int JAU14_MASK = 0x3FFF;
        private static final double JAU14_PER_RADIAN = 16384.0d / (2.0d * Math.PI);

        /** Turns smaller than this are not worth animating. */
        private static final int CAMERA_DEADZONE = 150;

        /** The context menu's "Choose Option" header height, in canvas pixels (real space). */
        private static final int MENU_HEADER_HEIGHT = 19;

        /** Camera pitch clamp in JAU14. */
        private static final int PITCH_MIN = 128;
        private static final int PITCH_MAX = 383;

        /** Ground-misclick correction window: the re-tap lands this many ms after the slip. */
        private static final int MISCORRECT_MIN_MS = 50;
        private static final int MISCORRECT_MAX_MS = 97;

        /** Rate limit for off-screen rescue camera turns. */
        private static final long RESCUE_COOLDOWN_MS = 15_000L;

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
        private final List<NPC> spots = new ArrayList<>();

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

        private int waitTicks;
        private NPC pendingSpot;
        private int pendingTicks;
        private NPC lastSpot;
        private int pendingAttempts;

        private WorldPoint huntCenter; // null = centre on the player

        // ---- input style (persona) rotation ----

        private volatile Persona persona;
        private volatile long personaChangedAt;
        private volatile long nextPersonaSwitchAt;

        // ---- camera ----

        private int catchesSinceTurn;
        private int nextTurnCatches;
        private CameraAnim yawAnim;
        private CameraAnim pitchAnim;
        private long lastRescueMs;

        // ---- menu scan handshake: client thread resolves, touch thread consumes ----

        private volatile boolean menuScanPending;
        private volatile long menuScanRequestedAt;
        private volatile String menuScanWanted;    // option text to find, e.g. "Lure" or "Drop"
        private volatile int[] menuActionPoint;    // stretched canvas coords
        private volatile int menuActionKind;       // 0 none, 1 wanted row, 2 dismiss point

        private long nextHoverDriftAt;

        private final Function<NPC, HighlightedNpc> highlighter = this::highlight;

        private final TouchEngine touch = new TouchEngine();

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
                catchesSinceTurn = 0;
                rollNextTurnCatches();
                pickInitialPersona();
                nextHoverDriftAt = 0L;
                lastRescueMs = 0L;
                yawAnim = null;
                pitchAnim = null;
                menuScanPending = false;
                menuScanWanted = null;
                menuActionPoint = null;
                menuActionKind = 0;
                touch.start();
                overlayManager.add(overlay);
                overlayManager.add(huntOverlay);
                npcOverlayService.registerHighlighter(highlighter);
                clientThread.invoke(this::rescan);
        }

        @Override
        protected void shutDown()
        {
                touch.stop();
                npcOverlayService.unregisterHighlighter(highlighter);
                overlayManager.remove(overlay);
                overlayManager.remove(huntOverlay);
                spots.clear();
                currentSpot = null;
                nextSpot = null;
                clearAutomation();
                resetSession();
                yawAnim = null;
                pitchAnim = null;
                menuScanPending = false;
                menuScanWanted = null;
                menuActionPoint = null;
                menuActionKind = 0;
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
                for (NPC npc : wv.npcs())
                {
                        if (isSpot(npc) && inHuntRange(npc, center))
                        {
                                spots.add(npc);
                        }
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


        // ---------------------------------------------------------------- input styles

        /** Where on the spot's clickbox the simulated finger aims. */
        private enum Placement
        {
                /** Gaussian around the hull centre — quick, confident taps. */
                GAUSSIAN_CENTER,
                /** Anywhere inside the hull — sloppy, wandering taps. */
                UNIFORM_HULL,
                /** Biased toward the lower body — a lazy, tired finger. */
                LOWER_BIAS,
                /** Middle 60% of the hull — careful, deliberate taps (also used for menu long-presses). */
                TIGHT_BOX
        }

        /**
         * A bundle of input habits. Rotation between these is what makes the "input method"
         * change every few minutes: the tap placement strategy, the pacing, the misclick rate
         * and the menu preference all move together, the way a real player's hands settle
         * into a rhythm and then drift out of it.
         */
        private static final class Persona
        {
                final String label;
                final Placement placement;
                final int tapDurMin;
                final int tapDurMax;
                final int menuChance;      // % of clicks done via long-press + menu
                final int approachChance;  // % of taps preceded by a hover approach trail
                final int misclickChance;  // base % (scaled by the config setting)
                final int padMin;          // ms of extra reaction padding
                final int padMax;
                final int hoverChance;     // % chance of an idle hover drift while fishing
                final int doubleTapChance; // % chance the first retry is a quick double-tap

                Persona(String label, Placement placement,
                        int tapDurMin, int tapDurMax, int menuChance, int approachChance, int misclickChance,
                        int padMin, int padMax, int hoverChance, int doubleTapChance)
                {
                        this.label = label;
                        this.placement = placement;
                        this.tapDurMin = tapDurMin;
                        this.tapDurMax = tapDurMax;
                        this.menuChance = menuChance;
                        this.approachChance = approachChance;
                        this.misclickChance = misclickChance;
                        this.padMin = padMin;
                        this.padMax = padMax;
                        this.hoverChance = hoverChance;
                        this.doubleTapChance = doubleTapChance;
                }
        }

        private static final List<Persona> PERSONAS = Collections.unmodifiableList(Arrays.asList(
                new Persona("Swift tapper", Placement.GAUSSIAN_CENTER, 45, 95, 0, 10, 6, 40, 220, 55, 12),
                new Persona("Cautious menuer", Placement.TIGHT_BOX, 90, 160, 70, 40, 9, 150, 500, 40, 6),
                new Persona("Wandering hover", Placement.UNIFORM_HULL, 60, 130, 15, 70, 14, 90, 380, 80, 9),
                new Persona("Weary grinder", Placement.LOWER_BIAS, 110, 210, 30, 30, 12, 250, 700, 45, 15)
        ));

        private void pickInitialPersona()
        {
                persona = PERSONAS.get(random.nextInt(PERSONAS.size()));
                personaChangedAt = System.currentTimeMillis();
                scheduleNextPersonaSwitch();
        }

        private void scheduleNextPersonaSwitch()
        {
                int min = Math.max(config.methodRotationMinSec(), 60);
                int max = Math.max(config.methodRotationMaxSec(), min);
                int holdSec = min + (max > min ? random.nextInt(max - min + 1) : 0);
                nextPersonaSwitchAt = System.currentTimeMillis() + holdSec * 1000L;
        }

        /** Swaps the input style when its time is up; called once per game tick. */
        private void maybeRotatePersona()
        {
                if (!config.rotateInputMethod())
                {
                        return;
                }
                if (System.currentTimeMillis() < nextPersonaSwitchAt)
                {
                        return;
                }
                Persona current = persona;
                Persona next = current;
                for (int i = 0; i < 4 && next == current; i++)
                {
                        next = PERSONAS.get(random.nextInt(PERSONAS.size()));
                }
                persona = next;
                personaChangedAt = System.currentTimeMillis();
                scheduleNextPersonaSwitch();
                if (config.logInput())
                {
                        Log.i(LOG_TAG, "input style -> " + next.label);
                }
        }

        String getPersonaLabel()
        {
                Persona p = persona;
                return p == null ? "-" : p.label;
        }

        long getMethodSwitchInSeconds()
        {
                if (!config.rotateInputMethod())
                {
                        return -1;
                }
                long ms = nextPersonaSwitchAt - System.currentTimeMillis();
                return Math.max(0, (ms + 999) / 1000);
        }

        /** Effective ground-misclick chance: the config setting scaled by the active style. */
        private int effectiveMisclickChance(Persona p)
        {
                int base = config.misclickChance();
                if (base <= 0 || p == null)
                {
                        return 0;
                }
                return Math.max(1, (int) Math.round(base * p.misclickChance / 10.0d));
        }

        private int personaTapDur(Persona p)
        {
                if (p == null)
                {
                        return 60 + random.nextInt(60);
                }
                int min = p.tapDurMin;
                int max = Math.max(p.tapDurMax, min);
                return min + random.nextInt(max - min + 1);
        }

        private int personaPad(Persona p)
        {
                if (p == null)
                {
                        return 60 + random.nextInt(240);
                }
                int min = p.padMin;
                int max = Math.max(p.padMax, min);
                return min + random.nextInt(max - min + 1);
        }

        // ---------------------------------------------------------------- automation

        /** Random pause before an automated click, so the cadence is not metronomic. */
        private int reactionDelay()
        {
                int min = Math.max(config.reactionMin(), 0);
                int max = Math.max(config.reactionMax(), min);
                int delay = min + (max > min ? random.nextInt(max - min + 1) : 0);
                if (config.hesitateChance() > 0 && random.nextInt(100) < config.hesitateChance())
                {
                        delay += 1 + random.nextInt(3);
                }
                return delay;
        }

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

                if (inventoryFull())
                {
                        pendingSpot = null;
                        pendingTicks = 0;

                        if (config.fullInvMode() == AutoFishConfig.FullInventoryMode.OFF)
                        {
                                if (!fullNotified)
                                {
                                        fullNotified = true;
                                        notifier.notify(config.notifyFull(), "Inventory full");
                                }
                                return;
                        }

                        if (!touch.isBusy())
                        {
                                List<DropTarget> plan = buildDropPlan();
                                if (plan.isEmpty())
                                {
                                        if (!fullNotified)
                                        {
                                                fullNotified = true;
                                                notifier.notify(config.notifyFull(), "Inventory full — nothing droppable");
                                        }
                                        return;
                                }
                                if (touch.drops(plan))
                                {
                                        waitTicks = 1 + random.nextInt(2);
                                }
                                else
                                {
                                        waitTicks = 1;
                                }
                        }
                        return;
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
                        if (pendingTicks < FISH_WAIT_TICKS && spots.contains(pendingSpot))
                        {
                                return;
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

                        if (!npcVisibleForTap(victim))
                        {
                                // Cannot tap what is not on screen: turn the camera (slowly, humanized)
                                // and let the next ticks retry once the spot swings into view.
                                maybeRescueCamera(victim);
                                return;
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
                        boolean quick = pendingAttempts == 1 && persona != null
                                && random.nextInt(100) < persona.doubleTapChance;

                        if (touch.fish(victim, quick))
                        {
                                pendingSpot = victim;
                                pendingTicks = 0;
                                pendingAttempts++;
                                waitTicks = reactionDelay();
                        }
                }
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

        /**
         * Builds the next batch of inventory drops: the droppable items in reading order
         * (top-left to bottom-right, like a player emptying their bag), a few at a time.
         * Empty when dropping is off or nothing is droppable.
         */
        private List<DropTarget> buildDropPlan()
        {
                if (config.fullInvMode() == AutoFishConfig.FullInventoryMode.OFF)
                {
                        return Collections.emptyList();
                }

                Widget invWidget = client.getWidget(ComponentID.INVENTORY_CONTAINER);
                if (invWidget == null)
                {
                        return Collections.emptyList();
                }
                Widget[] children = invWidget.getChildren();
                if (children == null || children.length == 0)
                {
                        return Collections.emptyList();
                }

                int w = canvasWidth();
                int h = canvasHeight();
                if (w <= 0 || h <= 0)
                {
                        return Collections.emptyList();
                }

                List<DropTarget> plan = new ArrayList<>();
                for (Widget child : children)
                {
                        if (child == null)
                        {
                                continue;
                        }
                        int itemId = child.getItemId();
                        if (itemId <= 0 || itemId == EMPTY_ITEM_ID)
                        {
                                continue;
                        }
                        if (!isDroppableItem(itemId))
                        {
                                continue;
                        }
                        Point loc = child.getCanvasLocation();
                        if (loc == null)
                        {
                                continue;
                        }
                        int cw = child.getWidth();
                        int ch = child.getHeight();
                        if (cw <= 0 || ch <= 0)
                        {
                                continue;
                        }

                        // A fresh spot inside the slot: hull-less cousin of the NPC placement, with
                        // the same time-derived nudge so no two long-presses hit the same pixel.
                        int realX = loc.getX() + cw / 2 + (int) Math.round((random.nextDouble() - 0.5d) * cw * 0.4d);
                        int realY = loc.getY() + ch / 2 + (int) Math.round((random.nextDouble() - 0.5d) * ch * 0.4d);
                        long t = System.nanoTime();
                        realX += (int) ((t >>> 11) & 0x7L) - 3;
                        realY += (int) ((t >>> 17) & 0x7L) - 3;

                        int[] s = toStretched(realX, realY);
                        if (s[0] < 6 || s[1] < 6 || s[0] >= w - 6 || s[1] >= h - 6)
                        {
                                continue;
                        }
                        // Note: no interface hit-test here — the inventory is exactly the interface
                        // we are supposed to tap.
                        plan.add(new DropTarget(s[0], s[1], itemName(itemId)));
                }

                if (plan.isEmpty())
                {
                        return plan;
                }
                int batch = Math.min(plan.size(), 2 + random.nextInt(3));
                return new ArrayList<>(plan.subList(0, batch));
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

        // ---------------------------------------------------------------- screen geometry

        /** Real→stretched canvas scale factors {x, y} (1,1 when stretching is off). */
        private double[] stretchFactors()
        {
                if (!client.isStretchedEnabled())
                {
                        return new double[]{1.0d, 1.0d};
                }
                java.awt.Dimension real = client.getRealDimensions();
                java.awt.Dimension stretched = client.getStretchedDimensions();
                if (real == null || stretched == null || real.width <= 0 || real.height <= 0)
                {
                        return new double[]{1.0d, 1.0d};
                }
                return new double[]{stretched.width / (double) real.width, stretched.height / (double) real.height};
        }

        /** Converts a real-space canvas point into stretched (dispatch) space. */
        private int[] toStretched(int realX, int realY)
        {
                double[] f = stretchFactors();
                return new int[]{
                        (int) Math.round(realX * f[0]),
                        (int) Math.round(realY * f[1])
                };
        }

        private int canvasWidth()
        {
                Canvas canvas = Canvas.latest();
                return canvas == null ? 0 : canvas.getWidth();
        }

        private int canvasHeight()
        {
                Canvas canvas = Canvas.latest();
                return canvas == null ? 0 : canvas.getHeight();
        }

        /**
         * Picks a fresh spot on the spot's clickbox (convex hull), converts it into stretched
         * canvas space, and nudges it with a time-derived wobble so the same pixel is never
         * tapped twice in a row. Null when the spot is not tappable (off screen, behind a
         * relocated interface, or outside the canvas).
         */
        private int[] npcTapPoint(NPC npc, Placement placement)
        {
                int[] real = hullPoint(npc, placement);
                if (real == null)
                {
                        // No hull (unusual view angle): aim at the projected body centre instead.
                        LocalPoint lp = npc.getLocalLocation();
                        if (lp == null)
                        {
                                return null;
                        }
                        WorldView wv = client.getTopLevelWorldView();
                        int plane = wv == null ? 0 : wv.getPlane();
                        int height = Math.max(npc.getLogicalHeight() / 2, 40);
                        Point c = Perspective.localToCanvas(client, lp, plane, height);
                        if (c == null)
                        {
                                return null;
                        }
                        real = new int[]{c.getX(), c.getY()};
                }

                int[] s = toStretched(real[0], real[1]);

                // Time-derived nudge: two taps a frame apart can never land on the same pixel.
                long t = System.nanoTime();
                s[0] += (int) ((t >>> 11) & 0x7L) - 3;
                s[1] += (int) ((t >>> 17) & 0x7L) - 3;

                int w = canvasWidth();
                int h = canvasHeight();
                if (w <= 0 || h <= 0 || s[0] < 6 || s[1] < 6 || s[0] >= w - 6 || s[1] >= h - 6)
                {
                        return null;
                }
                if (MobileHitTest.isInterfaceAt(s[0], s[1]))
                {
                        // Behind a relocated mobile interface panel; a real player would not tap here.
                        return null;
                }
                return s;
        }

        /** Random point inside the NPC's hull in real canvas space, per the placement strategy. */
        private int[] hullPoint(NPC npc, Placement placement)
        {
                Shape hull = npc.getConvexHull();
                if (hull == null)
                {
                        return null;
                }
                Rectangle b = hull.getBounds();
                if (b.width < 3 || b.height < 3)
                {
                        return null;
                }
                int cx = b.x + b.width / 2;
                int cy = b.y + b.height / 2;

                switch (placement)
                {
                        case GAUSSIAN_CENTER:
                        {
                                int x = cx + (int) Math.round(random.nextGaussian() * b.width * 0.16d);
                                int y = cy + (int) Math.round(random.nextGaussian() * b.height * 0.16d);
                                return clampToRect(x, y, b);
                        }
                        case UNIFORM_HULL:
                        {
                                // Mix a little of the current time into the first candidate angle,
                                // then fall back to plain rejection sampling inside the hull.
                                long t = System.nanoTime();
                                double ang = ((t >>> 9) % 360L) * Math.PI / 180.0d;
                                double rad = 0.3d + random.nextDouble() * 0.65d;
                                int x = (int) Math.round(cx + Math.cos(ang) * rad * b.width / 2.0d);
                                int y = (int) Math.round(cy + Math.sin(ang) * rad * b.height / 2.0d);
                                if (hull.contains(x, y))
                                {
                                        return new int[]{x, y};
                                }
                                for (int i = 0; i < 12; i++)
                                {
                                        x = b.x + random.nextInt(b.width);
                                        y = b.y + random.nextInt(b.height);
                                        if (hull.contains(x, y))
                                        {
                                                return new int[]{x, y};
                                        }
                                }
                                return new int[]{cx, cy};
                        }
                        case LOWER_BIAS:
                        {
                                int x = cx + (int) Math.round(random.nextGaussian() * b.width * 0.18d);
                                int y = (int) Math.round(cy + b.height * 0.15d + random.nextGaussian() * b.height * 0.12d);
                                return clampToRect(x, y, b);
                        }
                        case TIGHT_BOX:
                        default:
                        {
                                int x0 = b.x + (int) (b.width * 0.2d);
                                int x1 = b.x + (int) (b.width * 0.8d);
                                int y0 = b.y + (int) (b.height * 0.2d);
                                int y1 = b.y + (int) (b.height * 0.8d);
                                for (int i = 0; i < 8; i++)
                                {
                                        int x = x0 + random.nextInt(Math.max(1, x1 - x0));
                                        int y = y0 + random.nextInt(Math.max(1, y1 - y0));
                                        if (hull.contains(x, y))
                                        {
                                                return new int[]{x, y};
                                        }
                                }
                                return new int[]{cx, cy};
                        }
                }
        }

        private static int[] clampToRect(int x, int y, Rectangle b)
        {
                return new int[]{
                        Math.max(b.x, Math.min(b.x + b.width - 1, x)),
                        Math.max(b.y, Math.min(b.y + b.height - 1, y))
                };
        }

        /** True when the NPC's clickbox is at least partly on the canvas. */
        private boolean npcVisibleForTap(NPC npc)
        {
                Shape hull = npc.getConvexHull();
                if (hull == null)
                {
                        return false;
                }
                Rectangle b = hull.getBounds();
                int[] p1 = toStretched(b.x, b.y);
                int[] p2 = toStretched(b.x + b.width, b.y + b.height);
                int w = canvasWidth();
                int h = canvasHeight();
                return w > 0 && h > 0 && p2[0] >= 0 && p1[0] <= w && p2[1] >= 0 && p1[1] <= h;
        }

        /**
         * A water point just outside the spot's clickbox, for the occasional misclick:
         * a tile within 1-2 of the spot's tile that is on screen, not on an interface, and
         * does not overlap the hull itself.
         */
        private int[] groundMisclickPoint(NPC npc)
        {
                WorldPoint at = npc.getWorldLocation();
                WorldView wv = client.getTopLevelWorldView();
                if (at == null || wv == null)
                {
                        return null;
                }

                Shape hull = npc.getConvexHull();
                Rectangle hullReal = hull == null ? null : hull.getBounds();
                int[] hullCentre = null;
                int hx0 = Integer.MIN_VALUE, hy0 = Integer.MIN_VALUE, hx1 = Integer.MIN_VALUE, hy1 = Integer.MIN_VALUE;
                if (hullReal != null && hullReal.width > 0)
                {
                        int[] c = toStretched(hullReal.x + hullReal.width / 2, hullReal.y + hullReal.height / 2);
                        int[] p0 = toStretched(hullReal.x, hullReal.y);
                        int[] p1 = toStretched(hullReal.x + hullReal.width, hullReal.y + hullReal.height);
                        hullCentre = c;
                        hx0 = p0[0] - 4;
                        hy0 = p0[1] - 4;
                        hx1 = p1[0] + 4;
                        hy1 = p1[1] + 4;
                }
                if (hullCentre == null)
                {
                        LocalPoint lp = npc.getLocalLocation();
                        if (lp != null)
                        {
                                Point c = Perspective.localToCanvas(client, lp, at.getPlane());
                                if (c != null)
                                {
                                        hullCentre = toStretched(c.getX(), c.getY());
                                }
                        }
                }

                int w = canvasWidth();
                int h = canvasHeight();
                if (hullCentre == null || w <= 0 || h <= 0)
                {
                        return null;
                }

                int[] best = null;
                int bestDist = Integer.MAX_VALUE;
                for (int attempt = 0; attempt < 6; attempt++)
                {
                        int ox = random.nextInt(5) - 2;
                        int oy = random.nextInt(5) - 2;
                        if (ox == 0 && oy == 0)
                        {
                                ox = random.nextBoolean() ? 1 : -1;
                        }
                        LocalPoint lp = LocalPoint.fromWorld(wv, at.getX() + ox, at.getY() + oy);
                        if (lp == null)
                        {
                                continue;
                        }
                        Point c = Perspective.localToCanvas(client, lp, at.getPlane());
                        if (c == null)
                        {
                                continue;
                        }
                        int[] s = toStretched(c.getX(), c.getY());
                        s[0] += signedJitter(10);
                        s[1] += signedJitter(10);
                        if (s[0] < 8 || s[1] < 8 || s[0] >= w - 8 || s[1] >= h - 8)
                        {
                                continue;
                        }
                        if (MobileHitTest.isInterfaceAt(s[0], s[1]))
                        {
                                continue;
                        }
                        if (s[0] >= hx0 && s[0] <= hx1 && s[1] >= hy0 && s[1] <= hy1)
                        {
                                continue; // still on the spot itself
                        }
                        int d = Math.abs(s[0] - hullCentre[0]) + Math.abs(s[1] - hullCentre[1]);
                        if (d < bestDist)
                        {
                                bestDist = d;
                                best = s;
                        }
                }
                return best;
        }

        /**
         * A dismiss point for the currently open context menu, in stretched canvas space,
         * or null when no menu is open / no safe point exists. Client thread only.
         */
        private int[] currentMenuDismissPoint()
        {
                if (!client.isMenuOpen())
                {
                        return null;
                }
                double[] f = stretchFactors();
                return playerDismissPoint(
                        (int) Math.round(client.getMenuX() * f[0]),
                        (int) Math.round(client.getMenuY() * f[1]),
                        (int) Math.round(client.getMenuWidth() * f[0]),
                        (int) Math.round(client.getMenuHeight() * f[1]));
        }

        /** A harmless tap point: the player's own tile, pushed out of the given menu rect. */
        private int[] playerDismissPoint(int menuX, int menuY, int menuW, int menuH)
        {
                Player local = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (local == null || local.getLocalLocation() == null || wv == null)
                {
                        return null;
                }
                Point c = Perspective.localToCanvas(client, local.getLocalLocation(), wv.getPlane());
                if (c == null)
                {
                        return null;
                }
                int[] s = toStretched(c.getX(), c.getY());
                int w = canvasWidth();
                int h = canvasHeight();
                if (w <= 0 || h <= 0)
                {
                        return null;
                }

                if (menuW > 0 && menuH > 0
                        && s[0] >= menuX && s[0] <= menuX + menuW && s[1] >= menuY && s[1] <= menuY + menuH)
                {
                        s[1] = menuY + menuH + 30;
                        if (s[1] >= h - 8)
                        {
                                s[1] = Math.max(8, menuY - 30);
                        }
                }
                s[0] = Math.max(8, Math.min(w - 9, s[0]));
                s[1] = Math.max(8, Math.min(h - 9, s[1]));

                if (menuW > 0 && menuH > 0
                        && s[0] >= menuX && s[0] <= menuX + menuW && s[1] >= menuY && s[1] <= menuY + menuH)
                {
                        return null; // could not get clear of the menu
                }
                if (MobileHitTest.isInterfaceAt(s[0], s[1]))
                {
                        return null;
                }
                return s;
        }


        // ---------------------------------------------------------------- touch engine

        /** One inventory slot to drop: a long-press point in stretched canvas space. */
        private static final class DropTarget
        {
                final int x;
                final int y;
                final String label;

                DropTarget(int x, int y, String label)
                {
                        this.x = x;
                        this.y = y;
                        this.label = label;
                }
        }

        /**
         * Schedules and replays gesture "scripts". A script is a sequence of sleeps and
         * AWT-event emissions run on a single dedicated timeline thread, so gestures never
         * interleave; each emission is posted to the Android main thread, exactly where the
         * Compose touch driver dispatches from.
         *
         * <p>The event shapes replayed here are byte-for-byte what the real driver produces:
         * a tap is two parking {@code MOUSE_MOVED}s at finger-down, then a
         * MOVED/PRESSED/RELEASED/CLICKED burst at finger-up, all at the down position; a
         * long-press menu selection is a BUTTON3 burst at the driver's long-press timeout,
         * a trail of {@code MOUSE_MOVED}s tracking the finger to the menu row, and a BUTTON1
         * burst on the row.</p>
         */
        private final class TouchEngine
        {
                private ScheduledExecutorService timeline;
                private Handler main;
                private volatile boolean running;
                /** Bumped to cancel in-flight scripts (and their queued main-thread posts). */
                private volatile int generation;
                /** True while a fishing/drop gesture is in flight (hover drifts do not count). */
                private volatile boolean busy;
                private volatile boolean hovering;
                /** Last dispatched point, canvas (stretched) space. Engine thread only. */
                private int lastX = -1;
                private int lastY = -1;

                void start()
                {
                        if (running)
                        {
                                return;
                        }
                        timeline = Executors.newSingleThreadScheduledExecutor(r ->
                        {
                                Thread t = new Thread(r, "AutoFish-Touch");
                                t.setDaemon(true);
                                return t;
                        });
                        main = new Handler(Looper.getMainLooper());
                        running = true;
                        generation = 0;
                        busy = false;
                        hovering = false;
                        lastX = -1;
                        lastY = -1;
                }

                void stop()
                {
                        running = false;
                        generation++;
                        ScheduledExecutorService ex = timeline;
                        timeline = null;
                        if (ex != null)
                        {
                                ex.shutdownNow();
                        }
                        Handler h = main;
                        main = null;
                        if (h != null)
                        {
                                h.removeCallbacksAndMessages(null);
                        }
                        busy = false;
                        hovering = false;
                }

                boolean isBusy()
                {
                        return busy;
                }

                /** Drops every in-flight gesture and any events still queued on the main thread. */
                void cancelAll()
                {
                        generation++;
                }

                private boolean aborted(int gen)
                {
                        return !running || gen != generation;
                }

                /** @return false when interrupted (treat as cancel) */
                private boolean sleep(int ms)
                {
                        try
                        {
                                Thread.sleep(Math.max(1, ms));
                                return true;
                        }
                        catch (InterruptedException e)
                        {
                                Thread.currentThread().interrupt();
                                return false;
                        }
                }

                // ---- entry points (called on the client thread) ----

                /**
                 * Starts fishing at a spot with a simulated finger. Chooses between a plain tap
                 * (when the wanted action is the left-click default and the style is not in a
                 * menu mood) and a long-press + menu selection.
                 *
                 * @return true when a gesture was scheduled
                 */
                boolean fish(NPC spot, boolean quickRetry)
                {
                        if (!running || busy || persona == null)
                        {
                                return false;
                        }
                        if (client.getGameState() != GameState.LOGGED_IN)
                        {
                                return false;
                        }

                        String wanted = spotAction(spot);
                        if (wanted == null)
                        {
                                return false;
                        }

                        Persona p = persona;
                        boolean menuForced = !actionIsDefaultAction(spot, wanted);
                        boolean useMenu = menuForced || random.nextInt(100) < p.menuChance;
                        Placement placement = useMenu ? Placement.TIGHT_BOX : p.placement;

                        int[] pt = npcTapPoint(spot, placement);
                        if (pt == null)
                        {
                                return false;
                        }
                        int[] ground = useMenu ? null : groundMisclickPoint(spot);

                        // If a context menu is already open, tap it away first — nobody fishes
                        // through a stale menu.
                        int[] dismiss = currentMenuDismissPoint();
                        if (client.isMenuOpen() && dismiss == null)
                        {
                                return false;
                        }

                        int pad = quickRetry
                                ? 120 + random.nextInt(260)
                                : personaPad(p);
                        if (System.currentTimeMillis() - personaChangedAt < 5000L)
                        {
                                // The style just changed: a brief "getting used to it" pause.
                                pad += 350 + random.nextInt(850);
                        }

                        final int gen = ++generation;
                        final NPC victim = spot;
                        final Persona style = p;
                        final boolean menu = useMenu;
                        final String action = wanted;
                        final int[] point = pt;
                        final int[] groundPoint = ground;
                        final int[] dismissPoint = dismiss;
                        final int padMs = pad;

                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "fish gesture: " + (menu ? "menu-walk" : "tap")
                                        + " '" + action + "' at " + pt[0] + "," + pt[1] + " pad=" + padMs + "ms"
                                        + (ground != null ? " misclick-armed" : ""));
                        }
                        try
                        {
                                timeline.execute(() -> runFish(gen, victim, style, menu, action, point, groundPoint, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * Drops a batch of inventory items with the simulated finger: one long-press +
                 * "Drop" menu walk per item, spaced like a player emptying their bag.
                 *
                 * @return true when a gesture was scheduled
                 */
                boolean drops(List<DropTarget> targets)
                {
                        if (!running || busy || persona == null || targets == null || targets.isEmpty())
                        {
                                return false;
                        }
                        if (client.getGameState() != GameState.LOGGED_IN)
                        {
                                return false;
                        }
                        Persona p = persona;
                        int[] dismiss = currentMenuDismissPoint();
                        if (client.isMenuOpen() && dismiss == null)
                        {
                                return false;
                        }

                        final int gen = ++generation;
                        final Persona style = p;
                        final List<DropTarget> batch = new ArrayList<>(targets);
                        final int[] dismissPoint = dismiss;
                        final int padMs = personaPad(p);

                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "drop gesture: " + batch.size() + " items pad=" + padMs + "ms");
                        }
                        try
                        {
                                timeline.execute(() -> runDrops(gen, style, batch, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * Idle finger drift while waiting on a catch: pure hover moves, no clicks.
                 * The cursor traces Lissajous figures — a cosine oscillator on x and a sine
                 * oscillator on y, with random amplitudes, random phases and a frequency
                 * ratio drawn from near-simple fractions (1:1, 1:2, 2:3, …) plus a small
                 * detune. The detune stops the figure from ever closing on itself, so the
                 * wander looks smooth and curving yet never repeats: two trigonometric
                 * oscillators and a handful of random numbers make every drift unique.
                 */
                void hoverDrift()
                {
                        if (!running || busy || hovering || lastX < 0 || client.isMenuOpen())
                        {
                                return;
                        }
                        final int gen = generation;
                        hovering = true;
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                int cx = lastX;
                                                int cy = lastY;
                                                int w = canvasWidth();
                                                int h = canvasHeight();
                                                if (w <= 0 || h <= 0)
                                                {
                                                        return;
                                                }

                                                // Random harmonics for this drift.
                                                int[] ratios = {1, 2, 3};
                                                double base = ratios[random.nextInt(ratios.length)];
                                                double detune = 0.05d + random.nextDouble() * 0.14d;
                                                double fy = base + (random.nextBoolean() ? detune : -detune);

                                                double ax = 16.0d + random.nextDouble() * 46.0d;
                                                double ay = 12.0d + random.nextDouble() * 38.0d;
                                                double phx = random.nextDouble() * 2.0d * Math.PI;
                                                double phy = random.nextDouble() * 2.0d * Math.PI;

                                                double periodMs = 900.0d + random.nextDouble() * 1700.0d;
                                                double omega = 2.0d * Math.PI / periodMs;
                                                long duration = 1200L + random.nextInt(2600);
                                                long t0 = System.currentTimeMillis();

                                                while (System.currentTimeMillis() - t0 < duration && !aborted(gen))
                                                {
                                                        if (!sleep(40 + random.nextInt(50)))
                                                        {
                                                                return;
                                                        }
                                                        double t = System.currentTimeMillis() - t0;
                                                        double x = cx + ax * Math.cos(omega * t + phx);
                                                        double y = cy + ay * Math.sin(fy * omega * t + phy);
                                                        int ix = Math.max(4, Math.min(w - 5, (int) Math.round(x)));
                                                        int iy = Math.max(4, Math.min(h - 5, (int) Math.round(y)));
                                                        if (!MobileHitTest.isInterfaceAt(ix, iy))
                                                        {
                                                                emitMove(gen, ix, iy);
                                                        }
                                                }
                                        }
                                        finally
                                        {
                                                hovering = false;
                                        }
                                });
                        }
                        catch (RuntimeException ignored)
                        {
                                hovering = false;
                        }
                }

                // ---- gesture scripts (timeline thread) ----

                private void runFish(int gen, NPC victim, Persona p, boolean menu, String wanted, int[] pt, int[] ground, int[] dismiss, int pad)
                {
                        try
                        {
                                if (aborted(gen))
                                {
                                        return;
                                }
                                if (dismiss != null && !dismissMenu(gen, dismiss))
                                {
                                        return;
                                }
                                if (aborted(gen))
                                {
                                        return;
                                }
                                if (!sleep(Math.max(20, pad)))
                                {
                                        return;
                                }

                                // Re-read the hull right before committing so the tap lands where
                                // the spot actually is now, not where it was at decision time.
                                Placement placement = menu ? Placement.TIGHT_BOX : p.placement;
                                int[] fresh = refreshPoint(gen, victim, placement, pt);
                                if (fresh == null || aborted(gen))
                                {
                                        return;
                                }
                                int[] target = fresh;

                                boolean misclick = !menu && ground != null
                                        && random.nextInt(100) < effectiveMisclickChance(p);
                                int[] fingerDown = misclick ? ground : target;

                                if (!misclick && random.nextInt(100) < p.approachChance && lastX >= 0)
                                {
                                        approachTrail(gen, fingerDown[0], fingerDown[1]);
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                }

                                if (menu)
                                {
                                        menuWalk(gen, fingerDown, p, wanted);
                                        return;
                                }

                                // Finger down: two parking moves, a few ms apart.
                                emitMove(gen, fingerDown[0], fingerDown[1]);
                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, fingerDown[0], fingerDown[1]);

                                long pressStart = System.currentTimeMillis();
                                // Micro drift while the finger is pressed (30% of taps): a tiny
                                // two-axis sine/cosine wobble, well under the driver's drag slop.
                                if (random.nextInt(100) < 30)
                                {
                                        double ampX = 1.2d + random.nextDouble() * 2.2d;
                                        double ampY = 1.2d + random.nextDouble() * 2.2d;
                                        double freqX = 0.7d + random.nextDouble() * 1.3d;
                                        double freqY = 0.7d + random.nextDouble() * 1.3d;
                                        double phase = random.nextDouble() * 2.0d * Math.PI;
                                        int n = 1 + random.nextInt(3);
                                        for (int i = 0; i < n && !aborted(gen); i++)
                                        {
                                                if (!sleep(12 + random.nextInt(13)))
                                                {
                                                        return;
                                                }
                                                double t = (i + 1) * 0.4d;
                                                int mx = fingerDown[0] + (int) Math.round(ampX * Math.sin(2.0d * Math.PI * freqX * t + phase));
                                                int my = fingerDown[1] + (int) Math.round(ampY * Math.cos(2.0d * Math.PI * freqY * t + phase * 1.618d));
                                                emitMove(gen, mx, my);
                                        }
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                }

                                int tapDur = personaTapDur(p);
                                long elapsed = System.currentTimeMillis() - pressStart;
                                if (!sleep(Math.max(8, tapDur - (int) elapsed)) || aborted(gen))
                                {
                                        return;
                                }
                                emitBurst(gen, fingerDown[0], fingerDown[1], MouseEvent.BUTTON1);
                                lastX = fingerDown[0];
                                lastY = fingerDown[1];

                                if (misclick)
                                {
                                        // The slip is noticed: the correction tap lands exactly
                                        // 50-97ms after the misclick burst.
                                        long slipAt = System.currentTimeMillis();
                                        int gap = MISCORRECT_MIN_MS + random.nextInt(MISCORRECT_MAX_MS - MISCORRECT_MIN_MS + 1);
                                        if (!sleep(Math.max(6, gap - 45)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        int[] fix = refreshPoint(gen, victim, placement, target);
                                        if (fix == null || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitMove(gen, fix[0], fix[1]);
                                        if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitMove(gen, fix[0], fix[1]);
                                        long remaining = slipAt + gap - System.currentTimeMillis();
                                        if (remaining > 0 && !sleep((int) remaining))
                                        {
                                                return;
                                        }
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        emitBurst(gen, fix[0], fix[1], MouseEvent.BUTTON1);
                                        lastX = fix[0];
                                        lastY = fix[1];
                                        if (config.logInput())
                                        {
                                                Log.i(LOG_TAG, "misclick corrected after ~" + gap + "ms");
                                        }
                                }
                        }
                        finally
                        {
                                busy = false;
                        }
                }

                /**
                 * One batch of inventory drops. Each item is long-pressed, the finger is walked
                 * onto the "Drop" row and the row is clicked; the pauses between items are what
                 * a player's hands do while emptying a bag — quickish, uneven, never metronomic.
                 */
                private void runDrops(int gen, Persona p, List<DropTarget> targets, int[] dismiss, int pad)
                {
                        try
                        {
                                if (dismiss != null && !dismissMenu(gen, dismiss))
                                {
                                        return;
                                }

                                boolean first = true;
                                for (DropTarget target : targets)
                                {
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        int gap = first ? Math.max(20, pad) : 260 + random.nextInt(420);
                                        first = false;
                                        if (!sleep(gap) || aborted(gen))
                                        {
                                                return;
                                        }

                                        if (random.nextInt(100) < p.approachChance && lastX >= 0)
                                        {
                                                approachTrail(gen, target.x, target.y);
                                                if (aborted(gen))
                                                {
                                                        return;
                                                }
                                        }

                                        // Finger down on the slot: park the cursor, then hold perfectly
                                        // still — any movement would turn the long-press into a camera drag.
                                        emitMove(gen, target.x, target.y);
                                        if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitMove(gen, target.x, target.y);

                                        int longPress = ViewConfiguration.getLongPressTimeout() + random.nextInt(35);
                                        if (!sleep(longPress) || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitBurst(gen, target.x, target.y, MouseEvent.BUTTON3);
                                        lastX = target.x;
                                        lastY = target.y;

                                        // Ask the client thread where the "Drop" row was drawn.
                                        requestMenuScan("Drop");
                                        long deadline = System.currentTimeMillis() + 900;
                                        while (menuScanPending && menuActionPoint == null
                                                && System.currentTimeMillis() < deadline && !aborted(gen))
                                        {
                                                if (!sleep(12))
                                                {
                                                        return;
                                                }
                                        }
                                        int[] row = menuActionPoint;
                                        int kind = menuActionKind;
                                        menuActionPoint = null;
                                        menuScanPending = false;
                                        if (aborted(gen))
                                        {
                                                return;
                                        }

                                        if (row == null || kind == 0)
                                        {
                                                // Menu state unreadable; skip this item and try the next.
                                                if (config.logInput())
                                                {
                                                        Log.w(LOG_TAG, "drop: could not locate the Drop row for " + target.label);
                                                }
                                                continue;
                                        }

                                        if (kind == 2)
                                        {
                                                // No Drop row (item already gone?): tap the menu away.
                                                emitMove(gen, row[0], row[1]);
                                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                emitMove(gen, row[0], row[1]);
                                                if (!sleep(personaTapDur(p)) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                emitBurst(gen, row[0], row[1], MouseEvent.BUTTON1);
                                                continue;
                                        }

                                        // "Read" the menu for a moment, then drag the finger onto the option.
                                        if (!sleep(120 + random.nextInt(280)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        dragTo(gen, new int[]{target.x, target.y}, row, 200 + random.nextInt(320));
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        if (!sleep(25 + random.nextInt(60)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitBurst(gen, row[0], row[1], MouseEvent.BUTTON1);
                                        lastX = row[0];
                                        lastY = row[1];
                                        if (config.logInput())
                                        {
                                                Log.i(LOG_TAG, "drop: clicked the Drop row for " + target.label);
                                        }

                                        // Let the menu close and the item vanish before the next long-press.
                                        if (!sleep(140 + random.nextInt(260)))
                                        {
                                                return;
                                        }
                                }
                        }
                        finally
                        {
                                busy = false;
                        }
                }

                /**
                 * Long-press to open the context menu, drag the finger to the wanted row
                 * (a fishing action, "Drop", ...), and click it — the touch equivalent of
                 * right-click → option.
                 */
                private void menuWalk(int gen, int[] down, Persona p, String wanted)
                {
                        // Finger down: park the cursor, then hold perfectly still — any movement
                        // would turn the long-press into a camera drag.
                        emitMove(gen, down[0], down[1]);
                        if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                        {
                                return;
                        }
                        emitMove(gen, down[0], down[1]);

                        int longPress = ViewConfiguration.getLongPressTimeout() + random.nextInt(35);
                        if (!sleep(longPress) || aborted(gen))
                        {
                                return;
                        }
                        emitBurst(gen, down[0], down[1], MouseEvent.BUTTON3);
                        lastX = down[0];
                        lastY = down[1];

                        // Ask the client thread where the wanted row was drawn.
                        requestMenuScan(wanted);
                        long deadline = System.currentTimeMillis() + 900;
                        while (menuScanPending && menuActionPoint == null
                                && System.currentTimeMillis() < deadline && !aborted(gen))
                        {
                                if (!sleep(12))
                                {
                                        return;
                                }
                        }
                        int[] row = menuActionPoint;
                        int kind = menuActionKind;
                        menuActionPoint = null;
                        menuScanPending = false;
                        if (aborted(gen))
                        {
                                return;
                        }

                        if (row == null || kind == 0)
                        {
                                // Menu state unreadable; leave it and let the next gesture's
                                // menu-open guard tap it away.
                                if (config.logInput())
                                {
                                        Log.w(LOG_TAG, "menu-walk: could not locate the '" + wanted + "' row");
                                }
                                return;
                        }

                        if (kind == 2)
                        {
                                // No wanted row (spot gone / wrong click): dismiss the menu.
                                emitMove(gen, row[0], row[1]);
                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, row[0], row[1]);
                                if (!sleep(personaTapDur(p)) || aborted(gen))
                                {
                                        return;
                                }
                                emitBurst(gen, row[0], row[1], MouseEvent.BUTTON1);
                                return;
                        }

                        // "Read" the menu for a moment, then drag the finger onto the option.
                        if (!sleep(150 + random.nextInt(300)) || aborted(gen))
                        {
                                return;
                        }
                        dragTo(gen, down, row, 220 + random.nextInt(380));
                        if (aborted(gen))
                        {
                                return;
                        }
                        if (!sleep(25 + random.nextInt(60)) || aborted(gen))
                        {
                                return;
                        }
                        emitBurst(gen, row[0], row[1], MouseEvent.BUTTON1);
                        lastX = row[0];
                        lastY = row[1];
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "menu-walk: clicked the '" + wanted + "' row at " + row[0] + "," + row[1]);
                        }
                }

                /** Taps away an open context menu before doing anything else. */
                private boolean dismissMenu(int gen, int[] dismiss)
                {
                        emitMove(gen, dismiss[0], dismiss[1]);
                        if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                        {
                                return false;
                        }
                        emitMove(gen, dismiss[0], dismiss[1]);
                        if (!sleep(personaTapDur(persona)) || aborted(gen))
                        {
                                return false;
                        }
                        emitBurst(gen, dismiss[0], dismiss[1], MouseEvent.BUTTON1);
                        return !aborted(gen) && sleep(160 + random.nextInt(240));
                }

                /**
                 * Light finger slide toward the tap point: pure hover moves before the press,
                 * riding a randomized sine-wave path (see {@link #harmonicPath}).
                 */
                private void approachTrail(int gen, int toX, int toY)
                {
                        double dist = Math.hypot(toX - lastX, toY - lastY);
                        if (dist < 6)
                        {
                                return;
                        }
                        harmonicPath(gen, lastX, lastY, toX, toY, 160 + random.nextInt(300));
                }

                /** Drag path from one point to another along a randomized harmonic curve. */
                private void dragTo(int gen, int[] from, int[] to, int durationMs)
                {
                        harmonicPath(gen, from[0], from[1], to[0], to[1], durationMs);
                }

                /**
                 * Harmonic finger path from (x0, y0) to (x1, y1): a straight baseline eased
                 * with smoothstep, plus randomized trigonometric components — a sine ripple
                 * perpendicular to the line of travel (the finger wobbling across its
                 * course), a finer, faster cosine tremor layered on top, and a cosine swell
                 * along the direction of travel (the finger advancing and hesitating).
                 * Amplitude, frequency and phase of every component are rolled fresh for
                 * each gesture, so no two gestures ever trace the same curve; the sine
                 * window fades the wobble to zero at both endpoints so the finger leaves
                 * and arrives exactly on target.
                 */
                private void harmonicPath(int gen, double x0, double y0, double x1, double y1, int durationMs)
                {
                        double dx = x1 - x0;
                        double dy = y1 - y0;
                        double dist = Math.hypot(dx, dy);
                        if (dist < 3)
                        {
                                return;
                        }
                        double ux = dx / dist;
                        double uy = dy / dist;
                        double px = -uy; // perpendicular unit vector
                        double py = ux;

                        // Random harmonics for this gesture.
                        double rippleAmp = (0.05d + random.nextDouble() * 0.09d) * dist;
                        double rippleCycles = 0.6d + random.nextDouble() * 1.9d;
                        double ripplePhase = random.nextDouble() * 2.0d * Math.PI;
                        double tremorAmp = (0.012d + random.nextDouble() * 0.035d) * dist;
                        double tremorCycles = rippleCycles * (2.5d + random.nextDouble() * 2.5d);
                        double tremorPhase = random.nextDouble() * 2.0d * Math.PI;
                        double swellAmp = (0.03d + random.nextDouble() * 0.06d) * dist;
                        double swellCycles = 0.5d + random.nextDouble() * 1.1d;
                        double swellPhase = random.nextDouble() * 2.0d * Math.PI;

                        int steps = Math.max(4, Math.min(34, (int) Math.round(dist / 11.0d)));
                        double stepDur = Math.max(9.0d, durationMs / (double) steps);
                        for (int i = 1; i <= steps; i++)
                        {
                                if (aborted(gen) || !sleep((int) stepDur + random.nextInt(3)))
                                {
                                        return;
                                }
                                double t = i / (double) steps;
                                double u = t * t * (3.0d - 2.0d * t); // smoothstep: slow, fast, settle
                                double window = Math.sin(Math.PI * t); // wobble fades at both ends
                                double cross = rippleAmp * Math.sin(2.0d * Math.PI * rippleCycles * t + ripplePhase) * window
                                        + tremorAmp * Math.cos(2.0d * Math.PI * tremorCycles * t + tremorPhase) * window;
                                double swell = swellAmp * Math.cos(2.0d * Math.PI * swellCycles * t + swellPhase) * window;
                                double x = x0 + dx * u + px * cross + ux * swell;
                                double y = y0 + dy * u + py * cross + uy * swell;
                                emitMove(gen, (int) Math.round(x), (int) Math.round(y));
                        }
                        if (!sleep(14 + random.nextInt(20)) || aborted(gen))
                        {
                                return;
                        }
                        emitMove(gen, (int) Math.round(x1), (int) Math.round(y1));
                }

                /**
                 * Re-reads the spot's hull on the client thread and returns where the tap should
                 * go now. Null means "abort the gesture" (spot gone or plugin stopping); the
                 * previous point is kept when the hull cannot be recomputed.
                 */
                private int[] refreshPoint(int gen, NPC victim, Placement placement, int[] fallback)
                {
                        final CountDownLatch done = new CountDownLatch(1);
                        final int[][] out = {null};
                        final boolean[] ok = {true};
                        clientThread.invokeLater(() ->
                        {
                                try
                                {
                                        if (client.getGameState() != GameState.LOGGED_IN
                                                || !spots.contains(victim))
                                        {
                                                ok[0] = false;
                                        }
                                        else
                                        {
                                                int[] fresh = npcTapPoint(victim, placement);
                                                if (fresh != null)
                                                {
                                                        out[0] = fresh;
                                                }
                                        }
                                }
                                finally
                                {
                                        done.countDown();
                                }
                        });
                        try
                        {
                                if (!done.await(150, TimeUnit.MILLISECONDS))
                                {
                                        // Client busy: keep the last known point.
                                        return aborted(gen) ? null : fallback;
                                }
                        }
                        catch (InterruptedException e)
                        {
                                Thread.currentThread().interrupt();
                                return null;
                        }
                        if (!ok[0] || aborted(gen))
                        {
                                return null;
                        }
                        return out[0] != null ? out[0] : fallback;
                }

                private void requestMenuScan(String option)
                {
                        menuActionPoint = null;
                        menuActionKind = 0;
                        menuScanWanted = option;
                        menuScanRequestedAt = System.currentTimeMillis();
                        menuScanPending = true;
                }

                // ---- emission (timeline thread -> Android main thread) ----

                private void emitMove(int gen, int x, int y)
                {
                        if (aborted(gen))
                        {
                                return;
                        }
                        lastX = x;
                        lastY = y;
                        Handler h = main;
                        if (h != null)
                        {
                                h.post(() ->
                                {
                                        if (running && gen == generation)
                                        {
                                                fireAwtMove(x, y);
                                        }
                                });
                        }
                }

                private void emitBurst(int gen, int x, int y, int button)
                {
                        if (aborted(gen))
                        {
                                return;
                        }
                        lastX = x;
                        lastY = y;
                        Handler h = main;
                        if (h != null)
                        {
                                h.post(() ->
                                {
                                        if (running && gen == generation)
                                        {
                                                fireAwtBurst(x, y, button);
                                        }
                                });
                        }
                }
        }


        // ---------------------------------------------------------------- AWT dispatch

        /** Resolved dispatch target for a canvas point, mirroring the Compose touch driver. */
        private static final class DispatchTarget
        {
                final Component target; // the component the driver would dispatch on
                final Component hit;    // deepest component under the point
                final int winX;
                final int winY;
                final int lx;
                final int ly;

                DispatchTarget(Component target, Component hit, int winX, int winY, int lx, int ly)
                {
                        this.target = target;
                        this.hit = hit;
                        this.winX = winX;
                        this.winY = winY;
                        this.lx = lx;
                        this.ly = ly;
                }
        }

        /** One parking {@code MOUSE_MOVED}, like the driver's hover tracker on a move/press. */
        private void fireAwtMove(int canvasX, int canvasY)
        {
                Window window = Window.primaryFrame();
                if (window == null)
                {
                        return;
                }
                DispatchTarget dt = resolveDispatchTarget(window, canvasX, canvasY);
                if (dt == null)
                {
                        return;
                }
                fireMouse(dt.target, MouseEvent.MOUSE_MOVED, dt.lx, dt.ly, MouseEvent.NOBUTTON, false);
        }

        /**
         * The four-event click burst the driver emits at finger-up: MOVED, PRESSED,
         * RELEASED, CLICKED — all at the down position, with the press's button-down mask
         * on PRESSED only.
         */
        private void fireAwtBurst(int canvasX, int canvasY, int button)
        {
                Window window = Window.primaryFrame();
                if (window == null)
                {
                        return;
                }
                DispatchTarget dt = resolveDispatchTarget(window, canvasX, canvasY);
                if (dt == null)
                {
                        return;
                }
                if (button == MouseEvent.BUTTON1 && dt.target instanceof Canvas
                        && MobileScrollbars.tap(dt.lx, dt.ly))
                {
                        return;
                }
                fireMouse(dt.target, MouseEvent.MOUSE_MOVED, dt.lx, dt.ly, MouseEvent.NOBUTTON, false);
                fireMouse(dt.target, MouseEvent.MOUSE_PRESSED, dt.lx, dt.ly, button, true);
                fireMouse(dt.target, MouseEvent.MOUSE_RELEASED, dt.lx, dt.ly, button, false);
                fireMouse(dt.target, MouseEvent.MOUSE_CLICKED, dt.lx, dt.ly, button, false);
                clickDirectWidget(dt.hit, dt.winX, dt.winY, button);
        }

        /**
         * Window-space hit test, walk up to the mouse-listener-bearing component, translate
         * into that component's local coordinates — the same resolution the touch driver
         * performs for every tap.
         */
        private DispatchTarget resolveDispatchTarget(Window window, int canvasX, int canvasY)
        {
                Canvas canvas = Canvas.latest();
                if (canvas == null)
                {
                        return null;
                }
                Rectangle bounds = canvas.getBoundsInWindow();
                if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
                {
                        return null;
                }
                int[] mapped = MobileInventoryOverlay.mapInput(canvasX, canvasY, bounds.width, bounds.height);
                if (mapped == null || mapped.length < 2 || mapped[0] < 0 || mapped[1] < 0)
                {
                        return null; // dead zone left by relocated interface panels
                }
                int winX = bounds.x + mapped[0];
                int winY = bounds.y + mapped[1];

                Component hit = hitTest(window, winX, winY);
                if (hit == null)
                {
                        return null;
                }
                Component target = walkUpToListener(hit);
                if (target == null)
                {
                        target = hit;
                }
                int[] local = localCoords(target, winX, winY);
                return new DispatchTarget(target, hit, winX, winY, local[0], local[1]);
        }

        /** Deepest visible component containing the window point. */
        private static Component hitTest(Window window, int winX, int winY)
        {
                return walkHit(window, winX, winY, 0, 0);
        }

        private static Component walkHit(Component c, int winX, int winY, int tx, int ty)
        {
                if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0)
                {
                        return null;
                }
                int ax = tx + (c instanceof Window ? 0 : c.getX());
                int ay = ty + (c instanceof Window ? 0 : c.getY());
                if (winX < ax || winY < ay || winX >= ax + c.getWidth() || winY >= ay + c.getHeight())
                {
                        return null;
                }
                Component deepest = c;
                if (c instanceof Container)
                {
                        Component[] kids = ((Container) c).getComponents();
                        for (int i = kids.length - 1; i >= 0; i--)
                        {
                                Component child = kids[i];
                                if (child == null)
                                {
                                        continue;
                                }
                                Component sub = walkHit(child, winX, winY, ax, ay);
                                if (sub != null)
                                {
                                        deepest = sub;
                                        break;
                                }
                        }
                }
                return deepest;
        }

        private static Component walkUpToListener(Component c)
        {
                Component cur = c;
                while (cur != null)
                {
                        if (cur.hasMouseListeners())
                        {
                                return cur;
                        }
                        cur = cur.getParent();
                }
                return null;
        }

        private static int[] localCoords(Component target, int winX, int winY)
        {
                int dx = 0;
                int dy = 0;
                Component cur = target;
                while (cur != null && !(cur instanceof Window))
                {
                        dx += cur.getX();
                        dy += cur.getY();
                        cur = cur.getParent();
                }
                return new int[]{winX - dx, winY - dy};
        }

        private static void fireMouse(Component target, int id, int x, int y, int button, boolean pressed)
        {
                int mods = pressed ? buttonDownMask(button) : 0;
                // Deliberately no keyboard modifier masks: the engine resolves shift/alt state
                // from its own key array, and alt-masked clicks are dropped by its dispatcher.
                target.dispatchMouseEvent(new MouseEvent(
                        target, id, System.currentTimeMillis(), mods, x, y, 1,
                        button == MouseEvent.BUTTON3, button));
        }

        private static int buttonDownMask(int button)
        {
                switch (button)
                {
                        case MouseEvent.BUTTON1:
                                return InputEvent.BUTTON1_DOWN_MASK;
                        case MouseEvent.BUTTON2:
                                return InputEvent.BUTTON2_DOWN_MASK;
                        case MouseEvent.BUTTON3:
                                return InputEvent.BUTTON3_DOWN_MASK;
                        default:
                                return 0;
                }
        }

        /** Compose-side widgets that are not reachable through AWT mouse listeners. */
        private static void clickDirectWidget(Component hit, int winX, int winY, int button)
        {
                if (button != MouseEvent.BUTTON1)
                {
                        return;
                }
                if (hit instanceof javax.swing.JTabbedPane)
                {
                        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) hit;
                        int[] local = localCoords(tabs, winX, winY);
                        int idx = tabs.indexAtLocation(local[0], local[1]);
                        if (idx >= 0)
                        {
                                tabs.setSelectedIndex(tabs.getSelectedIndex() == idx ? -1 : idx);
                        }
                }
                else if (hit instanceof javax.swing.AbstractButton)
                {
                        ((javax.swing.AbstractButton) hit).doClick();
                }
        }

        // ---------------------------------------------------------------- camera

        /** One animated segment of a camera turn. */
        private static final class CameraLeg
        {
                final int delta;
                final int durationMs;
                final boolean easeIn;  // true: starts slow and accelerates; false: flicks and settles
                final double easeExp;

                CameraLeg(int delta, int durationMs, boolean easeIn, double easeExp)
                {
                        this.delta = delta;
                        this.durationMs = durationMs;
                        this.easeIn = easeIn;
                        this.easeExp = easeExp;
                }
        }

        /** A sequence of legs applied to yaw or pitch, stepped on client ticks. */
        private static final class CameraAnim
        {
                private final int from;
                private final List<CameraLeg> legs;
                private final long startMs;
                private final IntConsumer apply;

                CameraAnim(int from, List<CameraLeg> legs, long startMs, IntConsumer apply)
                {
                        this.from = from;
                        this.legs = legs;
                        this.startMs = startMs;
                        this.apply = apply;
                }

                /** @return true when the animation has finished */
                boolean step(long now)
                {
                        if (now < startMs)
                        {
                                return false;
                        }
                        long t = now - startMs;
                        int cum = 0;
                        long timeUsed = 0;
                        for (int i = 0; i < legs.size(); i++)
                        {
                                CameraLeg leg = legs.get(i);
                                if (t < timeUsed + leg.durationMs || i == legs.size() - 1)
                                {
                                        double p = leg.durationMs <= 0
                                                ? 1.0d
                                                : Math.min(1.0d, (t - timeUsed) / (double) leg.durationMs);
                                        int value = from + cum + (int) Math.round(leg.delta * legEase(p, leg));
                                        apply.accept(value);
                                        return p >= 1.0d && i == legs.size() - 1;
                                }
                                cum += leg.delta;
                                timeUsed += leg.durationMs;
                        }
                        return true;
                }

                private static double legEase(double p, CameraLeg leg)
                {
                        if (leg.easeIn)
                        {
                                return Math.pow(p, leg.easeExp);
                        }
                        return 1.0d - Math.pow(1.0d - p, leg.easeExp);
                }
        }

        private static int clampPitch(int v)
        {
                return Math.max(PITCH_MIN, Math.min(PITCH_MAX, v));
        }

        private void rollNextTurnCatches()
        {
                int min = Math.max(config.cameraEveryMin(), 1);
                int max = Math.max(config.cameraEveryMax(), min);
                nextTurnCatches = catchesSinceTurn + min + (max > min ? random.nextInt(max - min + 1) : 0);
        }

        /** Called on every counted catch: turns the camera once the catch threshold is reached. */
        private void onCatchCamera()
        {
                catchesSinceTurn++;
                if (!config.smartCamera() || yawAnim != null)
                {
                        return;
                }
                if (catchesSinceTurn < nextTurnCatches)
                {
                        return;
                }
                catchesSinceTurn = 0;
                rollNextTurnCatches();
                LocalPoint focus = voluntaryFocus();
                if (focus != null)
                {
                        planYawTurn(focus, false);
                }
        }

        /** Where a voluntary turn looks: mostly the current/next spot, sometimes the heat centre. */
        private LocalPoint voluntaryFocus()
        {
                Player local = client.getLocalPlayer();
                if (local == null || local.getLocalLocation() == null)
                {
                        return null;
                }
                WorldView wv = client.getTopLevelWorldView();

                int roll = random.nextInt(100);
                if (roll < 60)
                {
                        NPC npc = currentSpot != null ? currentSpot : nextSpot;
                        if (npc != null && npc.getLocalLocation() != null)
                        {
                                return npc.getLocalLocation();
                        }
                }
                if (roll < 85)
                {
                        WorldPoint centre = huntCenter(local);
                        if (centre != null)
                        {
                                LocalPoint lp = centre.distanceTo(local.getWorldLocation()) == 0
                                        ? local.getLocalLocation()
                                        : (wv == null ? null : LocalPoint.fromWorld(wv, centre));
                                if (lp != null)
                                {
                                        return lp;
                                }
                        }
                }
                // Otherwise: glance at a random spot inside the heat range.
                WorldPoint centre = huntCenter(local);
                if (centre == null)
                {
                        return local.getLocalLocation();
                }
                int r = Math.max(1, config.huntRadius());
                WorldPoint wp = new WorldPoint(
                        centre.getX() + signedJitter(r),
                        centre.getY() + signedJitter(r),
                        centre.getPlane());
                LocalPoint lp = wv == null ? null : LocalPoint.fromWorld(wv, wp);
                return lp != null ? lp : local.getLocalLocation();
        }

        /** Slow humanized turn to bring an off-screen spot back into view (rate-limited). */
        private void maybeRescueCamera(NPC target)
        {
                if (!config.cameraRescue() || yawAnim != null)
                {
                        return;
                }
                long now = System.currentTimeMillis();
                if (now - lastRescueMs < RESCUE_COOLDOWN_MS)
                {
                        return;
                }
                LocalPoint lp = target.getLocalLocation();
                if (lp == null)
                {
                        return;
                }
                lastRescueMs = now;
                planYawTurn(lp, true);
                if (config.logInput())
                {
                        Log.i(LOG_TAG, "camera rescue turn toward off-screen spot");
                }
        }

        /**
         * Plans a camera turn toward the focus point as a set of animated legs:
         * a slow-starting, accelerating main pan (ease-in with a randomized exponent —
         * never the same speed twice), sometimes hesitating mid-turn, sometimes
         * overshooting and settling back (ease-out correction), sometimes stopping just
         * short of the target and resting "behind" it, and sometimes landing on it with a
         * little resting jitter. Pitch occasionally follows, slightly out of phase.
         */
        private void planYawTurn(LocalPoint focus, boolean rescue)
        {
                Player local = client.getLocalPlayer();
                LocalPoint here = local == null ? null : local.getLocalLocation();
                if (focus == null || here == null)
                {
                        return;
                }

                int dx = focus.getX() - here.getX();
                int dy = focus.getY() - here.getY();
                if (dx == 0 && dy == 0)
                {
                        return;
                }
                // Perspective projects with x1 = x*cos(yaw) + y*sin(yaw); x1 is the horizontal
                // screen axis, so the target is centred when yaw = atan2(-dx, dy).
                int goal = (int) Math.round(Math.atan2(-dx, dy) * JAU14_PER_RADIAN);

                int from = client.getCameraYawTarget() & JAU14_MASK;
                int half = (JAU14_MASK + 1) / 2;
                int delta = Math.floorMod(goal - from + half, JAU14_MASK + 1) - half;
                if (random.nextInt(100) < 20)
                {
                        // Occasionally spin the long way round, like a player twirling the camera.
                        delta = delta > 0 ? delta - (JAU14_MASK + 1) : delta + (JAU14_MASK + 1);
                }
                if (Math.abs(delta) < CAMERA_DEADZONE)
                {
                        return;
                }

                int dir = delta > 0 ? 1 : -1;
                int min = Math.max(config.cameraPanMinMs(), 200);
                int max = Math.max(config.cameraPanMaxMs(), min);
                double share = Math.min(Math.abs(delta) / (double) half, 1.0d);
                double spread = 0.35d + 0.65d * share;
                int duration = Math.max((int) (min + (max - min) * spread), min);
                double easeExp = 1.7d + random.nextDouble() * 1.1d;

                List<CameraLeg> legs = new ArrayList<>(3);
                int outcome = rescue ? 70 + random.nextInt(30) : random.nextInt(100);
                if (outcome < 30)
                {
                        // Overshoot: fling past the target, then a quick ease-out settle back.
                        int over = dir * (400 + random.nextInt(501));
                        legs.add(new CameraLeg(delta + over, duration, true, easeExp));
                        legs.add(new CameraLeg(-over + signedJitter(60), 250 + random.nextInt(300), false, 1.8d + random.nextDouble() * 1.2d));
                }
                else if (outcome < 60)
                {
                        // Undershoot: stop short and leave the camera resting a little behind.
                        int under = dir * (200 + random.nextInt(451));
                        legs.add(new CameraLeg(delta - under, duration, true, easeExp));
                }
                else
                {
                        // Land on the target, but never perfectly centred.
                        legs.add(new CameraLeg(delta + signedJitter(60), duration, true, easeExp));
                }

                if (random.nextInt(100) < 25)
                {
                        // Hesitation: the finger pauses mid-turn, then carries on.
                        CameraLeg first = legs.get(0);
                        int firstDur = (int) (first.durationMs * 0.55d);
                        int firstDelta = (int) Math.round(first.delta * 0.55d);
                        legs.set(0, new CameraLeg(firstDelta, firstDur, first.easeIn, first.easeExp));
                        legs.add(1, new CameraLeg(0, 80 + random.nextInt(171), true, 1.0d));
                        legs.add(2, new CameraLeg(first.delta - firstDelta, Math.max(60, first.durationMs - firstDur), first.easeIn, first.easeExp));
                }

                yawAnim = new CameraAnim(from, legs, System.currentTimeMillis(),
                        v -> client.setCameraYawTarget(v & JAU14_MASK));

                if (random.nextInt(100) < (rescue ? 20 : 30))
                {
                        planPitchTurn(duration);
                }

                if (config.logInput())
                {
                        Log.i(LOG_TAG, "camera turn: delta=" + delta + " dur=" + duration + "ms legs=" + legs.size()
                                + (rescue ? " (rescue)" : ""));
                }
        }

        /** A small pitch drift alongside a yaw turn, starting slightly late. */
        private void planPitchTurn(int yawDuration)
        {
                int from = client.getCameraPitchTarget();
                int delta = signedJitter(1) * (60 + random.nextInt(101));
                int target = clampPitch(from + delta);
                delta = target - from;
                if (Math.abs(delta) < 20)
                {
                        return;
                }
                int duration = Math.max(300, (int) (yawDuration * (0.6d + random.nextDouble() * 0.6d)));
                List<CameraLeg> legs = new ArrayList<>(2);
                if (random.nextInt(100) < 20)
                {
                        int over = (int) Math.signum(delta) * (30 + random.nextInt(41));
                        legs.add(new CameraLeg(delta + over, duration, true, 1.6d + random.nextDouble()));
                        legs.add(new CameraLeg(-over, 200 + random.nextInt(200), false, 2.0d));
                }
                else
                {
                        legs.add(new CameraLeg(delta, duration, true, 1.5d + random.nextDouble()));
                }
                pitchAnim = new CameraAnim(from, legs,
                        System.currentTimeMillis() + 80 + random.nextInt(271),
                        v -> client.setCameraPitchTarget(clampPitch(v)));
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
                        touch.cancelAll();
                        spots.clear();
                        currentSpot = null;
                        nextSpot = null;
                        clearAutomation();
                        yawAnim = null;
                        pitchAnim = null;
                        menuScanPending = false;
                        menuScanWanted = null;
                        menuActionPoint = null;
                        menuActionKind = 0;
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
                if (message.startsWith("You catch "))
                {
                        if (sessionStart == null)
                        {
                                sessionStart = Instant.now();
                        }
                        catches++;
                        notifier.notify(config.notifyCatch(), message + " (" + catches + ")");
                        onCatchCamera();
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

                maybeRotatePersona();

                // Idle hover drifts while a catch is in progress — a finger idling on the glass.
                if (config.autoFish() && currentSpot != null && !touch.isBusy()
                        && System.currentTimeMillis() >= nextHoverDriftAt)
                {
                        nextHoverDriftAt = System.currentTimeMillis() + 2500L + random.nextInt(3500);
                        Persona p = persona;
                        if (p != null && random.nextInt(100) < p.hoverChance)
                        {
                                touch.hoverDrift();
                        }
                }

                automate();
        }

        @Subscribe
        public void onClientTick(ClientTick tick)
        {
                serviceMenuScan();

                long now = System.currentTimeMillis();
                if (yawAnim != null && yawAnim.step(now))
                {
                        yawAnim = null;
                }
                if (pitchAnim != null && pitchAnim.step(now))
                {
                        pitchAnim = null;
                }
        }

        /**
         * Resolves a pending menu scan: locates the wanted row (a fishing action, "Drop",
         * ...) in the open context menu (in stretched canvas space, where taps are
         * dispatched) and hands the point to the waiting gesture. When no such row exists,
         * a safe dismiss point is produced instead so the gesture can tap the menu away.
         */
        private void serviceMenuScan()
        {
                if (!menuScanPending)
                {
                        return;
                }

                boolean open = client.isMenuOpen();
                MenuEntry[] entries = open ? client.getMenuEntries() : null;
                if (entries == null || entries.length == 0)
                {
                        // Give the menu a few frames to appear before giving up.
                        if (System.currentTimeMillis() - menuScanRequestedAt < 600)
                        {
                                return;
                        }
                        finishMenuScan(0, 0, 0, 0);
                        return;
                }

                double[] f = stretchFactors();
                int menuX = (int) Math.round(client.getMenuX() * f[0]);
                int menuY = (int) Math.round(client.getMenuY() * f[1]);
                int menuW = (int) Math.round(client.getMenuWidth() * f[0]);
                int menuH = (int) Math.round(client.getMenuHeight() * f[1]);

                String wanted = menuScanWanted;
                int idx = -1;
                for (int i = 0; i < entries.length; i++)
                {
                        String option = entries[i].getOption();
                        if (option != null && wanted != null && wanted.equalsIgnoreCase(Text.removeTags(option)))
                        {
                                idx = i;
                                break;
                        }
                }

                if (idx < 0 || menuW <= 0 || menuH <= 0)
                {
                        finishMenuScan(menuX, menuY, menuW, menuH);
                        return;
                }

                int headerH = (int) Math.round(MENU_HEADER_HEIGHT * f[1]);
                // The entries array is stored bottom-to-top: array index 0 is the LAST row
                // drawn at the bottom of the menu, and the row directly under the header is
                // length-1. The client's own hit test maps "row from top" r to array index
                // length-1-r (cf. InteractHighlightOverlay.hoveredMenuEntry). Convert before
                // positioning, or the drag aims at the vertically mirrored row — sweeping
                // over every option on the way down and clicking "Cancel" instead of the
                // wanted one.
                int rowFromTop = entries.length - 1 - idx;
                int rowH = (menuH - headerH) / entries.length;
                if (rowH <= 0)
                {
                        rowH = Math.max(12, (int) Math.round(15 * f[1]));
                }
                int rowY = menuY + headerH + rowH * rowFromTop + rowH / 2 + signedJitter(Math.max(2, rowH / 6));
                int rowX = menuX + (int) (menuW * (0.42d + random.nextDouble() * 0.18d));

                if (!MobileHitTest.isMenuOptionAt(rowX, rowY))
                {
                        rowX = menuX + menuW / 2;
                        rowY = menuY + headerH + rowH * idx + rowH / 2;
                }
                if (!MobileHitTest.isMenuOptionAt(rowX, rowY))
                {
                        finishMenuScan(menuX, menuY, menuW, menuH);
                        return;
                }

                menuActionPoint = new int[]{rowX, rowY};
                menuActionKind = 1;
                menuScanPending = false;
        }

        /** Completes a menu scan with "no wanted row": produce a dismiss point instead. */
        private void finishMenuScan(int menuX, int menuY, int menuW, int menuH)
        {
                int[] dismiss = playerDismissPoint(menuX, menuY, menuW, menuH);
                if (dismiss != null)
                {
                        menuActionPoint = dismiss;
                        menuActionKind = 2;
                }
                else
                {
                        menuActionPoint = null;
                        menuActionKind = 0;
                }
                menuScanPending = false;
        }

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

        /** Lets the player right-click (long-press) the ground and mark the heat centre. */
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

        private int signedJitter(int px)
        {
                return px <= 0 ? 0 : random.nextInt(2 * px + 1) - px;
        }

        // ---------------------------------------------------------------- overlay accessors

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
