package net.runelite.client.plugins.autofight;

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
import java.util.LinkedHashSet;
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
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.mobile.MobileHitTest;
import net.runelite.client.plugins.mobile.MobileInventoryOverlay;
import net.runelite.client.plugins.mobile.MobileScrollbars;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Highlights NPCs by ID, marks the current and next target, counts kills, and optionally
 * auto-attacks and auto-loots.
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
 *       target (hull-biased, time-nudged, never the same pixel).</li>
 *   <li>Sometimes the tap lands on the ground next to the target; 50-97ms later the
 *       correction tap hits the target, like a player noticing their slip.</li>
 *   <li>When "Attack" is not the NPC's left-click option — or the active style prefers
 *       menus — the engine long-presses (BUTTON3 burst at the driver's long-press
 *       timeout), reads where the client drew the "Attack" row, drags the cursor along a
 *       soft bezier to it (the hover trail that highlights rows) and clicks it.</li>
 *   <li>The whole behaviour is steered by a rotating "input style" (persona): tap
 *       placement strategy, reaction padding, press duration, misclick rate and menu
 *       preference all change together every few minutes, so the fingerprint of the
 *       input drifts the way a real player's does over a long session.</li>
 * </ul>
 *
 * <h2>Camera</h2>
 * Voluntary camera turns only happen every 5-15 kills. Each turn is animated over
 * roughly a second or two of client ticks with a randomized ease-in velocity curve
 * (starts slow, speeds up), sometimes hesitating mid-turn, sometimes overshooting the
 * target and settling back, sometimes stopping just short of it, and occasionally
 * dragging the pitch along slightly out of phase. A rate-limited "rescue" turn brings a
 * fully off-screen target back into view when an attack is due, but with the same slow
 * humanized motion — never a snap.
 *
 * <h2>Heat range</h2>
 * Target search is limited to a square of tiles around a centre tile (marked by
 * right-clicking the ground, or centred on the player when none is marked). The ring of
 * the square and the centre tile are drawn in world space by {@code AutoFightHuntOverlay}.
 *
 * <p>The matching-NPC list is rebuilt from the world view every tick. An incrementally
 * maintained list drifts out of sync with the scene (a missed despawn, a scene reload,
 * an NPC that changes id), and when it empties the plugin keeps drawing every matching
 * NPC in the "other" colour while believing there is nothing to fight.</p>
 */
@PluginDescriptor(
        name = "AutoFight",
        description = "Highlight NPCs, count kills, and auto-attack/auto-loot with simulated touch input and humanized camera turns",
        tags = {"npc", "highlight", "indicators", "combat", "kill", "counter", "mobile", "id", "loot", "attack", "camera", "humanizer"}
)
public class AutoFightPlugin extends Plugin
{
        private static final String TAG = "AF-Tag";
        private static final String UNTAG = "AF-Untag";
        private static final String SET_CENTER = "AF-Set centre";
        private static final String CLEAR_CENTER = "AF-Clear centre";

        private static final String LOG_TAG = "AutoFight";

        /** Ticks after a counted kill during which the looter may act. */
        private static final int LOOT_WINDOW_TICKS = 15;

        /** Ticks to wait for combat to actually start before assuming the attack click was lost. */
        private static final int ATTACK_WAIT_TICKS = 6;

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

        @Inject
        private Client client;

        @Inject
        private ClientThread clientThread;

        @Inject
        private AutoFightConfig config;

        @Inject
        private ConfigManager configManager;

        @Inject
        private NpcOverlayService npcOverlayService;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private AutoFightOverlay overlay;

        @Inject
        private AutoFightHuntOverlay huntOverlay;

        @Inject
        private Notifier notifier;

        @Inject
        private NpcUtil npcUtil;

        @Inject
        private ItemManager itemManager;

        private final Set<Integer> targetIds = new HashSet<>();
        private final List<NPC> matching = new ArrayList<>();

        private final Set<Integer> lootIds = new HashSet<>();
        private final Set<String> lootNames = new HashSet<>(); // lowercase

        private final Random random = new Random();

        private NPC currentTarget;
        private NPC nextTarget;

