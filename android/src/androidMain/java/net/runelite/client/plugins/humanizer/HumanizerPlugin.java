package net.runelite.client.plugins.humanizer;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Home of the shared input settings every auto script follows ({@link HumanizerConfig}),
 * and of the two side-panel icons: <b>Scripts</b> (start / stop every auto script from one
 * place) and <b>Dev Tools</b> (dev mode on/off and its overlays).
 */
@PluginDescriptor(
        name = "Humanizer",
        description = "Shared input feel for all auto scripts (reactions, misclicks, input styles, camera, walking), plus the Scripts and Dev Tools side-panel icons",
        tags = {"humanizer", "scripts", "input", "camera", "dev", "mobile", "script"},
        enabledByDefault = true
)
public class HumanizerPlugin extends Plugin
{
        @Inject
        private ClientToolbar clientToolbar;

        @Inject
        private PluginManager pluginManager;

        @Inject
        private ConfigManager configManager;

        private ScriptsPanel scriptsPanel;
        private DevToolsPanel devToolsPanel;
        private NavigationButton scriptsButton;
        private NavigationButton devButton;

        @Provides
        HumanizerConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(HumanizerConfig.class);
        }

        @Override
        protected void startUp()
        {
                scriptsPanel = new ScriptsPanel(pluginManager, configManager);
                devToolsPanel = new DevToolsPanel(pluginManager, configManager);

                BufferedImage scriptsIcon = ImageUtil.loadImageResource(HumanizerPlugin.class, "scripts_icon.png");
                BufferedImage devIcon = ImageUtil.loadImageResource(HumanizerPlugin.class, "devtools_icon.png");

                scriptsButton = NavigationButton.builder()
                        .tooltip("Scripts")
                        .icon(scriptsIcon)
                        .priority(1)
                        .panel(scriptsPanel)
                        .build();
                devButton = NavigationButton.builder()
                        .tooltip("Dev Tools")
                        .icon(devIcon)
                        .priority(2)
                        .panel(devToolsPanel)
                        .build();
                clientToolbar.addNavigation(scriptsButton);
                clientToolbar.addNavigation(devButton);
        }

        @Override
        protected void shutDown()
        {
                clientToolbar.removeNavigation(scriptsButton);
                clientToolbar.removeNavigation(devButton);
                scriptsPanel.dispose();
                devToolsPanel.dispose();
        }
}
