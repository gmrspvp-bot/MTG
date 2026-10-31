# MTG Forge Commander simulator

Automated four-player Commander testing with MTG Forge 2.0.15, plus runtime AI patches that make
Forge's AI play more like a human (and like the repo owner in particular), and a setup for playing
against that AI with every game recorded.

Two people (each with their own Claude) push to this repo. See **Git workflow** before committing.

## Setup on a new machine

Not in git (too big or generated): Forge itself, sim results, compiled classes, the agent jar.

1. **Java:** BellSoft Liberica JDK 17 at `C:\Program Files\BellSoft\LibericaJDK-17` (not on PATH;
   `scripts\run-sims.ps1`, `scripts\play.ps1` and `scripts\build-tools.ps1` use that path).
2. **Forge 2.0.15:** extract the Forge 2.0.15 release into `forge-installer-2.0.15\` in the repo
   root, so `forge-installer-2.0.15\forge-gui-desktop-2.0.15-jar-with-dependencies.jar` exists.
3. **Copy the two changed Forge files** from `setup\forge-installer-2.0.15\` over the install:
   - `forge.profile.properties`: points Forge's user data and cache at `..\forge-data\` (repo-relative).
   - `res\ai\Realistic.ai`: our AI profile (Forge's Default tuned for 40-life Commander), used by
     every run.
4. **Build:** `powershell -ExecutionPolicy Bypass -File scripts\build-tools.ps1` compiles
   `scripts\tools\*.java` into `scripts\tools\classes` and builds `scripts\tools\evalcache-agent.jar`.
   Re-run it after pulling any change to `scripts\tools\*.java`.

Many `scratch\*.ps1` helper scripts hard-code `C:\Users\dmara\Claude`. Adjust them if the repo lives
elsewhere. The `scripts\` ones use paths relative to the repo.

## Layout

| Path | What |
|---|---|
| `decks\*.dck` | Forge decks. Originals plus improved versions (`X v2`, `v3`, ...). See `decks\README.md`. |
| `manabox\` | The owner's card collection export (ManaBox CSV) and original deck lists. |
| `forge-data\` | Forge user data: `custom\cards\` (AI fixes as custom card scripts), `decks\commander\` (copies made by play.ps1), `preferences\`. |
| `scripts\run-sims.ps1` | Run N games of a 4-deck pod in parallel; writes `results\<timestamp>\` (summary.txt, games.csv, batch logs). |
| `Update.cmd` / `scripts\update.ps1` | For friends: preflight (Forge closed, GitHub reachable), then update a git clone (fast-forward pull, local changes stashed and restored) or a zip copy (latest GitHub zip copied over, settings/cache/JDK kept, `scripts\tools` and `ui3d\src` mirrored so deleted sources go), then `setup.ps1 -CheckOnly -Rebuild`. Zip copies remember their version in `tools\update-version.txt`. |
| `scripts\play.ps1` | Play yourself vs the AI (desktop Forge with all patches). `Play Forge.cmd` launches it. (A modern/mobile-UI launcher was tried and removed 2026-10-17: not what the owner wanted.) |
| `ui3d\` | **In progress.** Our own 3D table UI (second owner's project). LibGDX, taken from Forge's own `forge-gui-mobile-dev` jar, so nothing is downloaded. `scripts\build-ui3d.ps1` compiles it, `scripts\run-ui3d.ps1` opens it on demo data with no Forge running, and `play.ps1 -Ui3d` opens it beside a real game. Stage 1 is read-only: Forge's UI still drives play. See `ui3d\README.md`. Its TODO is split into **attended** (needs someone playing - play flow, where a failure is invisible from here) and **unattended** (looks, layout, anything a probe or a rendered PNG can prove). Pick unattended work when the owner is not around. |
| `scripts\make-jarvis-skin.ps1` | Builds the `Jarvis` skin (navy/cyan/amber) into the Forge install from `default`. Forge reads its whole palette from single pixels at x=70 in `bg_splash.png`, so this recolours the entire desktop UI without touching code. `play.ps1 -Skin Default` keeps Forge's own look. |
| `scripts\tools\*.java` | The AI patches, agent hooks and launchers (below). |
| `scripts\*.ps1` | Analysis: summarise, compare (A/B), card-stats, throw-stats, board-stats, realism-stats, stuck-cards... |
| `AI-TODO.md` | The AI and house-rules to-do list (the 3D table's is in `ui3d\README.md`, card rules bugs in `CARD-MECHANICS.md`). |
| `scratch\` | Working area. In git: `*.ps1` helpers, `probe\*.java` test boards, `play-logs\game_*.log` (recorded human games), EDHREC data. Everything else there is temporary. |
| `setup\` | Files that override the Forge install (see Setup). `match-roomy.xml` is an optional match layout for four-player games (bigger own field, narrower side columns); copy it over `forge-data\preferences\match.xml` with Forge closed, since Forge rewrites that file on exit. The preference files there are `skip-worktree`, so each machine keeps its own. |

## Running simulations

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-sims.ps1 `
  -Decks "Derand - Captain America v7,Derand - Doom Prevails v3,Derand - Mr House v4,Other - Tidus v3" -Games 40 -Parallel 6
