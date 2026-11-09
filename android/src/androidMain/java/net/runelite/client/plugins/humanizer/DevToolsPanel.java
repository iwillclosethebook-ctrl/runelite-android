package net.runelite.client.plugins.humanizer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/** Dev mode on/off (the Mobile Dev Tools plugin) and a switch for each of its overlays. */
class DevToolsPanel extends PluginPanel
{
        private static final String DEV_CLASS = "net.runelite.client.plugins.mobiledevtools.MobileDevToolsPlugin";
        private static final String GROUP = "mobiledevtools";

        private final PluginManager pluginManager;
        private final ConfigManager configManager;
        private final JCheckBox devMode = new JCheckBox("Dev mode");
        private final Map<String, JCheckBox> toggles = new LinkedHashMap<>();
        private final List<JCheckBox> all = new ArrayList<>();
        private final Timer refresh;
        private boolean updating;

        DevToolsPanel(PluginManager pluginManager, ConfigManager configManager)
        {
                this.pluginManager = pluginManager;
                this.configManager = configManager;

                JLabel title = new JLabel("Dev Tools");
                title.setForeground(Color.WHITE);
                add(title);

                style(devMode);
                devMode.addActionListener(e -> setDevMode(devMode.isSelected()));
                add(devMode);

                addToggle("showNpcs", "NPC labels");
                addToggle("showPlayers", "Player labels");
                addToggle("showGroundItems", "Ground items");
                addToggle("showGameObjects", "Game objects");
                addToggle("showWalls", "Walls");
                addToggle("showDecorations", "Decorations");
                addToggle("showGroundObjects", "Ground objects");
                addToggle("showGraphicsObjects", "Graphics objects");
                addToggle("showProjectiles", "Projectiles");
                addToggle("showInventoryIds", "Inventory item IDs");
                addToggle("showTileCoords", "Tile coordinates");
                addToggle("showTileFlags", "Tile flags");
                addToggle("showInfoPanel", "Info panel");
                addToggle("showIdsInMenus", "IDs in long-press menus");

                refresh = new Timer(1000, e -> update());
                refresh.start();
                update();
        }

        private void style(JCheckBox box)
        {
                box.setForeground(Color.WHITE);
                box.setBackground(ColorScheme.DARK_GRAY_COLOR);
                all.add(box);
        }

        private void addToggle(String key, String label)
        {
                JCheckBox box = new JCheckBox(label);
                style(box);
                box.addActionListener(e ->
                {
                        if (!updating)
                        {
                                configManager.setConfiguration(GROUP, key, box.isSelected());
                        }
                });
                toggles.put(key, box);
                add(box);
        }

        private Plugin devPlugin()
        {
                for (Plugin p : pluginManager.getPlugins())
                {
                        if (p.getClass().getName().equals(DEV_CLASS))
                        {
                                return p;
                        }
                }
                return null;
        }

        private void setDevMode(boolean on)
        {
                if (updating)
                {
                        return;
                }
                Plugin p = devPlugin();
                if (p == null || pluginManager.isPluginEnabled(p) == on)
                {
                        return;
                }
                SwingUtilities.invokeLater(() ->
                {
                        try
                        {
                                pluginManager.setPluginEnabled(p, on);
                                if (on)
                                {
                                        pluginManager.startPlugin(p);
                                }
                                else
                                {
                                        pluginManager.stopPlugin(p);
                                }
                        }
                        catch (Exception ex)
                        {
                                android.util.Log.e("DevTools", "could not toggle dev mode", ex);
                        }
                });
        }

        private void update()
        {
                updating = true;
                try
                {
                        Plugin p = devPlugin();
                        boolean on = p != null && pluginManager.isPluginEnabled(p);
                        devMode.setSelected(on);
                        devMode.setEnabled(p != null);
                        for (Map.Entry<String, JCheckBox> e : toggles.entrySet())
                        {
                                String v = configManager.getConfiguration(GROUP, e.getKey());
                                if (v != null)
                                {
                                        e.getValue().setSelected(Boolean.parseBoolean(v));
                                }
                                e.getValue().setEnabled(on);
                        }
                }
                finally
                {
                        updating = false;
                }
        }

        void dispose()
        {
                refresh.stop();
        }
}
