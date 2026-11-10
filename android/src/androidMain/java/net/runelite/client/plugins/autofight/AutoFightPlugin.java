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
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.plugins.humanizer.BankSession;
import net.runelite.client.plugins.humanizer.GameHelpers;
import net.runelite.client.plugins.humanizer.HumanInput;
import net.runelite.client.plugins.humanizer.HumanizerConfig;
import net.runelite.client.plugins.humanizer.Target;
import net.runelite.client.plugins.humanizer.Targets;
import net.runelite.client.plugins.humanizer.WebWalker;
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
 *       timeout), reads where the client drew the "Attack" row, drags the cursor to it
 *       along a randomized sine-wave path (the hover trail that highlights rows) and
 *       clicks it.</li>
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
 * Voluntary camera turns only happen every 5-15 kills. Each turn is animated over
 * roughly a second or two of client ticks with a randomized ease-in velocity curve
 * (starts slow, speeds up), sometimes hesitating mid-turn, sometimes overshooting the
 * target and settling back, sometimes stopping just short of it, and occasionally
 * dragging the pitch along slightly out of phase. A rate-limited "rescue" turn brings a
 * fully off-screen target back into view when an attack is due, but with the same slow
 * humanized motion — never a snap.
 *
 * <h2>Area</h2>
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
        tags = {"npc", "highlight", "indicators", "combat", "kill", "counter", "mobile", "id", "loot", "attack", "camera", "humanizer"},
        enabledByDefault = false
)
public class AutoFightPlugin extends Plugin
{
        private static final String TAG = "AF-Tag";
        private static final String UNTAG = "AF-Untag";
        private static final String SET_CENTER = "AF-Set area centre";
        private static final String CLEAR_CENTER = "AF-Clear area centre";

        private static final String LOG_TAG = "AutoFight";

        /** Ticks after a counted kill during which the looter may act. */
        /** Ticks to wait for a drop to appear after the death animation starts. */
        private static final int DROP_APPEAR_TICKS = 6;
        /** Our drops stay ours (and worth walking back for) about a minute. */
        private static final int DROP_EXPIRE_TICKS = 100;
        /** Taps on one ground item before giving up on it. */
        private static final int MAX_LOOT_ATTEMPTS = 3;

        /** Ticks to wait for combat to actually start before assuming the attack click was lost. */
        private static final int ATTACK_WAIT_TICKS = 6;


        /** Turns smaller than this are not worth animating. */

        /** The context menu's "Choose Option" header height, in canvas pixels (real space). */


        /** Ground-misclick correction window: the re-tap lands this many ms after the slip. */

        /** Rate limit for off-screen rescue camera turns. */

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
        private HumanInput input;
        private WebWalker walker;
        private BankSession bankSession;

        private final List<NPC> matching = new ArrayList<>();
        private LocalReach reach;

        // ---- eating ----
        private GameHelpers.ItemList foodList = new GameHelpers.ItemList("", false, 1);
        private GameHelpers.ItemList withdrawFoodList = new GameHelpers.ItemList("", false, 1);
        private GameHelpers.ItemList withdrawItemsList = new GameHelpers.ItemList("", false, 1);
        private GameHelpers.ItemList keepList = new GameHelpers.ItemList("", true, 1);
        private int eatThreshold;
        private int eatCooldown;
        private int eatPendingTicks;
        private int hpBeforeEat;
        private int foodBeforeEat;

        // ---- banking ----
        private boolean returning;
        private WorldPoint returnPoint;
        private boolean bankBlocked;
        private int bankBlockedTicks;
        private boolean bankFailNotified;
        private final java.util.Map<NPC, Long> unreachableUntil = new java.util.HashMap<>();

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

        private int tickCount;

        // ---- looting: one decision per kill, kept until it is done ----
        /** Where our kills dropped their loot. */
        private final List<int[]> drops = new ArrayList<>(); // {x, y, plane, tick}
        /** Committed to picking up loot now (decided when the kill landed). */
        private boolean looting;
        private int lootStartTick;
        /** The wave this looting run follows: item order = angle + amp * sin(freq * distance + phase). */
        private double lootWaveAmp;
        private double lootWaveFreq;
        private double lootWavePhase;
        private Target pendingLoot;
        private int pendingLootTicks;
        private final java.util.Map<Object, Integer> lootAttempts = new java.util.HashMap<>();
        private int waitTicks;
        /** Set when a tap visibly did nothing; the next attempt goes out at once with a short pad. */
        private boolean missNoticed;
        private NPC pendingTarget;
        private int pendingTicks;
        private NPC lastVictim;
        private int pendingAttempts;

