package net.runelite.client.plugins.humanizer;

import java.awt.Shape;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Factories for {@link Target}. Equality follows the underlying game thing. */
public final class Targets
{
        private Targets()
        {
        }

        private abstract static class Base implements Target
        {
                private final BooleanSupplier live;

                Base(BooleanSupplier live)
                {
                        this.live = live;
                }

                @Override
                public boolean isLive()
                {
                        return live == null || live.getAsBoolean();
                }

                @Override
                public boolean equals(Object o)
                {
                        return o instanceof Target && Objects.equals(raw(), ((Target) o).raw());
                }

                @Override
                public int hashCode()
                {
                        return Objects.hashCode(raw());
                }
        }

        public static Target npc(NPC npc, BooleanSupplier live)
        {
                return new Base(live)
                {
                        @Override
                        public Shape hull()
                        {
                                return npc.getConvexHull();
                        }

                        @Override
                        public LocalPoint localLocation()
                        {
                                return npc.getLocalLocation();
                        }

                        @Override
                        public WorldPoint worldLocation()
                        {
                                return npc.getWorldLocation();
                        }

                        @Override
                        public int aimHeight()
                        {
                                return Math.max(npc.getLogicalHeight() / 2, 40);
                        }

                        @Override
                        public boolean matches(MenuEntry entry)
                        {
                                return entry != null && entry.getNpc() == npc;
                        }

                        @Override
                        public Object raw()
                        {
                                return npc;
                        }
                };
        }

        /** Scene x/y of the tile an object's menu entries are keyed on. */
        public static int[] sceneBase(TileObject obj)
        {
                if (obj instanceof GameObject)
                {
                        Point min = ((GameObject) obj).getSceneMinLocation();
                        return min == null ? null : new int[]{min.getX(), min.getY()};
                }
                LocalPoint lp = obj.getLocalLocation();
                return lp == null ? null : new int[]{lp.getSceneX(), lp.getSceneY()};
        }

        public static Shape hullOf(TileObject obj)
        {
                if (obj instanceof GameObject)
                {
                        return ((GameObject) obj).getConvexHull();
                }
                if (obj instanceof WallObject)
                {
                        return ((WallObject) obj).getConvexHull();
                }
                if (obj instanceof DecorativeObject)
                {
                        return ((DecorativeObject) obj).getConvexHull();
                }
                if (obj instanceof GroundObject)
                {
                        return ((GroundObject) obj).getConvexHull();
                }
                return obj.getClickbox();
        }

        /** True when a menu entry is an option on exactly this object (id and tile). */
        public static boolean objectMatches(MenuEntry entry, TileObject obj)
        {
                if (entry == null || obj == null || entry.getIdentifier() != obj.getId())
                {
                        return false;
                }
                int[] base = sceneBase(obj);
                return base != null && entry.getParam0() == base[0] && entry.getParam1() == base[1];
        }

        public static Target object(TileObject obj, BooleanSupplier live)
        {
                return new Base(live)
                {
                        @Override
                        public Shape hull()
                        {
                                return hullOf(obj);
                        }

                        @Override
                        public LocalPoint localLocation()
                        {
                                return obj.getLocalLocation();
                        }

                        @Override
                        public WorldPoint worldLocation()
                        {
                                return obj.getWorldLocation();
                        }

                        @Override
                        public int aimHeight()
                        {
                                return 90;
                        }

                        @Override
                        public boolean matches(MenuEntry entry)
                        {
                                return objectMatches(entry, obj);
                        }

                        @Override
                        public Object raw()
                        {
                                return obj;
                        }
                };
        }

        /**
         * A ground item: aimed at the item pile's clickbox on its tile, and only a "Take" row
         * for this item id on this tile counts.
         */
        public static Target groundItem(Client client, Tile tile, int itemId, BooleanSupplier live)
        {
                final LocalPoint lp = tile.getLocalLocation();
                final WorldPoint wp = tile.getWorldLocation();
                final String key = itemId + "@" + (wp == null ? "?" : wp.getX() + "," + wp.getY() + "," + wp.getPlane());
                return new Base(live)
                {
                        @Override
                        public Shape hull()
                        {
                                ItemLayer layer = tile.getItemLayer();
                                Shape s = layer == null ? null : layer.getClickbox();
                                if (s == null && lp != null)
                                {
                                        s = Perspective.getCanvasTilePoly(client, lp);
                                }
                                return s;
                        }

                        @Override
                        public LocalPoint localLocation()
                        {
                                return lp;
                        }

                        @Override
                        public WorldPoint worldLocation()
                        {
                                return wp;
                        }

                        @Override
                        public int aimHeight()
                        {
                                return 10;
                        }

                        @Override
                        public boolean matches(MenuEntry entry)
                        {
                                return entry != null && lp != null && entry.getIdentifier() == itemId
                                        && entry.getParam0() == lp.getSceneX() && entry.getParam1() == lp.getSceneY();
                        }

                        @Override
                        public Object raw()
                        {
                                return key;
                        }
                };
        }
}
