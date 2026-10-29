/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Provides;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.Projectile;
import net.runelite.api.ScriptEvent;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AmbientSoundEffectCreated;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.ClanMemberJoined;
import net.runelite.api.events.ClanMemberLeft;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PreMapLoad;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.client.callback.ClientThread;
import okhttp3.OkHttpClient;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "OpenRune-DeveloperTools",
	description = "Local MCP server letting AI tools screenshot interfaces, inspect widgets by packed id, and click/drag components",
	tags = {"ai", "mcp", "automation", "interface", "dev", "openrune"},
	enabledByDefault = false
)
@Slf4j
public class OpenRuneDevToolsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OpenRuneDevToolsConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private McpMarkerOverlay markerOverlay;

	private McpHttpServer server;
	private McpEventRecorder recorder;
	private ItemIconTool itemIconTool;

	// ProjectileMoved fires every cycle per projectile; only record first sighting
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());

	@Override
	protected void startUp() throws Exception
	{
		recorder = new McpEventRecorder();
		itemIconTool = new ItemIconTool(client, clientThread);
		overlayManager.add(markerOverlay);
		tryStartServer();
		if (config.autoUpdate())
		{
			new UpdateChecker(client, clientThread, okHttpClient).checkAsync();
		}
	}

	@Override
	protected void shutDown()
	{
		stopServer();
		overlayManager.remove(markerOverlay);
		markerOverlay.clear(null);
		recorder = null;
		if (itemIconTool != null)
		{
			itemIconTool.shutDown();
			itemIconTool = null;
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (itemIconTool != null && config.itemIconEditor())
		{
			itemIconTool.onMenuOpened(event);
		}
		if (recorder != null)
		{
			recorder.recordInterfaceMenu(
				event.getMenuEntries() != null ? event.getMenuEntries().length : 0,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (recorder != null)
		{
			ScriptEvent scriptEvent = event.getScriptEvent();
			recorder.recordScript(event.getScriptId(),
				scriptEvent != null ? scriptEvent.getArguments() : null,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (recorder != null)
		{
			if (event.getVarbitId() != -1)
			{
				recorder.recordVar("varbit", event.getVarbitId(), event.getValue(), client.getTickCount());
			}
			else
			{
				recorder.recordVar("varp", event.getVarpId(), event.getValue(), client.getTickCount());
			}
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (recorder != null)
		{
			recorder.recordVar("varcint", event.getIndex(),
				client.getVarcIntValue(event.getIndex()), client.getTickCount());
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		if (recorder != null)
		{
			recorder.recordVarStr(event.getIndex(),
				client.getVarcStrValue(event.getIndex()), client.getTickCount());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (recorder != null)
		{
			recorder.recordChat(event.getType().name(), event.getName(), event.getSender(),
				event.getMessage(), client.getTickCount());
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (recorder == null)
		{
			return;
		}
		Projectile projectile = event.getProjectile();
		if (!seenProjectiles.add(projectile))
		{
			return;
		}
		JsonObject detail = new JsonObject();
		detail.addProperty("projectileId", projectile.getId());
		detail.addProperty("startSceneX", projectile.getX1());
		detail.addProperty("startSceneY", projectile.getY1());
		detail.addProperty("floor", projectile.getFloor());
		detail.addProperty("slope", projectile.getSlope());
		detail.addProperty("endHeight", projectile.getEndHeight());
		detail.addProperty("startCycle", projectile.getStartCycle());
		detail.addProperty("endCycle", projectile.getEndCycle());
		WorldPoint target = projectile.getTargetPoint();
		if (target != null)
		{
			detail.addProperty("targetX", target.getX());
			detail.addProperty("targetY", target.getY());
			detail.addProperty("targetPlane", target.getPlane());
		}
		Actor targetActor = projectile.getTargetActor();
		if (targetActor != null)
		{
			detail.addProperty("target", targetActor.getName());
			detail.addProperty("targetType", targetActor instanceof NPC ? "npc" : "player");
		}
		recorder.recordProjectile(detail, client.getTickCount());
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		recorder.recordEffect("animation",
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			new JsonPrimitive(actor.getAnimation()),
			client.getTickCount());
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		JsonArray graphics = new JsonArray();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			graphics.add(spotAnim.getId());
		}
		recorder.recordEffect("graphic",
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			graphics,
			client.getTickCount());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		Hitsplat hitsplat = event.getHitsplat();
		recorder.recordCombatTick(actor == client.getLocalPlayer(), hitsplat.isMine(), client.getTickCount());
		recorder.recordHitsplat(hitsplat.getHitsplatType(),
			hitsplat.getAmount(),
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			actor.getHealthRatio(),
			actor.getHealthScale(),
			client.getTickCount());
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		recordActorLifecycle("spawn", event.getNpc(), null);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		recordActorLifecycle("despawn", event.getNpc(), null);
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		// Transforms keep the index but swap the composition: phase changes in bosses.
		Integer oldId = event.getOld() != null ? event.getOld().getId() : null;
		recordActorLifecycle("transform", event.getNpc(), oldId);
	}

	private void recordActorLifecycle(String kind, NPC npc, Integer oldNpcId)
	{
		if (recorder == null || npc == null)
		{
			return;
		}
		WorldPoint wp = npc.getWorldLocation();
		recorder.recordSpawn(kind, "npc", npc.getName(), npc.getIndex(), npc.getId(), oldNpcId,
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null, client.getTickCount());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		WorldPoint wp = actor.getWorldLocation();
		NPC npc = actor instanceof NPC ? (NPC) actor : null;
		recorder.recordSpawn("death", npc != null ? "npc" : "player", actor.getName(),
			npc != null ? npc.getIndex() : -1, npc != null ? npc.getId() : null, null,
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null, client.getTickCount());
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		recordGroundItem("spawned", event.getTile(), event.getItem());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		recordGroundItem("despawned", event.getTile(), event.getItem());
	}

	private void recordGroundItem(String kind, Tile tile, TileItem item)
	{
		if (recorder == null || item == null)
		{
			return;
		}
		WorldPoint wp = tile != null ? tile.getWorldLocation() : null;
		ItemComposition composition = client.getItemDefinition(item.getId());
		recorder.recordLoot(kind, item.getId(),
			composition != null ? composition.getName() : null, item.getQuantity(),
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null, client.getTickCount());
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		Actor actor = event.getActor();
		String text = event.getOverheadText();
		if (recorder == null || actor == null || text == null || text.isEmpty())
		{
			return;
		}
		recorder.recordOverhead(actor instanceof NPC ? "npc" : "player", actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null, text, client.getTickCount());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (recorder != null)
		{
			recorder.recordExperience(event.getSkill().getName(), event.getXp(), event.getLevel(),
				event.getBoostedLevel(), client.getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (recorder == null)
		{
			return;
		}
		Map<Integer, Integer> contents = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item.getId() != -1 && !isPlaceholder(item.getId()))
			{
				contents.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		recorder.recordContainer(event.getContainerId(), contents, client.getTickCount());
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (recorder == null)
		{
			return;
		}
		Actor source = event.getSource();
		recorder.recordSound("sound", event.getSoundId(), null, null,
			source != null ? source.getName() : null, client.getTickCount());
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (recorder == null)
		{
			return;
		}
		Actor source = event.getSource();
		recorder.recordSound("areaSound", event.getSoundId(), event.getSceneX(), event.getSceneY(),
			source != null ? source.getName() : null, client.getTickCount());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (recorder != null)
		{
			recorder.recordInterface("opened", event.getGroupId(), client.getTickCount());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (recorder != null)
		{
			recorder.recordInterface(event.isUnload() ? "unloaded" : "closed",
				event.getGroupId(), client.getTickCount());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Audit trail of every packet the client sends: which tool/user action produced it.
		if (recorder != null)
		{
			recorder.recordAction(event.getMenuOption(), event.getMenuTarget(),
				event.getMenuAction().name(), event.getParam0(), event.getParam1(),
				event.getId(), event.getItemId(), client.getTickCount());
		}
	}

	// --- scene object lifecycle: arena objects spawning/despawning mid-fight ---

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		recordObject("spawned", "game", event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		recordObject("despawned", "game", event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		recordObject("spawned", "wall", event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		recordObject("despawned", "wall", event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		recordObject("spawned", "decorative", event.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		recordObject("despawned", "decorative", event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		recordObject("spawned", "ground", event.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		recordObject("despawned", "ground", event.getGroundObject());
	}

	private void recordObject(String kind, String type, TileObject obj)
	{
		if (recorder == null || obj == null)
		{
			return;
		}
		WorldPoint wp = obj.getWorldLocation();
		ObjectComposition comp = client.getObjectDefinition(obj.getId());
		recorder.recordObject(kind, type, obj.getId(), comp != null ? comp.getName() : null,
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null,
			wp != null ? wp.getPlane() : null, client.getTickCount());
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (recorder == null || event.getGraphicsObject() == null)
		{
			return;
		}
		net.runelite.api.GraphicsObject gfx = event.getGraphicsObject();
		net.runelite.api.coords.LocalPoint lp = gfx.getLocation();
		WorldPoint wp = lp != null ? WorldPoint.fromLocal(client, lp) : null;
		recorder.recordGfx(gfx.getId(),
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null,
			gfx.getLevel(), client.getTickCount());
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (recorder == null)
		{
			return;
		}
		Actor source = event.getSource();
		Actor target = event.getTarget();
		if (source == null)
		{
			return;
		}
		NPC srcNpc = source instanceof NPC ? (NPC) source : null;
		NPC tgtNpc = target instanceof NPC ? (NPC) target : null;
		recorder.recordInteract(
			srcNpc != null ? "npc" : "player", source.getName(),
			srcNpc != null ? srcNpc.getIndex() : -1,
			target != null ? (tgtNpc != null ? "npc" : "player") : null,
			target != null ? target.getName() : null,
			tgtNpc != null ? tgtNpc.getIndex() : -1,
			client.getTickCount());
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		if (recorder == null || event.getItem() == null)
		{
			return;
		}
		WorldPoint wp = event.getTile() != null ? event.getTile().getWorldLocation() : null;
		ItemComposition comp = client.getItemDefinition(event.getItem().getId());
		recorder.recordLoot("quantity", event.getItem().getId(),
			comp != null ? comp.getName() : null, event.getNewQuantity(),
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null,
			client.getTickCount());
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		if (recorder != null)
		{
			recorder.recordWorld("world_changed", client.getWorld(), null, client.getTickCount());
		}
	}

	@Subscribe
	public void onPreMapLoad(PreMapLoad event)
	{
		if (recorder != null)
		{
			recorder.recordWorld("mapload", null,
				client.isInInstancedRegion() ? "instanced" : null, client.getTickCount());
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// ::commands the client actually sent — pairs with type_chat evidence.
		if (recorder != null)
		{
			String[] args = event.getArguments();
			recorder.recordAction("::" + event.getCommand(),
				args != null && args.length > 0 ? String.join(" ", args) : null,
				"COMMAND_EXECUTED", 0, 0, 0, -1, client.getTickCount());
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (recorder != null)
		{
			recorder.recordFakeXp(event.getSkill().name(), event.getXp(), client.getTickCount());
		}
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		// Appearance/gear updates — proves a player render-state changed.
		if (recorder == null || event.getPlayer() == null)
		{
			return;
		}
		Player p = event.getPlayer();
		WorldPoint wp = p.getWorldLocation();
		recorder.recordSpawn("changed", "player", p.getName(), -1, null, null,
			wp != null ? wp.getX() : null, wp != null ? wp.getY() : null, client.getTickCount());
	}

	@Subscribe
	public void onAmbientSoundEffectCreated(AmbientSoundEffectCreated event)
	{
		if (recorder != null && event.getAmbientSoundEffect() != null)
		{
			recorder.recordSound("ambient", event.getAmbientSoundEffect().getSoundEffectId(),
				null, null, null, client.getTickCount());
		}
	}

	// --- social: clan + friends chat membership events ---

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		if (recorder == null)
		{
			return;
		}
		String name = event.getClanChannel() != null ? event.getClanChannel().getName() : null;
		recorder.recordSocial(event.isGuest() ? "guest_clan" : "clan",
			name != null ? name : "(left)", null, client.getTickCount());
	}

	@Subscribe
	public void onClanMemberJoined(ClanMemberJoined event)
	{
		if (recorder != null && event.getClanMember() != null)
		{
			recorder.recordSocial("clan_member_joined", event.getClanMember().getName(),
				event.getClanMember().getRank() != null
					? String.valueOf(event.getClanMember().getRank().getRank()) : null,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onClanMemberLeft(ClanMemberLeft event)
	{
		if (recorder != null && event.getClanMember() != null)
		{
			recorder.recordSocial("clan_member_left", event.getClanMember().getName(),
				null, client.getTickCount());
		}
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		if (recorder != null)
		{
			net.runelite.api.FriendsChatManager fc = client.getFriendsChatManager();
			recorder.recordSocial(event.isJoined() ? "fc_joined" : "fc_left",
				fc != null ? fc.getName() : null, null, client.getTickCount());
		}
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		if (recorder != null && event.getMember() != null)
		{
			recorder.recordSocial("fc_member_joined", event.getMember().getName(),
				"world " + event.getMember().getWorld(), client.getTickCount());
		}
	}

	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		if (recorder != null && event.getMember() != null)
		{
			recorder.recordSocial("fc_member_left", event.getMember().getName(),
				null, client.getTickCount());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Forget per-session baselines so a hop/relog doesn't report phantom
		// changes or keep a stale "in combat" state.
		if (recorder != null && event.getGameState() != GameState.LOGGED_IN
			&& event.getGameState() != GameState.LOADING)
		{
			recorder.reset();
			seenProjectiles.clear();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (OpenRuneDevToolsConfig.GROUP.equals(event.getGroup()))
		{
			stopServer();
			tryStartServer();
		}
	}

	/** A busy port must not fail startUp, or the plugin never enables and its port can't be changed. */
	private void tryStartServer()
	{
		try
		{
			startServer();
		}
		catch (Exception e)
		{
			log.error("Failed to start MCP server on port {}; pick a free port in the plugin settings", config.port(), e);
		}
	}

	private void startServer() throws Exception
	{
		McpHttpServer started = new McpHttpServer(config.port(),
			new McpTools(client, clientThread, drawManager, recorder, markerOverlay));
		started.start();
		server = started;
		log.info("MCP server listening on http://127.0.0.1:{}/mcp", config.port());
	}

	private boolean isPlaceholder(int itemId)
	{
		ItemComposition item = client.getItemDefinition(itemId);
		return item.getPlaceholderTemplateId() != -1;
	}

	private void stopServer()
	{
		if (server != null)
		{
			server.stop();
			server = null;
		}
	}

	@Provides
	OpenRuneDevToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OpenRuneDevToolsConfig.class);
	}
}
