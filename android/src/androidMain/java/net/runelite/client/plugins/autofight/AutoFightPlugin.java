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
import java.util.Random;
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
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
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
 * Highlights NPCs by ID, marks the current and next target, counts kills, and
 * optionally auto-attacks and auto-loots. Automation goes through the client's own
 * menu actions rather than synthetic screen taps.
 *
 * The matching-NPC list is rebuilt from the world view every tick. An incrementally
 * maintained list drifts out of sync with the scene (a missed despawn, a scene reload,
 * an NPC that changes id), and when it empties the plugin keeps drawing every matching
 * NPC in the "other" colour while believing there is nothing to fight.
 */
@PluginDescriptor(
	name = "AutoFight Highlight",
	description = "Highlight NPCs by ID, mark your current and next target, count kills, optional auto-attack, auto-loot and smart camera",
	tags = {"npc", "highlight", "indicators", "combat", "kill", "counter", "mobile", "id", "loot", "attack", "camera"}
)
public class AutoFightPlugin extends Plugin
{
	private static final String TAG = "AF-Tag";
	private static final String UNTAG = "AF-Untag";

	/** Ticks after a counted kill during which the looter may act. */
	private static final int LOOT_WINDOW_TICKS = 15;

	/** Ticks to wait for combat to actually start before assuming the attack click was lost. */
	private static final int ATTACK_WAIT_TICKS = 6;

	/** Camera yaw is measured in JAU14: 2^14 units to a full turn, not 2^11. */
	private static final int JAU14_MASK = 0x3FFF;
	private static final double JAU14_PER_RADIAN = 16384.0d / (2.0d * Math.PI);

	/** How far off dead centre the camera is allowed to settle, in JAU14. */
	private static final int CAMERA_JITTER = 450;

	/** An NPC's action index maps to the matching NPC menu action. */
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
	private final Set<String> lootNames = new HashSet<>(); // lowercase

	private final Random random = new Random();

	private NPC currentTarget;
	private NPC nextTarget;

	private int kills;
	private Instant sessionStart;
	private int idleCount;
	private boolean idleNotified;
	private boolean lowHpNotified;

	private int lootTicksLeft;
	private int waitTicks;
	private NPC pendingTarget;
	private int pendingTicks;
	private int cameraCooldown;

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
		clearAutomation();
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

	/** Rebuilds the matching list from the live scene. Cheap, and cannot go stale. */
	private void refreshMatching()
	{
		matching.clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		for (NPC npc : wv.npcs())
		{
			if (matches(npc) && !npcUtil.isDying(npc))
			{
				matching.add(npc);
			}
		}
	}

	private void rescan()
	{
		refreshMatching();
		updateTargets();
		npcOverlayService.rebuild();
	}

