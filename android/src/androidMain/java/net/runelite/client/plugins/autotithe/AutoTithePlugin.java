package net.runelite.client.plugins.autotithe;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.humanizer.GameHelpers;
import net.runelite.client.plugins.humanizer.HumanInput;
import net.runelite.client.plugins.humanizer.HumanizerConfig;
import net.runelite.client.plugins.humanizer.SessionLog;
import net.runelite.client.plugins.humanizer.Target;
import net.runelite.client.plugins.humanizer.Targets;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

/**
 * Tithe Farm, following Microbot's TitheFarmingScript (states, lane layouts, patch
 * priorities, seed table, sack, barrel, Gricoller's can) with every input through the
 * Humanizer's simulated touch.
 *
 * <p>Faster than the original: everything is driven by game events and checked every
 * client frame (20ms) instead of fixed sleeps. Planting chains straight into watering
 * the moment the seedling appears. Accuracy: every patch tap is aim-checked (the
 * left-click entry under the finger must be the right option on the right patch),
 * the Humanizer's deliberate misclicks are off here, and if a tap still lands on the
 * wrong thing (seen in MenuOptionClicked) the correct tap is sent on the very next
 * frame, inside the same game tick.</p>
 */
@PluginDescriptor(
        name = "AutoTithe",
        description = "Tithe Farm minigame with simulated touch input",
        tags = {"tithe", "farm", "farming", "minigame", "mobile", "humanizer", "script"},
        enabledByDefault = false
)
public class AutoTithePlugin extends Plugin
{
        private static final String LOG_TAG = "AutoTithe";
        private static final int FARM_DOOR = ObjectID.HOSIDIUS_TITHE_FARM_DOOR;
        private static final int MINIMAP_WALK_DISTANCE = 8;
        private static final int WATERING_CANS = 8;
        private static final Pattern PERCENT = Pattern.compile("(\\d+(\\.\\d+)?)%");

        enum State
        {
                STARTING, RECHARGING_RUN, TAKE_SEEDS, REFILL_WATER, PLANTING, HARVEST, LEAVE
        }

        /** The three tithe seeds (Microbot's TitheFarmMaterial), highest the player can use. */
        enum Seed
        {
                GOLOVANOVA("Golovanova seed", 34, '1', ItemID.HOSIDIUS_TITHE_SEED_A, ItemID.HOSIDIUS_TITHE_FRUIT_A,
                        ObjectID.HOSIDIUS_TITHE_A_1_DRY, ObjectID.HOSIDIUS_TITHE_A_2_DRY, ObjectID.HOSIDIUS_TITHE_A_3_DRY, ObjectID.HOSIDIUS_TITHE_A_4),
                BOLOGANO("Bologano seed", 54, '2', ItemID.HOSIDIUS_TITHE_SEED_B, ItemID.HOSIDIUS_TITHE_FRUIT_B,
                        ObjectID.HOSIDIUS_TITHE_B_1_DRY, ObjectID.HOSIDIUS_TITHE_B_2_DRY, ObjectID.HOSIDIUS_TITHE_B_3_DRY, ObjectID.HOSIDIUS_TITHE_B_4),
                LOGAVANO("Logavano seed", 74, '3', ItemID.HOSIDIUS_TITHE_SEED_C, ItemID.HOSIDIUS_TITHE_FRUIT_C,
                        ObjectID.HOSIDIUS_TITHE_C_1_DRY, ObjectID.HOSIDIUS_TITHE_C_2_DRY, ObjectID.HOSIDIUS_TITHE_C_3_DRY, ObjectID.HOSIDIUS_TITHE_C_4);

                final String name;
                final int level;
                final char option;
                final int seedId;
                final int fruitId;
                final int dry1;
                final int dry2;
                final int dry3;
                final int ripe;

                Seed(String name, int level, char option, int seedId, int fruitId, int dry1, int dry2, int dry3, int ripe)
                {
                        this.name = name;
                        this.level = level;
                        this.option = option;
                        this.seedId = seedId;
                        this.fruitId = fruitId;
                        this.dry1 = dry1;
                        this.dry2 = dry2;
                        this.dry3 = dry3;
                        this.ripe = ripe;
                }

                static Seed forLevel(int farming)
                {
                        return farming >= 74 ? LOGAVANO : farming >= 54 ? BOLOGANO : GOLOVANOVA;
                }
        }

        /** One patch of the lane (Microbot's TitheFarmPlant). */
        static final class Patch
        {
                final int regionX;
                final int regionY;
                final int order;
                GameObject obj;

                Patch(int regionX, int regionY, int order)
                {
                        this.regionX = regionX;
                        this.regionY = regionY;
                        this.order = order;
                }

