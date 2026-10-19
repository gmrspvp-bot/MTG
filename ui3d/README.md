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

TAB toggles the camera between seated and overhead; ESC gives the camera back to the game. In the
demo, combat flips on and off every eight seconds so the transition can be watched on its own.

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

## Stages

1. **Read-only table** — *done*. Renders the live game, camera modes, hover-to-inspect.
2. **Camera and presentation** — real card art, tapping animation, attack arrows, better table.
3. **Take over interaction** — implement `IGuiGame` decision by decision, falling back to Forge's
   UI for anything not yet built.
4. **Lobby and deck selection** — `IGuiBase`, Commander only, reading the existing `decks\*.dck`.

Stage 3 is the large one. It is also the point at which this stops being a viewer and starts being
a client, so it should not begin until stages 1–2 feel right to play alongside.

## Known limits right now

- Cards are coloured blocks, not card art. Colour is by kind: land, creature, commander, other.
- Only the battlefield and your own hand are drawn; graveyard, exile and the stack are counts only.
- Nothing is clickable. Hover shows details; all input still goes to Forge's window.
- The camera's seated pose is a fixed position, not a true first-person head position.
