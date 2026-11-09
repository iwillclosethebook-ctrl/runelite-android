package net.runelite.client.plugins.gamedata;

import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * What the player can walk to right now, from the live collision data of the loaded
 * scene (so closed doors and gates count as closed). One flood fill per tick is cheap
 * (104x104 tiles); every plugin uses it to skip targets it could not reach, instead of
 * tapping them and getting "I can't reach that!".
 */
public final class LocalReach
{
        private static final int N = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        private static final int E = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        private static final int S = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        private static final int W = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        private static final int FULL = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

        private final int[][] flags;
        private final boolean[][] reach;
        private final int width;
        private final int height;

        private LocalReach(int[][] flags, boolean[][] reach)
        {
                this.flags = flags;
                this.reach = reach;
                this.width = flags.length;
                this.height = flags[0].length;
        }

        /** Flood fill from the player's tile; null when the scene has no collision data. */
        public static LocalReach compute(Client client)
        {
                WorldView wv = client.getTopLevelWorldView();
                Player local = client.getLocalPlayer();
                if (wv == null || local == null)
                {
                        return null;
                }
                CollisionData[] maps = wv.getCollisionMaps();
                int plane = wv.getPlane();
                if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
                {
                        return null;
                }
                int[][] f = maps[plane].getFlags();
                LocalPoint lp = local.getLocalLocation();
                if (f == null || f.length == 0 || lp == null)
                {
                        return null;
                }
                int w = f.length;
                int h = f[0].length;
                int sx = lp.getSceneX();
                int sy = lp.getSceneY();
                if (sx < 0 || sy < 0 || sx >= w || sy >= h)
                {
                        return null;
                }

                boolean[][] reach = new boolean[w][h];
                int[] queue = new int[w * h];
                int head = 0;
                int tail = 0;
                reach[sx][sy] = true;
                queue[tail++] = sx * h + sy;
                while (head < tail)
                {
                        int cur = queue[head++];
                        int x = cur / h;
                        int y = cur % h;
                        int here = f[x][y];
                        if (y + 1 < h && !reach[x][y + 1] && (here & N) == 0 && (f[x][y + 1] & (S | FULL)) == 0)
                        {
                                reach[x][y + 1] = true;
                                queue[tail++] = x * h + y + 1;
                        }
                        if (y - 1 >= 0 && !reach[x][y - 1] && (here & S) == 0 && (f[x][y - 1] & (N | FULL)) == 0)
                        {
                                reach[x][y - 1] = true;
                                queue[tail++] = x * h + y - 1;
                        }
                        if (x + 1 < w && !reach[x + 1][y] && (here & E) == 0 && (f[x + 1][y] & (W | FULL)) == 0)
                        {
                                reach[x + 1][y] = true;
                                queue[tail++] = (x + 1) * h + y;
                        }
                        if (x - 1 >= 0 && !reach[x - 1][y] && (here & W) == 0 && (f[x - 1][y] & (E | FULL)) == 0)
                        {
                                reach[x - 1][y] = true;
                                queue[tail++] = (x - 1) * h + y;
                        }
                }
                return new LocalReach(f, reach);
        }

        /** True when the player can stand on this scene tile. */
        public boolean tile(int sx, int sy)
        {
                return sx >= 0 && sy >= 0 && sx < width && sy < height && reach[sx][sy];
        }

        /**
         * True when something occupying the scene rectangle (minX,minY)+(sizeX,sizeY) can be
         * interacted with: the player can stand on a tile next to it, with no wall or fence
         * between that tile and the thing. Standing on it also counts.
         */
        public boolean canInteract(int minX, int minY, int sizeX, int sizeY)
        {
                int maxX = minX + Math.max(1, sizeX) - 1;
                int maxY = minY + Math.max(1, sizeY) - 1;
                for (int x = minX; x <= maxX; x++)
                {
                        for (int y = minY; y <= maxY; y++)
                        {
                                if (tile(x, y))
                                {
                                        return true;
                                }
                                // neighbour south of the footprint, stepping north into it
                                if (y == minY && open(x, y - 1, N, x, y, S))
                                {
                                        return true;
                                }
                                if (y == maxY && open(x, y + 1, S, x, y, N))
                                {
                                        return true;
                                }
                                if (x == minX && open(x - 1, y, E, x, y, W))
                                {
                                        return true;
                                }
                                if (x == maxX && open(x + 1, y, W, x, y, E))
                                {
                                        return true;
                                }
                        }
                }
                return false;
        }

        /** Reachable neighbour (nx,ny) with no wall from it toward (tx,ty). Object blocks are ignored. */
        private boolean open(int nx, int ny, int nWall, int tx, int ty, int tWall)
        {
                if (!tile(nx, ny))
                {
                        return false;
                }
                return (flags[nx][ny] & nWall) == 0 && (flags[tx][ty] & tWall) == 0;
        }

        /** Convenience for a scene-anchored thing given its centre LocalPoint and size in tiles. */
        public boolean canInteractCentred(LocalPoint centre, int size)
        {
                if (centre == null)
                {
                        return false;
                }
                // An actor's local point is the centre of its footprint; step back to the
                // south-west tile (half a tile per extra tile of size).
                int sx = (centre.getX() - (size - 1) * 64) >> 7;
                int sy = (centre.getY() - (size - 1) * 64) >> 7;
                return canInteract(sx, sy, size, size);
        }
}
