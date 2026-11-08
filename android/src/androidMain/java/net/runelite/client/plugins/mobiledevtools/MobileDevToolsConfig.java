package net.runelite.client.plugins.mobiledevtools;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(MobileDevToolsConfig.GROUP)
public interface MobileDevToolsConfig extends Config
{
        String GROUP = "mobiledevtools";

        String NPCS_KEY = "showNpcs";
        String PLAYERS_KEY = "showPlayers";
        String GROUND_ITEMS_KEY = "showGroundItems";
        String GAME_OBJECTS_KEY = "showGameObjects";
        String WALLS_KEY = "showWalls";
        String DECORATIONS_KEY = "showDecorations";
        String GROUND_OBJECTS_KEY = "showGroundObjects";
        String GRAPHICS_KEY = "showGraphicsObjects";
        String PROJECTILES_KEY = "showProjectiles";
        String INVENTORY_KEY = "showInventoryIds";
        String TILE_COORDS_KEY = "showTileCoords";
        String TILE_FLAGS_KEY = "showTileFlags";
        String INFO_PANEL_KEY = "showInfoPanel";

        @ConfigSection(name = "Entities", description = "Which things get their IDs drawn in the world", position = 0)
        String entitiesSection = "entities";

        @ConfigSection(name = "Tiles", description = "Tile-level debugging", position = 1)
        String tilesSection = "tiles";

        @ConfigSection(name = "Panel & menus", description = "Info panel and menu options", position = 2)
        String panelSection = "panel";

        // ---- Entities ----

        @ConfigItem(
                keyName = NPCS_KEY,
                name = "NPC labels",
                description = "Draw name, ID and animation above every nearby NPC. Long-press the info panel to toggle this quickly.",
                section = entitiesSection,
                position = 0
        )
        default boolean showNpcs()
        {
                return true;
        }

        @ConfigItem(
                keyName = PLAYERS_KEY,
                name = "Player labels",
                description = "Draw name, animation, pose and graphic above every nearby player",
                section = entitiesSection,
                position = 1
        )
        default boolean showPlayers()
        {
                return false;
        }

        @ConfigItem(
                keyName = GROUND_ITEMS_KEY,
                name = "Ground items",
                description = "Draw name and ID of ground items on their tile",
                section = entitiesSection,
                position = 2
        )
        default boolean showGroundItems()
        {
                return true;
        }

        @ConfigItem(
                keyName = GAME_OBJECTS_KEY,
                name = "Game objects",
                description = "Draw name and ID of game objects (trees, rocks, banks, ...)",
                section = entitiesSection,
                position = 3
        )
        default boolean showGameObjects()
        {
                return true;
        }

        @ConfigItem(
                keyName = WALLS_KEY,
                name = "Walls",
                description = "Draw the ID of wall objects",
                section = entitiesSection,
                position = 4
        )
        default boolean showWalls()
        {
                return false;
        }

        @ConfigItem(
                keyName = DECORATIONS_KEY,
                name = "Decorations",
                description = "Draw the ID of decorative objects (flowers, paths, ...)",
                section = entitiesSection,
                position = 5
        )
        default boolean showDecorations()
        {
                return false;
        }

        @ConfigItem(
                keyName = GROUND_OBJECTS_KEY,
                name = "Ground objects",
                description = "Draw the ID of ground-scenery objects",
                section = entitiesSection,
                position = 6
        )
        default boolean showGroundObjects()
        {
                return false;
        }

        @ConfigItem(
                keyName = GRAPHICS_KEY,
                name = "Graphics objects",
                description = "Draw the ID of spot animations (gfx) on their tile",
                section = entitiesSection,
                position = 7
        )
        default boolean showGraphicsObjects()
        {
                return false;
        }

        @ConfigItem(
                keyName = PROJECTILES_KEY,
                name = "Projectiles",
                description = "Draw the ID of projectiles (arrows, spells, ...) where they fly",
                section = entitiesSection,
                position = 8
        )
        default boolean showProjectiles()
        {
                return false;
        }

        @ConfigItem(
                keyName = INVENTORY_KEY,
                name = "Inventory item IDs",
                description = "Draw the item ID inside each inventory slot (with the inventory open)",
                section = entitiesSection,
                position = 9
        )
        default boolean showInventoryIds()
        {
                return false;
        }

        @Range(min = 1, max = 32)
        @ConfigItem(
                keyName = "labelDistance",
                name = "Label distance (tiles)",
                description = "How far away entity labels and tile labels are drawn",
                section = entitiesSection,
                position = 10
        )
        default int labelDistance()
        {
                return 10;
        }

        // ---- Tiles ----

        @ConfigItem(
                keyName = TILE_COORDS_KEY,
                name = "Tile coordinates",
                description = "Draw the world x,y on every tile within the radius below",
                section = tilesSection,
                position = 0
        )
        default boolean showTileCoords()
        {
                return false;
        }

        @Range(min = 1, max = 15)
        @ConfigItem(
                keyName = "tileCoordRadius",
                name = "Coordinate radius (tiles)",
                description = "Tiles around you that get their world coordinates drawn",
                section = tilesSection,
                position = 1
        )
        default int tileCoordRadius()
        {
                return 5;
        }

        @ConfigItem(
                keyName = TILE_FLAGS_KEY,
                name = "Tile flags",
                description = "Draw bridge / visible-below / roof flags on special tiles (B/V/R letters)",
                section = tilesSection,
                position = 2
        )
        default boolean showTileFlags()
        {
                return false;
        }

        // ---- Panel & menus ----

        @ConfigItem(
                keyName = INFO_PANEL_KEY,
                name = "Info panel",
                description = "Show your world/region/scene position, animation, camera and FPS. Long-press it for quick toggles of everything above.",
                section = panelSection,
                position = 0
        )
        default boolean showInfoPanel()
        {
                return true;
        }

        @ConfigItem(
                keyName = "npcDetails",
                name = "NPC details",
                description = "Include animation and pose in the NPC labels",
                section = panelSection,
                position = 1
        )
        default boolean npcDetails()
        {
                return true;
        }

        @ConfigItem(
                keyName = "showIdsInMenus",
                name = "Show IDs in menus",
                description = "Append the ID to NPC, ground item and object entries in the long-press menu, so IDs can be read without labels",
                section = panelSection,
                position = 2
        )
        default boolean showIdsInMenus()
        {
                return false;
        }
}
