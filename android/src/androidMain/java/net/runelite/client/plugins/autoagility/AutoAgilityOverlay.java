package net.runelite.client.plugins.autoagility;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Status panel (course, obstacle, laps, XP) and an outline on the current obstacle. */
class AutoAgilityOverlay extends OverlayPanel
{
        private static final Color GREEN = new Color(0x6CCB5F);

        private final Client client;
        private final AutoAgilityPlugin plugin;

        @Inject
        AutoAgilityOverlay(Client client, AutoAgilityPlugin plugin)
        {
                super(plugin);
                this.client = client;
                this.plugin = plugin;
                setPosition(OverlayPosition.TOP_LEFT);
                setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
                AutoAgilityConfig config = plugin.getConfig();
                TileObject obstacle = plugin.getCurrentObstacle();
                if (obstacle != null)
                {
                        Shape hull = obstacle instanceof GameObject ? ((GameObject) obstacle).getConvexHull() : obstacle.getClickbox();
                        if (hull != null)
                        {
                                g.setColor(new Color(108, 203, 95, 60));
                                g.fill(hull);
                                g.setColor(GREEN);
                                g.setStroke(new BasicStroke(2f));
                                g.draw(hull);
                        }
                }

                panelComponent.getChildren().add(TitleComponent.builder().text("AutoAgility").color(GREEN).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Course").right(config.course().toString()).build());
                int idx = plugin.getObstacleIndex();
                panelComponent.getChildren().add(LineComponent.builder().left("Obstacle")
                        .right(idx < 0 ? "-" : (idx + 1) + "/" + config.course().obstacles.size()).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Laps").right(String.valueOf(plugin.getLaps())).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Agility XP")
                        .right(String.format("%,d", client.getSkillExperience(Skill.AGILITY))).build());
                panelComponent.getChildren().add(LineComponent.builder().left("Status").right(plugin.getStatus()).build());
                return super.render(g);
        }
}
