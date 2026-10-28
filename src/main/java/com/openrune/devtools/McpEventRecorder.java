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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.util.Text;

/**
 * Rolling history of clientscript invocations, varbit/varp/varc changes, combat,
 * experience, item containers and sounds, recorded from the event bus and queried
 * by the MCP history tools. Every entry carries the game tick and a global sequence
 * number, so {@link #timeline} can merge the buffers back into one ordered stream.
 * Record methods are called on the client thread; queries come from HTTP threads.
 */
class McpEventRecorder
{
	private static final int SCRIPT_BUFFER = 5000;
	private static final int VAR_BUFFER = 3000;
	private static final int VARC_BUFFER = 1500;
	private static final int PROJECTILE_BUFFER = 1000;
	private static final int EFFECT_BUFFER = 2000;
	private static final int CHAT_BUFFER = 1000;
	private static final int COMBAT_BUFFER = 2000;
	private static final int XP_BUFFER = 2000;
	private static final int CONTAINER_BUFFER = 1000;
	private static final int SOUND_BUFFER = 2000;
	private static final int SPAWN_BUFFER = 1000;
	private static final int LOOT_BUFFER = 1000;
	private static final int OVERHEAD_BUFFER = 500;
	private static final int INTERFACE_BUFFER = 1000;
	private static final int ACTION_BUFFER = 1000;
	private static final int MAX_ARGS_LENGTH = 160;
	private static final int MAX_COUNT_ROWS = 50;

	private final Deque<JsonObject> scripts = new ArrayDeque<>();
	private final Deque<JsonObject> vars = new ArrayDeque<>();
	private final Deque<JsonObject> varcs = new ArrayDeque<>();
	private final Deque<JsonObject> projectiles = new ArrayDeque<>();
	private final Deque<JsonObject> effects = new ArrayDeque<>();
	private final Deque<JsonObject> chat = new ArrayDeque<>();
	private final Deque<JsonObject> combat = new ArrayDeque<>();
	private final Deque<JsonObject> experience = new ArrayDeque<>();
	private final Deque<JsonObject> containers = new ArrayDeque<>();
	private final Deque<JsonObject> sounds = new ArrayDeque<>();
	private final Deque<JsonObject> spawns = new ArrayDeque<>();
	private final Deque<JsonObject> loot = new ArrayDeque<>();
	private final Deque<JsonObject> overheads = new ArrayDeque<>();
	private final Deque<JsonObject> interfaces = new ArrayDeque<>();
	private final Deque<JsonObject> actions = new ArrayDeque<>();
	private final Map<String, Integer> lastIntValues = new HashMap<>();
	private final Map<Integer, String> lastStrValues = new HashMap<>();
	private final Map<String, Integer> lastSkillXp = new HashMap<>();
	private final Map<String, Integer> lastBoostedLevels = new HashMap<>();
	private final Map<Integer, Map<Integer, Integer>> lastContainerContents = new HashMap<>();
	private final Map<String, Long> droppedByChannel = new HashMap<>();
	private long seq = 1;
	private int lastHitOnMeTick = -1;
	private int lastMyHitTick = -1;

	/**
	 * Forget every history and per-session baseline. Called on logout/world hop so the
	 * new session can't report phantom diffs or inherit a stale "in combat" state.
	 */
	synchronized void reset()
	{
		scripts.clear();
		vars.clear();
		varcs.clear();
		projectiles.clear();
		effects.clear();
		chat.clear();
		combat.clear();
		experience.clear();
		containers.clear();
		sounds.clear();
		lastIntValues.clear();
		lastStrValues.clear();
		lastSkillXp.clear();
		lastBoostedLevels.clear();
		lastContainerContents.clear();
		spawns.clear();
		loot.clear();
		overheads.clear();
		interfaces.clear();
		actions.clear();
		droppedByChannel.clear();
		lastHitOnMeTick = -1;
		lastMyHitTick = -1;
	}

	synchronized void recordScript(int scriptId, Object[] arguments, int tick)
	{
		JsonObject entry = baseEntry("script", tick);
		entry.addProperty("scriptId", scriptId);
		if (arguments != null && arguments.length > 0)
		{
			String args = Arrays.toString(arguments);
			if (args.length() > MAX_ARGS_LENGTH)
			{
				args = args.substring(0, MAX_ARGS_LENGTH) + "…";
			}
			entry.addProperty("args", args);
		}
		push(scripts, SCRIPT_BUFFER, entry);
	}

