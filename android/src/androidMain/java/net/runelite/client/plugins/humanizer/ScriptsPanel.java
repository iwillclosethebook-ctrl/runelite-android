package net.runelite.client.plugins.humanizer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * One place to start and stop every auto script. "Start" switches the script's plugin on
 * (if it is off) and turns its automation on; "Stop" turns the automation off and leaves
 * the highlights running; "Off" switches the whole plugin off.
 */
class ScriptsPanel extends PluginPanel
{
        /** A script plugin and the config switch that runs its automation. */
        static final class Script
        {
                final String name;
                final String className;
                final String group;
                final String autoKey;
                final String blurb;

                Script(String name, String className, String group, String autoKey, String blurb)
                {
                        this.name = name;
                        this.className = className;
                        this.group = group;
                        this.autoKey = autoKey;
                        this.blurb = blurb;
                }
        }

        static final List<Script> SCRIPTS = new ArrayList<>();

        static
        {
                SCRIPTS.add(new Script("AutoFight", "net.runelite.client.plugins.autofight.AutoFightPlugin", "autofight", "autoAttack", "Attack, loot, eat, bank"));
                SCRIPTS.add(new Script("AutoFish", "net.runelite.client.plugins.autofish.AutoFishPlugin", "autofish", "autoFish", "Fish, drop"));
                SCRIPTS.add(new Script("AutoWoodcut", "net.runelite.client.plugins.autowoodcut.AutoWoodcutPlugin", "autowoodcut", "autoChop", "Chop, bank or drop"));
                SCRIPTS.add(new Script("AutoMine", "net.runelite.client.plugins.automine.AutoMinePlugin", "automine", "autoMine", "Mine, bank or drop"));
        }

        private final PluginManager pluginManager;
        private final ConfigManager configManager;
        private final List<Row> rows = new ArrayList<>();
        private final Timer refresh;

        private final class Row
        {
                final Script script;
                final JLabel state = new JLabel();
                final JButton startStop = new JButton();
                final JButton power = new JButton();

                Row(Script script)
                {
                        this.script = script;
                }
        }

        ScriptsPanel(PluginManager pluginManager, ConfigManager configManager)
        {
                this.pluginManager = pluginManager;
                this.configManager = configManager;

                JLabel title = new JLabel("Scripts");
                title.setForeground(Color.WHITE);
                title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
                add(title);

                JLabel hint = new JLabel("<html>Input feel for all scripts: Humanizer plugin settings.</html>");
                hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                add(hint);

                for (Script s : SCRIPTS)
                {
                        Row row = new Row(s);
                        rows.add(row);
                        add(buildRow(row));
                }

                add(buildLogRow());

                JButton stopAll = new JButton("Stop all");
                stopAll.addActionListener(e ->
                {
                        for (Row r : rows)
                        {
                                configManager.setConfiguration(r.script.group, r.script.autoKey, false);
                        }
                        update();
                });
                add(stopAll);

                refresh = new Timer(1000, e -> update());
                refresh.start();
                update();
        }

        private final JLabel logState = new JLabel();
        private final JButton logPower = new JButton();

        /** The always-on session log: on/off, size, Share (zip + share sheet), Clear. */
        private JPanel buildLogRow()
        {
                JPanel panel = new JPanel(new BorderLayout(4, 2));
                panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

                JLabel name = new JLabel("Session log");
                name.setForeground(Color.WHITE);
                JLabel blurb = new JLabel("Everything, for troubleshooting");
                blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                JPanel text = new JPanel(new GridLayout(3, 1));
                text.setOpaque(false);
                text.add(name);
                text.add(blurb);
                text.add(logState);

                JButton share = new JButton("Share");
                JButton clear = new JButton("Clear");
                JPanel buttons = new JPanel(new GridLayout(3, 1, 0, 3));
                buttons.setOpaque(false);
                for (JButton b : new JButton[] {logPower, share, clear})
                {
                        b.setPreferredSize(new Dimension(64, 24));
                        buttons.add(b);
                }
                logPower.addActionListener(e ->
                {
                        boolean on = !"false".equals(configManager.getConfiguration("humanizer", "sessionLog"));
                        configManager.setConfiguration("humanizer", "sessionLog", !on);
                        update();
                });
                share.addActionListener(e -> SessionLog.INSTANCE.share());
                clear.addActionListener(e ->
                {
                        SessionLog.INSTANCE.clear();
                        update();
                });

                panel.add(text, BorderLayout.CENTER);
                panel.add(buttons, BorderLayout.EAST);
                return panel;
        }

