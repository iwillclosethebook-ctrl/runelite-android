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

        /**
         * Makes sure the inventory is showing: when it is hidden behind another side tab,
         * taps the inventory tab button (found by its "Inventory" action) like a player would.
         *
         * @return true when the inventory is visible now; false when a tap was sent instead
         */
        public static boolean openInventoryTab(Client client, HumanInput input)
        {
                net.runelite.api.widgets.Widget inv = client.getWidget(net.runelite.api.widgets.ComponentID.INVENTORY_CONTAINER);
                if (inv != null && !inv.isHidden())
                {
                        return true;
                }
                net.runelite.api.widgets.Widget tab = findWidgetWithAction(client, "Inventory");
                int[] p = tab == null ? null : input.widgetPoint(tab);
                if (p == null)
                {
                        return true; // cannot find the tab: let the caller try anyway
                }
                input.tap(p, "inventory tab");
                return false;
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
