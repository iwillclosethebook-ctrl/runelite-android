package net.runelite.client.plugins.humanizer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewConfiguration;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.mobile.MobileHitTest;
import net.runelite.client.plugins.mobile.MobileInventoryOverlay;
import net.runelite.client.plugins.mobile.MobileScrollbars;
import net.runelite.client.util.Text;
import net.runelite.mp.ui.bridge.KeyDispatch;
import net.runelite.mp.ui.bridge.ModifierState;

/**
 * The shared simulated-touch engine every auto script drives (one instance per script).
 *
 * <p>Every action is a replayed finger gesture: two parking moves at finger-down, a
 * real-time press, then the four-event burst the touch driver produces — never an
 * internal menu call. Taps land at a fresh point on the target each time, steered by a
 * rotating input style; before a plain tap the parked finger's left-click option is read
 * and the finger re-aims (or falls back to long-press + menu) if the tap would not do the
 * wanted thing to that exact target. Off-screen targets bring the camera round with a
 * slow, slightly off-centre turn instead of being tapped blind.</p>
 *
 * <p>All of it reads one {@link HumanizerConfig}, so the feel is set in one place.</p>
 */
public final class HumanInput
{
        /** Camera yaw/pitch units. */
        private static final int JAU14_MASK = 0x3FFF;
        private static final double JAU14_PER_RADIAN = 16384.0d / (2.0d * Math.PI);

        /** Turns smaller than this (JAU) are not worth a camera move. */
        private static final int CAMERA_DEADZONE = 150;

        /** Height of the context menu's "Choose Option" header, in real canvas pixels. */
        private static final int MENU_HEADER_HEIGHT = 19;

        private static final int PITCH_MIN = 128;
        private static final int PITCH_MAX = 383;

        /** Ground-misclick correction window. */
        private static final int MISCORRECT_MIN_MS = 50;
        private static final int MISCORRECT_MAX_MS = 97;

        private static final long RESCUE_COOLDOWN_MS = 4_000L;

        private static final int EMPTY_ITEM_ID = 6512;

        private static final int INV_COLUMNS = 4;

        private final Client client;
        private final ClientThread clientThread;
        private final HumanizerConfig config;
        private final String LOG_TAG;
        private final Random random = new Random();

        // ---- input style rotation ----
        private volatile Persona persona;
        private volatile long personaChangedAt;
        private volatile long nextPersonaSwitchAt;

        // ---- camera ----
        private int progressSinceTurn;
        private int nextTurnProgress;
        private CameraAnim yawAnim;
        private CameraAnim pitchAnim;
        private long lastRescueMs;
        private Supplier<Target> focusTarget;
        private Supplier<WorldPoint> focusCentre;
        private IntSupplier focusRadius;

        // ---- menu scan (client thread <-> timeline thread) ----
        private volatile boolean menuScanPending;
        private volatile long menuScanRequestedAt;
        /** The exact target the wanted row must belong to; null = any row with the option. */
        private volatile Object menuScanTarget;
        private volatile String menuScanWanted;
        private volatile int[] menuActionPoint;    // stretched canvas coords
        private volatile int menuActionKind;       // 0 none, 1 wanted row, 2 dismiss point

        private long nextHoverDriftAt;
        private XpGlance xpGlance;
        private volatile Skill trainedSkill;

        private final TouchEngine touch = new TouchEngine();

        public HumanInput(Client client, ClientThread clientThread, HumanizerConfig config, String tag)
        {
                this.client = client;
                this.clientThread = clientThread;
                this.config = config;
                this.LOG_TAG = tag;
        }

        // ---------------------------------------------------------------- lifecycle & public API

        public void start()
        {
                pickInitialPersona();
                progressSinceTurn = 0;
                rollNextTurn();
                nextHoverDriftAt = 0L;
                lastRescueMs = 0L;
                reset();
                touch.start();
        }

        public void stop()
        {
                touch.stop();
                reset();
                if (xpGlance != null)
                {
                        xpGlance.reset();
                }
        }

        /** Forget gestures, camera turns and menu scans in flight (logout, hop, script stop). */
        public void reset()
        {
                touch.cancelAll();
                yawAnim = null;
                pitchAnim = null;
                menuScanPending = false;
                menuScanWanted = null;
                menuScanTarget = null;
                menuActionPoint = null;
                menuActionKind = 0;
        }

        /**
         * What voluntary camera turns look at: mostly {@code target} (the current / next
         * target), sometimes the work-area centre, sometimes a random spot in the area.
         */
        public void setFocus(Supplier<Target> target, Supplier<WorldPoint> centre, IntSupplier radius)
        {
                this.focusTarget = target;
                this.focusCentre = centre;
                this.focusRadius = radius;
        }

        public HumanizerConfig config()
        {
                return config;
        }

        public boolean isBusy()
        {
                return touch.isBusy();
        }

        public boolean logging()
        {
                return config.logInput();
        }

        public void log(String message)
        {
                if (config.logInput())
                {
                        Log.i(LOG_TAG, message);
                }
        }

        /** The skill this script trains (what an XP check looks at). Null = the last skill that gained XP. */
        public void setTrainedSkill(Skill skill)
        {
                this.trainedSkill = skill;
        }

        /** The XP-check habit (for overlays: {@code xpGlance().status()}). */
        public XpGlance xpGlance()
        {
                if (xpGlance == null)
                {
                        xpGlance = new XpGlance(this);
                }
                return xpGlance;
        }

        /**
         * Once per game tick: rotates the input style, now and then checks XP in the Skills
         * tab, and idles the finger while {@code waiting}.
         */
        public void onGameTick(boolean waiting)
        {
                maybeRotatePersona();
                XpGlance xp = xpGlance();
                // A finger resting on the next target is not to be disturbed.
                xp.tick(client, trainedSkill, waiting && !touch.isParked());
                if (touch.isParked())
                {
                        return;
                }
                if (xp.active())
                {
                        return;
                }
                if (!waiting || touch.isBusy() || System.currentTimeMillis() < nextHoverDriftAt)
                {
                        return;
                }
                nextHoverDriftAt = System.currentTimeMillis() + 2500L + random.nextInt(3500);
                Persona p = persona;
                int scaled = p == null ? 0 : p.hoverChance * Math.max(0, config.hoverScale()) / 100;
                if (p != null && random.nextInt(100) < scaled)
                {
                        touch.hoverDrift();
                }
        }

        /** Every client frame: camera animation and menu scans. */
        public void onClientTick()
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

        /** Whether the first retry after a lost tap should be a quick double-tap. */
        public boolean quickRetry(int attempts)
        {
                Persona p = persona;
                return attempts == 1 && p != null && random.nextInt(100) < p.doubleTapChance;
        }

