package net.runelite.client.plugins.autotithe;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Status panel and patch outlines (empty grey, dry orange, watered blue, ripe green). */
class AutoTitheOverlay extends OverlayPanel
{
        private static final Color GREEN = new Color(0x6CCB5F);

        private final AutoTithePlugin plugin;

        @Inject
        AutoTitheOverlay(AutoTithePlugin plugin)
        {
                super(plugin);
                this.plugin = plugin;
                setPosition(OverlayPosition.TOP_LEFT);
                setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
                if (plugin.getConfig().highlight())
                {
                        for (AutoTithePlugin.Patch p : plugin.getPatches())
                        {
                                if (p.obj == null)
                                {
                                        continue;
                                }
                                Shape hull = p.obj.getConvexHull();
                                if (hull == null)
                                {
                                        continue;
                                }
                                Color c = plugin.isRipe(p) ? GREEN
                                        : plugin.isWateredPatch(p) ? new Color(0x4FA3FF)
                                        : plugin.isDryPatch(p) ? new Color(0xFFA040)
                                        : new Color(0x9E9E9E);
                                g.setColor(c);
                                g.setStroke(new BasicStroke(1.5f));
                                g.draw(hull);
                        }
                }
                panelComponent.getChildren().add(TitleComponent.builder().text("AutoTithe").color(GREEN).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Status").right(plugin.getStatus()).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Fruit").right(String.valueOf(plugin.getFruits())).build());
                double ph = plugin.getFruitsPerHour();
                panelComponent.getChildren().add(LineComponent.builder().left("Fruit/hr").right(ph > 0 ? String.format("%.0f", ph) : "-").build());
                return super.render(g);
        }
}