	synchronized void recordVar(String type, int id, int value, int tick)
	{
		Integer old = lastIntValues.put(type + ":" + id, value);
		if (old != null && old == value)
		{
			return;
		}
		JsonObject entry = baseEntry("var", tick);
		entry.addProperty("type", type);
		entry.addProperty("id", id);
		entry.addProperty("value", value);
		if (old != null)
		{
			entry.addProperty("oldValue", old);
		}
		push("varcint".equals(type) ? varcs : vars,
			"varcint".equals(type) ? VARC_BUFFER : VAR_BUFFER, entry);
	}

	synchronized void recordVarStr(int id, String value, int tick)
	{
		String old = lastStrValues.put(id, value);
		if (old != null && old.equals(value))
		{
			return;
		}
		JsonObject entry = baseEntry("var", tick);
		entry.addProperty("type", "varcstr");
		entry.addProperty("id", id);
		entry.addProperty("value", value);
		if (old != null)
		{
			entry.addProperty("oldValue", old);
		}
		push(varcs, VARC_BUFFER, entry);
	}

	synchronized void recordProjectile(JsonObject detail, int tick)
	{
		JsonObject entry = baseEntry("projectile", tick);
		for (Map.Entry<String, JsonElement> e : detail.entrySet())
		{
			entry.add(e.getKey(), e.getValue());
		}
		push(projectiles, PROJECTILE_BUFFER, entry);
	}

	synchronized void recordEffect(String kind, String actorType, String actorName, Integer npcId,
		JsonElement value, int tick)
	{
		JsonObject entry = baseEntry("effect", tick);
		entry.addProperty("kind", kind);
		entry.addProperty("actorType", actorType);
		if (actorName != null)
		{
			entry.addProperty("actor", actorName);
		}
		if (npcId != null)
		{
			entry.addProperty("npcId", npcId);
		}
		entry.add("value", value);
		push(effects, EFFECT_BUFFER, entry);
	}

	synchronized void recordChat(String type, String name, String sender, String message, int tick)
	{
		JsonObject entry = baseEntry("chat", tick);
		entry.addProperty("type", type);
		if (name != null && !name.isEmpty())
		{
			entry.addProperty("name", name);
		}
		if (sender != null && !sender.isEmpty())
		{
			entry.addProperty("sender", sender);
		}
		entry.addProperty("message", message);
		push(chat, CHAT_BUFFER, entry);
	}

	/**
	 * Track the last tick a hitsplat landed on the local player, and the last tick one the local
	 * player dealt landed (on anything), for get_combat_state.
	 */
	synchronized void recordCombatTick(boolean onMe, boolean mine, int tick)
	{
		if (onMe)
		{
			lastHitOnMeTick = tick;
		}
		if (mine && !onMe)
		{
			lastMyHitTick = tick;
		}
	}

	synchronized int lastHitOnMeTick()
	{
		return lastHitOnMeTick;
	}

	synchronized int lastMyHitTick()
	{
		return lastMyHitTick;
	}

	synchronized void recordHitsplat(int hitsplatType, int amount, String actorType, String actorName,
		Integer npcId, int healthRatio, int healthScale, int tick)
	{
		JsonObject entry = baseEntry("combat", tick);
		entry.addProperty("hitsplatType", hitsplatType);
		entry.addProperty("amount", amount);
		entry.addProperty("actorType", actorType);
		if (actorName != null)
		{
			entry.addProperty("actor", actorName);
		}
		if (npcId != null)
		{
			entry.addProperty("npcId", npcId);
		}
		if (healthScale > 0)
		{
			entry.addProperty("healthRatio", healthRatio);
			entry.addProperty("healthScale", healthScale);
		}
		push(combat, COMBAT_BUFFER, entry);
	}

	synchronized void recordExperience(String skill, int xp, int level, int boostedLevel, int tick)
	{
		Integer oldXp = lastSkillXp.put(skill, xp);
		Integer oldBoostedLevel = lastBoostedLevels.put(skill, boostedLevel);
		// The login stat refresh fires for every skill; only real gains, drains and restores are events.
		if (oldXp == null || (oldXp == xp && oldBoostedLevel == boostedLevel))
		{
			return;
		}
		JsonObject entry = baseEntry("experience", tick);
		entry.addProperty("skill", skill);
		entry.addProperty("xp", xp);
		entry.addProperty("level", level);
		entry.addProperty("boostedLevel", boostedLevel);
		entry.addProperty("xpGained", xp - oldXp);
		push(experience, XP_BUFFER, entry);
	}

