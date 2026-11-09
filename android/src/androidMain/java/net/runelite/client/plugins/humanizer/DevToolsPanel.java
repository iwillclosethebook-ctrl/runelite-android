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
        private final javax.swing.JButton record = new javax.swing.JButton("● Record");
        private final javax.swing.JButton shareLast = new javax.swing.JButton("Share last recording");
        private final JLabel recState = new JLabel(" ");
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

                JLabel recTitle = new JLabel("<html>Recorder: everything within 20 tiles, every tick (NPCs and their tiles, "
                        + "projectiles, effects, items, objects, hits, sounds, varbits). Stop to save and share.</html>");
                recTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                add(recTitle);
                record.addActionListener(e -> toggleRecording());
                add(record);
                recState.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                add(recState);
                shareLast.addActionListener(e -> net.runelite.client.plugins.mobiledevtools.DevRecorder.INSTANCE.shareLast());
                add(shareLast);

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

        private void toggleRecording()
        {
                net.runelite.client.plugins.mobiledevtools.DevRecorder rec = net.runelite.client.plugins.mobiledevtools.DevRecorder.INSTANCE;
                if (rec.isRequested())
                {
                        rec.requestStop();
                }
                else
                {
                        setDevMode(true); // the recorder runs inside Mobile Dev Tools
                        rec.requestStart();
                }
                update();
        }

        private void update()
        {
                net.runelite.client.plugins.mobiledevtools.DevRecorder rec = net.runelite.client.plugins.mobiledevtools.DevRecorder.INSTANCE;
                record.setText(rec.isRequested() ? "■ Stop & share" : "● Record");
                if (rec.isRecording())
                {
                        long t = rec.elapsedSeconds();
                        recState.setText(String.format("Recording %d:%02d — %d KB", t / 60, t % 60, rec.bytesWritten() / 1024));
                }
                else if (rec.lastError() != null)
                {
                        recState.setText("Error: " + rec.lastError());
                }
                else
                {
                        recState.setText(rec.lastFile() == null ? " " : "Saved: " + new java.io.File(rec.lastFile()).getName());
                }
                shareLast.setEnabled(rec.lastFile() != null);
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
