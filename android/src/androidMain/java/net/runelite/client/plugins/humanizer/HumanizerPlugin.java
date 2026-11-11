package net.runelite.client.plugins.humanizer;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;
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

        @Inject
        private Client client;

        @Inject
        private HumanizerConfig config;

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
                applyLogConfig();
                SessionLog.INSTANCE.line("GAME", "client started (Humanizer up)");
        }

        @Override
        protected void shutDown()
        {
                clientToolbar.removeNavigation(scriptsButton);
                clientToolbar.removeNavigation(devButton);
                scriptsPanel.dispose();
                devToolsPanel.dispose();
                SessionLog.INSTANCE.line("GAME", "Humanizer stopped");
                SessionLog.INSTANCE.flush();
        }

        // ---------------------------------------------------------------- session log feed

        private int tick;
        private String lastState;
        private final int[] lastXp = new int[net.runelite.api.Skill.values().length];
        private int[] lastInv;

        private void applyLogConfig()
        {
                SessionLog.INSTANCE.configure(config.sessionLog(), config.sessionLogMoves(), config.sessionLogMaxMb());
        }

        @Subscribe
        public void onGameTick(GameTick e)
        {
                SessionLog log = SessionLog.INSTANCE;
                log.setTick(++tick);
                if (!log.isEnabled())
                {
                        return;
                }
                Player me = client.getLocalPlayer();
                if (me != null)
                {
                        WorldPoint wp = me.getWorldLocation();
                        Actor target = me.getInteracting();
                        String t = target == null ? "-" : (target instanceof NPC
                                ? ((NPC) target).getName() + "#" + ((NPC) target).getIndex() + "@" + target.getWorldLocation()
                                : "player");
                        String state = "at " + (wp == null ? "?" : wp.getX() + "," + wp.getY() + "," + wp.getPlane())
                                + " anim " + me.getAnimation() + " pose " + me.getPoseAnimation()
                                + " target " + t
                                + " hp " + client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) + "/" + client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)
                                + " pray " + client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER)
                                + " run " + (client.getEnergy() / 100) + "%"
                                + " tab " + GameHelpers.openTab(client)
                                + (client.isMenuOpen() ? " MENU-OPEN" : "");
                        if (!state.equals(lastState))
                        {
                                log.line("TICK", state);
                                lastState = state;
                        }
                }
                log.pollStatuses();
        }

        @Subscribe
        public void onMenuOptionClicked(MenuOptionClicked e)
        {
                SessionLog.INSTANCE.line("MENU", Text.removeTags(e.getMenuOption()) + " | " + Text.removeTags(e.getMenuTarget())
                        + " | " + e.getMenuAction() + " id " + e.getId() + " p0 " + e.getParam0() + " p1 " + e.getParam1());
        }

        @Subscribe
        public void onChatMessage(ChatMessage e)
        {
                SessionLog.INSTANCE.line("CHAT", e.getType() + " " + (e.getName() == null || e.getName().isEmpty() ? "" : Text.removeTags(e.getName()) + ": ")
                        + Text.removeTags(e.getMessage()));
        }

        @Subscribe
        public void onStatChanged(StatChanged e)
        {
                int i = e.getSkill().ordinal();
                int prev = lastXp[i];
                lastXp[i] = e.getXp();
                if (prev > 0 && e.getXp() > prev)
                {
                        SessionLog.INSTANCE.line("XP", e.getSkill().getName() + " +" + (e.getXp() - prev) + " = " + e.getXp() + " (lvl " + e.getLevel() + ")");
                }
        }

        @Subscribe
        public void onItemContainerChanged(ItemContainerChanged e)
        {
                if (e.getContainerId() != InventoryID.INVENTORY.getId())
                {
                        return;
                }
                Item[] items = e.getItemContainer().getItems();
                int[] now = new int[28 * 2];
                for (int i = 0; i < Math.min(28, items.length); i++)
                {
                        now[i * 2] = items[i].getId();
                        now[i * 2 + 1] = items[i].getQuantity();
                }
                if (lastInv != null)
                {
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < 28; i++)
                        {
                                if (now[i * 2] != lastInv[i * 2] || now[i * 2 + 1] != lastInv[i * 2 + 1])
                                {
                                        b.append(" s").append(i).append(':').append(lastInv[i * 2]).append('x').append(lastInv[i * 2 + 1])
                                                .append("->").append(now[i * 2]).append('x').append(now[i * 2 + 1]);
                                }
                        }
                        if (b.length() > 0)
                        {
                                SessionLog.INSTANCE.line("INV", "free " + GameHelpers.freeSlots(client) + ":" + b);
                        }
                }
                lastInv = now;
        }

        @Subscribe
        public void onWidgetLoaded(WidgetLoaded e)
        {
                SessionLog.INSTANCE.line("WIDGET", "opened group " + e.getGroupId());
        }

        @Subscribe
        public void onWidgetClosed(WidgetClosed e)
        {
                SessionLog.INSTANCE.line("WIDGET", "closed group " + e.getGroupId());
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged e)
        {
                SessionLog.INSTANCE.line("GAME", "state " + e.getGameState());
        }

        @Subscribe
        public void onActorDeath(ActorDeath e)
        {
                Actor a = e.getActor();
                if (a == client.getLocalPlayer())
                {
                        SessionLog.INSTANCE.line("GAME", "YOU DIED at " + a.getWorldLocation());
                }
                else if (a instanceof NPC)
                {
                        SessionLog.INSTANCE.line("GAME", "died: " + ((NPC) a).getName() + "#" + ((NPC) a).getIndex() + " at " + a.getWorldLocation());
                }
        }

        @Subscribe
        public void onHitsplatApplied(HitsplatApplied e)
        {
                Actor a = e.getActor();
                if (a == client.getLocalPlayer())
                {
                        SessionLog.INSTANCE.line("GAME", "hit on you: " + e.getHitsplat().getAmount() + " (type " + e.getHitsplat().getHitsplatType() + ")");
                }
                else if (e.getHitsplat().isMine() && a instanceof NPC)
                {
                        SessionLog.INSTANCE.line("GAME", "you hit " + ((NPC) a).getName() + "#" + ((NPC) a).getIndex() + ": " + e.getHitsplat().getAmount());
                }
        }

        @Subscribe
        public void onConfigChanged(ConfigChanged e)
        {
                if ("humanizer".equals(e.getGroup()) && e.getKey().startsWith("sessionLog"))
                {
                        applyLogConfig();
                }
                String g = e.getGroup();
                if ("humanizer".equals(g) || "autofight".equals(g) || "autofish".equals(g) || "autowoodcut".equals(g)
                        || "automine".equals(g) || "autoagility".equals(g) || "autotithe".equals(g) || "mobiledevtools".equals(g))
                {
                        SessionLog.INSTANCE.line("CONFIG", g + "." + e.getKey() + " = " + e.getNewValue());
                }
        }
}
