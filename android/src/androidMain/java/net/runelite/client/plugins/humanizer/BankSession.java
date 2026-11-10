package net.runelite.client.plugins.humanizer;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.VarClientInt;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.gamedata.BankLocation;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.util.Text;

/**
 * One bank trip for any script: walk to the nearest bank the player can actually reach
 * (by real path, from anywhere — upstairs banks and dungeons included), open it with a
 * real tap on a booth, chest or banker, deposit everything that is not kept, then withdraw
 * what the script needs (food, keys, ...). Everything is done the way a player would on a
 * phone: tap or long-press + menu row, the bank search box typed one key at a time, and
 * Withdraw-X amounts typed in.
 *
 * <p>Call {@link #begin} once, then {@link #tick} every game tick until it returns
 * {@link #DONE} or {@link #FAILED} (see {@link #failure()}). The bank is closed (Esc or
 * the X button) before DONE.</p>
 */
public final class BankSession
{
        public static final int DONE = 0;
        public static final int BUSY = 1;
        public static final int FAILED = 2;

        private static final int MAX_WITHDRAW_TRIES = 4;

        /** Chatbox input types (VarClientInt.INPUT_TYPE): 11 = bank search; the amount prompt is another non-zero type. */
        private static final int INPUT_SEARCH = 11;

        /** One thing to have in the inventory when the trip ends. */
        public static final class Need
        {
                public final GameHelpers.ItemList.Entry entry;
                public final GameHelpers.ItemList list;
                public final int quantity;
                /** Fail the trip when the bank has none (e.g. food). */
                public final boolean required;

                public Need(GameHelpers.ItemList list, GameHelpers.ItemList.Entry entry, int quantity, boolean required)
                {
                        this.list = list;
                        this.entry = entry;
                        this.quantity = quantity;
                        this.required = required;
                }
        }

        private enum Step
        {
                WALK,
                OPEN,
                DEPOSIT,
                WITHDRAW,
                CLOSE
        }

        private final Client client;
        private final ItemManager items;
        private final HumanInput input;
        private final WebWalker walker;

        private Step step;
        private List<BankLocation> candidates = Collections.emptyList();
        private BankLocation bank;
        private Target bankTarget;
        private Object bankRaw;
        private IntPredicate keep = id -> false;
        private List<Need> needs = Collections.emptyList();
        private int stepTicks;
        private int arrivedTicks;
        private int wait;
        private String failure;
        private final Map<String, Long> unreachable = new HashMap<>();

        // withdraw sub-state
        private int needIndex;
        private int phase;          // 0 locate, 1 search opening, 2 search typed, 3 amount prompt, 4 waiting for items
        private int phaseTicks;
        private int countBefore;
        private int tries;
        private boolean searchActive;
        private boolean searchedForCurrent;
        private String pendingAmount;
        private int noteFixTries;
        private final List<String> skipped = new ArrayList<>();

        public BankSession(Client client, ItemManager items, HumanInput input, WebWalker walker)
        {
                this.client = client;
                this.items = items;
                this.input = input;
                this.walker = walker;
        }

        /**
         * Plans a trip to the nearest usable bank within {@code maxDistance} tiles (straight
         * line; the walk itself follows real paths).
         *
         * @param keep  items never deposited
         * @param needs items to have in the inventory when done (missing ones are withdrawn)
         * @return false when no bank qualifies ({@link #failure()} says why)
         */
        public boolean begin(Player local, int maxDistance, IntPredicate keep, List<Need> needs)
        {
                return begin(local, maxDistance, keep, needs, null);
        }

