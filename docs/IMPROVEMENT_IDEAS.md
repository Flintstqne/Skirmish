# Skirmish — Improvement Ideas (Theorycrafting)

Every gamemode and §11 QoL item in `SKIRMISH_GAMEMODE_DESIGN.md` is now implemented (§14
build order complete), but nothing has been verified on a live server yet, and a from-scratch
build inevitably leaves gaps. This doc is a brainstorm of 30 candidate follow-ups, ranked
roughly by how load-bearing they are. Nothing here is committed scope — it's a menu, not a plan.
Rough effort tags: **S** (hours), **M** (a session), **L** (multi-session).

---

## Tier 1 — known gaps and correctness risks

1. **Live-server verification pass (L).** Every gamemode and the entire §11 QoL pass has only
   been unit-tested for pure logic. Nothing about the tick loops, particle rendering, title
   countdowns, scoreboard, or kill feed has run against real players. This is the single highest-
   value next step — everything else on this list is speculative until this happens.

2. **`objective_points` is dead code (S).** `StatService` and the `player_stats` schema both
   have a column for it, but nothing calls the increment method — crediting it correctly needs
   `HillObjective`/`DominationObjective`'s tick loop to know *which players* are standing in a
   held zone, not just whether the zone is held. Either wire it up or drop the column; a stat
   that always reads zero is worse than no stat.

3. **Team swap incentive points are never granted (S).** `TeamService.swapToShortTeam`'s own
   doc comment admits `config.getSwapIncentivePoints()` is configured but never awarded. If team
   imbalance is a real problem in practice, this is a one-line `loadoutService.addPoints` call;
   if it isn't, delete the config key and the comment instead of leaving a stub.

4. **No in-place preset rename (M).** `LoadoutPresetGui` only supports set-active/delete;
   presets are stuck with their save-time "Loadout N" name. Needs a chat-capture listener or
   AnvilGUI — cheap once picked, but genuinely absent today, not just polish.

5. **Mid-round joiners get a broken HUD (S).** `ObjectiveUIManager` doesn't track players who
   join after a KOTH/Domination round has started — no compass, no boss bar until the next
   round. A `PlayerJoinEvent` hook that re-runs the same registration `applyGamemodeRules`
   already does at round start would close this in under an hour.

6. **Crash mid-warmup leaves players geared with no round (S).** `RoundState.WAITING` now spans
   a real countdown window (`scheduleWarmupTick`). If the server crashes or is force-stopped
   during it, `worlds.sweepOrphanWorlds()` cleans up the world, but nothing verifies players get
   put somewhere sane on the next boot if they reconnect before a round starts. Worth an explicit
   "no active round" spawn fallback.

---

## Tier 2 — performance and scale

7. **Scoreboard rebuild is O(players) full-rebuild every tick interval (S). — done.**
   `ScoreboardService` now keeps one `Objective` per player for the session (cached in a map,
   cleared on `PlayerQuitEvent`) instead of allocating a new `Scoreboard`/`Objective` and calling
   `player.setScoreboard` every tick. Each update also diffs the line list against what was sent
   last time and returns immediately if nothing changed — most ticks during `WAITING`/a static
   score do zero work now instead of a full board reassignment.

8. **Spawn-zone lookup re-reads `arena.yml` per damage event (S). — no change, by design.**
   Re-reading the item's own text: it explicitly says "don't preemptively fix an unmeasured
   cost," and that's the right call here too — the existing `ponytail:` comment in
   `SpawnProtectionManager` already documents the ceiling and the upgrade path (cache,
   invalidated on `/arena setspawn`) for whoever picks this up once profiling actually shows it.
   Adding a cache now would be exactly the speculative complexity ponytail mode argues against.

9. **World-per-round clone is a full file copy every round (M). — no change, by design.**
   Same shape as #8 — the item text itself says "worth revisiting only if copy time is measured
   as a real problem," and the fix it sketches (reusing an undisposed previous copy) is a real
   architectural change to `WorldManager`'s lifecycle, not a small patch. Not worth building
   against a cost nobody's measured yet, especially before the Tier 1 live-server pass has even
   established whether round-start feels slow in practice.