        private int kills;
        private Instant sessionStart;
        private int idleCount;
        private boolean idleNotified;
        private boolean lowHpNotified;

        private int lootTicksLeft;
        private int waitTicks;
        private NPC pendingTarget;
        private int pendingTicks;
        private NPC lastVictim;
        private int pendingAttempts;

        private WorldPoint huntCenter; // null = centre on the player

        // ---- input style (persona) rotation ----

        private volatile Persona persona;
        private volatile long personaChangedAt;
        private volatile long nextPersonaSwitchAt;

        // ---- camera ----

        private int killsSinceTurn;
        private int nextTurnKills;
        private CameraAnim yawAnim;
        private CameraAnim pitchAnim;
        private long lastRescueMs;

        // ---- menu scan handshake: client thread resolves, touch thread consumes ----

        private volatile boolean menuScanPending;
        private volatile long menuScanRequestedAt;
        private volatile int[] menuActionPoint; // stretched canvas coords
        private volatile int menuActionKind;   // 0 none, 1 attack row, 2 dismiss point

        private long nextHoverDriftAt;

        private final Function<NPC, HighlightedNpc> highlighter = this::highlight;

        private final TouchEngine touch = new TouchEngine();

        @Provides
        AutoFightConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoFightConfig.class);
        }

        @Override
        protected void startUp()
        {
                parseIds();
                parseLoot();
                parseCenter();
                killsSinceTurn = 0;
                rollNextTurnKills();
                pickInitialPersona();
                nextHoverDriftAt = 0L;
                lastRescueMs = 0L;
                yawAnim = null;
                pitchAnim = null;
                menuScanPending = false;
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
                matching.clear();
                currentTarget = null;
                nextTarget = null;
                clearAutomation();
                resetSession();
                yawAnim = null;
                pitchAnim = null;
                menuScanPending = false;
                menuActionPoint = null;
                menuActionKind = 0;
        }

        // ---------------------------------------------------------------- state

        private void parseIds()
        {
                targetIds.clear();
                for (String part : config.npcIds().split("[,\\s]+"))
                {
                        if (part.isEmpty())
                        {
                                continue;
                        }
                        try
                        {
                                targetIds.add(Integer.parseInt(part.trim()));
                        }
                        catch (NumberFormatException ignored)
                        {
                                // not a number; skip it
                        }
                }
        }

        private void parseLoot()
        {
                lootIds.clear();
                lootNames.clear();
                for (String part : config.lootItems().split(","))
                {
                        String p = part.trim();
                        if (p.isEmpty())
                        {
                                continue;
                        }
                        try
                        {
                                lootIds.add(Integer.parseInt(p));
                        }
                        catch (NumberFormatException notAnId)
                        {
                                lootNames.add(p.toLowerCase(Locale.ROOT));
                        }
                }
        }

        private boolean matches(NPC npc)
        {
                return npc != null && targetIds.contains(npc.getId());
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

        /** Rebuilds the matching list from the live scene. Cheap, and cannot go stale. */
        private void refreshMatching()
        {
                matching.clear();
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
                        if (matches(npc) && !npcUtil.isDying(npc) && inHuntRange(npc, center))
                        {
                                matching.add(npc);
                        }
                }
        }

        private void rescan()
        {
                refreshMatching();
                updateTargets();
                npcOverlayService.rebuild();
        }

        private void clearAutomation()
        {
                lootTicksLeft = 0;
                waitTicks = 0;
                pendingTarget = null;
                pendingTicks = 0;
                lastVictim = null;
                pendingAttempts = 0;
        }

        private void updateTargets()
        {
                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        currentTarget = null;
                        nextTarget = null;
                        return;
                }

                // A dying NPC is not something we are still fighting: treating it as the current
                // target blocks looting and the next attack until it finishes despawning.
                NPC fighting = null;
                Actor interacting = local.getInteracting();
                if (interacting instanceof NPC && matches((NPC) interacting) && !npcUtil.isDying((NPC) interacting))
                {
                        fighting = (NPC) interacting;
                }
                else
                {
                        // Something attacking us counts too (aggressive NPCs, retaliation not started yet).
                        for (NPC npc : matching)
                        {
                                if (npc.getInteracting() == local && !npcUtil.isDying(npc))
                                {
                                        fighting = npc;
                                        break;
                                }
                        }
                }
                currentTarget = fighting;

                // Keep the existing choice while it is still valid, so the highlight does not
                // flicker between equally good candidates every tick.
                if (!isValidNext(nextTarget, local))
                {
                        nextTarget = pickNext(local);
                }
        }

        private boolean isValidNext(NPC npc, Player local)
        {
                if (npc == null || npc == currentTarget || !matching.contains(npc) || npcUtil.isDying(npc))
                {
                        return false;
                }
                if (config.skipOthersFights())
                {
                        Actor other = npc.getInteracting();
                        if (other instanceof Player && other != local)
                        {
                                return false;
                        }
                }
                return true;
        }

        /** Nearest matching NPC, or a random pick from the nearest few when a target pool is set. */
        private NPC pickNext(Player local)
        {
                WorldPoint me = local.getWorldLocation();
                List<NPC> candidates = new ArrayList<>();
                for (NPC npc : matching)
                {
                        if (npc == currentTarget || npcUtil.isDying(npc))
                        {
                                continue;
                        }
                        if (config.skipOthersFights())
                        {
                                Actor other = npc.getInteracting();
                                if (other instanceof Player && other != local)
                                {
                                        continue;
                                }
                        }
                        candidates.add(npc);
                }
                if (candidates.isEmpty())
                {
                        return null;
                }
                candidates.sort((a, b) -> Integer.compare(
                        a.getWorldLocation().distanceTo(me),
                        b.getWorldLocation().distanceTo(me)));

                int pool = Math.min(Math.max(config.targetPool(), 1), candidates.size());
                return candidates.get(pool <= 1 ? 0 : random.nextInt(pool));
        }

        private HighlightedNpc highlight(NPC npc)
        {
                if (!matches(npc))
                {
                        return null;
                }

                Color color;
                if (npc == currentTarget)
                {
                        color = config.targetColor();
                }
                else if (config.highlightNext() && npc == nextTarget)
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
                        .render(n -> !config.hideDead() || !npcUtil.isDying(n))
                        .build();
        }

        private void resetSession()
        {
                kills = 0;
                sessionStart = null;
                idleCount = 0;
                idleNotified = false;
                lowHpNotified = false;
        }

        // ---------------------------------------------------------------- input styles

        /** Where on the target's clickbox the simulated finger aims. */
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
                final int menuChance;      // % of attacks done via long-press + menu
                final int approachChance;  // % of taps preceded by a hover approach trail
                final int misclickChance;  // base % (scaled by the config setting)
                final int padMin;          // ms of extra reaction padding
                final int padMax;
                final int hoverChance;     // % chance of an idle hover drift while fighting
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
         * One decision per tick, on the client thread. Looting takes priority over attacking,
         * and nothing is attempted while we are in combat with a live matching NPC.
         */
        private void automate()
        {
                if (lootTicksLeft > 0)
                {
                        lootTicksLeft--;
                }

                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        return;
                }

                // In combat: the attack landed, so drop any pending state.
                if (currentTarget != null)
                {
                        pendingTarget = null;
                        pendingTicks = 0;
                        lastVictim = null;
                        pendingAttempts = 0;
                        return;
                }

                if (waitTicks > 0)
                {
                        waitTicks--;
                        return;
                }

                // An attack was sent but combat never started (out of reach, click lost, target
                // taken by someone else). Give it a few ticks, then fall through and try again
                // rather than waiting on it forever.
                if (pendingTarget != null)
                {
                        pendingTicks++;
                        if (pendingTicks < ATTACK_WAIT_TICKS && matching.contains(pendingTarget))
                        {
                                return;
                        }
                        pendingTarget = null;
                        pendingTicks = 0;
                }

                if (config.autoLoot() && lootTicksLeft > 0 && tryLoot(local))
                {
                        waitTicks = reactionDelay();
                        return;
                }

                if (config.autoAttack() && nextTarget != null)
                {
                        NPC victim = nextTarget;
                        if (victim != lastVictim)
                        {
                                lastVictim = victim;
                                pendingAttempts = 0;
                        }

                        if (!npcVisibleForTap(victim))
                        {
                                // Cannot tap what is not on screen: turn the camera (slowly, humanized)
                                // and let the next ticks retry once the target swings into view.
                                maybeRescueCamera(victim);
                                return;
                        }

                        // A first retry right after a lost click is the classic human double-tap:
                        // short pad, fresh spot on the target.
                        boolean quick = pendingAttempts == 1 && persona != null
                                && random.nextInt(100) < persona.doubleTapChance;

                        if (touch.attack(victim, quick))
                        {
                                pendingTarget = victim;
                                pendingTicks = 0;
                                pendingAttempts++;
                                waitTicks = reactionDelay();
                        }
                }
        }

        /** True when "Attack" is the NPC's default left-click option (a plain tap attacks). */
        private boolean attackIsDefaultAction(NPC npc)
        {
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
                for (String action : actions)
                {
                        if (action == null)
                        {
                                continue;
                        }
                        return "Attack".equalsIgnoreCase(action);
                }
                return false;
        }

        /**
         * Takes the nearest ground item matching the loot list within the loot radius and
         * taps it like a finger would. Returns true if a gesture was started.
         */
        private boolean tryLoot(Player local)
        {
                if (lootIds.isEmpty() && lootNames.isEmpty())
                {
                        return false;
                }

                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return false;
                }
                Scene scene = wv.getScene();
                if (scene == null)
                {
                        return false;
                }
                LocalPoint here = local.getLocalLocation();
                if (here == null)
                {
                        return false;
                }

                Tile[][][] tiles = scene.getTiles();
                int plane = wv.getPlane();
                if (plane < 0 || plane >= tiles.length)
                {
                        return false;
                }

                int meX = here.getSceneX();
                int meY = here.getSceneY();
                int radius = config.lootRadius();

                TileItem bestItem = null;
                int bestX = 0;
                int bestY = 0;
                int bestDist = Integer.MAX_VALUE;

                Tile[][] plane2d = tiles[plane];
                for (int x = Math.max(meX - radius, 0); x <= Math.min(meX + radius, plane2d.length - 1); x++)
                {
                        Tile[] column = plane2d[x];
                        if (column == null)
                        {
                                continue;
                        }
                        for (int y = Math.max(meY - radius, 0); y <= Math.min(meY + radius, column.length - 1); y++)
                        {
                                Tile tile = column[y];
                                if (tile == null)
                                {
                                        continue;
                                }
                                int d = Math.max(Math.abs(x - meX), Math.abs(y - meY));
                                if (d >= bestDist)
                                {
                                        continue;
                                }
                                List<TileItem> items = tile.getGroundItems();
                                if (items == null)
                                {
                                        continue;
                                }
                                for (TileItem item : items)
                                {
                                        if (isLootItem(item.getId()))
                                        {
                                                bestItem = item;
                                                bestX = x;
                                                bestY = y;
                                                bestDist = d;
                                                break;
                                        }
                                }
                        }
                }

                if (bestItem == null)
                {
                        return false;
                }

                // Tap the tile the item lies on: left-click default on ground items is "Take".
                LocalPoint lp = LocalPoint.fromScene(bestX, bestY, wv);
                if (lp == null)
                {
                        return false;
                }
                Point canvasPoint = Perspective.localToCanvas(client, lp, plane);
                if (canvasPoint == null)
                {
                        return false;
                }
                int[] stretched = toStretched(canvasPoint.getX(), canvasPoint.getY());
                if (stretched == null)
                {
                        return false;
                }
                return touch.tap("loot " + itemName(bestItem.getId()), stretched[0], stretched[1]);
        }

        private boolean isLootItem(int rawId)
        {
                int id = itemManager.canonicalize(rawId);
                if (lootIds.contains(id))
                {
                        return true;
                }
                if (lootNames.isEmpty())
                {
                        return false;
                }
                return lootNames.contains(itemName(id).toLowerCase(Locale.ROOT));
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
         * Picks a fresh spot on the NPC's clickbox (convex hull), converts it into stretched
         * canvas space, and nudges it with a time-derived wobble so the same pixel is never
         * tapped twice in a row. Null when the target is not tappable (off screen, behind a
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
         * A ground point just outside the target's clickbox, for the occasional misclick:
         * a tile within 1-2 of the NPC's tile that is on screen, not on an interface, and
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
                                continue; // still on the NPC itself
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
                /** True while an attack/loot gesture is in flight (hover drifts do not count). */
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
                                Thread t = new Thread(r, "AutoFight-Touch");
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
                 * Attacks an NPC with a simulated finger. Chooses between a plain tap (when
                 * "Attack" is the left-click default and the style is not in a menu mood) and a
                 * long-press + menu selection.
                 *
                 * @return true when a gesture was scheduled
                 */
                boolean attack(NPC npc, boolean quickRetry)
                {
                        if (!running || busy || persona == null)
                        {
                                return false;
                        }
                        if (client.getGameState() != GameState.LOGGED_IN)
                        {
                                return false;
                        }

                        Persona p = persona;
                        boolean menuForced = !attackIsDefaultAction(npc);
                        boolean useMenu = menuForced || random.nextInt(100) < p.menuChance;
                        Placement placement = useMenu ? Placement.TIGHT_BOX : p.placement;

                        int[] pt = npcTapPoint(npc, placement);
                        if (pt == null)
                        {
                                return false;
                        }
                        int[] ground = useMenu ? null : groundMisclickPoint(npc);

                        // If a context menu is already open, tap it away first — nobody attacks
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
                        final NPC victim = npc;
                        final Persona style = p;
                        final boolean menu = useMenu;
                        final int[] point = pt;
                        final int[] groundPoint = ground;
                        final int[] dismissPoint = dismiss;
                        final int padMs = pad;

                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "attack gesture: " + (menu ? "menu-walk" : "tap")
                                        + " at " + pt[0] + "," + pt[1] + " pad=" + padMs + "ms"
                                        + (ground != null ? " misclick-armed" : ""));
                        }
                        try
                        {
                                timeline.execute(() -> runAttack(gen, victim, style, menu, point, groundPoint, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /** A plain tap at a fixed canvas point (loot pickups). */
                boolean tap(String what, int x, int y)
                {
                        if (!running || busy || persona == null)
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
                        final int px = x;
                        final int py = y;
                        final int[] dismissPoint = dismiss;
                        final int padMs = personaPad(p);

                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "tap gesture: " + what + " at " + x + "," + y + " pad=" + padMs + "ms");
                        }
                        try
                        {
                                timeline.execute(() -> runTap(gen, style, px, py, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /** Idle finger drift while waiting on a kill: pure hover moves, no clicks. */
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
                                                int x = lastX;
                                                int y = lastY;
                                                int n = 1 + random.nextInt(3);
                                                for (int i = 0; i < n && !aborted(gen); i++)
                                                {
                                                        if (!sleep(40 + random.nextInt(60)))
                                                        {
                                                                return;
                                                        }
                                                        int nx = Math.max(4, Math.min(canvasWidth() - 5, x + signedJitter(34)));
                                                        int ny = Math.max(4, Math.min(canvasHeight() - 5, y + signedJitter(34)));
                                                        x = nx;
                                                        y = ny;
                                                        if (!MobileHitTest.isInterfaceAt(nx, ny))
                                                        {
                                                                emitMove(gen, nx, ny);
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

                private void runTap(int gen, Persona p, int x, int y, int[] dismiss, int pad)
                {
                        try
                        {
                                if (aborted(gen) || !sleep(pad))
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
                                if (random.nextInt(100) < p.approachChance && lastX >= 0)
                                {
                                        approachTrail(gen, x, y);
                                }
                                if (aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, x, y);
                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, x, y);
                                if (!sleep(personaTapDur(p)) || aborted(gen))
                                {
                                        return;
                                }
                                emitBurst(gen, x, y, MouseEvent.BUTTON1);
                        }
                        finally
                        {
                                busy = false;
                        }
                }

                private void runAttack(int gen, NPC victim, Persona p, boolean menu, int[] pt, int[] ground, int[] dismiss, int pad)
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
                                // the NPC actually is now, not where it was at decision time.
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
                                        menuWalk(gen, fingerDown, p);
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
                                // Micro drift while the finger is pressed (30% of taps).
                                if (random.nextInt(100) < 30)
                                {
                                        int n = 1 + random.nextInt(3);
                                        for (int i = 0; i < n && !aborted(gen); i++)
                                        {
                                                if (!sleep(12 + random.nextInt(13)))
                                                {
                                                        return;
                                                }
                                                emitMove(gen, fingerDown[0] + signedJitter(4), fingerDown[1] + signedJitter(4));
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
                 * Long-press to open the context menu, drag the finger to the "Attack" row,
                 * and click it — the touch equivalent of right-click → option.
                 */
                private void menuWalk(int gen, int[] down, Persona p)
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

                        // Ask the client thread where the "Attack" row was drawn.
                        requestMenuScan();
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
                                        Log.w(LOG_TAG, "menu-walk: could not locate the Attack row");
                                }
                                return;
                        }

                        if (kind == 2)
                        {
                                // No Attack row (target died / wrong click): dismiss the menu.
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

                /** Light finger slide toward the tap point: pure hover moves before the press. */
                private void approachTrail(int gen, int toX, int toY)
                {
                        double dist = Math.hypot(toX - lastX, toY - lastY);
                        if (dist < 6)
                        {
                                return;
                        }
                        int steps = 2 + random.nextInt(4);
                        double bow = (0.1d + random.nextDouble() * 0.25d) * dist * (random.nextBoolean() ? 1 : -1);
                        double mx = (lastX + toX) / 2.0d;
                        double my = (lastY + toY) / 2.0d;
                        double nx = -(toY - lastY) / dist;
                        double ny = (toX - lastX) / dist;
                        double cx = mx + nx * bow;
                        double cy = my + ny * bow;
                        double x0 = lastX;
                        double y0 = lastY;
                        for (int i = 1; i <= steps; i++)
                        {
                                if (aborted(gen) || !sleep(35 + random.nextInt(55)))
                                {
                                        return;
                                }
                                double t = i / (double) steps;
                                double x = quad(x0, cx, toX, t) + signedJitter(2);
                                double y = quad(y0, cy, toY, t) + signedJitter(2);
                                emitMove(gen, (int) Math.round(x), (int) Math.round(y));
                        }
                }

                /** Drag path from one point to another along a slightly bowed curve. */
                private void dragTo(int gen, int[] from, int[] to, int durationMs)
                {
                        double dist = Math.hypot(to[0] - from[0], to[1] - from[1]);
                        if (dist < 3)
                        {
                                return;
                        }
                        double bow = (0.08d + random.nextDouble() * 0.18d) * dist * (random.nextBoolean() ? 1 : -1);
                        double mx = (from[0] + to[0]) / 2.0d;
                        double my = (from[1] + to[1]) / 2.0d;
                        double nx = -(to[1] - from[1]) / dist;
                        double ny = (to[0] - from[0]) / dist;
                        double cx = mx + nx * bow;
                        double cy = my + ny * bow;

                        int steps = Math.max(3, Math.min(28, (int) Math.round(dist / 14.0d)));
                        double stepDur = durationMs / (double) steps;
                        for (int i = 1; i <= steps; i++)
                        {
                                if (aborted(gen) || !sleep((int) stepDur + (random.nextBoolean() ? 1 : 0)))
                                {
                                        return;
                                }
                                double t = i / (double) steps;
                                double u = t * t * (3.0d - 2.0d * t); // smoothstep: slow, fast, settle
                                double x = quad(from[0], cx, to[0], u) + signedJitter(2);
                                double y = quad(from[1], cy, to[1], u) + signedJitter(2);
                                emitMove(gen, (int) Math.round(x), (int) Math.round(y));
                        }
                        if (!sleep(14 + random.nextInt(20)) || aborted(gen))
                        {
                                return;
                        }
                        emitMove(gen, to[0], to[1]);
                }

                /**
                 * Re-reads the NPC's hull on the client thread and returns where the tap should
                 * go now. Null means "abort the gesture" (NPC gone or plugin stopping); the
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
                                                || !matching.contains(victim) || npcUtil.isDying(victim))
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

                private void requestMenuScan()
                {
                        menuActionPoint = null;
                        menuActionKind = 0;
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

        private void rollNextTurnKills()
        {
                int min = Math.max(config.cameraEveryMin(), 1);
                int max = Math.max(config.cameraEveryMax(), min);
                nextTurnKills = killsSinceTurn + min + (max > min ? random.nextInt(max - min + 1) : 0);
        }

        /** Called on every counted kill: turns the camera once the kill threshold is reached. */
        private void onKillCamera()
        {
                killsSinceTurn++;
                if (!config.smartCamera() || yawAnim != null)
                {
                        return;
                }
                if (killsSinceTurn < nextTurnKills)
                {
                        return;
                }
                killsSinceTurn = 0;
                rollNextTurnKills();
                LocalPoint focus = voluntaryFocus();
                if (focus != null)
                {
                        planYawTurn(focus, false);
                }
        }

        /** Where a voluntary turn looks: mostly the next target, sometimes the heat centre. */
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
                        NPC npc = currentTarget != null ? currentTarget : nextTarget;
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

        /** Slow humanized turn to bring an off-screen target back into view (rate-limited). */
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
                        Log.i(LOG_TAG, "camera rescue turn toward off-screen target");
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
                if (!AutoFightConfig.GROUP.equals(event.getGroup()))
                {
                        return;
                }
                if (AutoFightConfig.NPC_IDS_KEY.equals(event.getKey()))
                {
                        parseIds();
                        clientThread.invoke(this::rescan);
                }
                else if (AutoFightConfig.LOOT_ITEMS_KEY.equals(event.getKey()))
                {
                        parseLoot();
                }
                else if (AutoFightConfig.CENTER_KEY.equals(event.getKey()))
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
                        matching.clear();
                        currentTarget = null;
                        nextTarget = null;
                        clearAutomation();
                        yawAnim = null;
                        pitchAnim = null;
                        menuScanPending = false;
                        menuActionPoint = null;
                        menuActionKind = 0;
                }
        }

        @Subscribe
        public void onActorDeath(ActorDeath event)
        {
                if (!(event.getActor() instanceof NPC))
                {
                        return;
                }
                NPC npc = (NPC) event.getActor();
                if (!matches(npc))
                {
                        return;
                }
                if (npc == currentTarget || npc.getInteracting() == client.getLocalPlayer())
                {
                        if (sessionStart == null)
                        {
                                sessionStart = Instant.now();
                        }
                        kills++;
                        lootTicksLeft = LOOT_WINDOW_TICKS;
                        notifier.notify(config.notifyKill(), "Killed " + Text.removeTags(String.valueOf(npc.getName())) + " (" + kills + ")");
                        onKillCamera();
                }
                if (npc == currentTarget)
                {
                        currentTarget = null;
                }
                if (npc == nextTarget)
                {
                        nextTarget = null;
                }
        }

        @Subscribe
        public void onGameTick(GameTick tick)
        {
                NPC prevCurrent = currentTarget;
                NPC prevNext = nextTarget;

                refreshMatching();
                updateTargets();

                if (prevCurrent != currentTarget || prevNext != nextTarget)
                {
                        npcOverlayService.rebuild();
                }

                if (currentTarget != null && sessionStart == null)
                {
                        sessionStart = Instant.now();
                }

                // Idle alert: only while matching NPCs are around and a session has started.
                if (currentTarget == null && !matching.isEmpty() && sessionStart != null)
                {
                        idleCount++;
                        if (idleCount >= config.idleTicks() && !idleNotified)
                        {
                                idleNotified = true;
                                notifier.notify(config.notifyIdle(), "You're out of combat");
                        }
                }
                else
                {
                        idleCount = 0;
                        idleNotified = false;
                }

                int threshold = config.lowHp();
                if (threshold > 0)
                {
                        int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
                        if (hp <= threshold && !lowHpNotified)
                        {
                                lowHpNotified = true;
                                notifier.notify(Notification.ON, "Low HP: " + hp);
                        }
                        else if (hp > threshold)
                        {
                                lowHpNotified = false;
                        }
                }

                maybeRotatePersona();

                // Idle hover drifts while a kill is in progress — a finger idling on the glass.
                if (config.autoAttack() && currentTarget != null && !touch.isBusy()
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
         * Resolves a pending menu scan: locates the "Attack" row in the open context menu
         * (in stretched canvas space, where taps are dispatched) and hands the point to the
         * waiting gesture. When no attack row exists, a safe dismiss point is produced
         * instead so the gesture can tap the menu away.
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

                int idx = -1;
                for (int i = 0; i < entries.length; i++)
                {
                        String option = entries[i].getOption();
                        if (option != null && "Attack".equalsIgnoreCase(Text.removeTags(option)))
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
                int rowH = (menuH - headerH) / entries.length;
                if (rowH <= 0)
                {
                        rowH = 16;
                }
                int rowY = menuY + headerH + rowH * idx + rowH / 2 + signedJitter(Math.max(2, rowH / 6));
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

        /** Completes a menu scan with "no attack row": produce a dismiss point instead. */
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
                NPC npc = entry.getNpc();
                if (npc == null)
                {
                        addCenterEntries(entry);
                        return;
                }

                if (config.showIdInMenu())
                {
                        entry.setTarget(entry.getTarget() + ColorUtil.prependColorTag(" (ID " + npc.getId() + ")", Color.LIGHT_GRAY));
                }

                if (config.tagMenuOption() && entry.getType() == MenuAction.EXAMINE_NPC)
                {
                        boolean tagged = targetIds.contains(npc.getId());
                        client.createMenuEntry(-1)
                                .setOption(tagged ? UNTAG : TAG)
                                .setTarget(event.getTarget())
                                .setWorldViewId(entry.getWorldViewId())
                                .setIdentifier(event.getIdentifier())
                                .setType(MenuAction.RUNELITE)
                                .onClick(e -> toggleTag(npc.getId()));
                }
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
                configManager.setConfiguration(AutoFightConfig.GROUP, AutoFightConfig.CENTER_KEY,
                        point == null ? "" : point.getX() + "," + point.getY() + "," + point.getPlane());
                // ConfigChanged will reparse and rescan.
        }

        private void toggleTag(int id)
        {
                Set<Integer> ids = new LinkedHashSet<>(targetIds);
                if (!ids.remove(id))
                {
                        ids.add(id);
                }
                List<Integer> sorted = new ArrayList<>(ids);
                Collections.sort(sorted);
                configManager.setConfiguration(AutoFightConfig.GROUP, AutoFightConfig.NPC_IDS_KEY,
                        sorted.stream().map(String::valueOf).collect(Collectors.joining(",")));
                // ConfigChanged will reparse and rescan.
        }

        // ---------------------------------------------------------------- small helpers

        private int signedJitter(int px)
        {
                return px <= 0 ? 0 : random.nextInt(2 * px + 1) - px;
        }

        private static double quad(double a, double c, double b, double t)
        {
                double u = 1.0d - t;
                return u * u * a + 2.0d * u * t * c + t * t * b;
        }

        // ---------------------------------------------------------------- overlay accessors

        int getKills()
        {
                return kills;
        }

        Set<Integer> getTargetIds()
        {
                return targetIds;
        }

        NPC getCurrentTarget()
        {
                return currentTarget;
        }

        NPC getNextTarget()
        {
                return nextTarget;
        }

        int getMatchingCount()
        {
                return matching.size();
        }

        double getKillsPerHour()
        {
                if (sessionStart == null || kills == 0)
                {
                        return 0;
                }
                long ms = Duration.between(sessionStart, Instant.now()).toMillis();
                return ms <= 0 ? 0 : kills * 3_600_000.0 / ms;
        }

        void resetCounter()
        {
                resetSession();
        }
}