	synchronized void recordContainer(int containerId, Map<Integer, Integer> contents, int tick)
	{
		Map<Integer, Integer> previous = lastContainerContents.put(containerId, contents);
		if (previous == null)
		{
			// First sighting is the baseline to diff against, not a change worth reporting.
			return;
		}
		JsonArray changes = quantityChanges(previous, contents);
		if (changes.size() == 0)
		{
			return;
		}
		JsonObject entry = baseEntry("container", tick);
		entry.addProperty("containerId", containerId);
		entry.add("changes", changes);
		push(containers, CONTAINER_BUFFER, entry);
	}

	synchronized void recordSound(String kind, int soundId, Integer sceneX, Integer sceneY,
		String source, int tick)
	{
		JsonObject entry = baseEntry("sound", tick);
		entry.addProperty("kind", kind);
		entry.addProperty("soundId", soundId);
		if (sceneX != null && sceneY != null)
		{
			entry.addProperty("sceneX", sceneX);
			entry.addProperty("sceneY", sceneY);
		}
		if (source != null)
		{
			entry.addProperty("source", source);
		}
		push(sounds, SOUND_BUFFER, entry);
	}

	/**
	 * Actor lifecycle events: spawn, despawn and transform (NPC id change at a
	 * stable index — the phase transitions bosses do mid-fight).
	 */
	synchronized void recordSpawn(String kind, String actorType, String name, int index,
		Integer npcId, Integer oldNpcId, Integer x, Integer y, int tick)
	{
		JsonObject entry = baseEntry("spawn", tick);
		entry.addProperty("kind", kind);
		entry.addProperty("actorType", actorType);
		if (name != null)
		{
			entry.addProperty("name", name);
		}
		entry.addProperty("index", index);
		if (npcId != null)
		{
			entry.addProperty("npcId", npcId);
		}
		if (oldNpcId != null)
		{
			entry.addProperty("oldNpcId", oldNpcId);
		}
		if (x != null && y != null)
		{
			entry.addProperty("x", x);
			entry.addProperty("y", y);
		}
		push(spawns, SPAWN_BUFFER, entry);
	}

	/** Ground-item lifecycle: spawned (drops included) and despawned/picked up. */
	synchronized void recordLoot(String kind, int itemId, String name, int quantity,
		Integer x, Integer y, int tick)
	{
		JsonObject entry = baseEntry("loot", tick);
		entry.addProperty("kind", kind);
		entry.addProperty("itemId", itemId);
		if (name != null)
		{
			entry.addProperty("name", name);
		}
		entry.addProperty("quantity", quantity);
		if (x != null && y != null)
		{
			entry.addProperty("x", x);
			entry.addProperty("y", y);
		}
		push(loot, LOOT_BUFFER, entry);
	}

	/** Overhead text ("More power!") — how many mechanics announce phase changes. */
	synchronized void recordOverhead(String actorType, String actorName, Integer npcId,
		String text, int tick)
	{
		JsonObject entry = baseEntry("overhead", tick);
		entry.addProperty("actorType", actorType);
		if (actorName != null)
		{
			entry.addProperty("actor", actorName);
		}
		if (npcId != null)
		{
			entry.addProperty("npcId", npcId);
		}
		entry.addProperty("text", text);
		push(overheads, OVERHEAD_BUFFER, entry);
	}

	/** Interface (widget group) load/close events — proves the server opened or closed a screen. */
	synchronized void recordInterface(String kind, int groupId, int tick)
	{
		JsonObject entry = baseEntry("interface", tick);
		entry.addProperty("kind", kind);
		entry.addProperty("groupId", groupId);
		push(interfaces, INTERFACE_BUFFER, entry);
	}

	/**
	 * Menu options the client actually invoked (MenuOptionClicked) — the audit trail of
	 * what packet left the client: option, target, MenuAction type and raw params.
	 */
	synchronized void recordAction(String option, String target, String type,
		int param0, int param1, int identifier, int itemId, int tick)
	{
		JsonObject entry = baseEntry("action", tick);
		entry.addProperty("option", option);
		if (target != null && !target.isEmpty())
		{
			entry.addProperty("target", Text.removeTags(target));
		}
		entry.addProperty("type", type);
		entry.addProperty("param0", param0);
		entry.addProperty("param1", param1);
		entry.addProperty("identifier", identifier);
		if (itemId > 0)
		{
			entry.addProperty("itemId", itemId);
		}
		push(actions, ACTION_BUFFER, entry);
	}