        /**
         * Acts on a target with a simulated finger: a plain tap when {@code action} is its
         * left-click option (and the input style is not in a menu mood), otherwise a
         * long-press and a pick from the menu. When the target is not on screen the camera
         * is turned toward it instead and false is returned; call again on a later tick.
         *
         * @param defaultAction whether {@code action} is the target's left-click option
         * @param allowMisclick whether this tap may slip onto the ground first
         */
        public boolean interact(Target target, String action, boolean defaultAction, boolean quickRetry, boolean allowMisclick)
        {
                if (target == null || action == null)
                {
                        return false;
                }
                if (touch.isBusy())
                {
                        return false;
                }
                if (!visibleForTap(target))
                {
                        rescueCamera(target);
                        return false;
                }
                if (touch.interact(target, action, quickRetry, allowMisclick, !defaultAction))
                {
                        return true;
                }
                // On screen but no tappable point (behind an interface panel, at the edge):
                // turn the camera a little so it comes out into the open.
                if (!touch.isBusy())
                {
                        rescueCamera(target);
                }
                return false;
        }

        /** A plain tap on a fixed canvas point (minimap, a button). Stretched canvas space. */
        public boolean tap(int[] point, String label)
        {
                return touch.tap(point, label);
        }

        /** Long-press a fixed canvas point and pick {@code option} from the menu. */
        public boolean longPressPick(int[] point, String option)
        {
                return touch.longPressPick(point, option);
        }

        /** Long-press + "Drop" for each slot, in order. */
        public boolean drops(List<DropTarget> targets)
        {
                return touch.drops(targets);
        }

        /** Hold shift and tap each slot once (needs the game's shift-click drop setting). */
        public boolean shiftDrops(List<DropTarget> targets)
        {
                return touch.shiftDrops(targets);
        }

        /** Types text through the keyboard path, optionally pressing Enter at the end. */
        public boolean type(String text, boolean enter)
        {
                return touch.type(text, enter);
        }

        /** One real key stroke (separate press and release with a human hold), e.g. an F-key. */
        public boolean pressKey(int vk, String label)
        {
                return touch.pressKey(vk, label);
        }

        /**
         * Moves the finger onto {@code target} and leaves it resting there (no tap), so the
         * next {@link #interact} on the same target is a near-instant tap from where the
         * finger already is. While parked, idle drift and XP checks leave the finger alone.
         * Returns true when the finger is (or is now going) on the target.
         */
        public boolean park(Target target)
        {
                return touch.park(target);
        }

        /** True when the finger rests on {@code target} (see {@link #park}). */
        public boolean isParkedOn(Target target)
        {
                return touch.isParkedOn(target);
        }

        /** Rest the finger on a point for a while without tapping (curved approach, tiny wander). */
        public boolean hover(int[] point, int dwellMs, String label)
        {
                return touch.hover(point, dwellMs, label);
        }

        /** A fresh point inside the middle half of a widget (stretched canvas space), or null. */
        public int[] widgetPoint(Widget w)
        {
                if (w == null || w.isHidden())
                {
                        return null;
                }
                Rectangle b = w.getBounds();
                if (b == null || b.width <= 0 || b.height <= 0)
                {
                        return null;
                }
                int x = b.x + b.width / 2 + (int) Math.round((random.nextDouble() - 0.5d) * b.width * 0.5d);
                int y = b.y + b.height / 2 + (int) Math.round((random.nextDouble() - 0.5d) * b.height * 0.5d);
                int[] s = toStretched(x, y);
                int cw = canvasWidth();
                int ch = canvasHeight();
                if (cw <= 0 || ch <= 0 || s[0] < 2 || s[1] < 2 || s[0] >= cw - 2 || s[1] >= ch - 2)
                {
                        return null;
                }
                return s;
        }

