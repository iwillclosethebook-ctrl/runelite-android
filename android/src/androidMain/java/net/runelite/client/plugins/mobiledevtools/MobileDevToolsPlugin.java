package net.runelite.client.plugins.mobiledevtools;

import com.google.inject.Provides;
import java.awt.Color;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;

/**
 * A dev tools panel that works with fingers instead of a mouse: everything is drawn as
 * world-space labels on the game canvas, every category has its own toggle in the
 * config (and on a long-press of the info panel), and long-press menus can append IDs
 * to their entries so IDs can be read without any labels at all.
 *
 * <p>Covers the things microbot's dev tools show on a desktop: NPC/player labels with
 * IDs and animations, ground items, game objects, walls, decorations, ground objects,
 * graphics objects (gfx), projectiles, inventory item IDs, tile world coordinates,
 * and tile flags — plus an info panel with the player's world/region/scene position,
 * animation, camera state and FPS.</p>
 */
@PluginDescriptor(
        name = "Mobile Dev Tools",
        description = "Draw IDs of NPCs, objects, ground items, projectiles, graphics objects and tiles right in the world, with long-press toggles",
        tags = {"debug", "dev", "ids", "tiles", "projectiles", "mobile"},
        enabledByDefault = false
)
public class MobileDevToolsPlugin extends Plugin
{
        @Inject
        private Client client;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private MobileDevToolsOverlay overlay;

        @Inject
        private MobileDevToolsInfoOverlay infoOverlay;

        @Inject
        private ConfigManager configManager;

        @Inject
        private MobileDevToolsConfig config;

        @Provides
        MobileDevToolsConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(MobileDevToolsConfig.class);
        }

        @Override
        protected void startUp()
        {
                overlayManager.add(overlay);
                overlayManager.add(infoOverlay);
        }

        @Override
        protected void shutDown()
        {
                overlayManager.remove(overlay);
                overlayManager.remove(infoOverlay);
        }

        // ---------------------------------------------------------------- quick toggles

        /** Flip a boolean config item; called from the info panel's long-press menu. */
        private void toggle(String key, boolean current)
        {
                configManager.setConfiguration(MobileDevToolsConfig.GROUP, key, !current);
        }

        void toggleNpcs()
        {
                toggle(MobileDevToolsConfig.NPCS_KEY, config.showNpcs());
        }

        void togglePlayers()
        {
                toggle(MobileDevToolsConfig.PLAYERS_KEY, config.showPlayers());
        }

        void toggleGroundItems()
        {
                toggle(MobileDevToolsConfig.GROUND_ITEMS_KEY, config.showGroundItems());
        }

        void toggleGameObjects()
        {
                toggle(MobileDevToolsConfig.GAME_OBJECTS_KEY, config.showGameObjects());
        }

        void toggleWalls()
        {
                toggle(MobileDevToolsConfig.WALLS_KEY, config.showWalls());
        }

        void toggleDecorations()
        {
                toggle(MobileDevToolsConfig.DECORATIONS_KEY, config.showDecorations());
        }

        void toggleGroundObjects()
        {
                toggle(MobileDevToolsConfig.GROUND_OBJECTS_KEY, config.showGroundObjects());
        }

        void toggleGraphicsObjects()
        {
                toggle(MobileDevToolsConfig.GRAPHICS_KEY, config.showGraphicsObjects());
        }

        void toggleProjectiles()
        {
                toggle(MobileDevToolsConfig.PROJECTILES_KEY, config.showProjectiles());
        }

        void toggleInventoryIds()
        {
                toggle(MobileDevToolsConfig.INVENTORY_KEY, config.showInventoryIds());
        }

        void toggleTileCoords()
        {
                toggle(MobileDevToolsConfig.TILE_COORDS_KEY, config.showTileCoords());
        }

        void toggleTileFlags()
        {
                toggle(MobileDevToolsConfig.TILE_FLAGS_KEY, config.showTileFlags());
        }

        // ---------------------------------------------------------------- menu ID hints

        /**
         * Appends the ID to menu entries so a long-press on an NPC, a ground item or an
         * object shows its ID in the menu itself — the finger-friendly version of
         * "examine and look it up".
         */
        @Subscribe
        public void onMenuEntryAdded(MenuEntryAdded event)
        {
                if (!config.showIdsInMenus())
                {
                        return;
                }

                MenuEntry entry = event.getMenuEntry();
                if (entry == null)
                {
                        return;
                }

                NPC npc = entry.getNpc();
                if (npc != null)
                {
                        entry.setTarget(entry.getTarget() + ColorUtil.prependColorTag(" (ID " + npc.getId() + ")", Color.LIGHT_GRAY));
                        return;
                }

                MenuAction type = entry.getType();
                int id;
                if (type == MenuAction.EXAMINE_ITEM_GROUND)
                {
                        id = event.getIdentifier(); // ground item id
                }
                else if (type == MenuAction.EXAMINE_OBJECT)
                {
                        id = event.getIdentifier(); // object id
                }
                else
                {
                        return;
                }
                entry.setTarget(entry.getTarget() + ColorUtil.prependColorTag(" (ID " + id + ")", Color.LIGHT_GRAY));
        }
}
