package net.runelite.client.plugins.mobiledevtools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.Text;

/**
 * Draws IDs directly into the world, the way microbot's dev tools do on desktop — but
 * nothing here needs a mouse: every label is anchored to its NPC, tile, object or
 * projectile, and each category is toggled from the config or by long-pressing the
 * info panel.
 */
class MobileDevToolsOverlay extends Overlay
{
        private static final Font FONT = FontManager.getRunescapeFont().deriveFont(Font.BOLD, 16);
        private static final Font FONT_SMALL = FontManager.getRunescapeFont().deriveFont(Font.PLAIN, 12);

        private static final Color RED = new Color(221, 44, 0);
        private static final Color GREEN = new Color(0, 200, 83);
        private static final Color YELLOW = new Color(255, 214, 0);
        private static final Color CYAN = new Color(0, 184, 212);
        private static final Color BLUE = new Color(41, 98, 255);
        private static final Color GRAY = new Color(158, 158, 158);
        private static final Color PINK = new Color(255, 64, 129);
        private static final Color LIGHT_GRAY = new Color(211, 211, 211);
        private static final Color WHITE = Color.WHITE;
        private static final Color MAGENTA = Color.MAGENTA;

        private final Client client;
        private final MobileDevToolsPlugin plugin;
        private final MobileDevToolsConfig config;
        private final ItemManager itemManager;

