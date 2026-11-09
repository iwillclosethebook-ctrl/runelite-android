package net.runelite.client.plugins.humanizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.gamedata.LocalReach;
import net.runelite.client.util.Text;

/** Small read-only game helpers shared by the scripts. Client thread only. */
public final class GameHelpers
{
        public static final int EMPTY_ITEM_ID = 6512;

        private GameHelpers()
        {
        }

        /** The composition the player sees (impostors resolved), or null. */
        public static ObjectComposition composition(Client client, int id)
        {
                ObjectComposition comp = client.getObjectDefinition(id);
                if (comp != null && comp.getImpostorIds() != null)
                {
                        ObjectComposition imp = comp.getImpostor();
                        if (imp != null)
                        {
                                comp = imp;
                        }
                }
                return comp;
        }

        public static String name(ObjectComposition comp)
        {
                return comp == null ? "" : Text.removeTags(String.valueOf(comp.getName()));
        }

        public static boolean hasAction(String[] actions, String wanted)
        {
                if (actions == null || wanted == null)
                {
                        return false;
                }
                for (String a : actions)
                {
                        if (a != null && wanted.equalsIgnoreCase(Text.removeTags(a)))
                        {
                                return true;
                        }
                }
                return false;
        }

        /** True when {@code wanted} is the first (left-click) action in the list. */
        public static boolean isFirstAction(String[] actions, String wanted)
        {
                if (actions == null || wanted == null)
                {
                        return false;
                }
                for (String a : actions)
                {
                        if (a != null && !a.isEmpty())
                        {
                                return wanted.equalsIgnoreCase(Text.removeTags(a));
                        }
                }
                return false;
        }

        public static boolean objectHasAction(Client client, TileObject obj, String wanted)
        {
                ObjectComposition comp = composition(client, obj.getId());
                return comp != null && hasAction(comp.getActions(), wanted);
        }

        public static boolean objectDefaultAction(Client client, TileObject obj, String wanted)
        {
                ObjectComposition comp = composition(client, obj.getId());
                return comp != null && isFirstAction(comp.getActions(), wanted);
        }

        public static NPCComposition npcComposition(NPC npc)
        {
                NPCComposition comp = npc.getTransformedComposition();
                return comp != null ? comp : npc.getComposition();
        }

        public static boolean npcHasAction(NPC npc, String wanted)
        {
                NPCComposition comp = npcComposition(npc);
                return comp != null && hasAction(comp.getActions(), wanted);
        }

        public static boolean npcDefaultAction(NPC npc, String wanted)
        {
                NPCComposition comp = npcComposition(npc);
                return comp != null && isFirstAction(comp.getActions(), wanted);
        }

        /** True when the player can walk up to this object (live collision); null reach = unknown = yes. */
        public static boolean canInteract(LocalReach reach, TileObject obj)
        {
                if (obj == null)
                {
                        return false;
                }
                if (reach == null)
                {
                        return true;
                }
                if (obj instanceof GameObject)
                {
                        GameObject g = (GameObject) obj;
                        Point min = g.getSceneMinLocation();
                        return min != null && reach.canInteract(min.getX(), min.getY(), g.sizeX(), g.sizeY());
                }
                LocalPoint lp = obj.getLocalLocation();
                return lp != null && reach.canInteract(lp.getSceneX(), lp.getSceneY(), 1, 1);
        }

        public static boolean canInteract(LocalReach reach, NPC npc)
        {
                if (npc == null)
                {
                        return false;
                }
                if (reach == null)
                {
                        return true;
                }
                NPCComposition comp = npcComposition(npc);
                int size = comp == null ? 1 : Math.max(1, comp.getSize());
                return reach.canInteractCentred(npc.getLocalLocation(), size);
        }

