# Fork Improvement Roadmap — Devexity-fivem/OpenRune-Developer-Tools

Persistent audit and implementation ledger for the MCP testing/automation
improvements on branch `devexity-mcp-improvements`.

## 1. Baseline

| Item | Value |
|---|---|
| Fork | `https://github.com/Devexity-fivem/OpenRune-Developer-Tools` (`fork` remote) |
| Upstream | `https://github.com/OpenRune/OpenRune-Developer-Tools` (`origin` remote) |
| Baseline commit | `8304e71ddd4df43cb6875314cb3e5fe5808ed0ec` (fork `main` == upstream `main` == local `main`) |
| Upstream `prod` | `d3951fda905f148c11c05b68bf9e6126a21850df` (tag `v0.1.0-3`) |
| Working branch | `devexity-mcp-improvements` |
| Plugin version | `0.1.0` (local builds `0.1.0-local`) |
| Build | Gradle 8.8, Java 11 target, compiles against `net.runelite:client:latest.release` (resolved: runelite-api 1.13.4) |
| Tests | none present |
| CI | `.github/workflows/release.yml` — builds + releases on push to `prod` |
| Working tree at start | clean, no fork-only commits |

## 2. Architecture audit

- Plugin lifecycle: `OpenRuneDevToolsPlugin.startUp()` builds `McpEventRecorder`,
  `ItemIconTool`, starts `McpHttpServer` (loopback only, port from config, default
  7780). Busy port logs an error but keeps the plugin enabled (commit `c052532`).
- HTTP layer: `com.sun.net.httpserver` on a 4-thread pool. `POST /mcp` handles
  JSON-RPC 2.0 (`initialize`, `ping`, `tools/list`, `tools/call`); `GET /log`
  feeds the dashboard; `GET /` serves `dashboard.html`. New tool call interrupts
  the in-flight one (`activeToolThread` + `notifications/cancelled`).
- Client-thread access: `McpTools.onClientThread` via `ClientThread.invoke` +
  latch, 5 s timeout. Input via real `MouseEvent`/`KeyEvent` on the canvas (EDT).
- Event recorder: 10 bounded channels with global seq + tick; queries merge and
  tail-limit. Baselines (last var/xp/container values) used to suppress no-change
  events.
- Update path: `UpdateChecker` polls GitHub releases (upstream repo), downloads
  into every detected `*/sideloaded-plugins`, swaps locked jars via a shutdown
  hook + detached shell move.
- Deploy path: `jar` task is `finalizedBy deploy`, which copies to every detected
  client sideload dir and fails when a running client locks its jar.

## 3. Upstream evaluation

Upstream `main` has not moved past `8304e71`; the only candidates are the two
open PRs (both authored by Mvrcus, generated with Claude Code, tested by the
author on a live rev-238 private-server client; no reviews). Both compile
cleanly against our toolchain (verified: `git worktree` + `gradlew compileJava`
of `pr-3`, which contains `pr-2`).

### PR #2 — combat state, prayer switching, direct item ops (`e542555`, `51e3a19`)

| Proposal | Decision | Notes |
|---|---|---|
| `get_combat_state` | Adopt with corrections | Add hp/prayer points, player attackers, distance/health on attackers; reset hit-tick trackers on login/hop |
| `recordCombatTick` (last hitsplat on me / by me) | Adopt with corrections | `Hitsplat.isMine()` exists in API 1.13.4 and means "dealt by local player" — usage correct; add reset on login/hop |
| `set_prayer` | Adopt with corrections | `InterfaceID.Prayerbook.PRAYER1..30` verified sequential in API 1.13.4. Adapt: handle `RP_*` (Ruinous) names, fall back to scanning the alternate prayer-book interface, clearer error for unknown prayers |
| `interactingIndex` / `targetsMe` on actor rows | Adopt as written | Correct client-thread-safe reads |
| `inventoryActions` on `get_inventory` | Adopt as written | |
| `item_action` direct `CC_OP` on slot widget | Adopt as written | Op index `i<5 ? CC_OP : CC_OP_LOW_PRIORITY`, identifier `i+1` matches client menu construction; hover fallback kept for ops the slot doesn't list |