        @Inject
        MobileDevToolsOverlay(Client client, MobileDevToolsPlugin plugin, MobileDevToolsConfig config, ItemManager itemManager)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                this.itemManager = itemManager;
                setPosition(OverlayPosition.DYNAMIC);
                setLayer(OverlayLayer.ABOVE_WIDGETS);
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                        return null;
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null)
                {
                        return null;
                }
                Player local = client.getLocalPlayer();

                graphics.setFont(FONT);
                if (config.showNpcs())
                {
                        renderNpcs(wv, graphics, local);
                }
                if (config.showPlayers())
                {
                        renderPlayers(wv, graphics, local);
                }

                boolean anyTileThing = config.showGroundItems() || config.showGameObjects() || config.showWalls()
                        || config.showDecorations() || config.showGroundObjects() || config.showTileCoords();
                if (anyTileThing && local != null)
                {
                        renderTiles(wv, graphics, local);
                }

                if (config.showProjectiles())
                {
                        renderProjectiles(graphics);
                }
                if (config.showGraphicsObjects())
                {
                        renderGraphicsObjects(wv, graphics);
                }
                if (config.showInventoryIds())
                {
                        renderInventoryIds(graphics);
                }
                if (config.showTileFlags())
                {
                        renderTileFlags(wv, graphics);
                }
                return null;
        }

        private void renderNpcs(WorldView wv, Graphics2D graphics, Player local)
        {
                WorldPoint me = local == null ? null : local.getWorldLocation();
                for (NPC npc : wv.npcs())
                {
                        if (me != null)
                        {
                                WorldPoint at = npc.getWorldLocation();
                                if (at == null || me.distanceTo(at) > config.labelDistance())
                                {
                                        continue;
                                }
                        }

                        String name = npc.getName();
                        String text = (name == null ? "?" : Text.removeTags(name)) + " (ID:" + npc.getId() + ")";
                        if (config.npcDetails())
                        {
                                text += " (A:" + npc.getAnimation() + " P:" + npc.getPoseAnimation() + ")";
                        }
                        OverlayUtil.renderActorOverlay(graphics, npc, text, YELLOW);
                }
        }

        private void renderPlayers(WorldView wv, Graphics2D graphics, Player local)
        {
                WorldPoint me = local == null ? null : local.getWorldLocation();
                for (Player p : wv.players())
                {
                        if (p != local)
                        {
                                if (me != null)
                                {
                                        WorldPoint at = p.getWorldLocation();
                                        if (at == null || me.distanceTo(at) > config.labelDistance())
                                        {
                                                continue;
                                        }
                                }
                                String text = p.getName() + " (A:" + p.getAnimation() + " P:" + p.getPoseAnimation() + " G:" + p.getGraphic() + ")";
                                OverlayUtil.renderActorOverlay(graphics, p, text, BLUE);
                        }
                }
                if (local != null)
                {
                        String text = local.getName() + " (A:" + local.getAnimation() + " P:" + local.getPoseAnimation() + " G:" + local.getGraphic() + ")";
                        OverlayUtil.renderActorOverlay(graphics, local, text, CYAN);
                }
        }

        private void renderTiles(WorldView wv, Graphics2D graphics, Player local)
        {
                Scene scene = wv.getScene();
                if (scene == null)
                {
                        return;
                }
                Tile[][][] tiles = scene.getTiles();
                int plane = wv.getPlane();
                if (plane < 0 || plane >= tiles.length)
                {
                        return;
                }
                LocalPoint here = local.getLocalLocation();
                if (here == null)
                {
                        return;
                }

                int meX = here.getSceneX();
                int meY = here.getSceneY();
                int radius = Math.min(Math.max(config.labelDistance(), 1), 16);
                int coordRadius = Math.min(Math.max(config.tileCoordRadius(), 1), 15);

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

                                if (config.showGroundItems())
                                {
                                        renderGroundItems(graphics, tile, plane);
                                }
                                if (config.showGameObjects())
                                {
                                        renderGameObjects(graphics, tile);
                                }
                                if (config.showWalls())
                                {
                                        renderTileObject(graphics, tile.getWallObject(), "Wall", GRAY);
                                }
                                if (config.showDecorations())
                                {
                                        renderTileObject(graphics, tile.getDecorativeObject(), "Decor", LIGHT_GRAY);
                                }
                                if (config.showGroundObjects())
                                {
                                        renderTileObject(graphics, tile.getGroundObject(), "Ground", PINK);
                                }
                                if (config.showTileCoords() && Math.max(Math.abs(x - meX), Math.abs(y - meY)) <= coordRadius)
                                {
                                        renderTileCoords(graphics, tile, plane);
                                }
                        }
                }
        }

        /** One label per tile listing every ground item on it, name (ID) by name (ID). */
        private void renderGroundItems(Graphics2D graphics, Tile tile, int plane)
        {
                java.util.List<TileItem> items = tile.getGroundItems();
                if (items == null || items.isEmpty())
                {
                        return;
                }

                StringBuilder sb = new StringBuilder();
                for (TileItem item : items)
                {
                        int id = itemManager.canonicalize(item.getId());
                        if (sb.length() > 0)
                        {
                                sb.append(' ');
                        }
                        sb.append(itemName(id)).append(" (ID:").append(id).append(')');
                }

                LocalPoint lp = tile.getLocalLocation();
                if (lp == null)
                {
                        return;
                }
                graphics.setFont(FONT);
                Point labelPoint = Perspective.getCanvasTextLocation(client, graphics, lp, sb.toString(), plane);
                if (labelPoint != null)
                {
                        OverlayUtil.renderTextLocation(graphics, labelPoint, sb.toString(), RED);
                }
        }

        private void renderGameObjects(Graphics2D graphics, Tile tile)
        {
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null)
                {
                        return;
                }
                for (GameObject gameObject : gameObjects)
                {
                        if (gameObject == null || !gameObject.getSceneMinLocation().equals(tile.getSceneLocation()))
                        {
                                continue;
                        }

                        String name = null;
                        net.runelite.api.ObjectComposition comp = client.getObjectDefinition(gameObject.getId());
                        if (comp != null)
                        {
                                name = comp.getName();
                        }
                        String text = (name == null || name.isEmpty() ? "Object" : Text.removeTags(name)) + " (ID:" + gameObject.getId() + ")";

                        graphics.setFont(FONT);
                        Point textLocation = gameObject.getCanvasTextLocation(graphics, text, 0);
                        if (textLocation != null)
                        {
                                OverlayUtil.renderTextLocation(graphics, textLocation, text, GREEN);
                        }
                }
        }

        private void renderTileObject(Graphics2D graphics, TileObject tileObject, String prefix, Color color)
        {
                if (tileObject != null)
                {
                        OverlayUtil.renderTileOverlay(graphics, tileObject, prefix + " (ID:" + tileObject.getId() + ")", color);
                }
        }

        private void renderTileCoords(Graphics2D graphics, Tile tile, int plane)
        {
                LocalPoint lp = tile.getLocalLocation();
                if (lp == null)
                {
                        return;
                }
                WorldPoint wp = WorldPoint.fromLocalInstance(client, lp);
                String text = wp.getX() + "," + wp.getY();

                graphics.setFont(FONT_SMALL);
                Point loc = Perspective.getCanvasTextLocation(client, graphics, lp, text, plane);
                if (loc != null)
                {
                        OverlayUtil.renderTextLocation(graphics, loc, text, CYAN);
                }
        }

        private void renderProjectiles(Graphics2D graphics)
        {
                graphics.setFont(FONT);
                for (Projectile projectile : client.getProjectiles())
                {
                        String text = "(ID:" + projectile.getId() + ")";
                        int x = (int) projectile.getX();
                        int y = (int) projectile.getY();
                        LocalPoint projectilePoint = new LocalPoint(x, y);
                        Point textLocation = Perspective.getCanvasTextLocation(client, graphics, projectilePoint, text, 0);
                        if (textLocation != null)
                        {
                                OverlayUtil.renderTextLocation(graphics, textLocation, text, RED);
                        }
                }
        }

        private void renderGraphicsObjects(WorldView wv, Graphics2D graphics)
        {
                graphics.setFont(FONT);
                for (GraphicsObject graphicsObject : wv.getGraphicsObjects())
                {
                        LocalPoint lp = graphicsObject.getLocation();
                        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                        if (poly != null)
                        {
                                OverlayUtil.renderPolygon(graphics, poly, MAGENTA);
                        }
                        String infoString = "(ID:" + graphicsObject.getId() + ")";
                        Point textLocation = Perspective.getCanvasTextLocation(client, graphics, lp, infoString, 0);
                        if (textLocation != null)
                        {
                                OverlayUtil.renderTextLocation(graphics, textLocation, infoString, WHITE);
                        }
                }
        }

        private void renderInventoryIds(Graphics2D graphics)
        {
                Widget invWidget = client.getWidget(ComponentID.INVENTORY_CONTAINER);
                if (invWidget == null)
                {
                        return;
                }
                Widget[] children = invWidget.getChildren();
                if (children == null)
                {
                        return;
                }

                graphics.setFont(FONT_SMALL);
                for (Widget child : children)
                {
                        if (child == null)
                        {
                                continue;
                        }
                        int itemId = child.getItemId();
                        if (itemId <= 0 || itemId == 6512)
                        {
                                continue;
                        }
                        Point canvasLocation = child.getCanvasLocation();
                        if (canvasLocation == null)
                        {
                                continue;
                        }
                        Point label = new Point(canvasLocation.getX(), canvasLocation.getY() + child.getHeight());
                        OverlayUtil.renderTextLocation(graphics, label, String.valueOf(itemId), GREEN);
                }
        }

        /**
         * Bridge / visible-below / roof flags, drawn as B/V/R letters on the special tiles
         * of the whole scene (same encoding microbot's dev tools use).
         */
        private void renderTileFlags(WorldView wv, Graphics2D graphics)
        {
                byte[][][] settings = wv.getTileSettings();
                if (settings == null || settings.length <= 1)
                {
                        return;
                }
                int z = wv.getPlane();
                if (z < 0 || z >= settings.length)
                {
                        return;
                }

                graphics.setFont(FONT);
                for (int x = 0; x < Constants.SCENE_SIZE; ++x)
                {
                        for (int y = 0; y < Constants.SCENE_SIZE; ++y)
                        {
                                boolean isbridge = (settings[1][x][y] & Constants.TILE_FLAG_BRIDGE) != 0;
                                int flag = settings[z][x][y];
                                boolean isvisbelow = (flag & Constants.TILE_FLAG_VIS_BELOW) != 0;
                                boolean hasroof = (flag & Constants.TILE_FLAG_UNDER_ROOF) != 0;
                                if (!isbridge && !isvisbelow && !hasroof)
                                {
                                        continue;
                                }
                                String s = "";
                                if (isbridge)
                                {
                                        s += "B";
                                }
                                if (isvisbelow)
                                {
                                        s += "V";
                                }
                                if (hasroof)
                                {
                                        s += "R";
                                }
                                LocalPoint lp = new LocalPoint(x << Perspective.LOCAL_COORD_BITS, y << Perspective.LOCAL_COORD_BITS, wv);
                                Point loc = Perspective.getCanvasTextLocation(client, graphics, lp, s, z);
                                if (loc == null)
                                {
                                        continue;
                                }
                                OverlayUtil.renderTextLocation(graphics, loc, s, RED);
                        }
                }
        }

        private String itemName(int id)
        {
                return Text.removeTags(String.valueOf(itemManager.getItemComposition(id).getName()));
        }
}

