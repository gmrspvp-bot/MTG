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
   The modern UI (`-Modern`) also needs `forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar` there.
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
| `scripts\play.ps1` | Play yourself vs the AI (desktop Forge with all patches). `Play Forge.cmd` / `Play Forge (modern).cmd` launch it. |
| `scripts\tools\*.java` | The AI patches, agent hooks and launchers (below). |
| `scripts\*.ps1` | Analysis: summarise, compare (A/B), card-stats, throw-stats, board-stats, realism-stats, stuck-cards... |
| `scratch\` | Working area. In git: `*.ps1` helpers, `probe\*.java` test boards, `play-logs\game_*.log` (recorded human games), EDHREC data. Everything else there is temporary. |
| `setup\` | Files that override the Forge install (see Setup). |

## Running simulations

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-sims.ps1 `
  -Decks "Captain America v6,Doom Prevails v3,Mr House v4,Tidus v3" -Games 40 -Parallel 6
```

- Current deck versions to run: **Captain America v6, Doom Prevails v3, Mr House v4, Tidus v3,
  Deadpool v3**. Never delete or overwrite an original deck. Improvements go in a new `vN` file.
- `-JvmArgs "-Dforge.x=y"` passes switches (e.g. `-Dforge.captain.split=false`). `scratch\run-until.ps1`
  rotates pods until a deadline (`-PodJvmArgs` for A/B switches, `-Light` = 4 threads at idle priority).
- **PC load:** on the owner's PC (6 cores / 12 threads) never exceed `-Parallel 6` (about 75% CPU,
  BelowNormal priority). When the owner is using the PC, use light mode or ask first.
- A win-rate difference needs many games: 100 games is about ±7 points, 160 about ±6.
- When stopping runs, kill the driver PowerShell too (`run-until.ps1` / `run-sims.ps1`), not just
  the java processes, or the next batch starts.

## How the AI patches work

- `SimLauncher` (sims and desktop play) and `ModernLauncher` call `AiPatches.apply()`, which swaps in
  our AI classes for chosen APIs. Patches are picked with `-Dforge.aipatches=catch,counterall,threat,...`.
  run-sims.ps1 and play.ps1 pass the defaults.
- `EvalCacheAgent` (a `-javaagent`) rewrites Forge methods as classes load: the original becomes
  `<name>$orig` and calls go to a hook class. The table is `EvalCacheAgent.HOOKS`: board-eval cache,
  AttackHook, BlockHook, HumanHooks (mulligans), SacrificeHook, PlayHook (desktop games),
  ConfirmPassHook and AttackConfirmHook (human misclick safeguards). Logs show
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

## Playing and recorded games

`Play Forge.cmd` → `scripts\play.ps1`: copies decks into forge-data, sets prefs (Realistic AI,
ROWS layout, turn-safety prompts) and logs to `scratch\play-logs\`:
- `game_<time>.log` is the full game log, written when the game ends. The header lines
  `Player: X (HUMAN|AI) deck=...` show who played what.
- `console_<time>.log` holds the AI decision lines.

`scratch\play-summary.ps1 -Files <log>` prints a turn-by-turn summary of the human's turns.

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

## Git workflow

- `git pull --rebase` before starting work and before every push. The other person's Claude pushes
  here too.
- Keep commits small and focused, with a message that says what changed and why. Never force-push
  or rewrite pushed history.
- After pulling changes to `scripts\tools\*.java`, run `scripts\build-tools.ps1` before running sims
  or playing.
- Results stay out of git. Put conclusions (win rates, what changed) in commit messages or in this
  file.