### PR #3 — `observe`, `list_actions`, `act` (stacked on #2; `b9f9bd3`)

| Proposal | Decision | Notes |
|---|---|---|
| `observe` | Adopt with corrections | Null-safety fixes (see findings F-01..F-03), `worldX/worldY` naming for consistency, optional `sections` (equipment, skills, players, projectiles) |
| `list_actions` | Adopt with corrections | Same null-safety fixes; expose real tool name for object actions |
| `act` | Adopt with corrections | Read tick on client thread; per-kind pre-dispatch revalidation (NPC index+id, object on tile, ground item present, dialogue type); optional `args` override so input-prompt actions work |
| `invokeObjectOp` (direct object op) | Implement as real tool `object_action` | PR advertises `tool:"object_op"` in menu entries but never registers it; registering removes the inconsistency and gives agents a no-hover object interaction |
| README decision-loop docs | Adapt | Keep upstream's loop guidance, retarget to the adopted tool set |

## 4. Findings (classification: defect / improvement / upstream-feature / intentional / blocked)

### Confirmed defects

- **F-01** `list_actions` NPE: `npc.get("name").getAsString()` throws
  `UnsupportedOperationException` on nameless NPCs (JsonNull) — kills the whole
  menu. (`describeActor` writes `name` even when null.)
- **F-02** `observe`/`list_actions` NPE: `npc.get("distance").getAsInt()` and
  `me.distanceTo(wp)` throw when `local.getWorldLocation()` is null (login/region
  load edge).
- **F-03** `observe` NPE: `composition.getName()` on inventory/ground rows if
  `getItemDefinition` returns null (unloaded defs).
- **F-04** Cross-origin control surface: `POST /mcp` accepts requests from any
  origin. A browser page can form-POST tool calls (e.g. `type_chat`, `click`) to
  `127.0.0.1:7780/mcp` with no CORS preflight. Loopback bind alone does not stop
  this. Severity: medium (local dev tool, but drive-by browser POSTs are real).
- **F-05** Unbounded request body: `readAllBytes()` on POST — any local process
  can send an arbitrarily large body. Severity: low.
- **F-06** `tools/call` with missing `params.name` → NPE wrapped as `-32603`
  instead of `-32602` with a useful message; JSON-RPC batch requests rejected
  outright (streamable-HTTP clients may send them).
- **F-07** `UpdateChecker` pulls releases from the upstream org — a fork build
  would auto-"update" to an upstream jar, clobbering fork builds.
- **F-08** `jar` task auto-deploys to every detected client sideload dir
  (`finalizedBy(deploy)`); ordinary builds overwrite jars and fail when a client
  is running. Compilation/test must be decoupled from deployment.
- **F-09** Event baselines (`lastIntValues`, `lastSkillXp`, `lastBoostedLevels`,
  `lastContainerContents`), `seenProjectiles` and combat-tick trackers persist
  across logout/world-hop — stale `inCombat` and misleading `oldValue` chains.
- **F-10** `act` (PR3): tick read off the client thread; NPC index can be reused
  by a different NPC within the staleness window; dialogue-option entries press
  number keys that hit the chatbox if the dialogue closed — needs per-kind
  revalidation.
- **F-11** `act`/`list_actions` (PR3): menu advertises `tool:"object_op"` which
  is not a registered tool — calling it via `tools/call` fails.
- **F-12** Missing required args produce NPE-style `-32603` errors instead of a
  clear "missing required argument" result.
- **F-13** `McpActivityLog` keeps full base64 screenshots for 100 entries —
  tens of MB retained per session. Bounded but heavy; cap image payload bytes.

### Confirmed improvement opportunities