10. **Particle rendering (ring/beam) has no distance culling (S). — investigated, no change
    needed.** The premise didn't hold up: `ObjectiveParticleManager.spawn` calls
    `World#spawnParticle(Particle, Location, int)` with no explicit receiver list, which Bukkit
    already scopes to players near the effect location internally (it's not a real broadcast to
    every online player, unlike this doc's original wording implied). Adding a second, hand-rolled
    distance filter on top would just be re-implementing what the engine already does, for the
    same particle counts KOTH/Domination already draw once a second.

11. **DatabaseManager is a single shared SQLite connection (M, architectural). — no change, by
    design.** Conditioned explicitly on "if leaderboard queries or preset saves ever show up in
    a `/timings` report" — that hasn't happened, and won't be knowable until Tier 1's live-server
    pass runs with `/timings` actually watched. Building a background writer thread now would be
    solving a problem that may not exist at this plugin's write volume.

---

## Tier 3 — gameplay/balance

12. **No compass/waypoint for FFA or Gun Game (S).** `ObjectiveUIManager`'s HUD pattern only
    fires for KOTH/Domination. FFA and Gun Game have no equivalent "nearest enemy" indicator —
    not specified in the design doc, but worth floating since the pattern to build it already
    exists. -- SKIP THIS ONE

13. **Killstreak thresholds are global, not per-gamemode (S). — done.** `KillstreakService.onKill`
    now takes a `RoundService` and returns immediately for Gun Game — `GunGameListener`'s tier
    progress already communicates streak-like progression, so a second counter on top was just
    differently-paced noise. Thresholds stay global for every other mode; nothing else about the
    map changed.

14. **No comeback/anti-stomp mechanic beyond the per-life reset (M, deliberately deferred).**
    The design doc explicitly rules out diminishing returns and underdog bonuses for the combat-
    point economy (§7.4) — that's a considered decision, not a gap, and shouldn't be revisited
    without the same discussion that produced the original rule. -- SKIP THIS ONE

15. **FFA/Gun Game spawn selection doesn't account for line-of-sight, only distance (S). — no
    change, by design.** Its own text says this is "only worth revisiting if playtesting surfaces
    actual spawn-kill complaints" — no playtesting has happened yet (Tier 1 item 1 is still
    open), so there's nothing to react to. Distance-only spawn logic stays the lazy-but-correct
    default until there's a real complaint to fix.

16. **Gun Game's knife-demotion has no floor protection against griefing at tier 1 (S). — done.**
    `GunGameService.isFloorTier` (tier 0) is checked before `demote` in `GunGameListener`; a
    knife kill against a tier-1 player now sends them a gray actionbar explaining why nothing
    changed instead of silently no-op'ing.

---

## Tier 4 — persistence and stats

17. **Leaderboards have no time-windowing (M). — no change, real scope.** Still needs either a
    rolling snapshot table or timestamped stat deltas, and there's no request for it — building
    a new persistence shape speculatively would be exactly the kind of unrequested feature
    ponytail mode argues against. Left as a menu item for whenever a season/weekly-reset
    actually gets asked for.

18. **No per-round replay/history browsing (M). — done, adjusted scope.** `/history [count]`
    now reads `round_history` (`DatabaseManager.getRecentRounds`, `StatService.getRecentRounds`).
    One correction from the original wording: this doc said "your last N rounds," but
    `round_history` has no per-player link (§5.1's schema was never given a
    round-to-player join table) — so `/history` shows the *server's* most recent rounds
    (gamemode, score, winner, timestamp), not a per-player filter. Building the per-player
    version would mean a new join table, which is really Tier 4 item 17's problem
    (time/scope-windowed stats) wearing a different name — not attempted here.

19. **Stats have no data-retention or reset story (S). — done.** `/admin stats reset
    <player|all>` now exists (`DatabaseManager.resetPlayerStats`/`resetAllStats`,
    `StatService.resetStats`/`resetAllStats`). No season/rollover automation — just the manual
    escape hatch the item asked for.

20. **`WinsByMode`'s hand-rolled serializer has no migration path (S). — done.** `serialize` now
    stamps a `v1:` prefix; `parse` strips it if present and falls back to the original bare-JSON
    shape otherwise, so rows written before this existed still read correctly. Whoever adds a
    `v2` shape later has a branch point instead of a flag day.

---

## Tier 5 — ops, admin tooling, observability

