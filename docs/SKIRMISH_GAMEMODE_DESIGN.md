# Skirmish — Showcase Gamemode Design Document

**Status:** Handoff design doc — not yet implemented. Written to hand off to a fresh Claude Code session working in a **new, standalone repository**.

**"Skirmish" is a placeholder name.** Rename freely (plugin name, package root, world name) — used throughout this doc purely so every reference has something concrete to point at.

---

## 1. Overview

Skirmish is a fast-paced, round-based PvP showcase gamemode for the same server family as Trenched, but built as its **own standalone Paper plugin** — no compile-time or runtime dependency on the Trenched jar. It reuses Trenched's *architecture and patterns* (package-per-subsystem, `Db`/`Service`/`Command`/`Listener`, typed `ConfigManager`, IF-based GUIs, SQLite persistence, stat tracking) but is a clean-room implementation against a single fixed arena map.

Where Trenched is a multi-day, slow-burn territorial war, Skirmish is a **30-minute (configurable), single-map, gun-focused arena rotation**: players pick a team (or no team, depending on mode), gear up from a point-based loadout shop, fight through one of five gamemodes, and vote on what plays next. Think "COD lobby night" run entirely from one persistent Paper world.

### 1.1 Core pillars

- **Fast rounds.** 30 minutes default, often shorter (score-threshold end).
- **Per-life economy.** Combat points are earned and spent within a single life/round — never a long-term grind for combat power.
- **One map, many modes.** All five gamemodes run on the same arena; the world resets (block-level) between rounds so nothing persists across rounds except player-facing systems (presets, stats).
- **Guns via WeaponMechanics.** All ranged weapons are WeaponMechanics (WM) items; Skirmish owns the shop/economy/loadout layer on top.
- **Vote-driven rotation.** When a round ends (threshold or timer), a live vote GUI picks the next gamemode.

### 1.2 Non-goals (v1)

- Multiple maps / map rotation (single arena for now — see §14).
- A meaningful persistent progression currency (stubbed only — see §14.1).
- Region capture / supply lines / divisions / merit ranks — none of Trenched's territorial-war systems apply here.
- Cross-plugin integration with Trenched at runtime. (Doc conventions and *optionally* the Discord bot pattern are reused; the plugin itself is standalone.)

---

## 2. Relationship to Trenched

Skirmish is a **separate Maven project, separate plugin.yml, separate package root** (suggested: `org.flintstqne.skirmish`). Nothing in Trenched is imported. What *is* reused is the architectural pattern and, in several cases, logic ported almost directly:

| Trenched system | Reused as | Notes |
|---|---|---|
| Package-per-subsystem, `XDb`/`XService`/`XCommand`/`XListener` | Direct pattern reuse | See `CLAUDE.md` in the Trenched repo for the full writeup |
| Single concrete `XService` class (no separate interface) | Direct pattern reuse | Post-refactor Trenched convention — don't reintroduce an interface layer here either unless a second implementation is genuinely coming |
| `ConfigManager` (typed getters over `config.yml`) | Direct pattern reuse | See §7 |
| IF (`com.github.stefvanschie.inventoryframework`) for GUIs | Direct dependency reuse | Same library, same relocation-in-shade approach |
| `DeathListener` (invisible/no-effects/fly/no-phase spectator) | Ported logic | Extended with a *locked-radius* variant (in-round death) and a *free-roam* variant (end-of-round) — see §8.8 |
| `PlacedBlockTracker` (block-change tracking pattern) | Ported *approach*, lighter implementation | Skirmish doesn't need SQLite persistence for this — see §8.3 |
| `StatLogic` (`StatService`/`StatListener`, MVP calc, leaderboards) | Ported pattern | Own schema (in the shared `DatabaseManager`, not a `StatDb`), own categories (see §8.10) |
| `ScoreboardUtil` (periodic scoreboard push) | Direct pattern reuse | Live team score / K/D / points sidebar |
| `ObjectiveUIManager` compass HUD | Ported pattern | Points at nearest active KOTH/Domination zone |
| Discord bot (`discord-bot/`, `StatApiServer`) | Optional, separate reuse | Not required for v1; if wanted, stand up a second small HTTP API + a second bot instance (or extend the existing bot with a second backend URL) — do not couple it to Trenched's running instance |

---

## 3. Tech Stack & Dependencies