	synchronized JsonObject spawnHistory(long sinceMs, String kind, String nameFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = nameFilter != null ? nameFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : spawns)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (kind != null && !kind.equalsIgnoreCase(entry.get("kind").getAsString()))
			{
				continue;
			}
			if (filter != null && (!entry.has("name")
				|| !entry.get("name").getAsString().toLowerCase().contains(filter)))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("spawn"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject lootHistory(long sinceMs, String kind, String nameFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = nameFilter != null ? nameFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : loot)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (kind != null && !kind.equalsIgnoreCase(entry.get("kind").getAsString()))
			{
				continue;
			}
			if (filter != null && (!entry.has("name")
				|| !entry.get("name").getAsString().toLowerCase().contains(filter)))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("loot"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject overheadHistory(long sinceMs, String nameFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = nameFilter != null ? nameFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : overheads)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (filter != null)
			{
				String actor = entry.has("actor") ? entry.get("actor").getAsString().toLowerCase() : "";
				String text = entry.get("text").getAsString().toLowerCase();
				if (!actor.contains(filter) && !text.contains(filter))
				{
					continue;
				}
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("overhead"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject interfaceHistory(long sinceMs, Integer groupId, String kind, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : interfaces)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (groupId != null && entry.get("groupId").getAsInt() != groupId)
			{
				continue;
			}
			if (kind != null && !kind.equalsIgnoreCase(entry.get("kind").getAsString()))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("interface"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject actionHistory(long sinceMs, String filter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String needle = filter != null ? filter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : actions)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (needle != null)
			{
				String option = entry.has("option") ? entry.get("option").getAsString().toLowerCase() : "";
				String target = entry.has("target") ? entry.get("target").getAsString().toLowerCase() : "";
				String type = entry.get("type").getAsString().toLowerCase();
				if (!option.contains(needle) && !target.contains(needle) && !type.contains(needle))
				{
					continue;
				}
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("action"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject chatHistory(long sinceMs, String type, String nameFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = nameFilter != null ? nameFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : chat)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (type != null && !type.equalsIgnoreCase(entry.get("type").getAsString()))
			{
				continue;
			}
			if (filter != null && (!entry.has("name")
				|| !entry.get("name").getAsString().toLowerCase().contains(filter)))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("chat"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject projectileHistory(long sinceMs, Integer projectileId, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : projectiles)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (projectileId != null && entry.get("projectileId").getAsInt() != projectileId)
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("projectile"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject effectHistory(long sinceMs, String kind, String actorFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = actorFilter != null ? actorFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : effects)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (kind != null && !kind.equals(entry.get("kind").getAsString()))
			{
				continue;
			}
			if (filter != null && (!entry.has("actor")
				|| !entry.get("actor").getAsString().toLowerCase().contains(filter)))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("effect"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject scriptHistory(long sinceMs, Integer scriptId, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		Map<Integer, Integer> counts = new HashMap<>();
		for (JsonObject entry : scripts)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			int id = entry.get("scriptId").getAsInt();
			if (scriptId != null && id != scriptId)
			{
				continue;
			}
			matched.add(entry);
			counts.merge(id, 1, Integer::sum);
		}

		JsonArray countRows = new JsonArray();
		counts.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(MAX_COUNT_ROWS)
			.forEach(e ->
			{
				JsonObject row = new JsonObject();
				row.addProperty("scriptId", e.getKey());
				row.addProperty("count", e.getValue());
				countRows.add(row);
			});

		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("script"));
		result.add("counts", countRows);
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject varHistory(long sinceMs, String type, Integer id, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (Deque<JsonObject> buffer : List.of(vars, varcs))
		{
			for (JsonObject entry : buffer)
			{
				if (entry.get("timeMs").getAsLong() < cutoff)
				{
					continue;
				}
				if (type != null && !type.equals(entry.get("type").getAsString()))
				{
					continue;
				}
				if (id != null && entry.get("id").getAsInt() != id)
				{
					continue;
				}
				matched.add(entry);
			}
		}
		matched.sort((a, b) -> Long.compare(a.get("seq").getAsLong(), b.get("seq").getAsLong()));

		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("var"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject combatHistory(long sinceMs, String actorFilter, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		String filter = actorFilter != null ? actorFilter.toLowerCase() : null;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : combat)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (filter != null && (!entry.has("actor")
				|| !entry.get("actor").getAsString().toLowerCase().contains(filter)))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("combat"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject experienceHistory(long sinceMs, String skill, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : experience)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (skill != null && !skill.equalsIgnoreCase(entry.get("skill").getAsString()))
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("experience"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject containerHistory(long sinceMs, Integer containerId, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : containers)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (containerId != null && entry.get("containerId").getAsInt() != containerId)
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("container"));
		result.add("events", tail(matched, limit));
		return result;
	}

	synchronized JsonObject soundHistory(long sinceMs, Integer soundId, int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (JsonObject entry : sounds)
		{
			if (entry.get("timeMs").getAsLong() < cutoff)
			{
				continue;
			}
			if (soundId != null && entry.get("soundId").getAsInt() != soundId)
			{
				continue;
			}
			matched.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor("sound"));
		result.add("events", tail(matched, limit));
		return result;
	}

	/** Every buffer merged back into one stream, ordered by the sequence numbers they were stamped with. */
	synchronized JsonObject timeline(long sinceMs, Set<String> channels, Integer fromTick, Integer toTick,
		int limit)
	{
		long cutoff = System.currentTimeMillis() - sinceMs;
		List<JsonObject> matched = new ArrayList<>();
		for (Deque<JsonObject> buffer : allBuffers())
		{
			for (JsonObject entry : buffer)
			{
				if (entry.get("timeMs").getAsLong() < cutoff)
				{
					continue;
				}
				if (channels != null && !channels.contains(entry.get("channel").getAsString()))
				{
					continue;
				}
				int tick = entry.get("tick").getAsInt();
				if (fromTick != null && tick < fromTick)
				{
					continue;
				}
				if (toTick != null && tick > toTick)
				{
					continue;
				}
				matched.add(entry);
			}
		}
		matched.sort((a, b) -> Long.compare(a.get("seq").getAsLong(), b.get("seq").getAsLong()));

		JsonObject result = new JsonObject();
		result.addProperty("windowMs", sinceMs);
		result.addProperty("total", matched.size());
		result.addProperty("dropped", droppedFor(channels));
		result.add("events", tail(matched, limit));
		return result;
	}

	private List<Deque<JsonObject>> allBuffers()
	{
		return List.of(scripts, vars, varcs, projectiles, effects, chat,
			combat, experience, containers, sounds, spawns, loot, overheads,
			interfaces, actions);
	}

	private static JsonArray quantityChanges(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Set<Integer> itemIds = new HashSet<>(before.keySet());
		itemIds.addAll(after.keySet());
		JsonArray changes = new JsonArray();
		for (int itemId : itemIds)
		{
			int was = before.getOrDefault(itemId, 0);
			int now = after.getOrDefault(itemId, 0);
			if (was == now)
			{
				continue;
			}
			JsonObject change = new JsonObject();
			change.addProperty("itemId", itemId);
			change.addProperty("was", was);
			change.addProperty("now", now);
			change.addProperty("delta", now - was);
			changes.add(change);
		}
		return changes;
	}

	private long droppedFor(String channel)
	{
		return droppedByChannel.getOrDefault(channel, 0L);
	}

	private long droppedFor(Set<String> channels)
	{
		if (channels == null)
		{
			return droppedByChannel.values().stream().mapToLong(Long::longValue).sum();
		}
		long dropped = 0;
		for (String channel : channels)
		{
			dropped += droppedFor(channel);
		}
		return dropped;
	}

	private JsonObject baseEntry(String channel, int tick)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("seq", seq++);
		entry.addProperty("channel", channel);
		entry.addProperty("timeMs", System.currentTimeMillis());
		entry.addProperty("tick", tick);
		return entry;
	}

	private void push(Deque<JsonObject> buffer, int max, JsonObject entry)
	{
		buffer.addLast(entry);
		while (buffer.size() > max)
		{
			buffer.removeFirst();
			droppedByChannel.merge(entry.get("channel").getAsString(), 1L, Long::sum);
		}
	}

	private static JsonArray tail(List<JsonObject> list, int limit)
	{
		JsonArray out = new JsonArray();
		for (int i = Math.max(0, list.size() - limit); i < list.size(); i++)
		{
			out.add(list.get(i));
		}
		return out;
	}
}