        /**
         * Like {@link #begin(Player, int, IntPredicate, List)}, but goes to the bank the user
         * picked ({@code chosen}) instead of the closest one. The pick is honoured at any
         * distance; null means "closest usable bank within maxDistance".
         */
        public boolean begin(Player local, int maxDistance, IntPredicate keep, List<Need> needs, BankLocation chosen)
        {
                if (chosen != null)
                {
                        stop();
                        this.keep = keep == null
                        ? id -> net.runelite.client.plugins.gamedata.Transport.isRequiredItem(id)
                        : id -> keep.test(id) || net.runelite.client.plugins.gamedata.Transport.isRequiredItem(id);
                        this.needs = needs == null ? Collections.emptyList() : new ArrayList<>(needs);
                        WorldPoint here = local.getWorldLocation();
                        if (here == null)
                        {
                                failure = "Player position unknown";
                                return false;
                        }
                        String why = net.runelite.client.plugins.gamedata.BankPick.whyNot(client, chosen);
                        if (why != null)
                        {
                                failure = why;
                                return false;
                        }
                        candidates = new ArrayList<>(Collections.singletonList(chosen));
                        step = Step.WALK;
                        WorldPoint wp = chosen.getWorldPoint();
                        int d = Math.min(cheb(here, wp), cheb(new WorldPoint(here.getX(), here.getY() % 6400, here.getPlane()), wp));
                        walker.start(Collections.singletonList(wp), 3, d * 3 + 200);
                        input.log("bank trip: chosen bank " + chosen.getDisplayName() + " (" + d + " tiles)");
                        return true;
                }
                stop();
                this.keep = keep == null
                        ? id -> net.runelite.client.plugins.gamedata.Transport.isRequiredItem(id)
                        : id -> keep.test(id) || net.runelite.client.plugins.gamedata.Transport.isRequiredItem(id);
                this.needs = needs == null ? Collections.emptyList() : new ArrayList<>(needs);
                WorldPoint here = local.getWorldLocation();
                if (here == null)
                {
                        failure = "Player position unknown";
                        return false;
                }
                List<BankLocation> list = new ArrayList<>();
                List<WorldPoint> points = new ArrayList<>();
                for (BankLocation b : BankLocation.values())
                {
                        if (b.getNote() != null)
                        {
                                continue;
                        }
                        WorldPoint wp = b.getWorldPoint();
                        // Dungeons sit 6400 tiles north of their surface area: compare against both.
                        int d = Math.min(cheb(here, wp), cheb(new WorldPoint(here.getX(), here.getY() % 6400, here.getPlane()), wp));
                        if (d > maxDistance || !b.isAccessible(client))
                        {
                                continue;
                        }
                        list.add(b);
                        points.add(wp);
                }
                int cost = maxDistance * 3 + 120;
                if (list.isEmpty())
                {
                        // Nothing inside the limit: take the few nearest usable banks anywhere
                        // rather than stand still with a full inventory.
                        List<BankLocation> all = new ArrayList<>();
                        for (BankLocation b : BankLocation.values())
                        {
                                if (b.getNote() == null && b.isAccessible(client))
                                {
                                        all.add(b);
                                }
                        }
                        final WorldPoint surface = new WorldPoint(here.getX(), here.getY() % 6400, here.getPlane());
                        all.sort((a, b) -> Integer.compare(
                                Math.min(cheb(here, a.getWorldPoint()), cheb(surface, a.getWorldPoint())),
                                Math.min(cheb(here, b.getWorldPoint()), cheb(surface, b.getWorldPoint()))));
                        for (int i = 0; i < Math.min(4, all.size()); i++)
                        {
                                list.add(all.get(i));
                                points.add(all.get(i).getWorldPoint());
                                int d = Math.min(cheb(here, all.get(i).getWorldPoint()), cheb(surface, all.get(i).getWorldPoint()));
                                cost = Math.max(cost, d * 3 + 200);
                        }
                        if (list.isEmpty())
                        {
                                failure = "No usable bank";
                                return false;
                        }
                        input.log("no bank within " + maxDistance + " tiles; using the nearest ones further away");
                }
                candidates = list;
                step = Step.WALK;
                walker.start(points, 3, cost);
                input.log("bank trip: " + list.size() + " candidate banks");
                return true;
        }