| Dependency | Scope | Purpose |
|---|---|---|
| Paper API (match target server's Minecraft version) | provided | Platform |
| **WeaponMechanics** | provided (soft-depend, hard requirement at runtime) | All gun/melee weapon items and damage pipeline |
| `com.github.stefvanschie.inventoryframework` (IF) | compile, shaded/relocated | All GUIs |
| `org.xerial:sqlite-jdbc` | compile, shaded | Loadout presets, stats, round history persistence |
| PlaceholderAPI | provided, optional | Scoreboard/chat placeholders (rank, points, K/D) — optional, same as Trenched's pattern |

**plugin.yml**: `depend: [WeaponMechanics, InventoryFramework]`.

> **WeaponMechanics API — resolved.** WM publishes to Maven Central as
> `com.cjcrafter:weaponmechanics` (plus `com.cjcrafter:mechanicscore`, which it needs at
> runtime and which is *not* bundled in the WM jar). Both are `provided`. The pom pins the
> version via the `weaponmechanics.version` property — keep it in step with the server's build.
>
> The API surface this project actually uses, verified identical in 4.1.0 and 4.3.1:
>
> | Need | Call |
> |---|---|
> | Weapon key → ItemStack (§7.5.2) | `WeaponMechanicsAPI.generateWeapon(String weaponTitle)` — returns null for an unknown title |
> | Is this stack weapon X (Gun Game tiers) | `WeaponMechanicsAPI.getWeaponTitle(ItemStack)` |
> | Kill attribution (§13) | `WeaponKillEntityEvent` — `getShooter()`, `getVictim()`, `getWeaponTitle()` |
> | Friendly-fire cancel for guns (§7.7) | `WeaponDamageEntityEvent` (Cancellable) |
> | Melee/knife hits (§8.5) | `WeaponMeleeHitEvent` — also exposes `isBackstab()` |
>
> A "weapon title" is the top-level key of a WM weapon `.yml` (`AK_47`, `Combat_Knife`,
> `AX_50`, …), not the display name. The titles shipping with a default WM install are the
> ones referenced by `loadout-catalog.yml`.

---

## 4. Project / Package Structure

Mirrors Trenched's convention — one package per subsystem, each with a single concrete `Service` — with one deliberate departure: there's no per-subsystem `Db` class. All SQLite access is centralized in one `DatabaseManager` (see §5):

```
src/main/java/org/flintstqne/skirmish/
├── Skirmish.java                 # Main plugin class — composition root (mirrors Trenched.java)
├── ConfigManager.java            # Typed config.yml + arena.yml access
├── DatabaseManager.java          # SINGLE owner of the SQLite connection (data.db) — see §5
├── RoundLogic/
│   ├── RoundService.java         # Round lifecycle, timer, threshold-end detection
│   ├── GamemodeType.java         # KOTH, DOMINATION, TDM, FFA, GUN_GAME
│   ├── RoundCommand.java
│   └── EndRoundSequence.java     # Winner announce → free-roam spectator → vote → next round
├── TeamLogic/
│   ├── TeamService.java          # Join/lock/imbalance/swap-incentive
│   ├── TeamSelectGui.java
│   └── TeamCommand.java          # /team — join or reopen GUI mid-round
├── MapLogic/
│   ├── ArenaConfig.java          # Spawn points, hill/capture-point pools, boundaries (arena.yml)
│   ├── WorldManager.java         # Per-round world clone/dispose + crash sweep (see §7.3)
│   └── ArenaAdminCommand.java    # In-game location-setting tools
├── LoadoutLogic/
│   ├── LoadoutService.java       # Point economy, category/tier catalog, equip logic
│   ├── LoadoutBuilderGui.java    # GUI #2
│   ├── LoadoutPresetGui.java     # GUI #3 ("My Loadouts")
│   ├── WeaponFactory.java        # WeaponMechanics item generation wrapper
│   └── LoadoutCommand.java       # /loadout, blocked in no-loadout gamemodes
├── CombatLogic/
│   ├── CombatListener.java       # Friendly-fire block, kill points, kill feed, streak callouts
│   ├── SpawnProtectionManager.java
│   └── DeathSpectatorService.java # Locked-radius (death) + free-roam (end-round) spectator
├── ObjectiveLogic/
│   ├── HillObjective.java        # KOTH
│   ├── CapturePoint.java         # Domination (shared base with HillObjective)
│   ├── ObjectiveUIManager.java   # Compass HUD, locator-bar icons, beacon beams
│   └── ObjectiveParticleManager.java
├── GunGameLogic/
│   ├── GunGameService.java       # Tier ladder, promote/demote, knife-kill win
│   └── GunGameListener.java
├── VoteLogic/
│   ├── VoteService.java
│   └── VoteGui.java              # GUI #4
├── StatLogic/
│   ├── StatService.java, StatListener.java
│   └── StatCommand.java          # /stats, /leaderboard
└── Utils/
    ├── ScoreboardUtil.java
    └── ChatUtil.java             # Kill feed, death recap, killstreak callouts
```

**No per-subsystem `Db` classes.** Every subsystem that needs persistence (`LoadoutService`, `StatService`, `RoundService`) calls into the shared `DatabaseManager` rather than owning its own `Db` class — see §5 for why.

---

## 5. Data Model / Database Schema (SQLite)

**All persistence goes through a single `DatabaseManager`** — one class owns the SQLite connection to a single `data.db` file (stored in the plugin's data folder) for the entire plugin. There is no per-subsystem `Db` class (no `LoadoutPresetDb`, `StatDb`, `RoundHistoryDb`, etc.) — that's a deliberate departure from Trenched's one-`Db`-per-subsystem convention. `DatabaseManager` owns the connection lifecycle (open on enable, close on disable), schema creation/migration for every table below, and exposes query/update methods that `LoadoutService`, `StatService`, and `RoundService` call directly. Subsystem services still own their *logic*; they just don't own their *storage*.

Two persistence tiers, matching the per-round-vs-persistent split from §1.1/§6:

### 5.1 Persistent (survives restarts, spans rounds)

```sql
-- One row per saved loadout preset
CREATE TABLE loadout_presets (
    preset_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid   TEXT NOT NULL,
    name          TEXT NOT NULL,
    slot_index    INTEGER NOT NULL,       -- position in the "My Loadouts" GUI
    primary_item  TEXT,                    -- serialized selection (weapon tier key, not the ItemStack)
    secondary_item TEXT,
    armor_item    TEXT,
    potion_item   TEXT,
    tool_item     TEXT,
    created_at    INTEGER NOT NULL
);

-- Which preset auto-equips on respawn, persists across rounds
CREATE TABLE active_loadout (
    player_uuid   TEXT PRIMARY KEY,
    preset_id     INTEGER NOT NULL REFERENCES loadout_presets(preset_id) ON DELETE SET NULL
);

-- Lifetime stats (mirrors Trenched's StatDb shape)
CREATE TABLE player_stats (
    player_uuid   TEXT PRIMARY KEY,
    player_name   TEXT NOT NULL,
    kills         INTEGER DEFAULT 0,
    deaths        INTEGER DEFAULT 0,
    knife_kills   INTEGER DEFAULT 0,       -- Gun Game
    objective_points INTEGER DEFAULT 0,    -- KOTH/Domination tick contributions
    rounds_played INTEGER DEFAULT 0,
    rounds_won    INTEGER DEFAULT 0,
    wins_by_mode  TEXT                     -- JSON blob: {"KOTH": 3, "TDM": 5, ...}
);

-- One row per completed round, for recap screens / leaderboards
CREATE TABLE round_history (
    round_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    gamemode      TEXT NOT NULL,
    winner        TEXT,                    -- team id, or player UUID for FFA/Gun Game
    started_at    INTEGER NOT NULL,
    ended_at      INTEGER,
    final_score_a INTEGER,
    final_score_b INTEGER
);
```

**Important:** store the *selection key* (weapon tier identifier from the config catalog), not a serialized `ItemStack`. The catalog (§8.5.1) is the single source of truth for what that key resolves to — this way a config change (rebalancing a weapon, renaming a tier) doesn't orphan saved presets, and presets stay tiny.

### 5.2 Ephemeral (in-memory, discarded at round end)

- Per-round point balances (`Map<UUID, Integer>`) — never touches disk.
- Block-change diff (§8.3) — never touches disk (see that section for the crash-recovery tradeoff).
- Active votes (`Map<UUID, GamemodeType>`) — reset every end-round sequence.
- Capture-point ownership/progress state.

---

## 6. Config Schema

Two files, mirroring `ConfigManager`'s typed-getter convention:

### 6.1 `config.yml` (tuning — everything gameplay-facing)

```yaml
round:
  duration-minutes: 30
  score-threshold-ends-round: true      # if false, only the timer ends a round

team:
  imbalance-lock-ratio: 1.5             # e.g. 6v4 = 1.5, locks joining the fuller team
  swap-incentive-points: 50

spawn-protection:
  invulnerability-seconds: 10
  # Zone protection only applies to gamemodes with FIXED spawns (TDM, KOTH, Domination, TeamGunGame if added later).
  # FFA / Gun Game use random spawns each death — invulnerability applies, zone radius does not.
  zone-radius-blocks: 32
  zone-no-damage: true
  zone-no-break: true
  zone-no-build: true

points:
  kill: 10
  friendly-fire-enabled: false
  # NOTE: per design decision, no diminishing-returns / repeat-kill / underdog logic here.
  # Points are per-life scoped and reset on death — that's the entire anti-snowball mechanism.

death:
  spectator-lock-radius-blocks: 50      # in-round death spectator
  respawn-seconds: 10

end-round:
  free-roam-spectator: true             # no radius lock during end-round sequence
  winner-announcement-seconds: 5
  next-round-countdown-seconds: 15

vote:
  enabled-gamemodes: [KOTH, DOMINATION, TDM, FFA, GUN_GAME]
  # gamemode weights/eligibility could be added here later if some modes should vote less often

koth:
  # Hill coordinate pool lives in arena.yml (set via /arena addhillpoint) — map data, not tuning.
  capture-radius-blocks: 8
  points-per-second: 1
  score-threshold: 300                  # team score (accumulated hold-seconds × points-per-second) that ends the round
  particle-ring: DUST                   # ring particle type + team-color/neutral tinting

domination:
  capture-point-count: 3
  # Named capture-point pool lives in arena.yml (set via /arena addcapturepoint) — map data.
  capture-radius-blocks: 6
  points-per-tick-per-zone: 1            # total round points/tick = zones_controlled * this value
  tick-interval-seconds: 1

tdm:
  score-threshold: 75                    # kills needed for a team to win

ffa:
  score-threshold: 30
  min-spawn-distance-from-players: 15

gungame:
  score-threshold-is-tier-completion: true
  weapon-ladder:                         # ordered, first = starting weapon, last = knife
    - PISTOL
    - SMG
    - SHOTGUN
    - AR
    - SNIPER
    - LMG
    - KNIFE
  demote-on-knife-death: true
  final-knife-kill-wins-instantly: true

loadout:
  starting-points: 0
  # Category → tier catalog lives in a separate section/file since it's large (see §8.5.1)

kits:
  max-saved-presets: 5

discord:
  enabled: false                         # optional, see §2
```

### 6.2 `arena.yml` (map-specific data — set via in-game admin tools, not hand-edited)

```yaml
world-name: "skirmish_arena"
boundary:
  min: {x: -100, y: 0, z: -100}
  max: {x: 100, y: 255, z: 100}

team-spawns:
  red:  [{x: -80, y: 65, z: 0, yaw: 90}]
  blue: [{x: 80,  y: 65, z: 0, yaw: -90}]

ffa-spawns:                              # random surface spawn pool for FFA/Gun Game
  - {x: 10, y: 70, z: 40}
  - {x: -40, y: 68, z: -10}
  # ... N entries, populated via /arena addffaspawn

hill-points:                             # KOTH pool, one chosen at random per round (/arena addhillpoint)
  - {x: 0, y: 70, z: 0}

capture-points:                          # Domination pool, N chosen per round (/arena addcapturepoint <name>)
  A: {x: 20, y: 70, z: 20}
  B: {x: -20, y: 70, z: 20}
```

Locations are written using Bukkit's built-in `Location` serialization (`==: org.bukkit.Location`, world/x/y/z/yaw/pitch keys) rather than the hand-rolled maps sketched above — same data, no parser to maintain, and arena.yml isn't hand-edited anyway.

Keeping `arena.yml` separate from `config.yml` matters: `config.yml` is tuning you hand-edit and diff in version control; `arena.yml` is map data you set by standing in the world and running a command — different audiences, different edit patterns.

---

## 7. Core Systems

### 7.1 Round & Gamemode Lifecycle

`RoundService` owns the state machine:

```
WAITING (pre-round, loadout selection open, players in spawn-protected bases)
   → ACTIVE (round timer running, gamemode logic live)
      → [score threshold reached] OR [timer expires] → ENDING
   → ENDING (EndRoundSequence runs — see §7.9)
   → next round begins with the voted GamemodeType → WAITING
```

`GamemodeType` drives which subsystems are active — e.g. `ObjectiveUIManager`/`HillObjective` only run during `KOTH`; `GunGameService` only during `GUN_GAME`; `TeamService` locking rules don't apply during `FFA`/`GUN_GAME` (no teams).

### 7.2 Team System

- `TeamSelectGui` (§10, mockup #1): join Red/Blue. Locked (barrier overlay) on the fuller team once `imbalance-lock-ratio` is exceeded.
- Re-evaluated continuously (not just at initial join) — if the fuller team drops back under the ratio, it unlocks automatically.
- **Swap incentive**: when locked, a bonus slot appears next to the *short* team's banner offering players on the full team a one-click switch + `swap-incentive-points` bonus. Never forced.
- `/team`: if the player has no team yet, opens the normal join GUI. If they're already on a team, reopens the same GUI in an informational state — their own team's banner is inert, but the swap-incentive slot is still live if imbalance is currently active.
- Only relevant for `TDM`, `KOTH`, `DOMINATION`. `FFA` and `GUN_GAME` skip team assignment entirely (see §9.4/§9.5).

### 7.3 Map Persistence & Revert

**Approach: one throwaway world per round.**

> Superseded the original design. This section previously specified a single long-lived arena
> world reverted by an in-memory block diff (`BlockChangeTracker`). That was replaced because
> the diff could not survive a crash — the arena stayed damaged until fixed by hand. The
> world-per-round approach makes crash recovery fall out of the design instead of being a
> feature, and it deleted the tracker outright. Same pattern SiegeGame uses.

The world named in `arena.yml` is a pristine **template**. It is loaded on enable so admins can
build in it and point `/arena` at it, but **rounds never run there.**

`WorldManager` owns the lifecycle:

1. **Round start** — copy the template folder to `skirmish_round_<n>`, skipping `session.lock`,
   `uid.dat`, and per-player junk (`playerdata`, `stats`, `advancements`). Copying `uid.dat`
   would give two worlds the same UUID, which breaks Bukkit. The copy runs off the main thread;
   `Bukkit.createWorld` then loads it on the main thread.
2. **Configure** — `setAutoSave(false)` above all: the copy is disposable, so it should never
   write back. Plus round-appropriate gamerules (immediate respawn, no mob spawning, no daylight
   or weather cycle, no death messages).
3. **Round end** — the next round's world is promoted; the one it replaces is unloaded with
   `Bukkit.unloadWorld(world, false)` and its folder deleted, after a delay so nobody is still
   inside it.
4. **Disable** — the active round world is disposed the same way. A clean shutdown leaves only
   the template.

**Crash recovery.** Round worlds are all prefixed `skirmish_round_`, so any folder with that
prefix at startup is a leftover from a run that died. `WorldManager.sweepOrphanWorlds()` deletes
them on enable. This also prevents a stale folder from colliding with a fresh round — the bug
SiegeGame's in-memory counter has, since its counter resets to 1 every boot.

Nothing is reverted because nothing durable is ever damaged. This covers everything a block diff
would have missed too: dropped items, fire spread, liquid flow, entities.

**Locations are world-bound.** `arena.yml` stores coordinates against the template, but the world
a round plays in changes every time. `WorldManager.toActiveWorld(Location)` re-binds a stored
location onto the live round world, and every consumer of arena coordinates (team spawns, spawn
zones, and later the hill/capture pools) must go through it. Reading a stored location directly
would silently point at the template.

**Costs, accepted:** `Bukkit.createWorld` blocks the main thread, so a large template means a
visible hitch at round start — keep the template trimmed to the arena region. Two copies exist on
disk briefly during handover. `/arena hardreset` is no longer needed and is dropped from §10.

### 7.4 Points / Currency System

- Per-round, per-life. `LoadoutService` holds `Map<UUID, Integer>` — reset to `starting-points` on every respawn (death or round start), **not** persisted.
- Sources: kill (`points.kill`, default 10), KOTH/Domination hold-ticks (separate team score, not spendable — see §9.1/§9.2), killstreak callouts if configured as bonus points.
- No diminishing returns, no underdog multiplier, no interaction-tracking anti-farm — explicitly out of scope per design decision. The entire anti-snowball mechanic *is* the per-life reset: your best gun is only yours until you die.
- Friendly fire is hard-disabled at the damage-event level (§7.7), so there's no friendly-fire point exploit to guard against either.

### 7.5 Loadout System

#### 7.5.1 Catalog

A config-driven catalog (separate YAML section or file — `loadout-catalog.yml` if `config.yml` gets unwieldy) mapping category → tier → `{display name, WM weapon title / item spec, point cost}`. This is the data `LoadoutBuilderGui` (§10, mockup #2) renders and `WeaponFactory` resolves at equip time. Example shape:

```yaml
categories:
  primary:
    - {key: ar15, name: "AR-15", wm-weapon: "AR15", cost: 0}      # free tier, always available
    - {key: m4,   name: "M4",    wm-weapon: "M4",   cost: 120}
    - {key: lmg,  name: "LMG",   wm-weapon: "LMG",  cost: 500}
  armor:
    - {key: vest_basic, name: "Basic Vest", item: "LEATHER_CHESTPLATE", enchants: {PROTECTION: 1}, cost: 0}
    - {key: vest_heavy, name: "Heavy Vest", item: "IRON_CHESTPLATE",   enchants: {PROTECTION: 3}, cost: 150}
  # ... potions, tools similarly
```

#### 7.5.2 `WeaponFactory`

Thin wrapper around WeaponMechanics' item-generation API — given a catalog `wm-weapon` key, produce the actual `ItemStack` to hand the player. **Exact WM API calls are an implementation-time task** (§3 note) — don't guess method names here; this doc only fixes the *contract* (`String weaponKey → ItemStack`), not the WM call underneath it.

#### 7.5.3 Equip flow

- Locking: an item the player can't afford (this life) renders with a barrier overlay (see mockup #2) and doesn't equip on click.
- Equipping updates the player's in-progress selection immediately (visual/inventory), doesn't wait for a "confirm" step — matches how the mockup's `EQUIPD SUMRY` footer item works as a live summary, not a submit button.
- `/loadout` is **blocked entirely** (no GUI opens, actionbar message: `This gamemode doesn't use loadouts.`) whenever the active `GamemodeType` doesn't use loadouts — i.e. `GUN_GAME` (fixed weapon ladder) and, if you decide FFA should also be loadout-free, `FFA` too. **Decide this explicitly per gamemode via a config flag** (`gamemode.<X>.loadouts-enabled`) rather than hardcoding which modes qualify — keeps it consistent with "everything configurable."

### 7.6 Loadout Presets ("My Loadouts")

- `LoadoutPresetGui` (§10, mockup #3): up to `kits.max-saved-presets` named presets per player, persisted in `loadout_presets`.
- Clicking a preset sets it as that player's `active_loadout` — **persists across rounds and restarts**, distinct from the per-life point economy. This is a player-comfort feature ("spawn with my usual kit"), not currency.
- On every respawn (including round start), if the player has an active preset **and** the current gamemode has `loadouts-enabled: true` **and** they can afford every item in it this life (their reset point balance), auto-equip it. If they can't afford part of it (e.g. an expensive preset item outpaces a low starting-points config), fall back to the free-tier default for that category and notify them — don't silently deny spawn gear.
- If the gamemode has `loadouts-enabled: false`, the active preset is simply not applied — no error, it just doesn't apply, matching the `/loadout` block behavior.

### 7.7 Combat Rules

- **Friendly fire**: cancel damage in `CombatListener` whenever attacker and victim share a team — hard rule, not a config toggle to disable (there's no scenario in this design where you'd want it on, so don't build a switch for it — YAGNI).
- **Kill points**: awarded in the same listener, sourced from whatever WM damage/death event correctly identifies the killer (verify this against the installed WM version — some plugins only expose last-damager via vanilla `EntityDamageByEntityEvent`, which is probably sufficient here since there's no assist system in scope).
- **Spawn protection**: `SpawnProtectionManager` grants `invulnerability-seconds` of damage immunity on every respawn. For gamemodes with **fixed** spawns (TDM/KOTH/Domination), additionally enforce a persistent `zone-radius-blocks` no-damage/no-break/no-build bubble around each configured spawn point (checked continuously, not just at respawn — someone could sprint into it later and shouldn't be buildable/attackable there either, matching "spawn camping" prevention intent). For **random**-spawn gamemodes (FFA/Gun Game), only the personal timed invulnerability applies — there's no fixed point to build a persistent zone around.

### 7.8 Death & Spectator System

Two variants of the same underlying spectator state (invisible, no potion effects/particles, can fly, cannot interact with the world or other players, cannot phase through blocks — ported directly from Trenched's `DeathListener`):

| Variant | Trigger | Radius | Duration |
|---|---|---|---|
| **In-round death spectator** | Player dies during `ACTIVE` | Locked to a 50-block sphere (`death.spectator-lock-radius-blocks`) around the death location | `death.respawn-seconds` (10s default), then respawn at team/home spawn (or a random FFA/Gun Game spawn) |
| **End-of-round spectator** | Round enters `ENDING` | Free-roam, whole arena, no lock | Duration of the winner-announcement + countdown phases (§7.9) |

Both reuse the same "no-phase" collision handling as Trenched (movement is cancelled into solid blocks even while flying) — that's a deliberate design choice from the core game, keep it.

### 7.9 End-of-Round Sequence & Voting

Exact flow (already agreed in design discussion — reproduced here as the authoritative spec):

1. **Trigger**: a team/player hits the configured score threshold, or the round timer expires.
2. **Force all players** (alive or already-dead) into the free-roam end-of-round spectator variant (§7.8).
3. **Winner announcement**: title/subtitle (`"<TEAM> WINS!"` / `"Final Score: X – Y"`, or player name for FFA/Gun Game), held for `end-round.winner-announcement-seconds`.
4. **Vote GUI** (§10, mockup #4) auto-opens for every player, live tallies, votes changeable until countdown hits 0.
5. **Countdown title**: `"Next round in: Xs"`, ticking down for `end-round.next-round-countdown-seconds`, vote GUI stays interactable throughout.
6. **World handover**: the next round clones a fresh world from the template; the world just played in is unloaded without saving and deleted (§7.3). There is no revert step.
7. **New round starts** with the winning-vote `GamemodeType`; players return to `WAITING` (spawn-protected, loadout selection open) at their (re-picked, if teams reset per mode) spawns.

### 7.10 Stats & Leaderboards

Same shape as Trenched's `StatLogic`: async-batched writes, lifetime (`player_stats`) + per-round (`round_history`) tracking, `/stats` and `/leaderboard` commands. Categories are simpler than Trenched's 35 — kills, deaths, knife kills, objective points, rounds played/won, wins-by-mode. MVP/recap calc for the winner-announcement phase can reuse Trenched's weighted-formula approach (kills × N + objective points × M), tuned via config the same way.

---

## 8. Gamemodes

### 8.1 King of the Hill (`KOTH`)

- One hill point chosen at round start from `koth.hill-point-pool` (random).
- Visualized with a circular particle ring (`koth.particle-ring`) at `capture-radius-blocks`, tinted team-color when a team holds it, neutral otherwise (matches the locator-bar/beacon visual language shared with Domination — see §8.6 shared implementation note below).
- **Capture rule**: a team "holds" the hill when it has players present inside the radius and the enemy team does not (standard KOTH contest rule — if both teams are present, the hill is contested and scores for neither). While held uncontested, the holding team earns `points-per-second` **team score** (separate from personal point economy — this is what feeds the round-end threshold, not the loadout shop).
- Round ends at `tdm`-style score threshold reuse — actually define KOTH's own threshold in config (`koth.score-threshold`) mirroring the others; add this key to §6.1 KOTH block if missing (flagged as a doc gap here — make sure implementation adds it).

### 8.2 Domination (`DOMINATION`)

- `capture-point-count` points selected at round start from `capture-point-pool` (superset, for variety across rounds on the same map).
- Each point independently contestable/capturable the same way as KOTH's hill (present + uncontested = capture progress; fully neutral → team color once captured).
- **Scoring**: `points-per-tick = number_of_zones_controlled × points-per-tick-per-zone`, evaluated every `tick-interval-seconds`. This is the confirmed "more zones = faster score, but also a bigger comeback if you lose them" dynamic — the whole point of Domination as a mode.
- **Visuals**: each point shows on the locator/waypoint bar (bossbar or vanilla locator UI, whichever the target Paper version's API supports cleanly) *and* a vertical beacon-beam-style particle column, both tinted neutral/red/blue matching current ownership. Shared rendering code between KOTH and Domination (`ObjectiveUIManager`) — don't duplicate the particle/beacon logic per gamemode class.

### 8.3 Team Deathmatch (`TDM`)

- No objectives. Fixed team spawns (`arena.yml`). Team score = kill count. First to `tdm.score-threshold` wins (or timer).
- Simplest mode — mostly exists to validate the combat/loadout/spawn-protection systems in isolation before layering objective logic on top. Good candidate for first implementation milestone (§16).

### 8.4 Free-For-All (`FFA`)

- No teams. Players spawn individually at random points from `arena.yml`'s `ffa-spawns` pool, respecting `min-spawn-distance-from-players` (don't spawn someone next to an existing fight).
- Individual score = kills. First to `ffa.score-threshold` wins; winner announcement uses their name, not a team.
- Loadouts: **enabled.** `gamemode.ffa.loadouts-enabled: true` — FFA uses the full loadout shop (§7.5) exactly like TDM/KOTH/Domination, respecting the same per-life point economy and active-preset auto-equip on respawn. Only `GUN_GAME` sets this flag to `false`.

### 8.5 Gun Game (`GUN_GAME`)

FFA-structured (no teams, random spawns), no loadout shop — driven entirely by `gungame.weapon-ladder`.

- Every player starts at ladder index 0, **always carrying that tier's gun plus a knife** (except at the final tier, which *is* the knife — no duplicate knife slot then).
- **Kill with the current gun** → promote to the next ladder index, re-equip (new gun + knife again).
- **Kill with the knife** → promote same as above (knife kills count as a normal tier-advancing kill when it's *not* the getting-demoted case below).
- **Getting knifed by an opponent** → victim is **demoted** one tier (per confirmed design: "if you are knifed to death then you are demoted"). This is the mode's signature comeback/punish mechanic.
- **Death by gun (not knife)** → no tier change; player respawns at their current tier, per "dying leaves you where you are."
- **Final tier = knife.** Landing the final knife kill (i.e., a kill *while already on the last ladder tier*) **wins the round instantly** — skip the normal threshold/timer end condition entirely, jump straight to the winner announcement (§7.9 step 3) with that player named.
- `GunGameService` owns per-player ladder index (ephemeral, round-scoped — resets every round like everything else in this gamemode). `GunGameListener` hooks kill/death events and re-equips inventory on every tier change.

---

## 9. GUIs

Full ASCII specs already agreed during design — reproduced here as the canonical reference (implementation should match these exactly; treat this section as the source of truth over anything summarized elsewhere in this doc).

### 9.1 Team Select GUI

```
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│      │      │      │ RED  │ INFO │ BLUE │      │      │      │
│      │      │      │Banner│ Book │Banner│      │      │      │
│      │      │      │12/16 │      │ 9/16 │      │      │      │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
   0      1      2      3      4      5      6      7      8
```

Swap-incentive variant — the button always offers a switch **to the short team**, and sits
beside that team's banner (slot 2 if Red is short, slot 6 if Blue is short). It's only shown to
players currently on the full team. Below: Red is locked at 12/16, Blue is short at 9/16, so the
button reads "SWITCH TO BLUE" and sits at slot 6.

```
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│      │      │      │ RED  │ INFO │ BLUE │SWITCH│      │      │
│      │      │      │Banner│ Book │Banner│  TO  │      │      │
│      │      │      │ LOCK │      │ 9/16 │ BLUE │      │      │
│      │      │      │12/16 │      │      │+50pts│      │      │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
```

`/team` opens this same GUI whether or not the player already has a team (informational-only own-banner state if already assigned).

### 9.2 Loadout Builder GUI (6 rows / 54 slots)

```
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│PRIMRY│SECNDY│ ARMOR│POTION│ TOOLS│      │      │      │ MY   │ Row 0: category tabs
│ [GUN]│[PIST]│[VEST]│[SPLSH│[FLNT]│      │      │      │LOAD- │
│      │      │      │ POT] │      │      │      │      │OUTS  │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│ AR-15│  M4  │      │      │      │      │      │      │      │ Row 1: tier grid for
│ 0pts │120pts│      │      │      │      │      │      │      │ the selected category
│ ✓EQIP│ 🔒   │      │      │      │      │      │      │      │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│Shotgn│Sniper│  LMG │      │      │      │      │      │      │ Row 2 (more tiers)
│200pts│350pts│500pts│      │      │      │      │      │      │
│ 🔒   │ 🔒   │ 🔒   │      │      │      │      │      │      │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│      │      │      │      │      │      │      │      │      │ Rows 3-4: reserved
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤ for future tiers
│      │      │      │      │      │      │      │      │      │
├──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┼──────┤
│ BACK │      │POINTS│      │EQUIPD│      │ SAVE │      │ CLOSE│ Row 5: footer
│      │      │: 240 │      │SUMRY │      │PRESET│      │      │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
```

### 9.3 My Loadouts (presets) GUI

```
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│Rusher│Sniper│      │      │      │      │      │  +   │ BACK │
│ Kit  │ Kit  │      │      │      │      │      │ New  │      │
│AR-15 │Sniper│      │      │      │      │      │Loadut│      │
│★ACTIV│      │      │      │      │      │      │      │      │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
```

Left-click = set active (persists cross-round). Right-click = rename/delete. `+ New Loadout` → opens §9.2 in "save as new" mode.

### 9.4 End-of-Round Vote GUI

```
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│      │ KOTH │ DOM  │ TDM  │ FFA  │GUNGAM│      │      │TIMER │
│      │██████│███   │████  │██    │█     │      │      │ book │
│      │ 42%  │ 21%  │ 28%  │ 6%   │  3%  │      │      │ 0:12 │
└──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘
```

Click to vote, click again to change vote, glint on your current pick.

---

## 10. Commands

| Command | Who | Description |
|---|---|---|
| `/team` | Player | Open team-select GUI (or informational + swap-incentive if already on a team) |
| `/loadout` | Player | Open loadout builder — blocked with a message in no-loadout gamemodes |
| `/loadouts` | Player | GUI-only feature — this command is simply the entry point that opens the presets GUI directly (also reachable via the `MY LOADOUTS` tab inside the builder) |
| `/stats [player]` | Player | Lifetime/round stats |
| `/leaderboard <category>` | Player | Top players |
| `/arena setspawn <red\|blue>` | Admin | Sets team spawn to current location, writes to `arena.yml` |
| `/arena addffaspawn` | Admin | Adds current location to the FFA spawn pool |
| `/arena addhillpoint` | Admin | Adds current location to the KOTH hill pool |
| `/arena addcapturepoint <name>` | Admin | Adds current location + name to the Domination pool |
| `/arena setboundary <corner1\|corner2>` | Admin | Sets arena bounding box |
| `/round start <mode>` / `/round end` | Admin | Manual override of the normal vote-driven flow, for testing |
| `/admin points give/reset <player> <amount>` | Admin | Debug/testing aid, mirrors Trenched's admin merit commands |

Permissions: `skirmish.admin` gates all `/arena` and `/admin` commands, same convention as Trenched's `entrenched.admin`.

---

## 11. QoL Features (confirmed scope)

Carried over from design discussion, minus hit markers/hit sounds (explicitly cut):

1. Pre-round warm-up buffer (`WAITING` state — spawn-protected, loadout open, visible countdown).
2. Live scoreboard/tab list (`ScoreboardUtil` pattern).
3. Kill feed (actionbar/chat, weapon + headshot/knife icon).
4. Death recap during the 10s in-round spectator window.
5. Killstreak/multikill callouts, optionally tied to bonus points via config.
6. Objective compass/waypoint HUD for KOTH/Domination (`ObjectiveUIManager` pattern).
7. End-of-round recap screen (MVP/top-fragger, reusing `StatLogic`'s calc approach).
8. Live vote GUI (§9.4) rather than chat-based voting.
9. Loadout preset slots with one-click select (§7.6) — this *is* the kit-saving feature, not a bolt-on.

---

## 12. Future Work / Deferred

### 12.1 Persistent progression currency (stub only)

You want a slot reserved for this, not a built system. Recommended shape for whoever picks this up later:

- A **separate** currency from the per-round combat points — e.g. `player_stats` gains a `persistent_currency` column, earned in small amounts per round played/won (not per kill — keep it slow and cosmetic-scale).
- Spend surface should be **cosmetic-only** (weapon skins/particle trails/kill-effects/name colors) — never combat stats, tiers, or anything that touches the per-life loadout economy. Mixing the two currencies' spend surfaces is exactly the pay-to-win trap the per-round reset was designed to avoid; keep them walled off.
- No GUI/shop design is specified here — intentionally deferred. When it's time to build this, it's a new `ProgressionLogic` package following the same `Service`/`Gui` pattern as everything else in this doc, storing its data via `DatabaseManager` like every other subsystem.

### 12.2 Other deferred items

- Multi-map support (map-select alongside gamemode-vote, per-map `arena.yml`).
- Duo/party queueing into the same team (Trenched's `PartyLogic` pattern would port directly if wanted).
- Discord bot integration (optional, see §2).

---

## 13. Open Questions

Resolved during design review: FFA uses the full loadout shop (§8.4), the KOTH `score-threshold` config key is in place (§6.1), and `/loadouts` is a GUI-only feature whose only job is opening the presets GUI directly (§10).

**Kill-attribution source — resolved.** `WeaponKillEntityEvent` carries the shooter, the victim
and the weapon title in one event, covering both point-awarding and the kill feed. Melee is
`WeaponMeleeHitEvent`. Vanilla `EntityDamageByEntityEvent` is still listened to as the
friendly-fire backstop for damage WM doesn't route. See §3 for the full API table.

No open questions remain.

---

## 14. Suggested Build Order

Not a hard requirement, but a sane milestone sequence for an implementer to avoid building objective/vote systems on top of an unproven core loop:

1. **Skeleton**: plugin boots, arena world loads, `ConfigManager`/`arena.yml` reads, admin location-setting commands.
2. **Team + spawn + spectator core**: `TeamService`, fixed spawns, spawn protection, in-round death spectator (locked-radius). No combat yet.
3. **Loadout shop + WeaponMechanics integration**: `LoadoutService`, `WeaponFactory`, builder GUI, per-life point economy. Validate WM item generation and damage attribution here — this is the highest-risk integration point.
4. **TDM end-to-end**: simplest gamemode, exercises combat + points + round-end threshold + the full end-of-round sequence (§7.9) including free-roam spectator and the vote GUI (even if only TDM is voteable initially).
5. **Loadout presets**: `DatabaseManager` preset tables/queries, presets GUI, active-loadout auto-equip on respawn.
6. **KOTH**, then **Domination** (shares most of KOTH's capture-point plumbing — build KOTH first, generalize into `CapturePoint` for Domination rather than writing Domination from scratch).
7. **FFA**, then **Gun Game** (FFA proves out random-spawn logic that Gun Game also needs).
8. **Stats/leaderboards**, **QoL polish pass** (§11 items 3-8).
9. Anything from §12 (Future Work) only after the above is solid.
