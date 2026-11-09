package net.runelite.client.plugins.humanizer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws a script's work area in the world: the square border (traced tile edge by tile
 * edge at ground height, so it follows hills and stairs), a light wash over the inside,
 * and the centre tile with a label. Targets outside this square are never picked.
 */
public final class AreaRenderer
{
        private AreaRenderer()
        {
        }

        /**
         * @param fixed true when the centre is a marked tile; false when the area follows the player
         */
        public static void render(Graphics2D g, Client client, WorldPoint centre, int radius, Color color, boolean fixed)
        {
                if (centre == null)
                {
                        return;
                }
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null || wv.getPlane() != centre.getPlane())
                {
                        return;
                }
                int r = Math.max(0, Math.min(radius, 40));
                int x0 = centre.getX() - r;
                int y0 = centre.getY() - r;
                int x1 = centre.getX() + r + 1; // tile corners: the square spans [x0, x1) x [y0, y1)
                int y1 = centre.getY() + r + 1;

                // Corner points around the border, counter-clockwise from the south-west corner.
                List<int[]> corners = new ArrayList<>();
                for (int x = x0; x < x1; x++)
                {
                        corners.add(new int[]{x, y0});
                }
                for (int y = y0; y < y1; y++)
                {
                        corners.add(new int[]{x1, y});
                }
                for (int x = x1; x > x0; x--)
                {
                        corners.add(new int[]{x, y1});
                }
                for (int y = y1; y > y0; y--)
                {
                        corners.add(new int[]{x0, y});
                }

                Point[] pts = new Point[corners.size()];
                boolean complete = true;
                for (int i = 0; i < pts.length; i++)
                {
                        pts[i] = cornerToCanvas(client, wv, corners.get(i)[0], corners.get(i)[1], centre.getPlane());
                        complete &= pts[i] != null;
                }

                Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 200);
                Color wash = new Color(color.getRed(), color.getGreen(), color.getBlue(), 18);
                if (complete)
                {
                        Polygon poly = new Polygon();
                        for (Point p : pts)
                        {
                                poly.addPoint(p.getX(), p.getY());
                        }
                        g.setColor(wash);
                        g.fillPolygon(poly);
                }
                Stroke old = g.getStroke();
                g.setStroke(new BasicStroke(2f));
                g.setColor(edge);
                for (int i = 0; i < pts.length; i++)
                {
                        Point a = pts[i];
                        Point b = pts[(i + 1) % pts.length];
                        if (a != null && b != null)
                        {
                                g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
                        }
                }
                g.setStroke(old);

                // The centre tile, strongly marked, with a label.
                LocalPoint lp = LocalPoint.fromWorld(wv, centre);
                if (lp != null)
                {
                        Polygon tile = Perspective.getCanvasTilePoly(client, lp);
                        if (tile != null)
                        {
                                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
                                g.fillPolygon(tile);
                                g.setColor(edge);
                                g.drawPolygon(tile);
                        }
                        String label = fixed ? "Area centre (r" + r + ")" : "Area follows you (r" + r + ")";
                        Point text = Perspective.getCanvasTextLocation(client, g, lp, label, 0);
                        if (text != null)
                        {
                                OverlayUtil.renderTextLocation(g, text, label, edge);
                        }
                }
        }

        /** Canvas point of a tile corner (world corner coordinates) at ground height, or null off-scene. */
        private static Point cornerToCanvas(Client client, WorldView wv, int worldX, int worldY, int plane)
        {
                // fromWorld gives the centre of the tile whose south-west corner is (worldX, worldY).
                LocalPoint c = LocalPoint.fromWorld(wv, worldX, worldY);
                if (c == null)
                {
                        return null;
                }
                LocalPoint corner = new LocalPoint(c.getX() - Perspective.LOCAL_HALF_TILE_SIZE,
                        c.getY() - Perspective.LOCAL_HALF_TILE_SIZE, wv);
                return Perspective.localToCanvas(client, corner, plane);
        }
}