        public void stop()
        {
                walker.stop();
                step = null;
                bank = null;
                bankTarget = null;
                bankRaw = null;
                stepTicks = 0;
                arrivedTicks = 0;
                wait = 0;
                failure = null;
                needIndex = 0;
                phase = 0;
                phaseTicks = 0;
                tries = 0;
                searchActive = false;
                searchedForCurrent = false;
                pendingAmount = null;
                noteFixTries = 0;
                skipped.clear();
        }

        public boolean isActive()
        {
                return step != null;
        }

        public String failure()
        {
                return failure;
        }

        /** Items the bank did not have (non-required ones are skipped, not fatal). */
        public List<String> skipped()
        {
                return skipped;
        }

        public String status()
        {
                if (step == null)
                {
                        return "-";
                }
                switch (step)
                {
                        case WALK:
                                return "To " + (bank != null ? bank.getDisplayName() : "bank");
                        case OPEN:
                                return "Opening bank";
                        case DEPOSIT:
                                return "Depositing";
                        case CLOSE:
                                return "Closing bank";
                        default:
                                return "Withdrawing";
                }
        }

        /** "I can't reach that!": the booth / banker or a door on the way is unreachable. */
        public void onCantReach()
        {
                if (walker.onCantReach())
                {
                        return;
                }
                if (step == Step.OPEN && bankRaw != null)
                {
                        unreachable.put(key(bankRaw), System.currentTimeMillis() + 180_000L);
                        bankTarget = null;
                        bankRaw = null;
                        step = Step.WALK;
                        stepTicks = 0;
                }
        }

        private static int cheb(WorldPoint a, WorldPoint b)
        {
                return a.getPlane() != b.getPlane() ? Integer.MAX_VALUE / 2
                        : Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
        }

        private static String key(Object raw)
        {
                if (raw instanceof TileObject)
                {
                        TileObject o = (TileObject) raw;
                        WorldPoint wp = o.getWorldLocation();
                        return "o" + o.getId() + "@" + wp;
                }
                if (raw instanceof NPC)
                {
                        return "n" + ((NPC) raw).getIndex();
                }
                return String.valueOf(raw);
        }

        private boolean isUnreachable(Object raw)
        {
                Long until = unreachable.get(key(raw));
                return until != null && until > System.currentTimeMillis();
        }

        public boolean bankOpen()
        {
                Widget w = client.getWidget(ComponentID.BANK_CONTAINER);
                return w != null && !w.isHidden();
        }

