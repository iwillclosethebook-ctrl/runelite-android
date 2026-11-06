package net.runelite.client.plugins.autofight;

import com.google.inject.Provides;
import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Highlights NPCs by ID for touch devices, marks your current and next target, counts kills,
 * and (optionally, both off by default) auto-attacks matching NPCs and auto-loots listed items
 * after a kill. Automation goes through the client's own menu actions, not screen taps.
 */
@PluginDescriptor(
	name = "AutoFight Highlight",
	description = "Highlight NPCs by ID, mark your current and next target, count kills, optional auto-attack and auto-loot",
	tags = {"npc", "highlight", "indicators", "combat", "kill", "counter", "mobile", "id", "loot", "attack"}
)
public class AutoFightPlugin extends Plugin
{
	private static final String TAG = "AF-Tag";
	private static final String UNTAG = "AF-Untag";

	/** Ticks after a counted kill during which the looter is allowed to act. */
	private static final int LOOT_WINDOW_TICKS = 15;

	/** Ticks to wait after any automated click before clicking again. */
	private static final int ACTION_COOLDOWN_TICKS = 2;

	/** Index in an NPC's action list maps to the matching NPC menu action. */
	private static final MenuAction[] NPC_OPTIONS = {
		MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION
	};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private AutoFightConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NpcOverlayService npcOverlayService;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AutoFightOverlay overlay;

	@Inject
	private Notifier notifier;

	@Inject
	private NpcUtil npcUtil;

	@Inject
	private ItemManager itemManager;

	private final Set<Integer> targetIds = new HashSet<>();
	private final List<NPC> matching = new ArrayList<>();

	private final Set<Integer> lootIds = new HashSet<>();
	private final Set<String> lootNames = new HashSet<>(); // stored lowercase

	private NPC currentTarget;
	private NPC nextTarget;

	private int kills;
	private Instant sessionStart;
	private int idleCount;
	private boolean idleNotified;
	private boolean lowHpNotified;

	private int lootTicksLeft;
	private int actionCooldown;

	private final Function<NPC, HighlightedNpc> highlighter = this::highlight;