- **I-01** `wait_for` lacks combat-relevant conditions: `animation`, `hitsplat`,
  `projectile`, `var`, `in_combat`, `prayer` — high value for OpenRune combat
  verification (evidence without polling screenshots).
- **I-02** No server status tool — agents can't self-diagnose (version, port,
  uptime, game state). Add `get_status`.
- **I-03** `/log` response lacks the registered tool list — dashboard filter
  only fills after calls happen. Include `tools` + server version.
- **I-04** History queries don't indicate buffer eviction — add per-channel
  `dropped` counters so truncation is explicit.
- **I-05** `initialize` still reports `flux-client-mcp`/`0.1.0` — fork identity;
  update serverInfo + instructions for the new tool set.
- **I-06** Attackers list is NPC-only — include players (private-server PvP).
- **I-07** `set_prayer` should cover Ruinous (`RP_*`) prayers via the alternate
  prayer-book interface, not just the standard book.

### Intentional behavior (not defects)

- `UNLISTED_OPS` excludes drop/destroy/use/examine — destructive or two-target
  ops shouldn't appear in an autonomous menu; `item_action` remains the escape
  hatch.
- `initialize` echoes the client's protocolVersion — loose negotiation, works
  with current clients; keep.
- Hover + 150 ms menu rebuild for live menu reads — matches client behavior
  (implemented via `pauseMs`, a scheduler delay; `Thread.sleep` is fully removed).