/**
 * The finger-friendly replacement for microbot's dev tools side panel: a small info
 * panel with the player's position, animation, camera and FPS — and a long-press menu
 * that toggles every label category without opening the config.
 */
class MobileDevToolsInfoOverlay extends OverlayPanel
{
        private final Client client;
        private final MobileDevToolsPlugin plugin;
        private final MobileDevToolsConfig config;

        @Inject
        MobileDevToolsInfoOverlay(Client client, MobileDevToolsPlugin plugin, MobileDevToolsConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.TOP_LEFT);

                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "NPCs", "DevTools toggle", e -> plugin.toggleNpcs());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Players", "DevTools toggle", e -> plugin.togglePlayers());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Ground items", "DevTools toggle", e -> plugin.toggleGroundItems());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Game objects", "DevTools toggle", e -> plugin.toggleGameObjects());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Walls", "DevTools toggle", e -> plugin.toggleWalls());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Decorations", "DevTools toggle", e -> plugin.toggleDecorations());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Ground objects", "DevTools toggle", e -> plugin.toggleGroundObjects());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Graphics objects", "DevTools toggle", e -> plugin.toggleGraphicsObjects());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Projectiles", "DevTools toggle", e -> plugin.toggleProjectiles());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Inventory IDs", "DevTools toggle", e -> plugin.toggleInventoryIds());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Tile coords", "DevTools toggle", e -> plugin.toggleTileCoords());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Tile flags", "DevTools toggle", e -> plugin.toggleTileFlags());
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (!config.showInfoPanel() || client.getGameState() != GameState.LOGGED_IN)
                {
                        return null;
                }

                panelComponent.getChildren().add(TitleComponent.builder()
                        .text("Dev Tools")
                        .color(Color.ORANGE)
                        .build());

                Player local = client.getLocalPlayer();
                if (local != null && local.getWorldLocation() != null)
                {
                        WorldPoint wp = local.getWorldLocation();
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("World")
                                .right(wp.getX() + "," + wp.getY() + "," + wp.getPlane())
                                .build());
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Region")
                                .right(wp.getRegionID() + " (" + wp.getRegionX() + "," + wp.getRegionY() + ")")
                                .build());
                        if (local.getLocalLocation() != null)
                        {
                                panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Scene")
                                        .right(local.getLocalLocation().getSceneX() + "," + local.getLocalLocation().getSceneY())
                                        .build());
                        }
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Anim")
                                .right(local.getAnimation() + " / " + local.getPoseAnimation() + " / " + local.getGraphic())
                                .build());
                }

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Camera")
                        .right(client.getCameraYaw() + " / " + client.getCameraPitch())
                        .build());
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("FPS")
                        .right(String.valueOf(client.getFPS()))
                        .build());

                Dimension real = client.getRealDimensions();
                if (real != null)
                {
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Client")
                                .right(real.width + "x" + real.height)
                                .build());
                }

                return super.render(graphics);
        }
}
