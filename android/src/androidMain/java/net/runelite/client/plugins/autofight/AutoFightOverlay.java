package net.runelite.client.plugins.autofight;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.Text;

class AutoFightOverlay extends OverlayPanel
{
	private final Client client;
	private final AutoFightPlugin plugin;
	private final AutoFightConfig config;

	@Inject
	AutoFightOverlay(Client client, AutoFightPlugin plugin, AutoFightConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Reset", "AutoFight counter", e -> plugin.resetCounter());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showCounter() || plugin.getTargetIds().isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("AutoFight")
			.color(config.targetColor())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Kills")
			.right(String.valueOf(plugin.getKills()))
			.build());

		double kph = plugin.getKillsPerHour();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Kills/hr")
			.right(kph > 0 ? String.format("%.0f", kph) : "-")
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Nearby")
			.right(String.valueOf(plugin.getMatchingCount()))
			.build());

		NPC current = plugin.getCurrentTarget();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Fighting")
			.right(current != null ? name(current) : "-")
			.rightColor(current != null ? config.targetColor() : Color.GRAY)
			.build());

		NPC next = plugin.getNextTarget();
		Player local = client.getLocalPlayer();
		String nextText = "-";
		if (next != null && local != null)
		{
			nextText = next.getWorldLocation().distanceTo(local.getWorldLocation()) + " tiles";
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Next")
			.right(nextText)
			.rightColor(next != null ? config.nextColor() : Color.GRAY)
			.build());

		return super.render(graphics);
	}

	private static String name(NPC npc)
	{
		String n = npc.getName();
		return n == null ? String.valueOf(npc.getId()) : Text.removeTags(n);
	}
}