21. **No `/skirmish reload` for `config.yml` (S). — done, different command name.** `/admin
    reload` now calls the existing `ConfigManager.reload()` (which now also re-runs Tier 6 item
    27's validation pass). Folded into `/admin` rather than a new `/skirmish` command — one
    fewer `plugin.yml` entry, and it's an admin action like everything else already there.

22. **No admin visibility into round state beyond `/round status` (S). — done.** `/admin
    diagnostics` prints gamemode, round state, whether warmup is ticking
    (`RoundService.isWarmupActive`, new), seconds remaining, and the `PLAYABLE` list.

23. **No metrics/telemetry hook (M). — no change, by design.** Its own text already frames this
    as conditional ("worth flagging if this is ever meant to run on a public server") — it isn't
    one today, and adding bStats now would be a new dependency for a question nobody's asked.

24. **Discord integration is a config stub only (M, explicitly deferred in doc §12.2). — no
    change, as instructed.** The item's own text already says to leave it alone unless the user
    wants it; still true.

---

## Tier 6 — testing and code health

25. **Zero integration coverage for the round state machine's *sequencing* (M). — no change,
    real scope.** Adding MockBukkit is a new test dependency and a real infrastructure decision
    (which parts of Bukkit to mock, how far the fake server's fidelity needs to go) — the kind
    of call this pass makes deliberately rather than unilaterally. Recorded here as the
    strongest remaining recommendation in the whole doc; worth raising explicitly next time
    rather than doing silently.

26. **No test for the warm-up → active transition added this session (S). — done.**
    `RoundService.isFinalWarmupTick(int)` is now a pure static method (same pattern as
    `pickLeader`/ladder arithmetic), with `RoundServiceTest` covering the boundary (`1` is
    final, `0`/negative are final, `2` isn't). `cancelWarmup`'s null-check no-op wasn't split out
    — there's no branch in it worth a dedicated test beyond what the compiler already guarantees.

27. **`ConfigManager` getters have no schema validation (S). — done.** `ConfigManager.validate()`
    runs on construction and on `reload()`, checking every numeric/collection getter that has a
    real invariant (positive durations, non-negative radii, a non-empty weapon ladder, etc.) and
    logging every anomaly found in one pass instead of one at a time in production. It can't
    catch key-name typos (a mistyped key just silently keeps the default — `FileConfiguration`
    gives no way to distinguish "absent" from "misspelled"), only out-of-range values once read.

28. **No `mvn checkstyle`/static analysis wired into the build (S). — no change.** Adding a
    Maven plugin means picking a ruleset, which is a standards decision for the user to make,
    not something to bolt on unilaterally mid-cleanup-pass. Flagged here as a recommendation.

---

## Tier 7 — future scope (already flagged, not new)

29. **Persistent progression currency (L, explicitly stubbed in doc §12.1).** Separate cosmetic-
    only currency, deliberately walled off from the per-round combat economy. Design doc already
    specifies the shape; don't start this without the user asking, since it's explicitly deferred
    scope, not a gap.

30. **Multi-map support / duo-party queueing (L, explicitly deferred in doc §12.2).** Same
    category as #29 — real scope, already acknowledged as future work, not something to start
    speculatively.

---

## If asked to pick one

**#1 (live-server verification)** first, always — no amount of theorizing about the rest of this
doc is worth much until the QoL pass and all five gamemodes have actually run in front of
players. Everything else in Tiers 1–6 that could be done without that verification pass has now
been done or explicitly evaluated and left alone; what's left standing is either genuinely
gated on playtesting (#8, #9, #11, #15, #23), a real-scope feature nobody's asked for yet (#17,
#25, #28), or explicitly out of scope (#24, and all of Tier 7). #25 (MockBukkit for
`RoundService`'s state machine) is the strongest of the "ask before doing" group if a next pass
happens.

---

## Status after Tiers 1–6

Every item across Tiers 1–6 has a resolution noted inline: done, evaluated-and-skipped-by-design,
or (for #25/#28) flagged as a real decision to raise explicitly rather than make unilaterally.
Tier 7 was left untouched — it's explicitly future scope from the design doc, not part of this
pass. Build is clean, 101 tests pass (was 97 before Tiers 3–6 added warm-up-tick coverage), and
none of this has been verified on a live server — see #1.