        /** Every GameObject whose base tile lies within {@code radius} scene tiles of {@code centre}. */
        public static List<GameObject> gameObjectsAround(Client client, LocalPoint centre, int radius)
        {
                List<GameObject> out = new ArrayList<>();
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null || centre == null)
                {
                        return out;
                }
                Scene scene = wv.getScene();
                if (scene == null)
                {
                        return out;
                }
                Tile[][][] tiles = scene.getTiles();
                int plane = wv.getPlane();
                if (tiles == null || plane < 0 || plane >= tiles.length)
                {
                        return out;
                }
                int cx = centre.getSceneX();
                int cy = centre.getSceneY();
                Tile[][] layer = tiles[plane];
                for (int x = Math.max(0, cx - radius); x <= Math.min(layer.length - 1, cx + radius); x++)
                {
                        Tile[] column = layer[x];
                        if (column == null)
                        {
                                continue;
                        }
                        for (int y = Math.max(0, cy - radius); y <= Math.min(column.length - 1, cy + radius); y++)
                        {
                                Tile tile = column[y];
                                if (tile == null)
                                {
                                        continue;
                                }
                                GameObject[] objects = tile.getGameObjects();
                                if (objects == null)
                                {
                                        continue;
                                }
                                for (GameObject obj : objects)
                                {
                                        if (obj != null && obj.getSceneMinLocation() != null
                                                && obj.getSceneMinLocation().equals(tile.getSceneLocation()))
                                        {
                                                out.add(obj);
                                        }
                                }
                        }
                }
                return out;
        }

        /** VarClientID.TOPLEVEL_PANEL value of the inventory tab (same index Microbot's Rs2Tab uses). */
        public static final int INVENTORY_TAB_INDEX = 3;

        /** Side tab indices, as the TOPLEVEL_PANEL client variable numbers them (same as Microbot's InterfaceTab). */
        public static final int TAB_COMBAT = 0;
        public static final int TAB_SKILLS = 1;
        public static final int TAB_QUESTS = 2;
        public static final int TAB_EQUIPMENT = 4;
        public static final int TAB_PRAYER = 5;
        public static final int TAB_MAGIC = 6;

        /**
         * The varbit that stores which key the player bound to each side tab (Settings >
         * Controls > Keybinds), by tab index. Value 0 = no key, 1-12 = F1-F12, 13 = Escape.
         */
        private static final int[] TAB_KEY_VARBITS = {
                net.runelite.api.gameval.VarbitID.STONE_COMBAT_KEY,   // 0 combat
                net.runelite.api.gameval.VarbitID.STONE_STATS_KEY,    // 1 skills
                net.runelite.api.gameval.VarbitID.STONE_JOURNAL_KEY,  // 2 quests
                net.runelite.api.gameval.VarbitID.STONE_INV_KEY,      // 3 inventory
                net.runelite.api.gameval.VarbitID.STONE_WORN_KEY,     // 4 equipment
                net.runelite.api.gameval.VarbitID.STONE_PRAYER_KEY,   // 5 prayer
                net.runelite.api.gameval.VarbitID.STONE_MAGIC_KEY,    // 6 magic
                net.runelite.api.gameval.VarbitID.STONE_CLANCHAT_KEY, // 7 chat-channel
                net.runelite.api.gameval.VarbitID.STONE_ACCOUNT_KEY,  // 8 account
                net.runelite.api.gameval.VarbitID.STONE_FRIENDS_KEY,  // 9 friends
                net.runelite.api.gameval.VarbitID.STONE_LOGOUT_KEY,   // 10 logout
                net.runelite.api.gameval.VarbitID.STONE_OPTIONS1_KEY, // 11 settings
                net.runelite.api.gameval.VarbitID.STONE_OPTIONS2_KEY, // 12 emotes
                net.runelite.api.gameval.VarbitID.STONE_MUSIC_KEY,    // 13 music
        };

        /** Which side tab is open right now (TOPLEVEL_PANEL client variable). */
        public static int openTab(Client client)
        {
                return client.getVarcIntValue(net.runelite.api.gameval.VarClientID.TOPLEVEL_PANEL);
        }

        /** True when the inventory side tab is the open one (read from the client, like Microbot does). */
        public static boolean inventoryTabOpen(Client client)
        {
                return openTab(client) == INVENTORY_TAB_INDEX;
        }

        /**
         * The key code the player bound to a side tab in the game's keybind settings, read
         * from the game itself, or -1 when the tab has no key. F1-F12 map to VK_F1..VK_F12,
         * 13 is Escape.
         */
        public static int tabHotkey(Client client, int tabIndex)
        {
                if (tabIndex < 0 || tabIndex >= TAB_KEY_VARBITS.length)
                {
                        return -1;
                }
                int v = client.getVarbitValue(TAB_KEY_VARBITS[tabIndex]);
                if (v >= 1 && v <= 12)
                {
                        return java.awt.event.KeyEvent.VK_F1 + (v - 1);
                }
                if (v == 13)
                {
                        return java.awt.event.KeyEvent.VK_ESCAPE;
                }
                return -1;
        }

        /** "F4", "Esc", or "none" — for the overlay / logs. */
        public static String hotkeyLabel(int vk)
        {
                if (vk >= java.awt.event.KeyEvent.VK_F1 && vk <= java.awt.event.KeyEvent.VK_F12)
                {
                        return "F" + (vk - java.awt.event.KeyEvent.VK_F1 + 1);
                }
                return vk == java.awt.event.KeyEvent.VK_ESCAPE ? "Esc" : "none";
        }

        /** Makes sure the inventory is showing before dropping / eating. See {@link #openTab(Client, HumanInput, int)}. */
        public static boolean openInventoryTab(Client client, HumanInput input)
        {
                if (inventoryTabOpen(client))
                {
                        return true;
                }
                if (!openTab(client, input, INVENTORY_TAB_INDEX))
                {
                        // No tab button and no key (inventory pinned open by the mobile layout?): carry on if it is visible.
                        net.runelite.api.widgets.Widget inv = client.getWidget(net.runelite.api.widgets.ComponentID.INVENTORY_CONTAINER);
                        return inv != null && !inv.isHidden();
                }
                return false;
        }

        /**
         * Switches to a side tab. Which tab is open is read the way Microbot reads it (the
         * TOPLEVEL_PANEL client variable). The switch itself is a real input, never an
         * internal call: when the player has an F-key bound to that tab (read from the
         * game's keybind varbits) it is usually pressed — a separate key-down and key-up
         * with a human hold — and otherwise, or now and then anyway, the tab button is
         * tapped with the simulated finger. The button is found by its fixed interface ID
         * in each layout (resizable classic, resizable modern, fixed), falling back to any
         * visible widget with the tab's action.
         *
         * @return true when a key/tap was sent or the tab is already open; false when there
         *         was no way to switch
         */
        public static boolean openTab(Client client, HumanInput input, int tabIndex)
        {
                if (openTab(client) == tabIndex)
                {
                        return true;
                }
                if (input.isBusy())
                {
                        return true; // try again next tick
                }
                HumanizerConfig cfg = input.config();
                int vk = tabHotkey(client, tabIndex);
                boolean keyFirst = vk > 0 && cfg.useFKeys() && input.random().nextInt(100) < cfg.fKeyChance();
                if (keyFirst)
                {
                        input.pressKey(vk, "tab " + tabIndex + " (" + hotkeyLabel(vk) + ")");
                        return true;
                }
                int[] p = tabButtonPoint(client, input, tabIndex);
                if (p != null)
                {
                        input.tap(p, "tab " + tabIndex);
                        return true;
                }
                if (vk > 0 && cfg.useFKeys())
                {
                        input.pressKey(vk, "tab " + tabIndex + " (" + hotkeyLabel(vk) + ")");
                        return true;
                }
                return false;
        }

        private static final String[] TAB_ACTIONS = {
                "Combat Options", "Skills", "Quest List", "Inventory", "Worn Equipment", "Prayer", "Magic",
        };

        /** A tap point on a side tab's button, or null when none is showing. */
        public static int[] tabButtonPoint(Client client, HumanInput input, int tabIndex)
        {
                String field = "STONE" + tabIndex;
                Class<?>[] layouts = {
                        net.runelite.api.gameval.InterfaceID.ToplevelOsrsStretch.class,
                        net.runelite.api.gameval.InterfaceID.ToplevelPreEoc.class,
                        net.runelite.api.gameval.InterfaceID.Toplevel.class,
                };
                net.runelite.api.widgets.Widget tab = null;
                for (Class<?> layout : layouts)
                {
                        try
                        {
                                net.runelite.api.widgets.Widget w = client.getWidget(layout.getField(field).getInt(null));
                                if (w != null && !w.isHidden())
                                {
                                        tab = w;
                                        break;
                                }
                        }
                        catch (ReflectiveOperationException ignored)
                        {
                                // this layout has no such stone
                        }
                }
                if (tab == null && tabIndex < TAB_ACTIONS.length)
                {
                        tab = findWidgetWithAction(client, TAB_ACTIONS[tabIndex]);
                }
                return tab == null ? null : input.widgetPoint(tab);
        }

        /** First visible widget (any interface, any depth) with the given action, or null. */
        public static net.runelite.api.widgets.Widget findWidgetWithAction(Client client, String action)
        {
                net.runelite.api.widgets.Widget[] roots = client.getWidgetRoots();
                if (roots == null)
                {
                        return null;
                }
                java.util.ArrayDeque<net.runelite.api.widgets.Widget> queue = new java.util.ArrayDeque<>();
                for (net.runelite.api.widgets.Widget r : roots)
                {
                        if (r != null)
                        {
                                queue.add(r);
                        }
                }
                int budget = 4000;
                while (!queue.isEmpty() && budget-- > 0)
                {
                        net.runelite.api.widgets.Widget w = queue.poll();
                        if (w.isHidden())
                        {
                                continue;
                        }
                        if (hasAction(w.getActions(), action))
                        {
                                return w;
                        }
                        for (net.runelite.api.widgets.Widget[] kids : new net.runelite.api.widgets.Widget[][]{
                                w.getStaticChildren(), w.getNestedChildren(), w.getDynamicChildren()})
                        {
                                if (kids == null)
                                {
                                        continue;
                                }
                                for (net.runelite.api.widgets.Widget k : kids)
                                {
                                        if (k != null)
                                        {
                                                queue.add(k);
                                        }
                                }
                        }
                }
                return null;
        }

        public static Item[] inventory(Client client)
        {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                return inv == null ? new Item[0] : inv.getItems();
        }

        public static int freeSlots(Client client)
        {
                Item[] items = inventory(client);
                int used = 0;
                for (Item i : items)
                {
                        if (i.getId() > 0 && i.getId() != EMPTY_ITEM_ID)
                        {
                                used++;
                        }
                }
                return Math.max(0, 28 - used);
        }

        public static boolean inventoryFull(Client client)
        {
                return freeSlots(client) == 0;
        }

        public static String itemName(ItemManager items, int id)
        {
                return Text.removeTags(String.valueOf(items.getItemComposition(items.canonicalize(id)).getName()));
        }

        /**
         * A list of items written as "name or id [x quantity]" separated by commas, e.g.
         * "Lobster x10, Brass key, 995 x500". Names match exactly (case-insensitive) unless
         * {@code contains} is set, in which case a name matches any item containing it.
         */
        public static final class ItemList
        {
                public static final class Entry
                {
                        public final Integer id;
                        public final String name; // lowercase
                        public final int quantity;

                        Entry(Integer id, String name, int quantity)
                        {
                                this.id = id;
                                this.name = name;
                                this.quantity = quantity;
                        }

                        public boolean matches(ItemManager items, int itemId, boolean contains)
                        {
                                int canon = items.canonicalize(itemId);
                                if (id != null)
                                {
                                        return id == itemId || id == canon;
                                }
                                String n = itemName(items, canon).toLowerCase(Locale.ROOT);
                                return contains ? n.contains(name) : n.equals(name);
                        }

                        public String label()
                        {
                                return id != null ? String.valueOf(id) : name;
                        }

                        /** What to type into the bank search box. */
                        public String searchText(ItemManager items)
                        {
                                return id != null ? itemName(items, id) : name;
                        }
                }

                public final List<Entry> entries = new ArrayList<>();
                private final boolean contains;

                public ItemList(String raw, boolean contains, int defaultQuantity)
                {
                        this.contains = contains;
                        if (raw == null)
                        {
                                return;
                        }
                        for (String part : raw.split(","))
                        {
                                String p = part.trim();
                                if (p.isEmpty())
                                {
                                        continue;
                                }
                                int qty = defaultQuantity;
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*[xX*]\\s*(\\d+)$").matcher(p);
                                if (m.matches() && !m.group(1).isEmpty())
                                {
                                        p = m.group(1).trim();
                                        qty = Integer.parseInt(m.group(2));
                                }
                                Integer id = null;
                                try
                                {
                                        id = Integer.parseInt(p);
                                }
                                catch (NumberFormatException ignored)
                                {
                                        // a name
                                }
                                entries.add(new Entry(id, id == null ? p.toLowerCase(Locale.ROOT) : null, qty));
                        }
                }

                public boolean isEmpty()
                {
                        return entries.isEmpty();
                }

                public Entry match(ItemManager items, int itemId)
                {
                        if (itemId <= 0 || itemId == EMPTY_ITEM_ID)
                        {
                                return null;
                        }
                        for (Entry e : entries)
                        {
                                if (e.matches(items, itemId, contains))
                                {
                                        return e;
                                }
                        }
                        return null;
                }

                public boolean contains(ItemManager items, int itemId)
                {
                        return match(items, itemId) != null;
                }

                /** How many of this entry's item the inventory holds (stack sizes counted). */
                public int count(Client client, ItemManager items, Entry e)
                {
                        int n = 0;
                        for (Item i : inventory(client))
                        {
                                if (i.getId() > 0 && i.getId() != EMPTY_ITEM_ID && e.matches(items, i.getId(), contains))
                                {
                                        n += Math.max(1, i.getQuantity());
                                }
                        }
                        return n;
                }
        }
}