	@Provides
	AutoFightConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AutoFightConfig.class);
	}

	@Override
	protected void startUp()
	{
		parseIds();
		parseLoot();
		overlayManager.add(overlay);
		npcOverlayService.registerHighlighter(highlighter);
		clientThread.invoke(this::rescan);
	}

	@Override
	protected void shutDown()
	{
		npcOverlayService.unregisterHighlighter(highlighter);
		overlayManager.remove(overlay);
		matching.clear();
		currentTarget = null;
		nextTarget = null;
		lootTicksLeft = 0;
		actionCooldown = 0;
		resetSession();
	}

	// ---------------------------------------------------------------- state

	private void parseIds()
	{
		targetIds.clear();
		for (String part : config.npcIds().split("[,\\s]+"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			try
			{
				targetIds.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// not a number; skip it
			}
		}
	}

	private void parseLoot()
	{
		lootIds.clear();
		lootNames.clear();
		for (String part : config.lootItems().split(","))
		{
			String p = part.trim();
			if (p.isEmpty())
			{
				continue;
			}
			try
			{
				lootIds.add(Integer.parseInt(p));
			}
			catch (NumberFormatException notAnId)
			{
				lootNames.add(p.toLowerCase(Locale.ROOT));
			}
		}
	}

	private boolean matches(NPC npc)
	{
		return npc != null && targetIds.contains(npc.getId());
	}

	private void rescan()
	{
		matching.clear();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (matches(npc))
				{
					matching.add(npc);
				}
			}
		}
		updateTargets();
		npcOverlayService.rebuild();
	}

	private void updateTargets()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			currentTarget = null;
			nextTarget = null;
			return;
		}

		NPC fighting = null;
		Actor interacting = local.getInteracting();
		if (interacting instanceof NPC && matches((NPC) interacting))
		{
			fighting = (NPC) interacting;
		}
		else
		{
			// Something attacking us counts too (e.g. aggressive NPCs, retaliation not started yet).
			for (NPC npc : matching)
			{
				if (npc.getInteracting() == local && !npcUtil.isDying(npc))
				{
					fighting = npc;
					break;
				}
			}
		}
		currentTarget = fighting;

		NPC best = null;
		int bestDist = Integer.MAX_VALUE;
		WorldPoint me = local.getWorldLocation();
		for (NPC npc : matching)
		{
			if (npc == currentTarget || npcUtil.isDying(npc))
			{
				continue;
			}
			if (config.skipOthersFights())
			{
				Actor other = npc.getInteracting();
				if (other instanceof Player && other != local)
				{
					continue;
				}
			}
			int d = npc.getWorldLocation().distanceTo(me);
			if (d < bestDist)
			{
				bestDist = d;
				best = npc;
			}
		}
		nextTarget = best;
	}

	private HighlightedNpc highlight(NPC npc)
	{
		if (!matches(npc))
		{
			return null;
		}

		Color color;
		if (npc == currentTarget)
		{
			color = config.targetColor();
		}
		else if (config.highlightNext() && npc == nextTarget)
		{
			color = config.nextColor();
		}
		else
		{
			color = config.otherColor();
		}

		return HighlightedNpc.builder()
			.npc(npc)
			.highlightColor(color)
			.fillColor(config.fillColor())
			.hull(config.hull())
			.tile(config.tile())
			.trueTile(config.trueTile())
			.outline(config.outline())
			.name(config.name())
			.nameOnMinimap(config.minimap())
			.borderWidth((float) config.borderWidth())
			.outlineFeather(config.outlineFeather())
			.render(n -> !config.hideDead() || !npcUtil.isDying(n))
			.build();
	}

	private void resetSession()
	{
		kills = 0;
		sessionStart = null;
		idleCount = 0;
		idleNotified = false;
		lowHpNotified = false;
	}

	// ---------------------------------------------------------------- automation

	/**
	 * Runs once per game tick on the client thread. At most one automated click per
	 * cooldown window; looting takes priority over attacking, and both only happen
	 * when we are not already in combat.
	 */
	private void automate()
	{
		if (lootTicksLeft > 0)
		{
			lootTicksLeft--;
		}
		if (actionCooldown > 0)
		{
			actionCooldown--;
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null || currentTarget != null || local.getInteracting() != null)
		{
			return;
		}

		if (config.autoLoot() && lootTicksLeft > 0 && tryLoot(local))
		{
			actionCooldown = ACTION_COOLDOWN_TICKS;
			return;
		}

		if (config.autoAttack() && nextTarget != null)
		{
			attack(nextTarget);
			actionCooldown = ACTION_COOLDOWN_TICKS;
		}
	}

	/**
	 * Invokes the NPC's "Attack" action through the client's own menu handling.
	 * The menu slot is looked up from the NPC's action list rather than hard-coded,
	 * because the "Attack" option is in a different position for different NPCs.
	 */
	private void attack(NPC npc)
	{
		String[] actions = npc.getComposition().getActions();
		if (actions == null)
		{
			return;
		}
		for (int i = 0; i < actions.length && i < NPC_OPTIONS.length; i++)
		{
			if ("Attack".equalsIgnoreCase(actions[i]))
			{
				client.invokeMenuAction(
					"Attack",
					Text.removeTags(String.valueOf(npc.getName())),
					npc.getIndex(),
					NPC_OPTIONS[i],
					0,
					0);
				return;
			}
		}
	}

	/**
	 * Finds the nearest ground item that matches the loot list within the loot radius
	 * and invokes "Take" on it. Returns true if a click was sent.
	 */
	private boolean tryLoot(Player local)
	{
		if (lootIds.isEmpty() && lootNames.isEmpty())
		{
			return false;
		}

		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = wv.getPlane();

		int meX = local.getLocalLocation().getSceneX();
		int meY = local.getLocalLocation().getSceneY();
		int radius = config.lootRadius();

		TileItem bestItem = null;
		int bestX = 0;
		int bestY = 0;
		int bestDist = Integer.MAX_VALUE;

		Tile[][] plane2d = tiles[plane];
		for (int x = 0; x < plane2d.length; x++)
		{
			Tile[] column = plane2d[x];
			for (int y = 0; y < column.length; y++)
			{
				Tile tile = column[y];
				if (tile == null)
				{
					continue;
				}
				int d = Math.max(Math.abs(x - meX), Math.abs(y - meY));
				if (d > radius || d >= bestDist)
				{
					continue;
				}
				List<TileItem> items = tile.getGroundItems();
				if (items == null)
				{
					continue;
				}
				for (TileItem item : items)
				{
					if (isLootItem(item.getId()))
					{
						bestItem = item;
						bestX = x;
						bestY = y;
						bestDist = d;
						break;
					}
				}
			}
		}

		if (bestItem == null)
		{
			return false;
		}

		client.invokeMenuAction(
			"Take",
			itemName(bestItem.getId()),
			bestItem.getId(),
			MenuAction.GROUND_ITEM_THIRD_OPTION,
			bestX,
			bestY);
		return true;
	}

	private boolean isLootItem(int rawId)
	{
		int id = itemManager.canonicalize(rawId);
		if (lootIds.contains(id))
		{
			return true;
		}
		if (lootNames.isEmpty())
		{
			return false;
		}
		return lootNames.contains(itemName(id).toLowerCase(Locale.ROOT));
	}

	private String itemName(int id)
	{
		return Text.removeTags(itemManager.getItemComposition(id).getName());
	}

	// ---------------------------------------------------------------- events

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!AutoFightConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (AutoFightConfig.NPC_IDS_KEY.equals(event.getKey()))
		{
			parseIds();
			clientThread.invoke(this::rescan);
		}
		else if (AutoFightConfig.LOOT_ITEMS_KEY.equals(event.getKey()))
		{
			parseLoot();
		}
		else
		{
			clientThread.invoke(npcOverlayService::rebuild);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			matching.clear();
			currentTarget = null;
			nextTarget = null;
			lootTicksLeft = 0;
			actionCooldown = 0;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (matches(event.getNpc()))
		{
			matching.add(event.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		matching.remove(npc);
		if (npc == currentTarget)
		{
			currentTarget = null;
		}
		if (npc == nextTarget)
		{
			nextTarget = null;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		if (!matches(npc))
		{
			return;
		}
		if (npc == currentTarget || npc.getInteracting() == client.getLocalPlayer())
		{
			if (sessionStart == null)
			{
				sessionStart = Instant.now();
			}
			kills++;
			lootTicksLeft = LOOT_WINDOW_TICKS;
			notifier.notify(config.notifyKill(), "Killed " + Text.removeTags(String.valueOf(npc.getName())) + " (" + kills + ")");
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		NPC prevCurrent = currentTarget;
		NPC prevNext = nextTarget;
		updateTargets();
		if (prevCurrent != currentTarget || prevNext != nextTarget)
		{
			npcOverlayService.rebuild();
		}

		if (currentTarget != null && sessionStart == null)
		{
			sessionStart = Instant.now();
		}

		// Idle alert: only while matching NPCs are around and we've started a session.
		if (currentTarget == null && !matching.isEmpty() && sessionStart != null)
		{
			idleCount++;
			if (idleCount >= config.idleTicks() && !idleNotified)
			{
				idleNotified = true;
				notifier.notify(config.notifyIdle(), "You're out of combat");
			}
		}
		else
		{
			idleCount = 0;
			idleNotified = false;
		}

		int threshold = config.lowHp();
		if (threshold > 0)
		{
			int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
			if (hp <= threshold && !lowHpNotified)
			{
				lowHpNotified = true;
				notifier.notify(Notification.ON, "Low HP: " + hp);
			}
			else if (hp > threshold)
			{
				lowHpNotified = false;
			}
		}

		automate();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry entry = event.getMenuEntry();
		NPC npc = entry.getNpc();
		if (npc == null)
		{
			return;
		}

		if (config.showIdInMenu())
		{
			entry.setTarget(entry.getTarget() + ColorUtil.prependColorTag(" (ID " + npc.getId() + ")", Color.LIGHT_GRAY));
		}

		if (config.tagMenuOption() && entry.getType() == MenuAction.EXAMINE_NPC)
		{
			boolean tagged = targetIds.contains(npc.getId());
			client.createMenuEntry(-1)
				.setOption(tagged ? UNTAG : TAG)
				.setTarget(event.getTarget())
				.setWorldViewId(entry.getWorldViewId())
				.setIdentifier(event.getIdentifier())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> toggleTag(npc.getId()));
		}
	}

	private void toggleTag(int id)
	{
		Set<Integer> ids = new LinkedHashSet<>(targetIds);
		if (!ids.remove(id))
		{
			ids.add(id);
		}
		List<Integer> sorted = new ArrayList<>(ids);
		Collections.sort(sorted);
		configManager.setConfiguration(AutoFightConfig.GROUP, AutoFightConfig.NPC_IDS_KEY,
			sorted.stream().map(String::valueOf).collect(Collectors.joining(",")));
		// ConfigChanged will reparse and rescan.
	}

	// ---------------------------------------------------------------- overlay accessors

	int getKills()
	{
		return kills;
	}

	Set<Integer> getTargetIds()
	{
		return targetIds;
	}

	NPC getCurrentTarget()
	{
		return currentTarget;
	}

	NPC getNextTarget()
	{
		return nextTarget;
	}

	int getMatchingCount()
	{
		return matching.size();
	}

	double getKillsPerHour()
	{
		if (sessionStart == null || kills == 0)
		{
			return 0;
		}
		long ms = Duration.between(sessionStart, Instant.now()).toMillis();
		return ms <= 0 ? 0 : kills * 3_600_000.0 / ms;
	}

	void resetCounter()
	{
		resetSession();
	}
}
