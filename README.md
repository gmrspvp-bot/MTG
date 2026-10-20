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

**You need a JDK 17 or newer** and nothing else. Not a JRE: the launcher compiles the AI hooks and
the 3D table from source. If one is missing, the script says so and gives you the command to
install one.

Two windows open. The 3D table is the one to play in; Forge's own window sits behind it and still
answers anything the 3D table does not handle yet, so leave it running. Controls and the current
state of that work are in [`ui3d/README.md`](ui3d/README.md).

| | |
|---|---|
| `play3d` | Set up if needed, then play in the 3D table |
| `play3d -Strict` | Also say on screen whenever Forge answers something the 3D table should own |
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
