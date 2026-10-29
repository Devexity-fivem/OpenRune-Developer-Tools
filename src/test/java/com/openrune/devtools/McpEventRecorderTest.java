/*
 * Copyright (c) 2026, Devexity
 * BSD 2-Clause License
 */
package com.openrune.devtools;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpEventRecorderTest
{
	private final McpEventRecorder recorder = new McpEventRecorder();

	@Test
	void varChangesDedupeUnchangedValues()
	{
		recorder.recordVar("varbit", 1, 0, 10);
		recorder.recordVar("varbit", 1, 0, 11); // unchanged -> not recorded
		recorder.recordVar("varbit", 1, 5, 12);
		JsonObject history = recorder.varHistory(60_000, "varbit", 1, 100);
		assertEquals(2, history.get("total").getAsInt());
		assertEquals(5, history.getAsJsonArray("events").get(1).getAsJsonObject()
			.get("value").getAsInt());
		assertEquals(0, history.getAsJsonArray("events").get(1).getAsJsonObject()
			.get("oldValue").getAsInt());
	}

	@Test
	void historyReportsDroppedEvents()
	{
		// VAR_BUFFER is 3000; overflow by a few and expect explicit drop accounting.
		for (int i = 0; i < 3010; i++)
		{
			recorder.recordVar("varp", 7, i, i);
		}
		JsonObject history = recorder.varHistory(60_000, "varp", null, 5000);
		assertEquals(3000, history.get("total").getAsInt());
		assertEquals(10, history.get("dropped").getAsLong());
		// Oldest entries were evicted: first retained seq should be 11.
		assertEquals(11, history.getAsJsonArray("events").get(0).getAsJsonObject()
			.get("seq").getAsLong());
	}

	@Test
	void limitReturnsNewestNotOldest()
	{
		for (int i = 0; i < 5; i++)
		{
			recorder.recordChat("GAMEMESSAGE", null, null, "msg " + i, i);
		}
		JsonObject history = recorder.chatHistory(60_000, null, null, 2);
		assertEquals(5, history.get("total").getAsInt());
		assertEquals(2, history.getAsJsonArray("events").size());
		assertEquals("msg 4", history.getAsJsonArray("events").get(1).getAsJsonObject()
			.get("message").getAsString());
	}

	@Test
	void chatFiltersByTypeAndName()
	{
		recorder.recordChat("GAMEMESSAGE", null, null, "you die", 1);
		recorder.recordChat("PUBLICCHAT", "Bob", null, "hi", 2);
		JsonObject byType = recorder.chatHistory(60_000, "publicchat", null, 100);
		assertEquals(1, byType.get("total").getAsInt());
		JsonObject byName = recorder.chatHistory(60_000, null, "bob", 100);
		assertEquals(1, byName.get("total").getAsInt());
	}

	@Test
	void combatTicksTrackedSeparatelyForDealtAndReceived()
	{
		recorder.recordCombatTick(true, false, 50);   // hit on me
		recorder.recordCombatTick(false, true, 60);   // my hit on something else
		recorder.recordCombatTick(true, true, 70);    // mine AND on me -> only onMe
		assertEquals(70, recorder.lastHitOnMeTick());
		assertEquals(60, recorder.lastMyHitTick());
	}

	@Test
	void experienceBaselineIsNotAnEvent()
	{
		recorder.recordExperience("Attack", 1000, 50, 50, 1); // baseline, dropped
		recorder.recordExperience("Attack", 1050, 50, 50, 2); // +50 gain
		recorder.recordExperience("Attack", 1050, 50, 50, 3); // unchanged, dropped
		JsonObject history = recorder.experienceHistory(60_000, "Attack", 100);
		assertEquals(1, history.get("total").getAsInt());
		assertEquals(50, history.getAsJsonArray("events").get(0).getAsJsonObject()
			.get("xpGained").getAsInt());
	}

	@Test
	void containerDiffsAgainstBaseline()
	{
		recorder.recordContainer(93, Map.of(4151, 1, 995, 500), 1); // baseline
		recorder.recordContainer(93, Map.of(4151, 1, 995, 400), 2); // -100 coins
		recorder.recordContainer(93, Map.of(4151, 1, 995, 400), 3); // no change
		JsonObject history = recorder.containerHistory(60_000, 93, 100);
		assertEquals(1, history.get("total").getAsInt());
		JsonObject change = history.getAsJsonArray("events").get(0).getAsJsonObject()
			.getAsJsonArray("changes").get(0).getAsJsonObject();
		assertEquals(995, change.get("itemId").getAsInt());
		assertEquals(-100, change.get("delta").getAsInt());
	}

	@Test
	void timelineMergesChannelsInSequenceOrder()
	{
		recorder.recordChat("GAMEMESSAGE", null, null, "a", 5);
		recorder.recordVar("varbit", 1, 3, 6);
		recorder.recordHitsplat(2, 12, "npc", "Goblin", 812, 10, 20, 7);
		JsonObject tl = recorder.timeline(60_000, null, null, null, 100);
		assertEquals(3, tl.get("total").getAsInt());
		long prev = -1;
		for (var e : tl.getAsJsonArray("events"))
		{
			long seq = e.getAsJsonObject().get("seq").getAsLong();
			assertTrue(seq > prev);
			prev = seq;
		}
		JsonObject chatOnly = recorder.timeline(60_000, Set.of("chat"), null, null, 100);
		assertEquals(1, chatOnly.get("total").getAsInt());
		JsonObject tickFiltered = recorder.timeline(60_000, null, 6, 6, 100);
		assertEquals(1, tickFiltered.get("total").getAsInt());
	}

	@Test
	void resetClearsHistoriesBaselinesAndDropCounts()
	{
		for (int i = 0; i < 3010; i++)
		{
			recorder.recordVar("varp", 7, i, i);
		}
		recorder.recordChat("GAMEMESSAGE", null, null, "x", 1);
		recorder.recordCombatTick(true, false, 9);
		recorder.reset();
		assertEquals(0, recorder.varHistory(60_000, "varp", null, 100).get("total").getAsInt());
		assertEquals(0, recorder.varHistory(60_000, "varp", null, 100).get("dropped").getAsLong());
		assertEquals(0, recorder.chatHistory(60_000, null, null, 100).get("total").getAsInt());
		assertEquals(-1, recorder.lastHitOnMeTick());
		// baselines reset too: same value records again as a "new" first sighting
		recorder.recordVar("varp", 7, 5, 1);
		assertEquals(1, recorder.varHistory(60_000, "varp", 7, 100).get("total").getAsInt());
	}

	@Test
	void spawnHistoryRecordsLifecycleKinds()
	{
		recorder.recordSpawn("spawn", "npc", "Jad", 100, 3127, null, 3200, 3200, 10);
		recorder.recordSpawn("transform", "npc", "Jad", 100, 3128, 3127, 3200, 3200, 20);
		recorder.recordSpawn("despawn", "npc", "Goblin", 100, null, null, 3200, 3200, 30);
		assertEquals(3, recorder.spawnHistory(60_000, null, null, 100).get("total").getAsInt());
		assertEquals(1, recorder.spawnHistory(60_000, "transform", null, 100).get("total").getAsInt());
		assertEquals(2, recorder.spawnHistory(60_000, null, "jad", 100).get("total").getAsInt());
		var transform = recorder.spawnHistory(60_000, "transform", null, 1)
			.getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals(3127, transform.get("oldNpcId").getAsInt());
		assertEquals(3128, transform.get("npcId").getAsInt());
	}

	@Test
	void lootHistoryRecordsDropsAndPickups()
	{
		recorder.recordLoot("spawned", 526, "Bones", 1, 3200, 3201, 10);
		recorder.recordLoot("despawned", 995, "Coins", 50, 3200, 3201, 20);
		assertEquals(2, recorder.lootHistory(60_000, null, null, 100).get("total").getAsInt());
		assertEquals(1, recorder.lootHistory(60_000, "spawned", null, 100).get("total").getAsInt());
		assertEquals(1, recorder.lootHistory(60_000, null, "bones", 100).get("total").getAsInt());
	}

	@Test
	void overheadHistoryMatchesActorOrText()
	{
		recorder.recordOverhead("npc", "Vorkath", 8059, "More power!", 10);
		recorder.recordOverhead("player", "devin", null, "Ouch", 11);
		assertEquals(1, recorder.overheadHistory(60_000, "vorkath", 100).get("total").getAsInt());
		assertEquals(1, recorder.overheadHistory(60_000, "more power", 100).get("total").getAsInt());
		assertEquals(2, recorder.overheadHistory(60_000, null, 100).get("total").getAsInt());
	}

	@Test
	void newChannelsAppearInTimeline()
	{
		recorder.recordSpawn("spawn", "npc", "Goblin", 50, 3029, null, 1, 2, 5);
		recorder.recordLoot("spawned", 995, "Coins", 100, 1, 2, 6);
		JsonObject tl = recorder.timeline(60_000, Set.of("spawn", "loot"), null, null, 100);
		assertEquals(2, tl.get("total").getAsInt());
	}

	@Test
	void interfaceHistoryRecordsOpensAndCloses()
	{
		recorder.recordInterface("opened", 300, 10);
		recorder.recordInterface("opened", 335, 11);
		recorder.recordInterface("closed", 300, 20);
		assertEquals(3, recorder.interfaceHistory(60_000, null, null, 100).get("total").getAsInt());
		assertEquals(2, recorder.interfaceHistory(60_000, 300, null, 100).get("total").getAsInt());
		assertEquals(1, recorder.interfaceHistory(60_000, null, "closed", 100).get("total").getAsInt());
	}

	@Test
	void actionHistoryRecordsAndFiltersMenuInvocations()
	{
		recorder.recordAction("Talk-to", "Man", "NPC_FIRST_OPTION", 0, 0, 42, -1, 10);
		recorder.recordAction("Walk here", "", "WALK", 10, 10, 0, -1, 11);
		recorder.recordAction("Use", "<col=ff9040>Shark</col> -> <col=ffff>Door", "ITEM_USE_ON_GAME_OBJECT", 51, 50, 1535, 385, 12);
		assertEquals(3, recorder.actionHistory(60_000, null, 100).get("total").getAsInt());
		assertEquals(1, recorder.actionHistory(60_000, "walk", 100).get("total").getAsInt());
		assertEquals(1, recorder.actionHistory(60_000, "door", 100).get("total").getAsInt());
		var use = recorder.actionHistory(60_000, "use_on", 1)
			.getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals(385, use.get("itemId").getAsInt());
		assertFalse(use.get("target").getAsString().contains("<col"));
	}

	@Test
	void resetClearsNewChannels()
	{
		recorder.recordInterface("opened", 300, 1);
		recorder.recordAction("Walk here", "", "WALK", 1, 2, 0, -1, 2);
		recorder.reset();
		assertEquals(0, recorder.interfaceHistory(60_000, null, null, 100).get("total").getAsInt());
		assertEquals(0, recorder.actionHistory(60_000, null, 100).get("total").getAsInt());
	}

	@Test
	void channelHistoryQueriesNewBuffers()
	{
		recorder.recordObject("spawned", "game", 1234, "Fire", 3200, 3201, 0, 10);
		recorder.recordObject("despawned", "wall", 1551, "Door", 3201, 3202, 0, 11);
		recorder.recordGfx(452, 3200, 3200, 0, 12);
		recorder.recordInteract("npc", "Vorkath", 5, "player", "devin", -1, 13);
		recorder.recordSocial("clan_member_joined", "alice", "3", 14);
		recorder.recordWorld("world_changed", 302, null, 15);

		assertEquals(2, recorder.channelHistory("object", 60_000, null, null, 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("object", 60_000, "kind", "spawned", 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("object", 60_000, "name", "fire", 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("gfx", 60_000, "gfxId", "452", 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("interact", 60_000, "target", "dev", 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("social", 60_000, "kind", "clan_member_joined", 100)
			.get("total").getAsInt());
		assertEquals(1, recorder.channelHistory("world", 60_000, "worldId", "302", 100)
			.get("total").getAsInt());
		assertEquals(0, recorder.channelHistory("bogus", 60_000, null, null, 100)
			.get("total").getAsInt());

		recorder.reset();
		assertEquals(0, recorder.channelHistory("object", 60_000, null, null, 100)
			.get("total").getAsInt());
	}

	@Test
	void recordFakeXpMarksFakeEvents()
	{
		recorder.recordFakeXp("ATTACK", 42, 10);
		JsonObject history = recorder.experienceHistory(60_000, null, 100);
		JsonObject event = history.getAsJsonArray("events").get(0).getAsJsonObject();
		assertTrue(event.get("fake").getAsBoolean());
		assertEquals(42, event.get("xpGained").getAsInt());
	}

	@Test
	void firstEventSinceCoversNewChannels()
	{
		recorder.recordObject("spawned", "game", 1234, "Fire", 1, 2, 0, 1);
		recorder.recordInteract("npc", "Boss", 3, "player", "me", -1, 2);
		assertEquals(1234, recorder.firstEventSince("object", 0, e -> true)
			.get("objectId").getAsInt());
		assertEquals("Boss", recorder.firstEventSince("interact", 0,
			e -> e.has("target")).get("source").getAsString());
	}

	@Test
	void firstEventSinceReturnsNewestMatchAndRespectsCutoff()
	{
		recorder.recordChat("GAMEMESSAGE", null, null, "first", 1);
		recorder.recordChat("GAMEMESSAGE", null, null, "second", 2);
		recorder.recordChat("GAMEMESSAGE", null, null, "third", 3);

		// Newest matching event wins.
		JsonObject hit = recorder.firstEventSince("chat", 0, e -> true);
		assertEquals("third", hit.get("message").getAsString());

		// Predicate is honoured.
		assertEquals("first", recorder.firstEventSince("chat", 0,
			e -> e.get("message").getAsString().equals("first")).get("message").getAsString());
		assertNull(recorder.firstEventSince("chat", 0, e -> false));

		// Cutoff excludes everything older than it.
		long cutoff = System.currentTimeMillis() + 60_000;
		assertNull(recorder.firstEventSince("chat", cutoff, e -> true));

		// Unknown channel is a miss, not an error.
		assertNull(recorder.firstEventSince("bogus", 0, e -> true));
	}

	@Test
	void scriptArgsAreTruncated()
	{
		Object[] args = new Object[200];
		java.util.Arrays.fill(args, "xxxxxxxxxx");
		recorder.recordScript(42, args, 1);
		JsonObject history = recorder.scriptHistory(60_000, 42, 10);
		String stored = history.getAsJsonArray("events").get(0).getAsJsonObject()
			.get("args").getAsString();
		assertTrue(stored.length() <= 165);
		assertFalse(stored.contains("xxxxxxxxxx]"));
	}
}
