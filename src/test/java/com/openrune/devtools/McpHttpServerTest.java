/*
 * Copyright (c) 2026, Devexity
 * BSD 2-Clause License
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Exercises the real HTTP transport end to end on a loopback port with a mocked
 * client. These are transport/protocol tests, not in-game behavior tests.
 */
class McpHttpServerTest
{
	private McpHttpServer server;
	private int port;

	@BeforeEach
	void startServer() throws Exception
	{
		Client client = Mockito.mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		ClientThread clientThread = Mockito.mock(ClientThread.class);
		// Run client-thread work inline so the test does not need a live client loop.
		doAnswer(invocation ->
		{
			invocation.<BooleanSupplier>getArgument(0).getAsBoolean();
			return null;
		}).when(clientThread).invoke(any(BooleanSupplier.class));
		doAnswer(invocation ->
		{
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		DrawManager drawManager = Mockito.mock(DrawManager.class);
		server = new McpHttpServer(0, new McpTools(client, clientThread, drawManager, new McpEventRecorder()));
		server.start();
		port = server.boundPort();
		assertTrue(port > 0);
	}

	@AfterEach
	void stopServer()
	{
		if (server != null)
		{
			server.stop();
		}
	}

	private JsonElement post(Object body) throws IOException
	{
		return post(body, null);
	}

	private JsonElement post(Object body, String origin) throws IOException
	{
		HttpURLConnection conn = openPost(origin);
		byte[] bytes = McpHttpServer.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		conn.setFixedLengthStreamingMode(bytes.length);
		try (OutputStream out = conn.getOutputStream())
		{
			out.write(bytes);
		}
		int status = conn.getResponseCode();
		if (status == 202)
		{
			conn.disconnect();
			return null;
		}
		InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
		String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		conn.disconnect();
		return new JsonParser().parse(text);
	}

	private HttpURLConnection openPost(String origin) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp")
			.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		if (origin != null)
		{
			conn.setRequestProperty("Origin", origin);
		}
		return conn;
	}

	private static JsonObject request(int id, String method, JsonObject params)
	{
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		if (params != null)
		{
			msg.add("params", params);
		}
		return msg;
	}

	@Test
	void initializeReportsForkIdentityAndCapabilities() throws Exception
	{
		JsonObject response = post(request(1, "initialize", new JsonObject())).getAsJsonObject();
		JsonObject result = response.getAsJsonObject("result");
		assertEquals("openrune-devtools", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.has("instructions"));
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
	}

	@Test
	void toolsListContainsAllExpectedTools() throws Exception
	{
		JsonObject response = post(request(1, "tools/list", null)).getAsJsonObject();
		JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
		java.util.Set<String> names = new java.util.HashSet<>();
		for (var t : tools)
		{
			JsonObject tool = t.getAsJsonObject();
			names.add(tool.get("name").getAsString());
			// Every tool must carry an object input schema.
			assertEquals("object", tool.getAsJsonObject("inputSchema").get("type").getAsString(),
				tool.get("name").getAsString());
		}
		for (String expected : new String[]{
			"observe", "list_actions", "act", "get_status", "get_combat_state",
			"set_prayer", "item_action", "object_action", "wait_for", "get_timeline",
			"get_combat_history", "interact_npc", "interact_object",
			"snapshot_state", "diff_state", "run_batch",
			"get_special_attack", "toggle_special_attack",
			"get_attack_style", "set_attack_style", "cast_spell", "list_spells",
			"is_walkable", "get_destination", "search_interfaces", "bank_action",
			"get_spawn_history", "get_loot_history", "get_overhead_history"})
		{
			assertTrue(names.contains(expected), "missing tool " + expected);
		}
	}

	@Test
	void unknownToolIsAnInBandErrorResult() throws Exception
	{
		JsonObject params = new JsonObject();
		params.addProperty("name", "not_a_tool");
		params.add("arguments", new JsonObject());
		JsonObject response = post(request(1, "tools/call", params)).getAsJsonObject();
		JsonObject result = response.getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject()
			.get("text").getAsString().contains("Unknown tool"));
	}