- `get_combat_state.inCombat` is a hitsplat-recency heuristic (8-tick window,
  Idle Notifier's rule), not authoritative server state — documented.
- `ItemIconTool` reflection: retained, documented deviation in `AGENTS.md` —
  three `ItemComposition` fields have no API accessors; the tool degrades to
  inert sliders when fields can't be reached. Sole reflection in the codebase.

### Blocked / partial

- Live verification of the NEW tools: the dev client at `127.0.0.1:7780` runs
  the baseline plugin and sits at LOGIN_SCREEN; sideloaded jars only load at
  client start. New-tool live verification needs a client restart (user action).
- Rev-238 vs rev-241 behavioral differences: API symbols all verified against
  runelite-api 1.13.4 at compile time; runtime behavior on the OpenRune client
  (e.g. custom widget ids) can only be confirmed live.

## 5. Implementation status

All changes on `devexity-mcp-improvements`, version bumped to `0.2.0`.

| Finding | Fix | Status |
|---|---|---|
| F-01/F-02/F-03 (observe/list_actions NPEs) | `has("name")`/`has("distance")` guards throughout; `me == null` handled; `getItemDefinition` null-checked | Implemented |
| F-04 (cross-origin POSTs) | `localOrigin` check: no Origin (MCP clients) or loopback Origin only | Implemented |
| F-05 (unbounded body) | 2 MiB cap → 413 | Implemented |
| F-06/F-12 (protocol errors) | `-32602` for missing `params.name`; `callTool` validates required schema args; JSON-RPC batch support; notifications → 202 | Implemented |
| F-07 (upstream auto-update) | `UpdateChecker` repointed to fork releases **and** reduced to notify-only (announce in chat + log) — removes `HttpURLConnection`, `ProcessBuilder` jar-swap, and writes outside `.runelite` | Implemented |
| F-08 (auto-deploy) | `jar` no longer finalized by `deploy`; `gradlew deploy` is explicit | Implemented |
| F-09 (stale baselines) | `McpEventRecorder.reset()` on `GameStateChanged` (not LOGGED_IN/LOADING); clears histories, baselines, combat ticks | Implemented |
| F-10 (stale act targets) | per-kind validators before dispatch: NPC index+id match, ground item on tile, dialogue type | Implemented |
| F-11 (phantom `object_op`) | registered real tool `object_action` (direct scene-object op, no hover) | Implemented |
| F-13 (log memory) | `McpActivityLog` 16 MiB image budget; oldest payloads evicted with `imagesDropped` flag shown on the dashboard | Implemented |
| I-01 (wait_for conditions) | added `hitsplat`, `projectile`, `animation`, `spotanim`, `var`, `in_combat`, `out_of_combat`, `prayer` | Implemented |
| I-02 (self-diagnosis) | new `get_status` tool (version, tool count, game state, tick, position) | Implemented |
| I-03 (log payload) | `/log` returns `tools`, `server`, `version`, `uptimeMs`; dashboard seeds filter + shows server identity | Implemented |
| I-04 (silent truncation) | per-channel `dropped` counters on every history + timeline response | Implemented |
| I-05 (server identity) | `openrune-devtools` + jar version; instructions rewritten for the tool set | Implemented |
| I-06 (player attackers) | `get_combat_state` lists NPC and player attackers | Implemented |
| I-07 (Ruinous prayers) | `set_prayer` scans both `Prayerbook` and `HorrorPrayerbooks` interfaces, matches `RP_*` names | Implemented |
| AGENTS `Thread.sleep` | all 10 sites replaced by `pauseMs` (scheduled delay, interruptible so tool cancellation still works) | Implemented |
| AGENTS `new Gson()` | unchanged — one shared `McpHttpServer.GSON` static; documented | Documented |
| PR #2 | `get_combat_state`, `set_prayer`, `item_action` direct `CC_OP`, `interactingIndex`/`targetsMe`, `inventoryActions`, `recordCombatTick` | Implemented |
| PR #3 | `observe` (optional sections), `list_actions`, `act` (tick-age + revalidation), direct object op as `object_action` | Implemented |

### Deferred

- Shop/trade-specific tools (`shop_action`, `trade_action`), world hop/logout,
  quest-var mapping, screenshot pixel-diff — worthwhile but not yet justified.
- `enter_input` in `list_actions` menus for non-dialogue input prompts beyond
  the observed `input` type.
- Rate limiting on `/mcp` (loopback-only threat model judged sufficient).
- Splitting `McpTools.java` — still one registry; no correctness benefit shown.

## 5b. Second batch (post-`fb9d9af`)

Additional tools implemented for OpenRune Server testing:

- **New history channels**: `spawn` (NPC spawn/despawn/transform via
  NpcSpawned/NpcDespawned/NpcChanged), `loot` (ItemSpawned/ItemDespawned),
  `overhead` (OverheadTextChanged) — recorded on the event bus, merged into
  `get_timeline`, cleared by `reset()`.
- **Regression primitives**: `snapshot_state` (labelled observe snapshots) +
  `diff_state` (identity-keyed deep diff returning only changed fields);
  `run_batch` (ordered multi-step calls with per-step ok/error).
- **Combat**: `get_special_attack`/`toggle_special_attack` (spec orb widget),
  `get_attack_style`/`set_attack_style` (combat-tab style widgets),
  `list_spells`/`cast_spell` (spellbook widget walk + fuzzy name match).
- **World**: `is_walkable` (collision-map flags + direction blocks),
  `get_destination`.
- **Interfaces**: `search_interfaces` (text scan of loaded widgets),
  `bank_action` (direct CC_OP on Bankmain.ITEMS / Bankside.ITEMS).
- **`wait_for`**: +`ticks`, `spawn`, `despawn`, `transform`, `loot`, `overhead`.
- 7 new recorder tests (35 total, all green); tool-list test covers all 68
  tools.

## 6. Test matrix

`./gradlew build -x jar` → **28 tests, all green** (JDK 21 toolchain, Java 11 target).

- `McpEventRecorderTest` (11): var dedupe + oldValue, buffer cap + `dropped`
  accounting, newest-first limits, chat filters, combat-tick separation,
  xp/container baselines, timeline seq ordering + channel/tick filters, reset,
  script-arg truncation.
- `McpActivityLogTest` (4): 100-entry cap, `since` pagination, error flagging,
  image-byte budget eviction + `imagesDropped`.
- `McpHttpServerTest` (13): real loopback HTTP — initialize identity, tools/list
  schema validity + expected tool names, unknown tool/method error paths,
  `-32602`, notification → 202, batch requests, malformed JSON → `-32700`,
  oversized body → 413, cross-site Origin → 403 (raw socket; HttpURLConnection
  strips restricted headers), GET → 405, `/log` payload, full `tools/call`
  dispatch of `get_client_state` on a mocked client.

Not covered by automated tests: in-client behavior of interactive tools
(hover/click paths, menu rebuilding, prayer widget), which requires a live
client — see section 8.

## 7. Commits

- `fb9d9af` "Adopt upstream PR #2/#3 with hardening; add tests and transport
  safety"
- `6d9a48c` / `af01b9b` ledger updates
- `6434c94` "Add evidence channels, state diffing, and combat/world tools"
- `e58cfb5` "Fix diff_state schema and run_batch result envelope" — fixes
  caught by live testing (see §8): `maxDistance` declared required instead of
  optional; `run_batch` result missing the MCP `content` envelope.

All pushed to `fork/devexity-mcp-improvements`. `main` (fork + local)
and `prod` remain at `8304e71` / `d3951fd` respectively.

## 8. Live verification

Deployed via `gradlew deploy` to `.rlcustom`, `.rsprox`, `.runelite`
sideloaded-plugins; verified in a live client after restart (logged in,
Lumbridge area, world 3220,3219):

| Check | Result |
|---|---|
| `initialize` | `openrune-devtools 0.2.0-local`, new instructions ✓ |
| `tools/list` | 54 tools, all new tools present, valid schemas ✓ |
| `get_status` | version, toolCount, gameState, tick, position ✓ |
| `get_combat_state` | hp 99/99, not in combat, empty prayers/attackers ✓ |
| `observe` | vitals, combat, inventory w/ item ops, NPCs, objects, ground items, dialogue — one pass ✓ |
| `list_actions` | 40-entry menu: NPC ops, `object_action` ops, takes, item ops ✓ |
| `act` stale | 5-tick-old menu rejected "call list_actions again" ✓ |
| `act(0)` Talk-to | revalidated + invoked; player walked to NPC; dialogue opened ✓ |
| `continue_dialogue` | dialogue closed ✓ |
| `set_prayer` on/off | THICK_SKIN toggled both ways; `prayers` reflects it ✓ |
| `object_action` | Door 1535 Open invoked, no hover/mouse ✓ |
| `/log` | server+version+tools+entries all present ✓ |

Second batch (commit `6434c94`, then `e58cfb5` after live-found fixes) verified
in a live client after restart (70 tools registered):

| Check | Result |
|---|---|
| `tools/list` | 70 tools, all 16 new tools present ✓ |
| `get_special_attack` | energy 100, enabled false ✓ |
| `get_attack_style` | index + Accurate/Rapid/Longrange styles ✓ |
| `list_spells` | 100 spellbook entries ✓ |
| `is_walkable` | tile flags + `walkable:true`, `blockedDirections:[east]` ✓ |
| `get_destination` | `{"moving":false}` at rest ✓ |
| `search_interfaces` | 0 matches for absent text (correct) ✓ |
| `get_loot_history` | 7 ground-item events, `dropped:0` ✓ |
| `get_spawn_history` | 52 NPC spawn/despawn/transform events, `dropped:0` ✓ |
| `get_overhead_history` | 0 events in window (none occurred) ✓ |
| `snapshot_state`/`diff_state` | diff detects NPC movement + add/remove + tick; `from:"t0"` label works (after `e58cfb5` schema fix) ✓ |
| `run_batch` | per-step ok/error; unknown tool fails cleanly with step index; proper `content` envelope (after `e58cfb5` fix) ✓ |

Deferred (not implemented): shop/trade-specific tools, world hop/logout,
quest-variable name mapping, screenshot pixel-diff.