        /** One tick. Client thread. */
        public int tick(Player local, LocalReach reach)
        {
                if (step == null)
                {
                        return failure == null ? DONE : FAILED;
                }
                stepTicks++;
                if (wait > 0)
                {
                        wait--;
                        return BUSY;
                }
                if (input.isBusy())
                {
                        return BUSY;
                }
                WorldPoint here = local.getWorldLocation();
                if (here == null)
                {
                        return BUSY;
                }
                boolean moving = client.getLocalDestinationLocation() != null;

                switch (step)
                {
                        case WALK:
                        {
                                if (bankOpen())
                                {
                                        walker.stop();
                                        enter(Step.DEPOSIT);
                                        return BUSY;
                                }
                                if (bank == null && walker.targetIndex() >= 0 && walker.targetIndex() < candidates.size())
                                {
                                        bank = candidates.get(walker.targetIndex());
                                        input.log("walking to " + bank.getDisplayName());
                                }
                                Object booth = findBankTarget(local, reach, 12);
                                boolean close = booth != null && (walker.nearEnd(10) || distance(booth, here) <= 6 || !walker.isActive());
                                if (close)
                                {
                                        Target target = bankTargetFor(booth);
                                        String action = bankAction(booth);
                                        bankRaw = booth;
                                        bankTarget = target;
                                        if (input.interact(target, action, defaultAction(booth, action), false, false))
                                        {
                                                enter(Step.OPEN);
                                                wait = 1;
                                        }
                                        return BUSY;
                                }
                                int w = walker.tick(local);
                                if (w == WebWalker.FAILED)
                                {
                                        return fail("No walkable route to a bank");
                                }
                                if (w == WebWalker.ARRIVED && booth == null && !moving && ++arrivedTicks > 12)
                                {
                                        return fail("No reachable booth, chest or banker at " + (bank == null ? "the bank" : bank.getDisplayName()));
                                }
                                return BUSY;
                        }
                        case OPEN:
                        {
                                if (bankOpen())
                                {
                                        walker.stop();
                                        enter(Step.DEPOSIT);
                                        wait = 1 + input.random().nextInt(2);
                                        return BUSY;
                                }
                                if (moving && stepTicks < 30)
                                {
                                        return BUSY;
                                }
                                if (stepTicks >= 5)
                                {
                                        enter(Step.WALK);
                                }
                                return BUSY;
                        }
                        case DEPOSIT:
                        {
                                if (!bankOpen())
                                {
                                        enter(Step.WALK);
                                        return BUSY;
                                }
                                if (!hasDepositables())
                                {
                                        enter(Step.WITHDRAW);
                                        needIndex = 0;
                                        resetNeed();
                                        return BUSY;
                                }
                                if (stepTicks > 40)
                                {
                                        return fail("Could not deposit the inventory");
                                }
                                if (!inventoryHasKept())
                                {
                                        int[] button = depositInventoryPoint();
                                        if (button != null)
                                        {
                                                input.tap(button, "Deposit inventory");
                                                wait = 2 + input.random().nextInt(2);
                                                return BUSY;
                                        }
                                }
                                List<HumanInput.DropTarget> slots = input.slotTargets(ComponentID.BANK_INVENTORY_ITEM_CONTAINER,
                                        id -> !keep.test(id));
                                if (!slots.isEmpty())
                                {
                                        HumanInput.DropTarget t = slots.get(input.random().nextInt(Math.min(3, slots.size())));
                                        input.longPressPick(new int[]{t.x, t.y}, "Deposit-All");
                                        wait = 1 + input.random().nextInt(2);
                                }
                                return BUSY;
                        }
                        case CLOSE:
                                return closeStep();
                        case WITHDRAW:
                        default:
                                return tickWithdraw();
                }
        }

        private void enter(Step s)
        {
                step = s;
                stepTicks = 0;
        }

        private int fail(String why)
        {
                failure = why;
                input.log("bank trip failed: " + why);
                walker.stop();
                step = null;
                return FAILED;
        }

        private void resetNeed()
        {
                phase = 0;
                phaseTicks = 0;
                tries = 0;
                searchedForCurrent = false;
                pendingAmount = null;
        }

        // ---------------------------------------------------------------- withdrawing

        private int tickWithdraw()
        {
                if (!bankOpen())
                {
                        enter(Step.WALK);
                        return BUSY;
                }
                if (stepTicks > 300)
                {
                        return fail("Withdrawing took too long");
                }
                // Withdraw as items, never notes (noted food cannot be eaten). Same check Microbot
                // uses — the BANK_WITHDRAWNOTES varbit — and the same toggle button
                // (Bankmain.NOTE), pressed with our own tap; the varbit confirms the switch.
                if (client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1 && phase == 0 && needIndex < needs.size())
                {
                        Widget toggle = client.getWidget(InterfaceID.Bankmain.NOTE);
                        int[] p = input.widgetPoint(toggle);
                        if (p == null || ++noteFixTries > 3)
                        {
                                return fail("Could not switch the bank from 'Note' to 'Item'");
                        }
                        input.tap(p, "withdraw as item");
                        wait = 2 + input.random().nextInt(2);
                        return BUSY;
                }

                while (needIndex < needs.size())
                {
                        Need need = needs.get(needIndex);
                        int have = need.list.count(client, items, need.entry);
                        int missing = need.quantity - have;
                        if (missing <= 0 || (GameHelpers.freeSlots(client) == 0 && !stackable(need)))
                        {
                                needIndex++;
                                resetNeed();
                                continue;
                        }
                        return withdrawStep(need, missing, have);
                }
                return finish();
        }

