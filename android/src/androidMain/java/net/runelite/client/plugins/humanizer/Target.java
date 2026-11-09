package net.runelite.client.plugins.humanizer;

import java.awt.Shape;
import net.runelite.api.MenuEntry;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Something a script wants the simulated finger to act on: an NPC, an object (tree, rock,
 * bank booth, door, staircase) or a ground item. See {@link Targets} for the factories.
 */
public interface Target
{
        /** The model's hull in real canvas space, or null when it cannot be projected. */
        Shape hull();

        LocalPoint localLocation();

        WorldPoint worldLocation();

        /** Height (local units) of the fallback aim point when there is no hull. */
        int aimHeight();

        /** True when a menu entry is an option on exactly this thing (not a look-alike next to it). */
        boolean matches(MenuEntry entry);

        /** Still there and still wanted. */
        boolean isLive();

        /** The underlying NPC / TileObject / item key, for identity comparisons. */
        Object raw();
}
