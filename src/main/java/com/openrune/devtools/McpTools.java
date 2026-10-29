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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.awt.Canvas;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Quest;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.World;
import net.runelite.api.VarPlayer;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WallObject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Implements the MCP tools exposed by {@link OpenRuneDevToolsPlugin}: screenshots,
 * widget inspection by packed component id, and mouse control on the game canvas.
 */
class McpTools
{
	private static final long CLIENT_THREAD_TIMEOUT_MS = 5_000L;
	private static final long FRAME_TIMEOUT_MS = 5_000L;
	private static final int MAX_COMPONENTS_PER_INTERFACE = 2048;
	private static final int COMPONENT_SCAN_GAP = 64;
	private static final Set<String> DEFAULT_TIMELINE_CHANNELS = Set.of(
		"var", "projectile", "effect", "chat", "combat", "experience", "container", "sound",
		"interface", "action");

	private final Client client;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final McpEventRecorder recorder;
	private final McpMarkerOverlay markerOverlay;
	private final Map<String, ToolDef> tools = new LinkedHashMap<>();

	McpTools(Client client, ClientThread clientThread, DrawManager drawManager, McpEventRecorder recorder)
	{
		this(client, clientThread, drawManager, recorder, null);
	}

	McpTools(Client client, ClientThread clientThread, DrawManager drawManager,
		McpEventRecorder recorder, McpMarkerOverlay markerOverlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.drawManager = drawManager;
		this.recorder = recorder;
		this.markerOverlay = markerOverlay;
		registerTools();
	}

	/** Produces the final MCP result object for one tool call. */
	@FunctionalInterface
	private interface ToolHandler
	{
		JsonObject run(JsonObject args) throws Exception;
	}

	private static final class ToolDef
	{
		final String description;
		final JsonObject inputSchema;
		final ToolHandler handler;

		ToolDef(String description, JsonObject inputSchema, ToolHandler handler)
		{
			this.description = description;
			this.inputSchema = inputSchema;
			this.handler = handler;
		}
	}

	JsonArray listTools()
	{
		JsonArray arr = new JsonArray();
		tools.forEach((name, def) -> arr.add(tool(name, def.description, def.inputSchema)));
		return arr;
	}

	JsonObject callTool(String name, JsonObject args) throws Exception
	{
		ToolDef def = tools.get(name);
		if (def == null)
		{
			throw new IllegalArgumentException("Unknown tool: " + name);
		}
		JsonObject schema = def.inputSchema;
		if (schema.has("required"))
		{
			for (JsonElement required : schema.getAsJsonArray("required"))
			{
				String key = required.getAsString();
				if (!args.has(key) || args.get(key).isJsonNull())
				{
					throw new IllegalArgumentException("Missing required argument '" + key + "' for " + name);
				}
			}
		}
		return def.handler.run(args);
	}

	/** Registered tool names, for the dashboard filter. */
	JsonArray toolNames()
	{
		JsonArray names = new JsonArray();
		tools.keySet().forEach(names::add);
		return names;
	}

	private void register(String name, String description, JsonObject inputSchema, ToolHandler handler)
	{
		tools.put(name, new ToolDef(description, inputSchema, handler));
	}

