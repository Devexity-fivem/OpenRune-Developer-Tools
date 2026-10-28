# OpenRune-Developer-Tools

Standalone sideloaded plugin for RuneLite-based clients. Runs a local MCP server
(default `http://127.0.0.1:7780/mcp`) so AI tools like Claude can screenshot
interfaces, inspect widgets/NPCs/objects/items, drive input (click, drag, type,
dialogue) and read script/var/projectile/chat history. Live activity dashboard
at `http://127.0.0.1:7780/` shows every AI tool call with args, results and
screenshots.

## Build and deploy

```
gradlew build   # compile + run the unit/transport test suite, no side effects
gradlew jar     # build the plugin jar only
gradlew deploy  # build and copy the jar into every detected client's sideloaded-plugins folder
```

Deployment is a separate, explicit task: building or testing never touches
installed clients. `deploy` looks for the `sideloaded-plugins` folder each
client creates in your home directory — `~/.runelite`, `~/.rsprox`,
`~/.fluxious`, or a fork's own directory — and prints where it deployed.

Deploying fails if one of those clients is running, because it holds its jar
open. The other clients are still updated and the error names the ones that
were not. A client only loads a new jar when it starts, so restart it after
deploying.

Two optional flags, for cases the default doesn't cover:

```
gradlew deploy -PsideloadDirs=.runelite,.myclient   # deploy to exactly these
gradlew jar -PclientJar=/path/to/client-shaded.jar   # compile against a client's jar
```

`-PclientJar` is only needed when your client exposes API this plugin uses;
otherwise the build compiles against the upstream RuneLite artifact.

Start the client with `--developer-mode`, enable **OpenRune-DeveloperTools**,
then connect Claude:

```
claude mcp add --transport http flux http://127.0.0.1:7780/mcp
```

The server binds to the loopback interface only and refuses browser cross-origin
POSTs (an `Origin` header that is not loopback is rejected), so a web page cannot
drive your client. Request bodies are capped at 2 MiB. It is still an
unauthenticated local control surface — treat anything that can POST to
localhost as able to act in game.

The AI calls these tools constantly — skip per-call permission prompts by allowing
the whole server in your project's `.claude/settings.local.json`:

```json
{
	"permissions": {
		"allow": ["mcp__flux"]
	}
}
```

## Dashboard

While the plugin is running, `http://127.0.0.1:7780/` serves a live activity
dashboard — open it in a browser to watch what the AI is doing in real time:

![Live activity dashboard](docs/dashboard.png)

- Every MCP tool call as it happens: tool name, arguments as chips, duration,
  timestamp, with slow calls (>1s) and errors highlighted.
- Results inline: JSON pretty-printed and collapsible, screenshots as thumbnails
  with a click-to-zoom lightbox.
- Header stats for the session: total calls, errors, average duration, live
  connection indicator.
- Filter by tool via dropdown, errors-only toggle, pause/resume, follow
  (auto-scroll) and clear.

The page polls `GET /log?after=<id>` (same port) for new entries; the server keeps
the last 100 calls in memory, so refreshing the page replays recent history.

## Releases

Pushing to the `prod` branch builds the jar and publishes a GitHub release
automatically. The in-plugin update check only announces that a newer release
exists on this fork (in chat and the logs); it never downloads or installs
anything by itself.

## Tools

### Inspection

| Tool | Description |
|---|---|
| `screenshot` | PNG of the canvas; crop to a widget (`componentId`), whole interface (`interfaceId`) or region |
| `get_widget` | Widget values by packed id: bounds, position, text, item, sprite, hidden, children |
| `dump_interface` | Every component of an interface with positions/sizes/text — diff two dumps to spot layout changes |
| `list_interfaces` | Interface ids currently loaded and visible |
| `get_widget_at` | Deepest visible widget at a canvas pixel, with parent chain |
| `set_widget` | Live-edit a widget client-side: move, resize, hide, retext, recolor, then revalidate |
| `get_client_state` | Game state, canvas size, mouse, player position/region, energy, weight, camera, menu-open flag |
| `get_status` | Server + session status: plugin version, tool count, game state, tick, position — check this first when a tool behaves oddly |
| `observe` | One client-thread pass: vitals, boosts, combat, inventory (with item ops), nearest NPCs/objects/ground items, dialogue. Optional `sections`: equipment, skills, players, projectiles |
| `get_combat_state` | Cheap combat poll: in-combat flag (Idle Notifier timing), attackers, target, active prayers, ticks since last hit dealt/received |
| `get_skills` | All skills: level, boosted level, xp, total level |

### Raw input

| Tool | Description |
|---|---|
| `click` | Click at canvas coordinates (real mouse events) |
| `click_component` | Click the center of a widget by packed id |
| `hover` | Move the mouse to coordinates or a widget center |
| `drag` | Press, drag through interpolated points, release — components or coordinates |
| `type_chat` | Type into the chatbox and send (works for `::` commands) |
| `press_key` | Press one key: char, ENTER, ESCAPE, SPACE, TAB, arrows, F1-F12 |

