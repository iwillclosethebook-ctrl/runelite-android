package net.runelite.client.plugins.autofight;

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

class AutoFightOverlay extends OverlayPanel
{
        private final Client client;
        private final AutoFightPlugin plugin;
        private final AutoFightConfig config;

        @Inject
        AutoFightOverlay(Client client, AutoFightPlugin plugin, AutoFightConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.TOP_LEFT);
                addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Reset", "AutoFight counter", e -> plugin.resetCounter());
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (!config.showCounter() || plugin.getTargetIds().isEmpty())
                {
                        return null;
                }

                panelComponent.getChildren().add(TitleComponent.builder()
                        .text("AutoFight")
                        .color(config.targetColor())
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Kills")
                        .right(String.valueOf(plugin.getKills()))
                        .build());

                double kph = plugin.getKillsPerHour();
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Kills/hr")
                        .right(kph > 0 ? String.format("%.0f", kph) : "-")
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Nearby")
                        .right(String.valueOf(plugin.getMatchingCount()))
                        .build());

                NPC current = plugin.getCurrentTarget();
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Fighting")
                        .right(current != null ? name(current) : "-")
                        .rightColor(current != null ? config.targetColor() : Color.GRAY)
                        .build());

                NPC next = plugin.getNextTarget();
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

                if (config.autoAttack())
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

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Status")
                        .right(plugin.getStatus())
                        .build());

                if (config.autoEat() || config.bankEnabled())
                {
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Food")
                                .right(String.valueOf(plugin.getFoodCount()))
                                .rightColor(plugin.getFoodCount() == 0 ? Color.RED : Color.WHITE)
                                .build());
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
class AutoFightHuntOverlay extends Overlay
{
        private final Client client;
        private final AutoFightPlugin plugin;
        private final AutoFightConfig config;

        @Inject
        AutoFightHuntOverlay(Client client, AutoFightPlugin plugin, AutoFightConfig config)
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
                if (!config.useHuntRange() || plugin.getTargetIds().isEmpty())
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