	private void registerTools()
	{
		// ---- inspection ----

		register("screenshot",
			"Capture a PNG of the game canvas. No args = full canvas. Crop with componentId (single widget), interfaceId (whole interface, tight), or x/y/width/height (region).",
			schema(props(
				"componentId", "integer", "Packed component id to crop to (interfaceId << 16 | childId)",
				"childIndex", "integer", "Dynamic child index of componentId to crop to",
				"interfaceId", "integer", "Interface (group) id to crop to: union of its visible component bounds",
				"padding", "integer", "Extra pixels around the widget/interface crop (default 0)",
				"x", "integer", "Region crop x (canvas pixels)",
				"y", "integer", "Region crop y",
				"width", "integer", "Region crop width",
				"height", "integer", "Region crop height")),
			this::screenshot);

		register("get_widget",
			"Get a widget's values by packed component id: bounds, relative/original position, text, item, sprite, hidden state and children.",
			schema(props(
				"componentId", "integer", "Packed component id (interfaceId << 16 | childId)",
				"childIndex", "integer", "Optional dynamic child index",
				"depth", "integer", "How many levels of dynamic children to include (default 1, max 5)"),
				"componentId"),
			args -> textResult(getWidget(args)));

		register("dump_interface",
			"Dump every component of an interface with positions/sizes/text. Call twice and diff the JSON to spot layout changes.",
			schema(props(
				"interfaceId", "integer", "Interface (group) id to dump",
				"includeHidden", "boolean", "Include hidden components (default false)"),
				"interfaceId"),
			args -> textResult(dumpInterface(args)));

		register("list_interfaces",
			"List interface (group) ids currently loaded and visible, so you know what can be inspected.",
			schema(props()),
			args -> textResult(listInterfaces()));

		register("get_widget_at",
			"Find the deepest visible widget at a canvas pixel: its packed component id, bounds, text etc plus the parent chain. Use to identify what is at a spot on a screenshot.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y"),
				"x", "y"),
			args -> textResult(getWidgetAt(args)));

		register("set_widget",
			"Live-edit a widget client-side and revalidate: move, resize, hide, retext, recolor etc. Great for prototyping interface layout changes before touching server code. Server scripts may overwrite the change on the next interface update.",
			schema(props(
				"componentId", "integer", "Packed component id of the widget to modify",
				"childIndex", "integer", "Optional dynamic child index",
				"hidden", "boolean", "Show/hide the widget",
				"text", "string", "New text",
				"x", "integer", "New original X",
				"y", "integer", "New original Y",
				"width", "integer", "New original width",
				"height", "integer", "New original height",
				"spriteId", "integer", "New sprite id",
				"opacity", "integer", "New opacity 0-255 (0 = solid)",
				"textColor", "integer", "New text color as 0xRRGGBB int",
				"itemId", "integer", "New item id",
				"itemQuantity", "integer", "New item quantity"),
				"componentId"),
			args -> textResult(setWidget(args)));

		register("get_client_state",
			"Get game state, canvas size, resized/fixed mode, mouse position, player world position/region, run energy, weight, camera yaw/pitch/zoom and whether a right-click menu is open.",
			schema(props()),
			args -> textResult(getClientState()));

		register("get_status",
			"Server and session status for diagnostics: plugin version, port, uptime, tool count, game state and (when logged in) tick + player position. Use to confirm requests reach the plugin before trusting failures.",
			schema(props()),
			args -> textResult(getStatus()));

		register("get_combat_state",
			"One cheap call for whether the local player is fighting, moving or idle: hp/prayer points, ticks since a hitsplat landed on you and since one you dealt landed, inCombat (either within 8 ticks, Idle Notifier's rule), moving/animating, walk destination, the NPC you are attacking plus every NPC/player attacking you, and active prayers.",
			schema(props()),
			args -> textResult(getCombatState()));

		register("set_prayer",
			"Turn a prayer on or off in ONE call, without opening the prayer tab: e.g. prayer PROTECT_FROM_MAGIC, on true. Names are RuneLite Prayer enum names (PROTECT_FROM_MISSILES, PROTECT_FROM_MELEE, EAGLE_EYE, RIGOUR...). No-op when it is already in that state. Returns the active prayers afterwards.",
			schema(props(
				"prayer", "string", "Prayer enum name, e.g. PROTECT_FROM_MAGIC",
				"on", "boolean", "true to activate (default), false to deactivate"),
				"prayer"),
			args -> textResult(setPrayer(args)));

		register("observe",
			"Everything a decision loop or test reads each step, in ONE client-thread pass: tick, position, hp/prayer, run, combat state, inventory with each item's options, nearest NPCs/interactive objects/ground items, dialogue. Use instead of calling get_client_state, get_skills, get_inventory, list_* and get_dialogue separately.",
			schema(props(
				"maxDistance", "integer", "Only things within this many tiles (default 15)",
				"maxNpcs", "integer", "Nearest NPCs to include (default 10)",
				"maxObjects", "integer", "Nearest interactive objects to include (default 10)",
				"maxItems", "integer", "Nearest ground items to include (default 6)",
				"sections", "array", "Extra sections to include: equipment, skills, players, projectiles (none by default)")),
			args -> textResult(observe(args)));

		register("list_actions",
			"Every action available right now as a numbered menu with plain-language labels (\"Attack Goblin (lvl 2, 3 tiles)\", \"eat Shark\", dialogue options), each with the tool + args that perform it. Built for small decision models that pick from a list. Run one with act(id). While a dialogue options menu is open, only its options are listed.",
			schema(props(
				"maxDistance", "integer", "Only things within this many tiles (default 15)",
				"maxActions", "integer", "Cap on menu length (default 40)",
				"dedupe", "boolean", "Keep only the nearest of identical actions, e.g. one 'Attack Goblin' (default true)",
				"includeObservation", "boolean", "Also return the observe snapshot the menu was built from")),
			args -> textResult(listActions(args)));

		register("act",
			"Run action `id` from the last list_actions menu. Fails if that menu is older than maxAgeTicks, and re-validates the target right before acting (NPC identity, object still on its tile, ground item still there, dialogue still open) so a stale choice never runs. Optional args are merged into the stored call - needed for enter_input (text).",
			schema(props(
				"id", "integer", "Action id from list_actions",
				"maxAgeTicks", "integer", "Refuse menus older than this many game ticks (default 3)",
				"args", "object", "Optional overrides merged into the stored tool args (e.g. text for enter_input)"),
				"id"),
			args -> textResult(act(args)));

		register("get_skills",
			"Local player skills: level, boosted level and xp per skill, plus total level. Hitpoints boosted level = current HP.",
			schema(props()),
			args -> textResult(getSkills()));

		// ---- raw input ----

		register("click",
			"Click at canvas coordinates by dispatching real mouse events.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y",
				"button", "integer", "Mouse button: 1=left (default), 2=middle, 3=right"),
				"x", "y"),
			this::click);

		register("click_component",
			"Click the center of a widget resolved by packed component id. Fails if the widget is missing or hidden.",
			schema(props(
				"componentId", "integer", "Packed component id to click",
				"childIndex", "integer", "Optional dynamic child index",
				"button", "integer", "Mouse button: 1=left (default), 2=middle, 3=right"),
				"componentId"),
			this::clickComponent);

		register("hover",
			"Move the mouse to canvas coordinates or a widget's center without clicking.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y",
				"componentId", "integer", "Packed component id to hover (used instead of x/y)",
				"childIndex", "integer", "Optional dynamic child index")),
			this::hover);

		register("drag",
			"Press at the source, drag through intermediate points, release at the target. Source/target are either packed component ids or canvas coordinates.",
			schema(props(
				"fromComponentId", "integer", "Packed component id to drag from",
				"fromChildIndex", "integer", "Optional dynamic child index for the source",
				"fromX", "integer", "Source canvas x (used if fromComponentId absent)",
				"fromY", "integer", "Source canvas y",
				"toComponentId", "integer", "Packed component id to drag to",
				"toChildIndex", "integer", "Optional dynamic child index for the target",
				"toX", "integer", "Target canvas x (used if toComponentId absent)",
				"toY", "integer", "Target canvas y",
				"steps", "integer", "Number of intermediate drag moves (default 8)",
				"stepDelayMs", "integer", "Delay between drag moves in ms (default 20)")),
			this::drag);

		register("type_chat",
			"Type text into the game chatbox by dispatching key events, then press Enter to send. Works for :: commands.",
			schema(props(
				"text", "string", "Text to type into the chatbox, e.g. a chat message or a :: command",
				"send", "boolean", "Press Enter after typing (default true)"),
				"text"),
			this::typeChat);

		register("press_key",
			"Press a single key on the game canvas (e.g. ESCAPE to close an interface).",
			schema(props(
				"key", "string", "Single character, or ENTER, ESCAPE, SPACE, TAB, BACKSPACE, UP, DOWN, LEFT, RIGHT, F1-F12"),
				"key"),
			this::pressKey);

		// ---- world ----

		register("walk_to",
			"Click the tile at world coordinates to walk there. Clicks the viewport when the tile is on screen, otherwise the minimap. Tile must be in the loaded scene.",
			schema(props(
				"x", "integer", "World (map) x coordinate",
				"y", "integer", "World (map) y coordinate",
				"plane", "integer", "Plane 0-3 (default: current plane)"),
				"x", "y"),
			this::walkTo);

		register("list_npcs",
			"List NPCs with id, index, name, combat level, actions, world location, distance, animation, facing, health (when visible) and canvas position, sorted by distance.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on NPC name",
				"maxDistance", "integer", "Only NPCs within this many tiles of the player",
				"maxResults", "integer", "Cap on returned NPCs (default 50)")),
			args -> textResult(listNpcs(args)));

		register("list_players",
			"List players with name, combat level, world location, distance, animation, facing, health (when visible) and canvas position, sorted by distance. Local player is flagged isLocal.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on player name",
				"maxDistance", "integer", "Only players within this many tiles of the local player",
				"maxResults", "integer", "Cap on returned players (default 50)")),
			args -> textResult(listPlayers(args)));

		register("list_objects",
			"List scene objects (game, wall, decorative, ground) with id, name, actions, world position, face direction (orientation in jau 0-2047, degrees and compass) and size, sorted by distance.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on object name",
				"objectId", "integer", "Only objects with this id",
				"maxDistance", "integer", "Only objects within this many tiles of the player",
				"maxResults", "integer", "Cap on returned objects (default 50)")),
			args -> textResult(listObjects(args)));

		register("list_ground_items",
			"List items on the floor: itemId, name, quantity, world location and distance, sorted by distance. Use pickup_item to take one.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on item name",
				"maxDistance", "integer", "Only items within this many tiles of the player",
				"maxResults", "integer", "Cap on returned items (default 50)")),
			args -> textResult(listGroundItems(args)));

		register("pickup_item",
			"Invoke Take on a ground item at world coordinates. Player walks to the tile and picks it up.",
			schema(props(
				"itemId", "integer", "Item id from list_ground_items",
				"x", "integer", "World x of the tile the item is on",
				"y", "integer", "World y of the tile",
				"plane", "integer", "Plane (default current)"),
				"itemId", "x", "y"),
			args -> textResult(pickupItem(args)));

		register("list_projectiles",
			"List projectiles currently in flight: id, remaining cycles, start scene position and target point/actor.",
			schema(props()),
			args -> textResult(listProjectiles()));

		register("get_inventory",
			"Read an item container by id: every slot with itemId, name and quantity. 93=inventory, 94=worn, 95=bank; server-made containers work too.",
			schema(props(
				"inventoryId", "integer", "Item container id, e.g. 93 inventory, 94 worn equipment, 95 bank",
				"includeEmpty", "boolean", "Include empty slots as itemId -1 (default false)"),
				"inventoryId"),
			args -> textResult(getInventory(args)));

		register("list_inventories",
			"List all item containers currently loaded in the client: container id, slot count and non-empty item count. Use get_inventory for contents.",
			schema(props()),
			args -> textResult(listInventories()));

		// ---- interaction ----

		register("interact_npc",
			"Invoke a cache-defined right-click option on an NPC (default Talk-to). Only sees options from the NPC definition; for server-added or runtime menu options use get_npc_menu + click_menu_option instead.",
			schema(props(
				"npcIndex", "integer", "NPC index from list_npcs (exact match)",
				"npcName", "string", "Case-insensitive NPC name substring; nearest match wins (used if npcIndex absent)",
				"option", "string", "Right-click menu option to invoke, e.g. Talk-to, Trade, Attack (default Talk-to)")),
			args -> textResult(interactNpc(args)));

		register("get_npc_menu",
			"Hover an NPC and read its live right-click mini-menu: every entry including server-added quick options, top entry first (index 0 = default left-click). Follow with click_menu_option.",
			schema(props(
				"npcIndex", "integer", "NPC index from list_npcs (exact match)",
				"npcName", "string", "Case-insensitive NPC name substring; nearest match wins")),
			args -> textResult(getNpcMenu(args)));

		register("click_menu_option",
			"Click an entry of the currently built mini-menu (hover something first, e.g. via get_npc_menu or hover). Invokes the entry exactly as a right-click menu click would.",
			schema(props(
				"option", "string", "Case-insensitive substring of the menu option text, e.g. Vote, Trade, Quick-start",
				"target", "string", "Optional case-insensitive substring of the entry target to disambiguate"),
				"option"),
			args -> textResult(clickMenuOption(args)));

		register("item_action",
			"Right-click an inventory item and click an option in ONE call - no screenshot needed. Finds the item slot, hovers it, invokes the matching menu entry (e.g. Drop). Inventory tab must be visible.",
			schema(props(
				"itemId", "integer", "Item id in the inventory",
				"itemName", "string", "Case-insensitive item name substring (used if itemId absent)",
				"option", "string", "Right-click option to invoke, e.g. Drop, Eat, Wield, Use, Bury"),
				"option"),
			args -> textResult(itemAction(args)));

		register("interact_object",
			"Interact with a scene object in ONE call - no screenshot needed. Finds the nearest matching object, hovers it and clicks the option. e.g. nameFilter 'tree' + option 'Chop down'. list_objects shows available actions.",
			schema(props(
				"objectId", "integer", "Object id to interact with",
				"nameFilter", "string", "Case-insensitive object name substring; nearest match wins (used if objectId absent)",
				"option", "string", "Menu option to click, e.g. Chop down, Mine, Bank, Open. Default: the object's first action")),
			args -> textResult(interactObject(args)));

		register("object_action",
			"Invoke a scene object's option directly at its world coordinates - the object need not be on screen and the mouse never moves. Same menu entry the client sends (scene x/y + object id). Use interact_object instead when you want a real hover+click.",
			schema(props(
				"objectId", "integer", "Object id from list_objects or observe",
				"x", "integer", "World x of the object's tile",
				"y", "integer", "World y of the object's tile",
				"plane", "integer", "Plane 0-3 (default: current plane)",
				"option", "string", "Menu option to invoke, e.g. Chop down, Bank, Open"),
				"objectId", "x", "y", "option"),
			args -> textResult(invokeObjectOp(args)));

		register("interact_player",
			"Interact with another player in ONE call - no screenshot needed. Hovers the player and clicks the matching right-click option, e.g. Trade with.",
			schema(props(
				"playerName", "string", "Case-insensitive player name substring; nearest match wins",
				"option", "string", "Menu option to click, e.g. Trade with, Follow, Challenge, Attack"),
				"playerName", "option"),
			args -> textResult(interactPlayer(args)));

		register("widget_action",
			"Right-click any widget and click a menu option in ONE call: hovers the widget, then invokes the matching mini-menu entry. Works for spells, prayers, equipment, buttons.",
			schema(props(
				"componentId", "integer", "Packed component id of the widget",
				"childIndex", "integer", "Optional dynamic child index",
				"option", "string", "Case-insensitive substring of the menu option to invoke"),
				"componentId", "option"),
			args -> textResult(widgetAction(args)));

		register("invoke_menu_action",
			"Low-level escape hatch: invoke client.menuAction with raw parameters. Use only when the higher-level interact tools cannot express the action (params visible in get_npc_menu entries or script history).",
			schema(props(
				"param0", "integer", "Menu param0 (often slot or scene x)",
				"param1", "integer", "Menu param1 (often packed widget id or scene y)",
				"type", "string", "MenuAction enum name, e.g. CC_OP, WIDGET_TARGET, GAME_OBJECT_FIRST_OPTION",
				"identifier", "integer", "Menu identifier (op index, npc index, object id...)",
				"itemId", "integer", "Item id (default -1)",
				"option", "string", "Option text (default empty)",
				"target", "string", "Target text (default empty)"),
				"param0", "param1", "type", "identifier"),
			args -> textResult(invokeRawMenuAction(args)));

		// ---- dialogue ----

		register("get_dialogue",
			"Read the current dialogue state: NPC/player dialogue text and speaker, numbered options menu, message box, item dialog, or open chatbox input prompt.",
			schema(props()),
			args -> textResult(onClientThread(this::readDialogue)));

		register("continue_dialogue",
			"Press space to advance the current dialogue and return the new dialogue state as soon as it changes.",
			schema(props()),
			args ->
			{
				String before = dialogueFingerprint();
				pressNamedKey("SPACE");
				return textResult(waitDialogueChange(before));
			});

		register("select_option",
			"Choose a numbered dialogue option by pressing its number key and return the new dialogue state as soon as it changes.",
			schema(props(
				"option", "integer", "Option number as shown by get_dialogue (1-based)"),
				"option"),
			this::selectOption);

		register("enter_input",
			"Type a value into the open chatbox input (string or amount prompt) and press Enter, then return the new dialogue state.",
			schema(props(
				"text", "string", "Value to type: a string or a number depending on the prompt"),
				"text"),
			this::enterInput);

		// ---- history and vars ----

		register("get_script_history",
			"Report clientscripts fired recently: per-script fire counts plus individual events with game tick, timestamp and root-script arguments.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 5000)",
				"scriptId", "integer", "Only this clientscript id",
				"limit", "integer", "Max events returned, newest kept (default 200)")),
			args -> textResult(recorder.scriptHistory(
				optInt(args, "sinceMs", 5000),
				args.has("scriptId") ? args.get("scriptId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_var_history",
			"Report varbit, varp and varc changes in a recent window, in order, with old and new values, game tick and timestamp.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 5000)",
				"type", "string", "Filter: varbit, varp, varcint or varcstr",
				"id", "integer", "Only this varbit/varp/varc id",
				"limit", "integer", "Max events returned, newest kept (default 200)")),
			args -> textResult(recorder.varHistory(
				optInt(args, "sinceMs", 5000),
				args.has("type") ? args.get("type").getAsString() : null,
				args.has("id") ? args.get("id").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_var",
			"Read the current value of a varbit, varp, varc int or varc string.",
			schema(props(
				"type", "string", "One of: varbit, varp, varcint, varcstr",
				"id", "integer", "The varbit/varp/varc id"),
				"type", "id"),
			args -> textResult(getVar(args)));

		register("get_projectile_history",
			"Report projectiles launched recently: id, start position, slope/height values, cycles and target, one entry per projectile.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"projectileId", "integer", "Only this projectile (gfx) id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.projectileHistory(
				optInt(args, "sinceMs", 10000),
				args.has("projectileId") ? args.get("projectileId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_effect_history",
			"Report animations and graphics (spotanims) played by NPCs/players recently, with actor name, npc id, value and game tick.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"kind", "string", "Filter: animation or graphic",
				"actorName", "string", "Case-insensitive substring filter on the actor name",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.effectHistory(
				optInt(args, "sinceMs", 10000),
				args.has("kind") ? args.get("kind").getAsString() : null,
				args.has("actorName") ? args.get("actorName").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_chat_history",
			"Read chatbox messages received recently: type, sender, message, game tick and timestamp. Covers game messages, public/private chat etc (not NPC dialogue - use get_dialogue).",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"type", "string", "Filter by ChatMessageType name, e.g. GAMEMESSAGE, PUBLICCHAT, PRIVATECHAT, SPAM",
				"nameFilter", "string", "Case-insensitive substring filter on the sender name",
				"limit", "integer", "Max messages returned (default 100)")),
			args -> textResult(recorder.chatHistory(
				optInt(args, "sinceMs", 60000),
				args.has("type") ? args.get("type").getAsString() : null,
				args.has("nameFilter") ? args.get("nameFilter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_combat_history",
			"Report hitsplats applied recently: damage amount, hitsplat type, the actor hit, its remaining health ratio and game tick. Use this for max hit, accuracy and splash checks instead of watching damage numbers on screen.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"actorName", "string", "Case-insensitive substring filter on the actor name",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.combatHistory(
				optInt(args, "sinceMs", 10000),
				args.has("actorName") ? args.get("actorName").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_xp_history",
			"Report experience gains recently: skill, xp gained, new total xp, level and boosted level, with game tick. Use to check xp per action against the expected rate.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"skill", "string", "Only this skill, e.g. ATTACK, WOODCUTTING",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.experienceHistory(
				optInt(args, "sinceMs", 60000),
				args.has("skill") ? args.get("skill").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_container_history",
			"Report item container changes recently as per-item deltas (was, now, delta), so you can see exactly what was gained, consumed or dropped and on which tick. 93 inventory, 94 worn, 95 bank.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"containerId", "integer", "Only this container id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.containerHistory(
				optInt(args, "sinceMs", 60000),
				args.has("containerId") ? args.get("containerId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_sound_history",
			"Report sound effects played recently: sound id, source actor, and for area sounds the scene position, with game tick.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"soundId", "integer", "Only this sound id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.soundHistory(
				optInt(args, "sinceMs", 10000),
				args.has("soundId") ? args.get("soundId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_timeline",
			"Every recorded channel merged into one tick-ordered stream: vars, projectiles, effects, chat, combat, experience, containers and sounds. Scripts fire many times a second, so they are left out unless requested via channels. Read this to see what happened around an action, instead of correlating the per-channel history tools by hand.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"channels", "array", "Only these channels: script, var, projectile, effect, chat, combat, experience, container, sound (default all but script)",
				"fromTick", "integer", "Only events on or after this game tick",
				"toTick", "integer", "Only events on or before this game tick",
				"limit", "integer", "Max events returned, newest kept (default 300)")),
			args -> textResult(recorder.timeline(
				optInt(args, "sinceMs", 10000),
				Objects.requireNonNullElse(optStringSet(args, "channels"), DEFAULT_TIMELINE_CHANNELS),
				args.has("fromTick") ? args.get("fromTick").getAsInt() : null,
				args.has("toTick") ? args.get("toTick").getAsInt() : null,
				optInt(args, "limit", 300))));

		// ---- waiting ----

		// ---- evidence / regression ----

		register("snapshot_state",
			"Store a labelled state snapshot (vitals, combat, inventory, equipment, skills, nearby entities). Pair with diff_state to answer 'what did that action change' without eyeballing JSON.",
			schema(props(
				"label", "string", "Name to store it under (default 'last')")),
			args -> textResult(snapshotState(args)));

		register("diff_state",
			"Diff the live world against a stored snapshot_state label and return ONLY the changed fields: moved tiles, hp/prayer deltas, item and entity differences. The regression-test primitive.",
			schema(props(
				"from", "string", "snapshot_state label to compare against (default 'last')",
				"maxDistance", "integer", "Entity scan radius for both snapshots (default 15)")),
			args -> textResult(diffState(args)));

		register("run_batch",
			"Run a list of tool calls in order in one request: steps:[{tool,arguments}]. Stops at the first failure unless stopOnError:false. Each step reports ok + result/error text.",
			schema(props(
				"steps", "array", "Ordered calls: [{\"tool\":\"walk_to\",\"arguments\":{\"x\":3220,\"y\":3219}}]",
				"stopOnError", "boolean", "Abort the batch on first failed step (default true)"),
				"steps"),
			args -> textResult(runBatch(args)));

		// ---- combat ----

		register("get_special_attack",
			"Special attack state: energy percent and whether the spec bar is enabled.",
			schema(props()), args -> textResult(getSpecialAttack()));

		register("toggle_special_attack",
			"Toggle the special attack bar by clicking the spec orb. Returns the enabled state after the click.",
			schema(props()), args -> textResult(toggleSpecialAttack()));

		register("get_attack_style",
			"Current weapon attack style index plus the style names the combat tab offers for the wielded weapon.",
			schema(props()), args -> textResult(getAttackStyle()));

		register("set_attack_style",
			"Click a combat-tab attack style by name (fuzzy, e.g. 'aggressive') or index 0-3.",
			schema(props(
				"style", "string", "Style name substring or index ('slash', 'aggressive', '2')"),
				"style"),
			args -> textResult(setAttackStyle(args)));

		// ---- magic ----

		register("list_spells",
			"Spells visible in the standard spellbook: name + widget component id.",
			schema(props()), args -> textResult(listSpells()));

		register("cast_spell",
			"Click a spellbook spell by name (fuzzy). For on-target combat spells, cast then click the target.",
			schema(props(
				"spell", "string", "Spell name substring, e.g. 'fire surge'"),
				"spell"),
			args -> textResult(castSpell(args)));

		// ---- world / movement ----

		register("is_walkable",
			"Collision-map check on a world tile: raw flag bits, whether the tile is walkable, and which directions are blocked. Answers 'did the door actually block'.",
			schema(props(
				"x", "integer", "World x", "y", "integer", "World y",
				"plane", "integer", "Plane (default current)"),
				"x", "y"),
			args -> textResult(isWalkable(args)));

		register("get_destination",
			"The world tile the local player is currently walking to, or null when idle.",
			schema(props()), args -> textResult(getDestination()));

		// ---- interfaces ----

		register("search_interfaces",
			"Find loaded widgets whose text contains a string (quest journals, drop logs, banks, shops). Returns component ids, groups, text.",
			schema(props(
				"text", "string", "Case-insensitive substring to search for",
				"maxResults", "integer", "Cap (default 50)"),
				"text"),
			args -> textResult(searchInterfaces(args)));

		register("bank_action",
			"Invoke a bank op directly on a banked item (Withdraw-1/5/10/All...) or a deposited item via side:'inventory' (Deposit-1/All...) — bank must be open.",
			schema(props(
				"option", "string", "Op name, e.g. 'Withdraw-1', 'Withdraw-All', 'Deposit-5'",
				"itemId", "integer", "Item id",
				"itemName", "string", "Name substring (alternative)",
				"side", "string", "bank (default, withdraw side) or inventory (deposit side)"),
				"option"),
			args -> textResult(bankAction(args)));

		// ---- new history channels ----

		register("get_spawn_history",
			"NPC lifecycle events: spawn, despawn and transform (id change at a stable index — boss phase changes). Filter by kind/name.",
			schema(props(
				"sinceMs", "integer", "Window in ms (default 60000)",
				"kind", "string", "spawn, despawn or transform",
				"nameFilter", "string", "NPC name substring",
				"limit", "integer", "Max events (default 100)")),
			args -> textResult(recorder.spawnHistory(
				optInt(args, "sinceMs", 60_000),
				args.has("kind") ? args.get("kind").getAsString() : null,
				args.has("nameFilter") ? args.get("nameFilter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_loot_history",
			"Ground-item lifecycle: spawned (drops!) and despawned events with item, quantity, tile, tick — deterministic drop-table evidence.",
			schema(props(
				"sinceMs", "integer", "Window in ms (default 60000)",
				"kind", "string", "spawned or despawned",
				"nameFilter", "string", "Item name substring",
				"limit", "integer", "Max events (default 100)")),
			args -> textResult(recorder.lootHistory(
				optInt(args, "sinceMs", 60_000),
				args.has("kind") ? args.get("kind").getAsString() : null,
				args.has("nameFilter") ? args.get("nameFilter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_overhead_history",
			"Overhead text events ('More power!') — how many mechanics announce phase changes. Filter matches actor or text.",
			schema(props(
				"sinceMs", "integer", "Window in ms (default 60000)",
				"nameFilter", "string", "Matches actor name or text",
				"limit", "integer", "Max events (default 100)")),
			args -> textResult(recorder.overheadHistory(
				optInt(args, "sinceMs", 60_000),
				args.has("nameFilter") ? args.get("nameFilter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_interface_history",
			"Interface (widget group) load/close events in a recent window — proves the server opened or closed a screen, with the game tick it happened on.",
			schema(props(
				"sinceMs", "integer", "Window in ms (default 60000)",
				"groupId", "integer", "Only this interface (group) id",
				"kind", "string", "opened, closed or unloaded",
				"limit", "integer", "Max events (default 100)")),
			args -> textResult(recorder.interfaceHistory(
				optInt(args, "sinceMs", 60_000),
				args.has("groupId") ? args.get("groupId").getAsInt() : null,
				args.has("kind") ? args.get("kind").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_action_history",
			"Audit trail of menu options the client actually invoked (walks, clicks, tool calls): option, target, MenuAction type and raw param0/param1/identifier. This is what the server received - use it to verify a tool sent the packet you expected.",
			schema(props(
				"sinceMs", "integer", "Window in ms (default 60000)",
				"filter", "string", "Matches option, target or MenuAction type",
				"limit", "integer", "Max events (default 100)")),
			args -> textResult(recorder.actionHistory(
				optInt(args, "sinceMs", 60_000),
				args.has("filter") ? args.get("filter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_menu",
			"The menu entries currently built (whatever was hovered last): option, target, MenuAction type and raw params. Inspect before click_menu_option, or to see the real packet fields for invoke_menu_action.",
			schema(props()),
			args -> textResult(onClientThread(() ->
			{
				JsonObject result = new JsonObject();
				result.add("entries", menuEntriesJson());
				return result;
			})));

		// ---- economy interfaces ----

		register("list_shop",
			"Items on the open shop's stock widget: slot, itemId, name, stock quantity and the ops each slot offers (Value, Buy 1/5/10/50...). Errors when no shop is open.",
			schema(props()),
			args -> textResult(listShop()));

		register("shop_action",
			"Invoke a shop op directly on a stock item (Buy 1/5/10/50, Value) or on an inventory item shown for selling via side:'inventory' (Sell 1/5/10/50) - shop must be open.",
			schema(props(
				"option", "string", "Op name, e.g. 'Buy 1', 'Sell 5', 'Value'",
				"itemId", "integer", "Item id",
				"itemName", "string", "Name substring (alternative to itemId)",
				"side", "string", "shop (default, buy side) or inventory (sell side)"),
				"option"),
			args -> textResult(itemSlotAction(args,
				args.has("side") && "inventory".equalsIgnoreCase(args.get("side").getAsString())
					? InterfaceID.Shopside.ITEMS : InterfaceID.Shopmain.ITEMS,
				"Shop is not open",
				args.has("side") && "inventory".equalsIgnoreCase(args.get("side").getAsString())
					? "inventory" : "shop")));

		register("get_trade",
			"Trade screen state: stage (offer/confirm/none), your offered items from the trade container, and every item widget rendered on the trade screen grouped by parent (the partner's offer shows on their side).",
			schema(props()),
			args -> textResult(getTrade()));

		register("trade_action",
			"Invoke a trade op: action 'accept'/'decline' clicks the screen's button (works on the confirm screen too), or offer/remove ops on items - itemId|itemName + option ('Offer-1','Offer-10','Remove-All') + side 'inventory' (offer, default) or 'offer' (remove).",
			schema(props(
				"action", "string", "accept or decline (alternative to option+item)",
				"option", "string", "Item op, e.g. 'Offer-1', 'Offer-All', 'Remove-5'",
				"itemId", "integer", "Item id",
				"itemName", "string", "Name substring (alternative to itemId)",
				"side", "string", "inventory (default, offer side) or offer (remove side)")),
			args -> textResult(tradeAction(args)));

		// ---- session ----

		register("list_worlds",
			"The world list the client loaded: id, player count, activity, location, types, and which is current. Needed before hop_world.",
			schema(props()),
			args -> textResult(listWorlds()));

		register("hop_world",
			"Hop to a world id from list_worlds. The client logs out and reconnects - follow with wait_for world or login state.",
			schema(props(
				"world", "integer", "World id to hop to"),
				"world"),
			args -> textResult(hopWorld(args)));

		register("logout",
			"Invoke the Logout button on the logout tab interface. The logout interface must be loaded - if not loaded this errors; open the logout tab in the client first (or use press_key with the client's logout hotkey if configured).",
			schema(props()),
			args -> textResult(logout()));

		register("list_quests",
			"Every quest known to the client with its state: NOT_STARTED, IN_PROGRESS or FINISHED. Filter by name substring or state.",
			schema(props(
				"nameFilter", "string", "Quest name substring",
				"state", "string", "NOT_STARTED, IN_PROGRESS or FINISHED")),
			args -> textResult(listQuests(args)));

		// ---- definition inspectors ----

		register("item_def",
			"Item definition from the cache: name, note/placeholder links, stackable, members, tradeable, store price and inventory options - the ground truth for what an item id is. Optional 'param' resolves one item param key.",
			schema(props(
				"id", "integer", "Item id",
				"param", "integer", "Optional item param key to resolve (int/str/long value)"),
				"id"),
			args -> textResult(itemDef(args)));

		register("npc_def",
			"NPC definition from the cache: name, combat level, menu actions, size and transform ids (varbit-driven forms).",
			schema(props(
				"id", "integer", "NPC id"),
				"id"),
			args -> textResult(npcDef(args)));

		register("object_def",
			"Object definition from the cache: name, menu actions, size and impostor/transform ids (objects that swap form by varbit, like doors and farming patches).",
			schema(props(
				"id", "integer", "Object id"),
				"id"),
			args -> textResult(objectDef(args)));

		// ---- movement / collision ----

		register("find_path",
			"BFS pathfind over the loaded collision map to a world tile: returns whether it's reachable plus the exact tile path. Answers 'can the player actually get there' without walking.",
			schema(props(
				"x", "integer", "Target world x",
				"y", "integer", "Target world y",
				"plane", "integer", "Plane (default current)",
				"fromX", "integer", "Start world x (default player)",
				"fromY", "integer", "Start world y (default player)",
				"maxPath", "integer", "Return at most this many path tiles (default 200)"),
				"x", "y"),
			args -> textResult(findPath(args)));

		register("get_collision_area",
			"Raw collision flags for a rectangle of the loaded scene - the grid walls/doors/blockers live in. Row-major [y][x] in world coordinates, with per-tile walkable/decode.",
			schema(props(
				"x", "integer", "Rectangle origin world x (south-west)",
				"y", "integer", "Rectangle origin world y",
				"width", "integer", "Tiles wide (default 8, max 32)",
				"height", "integer", "Tiles tall (default 8, max 32)",
				"plane", "integer", "Plane (default current)"),
				"x", "y"),
			args -> textResult(getCollisionArea(args)));

		register("set_camera",
			"Point the camera: yaw and pitch targets for deterministic screenshots of the same scene.",
			schema(props(
				"yaw", "integer", "Camera yaw target 0-2047 (0 = south, 512 = west, 1024 = north, 1536 = east)",
				"pitch", "integer", "Camera pitch target 128-383 (lower = steeper overhead view)")),
			args -> textResult(setCamera(args)));

		// ---- item-on-target ----

		register("use_item",
			"Use an inventory item on something: 'Use' is invoked on the item, then the target's 'Use item -> target' menu entry is clicked (the same two packets a player sends). Targets: npc (npcIndex|npcName), object (objectId+x+y), player (playerName), or another inventory item (targetItemId|targetItemName). For quests, crafting, keys-on-doors.",
			schema(props(
				"itemId", "integer", "Item id in the inventory",
				"itemName", "string", "Item name substring (alternative)",
				"npcIndex", "integer", "Use on NPC index (from list_npcs)",
				"npcName", "string", "Use on nearest matching NPC name",
				"objectId", "integer", "Use on object id",
				"x", "integer", "Object world x",
				"y", "integer", "Object world y",
				"playerName", "string", "Use on player name substring",
				"targetItemId", "integer", "Use on this inventory item id (item-on-item)",
				"targetItemName", "string", "Use on this inventory item name")),
			args -> useItem(args));

		register("compare_screenshot",
			"Screenshot diffing for rendering regressions: first call with a label stores the region as the baseline; later calls diff against it and report changedPixels, percent and a bounding box. Same crop args as screenshot (componentId/childIndex, interfaceId, or x/y/width/height - defaults to full canvas). thresholdPct makes 'match' a pass/fail gate.",
			schema(props(
				"label", "string", "Baseline name (default 'default')",
				"componentId", "integer", "Crop to widget (see screenshot)",
				"childIndex", "integer", "Dynamic child of componentId",
				"interfaceId", "integer", "Crop to interface",
				"x", "integer", "Region x", "y", "integer", "Region y",
				"width", "integer", "Region width", "height", "integer", "Region height",
				"padding", "integer", "Padding around widget/interface crop",
				"thresholdPct", "number", "match=true when changedPct <= this (default 0)",
				"rebaseline", "boolean", "Overwrite the stored baseline instead of comparing")),
			args -> compareScreenshot(args));

		register("widget_op",
			"Invoke a named widget action directly (no mouse): resolves the widget, finds option in its actions list (or opIndex position), and fires CC_OP. Use for buttons/list rows/item ops anywhere a widget exposes actions. Returns the action invoked.",
			schema(props(
				"componentId", "integer", "Packed component id of the widget (or its container)",
				"childIndex", "integer", "Dynamic-child index inside the container (e.g. list row / item slot)",
				"option", "string", "Case-insensitive substring of the action to fire, e.g. 'Wear'",
				"opIndex", "integer", "Fire the Nth action (0-based) instead of matching by name")),
			args -> widgetOp(args));

		register("get_actor",
			"Deep inspect one actor by npcIndex, npcName or playerName: position, animations, facing, health, overhead text, target, spot anims, canvas position, and NPC id/composition.",
			schema(props(
				"npcIndex", "integer", "NPC index from list_npcs",
				"npcName", "string", "NPC name substring (first match wins)",
				"playerName", "string", "Player name substring (exact name preferred)")),
			args -> getActor(args));

		register("get_tile",
			"Inspect a single world tile: all objects (game/wall/decorative/ground) with actions, ground items, collision flags and walkability. Cheaper and more precise than list_* for a known tile.",
			schema(props(
				"x", "integer", "World x",
				"y", "integer", "World y",
				"plane", "integer", "Plane (default: current)")),
			args -> getTile(args));

		register("set_var",
			"Write a client-side var (varp/varbit/varcint/varcstr) for UI/state testing. CLIENT-SIDE ONLY: the server will overwrite it on the next sync; get_var.serverValue exposes what the server last sent. Returns old->new.",
			schema(props(
				"type", "string", "varbit, varp, varcint or varcstr",
				"id", "integer", "Var id",
				"value", "string", "New value (integer for all but varcstr)"),
				"type", "id", "value"),
			args -> setVar(args));

		register("toggle_run",
			"Toggle run mode by clicking the run orb. Returns energy and run state after the click.",
			schema(props()),
			args -> toggleRun());

		register("mark_tile",
			"Draw a persistent highlighted tile in the game scene so a human watching the client can see what an agent is pointing at. Optional label, colour and tick expiry.",
			schema(props(
				"name", "string", "Marker id (default 'mark'); reuse replaces it",
				"x", "integer", "World x",
				"y", "integer", "World y",
				"plane", "integer", "Plane (default: current)",
				"label", "string", "Text drawn above the tile",
				"color", "string", "Hex colour, e.g. '#ff0000' or '0xff0000' (default yellow)",
				"ticks", "integer", "Auto-remove after N game ticks (default 120; 0 = never)"),
				"x", "y"),
			args -> markTile(args));

		register("mark_widget",
			"Draw a persistent rectangle around a widget so a human can see which component an agent is referencing.",
			schema(props(
				"name", "string", "Marker id (default 'mark'); reuse replaces it",
				"componentId", "integer", "Packed component id",
				"childIndex", "integer", "Dynamic-child index to mark instead of the whole widget",
				"label", "string", "Text drawn above the rectangle",
				"color", "string", "Hex colour (default yellow)",
				"ticks", "integer", "Auto-remove after N game ticks (default 120; 0 = never)"),
				"componentId"),
			args -> markWidget(args));

		register("clear_marks",
			"Remove overlay markers: one by name, or all when name is omitted.",
			schema(props("name", "string", "Marker id to remove")),
			args -> clearMarks(args));

		register("get_hint_arrow",
			"Current hint arrow (server-set navigation marker): type + target tile/NPC/player. Servers send these for quests and mechanics.",
			schema(props()),
			args -> getHintArrow());

		register("set_hint_arrow",
			"Set a client-side hint arrow on a tile, NPC or player — navigation aid for humans watching the client (server can overwrite it).",
			schema(props(
				"x", "integer", "World x for a tile arrow",
				"y", "integer", "World y for a tile arrow",
				"npcIndex", "integer", "Arrow follows this NPC",
				"playerName", "string", "Arrow follows this player")),
			args -> setHintArrow(args));

		register("clear_hint_arrow",
			"Clear the current hint arrow.",
			schema(props()),
			args -> clearHintArrow());

		register("examine",
			"Send an Examine packet: npcIndex/npcName, objectId+x+y, ground item (itemId+x+y), or inventory itemId — the server responds with chat text; read it via wait_for chat_message or get_chat_history.",
			schema(props(
				"npcIndex", "integer", "NPC to examine",
				"npcName", "string", "NPC name substring to examine",
				"objectId", "integer", "Scene object id to examine (needs x/y)",
				"itemId", "integer", "Item id: inventory item when x/y omitted, ground item at x,y when present",
				"x", "integer", "World x for object/ground-item examine",
				"y", "integer", "World y for object/ground-item examine")),
			args -> examine(args));

		register("get_clan",
			"Clan channel info: own + guest channel name, member list (name, rank, world).",
			schema(props()),
			args -> getClan());

		register("get_fc",
			"Friends chat: owner, channel name, your rank, kick rank, member list (name, world, rank).",
			schema(props()),
			args -> getFriendsChat());

		register("list_friends",
			"Friends list: name + world for each friend.",
			schema(props()),
			args -> listFriends());

		register("list_ignore",
			"Ignore list: names (and previous names) of ignored players.",
			schema(props()),
			args -> listIgnore());

		register("list_vars",
			"Bulk var dump: varps (with serverValue) or varcs in an id range. For 'which vars did the server just send' — pair with get_var_history for per-change evidence.",
			schema(props(
				"type", "string", "varp (default), varcint or varcstr",
				"start", "integer", "First id to include (default 0)",
				"end", "integer", "Last id to include (default: whole varp array / populated varcs)",
				"maxResults", "integer", "Cap rows returned (default 500)")),
			args -> listVars(args));

		register("enum_def",
			"EnumComposition cache dump: enum id -> key/value rows (ints or strings). Useful for varbit-driven lookups (prayer lists, shop stock tables, skill data).",
			schema(props(
				"id", "integer", "Enum id",
				"maxResults", "integer", "Cap rows (default 500)"),
				"id"),
			args -> enumDef(args));

		register("struct_def",
			"StructComposition cache dump: struct id -> param keys, optionally resolving values for given keys.",
			schema(props(
				"id", "integer", "Struct id",
				"keys", "string", "Comma-separated param keys to resolve (default: first 20 found)"),
				"id"),
			args -> structDef(args));

		register("anim_def",
			"Animation definition: frame count, duration, per-frame lengths, forced hand items — verify an animation id does what the server intended.",
			schema(props("id", "integer", "Animation id"), "id"),
			args -> animDef(args));

		register("list_gfx",
			"Active unattached graphics objects (ground-targeted spotanims — AoE telegraphs without an actor): id, world tile, plane, finished.",
			schema(props()),
			args -> listGfx());

		register("list_ge_offers",
			"Grand Exchange offer slots: state, itemId, quantity, sold, price, spent. Empty on servers without GE.",
			schema(props()),
			args -> listGeOffers());

		register("get_instance_map",
			"Instanced region layout: isInInstancedRegion + the instance chunk template grid (source region coords + rotation per chunk) — for dungeon/instanced-area servers.",
			schema(props()),
			args -> getInstanceMap());

		register("db_table",
			"DBTable cache access: 'table' lists row ids, 'row' dumps a row's fields, 'table'+'column'+'value' queries rows by value. Server-driven config/data tables.",
			schema(props(
				"table", "integer", "DBTable id - list its rows (or query with column+value)",
				"row", "integer", "Row id - dump its fields",
				"column", "integer", "Column index for value queries",
				"field", "integer", "Field index within a column (default 0)",
				"value", "string", "Query rows whose column field equals this (int or string)",
				"maxResults", "integer", "Cap rows (default 200)")),
			args -> dbTable(args));

		register("item_sprite",
			"Render an item's inventory icon as a PNG image (content image block) - the same icon the client draws.",
			schema(props(
				"itemId", "integer", "Item id",
				"quantity", "integer", "Stack quantity shown (default 1)",
				"noted", "boolean", "Render the noted form"),
				"itemId"),
			args -> itemSprite(args));

		register("get_music",
			"Active MIDI requests: archive ids + jingle flag - which music tracks/jingles the server is currently playing.",
			schema(props()),
			args -> getMusic());

		register("get_object_history",
			"Scene object lifecycle events: spawned/despawned for game/wall/decorative/ground objects - boss arena objects, doors toggling, phase structures. Filter e.g. field=kind contains=spawned, or field=name contains=door.",
			historySchema(), args -> channelHistoryTool("object", args));

		register("get_gfx_history",
			"Unattached graphics object spawns (ground spotanims - AoE telegraphs). Filter e.g. field=gfxId.",
			historySchema(), args -> channelHistoryTool("gfx", args));

		register("get_interact_history",
			"Actor target changes: which actor targeted/cleared which target - tank swaps, retargets. Fields: source, target, sourceType, targetType.",
			historySchema(), args -> channelHistoryTool("interact", args));

		register("get_social_history",
			"Clan channel + friends chat events: joins, leaves, member changes. Fields: kind, name, detail.",
			historySchema(), args -> channelHistoryTool("social", args));

		register("get_world_history",
			"World/session events: world_changed, mapload (instanced region loads). Fields: kind, worldId.",
			historySchema(), args -> channelHistoryTool("world", args));

		register("wait_for",
			"Block until a game condition is met, checked every 100ms - returns the moment it happens. Use this after walk_to/interact/attack instead of taking screenshots or guessing delays. Event-based conditions only see NEW events since the call started. Returns met:false with current state on timeout.",
			schema(props(
				"condition", "string", "What to wait for: idle, at_tile, npc_dead, dialogue, interface_open / interface_closed, chat_message, hitsplat, projectile, animation, spotanim, var, in_combat / out_of_combat, prayer, ticks, spawn / despawn / transform, loot, overhead, xp, container, action, world, stat, menu, game_state, object_spawn / object_despawn, interacting, gfx, hintarrow",
				"timeoutMs", "integer", "Give up after this long (default 10000, max 60000)",
				"x", "integer", "at_tile: world x",
				"y", "integer", "at_tile: world y",
				"range", "integer", "at_tile: max tiles away to count as arrived (default 1)",
				"npcIndex", "integer", "npc_dead/animation/spotanim: NPC index from list_npcs",
				"npcName", "string", "npc_dead/animation/spotanim: NPC name substring",
				"interfaceId", "integer", "interface_open/closed: interface group id",
				"textContains", "string", "chat_message: case-insensitive substring to watch for",
				"actorName", "string", "hitsplat/animation/spotanim: actor name substring (default any)",
				"animationId", "integer", "animation: exact animation id to wait for",
				"projectileId", "integer", "projectile: exact projectile (gfx) id to wait for",
				"type", "string", "var: varbit, varp, varcint or varcstr",
				"id", "integer", "var: varbit/varp/varc id",
				"value", "integer", "var: value to wait for (exact match; omit to wait for ANY change from baseline)",
				"prayer", "string", "prayer: Prayer enum name, e.g. PROTECT_FROM_MAGIC",
				"on", "boolean", "prayer: wait for active (default true) or inactive",
				"skill", "string", "xp/stat: skill name, e.g. ATTACK (stat waits for any boosted/real level change)",
				"amount", "integer", "xp: xp gained to wait for (default 1)",
				"containerId", "integer", "container: item container id (default 93 inventory)",
				"itemId", "integer", "container: item id whose count change to wait for",
				"filter", "string", "action: matches option/target/MenuAction type",
				"state", "string", "game_state: GameState name substring (default LOGGED_IN)",
				"objectId", "integer", "object_spawn/despawn: object id to match",
				"objectName", "string", "object_spawn/despawn: object name substring",
				"gfxId", "integer", "gfx: graphics object id to match",
				"source", "string", "interacting: source actor name substring",
				"target", "string", "interacting: target actor name substring"),
				"condition"),
			this::waitFor);
	}

	/** Schema for the generic-channel history tools: sinceMs/limit + field/contains filter. */
	private static JsonObject historySchema()
	{
		return schema(props(
			"sinceMs", "integer", "Window in ms (default 60000)",
			"field", "string", "Entry field to filter on (default: return all)",
			"contains", "string", "Case-insensitive substring the field must contain",
			"limit", "integer", "Max events (default 100)"));
	}

	private JsonObject channelHistoryTool(String channel, JsonObject args)
	{
		// Filtering requires both halves; a lone "contains" is ignored.
		String field = args.has("field") && args.has("contains")
			? args.get("field").getAsString() : null;
		return textResult(recorder.channelHistory(channel,
			optInt(args, "sinceMs", 60_000),
			field,
			field != null ? args.get("contains").getAsString() : null,
			optInt(args, "limit", 100)));
	}

	/** Build a JSON Schema properties object from (name, type, description) triples. */
	private static JsonObject props(String... triples)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i < triples.length; i += 3)
		{
			o.add(triples[i], prop(triples[i + 1], triples[i + 2]));
		}
		return o;
	}

	// --- tools ---

	private JsonObject screenshot(JsonObject args) throws Exception
	{
		Rectangle crop = resolveCrop(args);
		BufferedImage frame = captureFrame();
		if (crop != null)
		{
			Rectangle clamped = crop.intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
			if (clamped.isEmpty())
			{
				throw new IllegalArgumentException("Crop region " + crop + " is outside the canvas ("
					+ frame.getWidth() + "x" + frame.getHeight() + ")");
			}
			frame = frame.getSubimage(clamped.x, clamped.y, clamped.width, clamped.height);
			crop = clamped;
		}

		JsonObject image = new JsonObject();
		image.addProperty("type", "image");
		image.addProperty("data", pngBase64(frame));
		image.addProperty("mimeType", "image/png");

		JsonArray content = new JsonArray();
		content.add(image);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private JsonElement getWidget(JsonObject args) throws Exception
	{
		int depth = Math.min(optInt(args, "depth", 1), 5);
		JsonElement result = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null ? null : describeWidget(w, depth, true);
		});
		if (result == null)
		{
			throw new IllegalArgumentException("Widget not found: " + describeId(args, "componentId", "childIndex"));
		}
		return result;
	}

	private JsonElement dumpInterface(JsonObject args) throws Exception
	{
		int interfaceId = args.get("interfaceId").getAsInt();
		boolean includeHidden = args.has("includeHidden") && args.get("includeHidden").getAsBoolean();
		return onClientThread(() ->
		{
			JsonArray components = new JsonArray();
			int found = 0;
			int gap = 0;
			for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE && gap < COMPONENT_SCAN_GAP; child++)
			{
				Widget w = client.getWidget(WidgetUtil.packComponentId(interfaceId, child));
				if (w == null)
				{
					gap++;
					continue;
				}
				gap = 0;
				found++;
				if (includeHidden || !w.isHidden())
				{
					components.add(describeWidget(w, 1, includeHidden));
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("interfaceId", interfaceId);
			result.addProperty("componentCount", found);
			result.add("components", components);
			return result;
		});
	}

	private JsonElement listInterfaces() throws Exception
	{
		return onClientThread(() ->
		{
			Set<Integer> visible = new HashSet<>();
			Set<Integer> loaded = new HashSet<>();
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				Deque<Widget> queue = new ArrayDeque<>();
				for (Widget root : roots)
				{
					if (root != null)
					{
						queue.add(root);
					}
				}
				while (!queue.isEmpty())
				{
					Widget w = queue.poll();
					int group = WidgetUtil.componentToInterface(w.getId());
					loaded.add(group);
					if (!w.isHidden())
					{
						visible.add(group);
					}
					enqueueChildren(queue, w.getStaticChildren());
					enqueueChildren(queue, w.getNestedChildren());
				}
			}
			JsonObject result = new JsonObject();
			result.add("visibleInterfaceIds", toArray(visible));
			result.add("loadedInterfaceIds", toArray(loaded));
			return result;
		});
	}

	private JsonElement getClientState() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("gameState", client.getGameState().name());
			result.addProperty("canvasWidth", client.getCanvasWidth());
			result.addProperty("canvasHeight", client.getCanvasHeight());
			result.addProperty("resized", client.isResized());
			net.runelite.api.Point mouse = client.getMouseCanvasPosition();
			if (mouse != null)
			{
				result.addProperty("mouseX", mouse.getX());
				result.addProperty("mouseY", mouse.getY());
			}
			Player local = client.getLocalPlayer();
			if (local != null && local.getWorldLocation() != null)
			{
				WorldPoint wp = local.getWorldLocation();
				result.addProperty("worldX", wp.getX());
				result.addProperty("worldY", wp.getY());
				result.addProperty("plane", wp.getPlane());
				result.addProperty("regionId", wp.getRegionID());
			}
			result.addProperty("runEnergy", client.getEnergy());
			result.addProperty("weight", client.getWeight());
			result.addProperty("menuOpen", client.isMenuOpen());
			result.addProperty("cameraYaw", client.getCameraYaw());
			result.addProperty("cameraPitch", client.getCameraPitch());
			result.addProperty("cameraZoom", client.get3dZoom());
			return result;
		});
	}

	/**
	 * Server/session status a caller can check before blaming a tool failure on the
	 * game: plugin version, port, uptime, tool count, game state, tick and position.
	 */
	private JsonElement getStatus() throws Exception
	{
		JsonObject result = new JsonObject();
		String version = McpTools.class.getPackage().getImplementationVersion();
		result.addProperty("server", "openrune-devtools");
		result.addProperty("version", version != null ? version : "dev");
		result.addProperty("toolCount", tools.size());
		JsonObject session = onClientThread(() ->
		{
			JsonObject o = new JsonObject();
			o.addProperty("gameState", client.getGameState().name());
			o.addProperty("tick", client.getTickCount());
			o.addProperty("fps", client.getFPS());
			o.addProperty("markers", markerOverlay != null ? markerOverlay.count() : 0);
			Player local = client.getLocalPlayer();
			o.addProperty("loggedIn", local != null);
			if (local != null && local.getWorldLocation() != null)
			{
				WorldPoint wp = local.getWorldLocation();
				o.addProperty("worldX", wp.getX());
				o.addProperty("worldY", wp.getY());
				o.addProperty("plane", wp.getPlane());
				o.addProperty("regionId", wp.getRegionID());
			}
			return o;
		});
		result.add("client", session);
		return result;
	}

	/** Ticks a monster may take between attacks before combat counts as over (Idle Notifier). */
	private static final int COMBAT_WINDOW_TICKS = 8;

	private JsonElement getCombatState() throws Exception
	{
		return onClientThread(this::combatStateJson);
	}

	/** The get_combat_state payload. Must be called on the client thread. */
	private JsonObject combatStateJson()
	{
		JsonObject o = new JsonObject();
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			o.addProperty("loggedIn", false);
			return o;
		}
		int tick = client.getTickCount();
		int onMe = recorder.lastHitOnMeTick();
		int mine = recorder.lastMyHitTick();
		int sinceOnMe = onMe < 0 ? -1 : tick - onMe;
		int sinceMine = mine < 0 ? -1 : tick - mine;
		o.addProperty("loggedIn", true);
		o.addProperty("tick", tick);
		o.addProperty("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		o.addProperty("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
		o.addProperty("prayerPoints", client.getBoostedSkillLevel(Skill.PRAYER));
		o.addProperty("prayerPointsMax", client.getRealSkillLevel(Skill.PRAYER));
		o.addProperty("ticksSinceHitOnMe", sinceOnMe);
		o.addProperty("ticksSinceMyHit", sinceMine);
		o.addProperty("inCombat", (sinceOnMe >= 0 && sinceOnMe <= COMBAT_WINDOW_TICKS)
			|| (sinceMine >= 0 && sinceMine <= COMBAT_WINDOW_TICKS));
		o.addProperty("animating", local.getAnimation() != -1);
		o.addProperty("moving", local.getPoseAnimation() != local.getIdlePoseAnimation());
		LocalPoint dest = client.getLocalDestinationLocation();
		if (dest != null)
		{
			WorldPoint d = WorldPoint.fromLocal(client, dest);
			o.addProperty("destination", d.getX() + "," + d.getY() + "," + d.getPlane());
		}
		Actor target = local.getInteracting();
		if (target != null)
		{
			o.addProperty("target", target.getName());
			if (target instanceof NPC)
			{
				NPC npc = (NPC) target;
				o.addProperty("targetIndex", npc.getIndex());
				o.addProperty("targetNpcId", npc.getId());
				o.addProperty("targetDead", npc.isDead());
				if (npc.getHealthScale() > 0)
				{
					o.addProperty("targetHealthPercent", npc.getHealthRatio() * 100 / npc.getHealthScale());
				}
			}
		}
		WorldPoint me = local.getWorldLocation();
		JsonArray attackers = new JsonArray();
		for (NPC npc : client.getNpcs())
		{
			if (npc.getInteracting() == local && !npc.isDead())
			{
				attackers.add(describeAttacker(npc, me));
			}
		}
		for (Player player : client.getPlayers())
		{
			if (player != null && player != local && player.getInteracting() == local && !player.isDead())
			{
				attackers.add(describeAttacker(player, me));
			}
		}
		o.add("attackers", attackers);
		o.add("prayers", activePrayers());
		return o;
	}

	private static JsonObject describeAttacker(Actor actor, WorldPoint me)
	{
		JsonObject a = new JsonObject();
		a.addProperty("type", actor instanceof NPC ? "npc" : "player");
		a.addProperty("name", actor.getName());
		if (actor instanceof NPC)
		{
			a.addProperty("index", ((NPC) actor).getIndex());
			a.addProperty("npcId", ((NPC) actor).getId());
		}
		a.addProperty("combatLevel", actor.getCombatLevel());
		WorldPoint wp = actor.getWorldLocation();
		if (me != null && wp != null)
		{
			a.addProperty("distance", me.distanceTo(wp));
		}
		if (actor.getHealthScale() > 0 && actor.getHealthRatio() > -1)
		{
			a.addProperty("healthPercent", actor.getHealthRatio() * 100 / actor.getHealthScale());
		}
		return a;
	}

	/** Active prayers as enum names. Must be called on the client thread. */
	private JsonArray activePrayers()
	{
		JsonArray active = new JsonArray();
		for (Prayer prayer : Prayer.values())
		{
			if (client.isPrayerActive(prayer))
			{
				active.add(prayer.name());
			}
		}
		return active;
	}

	/**
	 * Widget ids of the prayer-book interfaces to search for a named prayer: the
	 * standard book's numbered rows, then every loaded widget in the alternate
	 * (Ruinous) prayer book, whose child ids differ across revisions.
	 */
	private List<Widget> prayerBookWidgets()
	{
		List<Widget> widgets = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			Widget w = client.getWidget(InterfaceID.Prayerbook.PRAYER1 + i);
			if (w != null)
			{
				widgets.add(w);
			}
		}
		// The Ruinous/alternate book renders prayers under its god subtrees; collect
		// all loaded descendants rather than hardcoding component ids.
		Widget horror = client.getWidget(InterfaceID.HorrorPrayerbooks.CONTENTS);
		if (horror == null)
		{
			horror = client.getWidget(InterfaceID.HorrorPrayerbooks.UNIVERSE);
		}
		if (horror != null)
		{
			Deque<Widget> queue = new ArrayDeque<>();
			queue.add(horror);
			while (!queue.isEmpty())
			{
				Widget w = queue.poll();
				widgets.add(w);
				enqueueChildren(queue, w.getStaticChildren());
				enqueueChildren(queue, w.getDynamicChildren());
				enqueueChildren(queue, w.getNestedChildren());
			}
		}
		return widgets;
	}

	private JsonElement setPrayer(JsonObject args) throws Exception
	{
		String prayerArg = args.get("prayer").getAsString().toUpperCase();
		Prayer prayer;
		try
		{
			prayer = Prayer.valueOf(prayerArg);
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Unknown prayer '" + args.get("prayer").getAsString()
				+ "'; use a RuneLite Prayer enum name, e.g. PROTECT_FROM_MAGIC");
		}
		boolean on = !args.has("on") || args.get("on").getAsBoolean();
		// "PROTECT_FROM_MAGIC" -> "protect from magic"; Ruinous powers are stored as
		// RP_*, while their widgets show the plain name ("Dampen Magic").
		String wanted = prayer.name().replace('_', ' ').toLowerCase();
		if (wanted.startsWith("rp "))
		{
			wanted = wanted.substring(3);
		}
		final String wantedName = wanted;
		return onClientThread(() ->
		{
			if (client.isPrayerActive(prayer) != on)
			{
				Widget button = null;
				for (Widget w : prayerBookWidgets())
				{
					String name = w != null && w.getName() != null
						? Text.removeTags(w.getName()).toLowerCase() : "";
					if (name.equals(wantedName))
					{
						button = w;
						break;
					}
				}
				if (button == null)
				{
					throw new IllegalArgumentException("No prayer button named '" + wantedName
						+ "' in the prayer book");
				}
				// The op index is 1 on the standard book; read it off the button's own
				// actions so alternate books with a different layout still work.
				int opIndex = 1;
				String[] actions = button.getActions();
				for (int i = 0; actions != null && i < actions.length; i++)
				{
					if (actions[i] != null
						&& (Text.removeTags(actions[i]).equalsIgnoreCase("Activate")
							|| Text.removeTags(actions[i]).equalsIgnoreCase("Deactivate")))
					{
						opIndex = i + 1;
						break;
					}
				}
				client.menuAction(-1, button.getId(),
					opIndex <= 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY,
					opIndex, -1, on ? "Activate" : "Deactivate", button.getName());
			}
			JsonObject result = new JsonObject();
			result.addProperty("prayer", prayer.name());
			result.addProperty("requested", on);
			result.add("prayers", activePrayers());
			return result;
		});
	}

	private JsonElement setWidget(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			if (w == null)
			{
				throw new IllegalArgumentException("Widget not found: " + describeId(args, "componentId", "childIndex"));
			}
			if (args.has("hidden"))
			{
				w.setHidden(args.get("hidden").getAsBoolean());
			}
			if (args.has("text"))
			{
				w.setText(args.get("text").getAsString());
			}
			if (args.has("x"))
			{
				w.setOriginalX(args.get("x").getAsInt());
			}
			if (args.has("y"))
			{
				w.setOriginalY(args.get("y").getAsInt());
			}
			if (args.has("width"))
			{
				w.setOriginalWidth(args.get("width").getAsInt());
			}
			if (args.has("height"))
			{
				w.setOriginalHeight(args.get("height").getAsInt());
			}
			if (args.has("spriteId"))
			{
				w.setSpriteId(args.get("spriteId").getAsInt());
			}
			if (args.has("opacity"))
			{
				w.setOpacity(args.get("opacity").getAsInt());
			}
			if (args.has("textColor"))
			{
				w.setTextColor(args.get("textColor").getAsInt());
			}
			if (args.has("itemId"))
			{
				w.setItemId(args.get("itemId").getAsInt());
			}
			if (args.has("itemQuantity"))
			{
				w.setItemQuantity(args.get("itemQuantity").getAsInt());
			}
			w.revalidate();
			return describeWidget(w, 0, true);
		});
	}

	private JsonElement getWidgetAt(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		return onClientThread(() ->
		{
			Widget best = null;
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				for (Widget root : roots)
				{
					best = deepestWidgetAt(root, x, y, best);
				}
			}
			if (best == null)
			{
				throw new IllegalArgumentException("No visible widget at " + x + "," + y);
			}
			JsonObject o = describeWidget(best, 0, false);
			JsonArray parentChain = new JsonArray();
			for (Widget parent = best.getParent(); parent != null; parent = parent.getParent())
			{
				parentChain.add(parent.getId());
			}
			o.add("parentChain", parentChain);
			return o;
		});
	}

	/**
	 * Depth-first search for the deepest visible widget containing the point;
	 * later siblings win like the renderer. Must be called on the client thread.
	 */
	private Widget deepestWidgetAt(Widget w, int x, int y, Widget best)
	{
		if (w == null || w.isHidden())
		{
			return best;
		}
		Rectangle b = w.getBounds();
		if (b != null && b.contains(x, y))
		{
			best = w;
		}
		Widget[][] childGroups = {w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()};
		for (Widget[] children : childGroups)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				best = deepestWidgetAt(child, x, y, best);
			}
		}
		return best;
	}

	private JsonElement invokeRawMenuAction(JsonObject args) throws Exception
	{
		MenuAction type = MenuAction.valueOf(args.get("type").getAsString());
		int param0 = args.get("param0").getAsInt();
		int param1 = args.get("param1").getAsInt();
		int identifier = args.get("identifier").getAsInt();
		int itemId = optInt(args, "itemId", -1);
		String option = args.has("option") ? args.get("option").getAsString() : "";
		String target = args.has("target") ? args.get("target").getAsString() : "";
		return onClientThread(() ->
		{
			client.menuAction(param0, param1, type, identifier, itemId, option, target);
			JsonObject result = new JsonObject();
			result.addProperty("invoked", type.name());
			result.addProperty("identifier", identifier);
			return result;
		});
	}

	private JsonObject click(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		int button = optInt(args, "button", MouseEvent.BUTTON1);
		clickAt(x, y, button);
		return textResult("Clicked button " + button + " at " + x + "," + y);
	}

	private JsonObject clickComponent(JsonObject args) throws Exception
	{
		int button = optInt(args, "button", MouseEvent.BUTTON1);
		Rectangle bounds = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null || w.isHidden() ? null : w.getBounds();
		});
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
				+ describeId(args, "componentId", "childIndex"));
		}
		int x = bounds.x + bounds.width / 2;
		int y = bounds.y + bounds.height / 2;
		clickAt(x, y, button);
		return textResult("Clicked " + describeId(args, "componentId", "childIndex")
			+ " at " + x + "," + y + " (bounds " + bounds.x + "," + bounds.y + " "
			+ bounds.width + "x" + bounds.height + ")");
	}

	private JsonObject hover(JsonObject args) throws Exception
	{
		Point p = resolvePoint(args, "componentId", "childIndex", "x", "y");
		dispatchMouse(MouseEvent.MOUSE_MOVED, p.x, p.y, MouseEvent.NOBUTTON, 0);
		return textResult("Hovering at " + p.x + "," + p.y);
	}

	private JsonObject drag(JsonObject args) throws Exception
	{
		Point from = resolvePoint(args, "fromComponentId", "fromChildIndex", "fromX", "fromY");
		Point to = resolvePoint(args, "toComponentId", "toChildIndex", "toX", "toY");
		int steps = Math.max(1, optInt(args, "steps", 8));
		long stepDelay = optInt(args, "stepDelayMs", 20);

		dispatchMouse(MouseEvent.MOUSE_MOVED, from.x, from.y, MouseEvent.NOBUTTON, 0);
		pauseMs(30);
		dispatchMouse(MouseEvent.MOUSE_PRESSED, from.x, from.y, MouseEvent.BUTTON1, 1);
		pauseMs(100);
		for (int i = 1; i <= steps; i++)
		{
			int x = from.x + (to.x - from.x) * i / steps;
			int y = from.y + (to.y - from.y) * i / steps;
			dispatchMouse(MouseEvent.MOUSE_DRAGGED, x, y, MouseEvent.BUTTON1, 0);
			pauseMs(stepDelay);
		}
		dispatchMouse(MouseEvent.MOUSE_RELEASED, to.x, to.y, MouseEvent.BUTTON1, 1);
		return textResult("Dragged from " + from.x + "," + from.y + " to " + to.x + "," + to.y);
	}

	private JsonObject typeChat(JsonObject args) throws Exception
	{
		String text = args.get("text").getAsString();
		boolean send = !args.has("send") || args.get("send").getAsBoolean();
		for (char c : text.toCharArray())
		{
			typeChar(c);
		}
		if (send)
		{
			pressNamedKey("ENTER");
		}
		return textResult((send ? "Typed and sent: " : "Typed: ") + text);
	}

	private JsonObject pressKey(JsonObject args) throws Exception
	{
		String key = args.get("key").getAsString();
		if (key.length() == 1)
		{
			typeChar(key.charAt(0));
		}
		else
		{
			pressNamedKey(key.toUpperCase());
		}
		return textResult("Pressed " + key);
	}

	private JsonObject walkTo(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		JsonObject target = onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				return null;
			}
			net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
			JsonObject t = new JsonObject();
			if (p != null && p.getX() >= 0 && p.getY() >= 0
				&& p.getX() < client.getCanvasWidth() && p.getY() < client.getCanvasHeight())
			{
				t.addProperty("x", p.getX());
				t.addProperty("y", p.getY());
				t.addProperty("via", "viewport");
				return t;
			}
			net.runelite.api.Point mp = Perspective.localToMinimap(client, lp);
			if (mp != null)
			{
				t.addProperty("x", mp.getX());
				t.addProperty("y", mp.getY());
				t.addProperty("via", "minimap");
				return t;
			}
			return null;
		});
		if (target == null)
		{
			throw new IllegalArgumentException("Tile " + x + "," + y
				+ " is not in the loaded scene or not visible on the viewport/minimap");
		}
		int cx = target.get("x").getAsInt();
		int cy = target.get("y").getAsInt();
		String via = target.get("via").getAsString();
		clickAt(cx, cy, MouseEvent.BUTTON1);
		return textResult("Clicked " + via + " at " + cx + "," + cy + " to walk to " + x + "," + y);
	}

	private JsonElement listNpcs(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			List<JsonObject> list = new ArrayList<>();
			for (NPC npc : client.getNpcs())
			{
				if (npc == null || !matchesName(npc.getName(), filter))
				{
					continue;
				}
				JsonObject o = describeActor(npc, me, maxDistance);
				if (o == null)
				{
					continue;
				}
				o.addProperty("npcId", npc.getId());
				o.addProperty("index", npc.getIndex());
				NPCComposition comp = npc.getTransformedComposition() != null
					? npc.getTransformedComposition()
					: npc.getComposition();
				if (comp != null && comp.getActions() != null)
				{
					JsonArray actions = new JsonArray();
					for (String action : comp.getActions())
					{
						if (action != null)
						{
							actions.add(action);
						}
					}
					if (actions.size() > 0)
					{
						o.add("actions", actions);
					}
				}
				list.add(o);
			}
			return actorListResult("npcs", list, maxResults);
		});
	}

	private JsonElement listPlayers(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Player local = client.getLocalPlayer();
			WorldPoint me = local != null ? local.getWorldLocation() : null;
			List<JsonObject> list = new ArrayList<>();
			for (Player player : client.getPlayers())
			{
				if (player == null || !matchesName(player.getName(), filter))
				{
					continue;
				}
				JsonObject o = describeActor(player, me, maxDistance);
				if (o == null)
				{
					continue;
				}
				if (player == local)
				{
					o.addProperty("isLocal", true);
				}
				list.add(o);
			}
			return actorListResult("players", list, maxResults);
		});
	}

	private static final MenuAction[] NPC_MENU_ACTIONS = {
		MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION,
	};

	private JsonElement interactNpc(JsonObject args) throws Exception
	{
		String option = args.has("option") ? args.get("option").getAsString() : "Talk-to";
		return onClientThread(() ->
		{
			NPC npc = findNpc(args);
			if (npc == null)
			{
				throw new IllegalArgumentException("NPC not found; use list_npcs to see what is nearby");
			}
			NPCComposition comp = npc.getTransformedComposition() != null
				? npc.getTransformedComposition()
				: npc.getComposition();
			String[] actions = comp != null ? comp.getActions() : null;
			int actionIndex = -1;
			if (actions != null)
			{
				for (int i = 0; i < actions.length && i < NPC_MENU_ACTIONS.length; i++)
				{
					if (actions[i] != null && actions[i].equalsIgnoreCase(option))
					{
						actionIndex = i;
						break;
					}
				}
			}
			if (actionIndex == -1)
			{
				throw new IllegalArgumentException("NPC " + npc.getName() + " has no option '" + option
					+ "'. Available: " + availableActions(actions));
			}
			client.menuAction(0, 0, NPC_MENU_ACTIONS[actionIndex], npc.getIndex(), -1,
				actions[actionIndex], npc.getName());
			JsonObject result = new JsonObject();
			result.addProperty("invoked", actions[actionIndex]);
			result.addProperty("npc", npc.getName());
			result.addProperty("npcIndex", npc.getIndex());
			result.addProperty("note", "Player may need to walk to the NPC; poll get_dialogue for the conversation");
			return result;
		});
	}

	private JsonElement getNpcMenu(JsonObject args) throws Exception
	{
		Point hover = onClientThread(() ->
		{
			NPC npc = findNpc(args);
			if (npc == null)
			{
				throw new IllegalArgumentException("NPC not found; use list_npcs to see what is nearby");
			}
			Point p = actorHoverPoint(npc);
			if (p == null)
			{
				throw new IllegalArgumentException("NPC " + npc.getName() + " is not visible on screen");
			}
			return p;
		});
		dispatchMouse(MouseEvent.MOUSE_MOVED, hover.x, hover.y, MouseEvent.NOBUTTON, 0);
		// Let the client rebuild the mini-menu for the hovered target
		pauseMs(150);
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("hoverX", hover.x);
			result.addProperty("hoverY", hover.y);
			result.add("entries", menuEntriesJson());
			return result;
		});
	}

	private JsonElement clickMenuOption(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		String target = args.has("target") ? args.get("target").getAsString().toLowerCase() : null;
		return onClientThread(() -> invokeMenuEntry(option, target));
	}

	private JsonElement itemAction(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		Integer itemId = args.has("itemId") ? args.get("itemId").getAsInt() : null;
		String itemName = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase() : null;
		if (itemId == null && itemName == null)
		{
			throw new IllegalArgumentException("Provide itemId or itemName");
		}
		Object found = onClientThread(() ->
		{
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null)
			{
				throw new IllegalStateException("Inventory widget is not loaded");
			}
			Widget[] slots = inventory.getDynamicChildren();
			if (slots != null)
			{
				for (Widget slot : slots)
				{
					if (slot == null || slot.getItemId() <= 0)
					{
						continue;
					}
					if (itemId != null && slot.getItemId() != itemId)
					{
						continue;
					}
					if (itemName != null)
					{
						ItemComposition composition = client.getItemDefinition(slot.getItemId());
						String name = composition != null ? composition.getName() : null;
						if (name == null || !name.toLowerCase().contains(itemName))
						{
							continue;
						}
					}
					// Invoke the slot's own op directly (CC_OP, identifier = op index + 1, as the client
					// builds it), so this works with the inventory tab closed and never moves the mouse.
					String[] ops = slot.getActions();
					for (int i = 0; ops != null && i < ops.length; i++)
					{
						if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase(option))
						{
							client.menuAction(slot.getIndex(), InterfaceID.Inventory.ITEMS,
								i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY, i + 1,
								slot.getItemId(), ops[i], slot.getName());
							JsonObject result = new JsonObject();
							result.addProperty("invoked", ops[i]);
							result.addProperty("slot", slot.getIndex());
							result.addProperty("itemId", slot.getItemId());
							return result;
						}
					}
					if (inventory.isHidden())
					{
						throw new IllegalStateException("Item has no '" + option
							+ "' op and the inventory tab is closed");
					}
					Rectangle b = slot.getBounds();
					if (b != null && b.width > 0 && b.height > 0)
					{
						return new Point(b.x + b.width / 2, b.y + b.height / 2);
					}
				}
			}
			throw new IllegalArgumentException("Item not found in inventory: "
				+ (itemId != null ? "id " + itemId : itemName));
		});
		if (found instanceof JsonObject)
		{
			return (JsonObject) found;
		}
		// Ops the slot doesn't list (e.g. server-added): fall back to hovering and reading the menu.
		return hoverAndInvoke((Point) found, option, null);
	}

	private JsonElement widgetAction(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		Rectangle bounds = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null || w.isHidden() ? null : w.getBounds();
		});
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
				+ describeId(args, "componentId", "childIndex"));
		}
		return hoverAndInvoke(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2),
			option, null);
	}

	private JsonElement interactObject(JsonObject args) throws Exception
	{
		Integer objectId = args.has("objectId") ? args.get("objectId").getAsInt() : null;
		String nameFilter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		if (objectId == null && nameFilter == null)
		{
			throw new IllegalArgumentException("Provide objectId or nameFilter");
		}
		JsonObject found = onClientThread(() -> findObjectHover(objectId, nameFilter));
		String option = args.has("option")
			? args.get("option").getAsString().toLowerCase()
			: found.get("defaultOption").getAsString().toLowerCase();
		JsonElement clicked = hoverAndInvoke(
			new Point(found.get("hoverX").getAsInt(), found.get("hoverY").getAsInt()), option, null);
		JsonObject result = clicked.getAsJsonObject();
		result.addProperty("object", found.get("name").getAsString());
		result.addProperty("objectId", found.get("objectId").getAsInt());
		return result;
	}

	/**
	 * Nearest visible scene object matching the filters, with a hover point and its
	 * default action. Must be called on the client thread.
	 */
	private JsonObject findObjectHover(Integer objectId, String nameFilter)
	{
		Scene scene = client.getScene();
		if (scene == null)
		{
			throw new IllegalStateException("No scene is loaded");
		}
		WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		Tile[][] tiles = scene.getTiles()[client.getPlane()];
		Set<GameObject> seen = new HashSet<>();
		TileObject best = null;
		String bestName = null;
		String bestDefault = null;
		int bestDistance = Integer.MAX_VALUE;
		Point bestHover = null;
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				List<TileObject> candidates = new ArrayList<>();
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject go : gameObjects)
					{
						if (go != null && seen.add(go))
						{
							candidates.add(go);
						}
					}
				}
				if (tile.getWallObject() != null)
				{
					candidates.add(tile.getWallObject());
				}
				if (tile.getGroundObject() != null)
				{
					candidates.add(tile.getGroundObject());
				}
				if (tile.getDecorativeObject() != null)
				{
					candidates.add(tile.getDecorativeObject());
				}
				for (TileObject obj : candidates)
				{
					if (objectId != null && obj.getId() != objectId)
					{
						continue;
					}
					ObjectComposition composition = client.getObjectDefinition(obj.getId());
					String name = composition != null ? composition.getName() : null;
					if (nameFilter != null && (name == null || !name.toLowerCase().contains(nameFilter)))
					{
						continue;
					}
					int distance = me != null && obj.getWorldLocation() != null
						? me.distanceTo(obj.getWorldLocation())
						: 0;
					if (distance >= bestDistance)
					{
						continue;
					}
					Point hover = objectHoverPoint(obj);
					if (hover == null)
					{
						continue;
					}
					String defaultAction = null;
					if (composition != null && composition.getActions() != null)
					{
						for (String action : composition.getActions())
						{
							if (action != null)
							{
								defaultAction = action;
								break;
							}
						}
					}
					best = obj;
					bestName = name;
					bestDefault = defaultAction;
					bestDistance = distance;
					bestHover = hover;
				}
			}
		}
		if (best == null)
		{
			throw new IllegalArgumentException("No visible object matching "
				+ (objectId != null ? "id " + objectId : "'" + nameFilter + "'")
				+ "; use list_objects to see what is nearby");
		}
		if (bestDefault == null)
		{
			bestDefault = "";
		}
		JsonObject o = new JsonObject();
		o.addProperty("objectId", best.getId());
		o.addProperty("name", bestName != null ? bestName : "");
		o.addProperty("defaultOption", bestDefault);
		o.addProperty("hoverX", bestHover.x);
		o.addProperty("hoverY", bestHover.y);
		return o;
	}

	private JsonElement interactPlayer(JsonObject args) throws Exception
	{
		String playerName = args.get("playerName").getAsString().toLowerCase();
		String option = args.get("option").getAsString().toLowerCase();
		Point hover = onClientThread(() ->
		{
			Player local = client.getLocalPlayer();
			WorldPoint me = local != null ? local.getWorldLocation() : null;
			Player best = null;
			int bestDistance = Integer.MAX_VALUE;
			for (Player player : client.getPlayers())
			{
				if (player == null || player == local || player.getName() == null
					|| !player.getName().toLowerCase().contains(playerName))
				{
					continue;
				}
				int distance = me != null && player.getWorldLocation() != null
					? me.distanceTo(player.getWorldLocation())
					: 0;
				if (distance < bestDistance)
				{
					best = player;
					bestDistance = distance;
				}
			}
			if (best == null)
			{
				throw new IllegalArgumentException("Player not found: " + playerName
					+ "; use list_players to see who is nearby");
			}
			Point p = actorHoverPoint(best);
			if (p == null)
			{
				throw new IllegalArgumentException("Player " + best.getName() + " is not visible on screen");
			}
			return p;
		});
		return hoverAndInvoke(hover, option, null);
	}

	/**
	 * Move the mouse to a point, wait for the client to rebuild the mini-menu,
	 * then invoke the entry matching the option.
	 */
	private JsonElement hoverAndInvoke(Point hover, String optionLower, String targetLower) throws Exception
	{
		dispatchMouse(MouseEvent.MOUSE_MOVED, hover.x, hover.y, MouseEvent.NOBUTTON, 0);
		pauseMs(150);
		return onClientThread(() -> invokeMenuEntry(optionLower, targetLower));
	}

	/**
	 * Find and invoke a mini-menu entry, top-down like the rendered menu.
	 * Must be called on the client thread.
	 */
	private JsonObject invokeMenuEntry(String optionLower, String targetLower)
	{
		MenuEntry[] entries = client.getMenuEntries();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry == null || entry.getOption() == null
				|| !entry.getOption().toLowerCase().contains(optionLower))
			{
				continue;
			}
			if (targetLower != null && (entry.getTarget() == null
				|| !entry.getTarget().toLowerCase().contains(targetLower)))
			{
				continue;
			}
			client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(),
				entry.getIdentifier(), entry.getItemId(), entry.getOption(), entry.getTarget());
			JsonObject result = new JsonObject();
			result.addProperty("clicked", entry.getOption());
			result.addProperty("target", entry.getTarget());
			return result;
		}
		throw new IllegalArgumentException("No menu entry matching '" + optionLower
			+ "'. Current menu: " + McpHttpServer.GSON.toJson(menuEntriesJson()));
	}

	/**
	 * Current mini-menu entries, top entry first (index 0 = default left-click action).
	 * Must be called on the client thread.
	 */
	private JsonArray menuEntriesJson()
	{
		MenuEntry[] entries = client.getMenuEntries();
		JsonArray arr = new JsonArray();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("index", entries.length - 1 - i);
			o.addProperty("option", entry.getOption());
			o.addProperty("target", entry.getTarget());
			o.addProperty("type", entry.getType().name());
			o.addProperty("identifier", entry.getIdentifier());
			o.addProperty("param0", entry.getParam0());
			o.addProperty("param1", entry.getParam1());
			if (entry.getItemId() > 0)
			{
				o.addProperty("itemId", entry.getItemId());
			}
			arr.add(o);
		}
		return arr;
	}

	/**
	 * A canvas point over the actor's model for hovering. Must be called on the client thread.
	 */
	private Point actorHoverPoint(Actor actor)
	{
		Shape hull = actor.getConvexHull();
		if (hull != null)
		{
			Rectangle b = hull.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		Polygon poly = actor.getCanvasTilePoly();
		if (poly != null)
		{
			Rectangle b = poly.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		return null;
	}

	/**
	 * A canvas point over a scene object for hovering. Must be called on the client thread.
	 */
	private Point objectHoverPoint(TileObject obj)
	{
		Shape clickbox = obj.getClickbox();
		if (clickbox != null)
		{
			Rectangle b = clickbox.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		net.runelite.api.Point p = obj.getCanvasLocation();
		return p != null ? new Point(p.getX(), p.getY()) : null;
	}

	private NPC findNpc(JsonObject args)
	{
		Integer index = args.has("npcIndex") ? args.get("npcIndex").getAsInt() : null;
		String name = args.has("npcName") ? args.get("npcName").getAsString().toLowerCase() : null;
		if (index == null && name == null)
		{
			throw new IllegalArgumentException("Provide npcIndex or npcName");
		}
		WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null)
			{
				continue;
			}
			if (index != null)
			{
				if (npc.getIndex() == index)
				{
					return npc;
				}
				continue;
			}
			String npcName = npc.getName();
			if (npcName == null || !npcName.toLowerCase().contains(name))
			{
				continue;
			}
			int distance = me != null && npc.getWorldLocation() != null
				? me.distanceTo(npc.getWorldLocation())
				: 0;
			if (distance < bestDistance)
			{
				best = npc;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static String availableActions(String[] actions)
	{
		if (actions == null)
		{
			return "(none)";
		}
		StringBuilder sb = new StringBuilder();
		for (String action : actions)
		{
			if (action != null)
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(action);
			}
		}
		return sb.length() == 0 ? "(none)" : sb.toString();
	}

	private JsonElement getVar(JsonObject args) throws Exception
	{
		String type = args.get("type").getAsString();
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			JsonObject o = new JsonObject();
			o.addProperty("type", type);
			o.addProperty("id", id);
			switch (type)
			{
				case "varbit":
					o.addProperty("value", client.getVarbitValue(id));
					o.addProperty("serverValue", client.getServerVarbitValue(id));
					break;
				case "varp":
					o.addProperty("value", client.getVarpValue(id));
					o.addProperty("serverValue", client.getServerVarpValue(id));
					break;
				case "varcint":
					o.addProperty("value", client.getVarcIntValue(id));
					break;
				case "varcstr":
					o.addProperty("value", client.getVarcStrValue(id));
					break;
				default:
					throw new IllegalArgumentException("Unknown var type: " + type
						+ " (use varbit, varp, varcint or varcstr)");
			}
			return o;
		});
	}

	// --- dialogue ---

	private JsonObject selectOption(JsonObject args) throws Exception
	{
		int option = args.get("option").getAsInt();
		if (option < 1 || option > 9)
		{
			throw new IllegalArgumentException("Option must be 1-9");
		}
		String before = dialogueFingerprint();
		typeChar((char) ('0' + option));
		return textResult(waitDialogueChange(before));
	}

	private JsonObject enterInput(JsonObject args) throws Exception
	{
		String text = args.get("text").getAsString();
		String before = dialogueFingerprint();
		for (char c : text.toCharArray())
		{
			typeChar(c);
		}
		pressNamedKey("ENTER");
		return textResult(waitDialogueChange(before));
	}

	private String dialogueFingerprint() throws Exception
	{
		return McpHttpServer.GSON.toJson(onClientThread(this::readDialogue));
	}

	/**
	 * Poll the dialogue state until it differs from the given fingerprint, returning
	 * as soon as it changes instead of sleeping a fixed game tick. Falls through
	 * with the latest state after 2s so unchanged dialogues still return promptly.
	 */
	private JsonObject waitDialogueChange(String beforeFingerprint) throws Exception
	{
		long deadline = System.currentTimeMillis() + 2_000L;
		JsonObject current = null;
		while (System.currentTimeMillis() < deadline)
		{
			pauseMs(100);
			current = onClientThread(this::readDialogue);
			if (!McpHttpServer.GSON.toJson(current).equals(beforeFingerprint))
			{
				return current;
			}
		}
		return current;
	}

	/**
	 * Read whichever dialogue widget is currently up. Must be called on the client thread.
	 */
	private JsonObject readDialogue()
	{
		JsonObject o = new JsonObject();

		Widget npcText = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (visible(npcText))
		{
			o.addProperty("type", "npc");
			Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
			if (visible(name))
			{
				o.addProperty("speaker", name.getText());
			}
			o.addProperty("text", npcText.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.ChatLeft.CONTINUE)));
			return o;
		}

		Widget playerText = client.getWidget(InterfaceID.ChatRight.TEXT);
		if (visible(playerText))
		{
			o.addProperty("type", "player");
			Widget name = client.getWidget(InterfaceID.ChatRight.NAME);
			if (visible(name))
			{
				o.addProperty("speaker", name.getText());
			}
			o.addProperty("text", playerText.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.ChatRight.CONTINUE)));
			return o;
		}

		Widget optionsWidget = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (visible(optionsWidget))
		{
			o.addProperty("type", "options");
			Widget[] children = optionsWidget.getDynamicChildren();
			JsonArray options = new JsonArray();
			int number = 0;
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child == null || child.getText() == null || child.getText().isEmpty())
					{
						continue;
					}
					if (number == 0)
					{
						// First text child is the title, e.g. "Select an Option"
						o.addProperty("title", child.getText());
					}
					else
					{
						JsonObject opt = new JsonObject();
						opt.addProperty("option", number);
						opt.addProperty("text", child.getText());
						options.add(opt);
					}
					number++;
				}
			}
			o.add("options", options);
			return o;
		}

		Widget message = client.getWidget(InterfaceID.Messagebox.TEXT);
		if (visible(message))
		{
			o.addProperty("type", "message");
			o.addProperty("text", message.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.Messagebox.CONTINUE)));
			return o;
		}

		Widget itemText = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (visible(itemText))
		{
			o.addProperty("type", "item");
			o.addProperty("text", itemText.getText());
			Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
			if (visible(item) && item.getItemId() != -1)
			{
				o.addProperty("itemId", item.getItemId());
			}
			return o;
		}

		Widget inputPrompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (visible(inputPrompt))
		{
			o.addProperty("type", "input");
			o.addProperty("prompt", inputPrompt.getText());
			Widget current = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			if (visible(current))
			{
				o.addProperty("current", current.getText());
			}
			return o;
		}

		o.addProperty("type", "none");
		return o;
	}

	private static boolean visible(Widget w)
	{
		return w != null && !w.isHidden();
	}

	private JsonElement listProjectiles() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray arr = new JsonArray();
			for (Projectile projectile : client.getProjectiles())
			{
				if (projectile == null)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("projectileId", projectile.getId());
				o.addProperty("remainingCycles", projectile.getRemainingCycles());
				o.addProperty("startSceneX", projectile.getX1());
				o.addProperty("startSceneY", projectile.getY1());
				o.addProperty("slope", projectile.getSlope());
				o.addProperty("endHeight", projectile.getEndHeight());
				WorldPoint target = projectile.getTargetPoint();
				if (target != null)
				{
					o.addProperty("targetX", target.getX());
					o.addProperty("targetY", target.getY());
					o.addProperty("targetPlane", target.getPlane());
				}
				Actor targetActor = projectile.getTargetActor();
				if (targetActor != null)
				{
					o.addProperty("target", targetActor.getName());
					o.addProperty("targetType", targetActor instanceof NPC ? "npc" : "player");
				}
				arr.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", arr.size());
			result.add("projectiles", arr);
			return result;
		});
	}

	private JsonElement listGroundItems(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Scene scene = client.getScene();
			if (scene == null)
			{
				throw new IllegalStateException("No scene is loaded");
			}
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			int plane = client.getPlane();
			Tile[][] tiles = scene.getTiles()[plane];
			List<JsonObject> list = new ArrayList<>();
			for (Tile[] column : tiles)
			{
				for (Tile tile : column)
				{
					if (tile == null || tile.getGroundItems() == null)
					{
						continue;
					}
					WorldPoint wp = tile.getWorldLocation();
					int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
					if (distance >= 0 && distance > maxDistance)
					{
						continue;
					}
					for (TileItem item : tile.getGroundItems())
					{
						if (item == null)
						{
							continue;
						}
						ItemComposition composition = client.getItemDefinition(item.getId());
						String name = composition != null ? composition.getName() : null;
						if (filter != null && (name == null || !name.toLowerCase().contains(filter)))
						{
							continue;
						}
						JsonObject o = new JsonObject();
						o.addProperty("itemId", item.getId());
						if (name != null)
						{
							o.addProperty("name", name);
						}
						o.addProperty("quantity", item.getQuantity());
						if (wp != null)
						{
							o.addProperty("worldX", wp.getX());
							o.addProperty("worldY", wp.getY());
							o.addProperty("plane", wp.getPlane());
						}
						if (distance >= 0)
						{
							o.addProperty("distance", distance);
						}
						list.add(o);
					}
				}
			}
			return actorListResult("items", list, maxResults);
		});
	}

	private JsonElement getInventory(JsonObject args) throws Exception
	{
		int inventoryId = args.get("inventoryId").getAsInt();
		boolean includeEmpty = args.has("includeEmpty") && args.get("includeEmpty").getAsBoolean();
		return onClientThread(() ->
		{
			ItemContainer container = client.getItemContainer(inventoryId);
			if (container == null)
			{
				throw new IllegalArgumentException("Item container " + inventoryId
					+ " is not loaded; use list_inventories to see what exists");
			}
			Item[] items = container.getItems();
			JsonArray slots = new JsonArray();
			int nonEmpty = 0;
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				boolean empty = item == null || item.getId() == -1;
				if (empty && !includeEmpty)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("slot", slot);
				o.addProperty("itemId", empty ? -1 : item.getId());
				o.addProperty("quantity", empty ? 0 : item.getQuantity());
				if (!empty)
				{
					nonEmpty++;
					ItemComposition composition = client.getItemDefinition(item.getId());
					if (composition != null)
					{
						o.addProperty("name", composition.getName());
						o.add("inventoryActions", nonNull(composition.getInventoryActions()));
					}
				}
				slots.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("inventoryId", inventoryId);
			result.addProperty("size", items.length);
			result.addProperty("itemCount", nonEmpty);
			result.add("items", slots);
			return result;
		});
	}

	private JsonElement listInventories() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray arr = new JsonArray();
			for (ItemContainer container : client.getItemContainers())
			{
				if (container == null)
				{
					continue;
				}
				int nonEmpty = 0;
				for (Item item : container.getItems())
				{
					if (item != null && item.getId() != -1)
					{
						nonEmpty++;
					}
				}
				JsonObject o = new JsonObject();
				o.addProperty("inventoryId", container.getId());
				o.addProperty("size", container.size());
				o.addProperty("itemCount", nonEmpty);
				arr.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", arr.size());
			result.add("inventories", arr);
			return result;
		});
	}

	// --- decision-loop tools: observe, list_actions, act ---

	private static final MenuAction[] OBJECT_MENU_ACTIONS = {
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION,
	};

	/** Ops list_actions never offers: they need a second target, or only inform. */
	private static final Set<String> UNLISTED_OPS = Set.of("examine", "drop", "destroy", "use", "walk here", "cancel");

	/** Sections observe adds only on request, beyond its always-on core. */
	private static final Set<String> OBSERVE_SECTIONS = Set.of("equipment", "skills", "players", "projectiles");

	/** The stored call behind one list_actions entry. */
	private static final class PendingAction
	{
		final String tool;
		final JsonObject args;
		/** Returns an error message when the action is no longer valid, or null to proceed. */
		final Callable<String> validator;
		final Callable<JsonObject> runner;

		PendingAction(String tool, JsonObject args, Callable<String> validator, Callable<JsonObject> runner)
		{
			this.tool = tool;
			this.args = args;
			this.validator = validator;
			this.runner = runner;
		}
	}

	/** The actions behind the last list_actions menu, by action id, and the tick it was built on. */
	private final List<PendingAction> menuActions = new ArrayList<>();
	private int menuTick = -1;

	private JsonElement observe(JsonObject args) throws Exception
	{
		int maxDistance = optInt(args, "maxDistance", 15);
		int maxNpcs = optInt(args, "maxNpcs", 10);
		int maxObjects = optInt(args, "maxObjects", 10);
		int maxItems = optInt(args, "maxItems", 6);
		Set<String> sections = Objects.requireNonNullElse(optStringSet(args, "sections"), Set.of());
		for (String section : sections)
		{
			if (!OBSERVE_SECTIONS.contains(section))
			{
				throw new IllegalArgumentException("Unknown observe section '" + section
					+ "'; allowed: " + OBSERVE_SECTIONS);
			}
		}
		return onClientThread(() -> observeNow(maxDistance, maxNpcs, maxObjects, maxItems, sections));
	}

	/**
	 * One compact snapshot of what a decision loop reads each step, built in a single client-thread
	 * pass: vitals, combat state, inventory with item ops, nearest NPCs, interactive objects and
	 * ground items, and dialogue. Must be called on the client thread.
	 */
	private JsonObject observeNow(int maxDistance, int maxNpcs, int maxObjects, int maxItems,
		Set<String> sections)
	{
		JsonObject o = new JsonObject();
		Player local = client.getLocalPlayer();
		if (client.getGameState() != GameState.LOGGED_IN || local == null)
		{
			o.addProperty("loggedIn", false);
			return o;
		}
		WorldPoint me = local.getWorldLocation();
		o.addProperty("loggedIn", true);
		o.addProperty("tick", client.getTickCount());
		if (me != null)
		{
			o.addProperty("worldX", me.getX());
			o.addProperty("worldY", me.getY());
			o.addProperty("plane", me.getPlane());
			o.addProperty("regionId", me.getRegionID());
		}
		o.addProperty("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		o.addProperty("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
		o.addProperty("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
		o.addProperty("prayerMax", client.getRealSkillLevel(Skill.PRAYER));
		o.addProperty("runEnergy", client.getEnergy() / 100);
		o.addProperty("runOn", client.getVarpValue(VarPlayerID.OPTION_RUN) == 1);
		JsonObject boosts = new JsonObject();
		for (Skill skill : Skill.values())
		{
			int delta = client.getBoostedSkillLevel(skill) - client.getRealSkillLevel(skill);
			if (delta != 0)
			{
				boosts.addProperty(skill.getName(), delta);
			}
		}
		o.add("boosts", boosts);
		o.add("combat", combatStateJson());
		o.add("inventory", itemRows(client.getItemContainer(InventoryID.INV)));

		List<JsonObject> npcs = new ArrayList<>();
		for (NPC npc : client.getNpcs())
		{
			JsonObject row = npc != null ? describeActor(npc, me, maxDistance) : null;
			if (row == null)
			{
				continue;
			}
			NPCComposition comp = npc.getTransformedComposition() != null
				? npc.getTransformedComposition()
				: npc.getComposition();
			row.addProperty("index", npc.getIndex());
			row.addProperty("npcId", npc.getId());
			row.add("actions", nonNull(comp != null ? comp.getActions() : null));
			npcs.add(row);
		}
		o.add("npcs", nearest(npcs, maxNpcs));

		if (me != null)
		{
			Scene scene = client.getScene();
			Tile[][] tiles = scene != null ? scene.getTiles()[client.getPlane()] : null;
			Set<GameObject> seen = new HashSet<>();
			List<JsonObject> objects = new ArrayList<>();
			List<JsonObject> ground = new ArrayList<>();
			if (tiles != null)
			{
				for (Tile[] column : tiles)
				{
					for (Tile tile : column)
					{
						if (tile == null)
						{
							continue;
						}
						for (TileObject obj : tileObjects(tile, seen))
						{
							JsonObject row = describeTileObject("object", obj, me, null, null, maxDistance);
							if (row != null && row.has("name") && row.has("actions")
								&& row.getAsJsonArray("actions").size() > 0)
							{
								objects.add(row);
							}
						}
						if (tile.getGroundItems() == null)
						{
							continue;
						}
						WorldPoint wp = tile.getWorldLocation();
						if (wp == null)
						{
							continue;
						}
						int distance = me.distanceTo(wp);
						if (distance > maxDistance)
						{
							continue;
						}
						for (TileItem item : tile.getGroundItems())
						{
							if (item == null)
							{
								continue;
							}
							JsonObject row = new JsonObject();
							row.addProperty("itemId", item.getId());
							ItemComposition composition = client.getItemDefinition(item.getId());
							if (composition != null && composition.getName() != null)
							{
								row.addProperty("name", composition.getName());
							}
							row.addProperty("quantity", item.getQuantity());
							row.addProperty("worldX", wp.getX());
							row.addProperty("worldY", wp.getY());
							row.addProperty("distance", distance);
							ground.add(row);
						}
					}
				}
			}
			o.add("objects", nearest(objects, maxObjects));
			o.add("groundItems", nearest(ground, maxItems));
		}
		else
		{
			o.add("objects", new JsonArray());
			o.add("groundItems", new JsonArray());
		}
		o.add("dialogue", readDialogue());

		if (sections.contains("equipment"))
		{
			o.add("equipment", itemRows(client.getItemContainer(InventoryID.WORN)));
		}
		if (sections.contains("skills"))
		{
			JsonArray skills = new JsonArray();
			for (Skill skill : Skill.values())
			{
				JsonObject row = new JsonObject();
				row.addProperty("skill", skill.getName());
				row.addProperty("level", client.getRealSkillLevel(skill));
				row.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
				row.addProperty("xp", client.getSkillExperience(skill));
				skills.add(row);
			}
			o.add("skills", skills);
		}
		if (sections.contains("players"))
		{
			List<JsonObject> players = new ArrayList<>();
			for (Player player : client.getPlayers())
			{
				JsonObject row = player != null ? describeActor(player, me, maxDistance) : null;
				if (row == null)
				{
					continue;
				}
				if (player == local)
				{
					row.addProperty("isLocal", true);
				}
				players.add(row);
			}
			o.add("players", nearest(players, 20));
		}
		if (sections.contains("projectiles"))
		{
			o.add("projectiles", projectilesJson());
		}
		return o;
	}

	/** Compact per-slot rows for an item container (inventory, worn). Client thread only. */
	private JsonArray itemRows(ItemContainer container)
	{
		JsonArray rows = new JsonArray();
		if (container == null)
		{
			return rows;
		}
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() == -1)
			{
				continue;
			}
			JsonObject row = new JsonObject();
			row.addProperty("slot", slot);
			row.addProperty("itemId", item.getId());
			row.addProperty("quantity", item.getQuantity());
			ItemComposition composition = client.getItemDefinition(item.getId());
			if (composition != null)
			{
				if (composition.getName() != null)
				{
					row.addProperty("name", composition.getName());
				}
				row.add("actions", nonNull(composition.getInventoryActions()));
			}
			rows.add(row);
		}
		return rows;
	}

	/** In-flight projectiles, same shape as list_projectiles. Client thread only. */
	private JsonArray projectilesJson()
	{
		JsonArray arr = new JsonArray();
		for (Projectile projectile : client.getProjectiles())
		{
			if (projectile == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("projectileId", projectile.getId());
			o.addProperty("remainingCycles", projectile.getRemainingCycles());
			o.addProperty("startSceneX", projectile.getX1());
			o.addProperty("startSceneY", projectile.getY1());
			WorldPoint target = projectile.getTargetPoint();
			if (target != null)
			{
				o.addProperty("targetX", target.getX());
				o.addProperty("targetY", target.getY());
				o.addProperty("targetPlane", target.getPlane());
			}
			Actor targetActor = projectile.getTargetActor();
			if (targetActor != null)
			{
				o.addProperty("target", targetActor.getName());
				o.addProperty("targetType", targetActor instanceof NPC ? "npc" : "player");
			}
			arr.add(o);
		}
		return arr;
	}

	/** Game, wall, ground and decorative objects on a tile; multi-tile game objects once. */
	private static List<TileObject> tileObjects(Tile tile, Set<GameObject> seen)
	{
		List<TileObject> out = new ArrayList<>();
		if (tile.getGameObjects() != null)
		{
			for (GameObject go : tile.getGameObjects())
			{
				if (go != null && seen.add(go))
				{
					out.add(go);
				}
			}
		}
		if (tile.getWallObject() != null)
		{
			out.add(tile.getWallObject());
		}
		if (tile.getGroundObject() != null)
		{
			out.add(tile.getGroundObject());
		}
		if (tile.getDecorativeObject() != null)
		{
			out.add(tile.getDecorativeObject());
		}
		return out;
	}

	private static JsonArray nonNull(String[] values)
	{
		JsonArray out = new JsonArray();
		if (values != null)
		{
			for (String v : values)
			{
				if (v != null && !v.isEmpty() && !"null".equals(v))
				{
					out.add(v);
				}
			}
		}
		return out;
	}

	private static JsonArray nearest(List<JsonObject> rows, int max)
	{
		rows.sort(Comparator.comparingInt(r -> r.has("distance") ? r.get("distance").getAsInt() : Integer.MAX_VALUE));
		JsonArray out = new JsonArray();
		rows.stream().limit(max).forEach(out::add);
		return out;
	}

	@FunctionalInterface
	private interface MenuBuilder
	{
		void add(String label, String kind, String target, PendingAction action);
	}

	private static JsonObject argsOf(Object... pairs)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i < pairs.length; i += 2)
		{
			o.add((String) pairs[i], (JsonElement) pairs[i + 1]);
		}
		return o;
	}

	/**
	 * Every action available right now as a numbered menu, built from one observe pass. Each entry
	 * is a plain-language label (what a small decision model picks from) plus the tool and arguments
	 * that perform it; act(id) runs one.
	 */
	private JsonElement listActions(JsonObject args) throws Exception
	{
		boolean dedupe = !args.has("dedupe") || args.get("dedupe").getAsBoolean();
		int maxActions = optInt(args, "maxActions", 40);
		// Wider caps than observe's defaults: a menu wants candidates, not just the nearest 10.
		JsonObject obs = onClientThread(() -> observeNow(
			optInt(args, "maxDistance", 15), 25, 25, 12, Set.of()));
		JsonObject result = new JsonObject();
		if (!obs.get("loggedIn").getAsBoolean())
		{
			result.addProperty("loggedIn", false);
			return result;
		}
		JsonArray actions = new JsonArray();
		List<PendingAction> pending = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		MenuBuilder menu = (label, kind, target, action) ->
		{
			if (actions.size() >= maxActions || (dedupe && !seen.add(label.replaceAll(" \\(.*", ""))))
			{
				return;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("id", actions.size());
			entry.addProperty("label", label);
			entry.addProperty("kind", kind);
			if (target != null)
			{
				entry.addProperty("target", target);
			}
			entry.addProperty("tool", action.tool);
			entry.add("args", action.args);
			actions.add(entry);
			pending.add(action);
		};

		JsonObject dialogue = obs.getAsJsonObject("dialogue");
		String dialogueType = dialogue.has("type") ? dialogue.get("type").getAsString() : "none";
		if ("options".equals(dialogueType))
		{
			// An options menu blocks everything else until it is answered.
			for (JsonElement e : dialogue.getAsJsonArray("options"))
			{
				JsonObject opt = e.getAsJsonObject();
				JsonObject call = argsOf("option", opt.get("option"));
				menu.add(Text.removeTags(opt.get("text").getAsString()), "dialogue", null,
					new PendingAction("select_option", call, () -> checkDialogue("options"),
						() -> callTool("select_option", call)));
			}
		}
		else if ("input".equals(dialogueType))
		{
			String prompt = dialogue.has("prompt") ? dialogue.get("prompt").getAsString() : "input prompt";
			JsonObject call = new JsonObject();
			menu.add("answer '" + Text.removeTags(prompt) + "' (pass args.text)", "dialogue", null,
				new PendingAction("enter_input", call, () -> checkDialogue("input"),
					() -> callTool("enter_input", call)));
		}
		else
		{
			if (dialogue.has("canContinue") && dialogue.get("canContinue").getAsBoolean())
			{
				menu.add("continue the dialogue", "dialogue", null,
					new PendingAction("continue_dialogue", new JsonObject(), () -> checkDialogue(null),
						() -> callTool("continue_dialogue", new JsonObject())));
			}
			for (JsonElement e : obs.getAsJsonArray("npcs"))
			{
				JsonObject npc = e.getAsJsonObject();
				if (npc.has("isDead") || !npc.has("name") || !npc.has("index"))
				{
					continue;
				}
				String name = npc.get("name").getAsString();
				String detail = " (lvl " + npc.get("combatLevel").getAsInt()
					+ (npc.has("distance") ? ", " + npc.get("distance").getAsInt() + " tiles" : "")
					+ (npc.has("targetsMe") ? ", attacking you" : "") + ")";
				for (JsonElement op : npc.getAsJsonArray("actions"))
				{
					if (UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						continue;
					}
					JsonObject call = argsOf("npcIndex", npc.get("index"), "option", op);
					int expectedIndex = npc.get("index").getAsInt();
					int expectedNpcId = npc.get("npcId").getAsInt();
					menu.add(op.getAsString() + " " + name + detail, "npc", name,
						new PendingAction("interact_npc", call, () -> checkNpc(expectedIndex, expectedNpcId),
							() -> callTool("interact_npc", call)));
				}
			}
			for (JsonElement e : obs.getAsJsonArray("objects"))
			{
				JsonObject obj = e.getAsJsonObject();
				if (!obj.has("name"))
				{
					continue;
				}
				for (JsonElement op : obj.getAsJsonArray("actions"))
				{
					if (UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						continue;
					}
					JsonObject call = argsOf("objectId", obj.get("objectId"), "x", obj.get("worldX"),
						"y", obj.get("worldY"), "option", op);
					menu.add(op.getAsString() + " " + obj.get("name").getAsString()
						+ (obj.has("distance") ? " (" + obj.get("distance").getAsInt() + " tiles)" : ""),
						"object", obj.get("name").getAsString(),
						new PendingAction("object_action", call, null, () -> callTool("object_action", call)));
				}
			}
			for (JsonElement e : obs.getAsJsonArray("groundItems"))
			{
				JsonObject item = e.getAsJsonObject();
				String name = item.has("name") ? item.get("name").getAsString() : "item " + item.get("itemId").getAsInt();
				JsonObject call = argsOf("itemId", item.get("itemId"), "x", item.get("worldX"),
					"y", item.get("worldY"));
				menu.add("take " + name
					+ (item.has("distance") ? " (" + item.get("distance").getAsInt() + " tiles)" : ""),
					"ground", name, new PendingAction("pickup_item", call,
						() -> checkGroundItem(item.get("itemId").getAsInt(),
							item.get("worldX").getAsInt(), item.get("worldY").getAsInt()),
						() -> callTool("pickup_item", call)));
			}
			for (JsonElement e : obs.getAsJsonArray("inventory"))
			{
				JsonObject item = e.getAsJsonObject();
				String name = item.has("name") ? item.get("name").getAsString() : "item " + item.get("itemId").getAsInt();
				for (JsonElement op : item.getAsJsonArray("actions"))
				{
					if (UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						continue;
					}
					JsonObject call = argsOf("itemId", item.get("itemId"), "option", op);
					menu.add(op.getAsString().toLowerCase() + " " + name, "item", null,
						new PendingAction("item_action", call, null, () -> callTool("item_action", call)));
				}
			}
		}

		int tick = obs.get("tick").getAsInt();
		synchronized (menuActions)
		{
			menuActions.clear();
			menuActions.addAll(pending);
			menuTick = tick;
		}
		result.addProperty("tick", tick);
		result.add("actions", actions);
		if (args.has("includeObservation") && args.get("includeObservation").getAsBoolean())
		{
			result.add("observation", obs);
		}
		return result;
	}

	private JsonElement act(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		int maxAge = optInt(args, "maxAgeTicks", 3);
		PendingAction action;
		int tick = onClientThread(() -> client.getTickCount());
		int age;
		synchronized (menuActions)
		{
			if (menuTick < 0)
			{
				throw new IllegalStateException("No action menu built yet; call list_actions first");
			}
			if (id < 0 || id >= menuActions.size())
			{
				throw new IllegalArgumentException("No action " + id + " in the last list_actions menu ("
					+ menuActions.size() + " actions)");
			}
			age = tick - menuTick;
			action = menuActions.get(id);
		}
		if (age > maxAge)
		{
			throw new IllegalStateException("The action menu is " + age + " ticks old; call list_actions again");
		}
		JsonObject call = action.args.deepCopy();
		if (args.has("args") && args.get("args").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : args.getAsJsonObject("args").entrySet())
			{
				call.add(e.getKey(), e.getValue());
			}
		}
		if (action.validator != null)
		{
			String error = action.validator.call();
			if (error != null)
			{
				throw new IllegalStateException(error + "; call list_actions again");
			}
		}
		JsonObject result = callTool(action.tool, call);
		result.addProperty("actionId", id);
		return result;
	}

	/** Revalidation: the dialogue still has the expected type (any open type when expected is null). */
	private String checkDialogue(String expectedType) throws Exception
	{
		String type = onClientThread(() ->
		{
			JsonObject dialogue = readDialogue();
			return dialogue.has("type") ? dialogue.get("type").getAsString() : "none";
		});
		if (expectedType != null && !expectedType.equals(type))
		{
			return "Dialogue is no longer " + expectedType + " (now " + type + ")";
		}
		if (expectedType == null && "none".equals(type))
		{
			return "Dialogue is no longer open";
		}
		return null;
	}

	/** Revalidation: the NPC index still points at the same NPC. */
	private String checkNpc(int index, int npcId) throws Exception
	{
		return onClientThread(() ->
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc != null && npc.getIndex() == index)
				{
					if (npc.getId() == npcId && !npc.isDead())
					{
						return null;
					}
					return "NPC index " + index + " now holds a different NPC";
				}
			}
			return "NPC index " + index + " despawned";
		});
	}

	/** Revalidation: the ground item is still on the tile. */
	private String checkGroundItem(int itemId, int x, int y) throws Exception
	{
		return onClientThread(() ->
		{
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, client.getPlane()));
			if (lp == null)
			{
				return "Tile " + x + "," + y + " is no longer in the loaded scene";
			}
			Tile tile = client.getScene().getTiles()[client.getPlane()][lp.getSceneX()][lp.getSceneY()];
			if (tile != null && tile.getGroundItems() != null)
			{
				for (TileItem item : tile.getGroundItems())
				{
					if (item != null && item.getId() == itemId)
					{
						return null;
					}
				}
			}
			return "Item " + itemId + " is no longer on tile " + x + "," + y;
		});
	}

	/**
	 * Invoke a scene object's op directly (the menu entry the client would build: scene x/y, object
	 * id), so the object need not be on screen and the mouse never moves.
	 */
	private JsonObject invokeObjectOp(JsonObject args) throws Exception
	{
		int objectId = args.get("objectId").getAsInt();
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		String option = args.get("option").getAsString();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		return onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
			}
			Scene scene = client.getScene();
			Tile tile = scene != null ? scene.getTiles()[plane][lp.getSceneX()][lp.getSceneY()] : null;
			TileObject obj = tile == null ? null : tileObjects(tile, new HashSet<>()).stream()
				.filter(o -> o.getId() == objectId).findFirst().orElse(null);
			if (obj == null)
			{
				throw new IllegalArgumentException("Object " + objectId + " is no longer at " + x + "," + y);
			}
			ObjectComposition comp = client.getObjectDefinition(objectId);
			if (comp != null && comp.getImpostorIds() != null && comp.getImpostor() != null)
			{
				comp = comp.getImpostor();
			}
			String[] ops = comp != null ? comp.getActions() : null;
			int index = -1;
			for (int i = 0; ops != null && i < ops.length && i < OBJECT_MENU_ACTIONS.length; i++)
			{
				if (ops[i] != null && ops[i].equalsIgnoreCase(option))
				{
					index = i;
				}
			}
			if (index < 0)
			{
				throw new IllegalArgumentException((comp != null ? comp.getName() : "Object " + objectId)
					+ " has no option '" + option + "'. Available: " + availableActions(ops));
			}
			int sceneX = lp.getSceneX();
			int sceneY = lp.getSceneY();
			if (obj instanceof GameObject)
			{
				net.runelite.api.Point min = ((GameObject) obj).getSceneMinLocation();
				sceneX = min.getX();
				sceneY = min.getY();
			}
			client.menuAction(sceneX, sceneY, OBJECT_MENU_ACTIONS[index], objectId, -1, option,
				comp != null && comp.getName() != null ? comp.getName() : "");
			JsonObject result = new JsonObject();
			result.addProperty("invoked", option);
			result.addProperty("object", comp != null ? comp.getName() : null);
			result.addProperty("objectId", objectId);
			return result;
		});
	}

	// --- evidence / regression ---

	/** Stored snapshot_state payloads by label. */
	private final Map<String, JsonObject> snapshots = new HashMap<>();

	private JsonObject snapshotState(JsonObject args) throws Exception
	{
		String label = args.has("label") ? args.get("label").getAsString() : "last";
		JsonObject snap = onClientThread(() -> observeNow(15, 25, 25, 12,
			Set.of("equipment", "skills", "projectiles")));
		snap.addProperty("snapshotAtMs", System.currentTimeMillis());
		snapshots.put(label, snap);
		JsonObject result = snap.deepCopy();
		result.addProperty("label", label);
		return result;
	}

	private JsonObject diffState(JsonObject args) throws Exception
	{
		String label = args.has("from") ? args.get("from").getAsString() : "last";
		JsonObject before = snapshots.get(label);
		if (before == null)
		{
			throw new IllegalArgumentException("No snapshot labelled '" + label
				+ "'; call snapshot_state first (known: " + snapshots.keySet() + ")");
		}
		int maxDistance = optInt(args, "maxDistance", 15);
		JsonObject after = onClientThread(() -> observeNow(maxDistance, 25, 25, 12,
			Set.of("equipment", "skills", "projectiles")));
		JsonObject changes = new JsonObject();
		diffJson("", before, after, changes);
		JsonObject result = new JsonObject();
		result.addProperty("from", label);
		result.addProperty("snapshotTick", before.has("tick") ? before.get("tick").getAsInt() : -1);
		result.addProperty("nowTick", after.has("tick") ? after.get("tick").getAsInt() : -1);
		result.addProperty("changed", changes.size() > 0);
		result.add("changes", changes);
		return result;
	}

	/** Keys that identify one row inside an array of objects, in priority order. */
	private static final String[] IDENTITY_KEYS = {
		"slot", "index", "npcId", "objectId", "itemId", "skill", "name", "worldX"};

	private static void diffJson(String path, JsonElement a, JsonElement b, JsonObject out)
	{
		if (a == null || a.isJsonNull())
		{
			if (b != null && !b.isJsonNull())
			{
				JsonObject change = new JsonObject();
				change.add("added", b);
				out.add(path, change);
			}
			return;
		}
		if (b == null || b.isJsonNull())
		{
			JsonObject change = new JsonObject();
			change.add("removed", a);
			out.add(path, change);
			return;
		}
		if (a.isJsonObject() && b.isJsonObject())
		{
			Set<String> keys = new HashSet<>(a.getAsJsonObject().keySet());
			keys.addAll(b.getAsJsonObject().keySet());
			for (String key : keys)
			{
				if ("timeMs".equals(key) || "seq".equals(key) || "snapshotAtMs".equals(key))
				{
					continue; // timestamps always differ
				}
				diffJson(path.isEmpty() ? key : path + "." + key,
					a.getAsJsonObject().get(key), b.getAsJsonObject().get(key), out);
			}
			return;
		}
		if (a.isJsonArray() && b.isJsonArray())
		{
			diffArrays(path, a.getAsJsonArray(), b.getAsJsonArray(), out);
			return;
		}
		if (!a.equals(b))
		{
			JsonObject change = new JsonObject();
			change.add("was", a);
			change.add("now", b);
			out.add(path, change);
		}
	}

	/** Match array rows by an identity field so reordered lists don't diff as churn. */
	private static void diffArrays(String path, JsonArray a, JsonArray b, JsonObject out)
	{
		String idKey = identityKey(a, b);
		if (idKey != null)
		{
			Map<String, JsonObject> beforeRows = new HashMap<>();
			for (JsonElement e : a)
			{
				if (e.isJsonObject() && e.getAsJsonObject().has(idKey))
				{
					beforeRows.put(e.getAsJsonObject().get(idKey).getAsString(), e.getAsJsonObject());
				}
			}
			Set<String> seen = new HashSet<>();
			for (JsonElement e : b)
			{
				if (!e.isJsonObject() || !e.getAsJsonObject().has(idKey))
				{
					continue;
				}
				String key = e.getAsJsonObject().get(idKey).getAsString();
				seen.add(key);
				diffJson(path + "[" + key + "]", beforeRows.get(key), e, out);
			}
			for (Map.Entry<String, JsonObject> row : beforeRows.entrySet())
			{
				if (!seen.contains(row.getKey()))
				{
					JsonObject change = new JsonObject();
					change.add("removed", row.getValue());
					out.add(path + "[" + row.getKey() + "]", change);
				}
			}
			return;
		}
		if (!a.equals(b))
		{
			JsonObject change = new JsonObject();
			change.add("was", a);
			change.add("now", b);
			out.add(path, change);
		}
	}

	private static String identityKey(JsonArray a, JsonArray b)
	{
		for (String key : IDENTITY_KEYS)
		{
			boolean inA = a.size() == 0
				|| (a.get(0).isJsonObject() && a.get(0).getAsJsonObject().has(key));
			boolean inB = b.size() == 0
				|| (b.get(0).isJsonObject() && b.get(0).getAsJsonObject().has(key));
			if ((a.size() > 0 || b.size() > 0) && inA && inB)
			{
				return key;
			}
		}
		return null;
	}

	private JsonObject runBatch(JsonObject args) throws Exception
	{
		JsonArray steps = args.getAsJsonArray("steps");
		boolean stopOnError = !args.has("stopOnError") || args.get("stopOnError").getAsBoolean();
		JsonArray results = new JsonArray();
		int failed = 0;
		for (int i = 0; i < steps.size(); i++)
		{
			JsonObject step = steps.get(i).getAsJsonObject();
			String tool = step.get("tool").getAsString();
			JsonObject stepArgs = step.has("arguments") && step.get("arguments").isJsonObject()
				? step.getAsJsonObject("arguments") : new JsonObject();
			JsonObject row = new JsonObject();
			row.addProperty("step", i);
			row.addProperty("tool", tool);
			try
			{
				JsonObject result = callTool(tool, stepArgs);
				row.addProperty("ok", !(result.has("isError") && result.get("isError").getAsBoolean()));
				row.add("result", result);
				if (!row.get("ok").getAsBoolean())
				{
					failed++;
				}
			}
			catch (Exception e)
			{
				row.addProperty("ok", false);
				row.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
				failed++;
			}
			results.add(row);
			if (failed > 0 && stopOnError)
			{
				break;
			}
		}
		JsonObject result = new JsonObject();
		result.addProperty("stepsRun", results.size());
		result.addProperty("failed", failed);
		result.add("steps", results);
		return result;
	}

	// --- combat widgets ---

	private JsonObject getSpecialAttack() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("energy", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
			result.addProperty("enabled", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) != 0);
			return result;
		});
	}

	private JsonObject toggleSpecialAttack() throws Exception
	{
		Point p = onClientThread(() ->
		{
			Widget button = client.getWidget(InterfaceID.Orbs.SPECBUTTON);
			if (button == null || button.isHidden())
			{
				throw new IllegalStateException("Special attack orb is not visible");
			}
			Rectangle b = button.getBounds();
			if (b == null || b.width <= 0)
			{
				throw new IllegalStateException("Special attack orb has no bounds");
			}
			return new Point(b.x + b.width / 2, b.y + b.height / 2);
		});
		clickAt(p.x, p.y, MouseEvent.BUTTON1);
		return getSpecialAttack();
	}

	private static final int[] STYLE_WIDGETS = {
		InterfaceID.CombatInterface._0, InterfaceID.CombatInterface._1,
		InterfaceID.CombatInterface._2, InterfaceID.CombatInterface._3};
	private static final int[] STYLE_TEXT_WIDGETS = {
		InterfaceID.CombatInterface._0_TEXT, InterfaceID.CombatInterface._1_TEXT,
		InterfaceID.CombatInterface._2_TEXT, InterfaceID.CombatInterface._3_TEXT};

	private JsonObject getAttackStyle() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("styleIndex", client.getVarpValue(VarPlayer.ATTACK_STYLE));
			JsonArray styles = new JsonArray();
			for (int i = 0; i < STYLE_WIDGETS.length; i++)
			{
				Widget text = client.getWidget(STYLE_TEXT_WIDGETS[i]);
				JsonObject row = new JsonObject();
				row.addProperty("index", i);
				if (text != null && text.getText() != null && !text.getText().isEmpty())
				{
					row.addProperty("name", Text.removeTags(text.getText()));
				}
				styles.add(row);
			}
			result.add("styles", styles);
			return result;
		});
	}

	private JsonObject setAttackStyle(JsonObject args) throws Exception
	{
		String style = args.get("style").getAsString().toLowerCase();
		Point p = onClientThread(() ->
		{
			for (int i = 0; i < STYLE_WIDGETS.length; i++)
			{
				Widget text = client.getWidget(STYLE_TEXT_WIDGETS[i]);
				String name = text != null && text.getText() != null
					? Text.removeTags(text.getText()).toLowerCase() : "";
				if ((name.contains(style) || String.valueOf(i).equals(style)) && !name.isEmpty())
				{
					Widget button = client.getWidget(STYLE_WIDGETS[i]);
					Rectangle b = button != null ? button.getBounds() : null;
					if (b == null || b.width <= 0)
					{
						throw new IllegalStateException("Attack style " + i + " has no clickable bounds");
					}
					return new Point(b.x + b.width / 2, b.y + b.height / 2);
				}
			}
			throw new IllegalArgumentException("No attack style matching '" + style
				+ "'; call get_attack_style for what the wielded weapon offers");
		});
		clickAt(p.x, p.y, MouseEvent.BUTTON1);
		return getAttackStyle();
	}

	// --- magic ---

	private JsonObject listSpells() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray spells = new JsonArray();
			collectSpellWidgets(spells, null);
			JsonObject result = new JsonObject();
			result.addProperty("count", spells.size());
			result.add("spells", spells);
			return result;
		});
	}

	private JsonObject castSpell(JsonObject args) throws Exception
	{
		String spell = args.get("spell").getAsString().toLowerCase();
		JsonObject resolved = onClientThread(() ->
		{
			JsonArray spells = new JsonArray();
			JsonObject match = collectSpellWidgets(spells, spell);
			if (match == null)
			{
				JsonArray names = new JsonArray();
				for (JsonElement s : spells)
				{
					names.add(s.getAsJsonObject().get("name").getAsString());
				}
				throw new IllegalArgumentException("No spell matching '" + spell
					+ "' in the spellbook. Available: " + names);
			}
			Widget widget = client.getWidget(match.get("componentId").getAsInt());
			Rectangle b = widget != null ? widget.getBounds() : null;
			if (b == null || b.width <= 0)
			{
				throw new IllegalStateException("Spell widget is hidden or has no bounds: "
					+ match.get("name").getAsString());
			}
			match.addProperty("cx", b.x + b.width / 2);
			match.addProperty("cy", b.y + b.height / 2);
			return match;
		});
		clickAt(resolved.get("cx").getAsInt(), resolved.get("cy").getAsInt(), MouseEvent.BUTTON1);
		JsonObject result = new JsonObject();
		result.addProperty("cast", resolved.get("name").getAsString());
		result.addProperty("componentId", resolved.get("componentId").getAsInt());
		return result;
	}

	/**
	 * Collect spell widgets from the standard spellbook into rows (name + componentId).
	 * Returns the best fuzzy match for {@code matchName}, or null. Client thread only.
	 */
	private JsonObject collectSpellWidgets(JsonArray out, String matchName)
	{
		Widget universe = client.getWidget(InterfaceID.MagicSpellbook.UNIVERSE);
		if (universe == null)
		{
			return null;
		}
		JsonObject best = null;
		Deque<Widget> queue = new ArrayDeque<>();
		queue.add(universe);
		while (!queue.isEmpty())
		{
			Widget w = queue.poll();
			if (w == null)
			{
				continue;
			}
			String name = w.getName() != null ? Text.removeTags(w.getName()) : "";
			if (!name.isEmpty() && w.getActions() != null)
			{
				JsonObject row = new JsonObject();
				row.addProperty("name", name);
				row.addProperty("componentId", w.getId());
				out.add(row);
				if (matchName != null && name.toLowerCase().contains(matchName))
				{
					best = row;
				}
			}
			enqueueChildren(queue, w.getStaticChildren());
			enqueueChildren(queue, w.getDynamicChildren());
			enqueueChildren(queue, w.getNestedChildren());
		}
		return best;
	}

	// --- world / movement ---

	private JsonObject isWalkable(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		return onClientThread(() ->
		{
			int plane = args.has("plane") ? args.get("plane").getAsInt() : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
			}
			CollisionData[] maps = client.getCollisionMaps();
			if (maps == null || maps[plane] == null)
			{
				throw new IllegalStateException("No collision map loaded for plane " + plane);
			}
			int flags = maps[plane].getFlags()[lp.getSceneX()][lp.getSceneY()];
			JsonObject result = new JsonObject();
			result.addProperty("x", x);
			result.addProperty("y", y);
			result.addProperty("plane", plane);
			result.addProperty("flags", flags);
			int tileBlock = CollisionDataFlag.BLOCK_MOVEMENT_FULL
				| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
			result.addProperty("walkable", (flags & tileBlock) == 0);
			JsonArray blocked = new JsonArray();
			if ((flags & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) blocked.add("north");
			if ((flags & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) blocked.add("east");
			if ((flags & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) blocked.add("south");
			if ((flags & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) blocked.add("west");
			result.add("blockedDirections", blocked);
			return result;
		});
	}

	private JsonObject getDestination() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			LocalPoint dest = client.getLocalDestinationLocation();
			if (dest != null && dest.isInScene())
			{
				WorldPoint wp = WorldPoint.fromLocal(client, dest);
				result.addProperty("x", wp.getX());
				result.addProperty("y", wp.getY());
				result.addProperty("plane", wp.getPlane());
			}
			result.addProperty("moving", dest != null && dest.isInScene());
			return result;
		});
	}

	// --- interfaces ---

	private JsonObject searchInterfaces(JsonObject args) throws Exception
	{
		String needle = args.get("text").getAsString().toLowerCase();
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			JsonArray matches = new JsonArray();
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				Deque<Widget> queue = new ArrayDeque<>();
				for (Widget root : roots)
				{
					if (root != null)
					{
						queue.add(root);
					}
				}
				while (!queue.isEmpty() && matches.size() < maxResults)
				{
					Widget w = queue.poll();
					if (w == null)
					{
						continue;
					}
					String text = w.getText();
					if (text != null && Text.removeTags(text).toLowerCase().contains(needle))
					{
						JsonObject row = new JsonObject();
						row.addProperty("componentId", w.getId());
						row.addProperty("interfaceId", w.getId() >>> 16);
						String clean = Text.removeTags(text);
						row.addProperty("text", clean.length() > 120 ? clean.substring(0, 120) + "…" : clean);
						row.addProperty("hidden", w.isHidden());
						matches.add(row);
					}
					enqueueChildren(queue, w.getStaticChildren());
					enqueueChildren(queue, w.getDynamicChildren());
					enqueueChildren(queue, w.getNestedChildren());
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", matches.size());
			result.addProperty("truncated", matches.size() >= maxResults);
			result.add("matches", matches);
			return result;
		});
	}

	private JsonObject bankAction(JsonObject args) throws Exception
	{
		boolean depositSide = args.has("side") && "inventory".equalsIgnoreCase(args.get("side").getAsString());
		return itemSlotAction(args,
			depositSide ? InterfaceID.Bankside.ITEMS : InterfaceID.Bankmain.ITEMS,
			"Bank is not open",
			depositSide ? "inventory" : "bank");
	}

	/**
	 * Invoke an op on an item widget inside a container widget (bank, shop, trade panels):
	 * the same CC_OP the client sends, no hover or mouse. Runs on the client thread.
	 */
	private JsonObject itemSlotAction(JsonObject args, int containerWidget, String notOpenError,
		String sideLabel) throws Exception
	{
		String option = args.get("option").getAsString();
		Integer itemId = args.has("itemId") ? args.get("itemId").getAsInt() : null;
		String itemName = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase() : null;
		return onClientThread(() ->
		{
			Widget container = client.getWidget(containerWidget);
			if (container == null || container.isHidden())
			{
				throw new IllegalStateException(notOpenError + " (widget "
					+ (containerWidget >>> 16) + " not visible)");
			}
			Widget slot = findItemSlot(container, itemId, itemName);
			if (slot == null)
			{
				throw new IllegalArgumentException("Item not found on the " + sideLabel + " side: "
					+ (itemId != null ? "id " + itemId : itemName));
			}
			String[] ops = slot.getActions();
			for (int i = 0; ops != null && i < ops.length; i++)
			{
				if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase(option))
				{
					client.menuAction(slot.getIndex(), containerWidget,
						i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY, i + 1,
						slot.getItemId(), ops[i], slot.getName());
					JsonObject result = new JsonObject();
					result.addProperty("invoked", ops[i]);
					result.addProperty("slot", slot.getIndex());
					result.addProperty("itemId", slot.getItemId());
					result.addProperty("side", sideLabel);
					return result;
				}
			}
			throw new IllegalArgumentException("Item has no '" + option
				+ "' op. Available: " + availableActions(ops));
		});
	}

	/** First dynamic child of container carrying itemId/itemName; must be on the client thread. */
	private Widget findItemSlot(Widget container, Integer itemId, String itemName)
	{
		Widget[] slots = container.getDynamicChildren();
		if (slots == null)
		{
			return null;
		}
		for (Widget slot : slots)
		{
			if (slot == null || slot.getItemId() <= 0)
			{
				continue;
			}
			if (itemId != null && slot.getItemId() != itemId)
			{
				continue;
			}
			if (itemName != null)
			{
				ItemComposition composition = client.getItemDefinition(slot.getItemId());
				String name = composition != null ? composition.getName() : null;
				if (name == null || !name.toLowerCase().contains(itemName))
				{
					continue;
				}
			}
			return slot;
		}
		return null;
	}

	/**
	 * All widgets in an interface group that render an item (itemId > 0),
	 * grouped for reporting. Must be on the client thread.
	 */
	private JsonArray itemWidgetsInGroup(int groupId)
	{
		JsonArray groups = new JsonArray();
		for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE; child++)
		{
			Widget w = client.getWidget(WidgetUtil.packComponentId(groupId, child));
			if (w == null)
			{
				continue;
			}
			JsonObject group = new JsonObject();
			group.addProperty("componentId", w.getId());
			JsonArray items = new JsonArray();
			collectItemWidgets(w, items);
			if (items.size() > 0)
			{
				group.add("items", items);
				groups.add(group);
			}
		}
		return groups;
	}

	private void collectItemWidgets(Widget parent, JsonArray out)
	{
		if (parent == null || parent.isHidden())
		{
			return;
		}
		if (parent.getItemId() > 0)
		{
			JsonObject o = new JsonObject();
			o.addProperty("widgetId", parent.getId());
			o.addProperty("slot", parent.getIndex());
			o.addProperty("itemId", parent.getItemId());
			o.addProperty("quantity", parent.getItemQuantity());
			ItemComposition comp = client.getItemDefinition(parent.getItemId());
			if (comp != null)
			{
				o.addProperty("name", comp.getName());
			}
			String[] ops = parent.getActions();
			if (ops != null && ops.length > 0)
			{
				JsonArray opsArr = new JsonArray();
				for (String op : ops)
				{
					if (op != null)
					{
						opsArr.add(Text.removeTags(op));
					}
				}
				o.add("ops", opsArr);
			}
			out.add(o);
		}
		Widget[][] kinds = {parent.getDynamicChildren(), parent.getStaticChildren(), parent.getNestedChildren()};
		for (Widget[] children : kinds)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget c : children)
			{
				collectItemWidgets(c, out);
			}
		}
	}

	// ---- economy interfaces ----

	private JsonObject listShop() throws Exception
	{
		return onClientThread(() ->
		{
			Widget items = client.getWidget(InterfaceID.Shopmain.ITEMS);
			if (items == null || items.isHidden())
			{
				throw new IllegalStateException("No shop is open (Shopmain.ITEMS not visible)");
			}
			JsonArray stock = new JsonArray();
			Widget[] slots = items.getDynamicChildren();
			if (slots != null)
			{
				for (Widget slot : slots)
				{
					if (slot == null || slot.getItemId() <= 0)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("slot", slot.getIndex());
					o.addProperty("itemId", slot.getItemId());
					o.addProperty("stockQuantity", slot.getItemQuantity());
					ItemComposition comp = client.getItemDefinition(slot.getItemId());
					if (comp != null)
					{
						o.addProperty("name", comp.getName());
						o.addProperty("price", comp.getPrice());
					}
					String[] ops = slot.getActions();
					if (ops != null)
					{
						JsonArray opsArr = new JsonArray();
						for (String op : ops)
						{
							if (op != null)
							{
								opsArr.add(Text.removeTags(op));
							}
						}
						o.add("ops", opsArr);
					}
					stock.add(o);
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", stock.size());
			result.add("items", stock);
			return result;
		});
	}

	private JsonObject getTrade() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			String stage;
			if (client.getWidget(InterfaceID.Tradeconfirm.FRAME) != null)
			{
				stage = "confirm";
			}
			else if (client.getWidget(InterfaceID.Trademain.FRAME) != null)
			{
				stage = "offer";
			}
			else
			{
				stage = "none";
			}
			result.addProperty("stage", stage);
			if ("none".equals(stage))
			{
				return result;
			}
			ItemContainer offer = client.getItemContainer(InventoryID.TRADEOFFER);
			result.add("myOffer", offer != null ? itemRows(offer) : new JsonArray());
			result.add("tradeScreenItems", itemWidgetsInGroup(
				"confirm".equals(stage)
					? InterfaceID.Tradeconfirm.FRAME >>> 16
					: InterfaceID.Trademain.FRAME >>> 16));
			return result;
		});
	}

	private JsonObject tradeAction(JsonObject args) throws Exception
	{
		if (args.has("action"))
		{
			String action = args.get("action").getAsString().toLowerCase();
			return onClientThread(() ->
			{
				Widget button;
				if (client.getWidget(InterfaceID.Tradeconfirm.FRAME) != null)
				{
					button = client.getWidget("accept".equals(action)
						? InterfaceID.Tradeconfirm.TRADE2ACCEPT : InterfaceID.Tradeconfirm.TRADE2DECLINE);
				}
				else if (client.getWidget(InterfaceID.Trademain.FRAME) != null)
				{
					button = client.getWidget("accept".equals(action)
						? InterfaceID.Trademain.ACCEPT : InterfaceID.Trademain.DECLINE);
				}
				else
				{
					throw new IllegalStateException("No trade screen is open");
				}
				if (button == null)
				{
					throw new IllegalStateException("Trade '" + action + "' button is not visible");
				}
				client.menuAction(button.getIndex(), button.getId(), MenuAction.CC_OP, 1,
					-1, action, "");
				JsonObject result = new JsonObject();
				result.addProperty("invoked", action);
				return result;
			});
		}
		if (!args.has("option"))
		{
			throw new IllegalArgumentException("Provide action (accept/decline) or option + itemId|itemName");
		}
		boolean offerSide = args.has("side") && "offer".equalsIgnoreCase(args.get("side").getAsString());
		if (offerSide)
		{
			return itemSlotAction(args, InterfaceID.Trademain.CONTAINER,
				"No trade screen is open", "offer");
		}
		return onClientThread(() ->
		{
			// The offer side is the inventory replica inside Tradeside; find the item there.
			String option = args.get("option").getAsString();
			Integer itemId = args.has("itemId") ? args.get("itemId").getAsInt() : null;
			String itemName = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase() : null;
			JsonObject slotInfo = findWidgetItemOp(InterfaceID.Tradeside.SIDE_LAYER >>> 16,
				option, itemId, itemName);
			if (slotInfo == null)
			{
				throw new IllegalArgumentException("No '" + option + "' op for item "
					+ (itemId != null ? itemId : itemName)
					+ " on the trade offer side - is a trade open?");
			}
			client.menuAction(slotInfo.get("slot").getAsInt(), slotInfo.get("containerId").getAsInt(),
				slotInfo.get("opIndex").getAsInt() < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY,
				slotInfo.get("opIndex").getAsInt() + 1, slotInfo.get("itemId").getAsInt(),
				slotInfo.get("op").getAsString(), "");
			slotInfo.addProperty("invoked", option);
			return slotInfo;
		});
	}

	/**
	 * Find a widget in a group carrying an item + an op matching `option`.
	 * Returns op coordinates for menuAction, or null. Must be on the client thread.
	 */
	private JsonObject findWidgetItemOp(int groupId, String option, Integer itemId, String itemName)
	{
		for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE; child++)
		{
			Widget w = client.getWidget(WidgetUtil.packComponentId(groupId, child));
			JsonObject found = findWidgetItemOp(w, option, itemId, itemName);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	private JsonObject findWidgetItemOp(Widget w, String option, Integer itemId, String itemName)
	{
		if (w == null)
		{
			return null;
		}
		if (w.getItemId() > 0 && (itemId == null || w.getItemId() == itemId))
		{
			boolean nameOk = itemName == null;
			if (!nameOk)
			{
				ItemComposition comp = client.getItemDefinition(w.getItemId());
				nameOk = comp != null && comp.getName() != null
					&& comp.getName().toLowerCase().contains(itemName);
			}
			if (nameOk)
			{
				String[] ops = w.getActions();
				for (int i = 0; ops != null && i < ops.length; i++)
				{
					if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase(option))
					{
						JsonObject o = new JsonObject();
						o.addProperty("slot", w.getIndex());
						o.addProperty("containerId", w.getId());
						o.addProperty("opIndex", i);
						o.addProperty("op", ops[i]);
						o.addProperty("itemId", w.getItemId());
						return o;
					}
				}
			}
		}
		Widget[][] kinds = {w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
		for (Widget[] children : kinds)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget c : children)
			{
				JsonObject found = findWidgetItemOp(c, option, itemId, itemName);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	// ---- session ----

	private JsonObject listWorlds() throws Exception
	{
		return onClientThread(() ->
		{
			World[] worlds = client.getWorldList();
			JsonArray arr = new JsonArray();
			if (worlds != null)
			{
				int current = client.getWorld();
				for (World w : worlds)
				{
					if (w == null)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("id", w.getId());
					o.addProperty("players", w.getPlayerCount());
					if (w.getActivity() != null)
					{
						o.addProperty("activity", w.getActivity());
					}
					o.addProperty("location", w.getLocation());
					o.addProperty("address", w.getAddress());
					JsonArray types = new JsonArray();
					if (w.getTypes() != null)
					{
						w.getTypes().forEach(t -> types.add(t.name()));
					}
					o.add("types", types);
					o.addProperty("current", w.getId() == current);
					arr.add(o);
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", arr.size());
			result.addProperty("currentWorld", client.getWorld());
			result.add("worlds", arr);
			return result;
		});
	}

	private JsonObject hopWorld(JsonObject args) throws Exception
	{
		int worldId = args.get("world").getAsInt();
		return onClientThread(() ->
		{
			World[] worlds = client.getWorldList();
			if (worlds == null || worlds.length == 0)
			{
				throw new IllegalStateException("The client has no world list to hop between");
			}
			World target = null;
			for (World w : worlds)
			{
				if (w != null && w.getId() == worldId)
				{
					target = w;
					break;
				}
			}
			if (target == null)
			{
				throw new IllegalArgumentException("World " + worldId + " is not in the world list");
			}
			client.hopToWorld(target);
			JsonObject result = new JsonObject();
			result.addProperty("hopping", true);
			result.addProperty("world", worldId);
			return result;
		});
	}

	private JsonObject logout() throws Exception
	{
		return onClientThread(() ->
		{
			// Any widget in the Logout group carrying a "Logout" op is the button.
			JsonObject found = null;
			for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE && found == null; child++)
			{
				Widget w = client.getWidget(WidgetUtil.packComponentId(
					InterfaceID.Logout.LOGOUT >>> 16, child));
				found = findOpOnWidget(w, "logout");
			}
			if (found == null)
			{
				throw new IllegalStateException("The logout interface is not loaded - open the logout tab first");
			}
			client.menuAction(found.get("slot").getAsInt(), found.get("widgetId").getAsInt(),
				MenuAction.CC_OP, found.get("opIndex").getAsInt() + 1, -1, "Logout", "");
			JsonObject result = new JsonObject();
			result.addProperty("invoked", "Logout");
			result.addProperty("widgetId", found.get("widgetId").getAsInt());
			return result;
		});
	}

	/** Depth-first search for a widget exposing an op containing `option`. Client thread only. */
	private JsonObject findOpOnWidget(Widget w, String option)
	{
		if (w == null || w.isHidden())
		{
			return null;
		}
		String[] ops = w.getActions();
		if (ops != null)
		{
			for (int i = 0; i < ops.length; i++)
			{
				if (ops[i] != null && Text.removeTags(ops[i]).toLowerCase().contains(option))
				{
					JsonObject o = new JsonObject();
					o.addProperty("widgetId", w.getId());
					o.addProperty("slot", w.getIndex());
					o.addProperty("opIndex", i);
					return o;
				}
			}
		}
		Widget[][] kinds = {w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
		for (Widget[] children : kinds)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget c : children)
			{
				JsonObject found = findOpOnWidget(c, option);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private JsonObject listQuests(JsonObject args) throws Exception
	{
		String nameFilter = args.has("nameFilter")
			? args.get("nameFilter").getAsString().toLowerCase() : null;
		String stateFilter = args.has("state")
			? args.get("state").getAsString().toUpperCase() : null;
		return onClientThread(() ->
		{
			JsonArray quests = new JsonArray();
			for (Quest quest : Quest.values())
			{
				String state = quest.getState(client).name();
				if (stateFilter != null && !state.equals(stateFilter))
				{
					continue;
				}
				String name = quest.getName();
				if (nameFilter != null && (name == null || !name.toLowerCase().contains(nameFilter)))
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("id", quest.getId());
				o.addProperty("name", name);
				o.addProperty("state", state);
				quests.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", quests.size());
			result.add("quests", quests);
			return result;
		});
	}

	// ---- definition inspectors ----

	private JsonObject itemDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			ItemComposition comp = client.getItemDefinition(id);
			if (comp == null)
			{
				throw new IllegalArgumentException("No item definition for id " + id);
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", comp.getName());
			o.addProperty("note", comp.getNote());
			o.addProperty("linkedNoteId", comp.getLinkedNoteId());
			o.addProperty("placeholderId", comp.getPlaceholderId());
			o.addProperty("placeholderTemplateId", comp.getPlaceholderTemplateId());
			o.addProperty("price", comp.getPrice());
			o.addProperty("members", comp.isMembers());
			o.addProperty("stackable", comp.isStackable());
			o.addProperty("tradeable", comp.isTradeable());
			JsonArray ops = new JsonArray();
			String[] inv = comp.getInventoryActions();
			if (inv != null)
			{
				for (String op : inv)
				{
					if (op != null)
					{
						ops.add(op);
					}
				}
			}
			o.add("inventoryActions", ops);
			if (args.has("param"))
			{
				int key = args.get("param").getAsInt();
				JsonObject param = new JsonObject();
				param.addProperty("key", key);
				param.addProperty("intValue", comp.getIntValue(key));
				if (comp.getStringValue(key) != null)
				{
					param.addProperty("stringValue", comp.getStringValue(key));
				}
				long longVal = comp.getLongValue(key);
				if (longVal != 0)
				{
					param.addProperty("longValue", longVal);
				}
				o.add("param", param);
			}
			return o;
		});
	}

	private JsonObject npcDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			NPCComposition comp = client.getNpcDefinition(id);
			if (comp == null)
			{
				throw new IllegalArgumentException("No NPC definition for id " + id);
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", comp.getName());
			o.addProperty("combatLevel", comp.getCombatLevel());
			o.addProperty("size", comp.getSize());
			o.addProperty("interactible", comp.isInteractible());
			JsonArray ops = new JsonArray();
			String[] actions = comp.getActions();
			if (actions != null)
			{
				for (String action : actions)
				{
					if (action != null)
					{
						ops.add(action);
					}
				}
			}
			o.add("actions", ops);
			JsonArray transforms = new JsonArray();
			int[] configs = comp.getConfigs();
			if (configs != null)
			{
				for (int c : configs)
				{
					transforms.add(c);
				}
			}
			o.add("transformIds", transforms);
			return o;
		});
	}

	private JsonObject objectDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			if (comp == null)
			{
				throw new IllegalArgumentException("No object definition for id " + id);
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", comp.getName());
			o.addProperty("sizeX", comp.getSizeX());
			o.addProperty("sizeY", comp.getSizeY());
			o.addProperty("mapSceneId", comp.getMapSceneId());
			o.addProperty("varbitId", comp.getVarbitId());
			JsonArray ops = new JsonArray();
			String[] actions = comp.getActions();
			if (actions != null)
			{
				for (String action : actions)
				{
					if (action != null)
					{
						ops.add(action);
					}
				}
			}
			o.add("actions", ops);
			JsonArray impostors = new JsonArray();
			int[] impostorIds = comp.getImpostorIds();
			if (impostorIds != null)
			{
				for (int i : impostorIds)
				{
					impostors.add(i);
				}
			}
			o.add("impostorIds", impostors);
			return o;
		});
	}

	// ---- movement / collision ----

	private JsonObject findPath(JsonObject args) throws Exception
	{
		int targetX = args.get("x").getAsInt();
		int targetY = args.get("y").getAsInt();
		int maxPath = optInt(args, "maxPath", 200);
		return onClientThread(() ->
		{
			int plane = args.has("plane") ? args.get("plane").getAsInt() : client.getPlane();
			WorldPoint from;
			if (args.has("fromX") && args.has("fromY"))
			{
				from = new WorldPoint(args.get("fromX").getAsInt(), args.get("fromY").getAsInt(), plane);
			}
			else
			{
				Player local = client.getLocalPlayer();
				from = local != null ? local.getWorldLocation() : null;
				if (from == null)
				{
					throw new IllegalStateException("Local player is not available");
				}
			}
			CollisionData[] maps = client.getCollisionMaps();
			if (maps == null || maps[plane] == null)
			{
				throw new IllegalStateException("No collision map loaded for plane " + plane);
			}
			int[][] flags = maps[plane].getFlags();
			int baseX = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getBaseX() : -1;
			int baseY = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getBaseY() : -1;
			if (baseX < 0)
			{
				throw new IllegalStateException("Scene base coordinates unavailable");
			}
			int sx = from.getX() - baseX, sy = from.getY() - baseY;
			int tx = targetX - baseX, ty = targetY - baseY;
			int w = flags.length, h = flags[0].length;
			if (tx < 0 || ty < 0 || tx >= w || ty >= h)
			{
				throw new IllegalArgumentException("Target " + targetX + "," + targetY
					+ " is outside the loaded scene");
			}
			if (sx < 0 || sy < 0 || sx >= w || sy >= h)
			{
				throw new IllegalArgumentException("Start tile is outside the loaded scene");
			}
			int tileBlock = CollisionDataFlag.BLOCK_MOVEMENT_FULL
				| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
			boolean[][] seen = new boolean[w][h];
			int[][] prev = new int[w * h][2];
			ArrayDeque<int[]> queue = new ArrayDeque<>();
			seen[sx][sy] = true;
			queue.add(new int[]{sx, sy});
			int count = 0;
			boolean reached = false;
			int[][] dirs = {
				{0, 1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH},
				{1, 0, CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_WEST},
				{0, -1, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_NORTH},
				{-1, 0, CollisionDataFlag.BLOCK_MOVEMENT_WEST, CollisionDataFlag.BLOCK_MOVEMENT_EAST}};
			while (!queue.isEmpty() && !reached)
			{
				int[] cur = queue.poll();
				count++;
				for (int[] d : dirs)
				{
					int nx = cur[0] + d[0], ny = cur[1] + d[1];
					if (nx < 0 || ny < 0 || nx >= w || ny >= h || seen[nx][ny])
					{
						continue;
					}
					if ((flags[cur[0]][cur[1]] & d[2]) != 0 || (flags[nx][ny] & d[3]) != 0
						|| (flags[nx][ny] & tileBlock) != 0)
					{
						continue;
					}
					seen[nx][ny] = true;
					prev[nx * w + ny] = cur;
					if (nx == tx && ny == ty)
					{
						reached = true;
						break;
					}
					queue.add(new int[]{nx, ny});
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("reachable", reached);
			if (reached)
			{
				List<int[]> reversed = new ArrayList<>();
				int[] cur = {tx, ty};
				while (cur != null)
				{
					reversed.add(cur);
					cur = prev[cur[0] * w + cur[1]];
				}
				Collections.reverse(reversed);
				result.addProperty("length", reversed.size() - 1);
				JsonArray path = new JsonArray();
				for (int i = 0; i < reversed.size() && i < maxPath; i++)
				{
					JsonObject step = new JsonObject();
					step.addProperty("x", reversed.get(i)[0] + baseX);
					step.addProperty("y", reversed.get(i)[1] + baseY);
					path.add(step);
				}
				result.addProperty("truncated", reversed.size() > maxPath);
				result.add("path", path);
			}
			result.addProperty("tilesVisited", count);
			return result;
		});
	}

	private JsonObject getCollisionArea(JsonObject args) throws Exception
	{
		int ox = args.get("x").getAsInt();
		int oy = args.get("y").getAsInt();
		int width = Math.min(optInt(args, "width", 8), 32);
		int height = Math.min(optInt(args, "height", 8), 32);
		return onClientThread(() ->
		{
			int plane = args.has("plane") ? args.get("plane").getAsInt() : client.getPlane();
			CollisionData[] maps = client.getCollisionMaps();
			if (maps == null || maps[plane] == null)
			{
				throw new IllegalStateException("No collision map loaded for plane " + plane);
			}
			int[][] flags = maps[plane].getFlags();
			int tileBlock = CollisionDataFlag.BLOCK_MOVEMENT_FULL
				| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
				| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
			JsonArray rows = new JsonArray();
			JsonArray walkRows = new JsonArray();
			for (int wy = oy + height - 1; wy >= oy; wy--)
			{
				JsonArray flagRow = new JsonArray();
				StringBuilder walkRow = new StringBuilder();
				for (int wx = ox; wx < ox + width; wx++)
				{
					LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(wx, wy, plane));
					if (lp == null)
					{
						flagRow.add(JsonNull.INSTANCE);
						walkRow.append(' ');
						continue;
					}
					int f = flags[lp.getSceneX()][lp.getSceneY()];
					flagRow.add(f);
					walkRow.append((f & tileBlock) != 0 ? '#' : (f != 0 ? '+' : '.'));
				}
				rows.add(flagRow);
				walkRows.add(walkRow.toString());
			}
			JsonObject result = new JsonObject();
			result.addProperty("x", ox);
			result.addProperty("y", oy);
			result.addProperty("width", width);
			result.addProperty("height", height);
			result.addProperty("plane", plane);
			result.add("flags", rows);
			result.add("walkableMap", walkRows);
			result.addProperty("legend",
				"flags: raw int per tile, top row = north. walkableMap: '.' clear, '+' wall flags, '#' blocked tile");
			return result;
		});
	}

	private JsonObject setCamera(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			if (args.has("yaw"))
			{
				client.setCameraYawTarget(args.get("yaw").getAsInt());
			}
			if (args.has("pitch"))
			{
				client.setCameraPitchTarget(args.get("pitch").getAsInt());
			}
			JsonObject result = new JsonObject();
			result.addProperty("yaw", client.getCameraYawTarget());
			result.addProperty("pitch", client.getCameraPitchTarget());
			return result;
		});
	}

	// ---- item-on-target ----

	private JsonObject useItem(JsonObject args) throws Exception
	{
		Integer itemId = args.has("itemId") ? args.get("itemId").getAsInt() : null;
		String itemName = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase() : null;
		if (itemId == null && itemName == null)
		{
			throw new IllegalArgumentException("Provide itemId or itemName for the item to use");
		}
		boolean hasTarget = args.has("npcIndex") || args.has("npcName") || args.has("objectId")
			|| args.has("playerName") || args.has("targetItemId") || args.has("targetItemName");
		if (!hasTarget)
		{
			throw new IllegalArgumentException(
				"Provide a target: npcIndex|npcName, objectId+x+y, playerName or targetItemId|targetItemName");
		}
		// Step 1: invoke "Use" on the item slot - the same CC_OP a right-click sends.
		JsonObject used = onClientThread(() ->
		{
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null)
			{
				throw new IllegalStateException("Inventory widget is not loaded");
			}
			Widget slot = findItemSlot(inventory, itemId, itemName);
			if (slot == null)
			{
				throw new IllegalArgumentException("Item not found in inventory: "
					+ (itemId != null ? "id " + itemId : itemName));
			}
			String[] ops = slot.getActions();
			for (int i = 0; ops != null && i < ops.length; i++)
			{
				if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase("Use"))
				{
					client.menuAction(slot.getIndex(), InterfaceID.Inventory.ITEMS,
						i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY, i + 1,
						slot.getItemId(), ops[i], slot.getName());
					JsonObject o = new JsonObject();
					o.addProperty("itemId", slot.getItemId());
					o.addProperty("slot", slot.getIndex());
					return o;
				}
			}
			throw new IllegalArgumentException("Item has no 'Use' op. Available: " + availableActions(ops));
		});
		pauseMs(100); // let the clientscript put the client in item-target mode
		// Step 2: hover the target so the menu builds the "Use item -> target" entry.
		Point hover = onClientThread(() -> targetHoverPoint(args));
		if (hover == null)
		{
			throw new IllegalStateException("Could not get a hover point for the target");
		}
		String targetNeedle = onClientThread(() -> useItemTargetName(args));
		JsonElement invoked = hoverAndInvoke(hover, "use", targetNeedle);
		JsonObject result = invoked.getAsJsonObject();
		result.addProperty("usedItemId", used.get("itemId").getAsInt());
		return result;
	}

	/** Hover point for a use_item target. Must be on the client thread. */
	private Point targetHoverPoint(JsonObject args)
	{
		if (args.has("npcIndex") || args.has("npcName"))
		{
			NPC npc = findNpc(args);
			return npc != null ? actorHoverPoint(npc) : null;
		}
		if (args.has("objectId") && args.has("x") && args.has("y"))
		{
			int objectId = args.get("objectId").getAsInt();
			int x = args.get("x").getAsInt();
			int y = args.get("y").getAsInt();
			int plane = args.has("plane") ? args.get("plane").getAsInt() : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				return null;
			}
			Scene scene = client.getScene();
			Tile tile = scene != null ? scene.getTiles()[plane][lp.getSceneX()][lp.getSceneY()] : null;
			TileObject obj = tile == null ? null : tileObjects(tile, new HashSet<>()).stream()
				.filter(o -> o.getId() == objectId).findFirst().orElse(null);
			return obj != null ? objectHoverPoint(obj) : null;
		}
		if (args.has("playerName"))
		{
			String needle = args.get("playerName").getAsString().toLowerCase();
			Player local = client.getLocalPlayer();
			for (Player p : client.getPlayers())
			{
				if (p != null && p != local && p.getName() != null
					&& p.getName().toLowerCase().contains(needle))
				{
					return actorHoverPoint(p);
				}
			}
			return null;
		}
		if (args.has("targetItemId") || args.has("targetItemName"))
		{
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null || inventory.isHidden())
			{
				return null;
			}
			Integer targetId = args.has("targetItemId") ? args.get("targetItemId").getAsInt() : null;
			String targetName = args.has("targetItemName")
				? args.get("targetItemName").getAsString().toLowerCase() : null;
			Widget slot = findItemSlot(inventory, targetId, targetName);
			if (slot == null)
			{
				return null;
			}
			Rectangle b = slot.getBounds();
			return b != null ? new Point(b.x + b.width / 2, b.y + b.height / 2) : null;
		}
		return null;
	}

	/** Lower-case name to match inside the "Use item -> target" menu target. Client thread only. */
	private String useItemTargetName(JsonObject args)
	{
		if (args.has("npcIndex") || args.has("npcName"))
		{
			NPC npc = findNpc(args);
			return npc != null && npc.getName() != null ? npc.getName().toLowerCase() : null;
		}
		if (args.has("objectId"))
		{
			ObjectComposition comp = client.getObjectDefinition(args.get("objectId").getAsInt());
			return comp != null && comp.getName() != null ? comp.getName().toLowerCase() : null;
		}
		if (args.has("playerName"))
		{
			return args.get("playerName").getAsString().toLowerCase();
		}
		if (args.has("targetItemName"))
		{
			return args.get("targetItemName").getAsString().toLowerCase();
		}
		if (args.has("targetItemId"))
		{
			ItemComposition comp = client.getItemDefinition(args.get("targetItemId").getAsInt());
			return comp != null && comp.getName() != null ? comp.getName().toLowerCase() : null;
		}
		return null;
	}

	// ---- screenshot diffing ----

	private static final int MAX_BASELINES = 8;
	private static final int MAX_BASELINE_PIXELS = 1_048_576;

	private static final class ScreenBaseline
	{
		final int[] rgb;
		final int width;
		final int height;

		ScreenBaseline(int[] rgb, int width, int height)
		{
			this.rgb = rgb;
			this.width = width;
			this.height = height;
		}
	}

	/** Stored screenshot baselines by label, oldest evicted past MAX_BASELINES. */
	private final Map<String, ScreenBaseline> screenBaselines =
		new LinkedHashMap<String, ScreenBaseline>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, ScreenBaseline> eldest)
			{
				return size() > MAX_BASELINES;
			}
		};

	private JsonObject compareScreenshot(JsonObject args) throws Exception
	{
		String label = args.has("label") ? args.get("label").getAsString() : "default";
		boolean rebaseline = args.has("rebaseline") && args.get("rebaseline").getAsBoolean();
		double threshold = args.has("thresholdPct") ? args.get("thresholdPct").getAsDouble() : 0;
		Rectangle crop = resolveCrop(args);
		BufferedImage frame = captureFrame();
		if (crop != null)
		{
			Rectangle clamped = crop.intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
			if (clamped.isEmpty())
			{
				throw new IllegalArgumentException("Crop region " + crop + " is outside the canvas ("
					+ frame.getWidth() + "x" + frame.getHeight() + ")");
			}
			frame = frame.getSubimage(clamped.x, clamped.y, clamped.width, clamped.height);
			crop = clamped;
		}
		int width = frame.getWidth();
		int height = frame.getHeight();
		if (width * height > MAX_BASELINE_PIXELS)
		{
			throw new IllegalArgumentException("Region " + width + "x" + height
				+ " exceeds the " + MAX_BASELINE_PIXELS + "-pixel baseline cap - crop smaller");
		}
		int[] rgb = frame.getRGB(0, 0, width, height, null, 0, width);
		JsonObject result = new JsonObject();
		result.addProperty("label", label);
		result.addProperty("width", width);
		result.addProperty("height", height);
		if (crop != null)
		{
			result.addProperty("cropX", crop.x);
			result.addProperty("cropY", crop.y);
		}
		ScreenBaseline baseline = screenBaselines.get(label);
		if (rebaseline || baseline == null)
		{
			screenBaselines.put(label, new ScreenBaseline(rgb, width, height));
			result.addProperty("stored", true);
			result.addProperty("rebaselined", rebaseline && baseline != null);
			return result;
		}
		if (baseline.width != width || baseline.height != height)
		{
			result.addProperty("match", false);
			result.addProperty("reason", "region size changed: baseline was "
				+ baseline.width + "x" + baseline.height);
			result.addProperty("changedPct", 100);
			return result;
		}
		int changed = 0;
		int minX = width, minY = height, maxX = -1, maxY = -1;
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				if (rgb[y * width + x] != baseline.rgb[y * width + x])
				{
					changed++;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}
		double pct = changed * 100.0 / (width * height);
		result.addProperty("match", pct <= threshold);
		result.addProperty("changedPixels", changed);
		result.addProperty("totalPixels", width * height);
		result.addProperty("changedPct", Math.round(pct * 1000.0) / 1000.0);
		if (changed > 0)
		{
			JsonObject bbox = new JsonObject();
			bbox.addProperty("x", minX + (crop != null ? crop.x : 0));
			bbox.addProperty("y", minY + (crop != null ? crop.y : 0));
			bbox.addProperty("width", maxX - minX + 1);
			bbox.addProperty("height", maxY - minY + 1);
			result.add("diffBounds", bbox);
		}
		return result;
	}

	/** Crop region shared by screenshot and compare_screenshot, or null for full canvas. */
	private Rectangle resolveCrop(JsonObject args) throws Exception
	{
		if (args.has("componentId"))
		{
			int padding = optInt(args, "padding", 0);
			Rectangle bounds = onClientThread(() ->
			{
				Widget w = resolveWidget(args, "componentId", "childIndex");
				return w == null || w.isHidden() ? null : w.getBounds();
			});
			if (bounds == null)
			{
				throw new IllegalArgumentException("Widget not found or hidden: " + describeId(args, "componentId", "childIndex"));
			}
			return pad(bounds, padding);
		}
		if (args.has("interfaceId"))
		{
			int padding = optInt(args, "padding", 0);
			Rectangle bounds = onClientThread(() -> interfaceBounds(args.get("interfaceId").getAsInt()));
			if (bounds == null)
			{
				throw new IllegalArgumentException("Interface " + args.get("interfaceId").getAsInt()
					+ " is not loaded or has no visible components");
			}
			return pad(bounds, padding);
		}
		if (args.has("x") && args.has("y") && args.has("width") && args.has("height"))
		{
			return new Rectangle(args.get("x").getAsInt(), args.get("y").getAsInt(),
				args.get("width").getAsInt(), args.get("height").getAsInt());
		}
		return null;
	}

	private JsonObject widgetOp(JsonObject args) throws Exception
	{
		int componentId = args.get("componentId").getAsInt();
		Integer childIndex = args.has("childIndex") ? args.get("childIndex").getAsInt() : null;
		String option = args.has("option") ? args.get("option").getAsString() : null;
		Integer opIndex = args.has("opIndex") ? args.get("opIndex").getAsInt() : null;
		if (option == null && opIndex == null)
		{
			throw new IllegalArgumentException("widget_op requires option or opIndex");
		}
		return onClientThread(() ->
		{
			Widget w = client.getWidget(componentId);
			if (w != null && childIndex != null)
			{
				Widget[] children = w.getDynamicChildren();
				w = children != null && childIndex >= 0 && childIndex < children.length
					? children[childIndex] : null;
			}
			if (w == null)
			{
				throw new IllegalArgumentException("Widget not found: " + componentId
					+ (childIndex != null ? " child " + childIndex : ""));
			}
			String[] actions = w.getActions();
			int idx;
			if (opIndex != null)
			{
				idx = opIndex;
				if (actions == null || idx < 0 || idx >= actions.length || actions[idx] == null)
				{
					throw new IllegalArgumentException("Widget has no action at opIndex " + idx);
				}
			}
			else
			{
				idx = -1;
				if (actions != null)
				{
					String lower = option.toLowerCase();
					for (int i = 0; i < actions.length; i++)
					{
						if (actions[i] != null && Text.removeTags(actions[i]).toLowerCase().contains(lower))
						{
							idx = i;
							break;
						}
					}
				}
				if (idx < 0)
				{
					throw new IllegalArgumentException("Widget has no action matching '" + option + "'");
				}
			}
			client.menuAction(w.getIndex(), componentId,
				idx < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY,
				idx + 1, -1, actions[idx], w.getName());
			JsonObject result = new JsonObject();
			result.addProperty("invoked", Text.removeTags(actions[idx]));
			result.addProperty("opIndex", idx);
			result.addProperty("componentId", componentId);
			if (childIndex != null)
			{
				result.addProperty("childIndex", childIndex);
			}
			return result;
		});
	}

	private JsonObject getActor(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			Actor actor;
			if (args.has("playerName"))
			{
				String name = args.get("playerName").getAsString().toLowerCase();
				actor = null;
				for (Player p : client.getPlayers())
				{
					if (p != null && p.getName() != null
						&& p.getName().toLowerCase().contains(name))
					{
						actor = p;
						if (p.getName().equalsIgnoreCase(name))
						{
							break;
						}
					}
				}
				if (actor == null)
				{
					throw new IllegalArgumentException("No player matching '" + name + "'");
				}
			}
			else
			{
				actor = findNpc(args);
				if (actor == null)
				{
					throw new IllegalArgumentException("NPC not found");
				}
			}
			JsonObject o = describeActor(actor, null, Integer.MAX_VALUE);
			o.addProperty("type", actor instanceof NPC ? "npc" : "player");
			if (actor instanceof NPC)
			{
				NPC npc = (NPC) actor;
				o.addProperty("index", npc.getIndex());
				o.addProperty("npcId", npc.getId());
				NPCComposition comp = npc.getTransformedComposition();
				if (comp != null)
				{
					o.addProperty("size", comp.getSize());
					o.addProperty("transformation", comp.getId() != npc.getId());
				}
			}
			else
			{
				Player p = (Player) actor;
				o.addProperty("team", p.getTeam());
				o.addProperty("local", p == client.getLocalPlayer());
			}
			if (actor.getOverheadText() != null)
			{
				o.addProperty("overheadText", Text.removeTags(actor.getOverheadText()));
			}
			return o;
		});
	}

	private JsonObject getTile(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		return onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + "," + plane + " is not in the loaded scene");
			}
			Tile tile = client.getScene().getTiles()[plane][lp.getSceneX()][lp.getSceneY()];
			JsonObject result = new JsonObject();
			result.addProperty("x", x);
			result.addProperty("y", y);
			result.addProperty("plane", plane);
			int[][] flags = client.getCollisionMaps()[plane].getFlags();
			int flag = flags[lp.getSceneX()][lp.getSceneY()];
			result.addProperty("collisionFlags", flag);
			result.addProperty("walkable", (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0);
			int[][][] heights = client.getTileHeights();
			if (heights != null && plane < heights.length)
			{
				int[][] planeHeights = heights[plane];
				if (planeHeights != null && lp.getSceneX() < planeHeights.length
					&& planeHeights[lp.getSceneX()] != null
					&& lp.getSceneY() < planeHeights[lp.getSceneX()].length)
				{
					result.addProperty("tileHeight", planeHeights[lp.getSceneX()][lp.getSceneY()]);
				}
			}
			if (tile == null)
			{
				return result;
			}
			JsonArray objects = new JsonArray();
			if (tile.getGameObjects() != null)
			{
				for (GameObject go : tile.getGameObjects())
				{
					if (go != null)
					{
						objects.add(describeTileObject("game", go, null, null, null, Integer.MAX_VALUE));
					}
				}
			}
			if (tile.getWallObject() != null)
			{
				objects.add(describeTileObject("wall", tile.getWallObject(), null, null, null, Integer.MAX_VALUE));
			}
			if (tile.getDecorativeObject() != null)
			{
				objects.add(describeTileObject("decorative", tile.getDecorativeObject(), null, null, null, Integer.MAX_VALUE));
			}
			if (tile.getGroundObject() != null)
			{
				objects.add(describeTileObject("ground", tile.getGroundObject(), null, null, null, Integer.MAX_VALUE));
			}
			if (objects.size() > 0)
			{
				result.add("objects", objects);
			}
			List<TileItem> items = tile.getGroundItems();
			if (items != null && !items.isEmpty())
			{
				JsonArray arr = new JsonArray();
				for (TileItem item : items)
				{
					JsonObject o = new JsonObject();
					o.addProperty("itemId", item.getId());
					o.addProperty("quantity", item.getQuantity());
					ItemComposition comp = client.getItemDefinition(item.getId());
					if (comp != null)
					{
						o.addProperty("name", comp.getName());
					}
					arr.add(o);
				}
				result.add("groundItems", arr);
			}
			return result;
		});
	}

	private JsonObject setVar(JsonObject args) throws Exception
	{
		String type = args.get("type").getAsString();
		int id = args.get("id").getAsInt();
		String raw = args.get("value").getAsString();
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("type", type);
			result.addProperty("id", id);
			switch (type)
			{
				case "varp":
				{
					int v = Integer.parseInt(raw);
					result.addProperty("old", client.getVarpValue(id));
					client.setVarpValue(id, v);
					client.queueChangedVarp(id);
					result.addProperty("new", v);
					break;
				}
				case "varbit":
				{
					int v = Integer.parseInt(raw);
					VarbitComposition comp = client.getVarbit(id);
					if (comp == null)
					{
						throw new IllegalArgumentException("Unknown varbit " + id);
					}
					result.addProperty("old", client.getVarbitValue(id));
					client.setVarbitValue(client.getVarps(), id, v);
					client.queueChangedVarp(comp.getIndex());
					result.addProperty("new", v);
					result.addProperty("varpIndex", comp.getIndex());
					break;
				}
				case "varcint":
				{
					int v = Integer.parseInt(raw);
					result.addProperty("old", client.getVarcIntValue(id));
					client.setVarcIntValue(id, v);
					result.addProperty("new", v);
					break;
				}
				case "varcstr":
				{
					result.addProperty("old", client.getVarcStrValue(id));
					client.setVarcStrValue(id, raw);
					result.addProperty("new", raw);
					break;
				}
				default:
					throw new IllegalArgumentException("type must be varbit, varp, varcint or varcstr");
			}
			result.addProperty("note", "Client-side only - the server overwrites it on the next var sync");
			return result;
		});
	}

	private JsonObject toggleRun() throws Exception
	{
		Point p = onClientThread(() ->
		{
			Widget button = client.getWidget(InterfaceID.Orbs.RUNBUTTON);
			if (button == null || button.isHidden())
			{
				throw new IllegalStateException("Run orb is not visible");
			}
			Rectangle b = button.getBounds();
			if (b == null || b.width <= 0)
			{
				throw new IllegalStateException("Run orb has no bounds");
			}
			return new Point(b.x + b.width / 2, b.y + b.height / 2);
		});
		clickAt(p.x, p.y, MouseEvent.BUTTON1);
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("runEnergy", client.getEnergy());
			result.addProperty("running", client.getVarpValue(VarPlayerID.OPTION_RUN) != 0);
			return result;
		});
	}

	private int markerColor(JsonObject args)
	{
		if (!args.has("color"))
		{
			return McpMarkerOverlay.DEFAULT_COLOR;
		}
		String raw = args.get("color").getAsString().trim();
		if (raw.startsWith("#"))
		{
			raw = raw.substring(1);
		}
		return (int) Long.parseLong(raw, 16);
	}

	private JsonObject markTile(JsonObject args) throws Exception
	{
		if (markerOverlay == null)
		{
			throw new IllegalStateException("Marker overlay is not available");
		}
		String name = args.has("name") ? args.get("name").getAsString() : "mark";
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		int plane = args.has("plane") ? args.get("plane").getAsInt()
			: onClientThread(() -> client.getPlane());
		String label = args.has("label") ? args.get("label").getAsString() : null;
		int color = markerColor(args);
		int ticks = optInt(args, "ticks", 120);
		markerOverlay.markTile(name, x, y, plane, label, color, ticks);
		JsonObject result = new JsonObject();
		result.addProperty("marked", name);
		result.addProperty("tile", x + "," + y + "," + plane);
		return textResult(result);
	}

	private JsonObject markWidget(JsonObject args)
	{
		if (markerOverlay == null)
		{
			throw new IllegalStateException("Marker overlay is not available");
		}
		String name = args.has("name") ? args.get("name").getAsString() : "mark";
		int componentId = args.get("componentId").getAsInt();
		Integer childIndex = args.has("childIndex") ? args.get("childIndex").getAsInt() : null;
		String label = args.has("label") ? args.get("label").getAsString() : null;
		int color = markerColor(args);
		int ticks = optInt(args, "ticks", 120);
		markerOverlay.markWidget(name, componentId, childIndex, label, color, ticks);
		JsonObject result = new JsonObject();
		result.addProperty("marked", name);
		result.addProperty("componentId", componentId);
		return textResult(result);
	}

	private JsonObject clearMarks(JsonObject args)
	{
		if (markerOverlay == null)
		{
			throw new IllegalStateException("Marker overlay is not available");
		}
		String name = args.has("name") ? args.get("name").getAsString() : null;
		int cleared = markerOverlay.clear(name);
		JsonObject result = new JsonObject();
		result.addProperty("cleared", cleared);
		return textResult(result);
	}

	private JsonObject getHintArrow() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("hasHintArrow", client.hasHintArrow());
			if (!client.hasHintArrow())
			{
				return result;
			}
			result.addProperty("type", client.getHintArrowType());
			WorldPoint p = client.getHintArrowPoint();
			if (p != null)
			{
				result.addProperty("worldX", p.getX());
				result.addProperty("worldY", p.getY());
				result.addProperty("plane", p.getPlane());
			}
			NPC npc = client.getHintArrowNpc();
			if (npc != null)
			{
				result.addProperty("npcIndex", npc.getIndex());
				result.addProperty("npcId", npc.getId());
				result.addProperty("npcName", npc.getName());
			}
			Player player = client.getHintArrowPlayer();
			if (player != null)
			{
				result.addProperty("playerName", player.getName());
			}
			return result;
		});
	}

	private JsonObject setHintArrow(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			if (args.has("npcIndex"))
			{
				int index = args.get("npcIndex").getAsInt();
				for (NPC npc : client.getNpcs())
				{
					if (npc != null && npc.getIndex() == index)
					{
						client.setHintArrow(npc);
						JsonObject o = new JsonObject();
						o.addProperty("arrow", "npc");
						o.addProperty("npcIndex", index);
						return o;
					}
				}
				throw new IllegalArgumentException("No NPC with index " + index);
			}
			if (args.has("playerName"))
			{
				String name = args.get("playerName").getAsString().toLowerCase();
				for (Player p : client.getPlayers())
				{
					if (p != null && p.getName() != null
						&& p.getName().toLowerCase().contains(name))
					{
						client.setHintArrow(p);
						JsonObject o = new JsonObject();
						o.addProperty("arrow", "player");
						o.addProperty("playerName", p.getName());
						return o;
					}
				}
				throw new IllegalArgumentException("No player matching '" + name + "'");
			}
			if (args.has("x") && args.has("y"))
			{
				WorldPoint wp = new WorldPoint(args.get("x").getAsInt(), args.get("y").getAsInt(),
					args.has("plane") ? args.get("plane").getAsInt() : client.getPlane());
				client.setHintArrow(wp);
				JsonObject o = new JsonObject();
				o.addProperty("arrow", "tile");
				o.addProperty("worldX", wp.getX());
				o.addProperty("worldY", wp.getY());
				o.addProperty("plane", wp.getPlane());
				return o;
			}
			throw new IllegalArgumentException("Provide x+y, npcIndex or playerName");
		});
	}

	private JsonObject clearHintArrow() throws Exception
	{
		return onClientThread(() ->
		{
			client.clearHintArrow();
			JsonObject result = new JsonObject();
			result.addProperty("cleared", true);
			return result;
		});
	}

	private JsonObject examine(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			if (args.has("npcIndex") || args.has("npcName"))
			{
				NPC npc = findNpc(args);
				if (npc == null)
				{
					throw new IllegalArgumentException("NPC not found");
				}
				client.menuAction(0, 0, MenuAction.EXAMINE_NPC, npc.getIndex(), -1,
					"Examine", npc.getName() != null ? npc.getName() : "");
				result.addProperty("examined", "npc");
				result.addProperty("npcIndex", npc.getIndex());
				result.addProperty("npcId", npc.getId());
				result.addProperty("note", "Examine text arrives as a chat message - wait_for chat_message or get_chat_history");
				return result;
			}
			if (args.has("objectId") && args.has("x") && args.has("y"))
			{
				int objectId = args.get("objectId").getAsInt();
				int x = args.get("x").getAsInt();
				int y = args.get("y").getAsInt();
				LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, client.getPlane()));
				if (lp == null)
				{
					throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
				}
				client.menuAction(lp.getSceneX(), lp.getSceneY(), MenuAction.EXAMINE_OBJECT,
					objectId, -1, "Examine", "");
				result.addProperty("examined", "object");
				result.addProperty("objectId", objectId);
				result.addProperty("tile", x + "," + y);
				return result;
			}
			if (args.has("itemId") && args.has("x") && args.has("y"))
			{
				int itemId = args.get("itemId").getAsInt();
				int x = args.get("x").getAsInt();
				int y = args.get("y").getAsInt();
				LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, client.getPlane()));
				if (lp == null)
				{
					throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
				}
				client.menuAction(lp.getSceneX(), lp.getSceneY(), MenuAction.EXAMINE_ITEM_GROUND,
					itemId, -1, "Examine", "");
				result.addProperty("examined", "groundItem");
				result.addProperty("itemId", itemId);
				result.addProperty("tile", x + "," + y);
				return result;
			}
			if (args.has("itemId"))
			{
				int itemId = args.get("itemId").getAsInt();
				ItemContainer inv = client.getItemContainer(InventoryID.INV);
				if (inv == null)
				{
					throw new IllegalStateException("Inventory unavailable");
				}
				Item[] items = inv.getItems();
				for (int slot = 0; slot < items.length; slot++)
				{
					if (items[slot] != null && items[slot].getId() == itemId)
					{
						client.menuAction(slot, InterfaceID.Inventory.ITEMS,
							MenuAction.EXAMINE_ITEM, itemId, -1, "Examine", "");
						result.addProperty("examined", "inventoryItem");
						result.addProperty("itemId", itemId);
						result.addProperty("slot", slot);
						return result;
					}
				}
				throw new IllegalArgumentException("Item " + itemId + " not in inventory");
			}
			throw new IllegalArgumentException("Provide npcIndex/npcName, objectId+x+y, or itemId (+x+y for ground items)");
		});
	}

	private JsonObject getClan() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			addClanSection(result, "clan", client.getClanChannel());
			addClanSection(result, "guestClan", client.getGuestClanChannel());
			return result;
		});
	}

	private void addClanSection(JsonObject result, String key, net.runelite.api.clan.ClanChannel channel)
	{
		if (channel == null)
		{
			result.add(key, JsonNull.INSTANCE);
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", channel.getName());
		JsonArray members = new JsonArray();
		for (net.runelite.api.clan.ClanChannelMember m : channel.getMembers())
		{
			JsonObject row = new JsonObject();
			row.addProperty("name", m.getName());
			if (m.getRank() != null)
			{
				row.addProperty("rank", m.getRank().getRank());
			}
			row.addProperty("world", m.getWorld());
			members.add(row);
		}
		o.addProperty("memberCount", members.size());
		o.add("members", members);
		result.add(key, o);
	}

	private JsonObject getFriendsChat() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			net.runelite.api.FriendsChatManager fc = client.getFriendsChatManager();
			result.addProperty("joined", fc != null);
			if (fc == null)
			{
				return result;
			}
			result.addProperty("owner", fc.getOwner());
			result.addProperty("name", fc.getName());
			if (fc.getMyRank() != null)
			{
				result.addProperty("myRank", String.valueOf(fc.getMyRank()));
			}
			if (fc.getKickRank() != null)
			{
				result.addProperty("kickRank", String.valueOf(fc.getKickRank()));
			}
			JsonArray members = new JsonArray();
			for (net.runelite.api.FriendsChatMember m : fc.getMembers())
			{
				if (m == null)
				{
					continue;
				}
				JsonObject row = new JsonObject();
				row.addProperty("name", m.getName());
				row.addProperty("world", m.getWorld());
				if (m.getRank() != null)
				{
					row.addProperty("rank", String.valueOf(m.getRank()));
				}
				members.add(row);
			}
			result.addProperty("memberCount", members.size());
			result.add("members", members);
			return result;
		});
	}

	private JsonObject listFriends() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			net.runelite.api.FriendContainer friends = client.getFriendContainer();
			result.addProperty("count", friends != null ? friends.getCount() : 0);
			JsonArray arr = new JsonArray();
			if (friends != null)
			{
				for (net.runelite.api.Friend f : friends.getMembers())
				{
					if (f == null)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("name", f.getName());
					o.addProperty("world", f.getWorld());
					arr.add(o);
				}
			}
			result.add("friends", arr);
			return result;
		});
	}

	private JsonObject listIgnore() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			net.runelite.api.NameableContainer<net.runelite.api.Ignore> ignores = client.getIgnoreContainer();
			result.addProperty("count", ignores != null ? ignores.getCount() : 0);
			JsonArray arr = new JsonArray();
			if (ignores != null)
			{
				for (net.runelite.api.Ignore i : ignores.getMembers())
				{
					if (i == null)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("name", i.getName());
					if (i.getPrevName() != null)
					{
						o.addProperty("prevName", i.getPrevName());
					}
					arr.add(o);
				}
			}
			result.add("ignored", arr);
			return result;
		});
	}

	private JsonObject listVars(JsonObject args) throws Exception
	{
		String type = args.has("type") ? args.get("type").getAsString() : "varp";
		int max = optInt(args, "maxResults", 500);
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("type", type);
			JsonArray arr = new JsonArray();
			int count = 0;
			if ("varp".equals(type))
			{
				int[] varps = client.getVarps();
				int[] server = client.getServerVarps();
				int start = args.has("start") ? args.get("start").getAsInt() : 0;
				int end = args.has("end") ? args.get("end").getAsInt() : varps.length - 1;
				result.addProperty("totalVarps", varps.length);
				for (int i = Math.max(0, start); i <= Math.min(end, varps.length - 1) && count < max; i++)
				{
					JsonObject o = new JsonObject();
					o.addProperty("id", i);
					o.addProperty("value", varps[i]);
					if (server != null && i < server.length && server[i] != varps[i])
					{
						o.addProperty("serverValue", server[i]);
					}
					arr.add(o);
					count++;
				}
			}
			else
			{
				Map<Integer, Object> map = client.getVarcMap();
				if (map != null)
				{
					int start = args.has("start") ? args.get("start").getAsInt() : 0;
					int end = args.has("end") ? args.get("end").getAsInt() : Integer.MAX_VALUE;
					for (Map.Entry<Integer, Object> e : map.entrySet())
					{
						int id = e.getKey();
						if (id < start || id > end || count >= max)
						{
							continue;
						}
						boolean isStr = "varcstr".equals(type);
						if (e.getValue() instanceof String != isStr)
						{
							continue;
						}
						JsonObject o = new JsonObject();
						o.addProperty("id", id);
						if (e.getValue() instanceof String)
						{
							o.addProperty("value", (String) e.getValue());
						}
						else if (e.getValue() instanceof Number)
						{
							o.addProperty("value", ((Number) e.getValue()).intValue());
						}
						else
						{
							o.addProperty("value", String.valueOf(e.getValue()));
						}
						arr.add(o);
						count++;
					}
				}
				result.addProperty("populated", map != null ? map.size() : 0);
			}
			result.addProperty("count", count);
			result.add("vars", arr);
			return result;
		});
	}

	private JsonObject enumDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		int max = optInt(args, "maxResults", 500);
		return onClientThread(() ->
		{
			net.runelite.api.EnumComposition e = client.getEnum(id);
			if (e == null)
			{
				throw new IllegalArgumentException("Enum " + id + " not found");
			}
			JsonObject result = new JsonObject();
			result.addProperty("id", id);
			result.addProperty("size", e.size());
			JsonArray rows = new JsonArray();
			int[] keys = e.getKeys();
			int[] intVals = e.getIntVals();
			String[] strVals = e.getStringVals();
			for (int i = 0; keys != null && i < keys.length && i < max; i++)
			{
				JsonObject row = new JsonObject();
				row.addProperty("key", keys[i]);
				if (strVals != null && strVals.length > i && strVals[i] != null)
				{
					row.addProperty("stringValue", strVals[i]);
				}
				else if (intVals != null && intVals.length > i)
				{
					row.addProperty("intValue", intVals[i]);
				}
				rows.add(row);
			}
			result.add("entries", rows);
			return result;
		});
	}

	private JsonObject structDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			net.runelite.api.StructComposition s = client.getStructComposition(id);
			if (s == null)
			{
				throw new IllegalArgumentException("Struct " + id + " not found");
			}
			JsonObject result = new JsonObject();
			result.addProperty("id", id);
			List<Integer> keys = new ArrayList<>();
			if (args.has("keys"))
			{
				for (String k : args.get("keys").getAsString().split(","))
				{
					keys.add(Integer.parseInt(k.trim()));
				}
			}
			else if (s.getParams() != null)
			{
				for (net.runelite.api.Node node : s.getParams())
				{
					if (keys.size() >= 20)
					{
						break;
					}
					keys.add((int) node.getHash());
				}
			}
			JsonArray rows = new JsonArray();
			for (int key : keys)
			{
				JsonObject row = new JsonObject();
				row.addProperty("key", key);
				String strVal = s.getStringValue(key);
				if (strVal != null)
				{
					row.addProperty("stringValue", strVal);
				}
				row.addProperty("intValue", s.getIntValue(key));
				long longVal = s.getLongValue(key);
				if (longVal != 0)
				{
					row.addProperty("longValue", longVal);
				}
				rows.add(row);
			}
			result.add("params", rows);
			return result;
		});
	}

	private JsonObject animDef(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			net.runelite.api.Animation anim = client.loadAnimation(id);
			if (anim == null)
			{
				throw new IllegalArgumentException("Animation " + id + " not found");
			}
			JsonObject result = new JsonObject();
			result.addProperty("id", anim.getId());
			result.addProperty("numFrames", anim.getNumFrames());
			result.addProperty("duration", anim.getDuration());
			result.addProperty("frameStep", anim.getFrameStep());
			result.addProperty("mayaAnim", anim.isMayaAnim());
			int[] lengths = anim.getFrameLengths();
			if (lengths != null && lengths.length > 0)
			{
				JsonArray arr = new JsonArray();
				for (int len : lengths)
				{
					arr.add(len);
				}
				result.add("frameLengths", arr);
			}
			if (anim.getLeftHandItem() > 0)
			{
				result.addProperty("leftHandItem", anim.getLeftHandItem());
			}
			if (anim.getRightHandItem() > 0)
			{
				result.addProperty("rightHandItem", anim.getRightHandItem());
			}
			return result;
		});
	}

	private JsonObject listGfx() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			JsonArray arr = new JsonArray();
			net.runelite.api.Deque<net.runelite.api.GraphicsObject> objects = client.getGraphicsObjects();
			if (objects != null)
			{
				for (net.runelite.api.GraphicsObject gfx : objects)
				{
					if (gfx == null)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("gfxId", gfx.getId());
					net.runelite.api.coords.LocalPoint lp = gfx.getLocation();
					if (lp != null)
					{
						WorldPoint wp = WorldPoint.fromLocal(client, lp);
						if (wp != null)
						{
							o.addProperty("worldX", wp.getX());
							o.addProperty("worldY", wp.getY());
							o.addProperty("plane", wp.getPlane());
						}
					}
					o.addProperty("level", gfx.getLevel());
					o.addProperty("finished", gfx.finished());
					arr.add(o);
				}
			}
			result.addProperty("count", arr.size());
			result.add("graphics", arr);
			return result;
		});
	}

	private JsonObject listGeOffers() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			JsonArray arr = new JsonArray();
			net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null)
			{
				for (int slot = 0; slot < offers.length; slot++)
				{
					net.runelite.api.GrandExchangeOffer offer = offers[slot];
					if (offer == null)
					{
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("slot", slot);
					o.addProperty("state", offer.getState() != null ? offer.getState().name() : null);
					if (offer.getItemId() > 0)
					{
						o.addProperty("itemId", offer.getItemId());
						o.addProperty("quantity", offer.getTotalQuantity());
						o.addProperty("sold", offer.getQuantitySold());
						o.addProperty("price", offer.getPrice());
						o.addProperty("spent", offer.getSpent());
					}
					arr.add(o);
				}
			}
			result.add("offers", arr);
			return result;
		});
	}

	private JsonObject getInstanceMap() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			boolean instanced = client.isInInstancedRegion();
			result.addProperty("instanced", instanced);
			if (!instanced)
			{
				return result;
			}
			int[][][] chunks = client.getInstanceTemplateChunks();
			result.addProperty("planes", chunks != null ? chunks.length : 0);
			if (chunks != null)
			{
				JsonArray arr = new JsonArray();
				for (int z = 0; z < chunks.length; z++)
				{
					for (int x = 0; chunks[z] != null && x < chunks[z].length; x++)
					{
						for (int y = 0; chunks[z][x] != null && y < chunks[z][x].length; y++)
						{
							int packed = chunks[z][x][y];
							if (packed == 0)
							{
								continue;
							}
							JsonObject o = new JsonObject();
							o.addProperty("plane", z);
							o.addProperty("chunkX", x);
							o.addProperty("chunkY", y);
							o.addProperty("packed", packed);
							// Standard OSRS instance-chunk packing.
							o.addProperty("sourceRegionX", (packed >> 14) & 0x3FF);
							o.addProperty("sourceRegionY", (packed >> 3) & 0x7FF);
							o.addProperty("sourcePlane", (packed >> 24) & 0x3);
							o.addProperty("rotation", (packed >> 1) & 0x3);
							arr.add(o);
						}
					}
				}
				result.add("chunks", arr);
			}
			return result;
		});
	}

	private JsonObject dbTable(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			if (args.has("row"))
			{
				int row = args.get("row").getAsInt();
				result.addProperty("row", row);
				net.runelite.api.dbtable.DBRowConfig cfg = client.getDBRowConfig(row);
				if (cfg != null)
				{
					result.addProperty("tableId", cfg.getTableID());
				}
				JsonArray rows = new JsonArray();
				for (int col = 0; col < 16; col++)
				{
					for (int field = 0; field < 8; field++)
					{
						Object[] values = client.getDBTableField(row, col, field);
						if (values == null || values.length == 0)
						{
							continue;
						}
						for (int i = 0; i < values.length; i++)
						{
							JsonObject o = new JsonObject();
							o.addProperty("column", col);
							o.addProperty("field", field);
							o.addProperty("index", i);
							Object v = values[i];
							if (v instanceof Number)
							{
								o.addProperty("value", ((Number) v).longValue());
							}
							else
							{
								o.addProperty("value", String.valueOf(v));
							}
							rows.add(o);
						}
					}
				}
				result.add("fields", rows);
				return result;
			}
			if (!args.has("table"))
			{
				throw new IllegalArgumentException("Provide table, row, or table+column+value");
			}
			int table = args.get("table").getAsInt();
			result.addProperty("table", table);
			JsonArray arr = new JsonArray();
			if (args.has("column") && args.has("value"))
			{
				int column = args.get("column").getAsInt();
				int field = optInt(args, "field", 0);
				String raw = args.get("value").getAsString();
				Object value;
				try
				{
					value = Integer.parseInt(raw);
				}
				catch (NumberFormatException nfe)
				{
					value = raw;
				}
				for (Integer rowId : client.getDBRowsByValue(table, column, field, value))
				{
					arr.add(rowId);
				}
				result.addProperty("query", "column=" + column + " field=" + field + " value=" + value);
			}
			else
			{
				int max = optInt(args, "maxResults", 200);
				List<Integer> rows = client.getDBTableRows(table);
				int count = 0;
				if (rows != null)
				{
					for (Integer rowId : rows)
					{
						if (count++ >= max)
						{
							break;
						}
						arr.add(rowId);
					}
				}
				result.addProperty("totalRows", rows != null ? rows.size() : 0);
			}
			result.add("rows", arr);
			return result;
		});
	}

	private JsonObject itemSprite(JsonObject args) throws Exception
	{
		int itemId = args.get("itemId").getAsInt();
		int quantity = optInt(args, "quantity", 1);
		boolean noted = args.has("noted") && args.get("noted").getAsBoolean();
		BufferedImage image = onClientThread(() ->
		{
			net.runelite.api.SpritePixels sprite = client.createItemSprite(itemId, quantity,
				1, 0x333333, 0, noted, 1);
			return sprite != null ? sprite.toBufferedImage() : null;
		});
		if (image == null)
		{
			throw new IllegalArgumentException("No sprite for item " + itemId);
		}
		JsonObject imageContent = new JsonObject();
		imageContent.addProperty("type", "image");
		imageContent.addProperty("data", pngBase64(image));
		imageContent.addProperty("mimeType", "image/png");
		JsonArray content = new JsonArray();
		content.add(imageContent);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private JsonObject getMusic() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			JsonArray arr = new JsonArray();
			List<net.runelite.api.MidiRequest> requests = client.getActiveMidiRequests();
			if (requests != null)
			{
				for (net.runelite.api.MidiRequest r : requests)
				{
					JsonObject o = new JsonObject();
					o.addProperty("archiveId", r.getArchiveId());
					o.addProperty("jingle", r.isJingle());
					arr.add(o);
				}
			}
			result.addProperty("count", arr.size());
			result.add("tracks", arr);
			result.addProperty("musicVolume", client.getMusicVolume());
			return result;
		});
	}

	private JsonObject waitFor(JsonObject args) throws Exception
	{
		String condition = args.get("condition").getAsString();
		long timeout = Math.min(optInt(args, "timeoutMs", 10_000), 60_000);
		long start = System.currentTimeMillis();
		long deadline = start + timeout;
		int startTick = onClientThread(() -> client.getTickCount());
		while (true)
		{
			JsonObject state = onClientThread(() -> checkCondition(condition, args, start, startTick));
			if (state.get("met").getAsBoolean() || System.currentTimeMillis() >= deadline)
			{
				state.addProperty("condition", condition);
				state.addProperty("waitedMs", System.currentTimeMillis() - start);
				return textResult(state);
			}
			pauseMs(100);
		}
	}

	/**
	 * Evaluate a wait_for condition. Must be called on the client thread.
	 */
	private JsonObject checkCondition(String condition, JsonObject args, long startMs, int startTick)
	{
		JsonObject o = new JsonObject();
		boolean met;
		switch (condition)
		{
			case "idle":
			{
				Player local = client.getLocalPlayer();
				met = local != null && local.getAnimation() == -1
					&& local.getPoseAnimation() == local.getIdlePoseAnimation();
				if (local != null)
				{
					o.addProperty("animation", local.getAnimation());
					o.addProperty("poseAnimation", local.getPoseAnimation());
				}
				break;
			}
			case "at_tile":
			{
				int x = args.get("x").getAsInt();
				int y = args.get("y").getAsInt();
				int range = optInt(args, "range", 1);
				Player local = client.getLocalPlayer();
				WorldPoint wp = local != null ? local.getWorldLocation() : null;
				int distance = wp != null ? wp.distanceTo(new WorldPoint(x, y, wp.getPlane())) : Integer.MAX_VALUE;
				met = distance <= range;
				o.addProperty("distance", distance);
				break;
			}
			case "npc_dead":
			{
				NPC npc = findNpc(args);
				met = npc == null || npc.isDead();
				o.addProperty("despawned", npc == null);
				if (npc != null && npc.getHealthRatio() > -1)
				{
					o.addProperty("healthRatio", npc.getHealthRatio());
					o.addProperty("healthScale", npc.getHealthScale());
				}
				break;
			}
			case "dialogue":
			{
				JsonObject dialogue = readDialogue();
				met = !"none".equals(dialogue.get("type").getAsString());
				o.add("dialogue", dialogue);
				break;
			}
			case "interface_open":
			case "interface_closed":
			{
				int interfaceId = args.get("interfaceId").getAsInt();
				boolean open = interfaceBounds(interfaceId) != null;
				met = "interface_open".equals(condition) == open;
				o.addProperty("open", open);
				break;
			}
			case "chat_message":
			{
				String contains = args.get("textContains").getAsString().toLowerCase();
				JsonObject hit = recorder.firstEventSince("chat", startMs, e ->
					e.has("message") && e.get("message").getAsString().toLowerCase().contains(contains));
				met = hit != null;
				if (hit != null)
				{
					o.addProperty("message", hit.get("message").getAsString());
				}
				break;
			}
			case "hitsplat":
			{
				String actorFilter = args.has("actorName") ? args.get("actorName").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("combat", startMs, e ->
					actorFilter == null || (e.has("actor")
						&& e.get("actor").getAsString().toLowerCase().contains(actorFilter)));
				met = hit != null;
				if (hit != null)
				{
					o.add("hitsplat", hit);
				}
				break;
			}
			case "projectile":
			{
				Integer projectileId = args.has("projectileId") ? args.get("projectileId").getAsInt() : null;
				JsonObject hit = recorder.firstEventSince("projectile", startMs, e ->
					projectileId == null || e.get("projectileId").getAsInt() == projectileId);
				met = hit != null;
				if (hit != null)
				{
					o.add("projectile", hit);
				}
				break;
			}
			case "animation":
			case "spotanim":
			{
				String kind = "animation".equals(condition) ? "animation" : "graphic";
				String actorFilter = args.has("actorName") ? args.get("actorName").getAsString().toLowerCase()
					: args.has("npcName") ? args.get("npcName").getAsString().toLowerCase() : null;
				Integer wantedId = args.has("animationId") ? args.get("animationId").getAsInt() : null;
				JsonObject hit = recorder.firstEventSince("effect", startMs, e ->
				{
					if (!kind.equals(e.get("kind").getAsString()))
					{
						return false;
					}
					if (actorFilter != null && (!e.has("actor")
						|| !e.get("actor").getAsString().toLowerCase().contains(actorFilter)))
					{
						return false;
					}
					if (wantedId == null)
					{
						return true;
					}
					JsonElement value = e.get("value");
					if ("animation".equals(kind))
					{
						return value != null && value.isJsonPrimitive() && value.getAsInt() == wantedId;
					}
					if (value != null && value.isJsonArray())
					{
						for (JsonElement v : value.getAsJsonArray())
						{
							if (v.getAsInt() == wantedId)
							{
								return true;
							}
						}
					}
					return false;
				});
				met = hit != null;
				if (hit != null)
				{
					o.add("effect", hit);
				}
				break;
			}
			case "var":
			{
				String type = args.get("type").getAsString();
				int id = args.get("id").getAsInt();
				int current;
				switch (type)
				{
					case "varbit":
						current = client.getVarbitValue(id);
						break;
					case "varp":
						current = client.getVarpValue(id);
						break;
					case "varcint":
						current = client.getVarcIntValue(id);
						break;
					default:
						throw new IllegalArgumentException("var condition supports varbit, varp, varcint (not " + type + ")");
				}
				if (args.has("value"))
				{
					met = current == args.get("value").getAsInt();
				}
				else
				{
					// No expected value: wait for ANY change from the call-time baseline.
					if (!args.has("_baselineVar"))
					{
						args.addProperty("_baselineVar", current);
					}
					met = current != args.get("_baselineVar").getAsInt();
					o.addProperty("baseline", args.get("_baselineVar").getAsInt());
				}
				o.addProperty("current", current);
				break;
			}
			case "stat":
			{
				String skillName = args.get("skill").getAsString().toUpperCase();
				Skill skill;
				try
				{
					skill = Skill.valueOf(skillName);
				}
				catch (IllegalArgumentException ex)
				{
					throw new IllegalArgumentException("Unknown skill: " + skillName);
				}
				if (!args.has("_baselineStat"))
				{
					args.addProperty("_baselineStat", client.getBoostedSkillLevel(skill));
					args.addProperty("_baselineLevel", client.getRealSkillLevel(skill));
				}
				int boosted = client.getBoostedSkillLevel(skill);
				int real = client.getRealSkillLevel(skill);
				met = boosted != args.get("_baselineStat").getAsInt()
					|| real != args.get("_baselineLevel").getAsInt();
				o.addProperty("baselineBoosted", args.get("_baselineStat").getAsInt());
				o.addProperty("boostedLevel", boosted);
				o.addProperty("level", real);
				break;
			}
			case "menu":
			{
				boolean open = client.isMenuOpen();
				met = open;
				o.addProperty("open", open);
				break;
			}
			case "game_state":
			{
				String wanted = args.has("state") ? args.get("state").getAsString().toUpperCase() : "LOGGED_IN";
				String current = client.getGameState().name();
				met = current.contains(wanted);
				o.addProperty("gameState", current);
				break;
			}
			case "object_spawn":
			case "object_despawn":
			{
				String kind = "object_spawn".equals(condition) ? "spawned" : "despawned";
				Integer objectId = args.has("objectId") ? args.get("objectId").getAsInt() : null;
				String nameFilter = args.has("objectName") ? args.get("objectName").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("object", startMs, e ->
					kind.equals(e.get("kind").getAsString())
						&& (objectId == null || e.get("objectId").getAsInt() == objectId)
						&& (nameFilter == null || (e.has("name")
							&& e.get("name").getAsString().toLowerCase().contains(nameFilter))));
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "interacting":
			{
				String source = args.has("source") ? args.get("source").getAsString().toLowerCase() : null;
				String target = args.has("target") ? args.get("target").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("interact", startMs, e ->
					(source == null || (e.has("source")
						&& e.get("source").getAsString().toLowerCase().contains(source)))
						&& (target == null || (e.has("target")
							&& e.get("target").getAsString().toLowerCase().contains(target))));
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "gfx":
			{
				Integer gfxId = args.has("gfxId") ? args.get("gfxId").getAsInt() : null;
				JsonObject hit = recorder.firstEventSince("gfx", startMs, e ->
					gfxId == null || e.get("gfxId").getAsInt() == gfxId);
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "hintarrow":
			{
				met = client.hasHintArrow();
				o.addProperty("hasHintArrow", met);
				break;
			}
			case "in_combat":
			case "out_of_combat":
			{
				JsonObject combat = combatStateJson();
				boolean inCombat = combat.has("inCombat") && combat.get("inCombat").getAsBoolean();
				met = "in_combat".equals(condition) == inCombat;
				o.add("combat", combat);
				break;
			}
			case "prayer":
			{
				Prayer prayer = Prayer.valueOf(args.get("prayer").getAsString().toUpperCase());
				boolean wantOn = !args.has("on") || args.get("on").getAsBoolean();
				met = client.isPrayerActive(prayer) == wantOn;
				o.addProperty("active", client.isPrayerActive(prayer));
				break;
			}
			case "ticks":
			{
				int wanted = args.get("value").getAsInt();
				int elapsed = client.getTickCount() - startTick;
				met = elapsed >= wanted;
				o.addProperty("elapsedTicks", elapsed);
				break;
			}
			case "spawn":
			case "despawn":
			case "transform":
			{
				String nameFilter = args.has("actorName") ? args.get("actorName").getAsString().toLowerCase()
					: args.has("npcName") ? args.get("npcName").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("spawn", startMs, e ->
					condition.equalsIgnoreCase(e.get("kind").getAsString())
						&& (nameFilter == null || (e.has("name")
							&& e.get("name").getAsString().toLowerCase().contains(nameFilter))));
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "loot":
			{
				String nameFilter = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase()
					: args.has("actorName") ? args.get("actorName").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("loot", startMs, e ->
					"spawned".equals(e.get("kind").getAsString())
						&& (nameFilter == null || (e.has("name")
							&& e.get("name").getAsString().toLowerCase().contains(nameFilter))));
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "overhead":
			{
				String filter = args.has("textContains") ? args.get("textContains").getAsString().toLowerCase()
					: args.has("actorName") ? args.get("actorName").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("overhead", startMs, e ->
				{
					if (filter == null)
					{
						return true;
					}
					String actor = e.has("actor") ? e.get("actor").getAsString().toLowerCase() : "";
					String text = e.has("text") ? e.get("text").getAsString().toLowerCase() : "";
					return actor.contains(filter) || text.contains(filter);
				});
				met = hit != null;
				if (hit != null)
				{
					o.add("event", hit);
				}
				break;
			}
			case "xp":
			{
				String skillName = args.get("skill").getAsString().toUpperCase();
				Skill skill;
				try
				{
					skill = Skill.valueOf(skillName);
				}
				catch (IllegalArgumentException ex)
				{
					throw new IllegalArgumentException("Unknown skill: " + skillName);
				}
				if (!args.has("_baselineXp"))
				{
					args.addProperty("_baselineXp", client.getSkillExperience(skill));
				}
				int baseline = args.get("_baselineXp").getAsInt();
				int current = client.getSkillExperience(skill);
				int wanted = args.has("amount") ? args.get("amount").getAsInt() : 1;
				met = current - baseline >= wanted;
				o.addProperty("baselineXp", baseline);
				o.addProperty("xp", current);
				o.addProperty("xpGained", current - baseline);
				o.addProperty("level", client.getRealSkillLevel(skill));
				break;
			}
			case "container":
			{
				int containerId = optInt(args, "containerId", InventoryID.INV);
				int itemId = args.get("itemId").getAsInt();
				ItemContainer container = client.getItemContainer(containerId);
				int current = container != null ? container.count(itemId) : 0;
				if (!args.has("_baselineCount"))
				{
					args.addProperty("_baselineCount", current);
				}
				int baseline = args.get("_baselineCount").getAsInt();
				met = current != baseline;
				o.addProperty("containerId", containerId);
				o.addProperty("itemId", itemId);
				o.addProperty("baselineCount", baseline);
				o.addProperty("count", current);
				o.addProperty("delta", current - baseline);
				break;
			}
			case "action":
			{
				String filter = args.has("textContains") ? args.get("textContains").getAsString().toLowerCase()
					: args.has("filter") ? args.get("filter").getAsString().toLowerCase() : null;
				JsonObject hit = recorder.firstEventSince("action", startMs, e ->
				{
					if (filter == null)
					{
						return true;
					}
					String option = e.has("option") ? e.get("option").getAsString().toLowerCase() : "";
					String target = e.has("target") ? e.get("target").getAsString().toLowerCase() : "";
					String type = e.get("type").getAsString().toLowerCase();
					return option.contains(filter) || target.contains(filter) || type.contains(filter);
				});
				met = hit != null;
				if (hit != null)
				{
					o.add("action", hit);
				}
				break;
			}
			case "world":
			{
				if (!args.has("_baselineWorld"))
				{
					args.addProperty("_baselineWorld", client.getWorld());
				}
				int baseline = args.get("_baselineWorld").getAsInt();
				int current = client.getWorld();
				met = current != baseline;
				o.addProperty("baselineWorld", baseline);
				o.addProperty("world", current);
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown condition: " + condition
					+ " (idle, at_tile, npc_dead, dialogue, interface_open, interface_closed,"
					+ " chat_message, hitsplat, projectile, animation, spotanim, var,"
					+ " in_combat, out_of_combat, prayer, ticks, spawn, despawn, transform,"
					+ " loot, overhead, xp, container, action, world, stat, menu)");
		}
		o.addProperty("met", met);
		return o;
	}

	private JsonElement getSkills() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray skills = new JsonArray();
			for (Skill skill : Skill.values())
			{
				JsonObject o = new JsonObject();
				o.addProperty("skill", skill.getName());
				o.addProperty("level", client.getRealSkillLevel(skill));
				o.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
				o.addProperty("xp", client.getSkillExperience(skill));
				skills.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("totalLevel", client.getTotalLevel());
			result.add("skills", skills);
			return result;
		});
	}

	private JsonElement listObjects(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		Integer objectId = args.has("objectId") ? args.get("objectId").getAsInt() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Scene scene = client.getScene();
			if (scene == null)
			{
				throw new IllegalStateException("No scene is loaded");
			}
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			Tile[][] tiles = scene.getTiles()[client.getPlane()];
			// Multi-tile GameObjects appear on every tile they cover; report each once
			Set<GameObject> seen = new HashSet<>();
			List<JsonObject> list = new ArrayList<>();
			for (Tile[] column : tiles)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject go : gameObjects)
						{
							if (go == null || !seen.add(go))
							{
								continue;
							}
							JsonObject o = describeTileObject("game", go, me, filter, objectId, maxDistance);
							if (o != null)
							{
								o.addProperty("orientation", go.getOrientation());
								o.addProperty("orientationDegrees", go.getOrientation() * 360 / 2048);
								o.addProperty("facing", facing(go.getOrientation()));
								o.addProperty("sizeX", go.sizeX());
								o.addProperty("sizeY", go.sizeY());
								list.add(o);
							}
						}
					}
					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						JsonObject o = describeTileObject("wall", wall, me, filter, objectId, maxDistance);
						if (o != null)
						{
							o.addProperty("orientationA", wall.getOrientationA());
							o.addProperty("orientationB", wall.getOrientationB());
							list.add(o);
						}
					}
					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null)
					{
						JsonObject o = describeTileObject("decorative", deco, me, filter, objectId, maxDistance);
						if (o != null)
						{
							list.add(o);
						}
					}
					GroundObject ground = tile.getGroundObject();
					if (ground != null)
					{
						JsonObject o = describeTileObject("ground", ground, me, filter, objectId, maxDistance);
						if (o != null)
						{
							list.add(o);
						}
					}
				}
			}
			return actorListResult("objects", list, maxResults);
		});
	}

	/**
	 * Base description of a scene object, or null when it fails the filters.
	 * Must be called on the client thread.
	 */
	private JsonObject describeTileObject(String type, TileObject obj, WorldPoint me,
		String lowercaseFilter, Integer objectId, int maxDistance)
	{
		if (objectId != null && obj.getId() != objectId)
		{
			return null;
		}
		ObjectComposition composition = client.getObjectDefinition(obj.getId());
		String name = composition != null ? composition.getName() : null;
		if (lowercaseFilter != null && (name == null || !name.toLowerCase().contains(lowercaseFilter)))
		{
			return null;
		}
		WorldPoint wp = obj.getWorldLocation();
		int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
		if (distance >= 0 && distance > maxDistance)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("objectId", obj.getId());
		if (name != null && !"null".equals(name))
		{
			o.addProperty("name", name);
		}
		if (composition != null && composition.getActions() != null)
		{
			JsonArray actions = new JsonArray();
			for (String action : composition.getActions())
			{
				if (action != null)
				{
					actions.add(action);
				}
			}
			if (actions.size() > 0)
			{
				o.add("actions", actions);
			}
		}
		if (wp != null)
		{
			o.addProperty("worldX", wp.getX());
			o.addProperty("worldY", wp.getY());
			o.addProperty("plane", wp.getPlane());
		}
		if (distance >= 0)
		{
			o.addProperty("distance", distance);
		}
		return o;
	}

	private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	/**
	 * Convert a jagex angle unit orientation (0-2047, 0 = south, counter-clockwise) to a compass direction.
	 */
	private static String facing(int jau)
	{
		return COMPASS[((jau + 128) & 2047) / 256];
	}

	private JsonElement pickupItem(JsonObject args) throws Exception
	{
		int itemId = args.get("itemId").getAsInt();
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		return onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
			}
			client.menuAction(lp.getSceneX(), lp.getSceneY(), MenuAction.GROUND_ITEM_THIRD_OPTION,
				itemId, -1, "Take", "");
			JsonObject result = new JsonObject();
			result.addProperty("invoked", "Take");
			result.addProperty("itemId", itemId);
			result.addProperty("tile", x + "," + y + "," + plane);
			result.addProperty("note", "Player may need to walk to the tile; check list_ground_items again to confirm pickup");
			return result;
		});
	}

	// --- actor helpers ---

	private static boolean matchesName(String name, String lowercaseFilter)
	{
		return lowercaseFilter == null || (name != null && name.toLowerCase().contains(lowercaseFilter));
	}

	/**
	 * Describe an actor, or null when it is farther than maxDistance.
	 * Must be called on the client thread.
	 */
	private JsonObject describeActor(Actor actor, WorldPoint me, int maxDistance)
	{
		WorldPoint wp = actor.getWorldLocation();
		int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
		if (distance >= 0 && distance > maxDistance)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", actor.getName());
		o.addProperty("combatLevel", actor.getCombatLevel());
		if (wp != null)
		{
			o.addProperty("worldX", wp.getX());
			o.addProperty("worldY", wp.getY());
			o.addProperty("plane", wp.getPlane());
		}
		if (distance >= 0)
		{
			o.addProperty("distance", distance);
		}
		if (actor.getAnimation() != -1)
		{
			o.addProperty("animation", actor.getAnimation());
		}
		o.addProperty("poseAnimation", actor.getPoseAnimation());
		o.addProperty("orientation", actor.getCurrentOrientation());
		o.addProperty("facing", facing(actor.getCurrentOrientation()));
		// Health bar is only populated while it is rendered (in/just after combat)
		if (actor.getHealthRatio() > -1)
		{
			o.addProperty("healthRatio", actor.getHealthRatio());
			o.addProperty("healthScale", actor.getHealthScale());
			if (actor.getHealthScale() > 0)
			{
				o.addProperty("healthPercent", actor.getHealthRatio() * 100 / actor.getHealthScale());
			}
		}
		if (actor.isDead())
		{
			o.addProperty("isDead", true);
		}
		Actor interacting = actor.getInteracting();
		if (interacting != null)
		{
			o.addProperty("interacting", interacting.getName());
			o.addProperty("interactingType", interacting instanceof NPC ? "npc" : "player");
			if (interacting instanceof NPC)
			{
				o.addProperty("interactingIndex", ((NPC) interacting).getIndex());
			}
			if (interacting == client.getLocalPlayer() && actor != interacting)
			{
				o.addProperty("targetsMe", true);
			}
		}
		JsonArray spotAnims = new JsonArray();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			spotAnims.add(spotAnim.getId());
		}
		if (spotAnims.size() > 0)
		{
			o.add("spotAnims", spotAnims);
		}
		LocalPoint lp = actor.getLocalLocation();
		if (lp != null && wp != null)
		{
			net.runelite.api.Point p = Perspective.localToCanvas(client, lp, wp.getPlane());
			if (p != null)
			{
				o.addProperty("canvasX", p.getX());
				o.addProperty("canvasY", p.getY());
			}
		}
		return o;
	}

	private static JsonObject actorListResult(String key, List<JsonObject> list, int maxResults)
	{
		list.sort(Comparator.comparingInt(o -> o.has("distance") ? o.get("distance").getAsInt() : Integer.MAX_VALUE));
		JsonArray arr = new JsonArray();
		for (int i = 0; i < list.size() && i < maxResults; i++)
		{
			arr.add(list.get(i));
		}
		JsonObject result = new JsonObject();
		result.addProperty("count", list.size());
		result.add(key, arr);
		return result;
	}

	// --- widget helpers ---

	/**
	 * Union of the visible component bounds of an interface, or null if none.
	 * Must be called on the client thread.
	 */
	private Rectangle interfaceBounds(int interfaceId)
	{
		Rectangle union = null;
		int gap = 0;
		for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE && gap < COMPONENT_SCAN_GAP; child++)
		{
			Widget w = client.getWidget(WidgetUtil.packComponentId(interfaceId, child));
			if (w == null)
			{
				gap++;
				continue;
			}
			gap = 0;
			if (w.isHidden())
			{
				continue;
			}
			Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}
			union = union == null ? new Rectangle(b) : union.union(b);
		}
		return union;
	}

	private static Rectangle pad(Rectangle r, int padding)
	{
		return new Rectangle(r.x - padding, r.y - padding, r.width + padding * 2, r.height + padding * 2);
	}

	private Widget resolveWidget(JsonObject args, String idKey, String indexKey)
	{
		Widget w = client.getWidget(args.get(idKey).getAsInt());
		if (w != null && args.has(indexKey))
		{
			w = w.getChild(args.get(indexKey).getAsInt());
		}
		return w;
	}

	private JsonObject describeWidget(Widget w, int depth, boolean includeHidden)
	{
		JsonObject o = new JsonObject();
		o.addProperty("componentId", w.getId());
		o.addProperty("interfaceId", WidgetUtil.componentToInterface(w.getId()));
		o.addProperty("childId", WidgetUtil.componentToId(w.getId()));
		if (w.getIndex() != -1)
		{
			o.addProperty("childIndex", w.getIndex());
		}
		o.addProperty("type", w.getType());
		if (w.getContentType() != 0)
		{
			o.addProperty("contentType", w.getContentType());
		}
		Rectangle bounds = w.getBounds();
		if (bounds != null)
		{
			JsonObject b = new JsonObject();
			b.addProperty("x", bounds.x);
			b.addProperty("y", bounds.y);
			b.addProperty("width", bounds.width);
			b.addProperty("height", bounds.height);
			o.add("bounds", b);
		}
		o.addProperty("relativeX", w.getRelativeX());
		o.addProperty("relativeY", w.getRelativeY());
		o.addProperty("originalX", w.getOriginalX());
		o.addProperty("originalY", w.getOriginalY());
		o.addProperty("width", w.getWidth());
		o.addProperty("height", w.getHeight());
		o.addProperty("hidden", w.isHidden());
		if (w.isSelfHidden())
		{
			o.addProperty("selfHidden", true);
		}
		String text = w.getText();
		if (text != null && !text.isEmpty())
		{
			o.addProperty("text", text);
			o.addProperty("textColor", String.format("%06x", w.getTextColor()));
		}
		String name = w.getName();
		if (name != null && !name.isEmpty())
		{
			o.addProperty("name", name);
		}
		if (w.getOpacity() != 0)
		{
			o.addProperty("opacity", w.getOpacity());
		}
		if (w.getItemId() != -1)
		{
			o.addProperty("itemId", w.getItemId());
			o.addProperty("itemQuantity", w.getItemQuantity());
		}
		if (w.getSpriteId() != -1)
		{
			o.addProperty("spriteId", w.getSpriteId());
		}
		if (w.getModelId() != -1)
		{
			o.addProperty("modelId", w.getModelId());
		}
		if (depth > 0)
		{
			Widget[] dynamic = w.getDynamicChildren();
			if (dynamic != null && dynamic.length > 0)
			{
				JsonArray children = new JsonArray();
				for (Widget child : dynamic)
				{
					if (child != null && (includeHidden || !child.isHidden()))
					{
						children.add(describeWidget(child, depth - 1, includeHidden));
					}
				}
				o.add("dynamicChildren", children);
			}
			appendChildIds(o, "staticChildIds", w.getStaticChildren());
			appendChildIds(o, "nestedChildIds", w.getNestedChildren());
		}
		return o;
	}

	private static void appendChildIds(JsonObject o, String key, Widget[] children)
	{
		if (children == null || children.length == 0)
		{
			return;
		}
		JsonArray ids = new JsonArray();
		for (Widget child : children)
		{
			if (child != null)
			{
				ids.add(child.getId());
			}
		}
		if (ids.size() > 0)
		{
			o.add(key, ids);
		}
	}

	private static void enqueueChildren(Deque<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null)
			{
				queue.add(child);
			}
		}
	}

	private static JsonArray toArray(Set<Integer> values)
	{
		JsonArray arr = new JsonArray();
		values.stream().sorted().forEach(arr::add);
		return arr;
	}

	private Point resolvePoint(JsonObject args, String idKey, String indexKey, String xKey, String yKey) throws Exception
	{
		if (args.has(idKey))
		{
			Rectangle bounds = onClientThread(() ->
			{
				Widget w = resolveWidget(args, idKey, indexKey);
				return w == null || w.isHidden() ? null : w.getBounds();
			});
			if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			{
				throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
					+ describeId(args, idKey, indexKey));
			}
			return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		}
		if (args.has(xKey) && args.has(yKey))
		{
			return new Point(args.get(xKey).getAsInt(), args.get(yKey).getAsInt());
		}
		throw new IllegalArgumentException("Provide either " + idKey + " or " + xKey + "/" + yKey);
	}

	private static String describeId(JsonObject args, String idKey, String indexKey)
	{
		int id = args.get(idKey).getAsInt();
		String s = (id >> 16) + "." + (id & 0xFFFF) + " (" + id + ")";
		if (args.has(indexKey))
		{
			s += "[" + args.get(indexKey).getAsInt() + "]";
		}
		return s;
	}

	// --- input dispatch ---

	/**
	 * Pace input events without Thread.sleep: the delay runs on the scheduler and
	 * {@code get()} blocks this worker thread interruptibly, so MCP tool-call
	 * cancellation still aborts mid-sequence. Only used for millisecond input
	 * pacing where the event ordering, not the exact delay, matters.
	 */
	private static void pauseMs(long ms) throws InterruptedException, ExecutionException
	{
		CompletableFuture.runAsync(() ->
		{
		}, CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS)).get();
	}

	private void clickAt(int x, int y, int button) throws Exception
	{
		dispatchMouse(MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON, 0);
		pauseMs(15);
		dispatchMouse(MouseEvent.MOUSE_PRESSED, x, y, button, 1);
		pauseMs(20);
		dispatchMouse(MouseEvent.MOUSE_RELEASED, x, y, button, 1);
		dispatchMouse(MouseEvent.MOUSE_CLICKED, x, y, button, 1);
	}

	private void dispatchMouse(int id, int x, int y, int button, int clickCount) throws Exception
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			throw new IllegalStateException("Client canvas is not available");
		}
		int modifiers = 0;
		if (button != MouseEvent.NOBUTTON
			&& (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED))
		{
			modifiers = InputEvent.getMaskForButton(button);
		}
		// MOUSE_DRAGGED carries the pressed button in the modifier mask, not the button field
		int eventButton = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : button;
		MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers,
			x, y, clickCount, false, eventButton);
		SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(event));
	}

	private void typeChar(char c) throws Exception
	{
		int code = KeyEvent.getExtendedKeyCodeForChar(c);
		dispatchKeyBatch(
			keyEvent(KeyEvent.KEY_PRESSED, code, c),
			keyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c),
			keyEvent(KeyEvent.KEY_RELEASED, code, c));
		pauseMs(5);
	}

	private void pressNamedKey(String name) throws Exception
	{
		int keyCode;
		char keyChar = KeyEvent.CHAR_UNDEFINED;
		switch (name)
		{
			case "ENTER":
				keyCode = KeyEvent.VK_ENTER;
				keyChar = '\n';
				break;
			case "ESCAPE":
			case "ESC":
				keyCode = KeyEvent.VK_ESCAPE;
				keyChar = (char) 27;
				break;
			case "SPACE":
				keyCode = KeyEvent.VK_SPACE;
				keyChar = ' ';
				break;
			case "TAB":
				keyCode = KeyEvent.VK_TAB;
				keyChar = '\t';
				break;
			case "BACKSPACE":
				keyCode = KeyEvent.VK_BACK_SPACE;
				keyChar = '\b';
				break;
			case "UP":
				keyCode = KeyEvent.VK_UP;
				break;
			case "DOWN":
				keyCode = KeyEvent.VK_DOWN;
				break;
			case "LEFT":
				keyCode = KeyEvent.VK_LEFT;
				break;
			case "RIGHT":
				keyCode = KeyEvent.VK_RIGHT;
				break;
			default:
				if (name.matches("F([1-9]|1[0-2])"))
				{
					keyCode = KeyEvent.VK_F1 + Integer.parseInt(name.substring(1)) - 1;
					break;
				}
				throw new IllegalArgumentException("Unknown key: " + name);
		}
		if (keyChar != KeyEvent.CHAR_UNDEFINED)
		{
			dispatchKeyBatch(
				keyEvent(KeyEvent.KEY_PRESSED, keyCode, keyChar),
				keyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, keyChar),
				keyEvent(KeyEvent.KEY_RELEASED, keyCode, keyChar));
		}
		else
		{
			dispatchKeyBatch(
				keyEvent(KeyEvent.KEY_PRESSED, keyCode, keyChar),
				keyEvent(KeyEvent.KEY_RELEASED, keyCode, keyChar));
		}
	}

	private KeyEvent keyEvent(int id, int keyCode, char keyChar)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			throw new IllegalStateException("Client canvas is not available");
		}
		return new KeyEvent(canvas, id, System.currentTimeMillis(), 0, keyCode, keyChar);
	}

	private void dispatchKeyBatch(KeyEvent... events) throws Exception
	{
		Canvas canvas = client.getCanvas();
		SwingUtilities.invokeAndWait(() ->
		{
			for (KeyEvent event : events)
			{
				canvas.dispatchEvent(event);
			}
		});
	}

	// --- capture ---

	private BufferedImage captureFrame() throws Exception
	{
		CompletableFuture<Image> future = new CompletableFuture<>();
		drawManager.requestNextFrameListener(future::complete);
		Image image = future.get(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		if (image instanceof BufferedImage)
		{
			return (BufferedImage) image;
		}
		BufferedImage copy = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return copy;
	}

	private static String pngBase64(BufferedImage image) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	// --- plumbing ---

	private <T> T onClientThread(Supplier<T> supplier) throws Exception
	{
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<RuntimeException> error = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			try
			{
				result.set(supplier.get());
			}
			catch (RuntimeException e)
			{
				error.set(e);
			}
			finally
			{
				latch.countDown();
			}
		});
		if (!latch.await(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS))
		{
			throw new IllegalStateException("Timed out waiting for the client thread; is the client running?");
		}
		if (error.get() != null)
		{
			throw error.get();
		}
		return result.get();
	}

	private static JsonObject textResult(String text)
	{
		JsonObject item = new JsonObject();
		item.addProperty("type", "text");
		item.addProperty("text", text);
		JsonArray content = new JsonArray();
		content.add(item);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private static JsonObject textResult(JsonElement json)
	{
		return textResult(McpHttpServer.GSON.toJson(json));
	}

	private static int optInt(JsonObject args, String key, int def)
	{
		return args.has(key) ? args.get(key).getAsInt() : def;
	}

	/** Optional string-array argument, as a set; null when absent so callers read it as "no filter". */
	private static Set<String> optStringSet(JsonObject args, String key)
	{
		if (!args.has(key) || !args.get(key).isJsonArray())
		{
			return null;
		}
		Set<String> values = new HashSet<>();
		for (JsonElement element : args.get(key).getAsJsonArray())
		{
			values.add(element.getAsString());
		}
		return values.isEmpty() ? null : values;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema)
	{
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("description", description);
		o.add("inputSchema", inputSchema);
		return o;
	}

	private static JsonObject schema(JsonObject properties, String... required)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (required.length > 0)
		{
			JsonArray req = new JsonArray();
			for (String r : required)
			{
				req.add(r);
			}
			o.add("required", req);
		}
		return o;
	}

	private static JsonObject prop(String type, String description)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("description", description);
		return o;
	}
}