	private void clearAutomation()
	{
		lootTicksLeft = 0;
		waitTicks = 0;
		pendingTarget = null;
		pendingTicks = 0;
		cameraCooldown = 0;
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

		// A dying NPC is not something we are still fighting: treating it as the current
		// target blocks looting and the next attack until it finishes despawning.
		NPC fighting = null;
		Actor interacting = local.getInteracting();
		if (interacting instanceof NPC && matches((NPC) interacting) && !npcUtil.isDying((NPC) interacting))
		{
			fighting = (NPC) interacting;
		}
		else
		{
			// Something attacking us counts too (aggressive NPCs, retaliation not started yet).
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

		// Keep the existing choice while it is still valid, so the highlight does not
		// flicker between equally good candidates every tick.
		if (!isValidNext(nextTarget, local))
		{
			nextTarget = pickNext(local);
		}
	}

	private boolean isValidNext(NPC npc, Player local)
	{
		if (npc == null || npc == currentTarget || !matching.contains(npc) || npcUtil.isDying(npc))
		{
			return false;
		}
		if (config.skipOthersFights())
		{
			Actor other = npc.getInteracting();
			if (other instanceof Player && other != local)
			{
				return false;
			}
		}
		return true;
	}

	/** Nearest matching NPC, or a random pick from the nearest few when a target pool is set. */
	private NPC pickNext(Player local)
	{
		WorldPoint me = local.getWorldLocation();
		List<NPC> candidates = new ArrayList<>();
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
			candidates.add(npc);
		}
		if (candidates.isEmpty())
		{
			return null;
		}
		candidates.sort((a, b) -> Integer.compare(
			a.getWorldLocation().distanceTo(me),
			b.getWorldLocation().distanceTo(me)));

		int pool = Math.min(Math.max(config.targetPool(), 1), candidates.size());
		return candidates.get(pool <= 1 ? 0 : random.nextInt(pool));
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

	/** Random pause before an automated click, so the cadence is not metronomic. */
	private int reactionDelay()
	{
		int min = Math.max(config.reactionMin(), 0);
		int max = Math.max(config.reactionMax(), min);
		int delay = min + (max > min ? random.nextInt(max - min + 1) : 0);
		if (config.hesitateChance() > 0 && random.nextInt(100) < config.hesitateChance())
		{
			delay += 1 + random.nextInt(3);
		}
		return delay;
	}

	/**
	 * One decision per tick, on the client thread. Looting takes priority over attacking,
	 * and nothing is attempted while we are in combat with a live matching NPC.
	 */
	private void automate()
	{
		if (lootTicksLeft > 0)
		{
			lootTicksLeft--;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		// In combat: the attack landed, so drop any pending state.
		if (currentTarget != null)
		{
			pendingTarget = null;
			pendingTicks = 0;
			return;
		}

		if (waitTicks > 0)
		{
			waitTicks--;
			return;
		}

		// An attack was sent but combat never started (out of reach, click lost, target
		// taken by someone else). Give it a few ticks, then fall through and try again
		// rather than waiting on it forever.
		if (pendingTarget != null)
		{
			pendingTicks++;
			if (pendingTicks < ATTACK_WAIT_TICKS && matching.contains(pendingTarget))
			{
				return;
			}
			pendingTarget = null;
			pendingTicks = 0;
		}

		if (config.autoLoot() && lootTicksLeft > 0 && tryLoot(local))
		{
			waitTicks = reactionDelay();
			return;
		}

		if (config.autoAttack() && nextTarget != null)
		{
			attack(nextTarget);
			pendingTarget = nextTarget;
			pendingTicks = 0;
			waitTicks = reactionDelay();
		}
	}

	/**
	 * Invokes the NPC's "Attack" action through the client's own menu handling. The menu
	 * slot is read from the NPC's action list rather than hard-coded, because "Attack"
	 * sits in a different position for different NPCs.
	 */
	private void attack(NPC npc)
	{
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null)
		{
			return;
		}
		String[] actions = comp.getActions();
		if (actions == null)
		{
			return;
		}
		for (int i = 0; i < actions.length && i < NPC_OPTIONS.length; i++)
		{
			if ("Attack".equalsIgnoreCase(actions[i]))
			{
				client.menuAction(
					0,
					0,
					NPC_OPTIONS[i],
					npc.getIndex(),
					-1,
					"Attack",
					Text.removeTags(String.valueOf(npc.getName())));
				return;
			}
		}
	}

	/**
	 * Takes the nearest ground item matching the loot list within the loot radius.
	 * Returns true if a click was sent.
	 */
	private boolean tryLoot(Player local)
	{
		if (lootIds.isEmpty() && lootNames.isEmpty())
		{
			return false;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return false;
		}
		Scene scene = wv.getScene();
		if (scene == null)
		{
			return false;
		}
		LocalPoint here = local.getLocalLocation();
		if (here == null)
		{
			return false;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = wv.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return false;
		}

		int meX = here.getSceneX();
		int meY = here.getSceneY();
		int radius = config.lootRadius();

		TileItem bestItem = null;
		int bestX = 0;
		int bestY = 0;
		int bestDist = Integer.MAX_VALUE;

		Tile[][] plane2d = tiles[plane];
		for (int x = Math.max(meX - radius, 0); x <= Math.min(meX + radius, plane2d.length - 1); x++)
		{
			Tile[] column = plane2d[x];
			if (column == null)
			{
				continue;
			}
			for (int y = Math.max(meY - radius, 0); y <= Math.min(meY + radius, column.length - 1); y++)
			{
				Tile tile = column[y];
				if (tile == null)
				{
					continue;
				}
				int d = Math.max(Math.abs(x - meX), Math.abs(y - meY));
				if (d >= bestDist)
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

		client.menuAction(
			bestX,
			bestY,
			MenuAction.GROUND_ITEM_THIRD_OPTION,
			bestItem.getId(),
			bestItem.getId(),
			"Take",
			itemName(bestItem.getId()));
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
		return Text.removeTags(String.valueOf(itemManager.getItemComposition(id).getName()));
	}

	// ---------------------------------------------------------------- camera

	/**
	 * Turns the camera towards the target only when it has drifted off screen or close to
	 * the edge, so it does not fight the player for control on every tick.
	 */
	private void updateCamera()
	{
		if (!config.smartCamera())
		{
			return;
		}
		if (cameraCooldown > 0)
		{
			cameraCooldown--;
			return;
		}

		NPC target = currentTarget != null ? currentTarget : nextTarget;
		Player local = client.getLocalPlayer();
		if (target == null || local == null)
		{
			return;
		}

		LocalPoint targetPoint = target.getLocalLocation();
		LocalPoint here = local.getLocalLocation();
		if (targetPoint == null || here == null || onScreen(targetPoint))
		{
			return;
		}

		int dx = targetPoint.getX() - here.getX();
		int dy = targetPoint.getY() - here.getY();
		if (dx == 0 && dy == 0)
		{
			return;
		}

		// Perspective projects with x1 = x*cos(yaw) + y*sin(yaw); x1 is the horizontal
		// screen axis, so the target is centred when yaw = atan2(-dx, dy).
		double radians = Math.atan2(-dx, dy);
		int yaw = (int) Math.round(radians * JAU14_PER_RADIAN);
		yaw += random.nextInt(2 * CAMERA_JITTER + 1) - CAMERA_JITTER;

		client.setCameraYawTarget(yaw & JAU14_MASK);
		cameraCooldown = 2 + random.nextInt(3);
	}

	/** True when the point projects comfortably inside the viewport. */
	private boolean onScreen(LocalPoint point)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return false;
		}
		Point canvas = Perspective.localToCanvas(client, point, wv.getPlane());
		if (canvas == null)
		{
			return false;
		}

		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return false;
		}

		int x = canvas.getX() - client.getViewportXOffset();
		int y = canvas.getY() - client.getViewportYOffset();
		int marginX = width * config.cameraMargin() / 100;
		int marginY = height * config.cameraMargin() / 100;

		return x >= marginX && x <= width - marginX && y >= marginY && y <= height - marginY;
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
			clearAutomation();
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
		if (npc == pendingTarget)
		{
			pendingTarget = null;
			pendingTicks = 0;
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
	public void onGameTick(GameTick tick)
	{
		NPC prevCurrent = currentTarget;
		NPC prevNext = nextTarget;

		refreshMatching();
		updateTargets();

		if (prevCurrent != currentTarget || prevNext != nextTarget)
		{
			npcOverlayService.rebuild();
		}

		if (currentTarget != null && sessionStart == null)
		{
			sessionStart = Instant.now();
		}

		// Idle alert: only while matching NPCs are around and a session has started.
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
		updateCamera();
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
