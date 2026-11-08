package net.runelite.client.plugins.autofish;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.Text;

class AutoFishOverlay extends OverlayPanel
{
        private final Client client;
        private final AutoFishPlugin plugin;
        private final AutoFishConfig config;

        @Inject
        AutoFishOverlay(Client client, AutoFishPlugin plugin, AutoFishConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.TOP_LEFT);
                addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Reset", "AutoFish counter", e -> plugin.resetCounter());
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (!config.showCounter())
                {
                        return null;
                }

                panelComponent.getChildren().add(TitleComponent.builder()
                        .text("AutoFish")
                        .color(config.currentColor())
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Catches")
                        .right(String.valueOf(plugin.getCatches()))
                        .build());

                double cph = plugin.getCatchesPerHour();
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Catches/hr")
                        .right(cph > 0 ? String.format("%.0f", cph) : "-")
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Spots")
                        .right(String.valueOf(plugin.getSpotCount()))
                        .build());

                NPC current = plugin.getCurrentSpot();
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Fishing")
                        .right(current != null ? name(current) : "-")
                        .rightColor(current != null ? config.currentColor() : Color.GRAY)
                        .build());

                NPC next = plugin.getNextSpot();
                Player local = client.getLocalPlayer();
                String nextText = "-";
                if (next != null && local != null)
                {
                        nextText = next.getWorldLocation().distanceTo(local.getWorldLocation()) + " tiles";
                }
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Next")
                        .right(nextText)
                        .rightColor(next != null ? config.nextColor() : Color.GRAY)
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Inventory")
                        .right(plugin.getInventoryUsage())
                        .build());

                if (config.autoFish())
                {
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Method")
                                .right(plugin.getPersonaLabel())
                                .rightColor(config.nextColor())
                                .build());

                        long switchIn = plugin.getMethodSwitchInSeconds();
                        if (switchIn >= 0)
                        {
                                panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Style change")
                                        .right(formatDuration(switchIn))
                                        .build());
                        }
                }

                String center = plugin.getCenterLabel();
                if (center != null)
                {
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Centre")
                                .right(center)
                                .build());
                }

                return super.render(graphics);
        }

        private static String formatDuration(long seconds)
        {
                return String.format("%d:%02d", seconds / 60, seconds % 60);
        }

        private static String name(NPC npc)
        {
                String n = npc.getName();
                return n == null ? String.valueOf(npc.getId()) : Text.removeTags(n);
        }
}

/**
 * Draws the heat range in world space: the ring of tiles at the configured radius around
 * the centre tile, plus the centre tile itself. The ring is only drawn on the centre's
 * plane, and is thinned out for very large radii so phones are not drowned in polygons.
 */
class AutoFishHuntOverlay extends Overlay
{
        private final Client client;
        private final AutoFishPlugin plugin;
        private final AutoFishConfig config;

        @Inject
        AutoFishHuntOverlay(Client client, AutoFishPlugin plugin, AutoFishConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.DYNAMIC);
                setLayer(OverlayLayer.UNDER_WIDGETS);
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (!config.useHuntRange())
                {
                        return null;
                }

                WorldPoint centre = plugin.getHuntCenterWorld();
                if (centre == null)
                {
                        return null;
                }

                WorldView wv = client.getTopLevelWorldView();
                if (wv == null || wv.getPlane() != centre.getPlane())
                {
                        return null;
                }

                int radius = Math.min(config.huntRadius(), 32);
                Color other = config.otherColor();
                Color ringEdge = new Color(other.getRed(), other.getGreen(), other.getBlue(), Math.max(40, other.getAlpha() / 2));
                Color ringFill = new Color(other.getRed(), other.getGreen(), other.getBlue(), 22);

                // Thin the ring to every other tile once it gets big, so rendering stays cheap.
                int stride = radius > 12 ? 2 : 1;
                for (int dx = -radius; dx <= radius; dx++)
                {
                        for (int dy = -radius; dy <= radius; dy++)
                        {
                                if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
                                {
                                        continue;
                                }
                                if (stride == 2 && ((dx + dy) & 1) != 0)
                                {
                                        continue;
                                }
                                drawTile(graphics, wv, centre.getX() + dx, centre.getY() + dy, ringFill, ringEdge);
                        }
                }

                Color next = config.nextColor();
                Color centreEdge = new Color(next.getRed(), next.getGreen(), next.getBlue(), Math.max(120, next.getAlpha()));
                Color centreFill = new Color(next.getRed(), next.getGreen(), next.getBlue(), 70);
                drawTile(graphics, wv, centre.getX(), centre.getY(), centreFill, centreEdge);

                return null;
        }

        private void drawTile(Graphics2D graphics, WorldView wv, int worldX, int worldY, Color fill, Color edge)
        {
                LocalPoint lp = LocalPoint.fromWorld(wv, worldX, worldY);
                if (lp == null)
                {
                        return;
                }
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null)
                {
                        return;
                }
                if (fill != null)
                {
                        graphics.setColor(fill);
                        graphics.fillPolygon(poly);
                }
                if (edge != null)
                {
                        graphics.setColor(edge);
                        graphics.drawPolygon(poly);
                }
        }
}