        private int finish()
        {
                if (searchActive)
                {
                        // Leave the bank tidy: close the search box.
                        Widget search = client.getWidget(InterfaceID.Bankmain.SEARCH);
                        int[] p = input.widgetPoint(search);
                        searchActive = false;
                        if (p != null)
                        {
                                input.tap(p, "close search");
                                wait = 1;
                                return BUSY;
                        }
                }
                // Close the bank before walking back: on mobile the open bank covers the
                // minimap, so walking taps would land on the bank window instead.
                enter(Step.CLOSE);
                return BUSY;
        }

        /**
         * Closes the bank like Microbot's Rs2Bank.closeBank: Escape when the game's
         * "Esc closes the current interface" keybind is on (a real key press), otherwise
         * the X button (child 11 of the bank frame). Alternates if one does not work.
         */
        private int closeStep()
        {
                if (!bankOpen())
                {
                        step = null;
                        return DONE;
                }
                if (stepTicks > 12)
                {
                        // Could not close it: carry on anyway (the walk will try again).
                        step = null;
                        return DONE;
                }
                boolean escOn = client.getVarbitValue(net.runelite.api.gameval.VarbitID.KEYBINDING_ESC_TO_CLOSE) == 1;
                boolean useEsc = escOn && (stepTicks <= 4 ? input.random().nextInt(100) < 70 : stepTicks % 2 == 0);
                if (useEsc)
                {
                        input.pressKey(java.awt.event.KeyEvent.VK_ESCAPE, "close bank (Esc)");
                }
                else
                {
                        Widget frame = client.getWidget(InterfaceID.Bankmain.FRAME);
                        Widget close = frame == null ? null : frame.getChild(11);
                        if (close == null || close.isHidden())
                        {
                                close = GameHelpers.findWidgetWithAction(client, "Close");
                        }
                        int[] p = close == null ? null : input.widgetPoint(close);
                        if (p != null)
                        {
                                input.tap(p, "close bank");
                        }
                        else if (escOn)
                        {
                                input.pressKey(java.awt.event.KeyEvent.VK_ESCAPE, "close bank (Esc)");
                        }
                }
                wait = 1 + input.random().nextInt(2);
                return BUSY;
        }

