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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal MCP server over streamable HTTP: accepts JSON-RPC 2.0 messages on
 * POST /mcp and serves the tools implemented by {@link McpTools}.
 */
@Slf4j
class McpHttpServer
{
	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private static final String PROTOCOL_VERSION = "2025-03-26";
	private static final String SERVER_NAME = "openrune-devtools";
	/** Tool-call requests are tiny; refuse huge bodies instead of buffering them. */
	private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

	private final int port;
	private final McpTools tools;
	private final McpActivityLog activityLog = new McpActivityLog();
	private final long startedAt = System.currentTimeMillis();

	private HttpServer server;
	private ExecutorService executor;

	/** Thread currently executing a tool call; interrupted on cancel or when a newer call arrives. */
	private volatile Thread activeToolThread;

	McpHttpServer(int port, McpTools tools)
	{
		this.port = port;
		this.tools = tools;
	}

	void start() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		executor = Executors.newFixedThreadPool(4, r ->
		{
			Thread t = new Thread(r, "mcp-server");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(executor);
		server.createContext("/mcp", this::handle);
		server.createContext("/log", this::handleLog);
		server.createContext("/", this::handleDashboard);
		server.start();
	}

	void stop()
	{
		if (server != null)
		{
			server.stop(0);
			server = null;
		}
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	/** The port the server actually bound (differs from the configured port when it was 0). */
	int boundPort()
	{
		return server != null ? server.getAddress().getPort() : -1;
	}

	private void handle(HttpExchange exchange) throws IOException
	{
		try
		{
			if (!"POST".equals(exchange.getRequestMethod()))
			{
				respond(exchange, 405, "{\"error\":\"POST only\"}");
				return;
			}
			if (!localOrigin(exchange))
			{
				// Browsers always send Origin on cross-origin POSTs; MCP clients send none.
				// Without this check any web page could form-POST tool calls to the loopback
				// server (the response is unreadable to it, but the action still runs).
				respond(exchange, 403, "{\"error\":\"cross-origin requests are not allowed\"}");
				return;
			}

			byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
			if (bodyBytes.length > MAX_REQUEST_BYTES)
			{
				respond(exchange, 413, "{\"error\":\"request too large\"}");
				return;
			}
			String body = new String(bodyBytes, StandardCharsets.UTF_8);
			JsonElement parsed;
			try
			{
				parsed = new JsonParser().parse(body);
			}
			catch (RuntimeException e)
			{
				respond(exchange, 400, GSON.toJson(errorResponse(null, -32700, "Parse error: " + e.getMessage())));
				return;
			}

			if (parsed.isJsonArray())
			{
				// JSON-RPC batch: some streamable-HTTP clients batch requests/notifications.
				JsonArray responses = new JsonArray();
				boolean onlyNotifications = true;
				for (JsonElement element : parsed.getAsJsonArray())
				{
					JsonObject response = handleMessage(element);
					if (response != null)
					{
						onlyNotifications = false;
						responses.add(response);
					}
				}
				if (onlyNotifications)
				{
					exchange.sendResponseHeaders(202, -1);
					exchange.close();
				}
				else
				{
					respond(exchange, 200, GSON.toJson(responses));
				}
				return;
			}

			JsonObject response = handleMessage(parsed);
			if (response == null)
			{
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
				return;
			}
			respond(exchange, 200, GSON.toJson(response));
		}
		catch (Exception e)
		{
			log.warn("Unhandled MCP server error", e);
			exchange.close();
		}
	}

	/**
	 * Whether the request may proceed: no Origin header (MCP clients) or a loopback Origin
	 * (same-origin dashboard fetches). Anything else is a cross-site browser POST.
	 */
	private static boolean localOrigin(HttpExchange exchange)
	{
		String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin == null || origin.isEmpty())
		{
			return true;
		}
		String host;
		try
		{
			host = new java.net.URI(origin).getHost();
		}
		catch (Exception e)
		{
			return false;
		}
		if (host == null)
		{
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		return "localhost".equals(h) || "127.0.0.1".equals(h)
			|| "::1".equals(h) || "[::1]".equals(h) || h.endsWith(".localhost");
	}

	/** Handle one JSON-RPC message; null return means it was a notification (202, no body). */
	private JsonObject handleMessage(JsonElement message)
	{
		if (!message.isJsonObject())
		{
			return errorResponse(null, -32600, "Invalid request: expected a JSON-RPC object");
		}
		JsonObject msg = message.getAsJsonObject();

		// Notifications (no id) get acknowledged with 202 and no body
		if (!msg.has("id"))
		{
			String method = msg.has("method") && msg.get("method").isJsonPrimitive()
				? msg.get("method").getAsString() : "";
			if ("notifications/cancelled".equals(method))
			{
				// User stopped the task in the MCP client; abort the in-flight action
				cancelActiveTool();
			}
			return null;
		}

		JsonElement id = msg.get("id");
		JsonElement methodElement = msg.get("method");
		if (methodElement == null || !methodElement.isJsonPrimitive())
		{
			return errorResponse(id, -32600, "Invalid request: missing method");
		}
		String method = methodElement.getAsString();
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
			? msg.getAsJsonObject("params")
			: new JsonObject();

		try
		{
			JsonObject response = new JsonObject();
			response.addProperty("jsonrpc", "2.0");
			response.add("id", id);
			response.add("result", dispatch(method, params));
			return response;
		}
		catch (MethodNotFoundException e)
		{
			return errorResponse(id, -32601, e.getMessage());
		}
		catch (InvalidParamsException e)
		{
			return errorResponse(id, -32602, e.getMessage());
		}
		catch (Exception e)
		{
			log.debug("MCP request failed", e);
			return errorResponse(id, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private JsonElement dispatch(String method, JsonObject params) throws Exception
	{
		switch (method)
		{
			case "initialize":
			{
				JsonObject result = new JsonObject();
				result.addProperty("protocolVersion",
					params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject serverInfo = new JsonObject();
				serverInfo.addProperty("name", SERVER_NAME);
				serverInfo.addProperty("version", serverVersion());
				result.add("serverInfo", serverInfo);
				result.addProperty("instructions",
					"RuneLite game client control for testing private servers.\n"
					+ "Reading state:\n"
					+ "- observe: one client-thread pass - vitals, combat, inventory, nearby NPCs/objects/items, dialogue\n"
					+ "- get_combat_state: cheap combat poll (in combat, attackers, target, prayers, ticks since hits)\n"
					+ "- list_npcs / list_objects / list_ground_items / get_inventory / get_dialogue answer focused questions\n"
					+ "- get_timeline + per-channel history tools explain what just happened (projectiles, hitsplats, vars, xp)\n"
					+ "Acting:\n"
					+ "- list_actions: every action available now as a numbered menu; act(id) runs one - refuses stale menus\n"
					+ "- inventory item (drop/eat/wield/use): item_action - works with the inventory tab closed\n"
					+ "- scene object: object_action (no hover needed) or interact_object (on-screen objects)\n"
					+ "- NPC: interact_npc, or get_npc_menu + click_menu_option for server-added options\n"
					+ "- other player (trade/follow): interact_player; widget right-click option: widget_action\n"
					+ "- prayer on/off: set_prayer; raw escape hatch: invoke_menu_action\n"
					+ "Waiting: wait_for blocks until a condition is met (idle, arrival, npc death, dialogue, interface,\n"
					+ "chat, hitsplat, projectile, animation, var, combat) - never poll with screenshots or repeated calls.\n"
					+ "Only call screenshot when the user explicitly asks to SEE something or for visual layout verification.");
				return result;
			}
			case "ping":
				return new JsonObject();
			case "tools/list":
			{
				JsonObject result = new JsonObject();
				result.add("tools", tools.listTools());
				return result;
			}
			case "tools/call":
			{
				if (!params.has("name") || !params.get("name").isJsonPrimitive())
				{
					throw new InvalidParamsException("Missing params.name for tools/call");
				}
				String name = params.get("name").getAsString();
				JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
					? params.getAsJsonObject("arguments")
					: new JsonObject();
				long start = System.nanoTime();
				// A new call preempts any stale action still sleeping from a stopped task
				cancelActiveTool();
				activeToolThread = Thread.currentThread();
				JsonObject result;
				try
				{
					result = tools.callTool(name, args);
				}
				catch (InterruptedException e)
				{
					result = errorResult("Cancelled");
				}
				catch (Exception e)
				{
					// Tool failures are reported in-band so the model can react to them
					result = errorResult("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
				}
				finally
				{
					activeToolThread = null;
					Thread.interrupted(); // clear a late interrupt so it can't leak into the next request
				}
				activityLog.record(name, args, result, (System.nanoTime() - start) / 1_000_000L);
				return result;
			}
			default:
				throw new MethodNotFoundException("Method not found: " + method);
		}
	}

	private void handleLog(HttpExchange exchange) throws IOException
	{
		try
		{
			long after = 0;
			String query = exchange.getRequestURI().getQuery();
			if (query != null)
			{
				for (String param : query.split("&"))
				{
					if (param.startsWith("after="))
					{
						try
						{
							after = Long.parseLong(param.substring("after=".length()));
						}
						catch (NumberFormatException ignored)
						{
							// keep 0
						}
					}
				}
			}
			JsonObject payload = activityLog.since(after);
			payload.add("tools", tools.toolNames());
			payload.addProperty("server", SERVER_NAME);
			payload.addProperty("version", serverVersion());
			payload.addProperty("uptimeMs", System.currentTimeMillis() - startedAt);
			respond(exchange, 200, GSON.toJson(payload));
		}
		catch (Exception e)
		{
			log.warn("MCP log endpoint error", e);
			exchange.close();
		}
	}

	private static String serverVersion()
	{
		String version = McpHttpServer.class.getPackage().getImplementationVersion();
		return version != null ? version : "dev";
	}

	private void handleDashboard(HttpExchange exchange) throws IOException
	{
		String path = exchange.getRequestURI().getPath();
		if (!"/".equals(path) && !"/index.html".equals(path))
		{
			respond(exchange, 404, "{\"error\":\"not found\"}");
			return;
		}
		byte[] html;
		try (InputStream in = McpHttpServer.class.getResourceAsStream("dashboard.html"))
		{
			if (in == null)
			{
				respond(exchange, 500, "{\"error\":\"dashboard.html resource missing\"}");
				return;
			}
			html = in.readAllBytes();
		}
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, html.length);
		try (OutputStream out = exchange.getResponseBody())
		{
			out.write(html);
		}
	}

	private void cancelActiveTool()
	{
		Thread active = activeToolThread;
		if (active != null && active != Thread.currentThread() && active.isAlive())
		{
			active.interrupt();
		}
	}

	private static JsonObject errorResult(String message)
	{
		JsonObject item = new JsonObject();
		item.addProperty("type", "text");
		item.addProperty("text", message);
		JsonArray content = new JsonArray();
		content.add(item);
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", true);
		return result;
	}

	private static JsonObject errorResponse(JsonElement id, int code, String message)
	{
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody())
		{
			out.write(bytes);
		}
	}

	private static class MethodNotFoundException extends Exception
	{
		MethodNotFoundException(String message)
		{
			super(message);
		}
	}

	private static class InvalidParamsException extends Exception
	{
		InvalidParamsException(String message)
		{
			super(message);
		}
	}
}
