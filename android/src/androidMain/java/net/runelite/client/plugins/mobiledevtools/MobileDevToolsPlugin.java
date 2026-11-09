package net.runelite.client.plugins.mobiledevtools;

import com.google.inject.Provides;
import java.awt.Color;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
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
                DevRecorder.INSTANCE.stopNow();
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

        // ---------------------------------------------------------------- recorder

        void toggleRecording()
        {
                if (DevRecorder.INSTANCE.isRequested())
                {
                        DevRecorder.INSTANCE.requestStop();
                }
                else
                {
                        DevRecorder.INSTANCE.requestStart();
                }
        }

        @Subscribe
        public void onGameTick(GameTick event)
        {
                DevRecorder.INSTANCE.onTick(client);
        }

        @Subscribe
        public void onGameObjectSpawned(GameObjectSpawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "game", e.getGameObject(), true);
        }

        @Subscribe
        public void onGameObjectDespawned(GameObjectDespawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "game", e.getGameObject(), false);
        }

        @Subscribe
        public void onWallObjectSpawned(WallObjectSpawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "wall", e.getWallObject(), true);
        }

        @Subscribe
        public void onWallObjectDespawned(WallObjectDespawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "wall", e.getWallObject(), false);
        }

        @Subscribe
        public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "decor", e.getDecorativeObject(), true);
        }

        @Subscribe
        public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "decor", e.getDecorativeObject(), false);
        }

        @Subscribe
        public void onGroundObjectSpawned(GroundObjectSpawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "ground", e.getGroundObject(), true);
        }

        @Subscribe
        public void onGroundObjectDespawned(GroundObjectDespawned e)
        {
                DevRecorder.INSTANCE.onObject(client, "ground", e.getGroundObject(), false);
        }

        @Subscribe
        public void onItemSpawned(ItemSpawned e)
        {
                DevRecorder.INSTANCE.onItem(client, e.getTile(), e.getItem(), true);
        }

        @Subscribe
        public void onItemDespawned(ItemDespawned e)
        {
                DevRecorder.INSTANCE.onItem(client, e.getTile(), e.getItem(), false);
        }

        @Subscribe
        public void onHitsplatApplied(HitsplatApplied e)
        {
                DevRecorder.INSTANCE.onHitsplat(client, e.getActor(), e.getHitsplat().getAmount(), e.getHitsplat().getHitsplatType());
        }

        @Subscribe
        public void onActorDeath(ActorDeath e)
        {
                DevRecorder.INSTANCE.onDeath(client, e.getActor());
        }

        @Subscribe
        public void onChatMessage(ChatMessage e)
        {
                DevRecorder.INSTANCE.onText(String.valueOf(e.getType()), null, e.getMessage(), client);
        }

        @Subscribe
        public void onOverheadTextChanged(OverheadTextChanged e)
        {
                DevRecorder.INSTANCE.onText("OVERHEAD", e.getActor(), e.getOverheadText(), client);
        }

        @Subscribe
        public void onSoundEffectPlayed(SoundEffectPlayed e)
        {
                DevRecorder.INSTANCE.onSound(client, e.getSoundId(), e.getSource(), -1, -1, false);
        }

        @Subscribe
        public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed e)
        {
                DevRecorder.INSTANCE.onSound(client, e.getSoundId(), e.getSource(), e.getSceneX(), e.getSceneY(), true);
        }

        @Subscribe
        public void onVarbitChanged(VarbitChanged e)
        {
                DevRecorder.INSTANCE.onVarbit(e.getVarbitId(), e.getVarpId(), e.getValue());
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