        private JPanel buildRow(Row row)
        {
                JPanel panel = new JPanel(new BorderLayout(4, 2));
                panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

                JLabel name = new JLabel(row.script.name);
                name.setForeground(Color.WHITE);
                JLabel blurb = new JLabel(row.script.blurb);
                blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

                JPanel text = new JPanel(new GridLayout(3, 1));
                text.setOpaque(false);
                text.add(name);
                text.add(blurb);
                text.add(row.state);

                JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 3));
                buttons.setOpaque(false);
                row.startStop.setPreferredSize(new Dimension(64, 24));
                row.power.setPreferredSize(new Dimension(64, 24));
                buttons.add(row.startStop);
                buttons.add(row.power);

                row.startStop.addActionListener(e -> toggleAutomation(row));
                row.power.addActionListener(e -> togglePlugin(row));

                panel.add(text, BorderLayout.CENTER);
                panel.add(buttons, BorderLayout.EAST);
                return panel;
        }

        private Plugin find(Script s)
        {
                for (Plugin p : pluginManager.getPlugins())
                {
                        if (p.getClass().getName().equals(s.className))
                        {
                                return p;
                        }
                }
                return null;
        }

        private boolean automationOn(Script s)
        {
                return "true".equals(configManager.getConfiguration(s.group, s.autoKey));
        }

        private void toggleAutomation(Row row)
        {
                Plugin p = find(row.script);
                if (p == null)
                {
                        return;
                }
                boolean running = pluginManager.isPluginEnabled(p) && automationOn(row.script);
                if (running)
                {
                        configManager.setConfiguration(row.script.group, row.script.autoKey, false);
                }
                else
                {
                        setPluginEnabled(p, true);
                        configManager.setConfiguration(row.script.group, row.script.autoKey, true);
                }
                update();
        }

        private void togglePlugin(Row row)
        {
                Plugin p = find(row.script);
                if (p == null)
                {
                        return;
                }
                boolean on = pluginManager.isPluginEnabled(p);
                if (on)
                {
                        configManager.setConfiguration(row.script.group, row.script.autoKey, false);
                }
                setPluginEnabled(p, !on);
                update();
        }

        /** Same path as the plugin list toggle: enable + start (or stop) on the EDT. */
        private void setPluginEnabled(Plugin p, boolean enabled)
        {
                if (pluginManager.isPluginEnabled(p) == enabled)
                {
                        return;
                }
                SwingUtilities.invokeLater(() ->
                {
                        try
                        {
                                pluginManager.setPluginEnabled(p, enabled);
                                if (enabled)
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
                                android.util.Log.e("Scripts", "could not toggle " + p.getClass().getName(), ex);
                        }
                });
        }

        private void update()
        {
                boolean logOn = !"false".equals(configManager.getConfiguration("humanizer", "sessionLog"));
                logPower.setText(logOn ? "Off" : "On");
                logState.setText((logOn ? "Recording · " : "Off · ") + SessionLog.INSTANCE.summary());
                logState.setForeground(logOn ? new Color(0x6CCB5F) : ColorScheme.LIGHT_GRAY_COLOR);
                for (Row row : rows)
                {
                        Plugin p = find(row.script);
                        boolean installed = p != null;
                        boolean enabled = installed && pluginManager.isPluginEnabled(p);
                        boolean auto = enabled && automationOn(row.script);
                        row.state.setText(!installed ? "not installed" : auto ? "● Running" : enabled ? "○ On (paused)" : "Off");
                        row.state.setForeground(auto ? new Color(0, 200, 83) : enabled ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
                        row.startStop.setText(auto ? "Stop" : "Start");
                        row.startStop.setEnabled(installed);
                        row.power.setText(enabled ? "Off" : "On");
                        row.power.setEnabled(installed);
                }
        }

        void dispose()
        {
                refresh.stop();
        }
}