        private int withdrawStep(Need need, int missing, int have)
        {
                phaseTicks++;
                switch (phase)
                {
                        case 0:
                        {
                                Widget slot = findBankItem(need);
                                if (slot != null && slotVisible(slot))
                                {
                                        int free = GameHelpers.freeSlots(client);
                                        String option;
                                        if (!stackable(need) && missing >= free)
                                        {
                                                option = "Withdraw-All";
                                        }
                                        else if (missing == 1 || missing == 5 || missing == 10)
                                        {
                                                option = "Withdraw-" + missing;
                                        }
                                        else
                                        {
                                                option = "Withdraw-X";
                                                pendingAmount = String.valueOf(missing);
                                        }
                                        int[] p = input.widgetPoint(slot);
                                        if (p == null)
                                        {
                                                return BUSY;
                                        }
                                        countBefore = have;
                                        input.longPressPick(p, option);
                                        input.log("withdraw " + need.entry.label() + ": " + option + (pendingAmount != null ? " " + pendingAmount : ""));
                                        phase = pendingAmount != null ? 3 : 4;
                                        phaseTicks = 0;
                                        wait = 1;
                                        return BUSY;
                                }
                                if (searchedForCurrent)
                                {
                                        // Searched and still nothing: the bank does not have it.
                                        if (need.required)
                                        {
                                                return fail("No " + need.entry.searchText(items) + " left in the bank");
                                        }
                                        skipped.add(need.entry.searchText(items));
                                        needIndex++;
                                        resetNeed();
                                        return BUSY;
                                }
                                // Not on screen: search for it, the way a player would.
                                Widget search = client.getWidget(InterfaceID.Bankmain.SEARCH);
                                int[] p = input.widgetPoint(search);
                                if (p == null)
                                {
                                        searchedForCurrent = true;
                                        return BUSY;
                                }
                                input.tap(p, searchActive ? "clear search" : "search");
                                if (searchActive)
                                {
                                        // Tapping it again closes the old search; open a fresh one next.
                                        searchActive = false;
                                        wait = 1 + input.random().nextInt(2);
                                        return BUSY;
                                }
                                phase = 1;
                                phaseTicks = 0;
                                wait = 1;
                                return BUSY;
                        }
                        case 1:
                        {
                                if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) == INPUT_SEARCH)
                                {
                                        input.type(need.entry.searchText(items), false);
                                        searchActive = true;
                                        searchedForCurrent = true;
                                        phase = 2;
                                        phaseTicks = 0;
                                        return BUSY;
                                }
                                if (phaseTicks > 6)
                                {
                                        searchedForCurrent = true;
                                        phase = 0;
                                }
                                return BUSY;
                        }
                        case 2:
                        {
                                // Give the filtered list a moment to settle.
                                if (phaseTicks >= 2)
                                {
                                        phase = 0;
                                        phaseTicks = 0;
                                }
                                return BUSY;
                        }
                        case 3:
                        {
                                // Withdraw-X: wait for the amount prompt, then type it.
                                int type = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
                                if (type != 0 && type != INPUT_SEARCH && pendingAmount != null)
                                {
                                        input.type(pendingAmount, true);
                                        pendingAmount = null;
                                        phase = 4;
                                        phaseTicks = 0;
                                        return BUSY;
                                }
                                if (phaseTicks > 8)
                                {
                                        pendingAmount = null;
                                        return retry();
                                }
                                return BUSY;
                        }
                        case 4:
                        default:
                        {
                                if (have > countBefore)
                                {
                                        // Got some; loop decides whether more are needed.
                                        phase = 0;
                                        phaseTicks = 0;
                                        tries = 0;
                                        return BUSY;
                                }
                                if (phaseTicks > 5)
                                {
                                        return retry();
                                }
                                return BUSY;
                        }
                }
        }

        private int retry()
        {
                phase = 0;
                phaseTicks = 0;
                if (++tries >= MAX_WITHDRAW_TRIES)
                {
                        Need need = needs.get(needIndex);
                        if (need.required)
                        {
                                return fail("Could not withdraw " + need.entry.searchText(items));
                        }
                        skipped.add(need.entry.searchText(items));
                        needIndex++;
                        resetNeed();
                }
                return BUSY;
        }

        private boolean stackable(Need need)
        {
                Widget slot = findBankItem(need);
                if (slot == null)
                {
                        return false;
                }
                ItemComposition comp = items.getItemComposition(slot.getItemId());
                return comp != null && comp.isStackable();
        }

        /** The bank's slot for a need (placeholders excluded), or null. */
        private Widget findBankItem(Need need)
        {
                Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
                if (container == null || container.isHidden())
                {
                        return null;
                }
                Widget[] children = container.getDynamicChildren();
                if (children == null)
                {
                        return null;
                }
                for (Widget child : children)
                {
                        if (child == null || child.isHidden() || child.getItemQuantity() <= 0)
                        {
                                continue;
                        }
                        int id = child.getItemId();
                        if (id > 0 && id != GameHelpers.EMPTY_ITEM_ID && need.entry.matches(items, id, false))
                        {
                                return child;
                        }
                }
                return null;
        }

        /** True when the slot is fully inside the bank's visible (scrolled) area. */
        private boolean slotVisible(Widget slot)
        {
                Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
                if (container == null)
                {
                        return false;
                }
                Rectangle c = container.getBounds();
                Rectangle s = slot.getBounds();
                return c != null && s != null && s.width > 0 && s.height > 0
                        && s.y >= c.y && s.y + s.height <= c.y + c.height
                        && s.x >= c.x && s.x + s.width <= c.x + c.width;
        }

        // ---------------------------------------------------------------- depositing

        private boolean hasDepositables()
        {
                for (net.runelite.api.Item item : GameHelpers.inventory(client))
                {
                        if (item.getId() > 0 && item.getId() != GameHelpers.EMPTY_ITEM_ID && !keep.test(item.getId()))
                        {
                                return true;
                        }
                }
                return false;
        }

        private boolean inventoryHasKept()
        {
                for (net.runelite.api.Item item : GameHelpers.inventory(client))
                {
                        if (item.getId() > 0 && item.getId() != GameHelpers.EMPTY_ITEM_ID && keep.test(item.getId()))
                        {
                                return true;
                        }
                }
                return false;
        }

        /** The "Deposit inventory" button, found by its action text; null when not found. */
        private int[] depositInventoryPoint()
        {
                int[] ids = {ComponentID.BANK_DEPOSIT_INVENTORY, InterfaceID.Bankmain.DEPOSITINV, InterfaceID.Bankmain.DEPOSITWORN};
                for (int id : ids)
                {
                        Widget w = client.getWidget(id);
                        if (w == null || w.isHidden())
                        {
                                continue;
                        }
                        String[] actions = w.getActions();
                        if (actions == null)
                        {
                                continue;
                        }
                        for (String a : actions)
                        {
                                if (a != null && Text.removeTags(a).toLowerCase(java.util.Locale.ROOT).contains("deposit inventory"))
                                {
                                        return input.widgetPoint(w);
                                }
                        }
                }
                return null;
        }

        // ---------------------------------------------------------------- finding the bank

        private int distance(Object raw, WorldPoint here)
        {
                WorldPoint wp = raw instanceof NPC ? ((NPC) raw).getWorldLocation() : ((TileObject) raw).getWorldLocation();
                return wp == null ? Integer.MAX_VALUE : cheb(here, wp);
        }

        /** Nearest reachable booth / chest / banker within {@code radius} tiles, or null. */
        private Object findBankTarget(Player local, LocalReach reach, int radius)
        {
                WorldPoint me = local.getWorldLocation();
                Object best = null;
                int bestDist = Integer.MAX_VALUE;
                for (GameObject obj : GameHelpers.gameObjectsAround(client, local.getLocalLocation(), radius))
                {
                        if (bankAction(obj) == null || isUnreachable(obj) || !GameHelpers.canInteract(reach, obj))
                        {
                                continue;
                        }
                        int d = distance(obj, me);
                        if (d < bestDist)
                        {
                                best = obj;
                                bestDist = d;
                        }
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv != null)
                {
                        for (NPC npc : wv.npcs())
                        {
                                if (npc == null || !GameHelpers.npcHasAction(npc, "Bank") || isUnreachable(npc)
                                        || !GameHelpers.canInteract(reach, npc))
                                {
                                        continue;
                                }
                                int d = distance(npc, me);
                                if (d <= radius && d < bestDist)
                                {
                                        best = npc;
                                        bestDist = d;
                                }
                        }
                }
                return best;
        }

        private Target bankTargetFor(Object raw)
        {
                if (raw instanceof NPC)
                {
                        NPC npc = (NPC) raw;
                        return Targets.npc(npc, () -> bankRaw == npc && !npc.isDead());
                }
                TileObject obj = (TileObject) raw;
                return Targets.object(obj, () -> bankRaw == obj);
        }

        /** "Bank" for booths / bankers, "Use" for bank chests; null for anything else. */
        private String bankAction(Object raw)
        {
                if (raw instanceof NPC)
                {
                        return GameHelpers.npcHasAction((NPC) raw, "Bank") ? "Bank" : null;
                }
                ObjectComposition comp = GameHelpers.composition(client, ((TileObject) raw).getId());
                if (comp == null)
                {
                        return null;
                }
                if (GameHelpers.hasAction(comp.getActions(), "Bank"))
                {
                        return "Bank";
                }
                String name = GameHelpers.name(comp).toLowerCase(java.util.Locale.ROOT);
                if (name.contains("bank chest") && GameHelpers.hasAction(comp.getActions(), "Use"))
                {
                        return "Use";
                }
                return null;
        }

        private boolean defaultAction(Object raw, String action)
        {
                if (raw instanceof NPC)
                {
                        return GameHelpers.npcDefaultAction((NPC) raw, action);
                }
                return GameHelpers.objectDefaultAction(client, (TileObject) raw, action);
        }
}
