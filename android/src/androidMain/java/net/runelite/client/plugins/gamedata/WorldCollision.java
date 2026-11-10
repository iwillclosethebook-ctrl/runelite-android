/*
 * Reader for the ShortestPath collision map (collision-map.zip), as shipped with the
 * ShortestPath plugin by Skretzo and the Microbot project.
 *
 * Copyright (c) 2022, Skretzo; Microbot contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gamedata;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Whole-world walkability: for every tile, whether you can step north and whether you can
 * step east (the other directions are read from the neighbour). Loaded once, in the
 * background, the first time anything asks for it.
 */
public final class WorldCollision
{
        private static final String TAG = "WorldCollision";
        private static final int REGION_SIZE = 64;
        private static final int FLAG_COUNT = 2;

        private static volatile WorldCollision instance;
        private static volatile boolean loading;
        private static volatile boolean failed;

        /** True when the map could not be loaded (callers fall back to straight-line walking). */
        public static boolean failed()
        {
                return failed;
        }

        private final Map<Integer, BitSet> regions;
        private final Map<Integer, Integer> planeCounts;

        private WorldCollision(Map<Integer, BitSet> regions, Map<Integer, Integer> planeCounts)
        {
                this.regions = regions;
                this.planeCounts = planeCounts;
        }

        /** The loaded map, or null while it is still loading (starts the load on first call). */
        public static WorldCollision get()
        {
                WorldCollision w = instance;
                if (w != null)
                {
                        return w;
                }
                startLoading();
                return null;
        }

        public static synchronized void startLoading()
        {
                if (instance != null || loading || failed)
                {
                        return;
                }
                loading = true;
                Thread t = new Thread(() ->
                {
                        try
                        {
                                instance = load();
                        }
                        catch (Throwable e)
                        {
                                Log.e(TAG, "could not load collision map", e);
                                failed = true;
                        }
                        finally
                        {
                                loading = false;
                        }
                }, "WorldCollision-load");
                t.setDaemon(true);
                t.start();
        }

        private static WorldCollision load() throws IOException
        {
                Map<Integer, BitSet> regions = new HashMap<>();
                Map<Integer, Integer> planes = new HashMap<>();
                long start = System.currentTimeMillis();
                InputStream raw = WorldCollision.class.getResourceAsStream("collision-map.zip");
                if (raw == null)
                {
                        throw new IOException("collision-map.zip missing from resources");
                }
                try (ZipInputStream in = new ZipInputStream(raw))
                {
                        ZipEntry entry;
                        byte[] buf = new byte[8192];
                        while ((entry = in.getNextEntry()) != null)
                        {
                                String[] n = entry.getName().split("_");
                                if (n.length < 2)
                                {
                                        continue;
                                }
                                int rx = Integer.parseInt(n[0].trim());
                                int ry = Integer.parseInt(n[1].trim());
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                int r;
                                while ((r = in.read(buf)) > 0)
                                {
                                        out.write(buf, 0, r);
                                }
                                BitSet bits = BitSet.valueOf(out.toByteArray());
                                int scale = REGION_SIZE * REGION_SIZE * FLAG_COUNT;
                                int key = key(rx, ry);
                                regions.put(key, bits);
                                planes.put(key, (bits.size() + scale - 1) / scale);
                        }
                }
                Log.i(TAG, "loaded " + regions.size() + " regions in " + (System.currentTimeMillis() - start) + "ms");
                return new WorldCollision(regions, planes);
        }

        private static int key(int regionX, int regionY)
        {
                return (regionX & 0xFFFF) | ((regionY & 0xFFFF) << 16);
        }

        private boolean flag(int x, int y, int z, int flag)
        {
                if (x < 0 || y < 0 || z < 0)
                {
                        return false;
                }
                int k = key(x / REGION_SIZE, y / REGION_SIZE);
                BitSet bits = regions.get(k);
                if (bits == null)
                {
                        return false;
                }
                Integer pc = planeCounts.get(k);
                if (pc == null || z >= pc)
                {
                        return false;
                }
                int lx = x % REGION_SIZE;
                int ly = y % REGION_SIZE;
                return bits.get((z * REGION_SIZE * REGION_SIZE + ly * REGION_SIZE + lx) * FLAG_COUNT + flag);
        }

        public boolean hasRegion(int x, int y)
        {
                return regions.containsKey(key(x / REGION_SIZE, y / REGION_SIZE));
        }

        public boolean n(int x, int y, int z)
        {
                return flag(x, y, z, 0);
        }

        public boolean e(int x, int y, int z)
        {
                return flag(x, y, z, 1);
        }

        public boolean s(int x, int y, int z)
        {
                return n(x, y - 1, z);
        }

        public boolean w(int x, int y, int z)
        {
                return e(x - 1, y, z);
        }

        /** True when a single step from (x,y,z) by (dx,dy) is allowed (diagonals need both sides clear). */
        public boolean canStep(int x, int y, int z, int dx, int dy)
        {
                if (dx == 0 && dy == 1)
                {
                        return n(x, y, z);
                }
                if (dx == 0 && dy == -1)
                {
                        return s(x, y, z);
                }
                if (dx == 1 && dy == 0)
                {
                        return e(x, y, z);
                }
                if (dx == -1 && dy == 0)
                {
                        return w(x, y, z);
                }
                if (dx == 1 && dy == 1)
                {
                        return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
                }
                if (dx == -1 && dy == 1)
                {
                        return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
                }
                if (dx == 1 && dy == -1)
                {
                        return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
                }
                if (dx == -1 && dy == -1)
                {
                        return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
                }
                return false;
        }
}
