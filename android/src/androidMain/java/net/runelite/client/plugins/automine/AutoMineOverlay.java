package net.runelite.client.plugins.automine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.humanizer.AreaRenderer;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class AutoMineOverlay extends OverlayPanel
{
        private final Client client;
        private final AutoMinePlugin plugin;
        private final AutoMineConfig config;

        @Inject
        AutoMineOverlay(Client client, AutoMinePlugin plugin, AutoMineConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.TOP_LEFT);
                addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Reset", "AutoMine counter", e -> plugin.resetCounter());
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                if (!config.showCounter())
                {
                        return null;
                }

                panelComponent.getChildren().add(TitleComponent.builder()
                        .text("AutoMine")
                        .color(config.currentColor())
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Ores")
                        .right(String.valueOf(plugin.getOres()))
                        .build());

                double lph = plugin.getOresPerHour();
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Ores/hr")
                        .right(lph > 0 ? String.format("%.0f", lph) : "-")
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Rocks")
                        .right(plugin.getRockCount() + " " + config.rock().getObjectName().toLowerCase())
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Status")
                        .right(plugin.getStatus())
                        .rightColor(plugin.getCurrentRock() != null ? config.currentColor() : Color.WHITE)
                        .build());

                GameObject next = plugin.getNextRock();
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

                if (config.autoMine())
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
                                        .right(String.format("%d:%02d", switchIn / 60, switchIn % 60))
                                        .build());
                        }
                }

                String center = plugin.getCenterLabel();
                if (center != null)
                {
                        panelComponent.getChildren().add(LineComponent.builder()
                                .left("Area")
                                .right(center)
                                .build());
                }

                if (plugin.getRockCount() == 0)
                {
                        String spot = plugin.getNearestKnownSpot();
                        if (spot != null)
                        {
                                panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Known spot")
                                        .right(spot)
                                        .rightColor(Color.LIGHT_GRAY)
                                        .build());
                        }
                }

                return super.render(graphics);
        }
}

/**
 * Highlights matching rocks in the world: the rock being mined, the next one, and the
 * rest, using the hull / tile / name style options.
 */
class AutoMineRockOverlay extends Overlay
{
        private final Client client;
        private final AutoMinePlugin plugin;
        private final AutoMineConfig config;

        @Inject
        AutoMineRockOverlay(Client client, AutoMinePlugin plugin, AutoMineConfig config)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.DYNAMIC);
                setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D graphics)
        {
                GameObject current = plugin.getCurrentRock();
                GameObject next = plugin.getNextRock();
                Stroke stroke = new BasicStroke((float) config.borderWidth());
                for (GameObject rock : plugin.getRocks())
                {
                        Color color;
                        if (rock == current)
                        {
                                color = config.currentColor();
                        }
                        else if (config.highlightNext() && rock == next)
                        {
                                color = config.nextColor();
                        }
                        else
                        {
                                color = config.otherColor();
                        }
                        renderRock(graphics, rock, color, stroke);
                }
                return null;
        }

        private void renderRock(Graphics2D graphics, GameObject rock, Color color, Stroke stroke)
        {
                if (config.hull())
                {
                        Shape hull = rock.getConvexHull();
                        if (hull != null)
                        {
                                OverlayUtil.renderPolygon(graphics, hull, color, config.fillColor(), stroke);
                        }
                }
                if (config.tile() || config.trueTile())
                {
                        LocalPoint lp = rock.getLocalLocation();
                        Polygon poly = lp == null ? null : Perspective.getCanvasTileAreaPoly(client, lp, Math.max(1, rock.sizeX()));
                        if (poly != null)
                        {
                                OverlayUtil.renderPolygon(graphics, poly, color, config.fillColor(), stroke);
                        }
                }
                if (config.name())
                {
                        ObjectComposition comp = client.getObjectDefinition(rock.getId());
                        String name = comp == null ? null : comp.getName();
                        if (name != null)
                        {
                                Point text = rock.getCanvasTextLocation(graphics, name, 120);
                                if (text != null)
                                {
                                        OverlayUtil.renderTextLocation(graphics, text, name, color);
                                }
                        }
                }
        }
}

/**
 * Draws the area in world space: the ring of tiles at the configured radius around
 * the centre tile, plus the centre tile itself. The ring is only drawn on the centre's
 * plane, and is thinned out for very large radii so phones are not drowned in polygons.
 */
class AutoMineHuntOverlay extends Overlay
{
        private final Client client;
        private final AutoMinePlugin plugin;
        private final AutoMineConfig config;

        @Inject
        AutoMineHuntOverlay(Client client, AutoMinePlugin plugin, AutoMineConfig config)
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
                AreaRenderer.render(graphics, client, plugin.getHuntCenterWorld(), config.huntRadius(),
                        config.nextColor(), plugin.getCenterLabel() != null && !plugin.getCenterLabel().startsWith("self"));
                return null;
        }

}