        /** Minimap tap point for a world tile on the current plane, or null when off the minimap. */
        public int[] minimapPoint(int worldX, int worldY)
        {
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return null;
                }
                LocalPoint lp = LocalPoint.fromWorld(wv, worldX, worldY);
                if (lp == null)
                {
                        return null;
                }
                Point mm = Perspective.localToMinimap(client, lp);
                if (mm == null)
                {
                        return null;
                }
                int[] s = toStretched(mm.getX(), mm.getY());
                s[0] += signedJitter(2);
                s[1] += signedJitter(2);
                return s;
        }

        public int signedJitterPublic(int px)
        {
                return signedJitter(px);
        }

        public Random random()
        {
                return random;
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
                final int menuChance;      // % of clicks done via long-press + menu
                final int approachChance;  // % of taps preceded by a hover approach trail
                final int misclickChance;  // base % (scaled by the config setting)
                final int padMin;          // ms of extra reaction padding
                final int padMax;
                final int hoverChance;     // % chance of an idle hover drift while a script waits
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

        public String getPersonaLabel()
        {
                Persona p = persona;
                return p == null ? "-" : p.label;
        }

        public long getMethodSwitchInSeconds()
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

        // ---------------------------------------------------------------- timing

        /** Random pause before an automated click, so the cadence is not metronomic. */
        public int reactionDelay()
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

        // ---------------------------------------------------------------- screen geometry

        /** Real→stretched canvas scale factors {x, y} (1,1 when stretching is off). */
        public double[] stretchFactors()
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
        public int[] toStretched(int realX, int realY)
        {
                double[] f = stretchFactors();
                return new int[]{
                        (int) Math.round(realX * f[0]),
                        (int) Math.round(realY * f[1])
                };
        }

        public int canvasWidth()
        {
                Canvas canvas = Canvas.latest();
                return canvas == null ? 0 : canvas.getWidth();
        }

        public int canvasHeight()
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
        public int[] tapPoint(Target npc, Placement placement)
        {
                int[] real = hullPoint(npc, placement);
                if (real == null)
                {
                        // No hull (unusual view angle): aim at the projected body centre instead.
                        LocalPoint lp = npc.localLocation();
                        if (lp == null)
                        {
                                return null;
                        }
                        WorldView wv = client.getTopLevelWorldView();
                        int plane = wv == null ? 0 : wv.getPlane();
                        int height = npc.aimHeight();
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

        /** Random point inside the Target's hull in real canvas space, per the placement strategy. */
        private int[] hullPoint(Target npc, Placement placement)
        {
                Shape hull = npc.hull();
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

        /**
         * True when enough of the target is on screen to tap it like a player would: at
         * least ~40% of its clickbox inside the canvas, and its centre not hidden behind a
         * mobile interface panel.
         */
        public boolean visibleForTap(Target target)
        {
                Shape hull = target.hull();
                if (hull == null)
                {
                        return false;
                }
                Rectangle b = hull.getBounds();
                if (b.width <= 0 || b.height <= 0)
                {
                        return false;
                }
                int[] p1 = toStretched(b.x, b.y);
                int[] p2 = toStretched(b.x + b.width, b.y + b.height);
                int w = canvasWidth();
                int h = canvasHeight();
                if (w <= 0 || h <= 0)
                {
                        return false;
                }
                int ix = Math.max(0, Math.min(p2[0], w) - Math.max(p1[0], 0));
                int iy = Math.max(0, Math.min(p2[1], h) - Math.max(p1[1], 0));
                long area = (long) Math.max(1, p2[0] - p1[0]) * Math.max(1, p2[1] - p1[1]);
                if ((long) ix * iy * 10L < area * 4L)
                {
                        return false;
                }
                int cx = Math.max(1, Math.min(w - 2, (p1[0] + p2[0]) / 2));
                int cy = Math.max(1, Math.min(h - 2, (p1[1] + p2[1]) / 2));
                return !MobileHitTest.isInterfaceAt(cx, cy);
        }

        /**
         * A water point just outside the spot's clickbox, for the occasional misclick:
         * a tile within 1-2 of the spot's tile that is on screen, not on an interface, and
         * does not overlap the hull itself.
         */
        private int[] groundMisclickPoint(Target npc)
        {
                WorldPoint at = npc.worldLocation();
                WorldView wv = client.getTopLevelWorldView();
                if (at == null || wv == null)
                {
                        return null;
                }

                Shape hull = npc.hull();
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
                        LocalPoint lp = npc.localLocation();
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


        // ---------------------------------------------------------------- item slots

        /**
         * Tap / long-press points for the items in an item-container widget (inventory,
         * bank inventory, bank items), one per slot in reading order. A fresh spot inside
         * each slot with the same time-derived nudge as every other tap. Slots whose item
         * the filter rejects are skipped.
         */
        public List<DropTarget> slotTargets(int componentId, IntPredicate include)
        {
                Widget invWidget = client.getWidget(componentId);
                if (invWidget == null || invWidget.isHidden())
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
                for (int slot = 0; slot < children.length; slot++)
                {
                        Widget child = children[slot];
                        if (child == null)
                        {
                                continue;
                        }
                        int itemId = child.getItemId();
                        if (itemId <= 0 || itemId == EMPTY_ITEM_ID)
                        {
                                continue;
                        }
                        if (include != null && !include.test(itemId))
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
                        DropTarget dt = new DropTarget(s[0], s[1], "item " + itemId, slot, itemId);
                        dt.componentId = componentId;
                        plan.add(dt);
                }

                return plan;
        }

        /**
         * Orders a drop pass along a sine wave laid over the 4x7 inventory grid. Each pass
         * rolls a fresh wave: k in 1..8 sets how many half-oscillations fit across the
         * sweep, plus a random amplitude, phase, sweep axis and sweep direction. Slots are
         * sorted by (sweep position + amplitude * sin(k * pi * cross position + phase)),
         * so the finger snakes through the bag in a smooth, repeatable-looking curve whose
         * shape never repeats from one pass to the next. Each target also gets its wave
         * value stored so the tap rhythm can ride the same curve.
         */
        public void waveOrder(List<DropTarget> plan)
        {
                final int k = 1 + random.nextInt(8);
                final double amp = 0.35d + random.nextDouble() * (0.6d + k * 0.18d);
                final double phase = random.nextDouble() * Math.PI * 2d;
                final boolean byColumns = random.nextBoolean();
                final boolean reverse = random.nextInt(4) == 0;
                final double rows = 7d;
                final double cols = INV_COLUMNS;

                for (DropTarget t : plan)
                {
                        int row = t.slot / INV_COLUMNS;
                        int col = t.slot % INV_COLUMNS;
                        double sweep = byColumns ? col : row;
                        double cross = byColumns ? row / (rows - 1d) : col / (cols - 1d);
                        double wave = Math.sin(k * Math.PI * cross + phase);
                        t.wave = wave;
                        t.key = (reverse ? -sweep : sweep) + amp * wave + cross * 0.001d;
                }
                plan.sort((a, b) -> Double.compare(a.key, b.key));
                if (config.logInput())
                {
                        Log.i(LOG_TAG, String.format(Locale.ROOT, "drop wave: k=%d amp=%.2f phase=%.2f %s%s",
                                k, amp, phase, byColumns ? "columns" : "rows", reverse ? " reversed" : ""));
                }
        }

        // ---------------------------------------------------------------- touch engine

        /** One inventory slot to drop: a long-press point in stretched canvas space. */
        public static final class DropTarget
        {
                public final int x;
                public final int y;
                public final String label;
                public final int slot;
                public final int itemId;
                /** Inventory-like container the slot lives in (for re-aiming), or -1. */
                int componentId = -1;
                /** Wave value (-1..1) at this slot for the current pass; drives the tap rhythm. */
                double wave;
                double key;

                DropTarget(int x, int y, String label, int slot, int itemId)
                {
                        this.itemId = itemId;
                        this.x = x;
                        this.y = y;
                        this.label = label;
                        this.slot = slot;
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
                                Thread t = new Thread(r, LOG_TAG + "-Touch");
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
                 * Performs {@code wanted} on an object with a simulated finger. Chooses between
                 * a plain tap (when the action is the left-click default and the style is not in
                 * a menu mood) and a long-press + menu selection.
                 *
                 * @param allowMisclick whether this tap may slip onto the ground first
                 * @return true when a gesture was scheduled
                 */
                boolean interact(Target target, String wanted, boolean quickRetry, boolean allowMisclick, boolean menuForced)
                {
                        if (!running || busy || persona == null || wanted == null)
                        {
                                return false;
                        }
                        if (client.getGameState() != GameState.LOGGED_IN)
                        {
                                return false;
                        }

                        Persona p = persona;
                        boolean parkedHere = isParkedOn(target) && parkedPoint != null && !menuForced;
                        boolean useMenu = !parkedHere && (menuForced || random.nextInt(100) < p.menuChance);
                        Placement placement = useMenu ? Placement.TIGHT_BOX : p.placement;

                        int[] pt = parkedHere ? parkedPoint : tapPoint(target, placement);
                        if (pt == null)
                        {
                                return false;
                        }
                        int[] ground = useMenu || !allowMisclick || parkedHere ? null : groundMisclickPoint(target);

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
                                pad += 350 + random.nextInt(850);
                        }
                        if (parkedHere)
                        {
                                // Finger already on it, eyes already on it: just press.
                                pad = 25 + random.nextInt(70);
                        }

                        final int gen = ++generation;
                        final Target victim = target;
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
                                Log.i(LOG_TAG, (menu ? "menu-walk" : "tap") + " '" + action + "' at " + pt[0] + "," + pt[1]
                                        + " pad=" + padMs + "ms" + (ground != null ? " misclick-armed" : ""));
                        }
                        try
                        {
                                timeline.execute(() -> runInteract(gen, victim, style, menu, action, point, groundPoint, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                private volatile Target parkedTarget;
                private volatile int[] parkedPoint;
                private volatile int parkedGen;

                boolean isParked()
                {
                        return parkedTarget != null && parkedGen == generation;
                }

                boolean isParkedOn(Target target)
                {
                        return isParked() && target != null && target.equals(parkedTarget);
                }

                boolean park(Target target)
                {
                        if (isParkedOn(target))
                        {
                                return true;
                        }
                        if (!running || busy || persona == null || target == null || client.isMenuOpen())
                        {
                                return false;
                        }
                        int[] pt = tapPoint(target, Placement.TIGHT_BOX);
                        if (pt == null)
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        busy = true;
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                // A short look-over first, then a curved glide onto the target.
                                                if (!sleep(120 + random.nextInt(260)) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                approachTrail(gen, pt[0], pt[1]);
                                                if (aborted(gen))
                                                {
                                                        return;
                                                }
                                                emitMove(gen, pt[0], pt[1]);
                                                parkedPoint = pt;
                                                parkedTarget = target;
                                                parkedGen = gen;
                                                if (config.logInput())
                                                {
                                                        Log.i(LOG_TAG, "parked on next target at " + pt[0] + "," + pt[1]);
                                                }
                                        }
                                        finally
                                        {
                                                busy = false;
                                        }
                                });
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /** A plain tap on a fixed canvas point (minimap, bank button). */
                boolean tap(int[] pt, String label)
                {
                        if (!running || busy || persona == null || pt == null)
                        {
                                return false;
                        }
                        int[] dismiss = currentMenuDismissPoint();
                        if (client.isMenuOpen() && dismiss == null)
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        final Persona style = persona;
                        final int[] point = pt;
                        final int[] dismissPoint = dismiss;
                        final int padMs = Math.max(30, personaPad(style) / 2);
                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "tap '" + label + "' at " + pt[0] + "," + pt[1]);
                        }
                        try
                        {
                                timeline.execute(() -> runTap(gen, style, point, dismissPoint, padMs));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /** Long-press a fixed canvas point and pick {@code option} from the menu. */
                boolean longPressPick(int[] pt, String option)
                {
                        if (!running || busy || persona == null || pt == null)
                        {
                                return false;
                        }
                        int[] dismiss = currentMenuDismissPoint();
                        if (client.isMenuOpen() && dismiss == null)
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        final Persona style = persona;
                        final int[] point = pt;
                        final int[] dismissPoint = dismiss;
                        final int padMs = Math.max(40, personaPad(style) / 2);
                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "long-press '" + option + "' at " + pt[0] + "," + pt[1]);
                        }
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                if (dismissPoint != null && !dismissMenu(gen, dismissPoint))
                                                {
                                                        return;
                                                }
                                                if (!sleep(padMs) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                // Curved (harmonic) approach before every gesture.
                                                {
                                                        approachTrail(gen, point[0], point[1]);
                                                        if (aborted(gen))
                                                        {
                                                                return;
                                                        }
                                                }
                                                menuWalk(gen, point, style, option, null);
                                        }
                                        finally
                                        {
                                                busy = false;
                                        }
                                });
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
                 * Shift-drops a whole pass of inventory items: hold shift, then quick single
                 * taps on each slot in the pass's wave order, then let go of shift. Needs the
                 * in-game "Shift click to drop items" setting.
                 *
                 * @return true when a gesture was scheduled
                 */
                boolean shiftDrops(List<DropTarget> targets)
                {
                        if (!running || busy || persona == null || targets == null || targets.isEmpty())
                        {
                                return false;
                        }
                        if (client.getGameState() != GameState.LOGGED_IN)
                        {
                                return false;
                        }
                        int[] dismiss = currentMenuDismissPoint();
                        if (client.isMenuOpen() && dismiss == null)
                        {
                                return false;
                        }

                        final int gen = ++generation;
                        final Persona style = persona;
                        final List<DropTarget> batch = new ArrayList<>(targets);
                        final int[] dismissPoint = dismiss;

                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "shift-drop pass: " + batch.size() + " items");
                        }
                        try
                        {
                                timeline.execute(() -> runShiftDrops(gen, style, batch, dismissPoint));
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * Types text the way a hardware keyboard reaches the game: every key is a
                 * separate press and release (KEY_PRESSED + KEY_TYPED at key-down, KEY_RELEASED
                 * after a human hold), through the same dispatcher a physical keyboard uses.
                 * Capitals hold the real Shift key around the letter. The gap between keys
                 * rides a sine wave between the configured min/max (the rhythm speeds up and
                 * slows down), and each hold time rides a second, detuned cosine, so neither
                 * the press lengths nor the gaps ever fall into a fixed beat.
                 */
                boolean type(String text, boolean enter)
                {
                        if (!running || busy || text == null)
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        busy = true;
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                int min = Math.max(30, config.typeMinMs());
                                                int max = Math.max(min, config.typeMaxMs());
                                                double phase = random.nextDouble() * Math.PI * 2.0d;
                                                double freq = 0.5d + random.nextDouble() * 0.9d;
                                                KeyRhythm hold = new KeyRhythm();
                                                if (!sleep(180 + random.nextInt(260)) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                for (int i = 0; i < text.length(); i++)
                                                {
                                                        final char c = text.charAt(i);
                                                        if (aborted(gen) || !strokeChar(gen, c, hold, i))
                                                        {
                                                                return;
                                                        }
                                                        double wave = (Math.sin(phase + i * freq) + 1.0d) / 2.0d;
                                                        int gap = (int) Math.round(min + (max - min) * wave) + random.nextInt(25);
                                                        if (Character.isUpperCase(c) || c == ' ')
                                                        {
                                                                gap += 40 + random.nextInt(80);
                                                        }
                                                        // The hold already used part of the gap: keys overlap in time like real typing.
                                                        gap = Math.max(12, gap - hold.last);
                                                        if (!sleep(gap) || aborted(gen))
                                                        {
                                                                return;
                                                        }
                                                }
                                                if (enter)
                                                {
                                                        if (!sleep(120 + random.nextInt(220)) || aborted(gen))
                                                        {
                                                                return;
                                                        }
                                                        strokeKey(gen, java.awt.event.KeyEvent.VK_ENTER, '\n', hold, text.length());
                                                        sleep(60 + random.nextInt(80));
                                                }
                                        }
                                        finally
                                        {
                                                busy = false;
                                        }
                                });
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * One real key stroke on its own (an F-key for a side tab, Escape, Space):
                 * a short "finding the key" pause, key-down, a human hold, key-up.
                 */
                boolean pressKey(int vk, String label)
                {
                        if (!running || busy)
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "key '" + label + "' (vk " + vk + ")");
                        }
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                // Eyes go to the keyboard, the finger finds the key.
                                                double ph = random.nextDouble() * 2.0d * Math.PI;
                                                int find = 90 + (int) Math.round(70.0d * (1.0d + Math.sin(ph))) + random.nextInt(80);
                                                if (!sleep(find) || aborted(gen))
                                                {
                                                        return;
                                                }
                                                strokeKey(gen, vk, java.awt.event.KeyEvent.CHAR_UNDEFINED, new KeyRhythm(), 0);
                                                sleep(40 + random.nextInt(70));
                                        }
                                        finally
                                        {
                                                busy = false;
                                        }
                                });
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * Rests the finger on a point without tapping (looking at a skill, reading a
                 * tooltip): curved approach, then a slow, small Lissajous wander around the
                 * point for {@code dwellMs}.
                 */
                boolean hover(int[] pt, int dwellMs, String label)
                {
                        if (!running || busy || pt == null || client.isMenuOpen())
                        {
                                return false;
                        }
                        final int gen = ++generation;
                        busy = true;
                        if (config.logInput())
                        {
                                Log.i(LOG_TAG, "hover '" + label + "' at " + pt[0] + "," + pt[1] + " for " + dwellMs + "ms");
                        }
                        try
                        {
                                timeline.execute(() ->
                                {
                                        try
                                        {
                                                approachTrail(gen, pt[0], pt[1]);
                                                if (aborted(gen))
                                                {
                                                        return;
                                                }
                                                emitMove(gen, pt[0], pt[1]);
                                                double ax = 1.5d + random.nextDouble() * 3.5d;
                                                double ay = 1.5d + random.nextDouble() * 3.0d;
                                                double ratio = 1.0d + random.nextInt(3) + (random.nextDouble() - 0.5d) * 0.2d;
                                                double phx = random.nextDouble() * 2.0d * Math.PI;
                                                double phy = random.nextDouble() * 2.0d * Math.PI;
                                                double omega = 2.0d * Math.PI / (700.0d + random.nextDouble() * 900.0d);
                                                long t0 = System.currentTimeMillis();
                                                while (System.currentTimeMillis() - t0 < dwellMs && !aborted(gen))
                                                {
                                                        if (!sleep(70 + random.nextInt(90)))
                                                        {
                                                                return;
                                                        }
                                                        double t = System.currentTimeMillis() - t0;
                                                        emitMove(gen, pt[0] + (int) Math.round(ax * Math.sin(omega * t + phx)),
                                                                pt[1] + (int) Math.round(ay * Math.cos(ratio * omega * t + phy)));
                                                }
                                        }
                                        finally
                                        {
                                                busy = false;
                                        }
                                });
                        }
                        catch (RuntimeException rejected)
                        {
                                busy = false;
                                return false;
                        }
                        return true;
                }

                /**
                 * Hold times for a run of keys: a cosine around the middle of the configured
                 * range, its own random phase and detuned frequency, plus a little noise.
                 */
                private final class KeyRhythm
                {
                        final double phase = random.nextDouble() * 2.0d * Math.PI;
                        final double freq = 0.37d + random.nextDouble() * 0.71d;
                        int last;

                        int next(int i)
                        {
                                int min = Math.max(25, config.keyHoldMinMs());
                                int max = Math.max(min, config.keyHoldMaxMs());
                                double w = (Math.cos(phase + i * freq) + 1.0d) / 2.0d;
                                last = (int) Math.round(min + (max - min) * w) + random.nextInt(18) - 9;
                                last = Math.max(20, last);
                                return last;
                        }
                }

                /** Character key, with a real Shift press around capitals and shifted symbols. */
                private boolean strokeChar(int gen, char c, KeyRhythm hold, int index)
                {
                        boolean shifted = Character.isUpperCase(c) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
                        int vk = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c);
                        if (vk == java.awt.event.KeyEvent.VK_UNDEFINED)
                        {
                                vk = c;
                        }
                        if (!shifted)
                        {
                                return strokeKey(gen, vk, c, hold, index);
                        }
                        final int shiftMask = java.awt.event.InputEvent.SHIFT_DOWN_MASK;
                        if (!post(() -> KeyDispatch.INSTANCE.keyDown(java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.CHAR_UNDEFINED, shiftMask)))
                        {
                                return false;
                        }
                        boolean ok = sleep(35 + random.nextInt(55)) && !aborted(gen);
                        if (ok)
                        {
                                final int v = vk;
                                ok = post(() -> KeyDispatch.INSTANCE.keyDown(v, c, shiftMask));
                                int held = hold.next(index);
                                ok = ok && sleep(held);
                                post(() -> KeyDispatch.INSTANCE.keyUp(v, c, shiftMask));
                                ok = ok && sleep(20 + random.nextInt(45));
                        }
                        // Shift always comes back up, even when stopping.
                        post(() -> KeyDispatch.INSTANCE.keyUp(java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0));
                        return ok && !aborted(gen);
                }

                /** Key-down, hold for the rhythm's next hold time, key-up. Always releases. */
                private boolean strokeKey(int gen, int vk, char c, KeyRhythm hold, int index)
                {
                        if (!post(() -> KeyDispatch.INSTANCE.keyDown(vk, c, 0)))
                        {
                                return false;
                        }
                        boolean ok = sleep(hold.next(index));
                        post(() -> KeyDispatch.INSTANCE.keyUp(vk, c, 0));
                        return ok && !aborted(gen);
                }

                private boolean post(Runnable r)
                {
                        Handler h = main;
                        if (h == null)
                        {
                                return false;
                        }
                        h.post(() ->
                        {
                                try
                                {
                                        r.run();
                                }
                                catch (RuntimeException e)
                                {
                                        Log.w(LOG_TAG, "key dispatch failed", e);
                                }
                        });
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

                private void runInteract(int gen, Target victim, Persona p, boolean menu, String wanted, int[] pt, int[] ground, int[] dismiss, int pad)
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

                                if (!misclick)
                                {
                                        approachTrail(gen, fingerDown[0], fingerDown[1]);
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                }

                                if (menu)
                                {
                                        menuWalk(gen, fingerDown, p, wanted, victim);
                                        return;
                                }

                                // Finger down: two parking moves, a few ms apart.
                                emitMove(gen, fingerDown[0], fingerDown[1]);
                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, fingerDown[0], fingerDown[1]);

                                if (!misclick)
                                {
                                        // Look before tapping: make sure a tap here really does the
                                        // wanted action on this target, re-aiming if it would not.
                                        int[] aimed = aimUntilHover(gen, victim, wanted, fingerDown);
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        if (aimed == null)
                                        {
                                                // No plain tap lines up (something on top of it):
                                                // long-press and pick the action from the menu.
                                                menuWalk(gen, fingerDown, p, wanted, victim);
                                                return;
                                        }
                                        fingerDown = aimed;
                                }

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

                /** Plain tap on a fixed point, with the same finger shape as every other tap. */
                private void runTap(int gen, Persona p, int[] pt, int[] dismiss, int pad)
                {
                        try
                        {
                                if (dismiss != null && !dismissMenu(gen, dismiss))
                                {
                                        return;
                                }
                                if (!sleep(pad) || aborted(gen))
                                {
                                        return;
                                }
                                // Curved (harmonic) approach before every gesture.
                                {
                                        approachTrail(gen, pt[0], pt[1]);
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                }
                                emitMove(gen, pt[0], pt[1]);
                                if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                {
                                        return;
                                }
                                emitMove(gen, pt[0], pt[1]);
                                if (!sleep(personaTapDur(p)) || aborted(gen))
                                {
                                        return;
                                }
                                emitBurst(gen, pt[0], pt[1], MouseEvent.BUTTON1);
                                lastX = pt[0];
                                lastY = pt[1];
                        }
                        finally
                        {
                                busy = false;
                        }
                }

                /**
                 * One shift-drop pass. The gap between taps rides the same wave the order was
                 * built from (fast through the troughs, a touch slower over the crests) with a
                 * little jitter on top, so the rhythm is quick but never metronomic.
                 */
                private void runShiftDrops(int gen, Persona p, List<DropTarget> targets, int[] dismiss)
                {
                        boolean pressedShift = false;
                        try
                        {
                                if (dismiss != null && !dismissMenu(gen, dismiss))
                                {
                                        return;
                                }
                                pressedShift = setShift(true);
                                if (!sleep(70 + random.nextInt(90)) || aborted(gen))
                                {
                                        return;
                                }

                                final double base = 55d + random.nextInt(30);
                                final double swing = 25d + random.nextInt(45);
                                for (DropTarget target : targets)
                                {
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        // Slot to slot along a short harmonic curve, then the two parking moves.
                                        approachTrail(gen, target.x, target.y);
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        emitMove(gen, target.x, target.y);
                                        if (!sleep(4 + random.nextInt(10)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        emitMove(gen, target.x, target.y);
                                        if (!sleep(8 + random.nextInt(18)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        // Look before tapping: the tap here must be "Drop" on this very slot.
                                        int[] aim = aimSlot(gen, target, "Drop");
                                        if (aborted(gen))
                                        {
                                                return;
                                        }
                                        if (aim == null)
                                        {
                                                // A plain tap here would not drop it (shift-drop off,
                                                // slot moved): long-press and pick "Drop" instead.
                                                if (config.logInput())
                                                {
                                                        Log.i(LOG_TAG, "shift-drop: slot " + target.slot + " not lined up, dropping from the menu");
                                                }
                                                menuWalk(gen, new int[] {lastX, lastY}, p, "Drop", null);
                                                if (!sleep(60 + random.nextInt(90)))
                                                {
                                                        return;
                                                }
                                                continue;
                                        }
                                        emitBurst(gen, aim[0], aim[1], MouseEvent.BUTTON1);
                                        if (config.logInput())
                                        {
                                                Log.i(LOG_TAG, "shift-drop: slot " + target.slot + " " + target.label);
                                        }

                                        int gap = (int) Math.round(base + swing * (target.wave + 1d) / 2d) + random.nextInt(25);
                                        if (random.nextInt(100) < 4)
                                        {
                                                gap += 80 + random.nextInt(160); // the odd thumb re-grip
                                        }
                                        if (!sleep(gap))
                                        {
                                                return;
                                        }
                                }
                                // Let the last tap land before shift comes up.
                                sleep(60 + random.nextInt(80));
                        }
                        finally
                        {
                                if (pressedShift)
                                {
                                        setShift(false);
                                }
                                busy = false;
                        }
                }

                /**
                 * Engages or releases shift through the same sticky modifier the on-screen
                 * Shift chip uses (it keeps the key held with a heartbeat). Only touches it when
                 * the state actually needs to change.
                 *
                 * @return true when this call changed the shift state
                 */
                private boolean setShift(boolean down)
                {
                        CountDownLatch done = new CountDownLatch(1);
                        boolean[] changed = {false};
                        new Handler(Looper.getMainLooper()).post(() ->
                        {
                                try
                                {
                                        boolean active = Boolean.TRUE.equals(ModifierState.INSTANCE.getShiftActive().getValue());
                                        if (active != down)
                                        {
                                                ModifierState.INSTANCE.toggleShift();
                                                changed[0] = true;
                                        }
                                }
                                catch (RuntimeException e)
                                {
                                        Log.w(LOG_TAG, "could not change shift state", e);
                                }
                                finally
                                {
                                        done.countDown();
                                }
                        });
                        try
                        {
                                done.await(500, TimeUnit.MILLISECONDS);
                        }
                        catch (InterruptedException e)
                        {
                                Thread.currentThread().interrupt();
                        }
                        return changed[0];
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

                                        // Curved (harmonic) approach before every gesture.
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
                                        requestMenuScan("Drop", null);
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
                private void menuWalk(int gen, int[] down, Persona p, String wanted, Object target)
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
                        requestMenuScan(wanted, target);
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
                /**
                 * Every gesture starts with the finger travelling to its point along a
                 * harmonic (trigonometric) curve. Before the first gesture the finger rests
                 * at a random spot in the lower middle of the screen, like a thumb at rest.
                 */
                private void approachTrail(int gen, int toX, int toY)
                {
                        if (lastX < 0 || lastY < 0)
                        {
                                int w = canvasWidth();
                                int h = canvasHeight();
                                if (w <= 0 || h <= 0)
                                {
                                        return;
                                }
                                lastX = (int) (w * (0.35d + random.nextDouble() * 0.3d));
                                lastY = (int) (h * (0.6d + random.nextDouble() * 0.25d));
                        }
                        double dist = Math.hypot(toX - lastX, toY - lastY);
                        if (dist < 6)
                        {
                                return;
                        }
                        // Fitts' law: movement time grows with log2(1 + distance / target width),
                        // so a flick between neighbouring slots is quick and a glide across the
                        // screen takes longer without growing linearly. Random spread on top.
                        double fitts = Math.log(1.0d + dist / 18.0d) / Math.log(2.0d);
                        int duration = (int) Math.min(480.0d, 25.0d + 55.0d * fitts) + random.nextInt(50);
                        harmonicPath(gen, lastX, lastY, toX, toY, duration);
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
                        // Settle: longer glides carry a little momentum past the target and
                        // come back on a damped cosine (A * e^(-k t) * cos(w t)) with a faint
                        // sideways sine, landing dead on the point like a finger braking.
                        if (dist > 55 && random.nextInt(100) < 60)
                        {
                                double over = Math.min(9.0d, 1.5d + dist * (0.008d + random.nextDouble() * 0.014d));
                                double side = (random.nextDouble() - 0.5d) * over * 0.6d;
                                double k = 3.2d + random.nextDouble() * 1.8d;
                                double w = Math.PI * (1.3d + random.nextDouble() * 0.9d);
                                int n = 3 + random.nextInt(3);
                                for (int i = 1; i <= n; i++)
                                {
                                        if (!sleep(10 + random.nextInt(9)) || aborted(gen))
                                        {
                                                return;
                                        }
                                        double t = i / (double) n;
                                        double decay = Math.exp(-k * t);
                                        double along = over * decay * Math.cos(w * t);
                                        double across = side * decay * Math.sin(w * t);
                                        emitMove(gen, (int) Math.round(x1 + ux * along + px * across),
                                                (int) Math.round(y1 + uy * along + py * across));
                                }
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
                private int[] refreshPoint(int gen, Target victim, Placement placement, int[] fallback)
                {
                        final CountDownLatch done = new CountDownLatch(1);
                        final int[][] out = {null};
                        final boolean[] ok = {true};
                        clientThread.invokeLater(() ->
                        {
                                try
                                {
                                        if (client.getGameState() != GameState.LOGGED_IN
                                                || !victim.isLive())
                                        {
                                                ok[0] = false;
                                        }
                                        else
                                        {
                                                int[] fresh = tapPoint(victim, placement);
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


                /**
                 * Lines the finger up before committing a tap: with the finger parked on the
                 * aim point, read what a tap there would actually do (the client's top menu
                 * entry). If it is not the wanted action on this exact target, slide to a
                 * fresh, tighter point on the target and look again — up to three looks.
                 *
                 * @return the point to tap, or null when no plain tap lines up (use the menu)
                 */
                private int[] aimUntilHover(int gen, Target victim, String wanted, int[] start)
                {
                        int[] aim = start;
                        for (int attempt = 0; attempt < 3; attempt++)
                        {
                                if (attempt > 0)
                                {
                                        int[] next = refreshPoint(gen, victim, Placement.TIGHT_BOX, null);
                                        if (aborted(gen))
                                        {
                                                return null;
                                        }
                                        if (next == null)
                                        {
                                                return aim; // client busy or target gone: keep the old aim
                                        }
                                        emitMove(gen, next[0], next[1]);
                                        if (!sleep(2 + random.nextInt(7)) || aborted(gen))
                                        {
                                                return null;
                                        }
                                        emitMove(gen, next[0], next[1]);
                                        aim = next;
                                }
                                Boolean hit = hoverHits(gen, victim, wanted);
                                if (aborted(gen))
                                {
                                        return null;
                                }
                                if (hit == null || hit)
                                {
                                        return aim;
                                }
                                if (config.logInput())
                                {
                                        Log.i(LOG_TAG, "aim check: tap would not '" + wanted + "' the target, re-aiming");
                                }
                        }
                        return null;
                }

                /**
                 * Slot aim check: reads the top (left-click) menu entry under the parked finger
                 * and accepts it only when it is {@code wanted} on exactly this slot (param0 is
                 * the slot index for inventory entries) and item. On a miss the finger re-aims
                 * to a fresh point nearer the slot's centre, drawn on a small sine/cosine
                 * spiral, up to twice. Returns the point to tap, or null when it never lined up.
                 * Unreadable (slow frame) counts as fine, like the NPC aim check.
                 */
                private int[] aimSlot(int gen, DropTarget target, String wanted)
                {
                        int[] aim = {target.x, target.y};
                        for (int attempt = 0; attempt < 3; attempt++)
                        {
                                if (attempt > 0)
                                {
                                        int[] next = slotCentrePoint(gen, target, attempt);
                                        if (next == null || aborted(gen))
                                        {
                                                return aborted(gen) ? null : aim;
                                        }
                                        harmonicPath(gen, aim[0], aim[1], next[0], next[1], 40 + random.nextInt(40));
                                        emitMove(gen, next[0], next[1]);
                                        aim = next;
                                }
                                Boolean ok = callClient(gen, () ->
                                {
                                        if (client.isMenuOpen())
                                        {
                                                return null;
                                        }
                                        MenuEntry[] entries = client.getMenuEntries();
                                        if (entries == null || entries.length == 0)
                                        {
                                                return Boolean.FALSE;
                                        }
                                        MenuEntry top = entries[entries.length - 1];
                                        // Only the slot matters: a wrong slot is re-aimed, anything else is tapped.
                                        boolean itemOk = top.getItemId() <= 0 || top.getItemId() == target.itemId;
                                        return top.getParam0() == target.slot && itemOk;
                                }, 0);
                                if (aborted(gen))
                                {
                                        return null;
                                }
                                if (ok == null || ok)
                                {
                                        return aim;
                                }
                                if (config.logInput())
                                {
                                        Log.i(LOG_TAG, "slot aim: tap would not '" + wanted + "' slot " + target.slot + ", re-aiming");
                                }
                        }
                        return null;
                }

                /** A point near the slot's centre: radius shrinks with each attempt, angle random. */
                private int[] slotCentrePoint(int gen, DropTarget target, int attempt)
                {
                        if (target.componentId < 0)
                        {
                                return null;
                        }
                        return callClient(gen, () ->
                        {
                                Widget inv = client.getWidget(target.componentId);
                                Widget[] kids = inv == null ? null : inv.getChildren();
                                if (kids == null || target.slot >= kids.length || kids[target.slot] == null)
                                {
                                        return null;
                                }
                                Widget c = kids[target.slot];
                                Point loc = c.getCanvasLocation();
                                if (loc == null || c.getWidth() <= 0 || c.getHeight() <= 0)
                                {
                                        return null;
                                }
                                double r = (attempt == 1 ? 0.18d : 0.08d) * Math.min(c.getWidth(), c.getHeight());
                                double th = random.nextDouble() * 2.0d * Math.PI;
                                int rx = loc.getX() + c.getWidth() / 2 + (int) Math.round(r * Math.cos(th));
                                int ry = loc.getY() + c.getHeight() / 2 + (int) Math.round(r * Math.sin(th));
                                return toStretched(rx, ry);
                        }, 0);
                }

                /**
                 * Runs {@code read} on the client thread after an optional settle pause and
                 * waits up to 250ms for the answer. Null on timeout or abort.
                 */
                private <T> T callClient(int gen, java.util.function.Supplier<T> read, int settleMs)
                {
                        if (settleMs > 0 && (!sleep(settleMs) || aborted(gen)))
                        {
                                return null;
                        }
                        final CountDownLatch done = new CountDownLatch(1);
                        final java.util.concurrent.atomic.AtomicReference<T> out = new java.util.concurrent.atomic.AtomicReference<>();
                        clientThread.invokeLater(() ->
                        {
                                try
                                {
                                        out.set(read.get());
                                }
                                catch (RuntimeException e)
                                {
                                        Log.w(LOG_TAG, "client read failed", e);
                                }
                                finally
                                {
                                        done.countDown();
                                }
                        });
                        try
                        {
                                if (!done.await(80, TimeUnit.MILLISECONDS))
                                {
                                        return null;
                                }
                        }
                        catch (InterruptedException e)
                        {
                                Thread.currentThread().interrupt();
                                return null;
                        }
                        return out.get();
                }

                /**
                 * True when the client's left-click (top) menu entry under the parked finger is
                 * {@code wanted} on {@code victim}; null when it could not be read in time. A
                 * negative is re-read once a frame later so a slow frame is not taken as a miss.
                 */
                private Boolean hoverHits(int gen, Target victim, String wanted)
                {
                        for (int look = 0; look < 2; look++)
                        {
                                if (!sleep((look == 0 ? 28 : 22) + random.nextInt(20)) || aborted(gen))
                                {
                                        return null;
                                }
                                final CountDownLatch done = new CountDownLatch(1);
                                final Boolean[] out = {null};
                                clientThread.invokeLater(() ->
                                {
                                        try
                                        {
                                                if (client.isMenuOpen())
                                                {
                                                        return;
                                                }
                                                MenuEntry[] entries = client.getMenuEntries();
                                                if (entries == null || entries.length == 0)
                                                {
                                                        out[0] = Boolean.FALSE;
                                                        return;
                                                }
                                                MenuEntry top = entries[entries.length - 1];
                                                String option = top.getOption() == null ? "" : Text.removeTags(top.getOption());
                                                out[0] = victim.matches(top) && option.equalsIgnoreCase(wanted);
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
                                                return null;
                                        }
                                }
                                catch (InterruptedException e)
                                {
                                        Thread.currentThread().interrupt();
                                        return null;
                                }
                                if (out[0] == null || out[0])
                                {
                                        return out[0];
                                }
                        }
                        return Boolean.FALSE;
                }

                private void requestMenuScan(String option, Object target)
                {
                        menuScanTarget = target;
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

        private void rollNextTurn()
        {
                int min = Math.max(config.cameraEveryMin(), 1);
                int max = Math.max(config.cameraEveryMax(), min);
                nextTurnProgress = progressSinceTurn + min + (max > min ? random.nextInt(max - min + 1) : 0);
        }

        /** Called on every counted catch: turns the camera once the catch threshold is reached. */
        public void onProgress()
        {
                progressSinceTurn++;
                if (!config.smartCamera() || yawAnim != null)
                {
                        return;
                }
                if (progressSinceTurn < nextTurnProgress)
                {
                        return;
                }
                progressSinceTurn = 0;
                rollNextTurn();
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
                        Target npc = focusTarget == null ? null : focusTarget.get();
                        if (npc != null && npc.localLocation() != null)
                        {
                                return npc.localLocation();
                        }
                }
                if (roll < 85)
                {
                        WorldPoint centre = focusCentre == null ? null : focusCentre.get();
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
                WorldPoint centre = focusCentre == null ? null : focusCentre.get();
                if (centre == null)
                {
                        return local.getLocalLocation();
                }
                int r = Math.max(1, focusRadius == null ? 5 : focusRadius.getAsInt());
                WorldPoint wp = new WorldPoint(
                        centre.getX() + signedJitter(r),
                        centre.getY() + signedJitter(r),
                        centre.getPlane());
                LocalPoint lp = wv == null ? null : LocalPoint.fromWorld(wv, wp);
                return lp != null ? lp : local.getLocalLocation();
        }

        /** Slow humanized turn to bring an off-screen spot back into view (rate-limited). */
        public void rescueCamera(Target target)
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
                LocalPoint lp = target.localLocation();
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
                if (rescue)
                {
                        // Bring it into view, not dead centre: settle a little to one side of it,
                        // the way a thumb drags until the target is "on screen enough".
                        int offMin = (int) Math.round(Math.max(0, config.rescueOffsetMinDeg()) * JAU14_PER_RADIAN * Math.PI / 180.0d);
                        int offMax = Math.max(offMin, (int) Math.round(Math.max(0, config.rescueOffsetMaxDeg()) * JAU14_PER_RADIAN * Math.PI / 180.0d));
                        delta += (random.nextBoolean() ? 1 : -1) * (offMin + random.nextInt(offMax - offMin + 1));
                }
                else if (random.nextInt(100) < 20)
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
                int outcome = rescue ? (random.nextInt(100) < 25 ? random.nextInt(30) : 60 + random.nextInt(40)) : random.nextInt(100);
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

        // ---------------------------------------------------------------- menu scan

        /** True when a menu row belongs to the scan target (or there is no specific target). */
        private static boolean menuEntryIsTarget(MenuEntry entry, Object target)
        {
                return target == null || (target instanceof Target && ((Target) target).matches(entry));
        }

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
                        if (option != null && wanted != null && wanted.equalsIgnoreCase(Text.removeTags(option))
                                && menuEntryIsTarget(entries[i], menuScanTarget))
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
                        rowY = menuY + headerH + rowH * rowFromTop + rowH / 2;
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

        // ---------------------------------------------------------------- small helpers

        private int signedJitter(int px)
        {
                return px <= 0 ? 0 : random.nextInt(2 * px + 1) - px;
        }

}