        private WorldPoint huntCenter; // manual centre from the settings, or null
        /** Where the player stood when the script was switched on (Microbot's centerLocation). */
        private WorldPoint startAnchor;





        private final Function<NPC, HighlightedNpc> highlighter = this::highlight;


        @Provides
        AutoFightConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoFightConfig.class);
        }

        @Override
        protected void startUp()
        {
                net.runelite.client.plugins.humanizer.SessionLog.INSTANCE.statusSource("AutoFight", this::getStatus);
                parseIds();
                parseLoot();
                parseCenter();
                parseFood();
                input = new HumanInput(client, clientThread, configManager.getConfig(HumanizerConfig.class), LOG_TAG);
                walker = new WebWalker(client, input, LOG_TAG);
                bankSession = new BankSession(client, itemManager, input, walker);
                input.setFocus(
                        () -> currentTarget != null ? npcTarget(currentTarget) : nextTarget != null ? npcTarget(nextTarget) : null,
                        () -> huntCenter(client.getLocalPlayer()),
                        () -> config.huntRadius());
                input.start();
                WebWalker.preload();
                rollEatThreshold();
                overlayManager.add(overlay);
                overlayManager.add(huntOverlay);
                npcOverlayService.registerHighlighter(highlighter);
                clientThread.invoke(this::rescan);
        }

        @Override
        protected void shutDown()
        {
                net.runelite.client.plugins.humanizer.SessionLog.INSTANCE.statusSource("AutoFight", null);
                input.stop();
                npcOverlayService.unregisterHighlighter(highlighter);
                overlayManager.remove(overlay);
                overlayManager.remove(huntOverlay);
                matching.clear();
                currentTarget = null;
                nextTarget = null;
                clearAutomation();
                resetSession();
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

        /**
         * The area centre, the way Microbot's AIO fighter does it: the manually set tile, else
         * the tile the player stood on when the script was switched on. It never follows the
         * player, so the script cannot drift away chasing targets.
         */
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
                return local == null ? null : local.getWorldLocation();
        }

        /** Sets / clears the start anchor as the script is switched on / off. Client thread. */
        private void updateAnchor(Player local)
        {
                if (!config.autoAttack())
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
                if (!config.useHuntRange() || c == null || here == null)
                {
                        return false;
                }
                return c.getPlane() != here.getPlane()
                        || Math.max(Math.abs(here.getX() - c.getX()), Math.abs(here.getY() - c.getY())) > config.huntRadius();
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
                return (huntCenter == null ? "start " : "") + center.getX() + "," + center.getY()
                        + " r" + config.huntRadius();
        }

        /** Centre tile for the world-space range overlay; null when not determinable. */
        WorldPoint getHuntCenterWorld()
        {
                return huntCenter(client.getLocalPlayer());
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
                reach = LocalReach.compute(client);
                for (NPC npc : wv.npcs())
                {
                        if (matches(npc) && !npcUtil.isDying(npc) && inHuntRange(npc, center) && reachable(reach, npc))
                        {
                                matching.add(npc);
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
                NPC target = pendingTarget != null ? pendingTarget : lastVictim;
                if (target == null)
                {
                        return;
                }
                unreachableUntil.put(target, System.currentTimeMillis() + 60_000L);
                if (unreachableUntil.size() > 64)
                {
                        unreachableUntil.clear();
                }
                pendingTarget = null;
                pendingTicks = 0;
                if (input.logging())
                {
                        Log.i(LOG_TAG, "target unreachable, skipping it for a minute");
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
                drops.clear();
                looting = false;
                pendingLoot = null;
                pendingLootTicks = 0;
                lootAttempts.clear();
                waitTicks = 0;
                pendingTarget = null;
                pendingTicks = 0;
                lastVictim = null;
                pendingAttempts = 0;
                eatPendingTicks = 0;
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

        // ---------------------------------------------------------------- automation


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
                if (eatCooldown > 0)
                {
                        eatCooldown--;
                }

                Player local = client.getLocalPlayer();
                if (local == null)
                {
                        return;
                }

                // Eating comes before everything else, reaction pause or not: a player eats as soon
                // as they notice the HP bar, and a little faster the lower it is.
                if (config.autoEat() && tickEating(local))
                {
                        return;
                }

                if (waitTicks > 0)
                {
                        waitTicks--;
                        return;
                }

                if (!config.autoAttack())
                {
                        if (bankSession.isActive() || returning)
                        {
                                clearAutomation();
                        }
                        return;
                }

                // ---- bank trip & walk back ----
                if (bankSession.isActive())
                {
                        int r = bankSession.tick(local, reach);
                        if (r == BankSession.DONE)
                        {
                                if (!bankSession.skipped().isEmpty())
                                {
                                        notifier.notify(Notification.ON, "Bank had none of: " + String.join(", ", bankSession.skipped()));
                                }
                                returning = true;
                                walker.start(Collections.singletonList(returnPoint), 2, config.bankMaxDistance() * 3 + 120);
                                waitTicks = 1 + random.nextInt(3);
                        }
                        else if (r == BankSession.FAILED)
                        {
                                bankBlocked = true;
                                notifier.notify(Notification.ON, "Bank trip stopped: " + bankSession.failure());
                        }
                        return;
                }
                if (returning)
                {
                        WorldPoint here = local.getWorldLocation();
                        boolean inRange = returnPoint == null || (returnPoint.getPlane() == here.getPlane()
                                && Math.max(Math.abs(here.getX() - returnPoint.getX()), Math.abs(here.getY() - returnPoint.getY()))
                                <= Math.max(2, config.huntRadius() / 2));
                        int w = inRange && !matching.isEmpty() ? WebWalker.ARRIVED : walker.tick(local);
                        if (w == WebWalker.ARRIVED)
                        {
                                returning = false;
                                walker.stop();
                                nextTarget = null;
                                waitTicks = input.reactionDelay();
                        }
                        else if (w == WebWalker.FAILED)
                        {
                                returning = false;
                                walker.stop();
                                bankBlocked = true;
                                notifier.notify(Notification.ON, "No walkable route back to the fight area");
                        }
                        return;
                }
                if (config.bankEnabled() && !bankBlocked)
                {
                        String why = bankReason(local);
                        if (why != null)
                        {
                                startBankTrip(local, why);
                                return;
                        }
                }
                if (bankBlocked && !GameHelpers.inventoryFull(client) && foodInInventory() > 0)
                {
                        bankBlocked = false;
                }

                expireDrops();

                // Committed to looting (decided when the kill landed): finish it before anything
                // else, so the script never flips between "next target" and "pick it up".
                if (looting)
                {
                        tickLooting(local);
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

                // An attack was sent but combat never started (out of reach, click lost, target
                // taken by someone else). A tap that left the player idle is fixed straight away.
                if (pendingTarget != null)
                {
                        pendingTicks++;
                        if (pendingTicks < ATTACK_WAIT_TICKS && !tapMissed(local) && matching.contains(pendingTarget))
                        {
                                return;
                        }
                        if (pendingTicks < ATTACK_WAIT_TICKS)
                        {
                                missNoticed = true;
                                waitTicks = 0;
                        }
                        pendingTarget = null;
                        pendingTicks = 0;
                }

                if (input.isBusy())
                {
                        return;
                }

                // Loot left for later (kill-first decision) gets picked up when there is nothing
                // to fight, or before it would expire.
                if (config.autoLoot() && !drops.isEmpty() && (nextTarget == null || dropsAboutToExpire()))
                {
                        startLooting();
                        return;
                }

                // Nothing to fight here and outside the area: walk back to its centre (Microbot's
                // AIO fighter does the same with walkTo(centerLocation)).
                if (nextTarget == null && outsideArea(local))
                {
                        returnPoint = huntCenter(local);
                        returning = true;
                        walker.start(Collections.singletonList(returnPoint), 1, 600);
                        input.log("outside the area: walking back to " + returnPoint);
                        return;
                }

                if (nextTarget != null)
                {
                        NPC victim = nextTarget;
                        if (victim != lastVictim)
                        {
                                lastVictim = victim;
                                pendingAttempts = 0;
                        }
                        boolean quick = missNoticed || input.quickRetry(pendingAttempts);
                        missNoticed = false;
                        // Off-screen targets turn the camera instead (interact returns false; retried next tick).
                        if (input.interact(npcTarget(victim), "Attack", GameHelpers.npcDefaultAction(victim, "Attack"), quick, true))
                        {
                                pendingTarget = victim;
                                pendingTicks = 0;
                                pendingAttempts++;
                                waitTicks = input.reactionDelay();
                        }
                }
        }

        // ---------------------------------------------------------------- loot decisions

        /**
         * A counted kill: remember where it dropped, then decide once — loot right away
         * (the configured chance) or keep fighting and collect it later together with the
         * next drops. The decision stands until that looting run is finished.
         */
        private void onKill(NPC npc)
        {
                if (!config.autoLoot())
                {
                        return;
                }
                WorldPoint wp = npc.getWorldLocation();
                if (wp != null)
                {
                        drops.add(new int[]{wp.getX(), wp.getY(), wp.getPlane(), tickCount});
                }
                if (!looting && random.nextInt(100) < config.lootNowChance())
                {
                        startLooting();
                }
                input.log("kill: " + (looting ? "loot now" : "loot later") + " (" + drops.size() + " drop piles)");
        }

        private void startLooting()
        {
                looting = true;
                lootStartTick = tickCount;
                pendingLoot = null;
                pendingLootTicks = 0;
                lootAttempts.clear();
                // A fresh wave for this run: how the pick-up order sweeps around the player.
                lootWaveAmp = 0.4d + random.nextDouble() * 1.4d;
                lootWaveFreq = 0.3d + random.nextInt(8) * 0.25d;
                lootWavePhase = random.nextDouble() * Math.PI * 2.0d;
        }

        private void stopLooting()
        {
                looting = false;
                drops.clear();
                pendingLoot = null;
                pendingLootTicks = 0;
                lootAttempts.clear();
                waitTicks = input.reactionDelay();
        }

        private void expireDrops()
        {
                drops.removeIf(d -> tickCount - d[3] > DROP_EXPIRE_TICKS);
        }

        private boolean dropsAboutToExpire()
        {
                for (int[] d : drops)
                {
                        if (tickCount - d[3] > DROP_EXPIRE_TICKS - 25)
                        {
                                return true;
                        }
                }
                return false;
        }

        /** One looting tick: wait for the drop, pick items one by one along the wave, then finish. */
        private void tickLooting(Player local)
        {
                if (input.isBusy())
                {
                        return;
                }
                if (pendingLoot != null)
                {
                        pendingLootTicks++;
                        boolean moving = client.getLocalDestinationLocation() != null;
                        if (pendingLoot.isLive() && (moving || pendingLootTicks < 3) && pendingLootTicks < 10)
                        {
                                return; // walking over / picking it up
                        }
                        pendingLoot = null;
                        pendingLootTicks = 0;
                }
                if (GameHelpers.inventoryFull(client))
                {
                        stopLooting(); // banking (if enabled) takes over
                        return;
                }
                LootCandidate next = nextLoot(local);
                if (next == null)
                {
                        // Nothing (yet): the drop appears a few ticks after the death animation.
                        if (tickCount - lootStartTick < DROP_APPEAR_TICKS)
                        {
                                return;
                        }
                        stopLooting();
                        return;
                }
                int tries = lootAttempts.getOrDefault(next.target.raw(), 0);
                if (tries >= MAX_LOOT_ATTEMPTS)
                {
                        return;
                }
                if (input.interact(next.target, "Take", true, false, false))
                {
                        lootAttempts.put(next.target.raw(), tries + 1);
                        pendingLoot = next.target;
                        pendingLootTicks = 0;
                        waitTicks = Math.min(input.reactionDelay(), 2);
                }
        }

        private static final class LootCandidate
        {
                final Target target;
                final double key;

                LootCandidate(Target target, double key)
                {
                        this.target = target;
                        this.key = key;
                }
        }

        /**
         * The next item to pick up: listed loot lying on (or right next to) one of our drop
         * tiles, reachable, within the loot radius. Among those the order follows this run's
         * wave — angle around the player bent by amp * sin(freq * distance + phase) — so the
         * finger sweeps the piles in a curve that is different every time.
         */
        private LootCandidate nextLoot(Player local)
        {
                if (lootIds.isEmpty() && lootNames.isEmpty())
                {
                        return null;
                }
                WorldView wv = client.getTopLevelWorldView();
                LocalPoint here = local.getLocalLocation();
                if (wv == null || wv.getScene() == null || here == null)
                {
                        return null;
                }
                Tile[][][] tiles = wv.getScene().getTiles();
                int plane = wv.getPlane();
                if (plane < 0 || plane >= tiles.length)
                {
                        return null;
                }
                int meX = here.getSceneX();
                int meY = here.getSceneY();
                int radius = config.lootRadius();
                LootCandidate best = null;
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
                                if (tile == null || !nearOurDrop(tile.getWorldLocation()))
                                {
                                        continue;
                                }
                                if (reach != null && !reach.tile(x, y) && !reach.canInteract(x, y, 1, 1))
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
                                        if (!isLootItem(item.getId()))
                                        {
                                                continue;
                                        }
                                        final Tile t = tile;
                                        final int id = item.getId();
                                        Target target = Targets.groundItem(client, t, id, () -> hasGroundItem(t, id));
                                        if (lootAttempts.getOrDefault(target.raw(), 0) >= MAX_LOOT_ATTEMPTS)
                                        {
                                                continue;
                                        }
                                        double dx = x - meX;
                                        double dy = y - meY;
                                        double dist = Math.hypot(dx, dy);
                                        double angle = Math.atan2(dy, dx) + Math.PI; // 0..2pi
                                        double key = angle + lootWaveAmp * Math.sin(lootWaveFreq * dist + lootWavePhase) + dist * 0.05d;
                                        if (best == null || key < best.key)
                                        {
                                                best = new LootCandidate(target, key);
                                        }
                                }
                        }
                }
                return best;
        }

        private boolean nearOurDrop(WorldPoint wp)
        {
                if (wp == null)
                {
                        return false;
                }
                for (int[] d : drops)
                {
                        if (d[2] == wp.getPlane() && Math.abs(d[0] - wp.getX()) <= 2 && Math.abs(d[1] - wp.getY()) <= 2)
                        {
                                return true;
                        }
                }
                return false;
        }

        // ---------------------------------------------------------------- eating

        private void parseFood()
        {
                foodList = new GameHelpers.ItemList(config.foodItems(), false, 1);
                withdrawFoodList = new GameHelpers.ItemList(config.withdrawFood(), false, 10);
                withdrawItemsList = new GameHelpers.ItemList(config.withdrawItems(), false, 1);
                keepList = new GameHelpers.ItemList(config.keepItems(), true, 1);
        }

        private void rollEatThreshold()
        {
                int base = config.eatAtPercent();
                int j = Math.max(0, config.eatJitter());
                eatThreshold = Math.max(5, Math.min(95, base + (j == 0 ? 0 : random.nextInt(2 * j + 1) - j)));
        }

        private int hpPercent()
        {
                int max = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
                return client.getBoostedSkillLevel(Skill.HITPOINTS) * 100 / max;
        }

        /** True when this item counts as food (listed, or anything with an "Eat" option when the list is empty). */
        private boolean isFood(int itemId)
        {
                if (itemId <= 0 || itemId == GameHelpers.EMPTY_ITEM_ID)
                {
                        return false;
                }
                if (!foodList.isEmpty())
                {
                        return foodList.contains(itemManager, itemId) || withdrawFoodList.contains(itemManager, itemId);
                }
                if (!withdrawFoodList.isEmpty() && withdrawFoodList.contains(itemManager, itemId))
                {
                        return true;
                }
                ItemComposition comp = itemManager.getItemComposition(itemId);
                return comp != null && GameHelpers.hasAction(comp.getInventoryActions(), "Eat");
        }

        private int foodInInventory()
        {
                int n = 0;
                for (Item i : GameHelpers.inventory(client))
                {
                        if (isFood(i.getId()))
                        {
                                n++;
                        }
                }
                return n;
        }

        /**
         * Eats when HP is at or below the (wobbling) threshold. One bite, then the 3-tick eat
         * delay; the bite is confirmed by HP going up or the food count going down, and
         * retried once if neither happened.
         *
         * @return true when this tick was spent on eating
         */
        private boolean tickEating(Player local)
        {
                if (eatPendingTicks > 0)
                {
                        eatPendingTicks++;
                        int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
                        if (hp > hpBeforeEat || foodInInventory() < foodBeforeEat)
                        {
                                eatPendingTicks = 0;
                                eatCooldown = 3;
                                rollEatThreshold();
                                return false;
                        }
                        if (eatPendingTicks > 4)
                        {
                                eatPendingTicks = 0; // the bite did not land: try again below
                        }
                        else
                        {
                                return true;
                        }
                }
                if (eatCooldown > 0 || input.isBusy() || hpPercent() > eatThreshold)
                {
                        return false;
                }
                if (bankSession.isActive() && bankSession.bankOpen())
                {
                        return false;
                }
                if (!GameHelpers.openInventoryTab(client, input))
                {
                        return true; // tapped the inventory tab; eat next tick
                }
                List<HumanInput.DropTarget> slots = input.slotTargets(ComponentID.INVENTORY_CONTAINER, this::isFood);
                if (slots.isEmpty())
                {
                        return false; // no food: banking (if enabled) takes over
                }
                HumanInput.DropTarget slot = slots.get(random.nextInt(Math.min(2, slots.size())));
                ItemComposition comp = itemManager.getItemComposition(slot.itemId);
                boolean leftClickEat = comp != null && GameHelpers.isFirstAction(comp.getInventoryActions(), "Eat");
                hpBeforeEat = client.getBoostedSkillLevel(Skill.HITPOINTS);
                foodBeforeEat = foodInInventory();
                boolean sent = leftClickEat
                        ? input.tap(new int[]{slot.x, slot.y}, "eat")
                        : input.longPressPick(new int[]{slot.x, slot.y}, "Eat");
                if (sent)
                {
                        eatPendingTicks = 1;
                        input.log("eat at " + hpPercent() + "% (threshold " + eatThreshold + "%)");
                }
                return true;
        }

        // ---------------------------------------------------------------- banking

        /** Why a bank trip is due now, or null. */
        private String bankReason(Player local)
        {
                boolean full = GameHelpers.inventoryFull(client);
                int food = foodInInventory();
                boolean foodPlanned = !withdrawFoodList.isEmpty();
                if (config.bankWhenFull() && full && currentTarget == null)
                {
                        return "inventory full";
                }
                if (foodPlanned && food == 0)
                {
                        int hp = hpPercent();
                        if (config.panicHpPercent() > 0 && hp <= config.panicHpPercent())
                        {
                                return "out of food at " + hp + "% HP";
                        }
                        if (config.bankWhenOutOfFood() && currentTarget == null)
                        {
                                return "out of food";
                        }
                }
                return null;
        }

        private boolean keepOnDeposit(int itemId)
        {
                return keepList.contains(itemManager, itemId)
                        || withdrawFoodList.contains(itemManager, itemId)
                        || withdrawItemsList.contains(itemManager, itemId)
                        || (!foodList.isEmpty() && foodList.contains(itemManager, itemId));
        }

        private void startBankTrip(Player local, String why)
        {
                List<BankSession.Need> needs = new ArrayList<>();
                for (GameHelpers.ItemList.Entry e : withdrawFoodList.entries)
                {
                        needs.add(new BankSession.Need(withdrawFoodList, e, e.quantity, true));
                }
                for (GameHelpers.ItemList.Entry e : withdrawItemsList.entries)
                {
                        needs.add(new BankSession.Need(withdrawItemsList, e, e.quantity, false));
                }
                returnPoint = huntCenter(local);
                pendingTarget = null;
                pendingTicks = 0;
                if (!bankSession.begin(local, config.bankMaxDistance(), this::keepOnDeposit, needs, chosenBank()))
                {
                        bankBlocked = true;
                        notifier.notify(Notification.ON, "Bank trip impossible: " + bankSession.failure());
                        return;
                }
                notifier.notify(Notification.ON, "Banking (" + why + ")");
                input.log("bank trip: " + why);
                waitTicks = why.contains("HP") ? 0 : input.reactionDelay();
        }

        // ---------------------------------------------------------------- looting


        private static boolean hasGroundItem(Tile tile, int itemId)
        {
                List<TileItem> items = tile.getGroundItems();
                if (items == null)
                {
                        return false;
                }
                for (TileItem i : items)
                {
                        if (i.getId() == itemId)
                        {
                                return true;
                        }
                }
                return false;
        }

        /**
         * Two ticks after a tap the player should be fighting the target or at least walking
         * there. Standing still with nothing targeted means the tap was lost.
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
                else if (AutoFightConfig.FOOD_ITEMS_KEY.equals(event.getKey())
                        || AutoFightConfig.WITHDRAW_FOOD_KEY.equals(event.getKey())
                        || AutoFightConfig.WITHDRAW_ITEMS_KEY.equals(event.getKey())
                        || AutoFightConfig.KEEP_ITEMS_KEY.equals(event.getKey()))
                {
                        parseFood();
                }
                else if ("autoAttack".equals(event.getKey()) || "bankEnabled".equals(event.getKey()))
                {
                        clientThread.invoke(this::clearAutomation);
                }
                else if ("eatAtPercent".equals(event.getKey()) || "eatJitter".equals(event.getKey()))
                {
                        rollEatThreshold();
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
                        matching.clear();
                        currentTarget = null;
                        nextTarget = null;
                        clearAutomation();
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
                        onKill(npc);
                        notifier.notify(config.notifyKill(), "Killed " + Text.removeTags(String.valueOf(npc.getName())) + " (" + kills + ")");
                        input.onProgress();
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
        public void onChatMessage(net.runelite.api.events.ChatMessage event)
        {
                if (event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE
                        && event.getType() != net.runelite.api.ChatMessageType.SPAM)
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
                                markUnreachable();
                        }
                }
        }

        @Subscribe
        public void onGameTick(GameTick tick)
        {
                tickCount++;
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
                if (currentTarget == null && !matching.isEmpty() && sessionStart != null
                        && !bankSession.isActive() && !returning)
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

                // Rotates the input style; idles the finger while a kill is in progress.
                input.onGameTick(config.autoAttack() && currentTarget != null);

                automate();
        }

        @Subscribe
        public void onClientTick(ClientTick tick)
        {
                input.onClientTick();
        }

        /**
         * Resolves a pending menu scan: locates the "Attack" row in the open context menu
         * (in stretched canvas space, where taps are dispatched) and hands the point to the
         * waiting gesture. When no attack row exists, a safe dismiss point is produced
         * instead so the gesture can tap the menu away.
         */



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


        // ---------------------------------------------------------------- overlay accessors

        String getPersonaLabel()
        {
                return input == null ? "-" : input.getPersonaLabel();
        }

        long getMethodSwitchInSeconds()
        {
                return input == null ? -1 : input.getMethodSwitchInSeconds();
        }

        /** What the script is doing, for the overlay. */
        String getStatus()
        {
                if (eatPendingTicks > 0)
                {
                        return "Eating";
                }
                if (bankSession != null && bankSession.isActive())
                {
                        return bankSession.status();
                }
                if (returning)
                {
                        return "Walking back";
                }
                if (bankBlocked)
                {
                        return "Bank blocked: " + (bankSession.failure() == null ? "?" : bankSession.failure());
                }
                return currentTarget != null ? "Fighting" : "Idle";
        }

        int getFoodCount()
        {
                return foodInInventory();
        }

        /** For the Scripts panel: whether automation is on. */
        public boolean isAutomationOn()
        {
                return config.autoAttack();
        }

        private Target npcTarget(NPC npc)
        {
                return Targets.npc(npc, () -> matching.contains(npc) && !npc.isDead());
        }

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

        /** The bank the user picked (this script's setting, or the shared Humanizer default); null = closest. */
        private net.runelite.client.plugins.gamedata.BankLocation chosenBank()
        {
                return net.runelite.client.plugins.gamedata.BankPick.resolve(config.bankPick(), input.config().defaultBank());
        }
}
