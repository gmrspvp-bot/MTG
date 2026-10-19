# ui3d — our own 3D table

A 3D view of a Commander pod: four seats round a table, you at the near edge, with a camera that
sits low behind your own board and lifts to look down on the whole table when combat or targeting
starts. Hovering a card opens an enlarged, readable panel of its details.

This is the second owner's project and it is **in progress**. Forge's own UI still runs the game.

## Running it

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-ui3d.ps1      # demo data, no Forge
powershell -ExecutionPolicy Bypass -File scripts\build-ui3d.ps1    # compile only
.\scripts\play.ps1 -Ui3d                                           # beside a real game
```

### Controls

| Key | What |
|---|---|
| `LEFT` / `RIGHT` | Walk round the table to the next player's seat |
| `DOWN` (or `HOME`) | Back to your own seat, and follow the turn again |
| `TAB` | Toggle seated / overhead |
| `ESC` | Give the camera back to the game (it lifts by itself for combat and targeting) |

By default the camera follows the turn: as each player becomes active it walks round to their
seat. Stepping manually with the arrow keys takes over; `DOWN` hands it back.

In the demo a fake clock runs a turn every twelve seconds, passing round the table, with combat in
its second half, so both the walk round and the lift can be watched without playing a real game.

## Why LibGDX

Forge ships `forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar`, which is Forge's own libGDX
UI built for desktop. It carries libgdx, the LWJGL3 backend, the native libraries, FreeType and a
default font. So the whole 3D stack was already in the repo: nothing is downloaded and nothing is
installed outside this folder.

Java rather than Python or a browser for one reason: Forge's engine talks to a UI through
`IGuiGame` and `IGuiBase`, 153 in-process methods passing live Forge objects, many of them
*blocking* decisions ("choose targets", "order these triggers"). In another language all of that
has to be implemented anyway **plus** a serialising bridge. In the same JVM there is no bridge.

## How it is put together

| File | What |
|---|---|
| `Snapshot.java` | Plain immutable data for the renderer. No Forge imports at all. |
| `StateReader.java` | Forge `Game` → `Snapshot`. Only ever called on the game thread. |
| `Bridge.java` | Subscribes to the game's event bus, publishes snapshots, opens the window. |
| `Table3D.java` | The view: table, seats, cards, camera, picking, hover panel. |
| `Demo.java` | Fake data + a `main`, so the view can be developed without Forge. |

The concurrency story is the whole reason for `Snapshot`: LibGDX renders on its own thread while
Forge mutates its `Game` on the game thread. The renderer never walks Forge's object graph. A
snapshot is built on the game thread when something happens and handed over complete, through one
volatile field.

`Demo` matters more than it looks: it means a rendering problem can never be confused with a
problem in the bridge, and the view starts in two seconds instead of a whole game.

## Still to do

- **The stack.** Wanted as a floating bar across the middle of the table, filling left to right as
  spells go on it. Not started.
- Basic lands have no cached art and stay coloured blocks; a generated land texture would fix it.
- Tap/untap is instant, not animated.

## Stages

1. **Read-only table** — *done*. Renders the live game, camera modes, hover-to-inspect.
2. **Camera and presentation** — real card art, tapping animation, attack arrows, better table.
3. **Take over interaction** — implement `IGuiGame` decision by decision, falling back to Forge's
   UI for anything not yet built.
4. **Lobby and deck selection** — `IGuiBase`, Commander only, reading the existing `decks\*.dck`.

Stage 3 is the large one. It is also the point at which this stops being a viewer and starts being
a client, so it should not begin until stages 1–2 feel right to play alongside.

## Known limits right now

- Card art comes from Forge's own picture cache (`forge-data\cache\pics\cards`), which only holds
  cards Forge has had reason to download - a few hundred, not all of Magic, and usually no basic
  lands. A card with no cached picture stays a coloured block, by kind: land, creature, commander,
  other. The HUD prints `art N loaded / M cached`; if M is 0 the cache was not found at all.
- Graveyard, exile and library are pads with counts beside each seat, not browsable piles. The
  stack is not drawn at all yet.
- Nothing is clickable. Hover shows details; all input still goes to Forge's window.
- The camera's seated pose is a fixed position, not a true first-person head position.

## Hidden information

The snapshot only ever contains the hand of the player this client controls - `StateReader` does
not read anyone else's - so walking round to another seat cannot reveal their cards, because they
were never sent to the renderer. Your own hand stays anchored at your own seat rather than moving
with the camera, so from another player's seat you see your cards across the table, from the back,
and they can never be mistaken for theirs. The overlay also names whose seat you are in.

Other players' hands are drawn as face-down backs, one per card. How many cards someone is
holding is public knowledge, so it is shown; what they are is not, and the renderer is never told
them. The centre tracker shows life, poison and commander damage, all of which are public too,
which is why it can sit in the open where everyone's view of it is the same.
