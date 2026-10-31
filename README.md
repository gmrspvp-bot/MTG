# MTG — Forge with a 3D table

A four-player Commander setup built on [Forge](https://card-forge.github.io/forge/): Forge's rules
engine and AI, with our own 3D table in front of it, runtime AI patches that make the AI play more
like a person, and a simulator for testing decks against each other.

## Playing

```
play3d
```

Or double-click `Play 3D.cmd` in Explorer. (`play3d.cmd` is the same thing without a space
in the name, so it can be typed without quoting.)

That is the whole thing. It checks what is present, builds anything missing, and starts the game.
It is safe to run every time — each step is skipped when it is already done.

**You need nothing installed.** If the machine has no JDK, the launcher fetches a portable one
(Temurin 21, about 180 MB) into `tools\jdk` and uses it from there. That is a download, not an
install: no admin prompt, no PATH change, nothing outside this folder, and deleting `tools\jdk`
undoes it. It happens once. A JDK already on the machine is used instead, and a JRE is not
enough, because this compiles the AI hooks and the 3D table from source.

An internet connection is needed the first time, and only for that. Forge itself is in here.

## Getting it

`git clone`, or **Code → Download ZIP** on GitHub and unzip it — both give a complete, working
copy. Windows marks executables that came out of a downloaded zip, so the first launch may show
"Windows protected your PC"; **More info → Run anyway**.

The 3D table is the window to play in. Forge's own window minimises itself a moment after the
game starts - the game still runs inside it, so do not close it - and comes back by itself if
Forge ever needs it (or press **H** in the 3D table). Controls are in
[`ui3d/README.md`](ui3d/README.md).

## Updating

Close Forge, then double-click **`Update.cmd`**. It checks that Forge is closed and GitHub is
reachable, brings this copy up to the latest version, and rebuilds. Your settings, card pictures
and recorded games are kept.

- A **git clone** updates with git (your changed settings are put aside and back).
- A **downloaded zip** copy downloads the latest zip and copies it over itself (a few hundred MB,
  a couple of minutes). It remembers which version it has, so running it again when nothing is
  new is quick.

`Update.cmd -Check` only says whether there is an update.

| | |
|---|---|
| `play3d` | Set up if needed, then play in the 3D table |
| `Update.cmd` | Update from GitHub (git clone or zip copy) and rebuild; close Forge first |
| `play3d -Strict` | Also say on screen whenever Forge answers something the 3D table should own |
| `Play 3D (both windows).cmd` | Both windows side by side, with every grab of attention logged. Turns the auto-minimise of Forge's window off, which is right for watching what steals focus and wrong for ordinary play - hence its own file. Afterwards: `scriptsttention-report.ps1` |
| `Play Forge.cmd` | Forge's own window only |
| `scripts\setup.ps1 -CheckOnly` | Report what is missing and stop |
| `scripts\setup.ps1 -Rebuild` | Force a recompile |

## Why Forge is committed here

Forge itself is in this repository rather than downloaded, which is unusual and deliberate.

A clone runs without fetching anything — no release to find, no version to match. More importantly,
**Forge cannot change underneath us**. Every deck, every AI patch, every custom card script and all
1,440 games of league results were tested against this exact build. A newer Forge arriving by
accident could change card behaviour or AI decisions with nothing in the history to explain it.
Pinning it makes a Forge upgrade a deliberate commit that can be seen, reviewed and reverted.

`.gitattributes` marks that tree `-text` so git never rewrites line endings in the card scripts —
a clone gets byte-identical files to the install everything was tested against.

## What is here

| Path | What |
|---|---|
| `ui3d\` | The 3D table. See its README for controls, architecture and what is left. |
| `decks\` | Forge decks, originals plus improved versions. |
| `scripts\` | Setup, play, simulation runs and analysis. |
| `scripts\tools\` | The AI patches and agent hooks, loaded into Forge at runtime. |
| `forge-data\` | Forge's user data: custom card fixes, card images, preferences. |
| `scratch\play-logs\` | Recorded games. |
| `scratch\bug-reports\` | Reports filed from the 3D table's bug cog, with the board attached. |

## Working on it

Two people work on this repo, each with their own Claude. Read [`CLAUDE.md`](CLAUDE.md) before
changing anything, and claim work in [`CLAIMS.md`](CLAIMS.md) first — it exists because git only
reveals a collision after both sides have written the code.