	@Test
	void missingToolNameIsInvalidParams() throws Exception
	{
		JsonObject response = post(request(1, "tools/call", new JsonObject())).getAsJsonObject();
		assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void unknownMethodIsMinus32601() throws Exception
	{
		JsonObject response = post(request(1, "resources/list", null)).getAsJsonObject();
		assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void notificationGetsNoResponseBody() throws Exception
	{
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", "notifications/initialized");
		assertEquals(null, post(msg));
	}

	@Test
	void batchReturnsOneResponsePerRequest() throws Exception
	{
		JsonArray batch = new JsonArray();
		batch.add(request(1, "ping", null));
		batch.add(request(2, "initialize", new JsonObject()));
		JsonObject notification = new JsonObject();
		notification.addProperty("jsonrpc", "2.0");
		notification.addProperty("method", "notifications/initialized");
		batch.add(notification);
		JsonArray responses = post(batch).getAsJsonArray();
		assertEquals(2, responses.size());
	}

	@Test
	void malformedJsonIsAParseError() throws Exception
	{
		HttpURLConnection conn = openPost(null);
		byte[] bytes = "{not json".getBytes(StandardCharsets.UTF_8);
		conn.setFixedLengthStreamingMode(bytes.length);
		try (OutputStream out = conn.getOutputStream())
		{
			out.write(bytes);
		}
		assertEquals(400, conn.getResponseCode());
		JsonObject response = new JsonParser()
			.parse(new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
			.getAsJsonObject();
		assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
		conn.disconnect();
	}

	@Test
	void oversizedBodyIsRefused() throws Exception
	{
		HttpURLConnection conn = openPost(null);
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setChunkedStreamingMode(8192);
		try (OutputStream out = conn.getOutputStream())
		{
			out.write(new byte[2 * 1024 * 1024 + 16]);
		}
		catch (IOException ignored)
		{
			// the server may close the connection before the client finishes writing
		}
		assertEquals(413, conn.getResponseCode());
		conn.disconnect();
	}

	@Test
	void crossSiteOriginIsRejected() throws Exception
	{
		// HttpURLConnection will not send the restricted Origin header, so this
		// request is written to the socket directly.
		byte[] body = McpHttpServer.GSON.toJson(request(1, "ping", null)).getBytes(StandardCharsets.UTF_8);
		assertEquals(403, rawPostStatus("https://evil.example", body));
		assertEquals(200, rawPostStatus("http://127.0.0.1:" + port, body));
		// No Origin header at all (how MCP clients connect) is still allowed.
		JsonElement ok = post(request(1, "ping", null));
		assertNotNull(ok);
	}

	private int rawPostStatus(String origin, byte[] body) throws IOException
	{
		try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port))
		{
			String headers = "POST /mcp HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Content-Type: application/json\r\n"
				+ "Origin: " + origin + "\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Connection: close\r\n\r\n";
			OutputStream out = socket.getOutputStream();
			out.write(headers.getBytes(StandardCharsets.UTF_8));
			out.write(body);
			out.flush();
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return Integer.parseInt(response.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3));
		}
	}

	@Test
	void getOnMcpIsNotAllowed() throws Exception
	{
		HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp")
			.openConnection();
		assertEquals(405, conn.getResponseCode());
		conn.disconnect();
	}

	@Test
	void logEndpointExposesEntriesToolsAndServerInfo() throws Exception
	{
		JsonObject params = new JsonObject();
		params.addProperty("name", "not_a_tool");
		post(request(1, "tools/call", params));
		HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/log")
			.openConnection();
		JsonObject payload = new JsonParser()
			.parse(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.getAsJsonObject();
		conn.disconnect();
		assertEquals("openrune-devtools", payload.get("server").getAsString());
		assertTrue(payload.getAsJsonArray("tools").size() > 0);
		assertEquals(1, payload.getAsJsonArray("entries").size());
		assertTrue(payload.getAsJsonArray("entries").get(0).getAsJsonObject()
			.get("isError").getAsBoolean());
	}

	@Test
	void getClientStateRunsThroughTheFullDispatchPath() throws Exception
	{
		JsonObject params = new JsonObject();
		params.addProperty("name", "get_client_state");
		params.add("arguments", new JsonObject());
		JsonObject response = post(request(1, "tools/call", params)).getAsJsonObject();
		JsonObject result = response.getAsJsonObject("result");
		// LOGIN_SCREEN mocked client: the tool answers, not errors.
		assertFalse(result.has("isError"));
		JsonObject content = result.getAsJsonArray("content").get(0).getAsJsonObject();
		JsonObject state = new JsonParser().parse(content.get("text").getAsString()).getAsJsonObject();
		assertEquals("LOGIN_SCREEN", state.get("gameState").getAsString());
	}
}