```

- **Deck files are named `<owner> - <deck>`** since 2026-10-30 (owner's request), in the file name and
  in the `Name=` Forge's picker shows, so the picker groups by owner; `Other` means unclaimed. Who owns
  what is in `decks\README.md`. Older notes below use the pre-rename names. Sim logs carry the deck name,
  so new `results\` runs carry the prefix too.
- Current deck versions to run: **every deck in `decks\`** (owner, 2026-10-26: "add all the other
  decks as well to the sims") - 18 on that date: Captain America v7, Doom Prevails v3, Mr House v4,
  Tidus v3, Deadpool v3, Baylen (the owner's), the second owner's Angels, Olivia, Scion and
  Tinybones, the friends' Alec, Eivor, Eldrazi, Frodo, Rin and Seri, and Claudes Neyali, Caelorna,
  Caesar. `scratch\league-all.ps1` builds balanced pods of all of them (each round two decks sit
  out in turn; with 18 decks, 36 pods and every deck in 8) and runs them through run-until.
  Older notes below name the nine decks used before then. Captain America v7
  became the default on 2026-10-18; on 2026-10-19 10:55 the owner had the first v7 deleted and
  the v8 deck renamed to v7. So "Captain America v7" in results before that moment (e.g. the
  2026-10-19 overnight league, 22.3%) is the **first** v7 (in git history), and "v8" results are
  today's v7. Results before 2026-10-18 used v6. Older versions, originals included, were moved to `decks\archive\` on 2026-10-22 (owner's request, so Forge's deck picker shows only current decks; their Forge copies went to `forge-data\decks-archive\`). Never delete a deck.
  **Captain America changes now go straight into `Derand - Captain America v7.dck`** (owner's request,
  2026-10-19: no v8); git history keeps earlier states, and `manabox\decks\captain america v7.txt`
  is kept in sync. Other decks still get a new `vN` file for improvements.
- `-JvmArgs "-Dforge.x=y"` passes switches (e.g. `-Dforge.captain.split=false`). `scratch\run-until.ps1`
  rotates pods until a deadline (`-PodJvmArgs` for A/B switches, `-Light` = 4 threads at idle priority).
- **PC load:** on the owner's PC (6 cores / 12 threads) never exceed `-Parallel 6` (about 75% CPU,
  BelowNormal priority). When the owner is using the PC, use light mode or ask first.
- A win-rate difference needs many games: 100 games is about ±7 points, 160 about ±6.
- When stopping runs, kill the driver PowerShell too (`run-until.ps1` / `run-sims.ps1`), not just
  the java processes, or the next batch starts.

## How the AI patches work

- `SimLauncher` (sims and desktop play) calls `AiPatches.apply()`, which swaps in
  our AI classes for chosen APIs. Patches are picked with `-Dforge.aipatches=catch,counterall,threat,...`.
  run-sims.ps1 and play.ps1 pass the defaults.
- `EvalCacheAgent` (a `-javaagent`) rewrites Forge methods as classes load: the original becomes
  `<name>$orig` and calls go to a hook class. The table is `EvalCacheAgent.HOOKS`: board-eval cache,
  AttackHook, BlockHook, HumanHooks (mulligans), SacrificeHook, PlayHook (desktop games),
  ConfirmPassHook and AttackConfirmHook (human misclick safeguards), DiceIgnoreHook (house rule:
  a die an "ignore the lowest" effect drops still triggers "whenever you roll" cards, except the
  card whose own ability rolled; see `CARD-MECHANICS.md`), ActionableCardsHook (retries Forge's pay-mana
  highlight when the board changes under it, instead of Forge's crash window), TrackerSyncHook (makes
  Forge's delayed-change list safe for the host's network sync to read while the game writes it),
  ImageScaleHook (no picture instead of an error when Forge scales one under 3x3),
  VillainousChoiceHook (rule 701.55b: a villainous choice offers only options that player can do),
  ConcedeHook (house rule: a human who concedes sits out, passing everything, until their next upkeep,
  then leaves; see `CARD-MECHANICS.md`), MassSelectHook (proliferate's "Mine" button also picks the
  player's own counters), AutoPassHook (logs who arms "auto-pass to end of turn", refuses Forge's
  menu shortcut while the 3D table runs, and when an auto-pass is called off with a question open,
  shows that question again instead of Forge's blank prompt). Logs show
  `EvalCacheAgent: hooks installed in <class>`.
  - **Pitfall:** `scripts\tools\classes` also holds `EvalCacheAgent*.class`, and the classpath copy
    wins over the jar. build-tools.ps1 keeps both in sync.
- `SimRunner` replaces Forge's sim loop and attaches `BoardRecorder`, which writes
  `Information: BOARD ...` snapshot lines that the analysis scripts read.
- Each AI decision prints a tagged line: THROWAI, EQUIPAI, ATTACKAI, TARGETAI, BLOCKAI, COUNTERAI,
  SACAI, MULLAI. Decision lines print while a game runs; the game's event log prints after it
  ends, so match them per game, not by line distance.
- Card-level AI fixes are custom card scripts in `forge-data\custom\cards\`. The rules text is
  unchanged; only the AI hints change, or abilities are split up so the AI can use them (e.g.
  Captain America's Throw is split by mana value).
- Captain America policy (`AiPatches.CaptainPolicy`) is tuned to the owner's play, learned from
  their recorded games in `scratch\play-logs\`:
  - **Throws:** exact lethal with overflow to the next player; deathtouch split wipes (Nazgûl
    Battle-Mace stays on, an MV-3 piece is thrown as 1 damage each); the Excalibur/Zirda loop
    (throw at faces, re-equip); throws in main phase 1 when Catch will re-attach the gear.
  - **Attacks:** goes for commander-damage kills.

### Captain America switches (state on 2026-10-18)

| Switch | Default | Meaning |
|---|---|---|
| `forge.captain.split` | `true` | Split throws kill 2-3 creatures; deathtouch-aware (1 damage each). |
| `forge.captain.protthrow` | `user` | A throw may unattach protection only in our main phase while other protection stays on (`legacy` / `never` = old behaviour / never). |
| `forge.captain.throwtiming` | `main` | Non-lethal throws only in our main phases, an opponent's attack, or the end step before our turn (`any` = old: the AI threw 38% in its upkeep). |
| `forge.captain.faceslot` | `false` | **Under test.** Throws of 6+ damage kill the best 1-2 creatures and put the rest on a face. Overnight A/B started 2026-10-18 19:40 (`scratch\faceslot-night-runs.txt`). |

Test results behind these defaults (Cap v6 vs Doom v3 / House v4 / Tidus v3):
- **Split on/off** (440 games each): 17.7% vs 19.3% wins (±3.6), so no real difference, but split ON
  plays far more like the owner. Kept on.
- **Overnight 2026-10-16/17** (760 games each): A = split 20.3%, B = + protthrow 21.7%,
  C = + main timing 22.2% (±3). C was closest to the owner, so it became the default.
- **All Cap versions, original to v6** (160 games each, 2026-10-14): 16-20%, no clear difference.
- **All-deck league, current Captain America v7 + the 2026-10-19 AI fixes** (24 of 36 balanced
  pods, ~920 games): Angels 48.4%, Cap v7 35.8% (±4.7), Scion 27.5, Doom 22.3, Mr House 20.8,
  Deadpool 20.5, Olivia 19.8, Tidus 14.3, Tinybones 14.2. On the same pods that morning the first
  v7 had 22.5% and Angels 53.2%. Runs: `scratch\league-v7e-runs.txt`.

**The owner's throw style** (16 recorded games, `scratch\throw-style.ps1`): 3.0 throws/game,
54% multi-target, 48% face-only, 31% at commanders, 42% deathtouch splits, main 1 44% / main 2 35%,
never in upkeep. The AI default throws about 1.5 per game with about 24% face-only; that gap is
what `faceslot` targets. Compare a run with:
`scratch\throw-style.ps1 -Files <batch logs> -Player "Captain America v7"`, or no `-Player` for
recorded games (it reads the `(HUMAN)` header).

When learning from the owner's games: a **misclick** (they meant something else) means learning
the intended play; a **misplay** (meant, but a mistake) must not be copied into the AI. Before
judging their blocks or attacks, check flying, menace, goad and similar.

## Playing and recorded games

`Play Forge.cmd` → `scripts\play.ps1`: copies decks into forge-data, sets prefs (Realistic AI,
ROWS layout, turn-safety prompts) and logs to `scratch\play-logs\`:
- `game_<time>.log` is the full game log, written when the game ends. The header lines
  `Player: X (HUMAN|AI) deck=...` show who played what.
- `console_<time>.log` holds the AI decision lines.
- A `Result Override:` line after the `Player:` headers means the recorded result is wrong (a bug decided the
  game); count the game as that line says, not as `Game Result:` does.
- **Multiplayer:** the HOST's PlayHook records every player, other humans included. A player
  who JOINS a game gets `game_<time>_joined.log` from `ClientRecorder` (wrapped in by ClientHook,
  3D on or off). The host never sends clients its game log, so this one is rebuilt from the
  host's views: same line formats (Turn, Phase, Land, Add To Stack, Combat, Life, Zone Change,
  BOARD), but there are no damage, mulligan or resolve lines and other players' hands are hidden.
  `-Dforge.clientrecord=false` turns it off. Test: `scratch\probe\ClientRecorderTest.java`.

`scratch\play-summary.ps1 -Files <log>` prints a turn-by-turn summary of the human's turns.

`scripts\deck-style.ps1` compares every deck's human games with its sim games (matched by
commander) and writes suggestions to `scratch\deck-style\` (index.md shows new games per deck).
This is the per-deck learning routine: run it after games, filter noise and misplays, bring the
rest to the owner, build only what they approve. See `AI-TODO.md`.

In-game helpers for the human:
- a "YOUR TURN" banner;
- "Pass anyway?" / "Skip combat anyway?" confirmations;
- a counterspell reminder;
- a "Restart this phase" button (GameSnapshot rollback, with an equipment re-attach fix).

Don't use the GRID multiplayer layout: it crashes Forge with "Gap was not filled".

## Conventions and pitfalls

- Edit Java/PowerShell files with an editor or tool that writes **UTF-8 without BOM**. Windows
  PowerShell 5.1 `Set-Content`/`Out-File` add a BOM that javac rejects.
- PowerShell variable names are case-insensitive (`$l` and `$L` are the same variable).
- Forge words commander-damage losses "has lost due to accumulation of 21 damage from generals".
  Match that as well as "has lost because".
- Known Forge loops: Barbarian Class plus dice cards, and Mr House token doubling. run-sims has a
  watchdog (`-ClockSeconds`).
- Test boards: `scratch\probe\*.java` (set up a board with `GameState`, then call AI code directly).
  Run them from `forge-installer-2.0.15\` with the Forge jar and `scripts\tools\classes` on the
  classpath.
- PowerShell splits `-Dforge.x.y=z` at the dots when it is passed bare to `java`. Put it in a variable
  (`$d = '-Dforge.captain.faceslot=true'; & java $d ...`).
- In Windows PowerShell 5.1, double quotes inside a here-string passed to `git commit -m` break the
  arguments. Write the message to a file and use `git commit -F <file>`.

## Git workflow

- `git pull --rebase` before starting work and before every push. The other person's Claude pushes
  here too.
- Keep commits small and focused, with a message that says what changed and why. Never force-push
  or rewrite pushed history.
- After pulling changes to `scripts\tools\*.java`, run `scripts\build-tools.ps1` before running sims
  or playing.
- Results stay out of git. Put conclusions (win rates, what changed) in commit messages or in this
  file.
- Forge rewrites `forge-data\preferences\*` whenever UI settings change, which blocks
  `pull --rebase`. On a machine where that gets in the way, run
  `git update-index --skip-worktree forge-data/preferences/forge.preferences forge-data/preferences/match.xml`
  (undo with `--no-skip-worktree`).
- New recorded games (`scratch\play-logs\game_*.log`) are committed so both Claudes can analyse them.
- **Claim anything bigger than a quick fix in `CLAIMS.md` before writing code**, in its own
  `claim:` commit, pushed straight away — a claim that has not reached the remote cannot be seen by
  the other agent. Release it by deleting the row when the work lands. Full rules in that file.