### World

| Tool | Description |
|---|---|
| `walk_to` | Click a world tile to walk there (viewport, or minimap when off-screen) |
| `list_npcs` | NPCs with id, index, name, actions, location, distance, animation, facing, health |
| `list_players` | Players with name, location, distance, animation, facing, health; local flagged |
| `list_objects` | Scene objects (game/wall/decorative/ground) with id, name, actions, position, orientation |
| `list_ground_items` | Floor items with id, name, quantity, location, distance |
| `pickup_item` | Invoke Take on a ground item at world coordinates |
| `list_projectiles` | Projectiles in flight with target info |
| `get_inventory` | Any item container by id (93 inventory, 94 worn, 95 bank, custom) with slots/names/quantities |
| `list_inventories` | All loaded item containers |

### Interaction (one call, no screenshots)

| Tool | Description |
|---|---|
| `interact_npc` | Invoke a cache-defined NPC option (default Talk-to) |
| `get_npc_menu` | Hover an NPC and read its live right-click menu, incl. server-added options |
| `click_menu_option` | Click an entry of the currently built mini-menu |
| `item_action` | Invoke an item's own inventory op (Drop, Eat, Wield...) — works with the inventory tab closed |
| `interact_object` | Nearest matching object, hover, click option (Chop down, Mine, Open...) |
| `object_action` | Invoke a scene object's op directly by objectId + world x/y — object need not be on screen, mouse never moves |
| `interact_player` | Hover a player and click an option (Trade with, Follow...) |
| `widget_action` | Right-click any widget and click a menu option (spells, prayers, buttons) |
| `set_prayer` | Toggle a prayer by name (exact Prayer enum name or fuzzy prayer-book label) via the real prayer-book widget |
| `invoke_menu_action` | Raw `menuAction` escape hatch with explicit params |

### Decision loop (observe → pick → act)

| Tool | Description |
|---|---|
| `list_actions` | Everything actionable right now as a numbered menu — dialogue answers, NPC ops, object ops, ground-item takes, item ops — each with the tool + args that would run it |
| `act` | Execute one menu entry by `id`. Rejects ids from menus older than `maxAgeTicks` (default 3) and revalidates the target (NPC still there and same id, item still on the tile, dialogue still open) before dispatch |

`list_actions` answers "what can I do here" in one call instead of an agent
guessing targets. Actors that target the local player are flagged `targetsMe`
in `observe`/`list_npcs`, and NPCs carry `interactingIndex` when their target
can be resolved. Destructive or two-target ops (drop, destroy, use) are never
listed — use `item_action`/`invoke_menu_action` for those deliberately.

### Dialogue

| Tool | Description |
|---|---|
| `get_dialogue` | Current dialogue: NPC/player text, options menu, message box, item box, input prompt |
| `continue_dialogue` | Press space, return the new dialogue state as soon as it changes |
| `select_option` | Pick a numbered dialogue option, return the new state |
| `enter_input` | Type into an open chatbox input (string/amount) and press Enter |

### History and vars

| Tool | Description |
|---|---|
| `get_script_history` | Clientscripts fired recently: per-script counts + events with tick and args |
| `get_var_history` | Varbit/varp/varc changes with old/new values, tick, timestamp |
| `get_var` | Current value of a varbit, varp, varc int or varc string |
| `get_projectile_history` | Projectiles launched recently, one entry each, with values and target |
| `get_effect_history` | Animations and graphics played by NPCs/players recently |
| `get_chat_history` | Chatbox messages: type, sender, message, tick, timestamp |
| `get_combat_history` | Hitsplats applied: amount, type, actor hit, remaining health ratio |
| `get_xp_history` | Experience gains: skill, xp gained, new total, level, boosted level |
| `get_container_history` | Item container changes as per-item deltas (was/now/delta) |
| `get_sound_history` | Sound effects played: id, source actor, scene position for area sounds |

### Timeline

| Tool | Description |
|---|---|
| `get_timeline` | Every channel merged into one tick-ordered stream — scripts, vars, projectiles, effects, chat, combat, experience, containers, sounds. Scripts are left out unless requested. Filter by `channels`, `fromTick`/`toTick` |

### Waiting

| Tool | Description |
|---|---|
| `wait_for` | Block until a condition: idle, at_tile, npc_dead, dialogue, interface_open/closed, chat_message, hitsplat, projectile, animation, spotanim, var, in_combat/out_of_combat, prayer — checked every 100ms, returns immediately when met. Event-based conditions only see new events after the call starts |

All history tools report a `dropped` count — how many events were evicted from
the bounded buffer — so truncation is visible instead of silent.
