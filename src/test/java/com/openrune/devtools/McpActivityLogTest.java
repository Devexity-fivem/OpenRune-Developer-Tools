/*
 * Copyright (c) 2026, Devexity
 * BSD 2-Clause License
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpActivityLogTest
{
	private final McpActivityLog log = new McpActivityLog();

	private static JsonObject textResult(String text)
	{
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray arr = new JsonArray();
		arr.add(content);
		JsonObject result = new JsonObject();
		result.add("content", arr);
		return result;
	}

	private static JsonObject imageResult(String base64)
	{
		JsonObject content = new JsonObject();
		content.addProperty("type", "image");
		content.addProperty("data", base64);
		JsonArray arr = new JsonArray();
		arr.add(content);
		JsonObject result = new JsonObject();
		result.add("content", arr);
		return result;
	}

	@Test
	void retainsAtMostHundredEntries()
	{
		for (int i = 0; i < 120; i++)
		{
			log.record("ping", new JsonObject(), textResult("ok " + i), 1);
		}
		JsonObject payload = log.since(0);
		assertEquals(100, payload.getAsJsonArray("entries").size());
		assertEquals(120, payload.get("last").getAsLong());
		// Oldest retained entry is id 21.
		assertEquals(21, payload.getAsJsonArray("entries").get(0).getAsJsonObject()
			.get("id").getAsLong());
	}

	@Test
	void sinceFiltersById()
	{
		for (int i = 0; i < 5; i++)
		{
			log.record("ping", new JsonObject(), textResult("ok"), 1);
		}
		JsonObject payload = log.since(3);
		assertEquals(2, payload.getAsJsonArray("entries").size());
	}

	@Test
	void marksErrorsInEntries()
	{
		JsonObject error = textResult("Error: nope");
		error.addProperty("isError", true);
		log.record("boom", new JsonObject(), error, 2);
		JsonObject entry = log.since(0).getAsJsonArray("entries").get(0).getAsJsonObject();
		assertTrue(entry.get("isError").getAsBoolean());
		assertEquals("boom", entry.get("tool").getAsString());
	}

	@Test
	void dropsOldImagePayloadsWhenImageBudgetExceeded()
	{
		// MAX_IMAGE_BYTES is 16 MiB; three 8 MiB payloads force eviction of the first.
		String big = "A".repeat(8 * 1024 * 1024);
		log.record("screenshot", new JsonObject(), imageResult(big), 10);
		log.record("screenshot", new JsonObject(), imageResult(big), 10);
		log.record("screenshot", new JsonObject(), imageResult(big), 10);

		JsonArray entries = log.since(0).getAsJsonArray("entries");
		assertEquals(3, entries.size());
		JsonObject first = entries.get(0).getAsJsonObject();
		assertTrue(first.has("imagesDropped") && first.get("imagesDropped").getAsBoolean());
		assertEquals(0, first.getAsJsonArray("images").size());
		// The newest entries still carry their payloads.
		JsonObject last = entries.get(2).getAsJsonObject();
		assertEquals(1, last.getAsJsonArray("images").size());
	}
}