                int id()
                {
                        return obj == null ? -1 : obj.getId();
                }
        }

        @Inject
        private Client client;

        @Inject
        private ClientThread clientThread;

        @Inject
        private AutoTitheConfig config;

        @Inject
        private ConfigManager configManager;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private AutoTitheOverlay overlay;

        @Inject
        private ItemManager itemManager;

        private HumanInput input;
        private final Random random = new Random();

        private State state = State.STARTING;
        private final List<Patch> patches = new ArrayList<>();
        private boolean allPlanted;
        private int gricollerCharges = -1;
        private int initialFruit = -1;
        private int fruits;
        private long startedAt;
        private String status = "off";

        // ---- the step in progress (replaces Microbot's sleepUntil calls) ----
        private BooleanSupplier waitFor;
        private long waitUntilMs;
        private Runnable afterWait;
        private Runnable onTimeout;

        // ---- same-tick correction ----
        private Patch expectPatch;
        private String expectOption;
        private Runnable correction;
        private int corrections;

        @Provides
        AutoTitheConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(AutoTitheConfig.class);
        }

        @Override
        protected void startUp()
        {
                SessionLog.INSTANCE.statusSource("AutoTithe", this::getStatus);
                input = new HumanInput(client, clientThread, configManager.getConfig(HumanizerConfig.class), LOG_TAG);
                input.setTrainedSkill(Skill.FARMING);
                applySpeed();
                input.start();
                overlayManager.add(overlay);
                restart();
        }

        @Override
        protected void shutDown()
        {
                SessionLog.INSTANCE.statusSource("AutoTithe", null);
                input.stop();
                overlayManager.remove(overlay);
                patches.clear();
        }

        private void applySpeed()
        {
                input.setSpeedBoost(config.speed() / 100.0d / 1.5d);
        }

        private void restart()
        {
                state = State.STARTING;
                patches.clear();
                allPlanted = false;
                gricollerCharges = -1;
                initialFruit = -1;
                fruits = 0;
                startedAt = System.currentTimeMillis();
                clearWait();
                expectPatch = null;
                correction = null;
        }

        // ---------------------------------------------------------------- events

        @Subscribe
        public void onConfigChanged(ConfigChanged e)
        {
                if (!AutoTitheConfig.GROUP.equals(e.getGroup()))
                {
                        return;
                }
                if ("speed".equals(e.getKey()))
                {
                        applySpeed();
                }
                if ("autoTithe".equals(e.getKey()) || "lanes".equals(e.getKey()))
                {
                        clientThread.invoke(() ->
                        {
                                input.reset();
                                restart();
                        });
                }
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged e)
        {
                if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
                {
                        input.reset();
                        restart();
                }
        }

        /** Patches change object on every stage: keep each patch's object current. */
        @Subscribe
        public void onGameObjectSpawned(GameObjectSpawned e)
        {
                GameObject go = e.getGameObject();
                WorldPoint at = go.getWorldLocation();
                for (Patch p : patches)
                {
                        if (at.equals(patchPoint(p)))
                        {
                                p.obj = go;
                        }
                }
        }

        @Subscribe
        public void onChatMessage(ChatMessage e)
        {
                String msg = Text.removeTags(e.getMessage());
                if (msg.contains("%"))
                {
                        Matcher m = PERCENT.matcher(msg);
                        if (m.find())
                        {
                                gricollerCharges = (int) Float.parseFloat(m.group(1));
                        }
                }
                else if (msg.equalsIgnoreCase("Gricoller's can is already full."))
                {
                        gricollerCharges = 100;
                }
                else if (msg.equalsIgnoreCase("You don't have a suitable vessel of water for watering the plant."))
                {
                        state = State.REFILL_WATER;
                        clearWait();
                }
        }

        /**
         * Every tap on a patch is checked against what was meant. A tap that went to the
         * wrong place (another patch, the ground, a player) is answered with the correct
         * tap on the next frame, within the same game tick.
         */
        @Subscribe
        public void onMenuOptionClicked(MenuOptionClicked e)
        {
                if (expectPatch == null || expectOption == null || !config.autoTithe())
                {
                        return;
                }
                Patch p = expectPatch;
                String want = expectOption;
                expectPatch = null;
                expectOption = null;
                String option = Text.removeTags(e.getMenuOption());
                boolean rightPatch = p.obj != null && e.getId() == p.obj.getId()
                        && p.obj.getSceneMinLocation() != null
                        && e.getParam0() == p.obj.getSceneMinLocation().getX() && e.getParam1() == p.obj.getSceneMinLocation().getY();
                if (option.equalsIgnoreCase(want) && rightPatch)
                {
                        corrections = 0;
                        return;
                }
                if (corrections >= 3)
                {
                        input.log("tithe: tap went wrong 3 times in a row (" + option + "); letting the loop retry");
                        corrections = 0;
                        return;
                }
                corrections++;
                input.log("tithe: tap hit '" + option + " " + Text.removeTags(e.getMenuTarget()) + "' instead of '" + want
                        + "': correcting now");
                final String act = want;
                final Seed seed = Seed.forLevel(client.getRealSkillLevel(Skill.FARMING));
                clearWait();
                correction = () -> redo(seed, p, act);
        }

        @Subscribe
        public void onClientTick(ClientTick e)
        {
                input.onClientTick();
                if (!config.autoTithe())
                {
                        return;
                }
                // Same-tick correction first.
                if (correction != null && !input.isBusy())
                {
                        Runnable c = correction;
                        correction = null;
                        c.run();
                        return;
                }
                // Finish the step in progress the frame its condition comes true.
                if (waitFor != null)
                {
                        boolean done;
                        try
                        {
                                done = waitFor.getAsBoolean();
                        }
                        catch (RuntimeException ex)
                        {
                                done = true;
                        }
                        if (done)
                        {
                                Runnable next = afterWait;
                                clearWait();
                                if (next != null && !input.isBusy())
                                {
                                        next.run();
                                }
                                else if (next != null)
                                {
                                        waitFor = () -> !input.isBusy();
                                        waitUntilMs = System.currentTimeMillis() + 1500;
                                        afterWait = next;
                                }
                        }
                        else if (System.currentTimeMillis() > waitUntilMs)
                        {
                                Runnable t = onTimeout;
                                clearWait();
                                if (t != null)
                                {
                                        t.run();
                                }
                        }
                        return;
                }
                if (!input.isBusy())
                {
                        loop();
                }
        }

        @Subscribe
        public void onGameTick(GameTick e)
        {
                input.onGameTick(config.autoTithe() && waitFor != null);
                updateFruitCount();
                if (!config.autoTithe())
                {
                        status = "off";
                }
        }

        private void clearWait()
        {
                waitFor = null;
                afterWait = null;
                onTimeout = null;
        }

        private void waitThen(BooleanSupplier cond, long timeoutMs, Runnable then, Runnable timeout)
        {
                waitFor = cond;
                waitUntilMs = System.currentTimeMillis() + timeoutMs;
                afterWait = then;
                onTimeout = timeout;
        }

        // ---------------------------------------------------------------- main loop (Microbot's run())

        private void loop()
        {
                Player local = client.getLocalPlayer();
                if (local == null || client.getGameState() != GameState.LOGGED_IN)
                {
                        return;
                }
                Seed seed = Seed.forLevel(client.getRealSkillLevel(Skill.FARMING));
                if (client.getRealSkillLevel(Skill.FARMING) < 34)
                {
                        status = "need Farming 34";
                        return;
                }
                if (initialFruit < 0)
                {
                        initialFruit = count(seed.fruitId);
                }

                // First-visit dialogues.
                if (continueVisible())
                {
                        status = "dialogue";
                        input.pressKey(KeyEvent.VK_SPACE, "continue");
                        waitThen(() -> !continueVisible(), 1500, null, null);
                        return;
                }
                if (selectOptionVisible() && state != State.TAKE_SEEDS)
                {
                        status = "dialogue option";
                        input.pressKey(KeyEvent.VK_3, "option 3");
                        waitThen(() -> !selectOptionVisible(), 2000, null, null);
                        return;
                }

                boolean inside = inMinigame();
                if (!inside && count(seed.seedId) == 0)
                {
                        state = State.TAKE_SEEDS;
                }
                if (inside && count(seed.seedId) == 0 && state != State.TAKE_SEEDS)
                {
                        state = State.LEAVE;
                }
                if (count(seed.fruitId) >= config.storeAt())
                {
                        status = "storing fruit";
                        depositSack(seed);
                        return;
                }

                switch (state)
                {
                        case LEAVE:
                                status = "leaving";
                                if (!depositSack(seed))
                                {
                                        leave();
                                }
                                break;
                        case TAKE_SEEDS:
                                if (inside)
                                {
                                        state = State.STARTING;
                                        break;
                                }
                                takeSeeds(seed);
                                break;
                        case STARTING:
                                status = "starting";
                                if (!GameHelpers.openInventoryTab(client, input))
                                {
                                        return;
                                }
                                if (!runOn() && client.getEnergy() > 1000 && toggleRun())
                                {
                                        return;
                                }
                                initPatches();
                                if (!validateInventory())
                                {
                                        return;
                                }
                                if (dropFertiliser())
                                {
                                        return;
                                }
                                validateRunEnergy();
                                if (state != State.RECHARGING_RUN)
                                {
                                        state = State.REFILL_WATER;
                                }
                                break;
                        case RECHARGING_RUN:
                                status = "waiting for run energy";
                                validateRunEnergy();
                                break;
                        case REFILL_WATER:
                                status = "filling watering cans";
                                refillWater();
                                break;
                        case PLANTING:
                        case HARVEST:
                                coreLoop(seed, local);
                                break;
                        default:
                                break;
                }
        }

        // ---------------------------------------------------------------- planting / watering / harvest

        /** Microbot's coreLoop: empty patches first, then stage 1, then stage 2; harvest when nothing else. */
        private void coreLoop(Seed seed, Player local)
        {
                if (isMoving(local))
                {
                        return;
                }
                Comparator<Patch> byOrder = Comparator.comparingInt(p -> p.order);
                Patch patch = null;
                if (state != State.HARVEST)
                {
                        if (!allPlanted)
                        {
                                patch = patches.stream().sorted(byOrder).filter(p -> isEmpty(p) || p.id() == seed.dry1).findFirst().orElse(null);
                        }
                        if (patch == null)
                        {
                                patch = patches.stream().sorted(byOrder).filter(p -> p.id() == seed.dry2).findFirst()
                                        .orElseGet(() -> patches.stream().sorted(byOrder).filter(p -> p.id() == seed.dry3).findFirst().orElse(null));
                        }
                }
                if (state == State.HARVEST && patches.stream().allMatch(this::isEmpty))
                {
                        state = State.STARTING;
                        allPlanted = false;
                        return;
                }
                if (patches.stream().noneMatch(this::isEmpty))
                {
                        allPlanted = true;
                }
                if (patch == null && patches.stream().anyMatch(p -> p.id() == seed.ripe))
                {
                        state = State.HARVEST;
                        patch = patches.stream().sorted(byOrder).filter(p -> p.id() == seed.ripe).findFirst().orElse(null);
                }
                if (patch == null || patch.obj == null)
                {
                        status = state == State.HARVEST ? "waiting for crops" : "waiting for growth";
                        return;
                }

                WorldPoint me = local.getWorldLocation();
                WorldPoint at = patch.obj.getWorldLocation();
                if (Math.max(Math.abs(at.getX() - me.getX()), Math.abs(at.getY() - me.getY())) > MINIMAP_WALK_DISTANCE)
                {
                        int[] mm = input.minimapPoint(at.getX(), at.getY());
                        if (mm != null && input.tap(mm, "walk to patch"))
                        {
                                status = "walking to patch " + patch.order;
                                waitThen(() -> isMoving(client.getLocalPlayer()), 1200, null, null);
                        }
                        return;
                }

                final Patch target = patch;
                if (isEmpty(target) && !allPlanted)
                {
                        plant(seed, target);
                        return;
                }
                if (isDry(seed, target))
                {
                        water(seed, target);
                        return;
                }
                if (target.id() == seed.ripe)
                {
                        status = "harvesting patch " + target.order;
                        if (tapPatch(target, "Harvest"))
                        {
                                waitThen(() -> isEmpty(target), 4000, null, null);
                        }
                }
        }

        /** Use seed on the empty patch, then water the seedling the frame it appears. */
        private void plant(Seed seed, Patch target)
        {
                status = "planting patch " + target.order;
                if (!selectItem(seed.seedId))
                {
                        return;
                }
                waitThen(() -> client.isWidgetSelected(), 900, () ->
                {
                        if (tapPatch(target, "Use"))
                        {
                                // Microbot saves a tick by watering straight after planting.
                                waitThen(() -> target.id() == seed.dry1, 3000, () -> water(seed, target), null);
                        }
                }, null);
        }

        private void water(Seed seed, Patch target)
        {
                status = "watering patch " + target.order;
                if (tapPatch(target, "Water"))
                {
                        waitThen(() -> isWatered(target), 3000, null, null);
                }
        }

        /** The correct action again, with its follow-up wait (seed re-selected for "Use"). */
        private void redo(Seed seed, Patch p, String option)
        {
                if ("Use".equalsIgnoreCase(option))
                {
                        plant(seed, p);
                }
                else if ("Water".equalsIgnoreCase(option))
                {
                        water(seed, p);
                }
                else if (tapPatch(p, option))
                {
                        waitThen(() -> isEmpty(p), 4000, null, null);
                }
        }

        /** Aim-checked tap on a patch with the expected option, recorded for the same-tick check. */
        private boolean tapPatch(Patch p, String option)
        {
                if (p.obj == null)
                {
                        return false;
                }
                final GameObject obj = p.obj;
                Target t = Targets.object(obj, () -> p.obj == obj);
                boolean leftClick = true;
                expectPatch = p;
                expectOption = option;
                boolean sent = input.interact(t, option, leftClick, true, false);
                if (!sent)
                {
                        expectPatch = null;
                        expectOption = null;
                }
                return sent;
        }

        // ---------------------------------------------------------------- patches

        private WorldPoint patchPoint(Patch p)
        {
                Player local = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (local == null || wv == null)
                {
                        return null;
                }
                return WorldPoint.fromRegion(local.getWorldLocation().getRegionID(), p.regionX, p.regionY, wv.getPlane());
        }

        private void initPatches()
        {
                TitheLanes lanes = config.lanes();
                if (lanes == TitheLanes.RANDOM)
                {
                        lanes = TitheLanes.values()[random.nextInt(TitheLanes.values().length - 1)];
                }
                patches.clear();
                for (int[] p : lanes.patches)
                {
                        patches.add(new Patch(p[0], p[1], p[2]));
                }
                // Find each patch's current object.
                WorldView wv = client.getTopLevelWorldView();
                Scene scene = wv == null ? null : wv.getScene();
                if (scene == null)
                {
                        return;
                }
                Tile[][] tiles = scene.getTiles()[wv.getPlane()];
                for (Patch p : patches)
                {
                        WorldPoint wp = patchPoint(p);
                        LocalPoint lp = wp == null ? null : LocalPoint.fromWorld(wv, wp);
                        if (lp == null)
                        {
                                continue;
                        }
                        for (int dx = -1; dx <= 1 && p.obj == null; dx++)
                        {
                                for (int dy = -1; dy <= 1 && p.obj == null; dy++)
                                {
                                        int x = lp.getSceneX() + dx;
                                        int y = lp.getSceneY() + dy;
                                        if (x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length || tiles[x][y] == null)
                                        {
                                                continue;
                                        }
                                        GameObject[] gos = tiles[x][y].getGameObjects();
                                        if (gos == null)
                                        {
                                                continue;
                                        }
                                        for (GameObject g : gos)
                                        {
                                                if (g != null && wp.equals(g.getWorldLocation()))
                                                {
                                                        p.obj = g;
                                                        break;
                                                }
                                        }
                                }
                        }
                }
                long missing = patches.stream().filter(p -> p.obj == null).count();
                input.log("tithe: lanes " + lanes + ", " + patches.size() + " patches" + (missing > 0 ? " (" + missing + " not found yet)" : ""));
        }

        private boolean isEmpty(Patch p)
        {
                return p.id() == ObjectID.HOSIDIUS_TITHE_EMPTY;
        }

        private static boolean isDry(Seed s, Patch p)
        {
                int id = p.id();
                return id == s.dry1 || id == s.dry2 || id == s.dry3;
        }

        private static boolean isWatered(Patch p)
        {
                switch (p.id())
                {
                        case ObjectID.HOSIDIUS_TITHE_A_1_WET:
                        case ObjectID.HOSIDIUS_TITHE_A_2_WET:
                        case ObjectID.HOSIDIUS_TITHE_A_3_WET:
                        case ObjectID.HOSIDIUS_TITHE_B_1_WET:
                        case ObjectID.HOSIDIUS_TITHE_B_2_WET:
                        case ObjectID.HOSIDIUS_TITHE_B_3_WET:
                        case ObjectID.HOSIDIUS_TITHE_C_1_WET:
                        case ObjectID.HOSIDIUS_TITHE_C_2_WET:
                        case ObjectID.HOSIDIUS_TITHE_C_3_WET:
                                return true;
                        default:
                                return false;
                }
        }

        // ---------------------------------------------------------------- seeds, door, sack, water

        private void takeSeeds(Seed seed)
        {
                status = "taking seeds";
                // Seed choice menu open: press the seed's number key.
                if (selectOptionVisible() && optionsContain(seed.name))
                {
                        input.pressKey(KeyEvent.VK_0 + (seed.option - '0'), "seed " + seed.option);
                        waitThen(this::amountInputVisible, 2500, null, null);
                        return;
                }
                // Amount prompt: type a random amount and Enter.
                if (amountInputVisible())
                {
                        input.type(String.valueOf(1000 + random.nextInt(9001)), true);
                        waitThen(() -> count(seed.seedId) > 0, 3000, null, null);
                        return;
                }
                if (count(seed.seedId) > 0)
                {
                        enterFarm();
                        return;
                }
                GameObject table = findGameObject(ObjectID.TITHE_PLANT_SEED_TABLE);
                if (table != null && input.interact(Targets.object(table, () -> true), firstAction(table.getId(), "Search"), true, true, false))
                {
                        waitThen(() -> selectOptionVisible() && optionsContain(seed.name), 6000, null, null);
                }
        }

        private void enterFarm()
        {
                status = "entering the farm";
                WallObject door = findWallObject(FARM_DOOR);
                if (door != null && input.interact(Targets.object(door, () -> true), firstAction(FARM_DOOR, "Open"), true, true, false))
                {
                        waitThen(this::inMinigame, 8000, () -> state = State.STARTING, null);
                }
        }

        private void leave()
        {
                WallObject door = findWallObject(FARM_DOOR);
                if (door != null && input.interact(Targets.object(door, () -> true), firstAction(FARM_DOOR, "Open"), true, true, false))
                {
                        waitThen(() -> !inMinigame(), 8000, () -> state = State.TAKE_SEEDS, null);
                }
        }

        /** Microbot's depositSack: put held fruit in the sack for XP. */
        private boolean depositSack(Seed seed)
        {
                if (count(seed.fruitId) == 0)
                {
                        return false;
                }
                GameObject sack = findGameObject(ObjectID.TITHE_SACK_OF_FRUIT_EMPTY);
                if (sack == null)
                {
                        sack = findGameObject(ObjectID.TITHE_SACK_OF_FRUIT);
                }
                if (sack != null && input.interact(Targets.object(sack, () -> true), firstAction(sack.getId(), "Deposit"), true, true, false))
                {
                        waitThen(() -> count(seed.fruitId) == 0, 8000, null, null);
                }
                return true;
        }

        private void refillWater()
        {
                if (count(ItemID.ZEAH_WATERINGCAN) > 0)
                {
                        if (gricollerCharges < 0)
                        {
                                List<HumanInput.DropTarget> can = slots(ItemID.ZEAH_WATERINGCAN);
                                if (!can.isEmpty() && input.longPressPick(new int[] {can.get(0).x, can.get(0).y}, "Check"))
                                {
                                        waitThen(() -> gricollerCharges >= 0, 3000, null, () -> gricollerCharges = 100);
                                }
                                return;
                        }
                        if (gricollerCharges < config.gricollerRefill())
                        {
                                useItemOnBarrel(ItemID.ZEAH_WATERINGCAN, () -> gricollerCharges = 100);
                                return;
                        }
                        state = State.PLANTING;
                        return;
                }
                int partial = wateringCanToFill();
                if (partial > 0)
                {
                        useItemOnBarrel(partial, null);
                        return;
                }
                state = State.PLANTING;
        }

        private void useItemOnBarrel(int itemId, Runnable done)
        {
                GameObject barrel = findGameObject(ObjectID.WATER_BARREL1);
                if (barrel == null)
                {
                        status = "no water barrel in view";
                        return;
                }
                Player local = client.getLocalPlayer();
                WorldPoint at = barrel.getWorldLocation();
                WorldPoint me = local.getWorldLocation();
                if (Math.max(Math.abs(at.getX() - me.getX()), Math.abs(at.getY() - me.getY())) > MINIMAP_WALK_DISTANCE)
                {
                        int[] mm = input.minimapPoint(at.getX(), at.getY());
                        if (mm != null && input.tap(mm, "walk to barrel"))
                        {
                                waitThen(() -> isMoving(client.getLocalPlayer()), 1200, null, null);
                        }
                        return;
                }
                if (!selectItem(itemId))
                {
                        return;
                }
                final GameObject b = barrel;
                waitThen(() -> client.isWidgetSelected(), 900, () ->
                {
                        if (input.interact(Targets.object(b, () -> true), "Use", true, true, false))
                        {
                                if (itemId == ItemID.ZEAH_WATERINGCAN)
                                {
                                        waitThen(() -> client.getLocalPlayer().getAnimation() != -1, 6000, done, null);
                                }
                                else
                                {
                                        waitThen(() -> count(ItemID.WATERING_CAN_8) >= WATERING_CANS, 60000, done, null);
                                }
                        }
                }, null);
        }

        private int wateringCanToFill()
        {
                int[] ids = {ItemID.WATERING_CAN_7, ItemID.WATERING_CAN_6, ItemID.WATERING_CAN_5, ItemID.WATERING_CAN_4,
                        ItemID.WATERING_CAN_3, ItemID.WATERING_CAN_2, ItemID.WATERING_CAN_1, ItemID.WATERING_CAN_0};
                for (int id : ids)
                {
                        if (count(id) > 0)
                        {
                                return id;
                        }
                }
                return -1;
        }

        // ---------------------------------------------------------------- inventory, run

        private boolean validateInventory()
        {
                if (count(ItemID.DIBBER) == 0 || count(ItemID.SPADE) == 0)
                {
                        stopWith("You need a seed dibber and a spade in your inventory");
                        return false;
                }
                int cans = 0;
                for (int id = ItemID.WATERING_CAN_1; id <= ItemID.WATERING_CAN_8; id++)
                {
                        cans += count(id);
                }
                cans += count(ItemID.WATERING_CAN_0);
                if (cans < WATERING_CANS && count(ItemID.ZEAH_WATERINGCAN) == 0)
                {
                        stopWith("You need 8 watering cans or a Gricoller's can");
                        return false;
                }
                return true;
        }

        private void stopWith(String why)
        {
                status = why;
                input.log("tithe: " + why + " — stopping");
                configManager.setConfiguration(AutoTitheConfig.GROUP, "autoTithe", false);
        }

        private boolean dropFertiliser()
        {
                List<HumanInput.DropTarget> f = slots(ItemID.HOSIDIUS_TITHE_FERTILISER);
                if (f.isEmpty())
                {
                        return false;
                }
                input.longPressPick(new int[] {f.get(0).x, f.get(0).y}, "Drop");
                waitThen(() -> count(ItemID.HOSIDIUS_TITHE_FERTILISER) == 0, 2000, null, null);
                return true;
        }

        /** Microbot's validateRunEnergy: below 40% with every patch empty, drink a restore or wait. */
        private void validateRunEnergy()
        {
                boolean allEmpty = patches.isEmpty() || patches.stream().allMatch(this::isEmpty);
                if (client.getEnergy() < 4000 && allEmpty && state != State.RECHARGING_RUN)
                {
                        state = State.RECHARGING_RUN;
                        input.log("tithe: recharging run energy");
                        List<HumanInput.DropTarget> pots = new ArrayList<>(input.slotTargets(ComponentID.INVENTORY_CONTAINER, id ->
                        {
                                ItemComposition c = itemManager.getItemComposition(id);
                                String n = c == null || c.getName() == null ? "" : c.getName().toLowerCase();
                                return n.contains("stamina") || n.contains("energy potion") || n.contains("super energy");
                        }));
                        if (!pots.isEmpty())
                        {
                                input.tap(new int[] {pots.get(0).x, pots.get(0).y}, "drink");
                        }
                }
                else if (state == State.RECHARGING_RUN && client.getEnergy() >= 4000)
                {
                        state = State.STARTING;
                }
        }

        private boolean runOn()
        {
                return client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
        }

        private boolean toggleRun()
        {
                Widget orb = client.getWidget(InterfaceID.Orbs.RUNBUTTON);
                int[] p = orb == null || orb.isHidden() ? null : input.widgetPoint(orb);
                return p != null && input.tap(p, "run on");
        }

        /** Selects an inventory item for "Use" (left tap when Use is its default, else menu). */
        private boolean selectItem(int itemId)
        {
                if (!GameHelpers.openInventoryTab(client, input))
                {
                        return false;
                }
                if (client.isWidgetSelected())
                {
                        return true;
                }
                List<HumanInput.DropTarget> s = slots(itemId);
                if (s.isEmpty())
                {
                        return false;
                }
                int[] pt = {s.get(0).x, s.get(0).y};
                ItemComposition c = itemManager.getItemComposition(itemId);
                String[] actions = c == null ? null : c.getInventoryActions();
                boolean useIsLeft = actions == null || actions.length == 0 || actions[0] == null;
                return useIsLeft ? input.tap(pt, "use " + itemId) : input.longPressPick(pt, "Use");
        }

        private List<HumanInput.DropTarget> slots(int itemId)
        {
                return input.slotTargets(ComponentID.INVENTORY_CONTAINER, id -> id == itemId);
        }

        private int count(int itemId)
        {
                ItemContainer inv = client.getItemContainer(InventoryID.INV);
                if (inv == null)
                {
                        return 0;
                }
                int n = 0;
                for (Item i : inv.getItems())
                {
                        if (i != null && i.getId() == itemId)
                        {
                                n += Math.max(1, i.getQuantity());
                        }
                }
                return n;
        }

        private void updateFruitCount()
        {
                Seed seed = Seed.forLevel(client.getRealSkillLevel(Skill.FARMING));
                if (initialFruit >= 0)
                {
                        fruits = Math.max(fruits, count(seed.fruitId) - initialFruit);
                }
        }

        // ---------------------------------------------------------------- widgets / scene helpers

        private boolean inMinigame()
        {
                Widget w = client.getWidget(InterfaceID.HosidiusStripFarming.PANEL);
                return w != null && !w.isHidden();
        }

        private boolean continueVisible()
        {
                int[] groups = {InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE, InterfaceID.Messagebox.UNIVERSE,
                        InterfaceID.Objectbox.UNIVERSE, InterfaceID.ObjectboxDouble.UNIVERSE};
                for (int g : groups)
                {
                        Widget w = client.getWidget(g);
                        if (w != null && !w.isHidden())
                        {
                                return true;
                        }
                }
                return false;
        }

        private boolean selectOptionVisible()
        {
                Widget w = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
                return w != null && !w.isHidden();
        }

        private boolean optionsContain(String text)
        {
                Widget w = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
                Widget[] kids = w == null ? null : w.getDynamicChildren();
                if (kids == null)
                {
                        return false;
                }
                for (Widget k : kids)
                {
                        if (k != null && k.getText() != null && Text.removeTags(k.getText()).toLowerCase().contains(text.toLowerCase()))
                        {
                                return true;
                        }
                }
                return false;
        }

        private boolean amountInputVisible()
        {
                Widget w = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
                return w != null && !w.isHidden();
        }

        private GameObject findGameObject(int id)
        {
                Player local = client.getLocalPlayer();
                WorldView wv = client.getTopLevelWorldView();
                if (local == null || wv == null)
                {
                        return null;
                }
                GameObject best = null;
                int bestD = Integer.MAX_VALUE;
                for (GameObject g : GameHelpers.gameObjectsAround(client, local.getLocalLocation(), 30))
                {
                        if (g.getId() == id)
                        {
                                int d = g.getWorldLocation().distanceTo(local.getWorldLocation());
                                if (d < bestD)
                                {
                                        bestD = d;
                                        best = g;
                                }
                        }
                }
                return best;
        }

        private WallObject findWallObject(int id)
        {
                WorldView wv = client.getTopLevelWorldView();
                Player local = client.getLocalPlayer();
                if (wv == null || local == null || wv.getScene() == null)
                {
                        return null;
                }
                Tile[][] tiles = wv.getScene().getTiles()[wv.getPlane()];
                LocalPoint lp = local.getLocalLocation();
                WallObject best = null;
                int bestD = Integer.MAX_VALUE;
                for (int x = Math.max(0, lp.getSceneX() - 30); x < Math.min(tiles.length, lp.getSceneX() + 31); x++)
                {
                        for (int y = Math.max(0, lp.getSceneY() - 30); y < Math.min(tiles[x].length, lp.getSceneY() + 31); y++)
                        {
                                Tile t = tiles[x][y];
                                WallObject w = t == null ? null : t.getWallObject();
                                if (w != null && w.getId() == id)
                                {
                                        int d = Math.abs(x - lp.getSceneX()) + Math.abs(y - lp.getSceneY());
                                        if (d < bestD)
                                        {
                                                bestD = d;
                                                best = w;
                                        }
                                }
                        }
                }
                return best;
        }

        private String firstAction(int objectId, String fallback)
        {
                net.runelite.api.ObjectComposition c = GameHelpers.composition(client, objectId);
                if (c != null && c.getActions() != null)
                {
                        for (String a : c.getActions())
                        {
                                if (a != null && !a.isEmpty())
                                {
                                        return a;
                                }
                        }
                }
                return fallback;
        }

        private static boolean isMoving(Player p)
        {
                return p != null && p.getPoseAnimation() != p.getIdlePoseAnimation();
        }

        // ---------------------------------------------------------------- overlay

        String getStatus()
        {
                return config.autoTithe() ? state + ": " + status : "off";
        }

        List<Patch> getPatches()
        {
                return patches;
        }

        int getFruits()
        {
                return fruits;
        }

        double getFruitsPerHour()
        {
                long ms = System.currentTimeMillis() - startedAt;
                return ms < 60_000 ? 0 : fruits * 3_600_000.0d / ms;
        }

        boolean isRipe(Patch p)
        {
                return p.id() == Seed.forLevel(client.getRealSkillLevel(Skill.FARMING)).ripe;
        }

        boolean isDryPatch(Patch p)
        {
                return isDry(Seed.forLevel(client.getRealSkillLevel(Skill.FARMING)), p);
        }

        boolean isWateredPatch(Patch p)
        {
                return isWatered(p);
        }

        boolean isEmptyPatch(Patch p)
        {
                return isEmpty(p);
        }

        AutoTitheConfig getConfig()
        {
                return config;
        }
}
