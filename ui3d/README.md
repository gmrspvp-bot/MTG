# ui3d — our own 3D table for Forge

A 3D view of a Commander pod: four seats round a table, a camera that sits low behind the seat it
is looking from and lifts to an overhead view for combat and targeting, card art from Forge's own
cache, and hover-to-inspect on every card.

**Status: stage 2, read-only.** Forge's own Swing UI still runs the game. This window shows it and
cannot affect it. Nothing here is clickable yet.

This is the second owner's project (Xenil / `journeybeforepancakes`). Either Claude may work on it;
this file is meant to be enough to pick it up cold.

---

## Running it

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-ui3d.ps1      # demo data, no Forge at all
powershell -ExecutionPolicy Bypass -File scripts\build-ui3d.ps1    # compile only
.\scripts\play.ps1 -Ui3d                                           # beside a real game
```

`run-ui3d.ps1 -Rebuild` compiles first. **Always use `run-ui3d.ps1` while developing** — it starts
in about two seconds on invented data, so a rendering bug can never be confused with a bug in the
bridge to Forge, and you never have to play to combat to see the combat camera.

The demo runs a fake clock: a turn every twelve seconds passing round the table, combat in the
second half of each turn, the active player's creatures attacking. That exercises the turn-follow
camera, the overhead lift and the attack markers without a game.

### Controls

| Key | What |
|---|---|
| `LEFT` / `RIGHT` | Walk round the table to the next seat |
| `DOWN` (or `HOME`) | Back to your own seat, and follow the turn again |
| `S` | Open the search panel on demo data (temporary, until stage 3 drives it) |
| `TAB` | Toggle seated / overhead |
| `ESC` | Give the camera back to the game |

The camera follows the turn by default. Arrow keys take over; `DOWN` hands it back.

### Switches

| Property | Effect |
|---|---|
| `-Dforge.ui3d=true` | Opens the 3D window (set by `play.ps1 -Ui3d`) |
| `-Dforge.ui3d.pics=<dir>` | Override where card art is read from |

---

## How it is put together

| File | What |
|---|---|
| `Snapshot.java` | Plain immutable data for the renderer. **No Forge imports.** |
| `StateReader.java` | Forge `Game` → `Snapshot`. Only ever called on the game thread. |
| `Bridge.java` | Subscribes to the game's event bus, publishes snapshots, opens the window. |
| `CardArt.java` | Finds and loads card pictures from Forge's cache. |
| `Table3D.java` | The view: table, seats, cards, camera, picking, hover panel, overlays. |
| `SearchPanel.java` | The animated panel every search opens into. Presentation done; not yet wired to the game. |
| `FaceMaker.java` | Draws a card face for anything with no cached picture. Pure AWT. |
| `FacePreview.java` | `main` that renders a sheet of generated faces to a PNG, no GL context needed. |
| `Demo.java` | Fake data + a `main`, so the view runs without Forge. |

### Threading — read this before changing anything

Three threads, and the split is deliberate:

- **Forge's game thread** mutates `Game`. `StateReader` runs here, from a Guava `@Subscribe`
  event handler, and builds a complete `Snapshot`.
- **LibGDX's render thread** runs `Table3D`. It only ever reads the latest finished `Snapshot`
  through one `volatile` field. **It must never touch a Forge object.**
- **Art worker threads** decode JPEGs. They hand back `Pixmap`s through a concurrent queue.

The rule that bites: **OpenGL objects may only be created on the render thread.** `CardArt.update()`
is called once per frame from `render()` and turns at most two decoded `Pixmap`s into `Texture`s.
That per-frame budget is what stops a board full of new cards stalling a frame. If you move texture
creation anywhere else it will fail or crash.

`Snapshot` has no Forge imports on purpose: that is what lets `Demo` drive the identical view with
invented data.

### How it attaches

`PlayHook.attach()` (in `scripts\tools\`) calls `mtg3d.Bridge.attach(game)` **by reflection**, when
`-Dforge.ui3d=true`. Reflection is deliberate: `scripts\tools` is compiled by `build-tools.ps1`
against the desktop jar only, and must not need libGDX on its classpath. A missing or unbuilt 3D
view logs one line and the game carries on.

Two independent builds, two output folders:

- `scripts\build-tools.ps1` → `scripts\tools\classes` (agent hooks, needs the desktop jar)
- `scripts\build-ui3d.ps1` → `ui3d\classes` (this, needs desktop **and** mobile jars)

Both are gitignored. **Re-run both after pulling.**

---

## Why LibGDX, and why Java

Forge ships `forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar` — its own libGDX UI built for
desktop. That jar carries libgdx, the LWJGL3 backend, the native libraries, FreeType and a default
font (`lsans-15.fnt`; note **not** `arial-15`, which newer libGDX dropped). So the whole 3D stack
was already in the repo. Nothing is downloaded and nothing is installed outside this folder, which
is a standing constraint from the owner.

Java rather than Python or a browser for one reason: Forge's engine talks to a UI through
`IGuiGame` (105 methods) and `IGuiBase` (48), in-process, passing live Forge objects, and many are
*blocking* decisions — "choose targets", "order these triggers", "assign combat damage". In another
language all 153 have to be implemented anyway **plus** a serialising bridge and a synchronous
round-trip for every decision. In the same JVM there is no bridge at all.

`forge.GuiMobile` in the mobile jar is a complete worked `IGuiGame` implementation. **Read it before
starting stage 3** — it shows how Forge expects each callback answered.

---

## Gotchas already paid for

Please do not rediscover these.

- **Card stripes were z-fighting, not aliasing.** Cards lie almost flat and the art quad sat 0.004
  above the coloured body box, with a camera of `near 0.1 / far 300`. Depth precision is governed by
  the near/far **ratio**, and 3000:1 left nothing at table distance. Fixed three ways: the range is
  now `0.5 / 120`, the offset is `0.03`, and **the body is not drawn at all when there is art** —
  two near-coplanar surfaces for no visual gain was the real mistake. Anisotropic filtering was
  added too (correct for grazing angles, but it was not the cause).
- **Texture orientation.** `ModelBuilder.createRect` puts the top of the image along the
  `corner11`/`corner01` edge. That edge is placed at `-Z` so a card reads the right way up to
  whoever is sitting in front of it. Change the corner order, not the UVs.
- **Generated faces are checked by eye, not by running the game.** `FaceMaker.draw()` is kept free
  of libGDX so `java -cp ui3d\classes mtg3d.FacePreview out.png` renders a sheet of them to a PNG.
  That is how the name/mana-cost collision and the overflowing watermark were caught. Use it.
- **The art cache is sparse.** `forge-data\cache\pics\cards\<SET>\<Name>.fullborder.jpg`, only what
  Forge has had reason to download — about 420 cards, and **no basic lands**. A miss is normal and
  leaves a coloured block. The HUD prints `art N loaded / M cached`; **M = 0 means the cache was not
  found at all**, which is the first thing to check.
- **Seat angles run anticlockwise**, so stepping `+1` in index order moves the camera *left* on
  screen. The arrow keys are mapped to what you see, not to index order.
- **Keep the orbit angle unwrapped.** It is a continuous float, and each new target is matched to
  its nearest equivalent (plus or minus 2 pi), so last-seat-to-first turns the short way instead of
  unwinding all the way round.

---

## Hidden information

This is handled **structurally**, not by hiding things late, and it must stay that way.

`StateReader` only reads the hand of the player this client controls
(`PlayerControllerHuman.isGuiPlayer()`). Walking round to another seat cannot reveal their cards
because they were never put in the `Snapshot`. Your own hand is anchored at your own seat rather
than following the camera, so from another seat you see your cards across the table from the back.

What *is* shown, because it is public: how many cards each player holds (face-down backs, one per
card), life, poison, commander damage, and all zone counts.

> **Stage 3 warning.** The moment this starts driving decisions, "read the GUI player's hand" is no
> longer sufficient — it must move to the engine's real per-player views (`PlayerView`). A
> host-side or hotseat game will otherwise leak, exactly as `ConfirmPassHook` did on 2026-10-20
> (it was asking about *both* humans and showed the host a counterspell in the other player's
> hand; fixed by gating on `isGuiPlayer()`).

---

## Forge APIs this uses

Saved so you do not have to `javap` them again.

| Need | Call |
|---|---|
| Players | `game.getRegisteredPlayers()` |
| Zones | `player.getCardsIn(ZoneType.Battlefield/Hand/Library/Graveyard/Exile)` |
| Life, poison | `player.getLife()`, `player.getPoisonCounters()` |
| Commander damage | `player.getCommanderDamage()` gives `Iterable<Map.Entry<Card,Integer>>`, per source commander |
| Turn / phase / priority | `game.getPhaseHandler()` then `.getPlayerTurn() .getPhase() .getPriorityPlayer() .getTurn()` |
| Attackers | `game.getCombat().getAttackersAndDefenders()` — a card only knows it is attacking while combat exists |
| Card facts | `getName() getType() getManaCost() getOracleText() getNetPower() getNetToughness() isTapped() isLand() isCreature() isArtifact() isEnchantment() isPlaneswalker() isCommander()` |
| The stack | `game.getStack()` iterates `SpellAbilityStackInstance`, then `.getSourceCard() .getStackDescription() .getActivatingPlayer() .isSpell()` |
| Is this our seat | `((PlayerControllerHuman) player.getController()).isGuiPlayer()` |

`javap -classpath <desktop jar> <class>` is how all of these were found; there is no Forge source
tree in this repo.

---

## TODO

Roughly in priority order. Claim anything bigger than a quick fix in the repo-root `CLAIMS.md`
before you start, and push the claim before writing code.

### Known risks — look at these before building more on top

- [ ] **Closing the 3D window may kill Forge.** `Lwjgl3Application` ends when its window closes.
      It runs on a daemon thread, but if libGDX calls `System.exit` on shutdown it would take the
      whole JVM — and the game — with it. **Untested.** Open a game with `-Ui3d`, close the 3D
      window, and check Forge survives. If it does not, the window needs its own close handler.
- [ ] **A second game in the same session.** `Bridge.start()` opens the window once per process,
      but `attach()` subscribes a new `Listener` per game and every listener writes the same
      `latest` field. After a match ends and another begins, the old game's listener is probably
      still alive and fighting the new one. Needs the bridge to drop the previous listener, or to
      ignore events from a game that is over.
- [ ] **Texture memory has a cap but no eviction.** `CardArt` stops loading at 400 textures and
      then simply shows blocks. A long session with many distinct cards will hit it. Needs LRU
      eviction keyed on what was drawn recently.

### Stage 2 — presentation (current)

- [x] Card art from Forge's cache
- [x] Typed bands: creatures / other permanents / lands
- [x] Per-player life monitors, zone pads with counts
- [x] Opponent hands as face-down backs
- [x] Floating stack bar — above the table centre, filling left to right in cast order, laid out
      along the viewed seat's local X so it stays broadside to the camera from every seat
- [x] Generated faces for any card with no cached picture (covers basic lands, which are never
      cached and are most of the table) — name, type line, mana cost, P/T, tinted by kind
- [ ] **Tap/untap animation.** Currently an instant 90 degree snap. Needs `Drawn` persisted between
      frames, keyed by card id — see *Known limits*. Doing this unlocks every other animation.
- [ ] **Card movement.** Cards teleport between zones. Once `Drawn` persists, interpolate from the
      old position when a card changes row or zone.
- [ ] Attachments: equipment and auras should sit visibly against what they are attached to.
      `Card.getAttachedCards()` / `getAttachedTo()`.
- [ ] Power, toughness and counters drawn on the card, not only in the hover panel.
- [ ] Tokens visually distinct from real cards (they have no art and never will).
- [ ] The commander, shown in a command zone rather than only when on the battlefield.
- [ ] Arcs from each attacker to the player or planeswalker it is attacking.
- [ ] Highlight whose priority it is — the snapshot already carries `hasPriority` and nothing uses it.
- [ ] Phase shown on the table itself, not only in the corner text.
- [ ] Hover panel should show the card picture at full size, not just wrapped text.
- [ ] Seat pads are large plain slabs that dominate the table; smaller and darker.
- [ ] Mouse camera: drag to orbit, wheel to zoom, so the fixed poses are not the only options.
- [ ] Remember window size and position between runs.
- [ ] Face-down and morphed cards must render as backs — a correctness issue, not cosmetic.
- [ ] A hanging "stadium" scoreboard, as an addition to the four centre monitors rather than a
      replacement.

### Stage 3 — taking over interaction

The large one. Implement `IGuiGame` decision by decision, falling back to Forge's Swing UI for
anything not yet built, so the game is always playable mid-way through the work.

- [ ] **Move hidden information to `PlayerView`** before anything else here. "Read the GUI player's
      hand" is fine for a viewer and is *not* fine for a client. See the warning above.
- [ ] Read `forge.GuiMobile` first — it is a complete worked implementation of the same interface.
- [ ] Decide how input is routed while both UIs exist: two windows, one game thread, and only one
      of them should be answering a given decision.
- [ ] Click a card to select; click again to cast or activate.
- [ ] Target selection in 3D, with the arcs already needed for combat.
- [ ] Declare attackers and blockers by dragging.
- [ ] Mana payment, including which lands to tap.
- [ ] Ordering triggers and the stack.
- [ ] Choosing from a list (search a library, order a graveyard, modal spells). **The panel is
      already built** — `SearchPanel`, with a `min/max` pick and a `Result` callback. What is left
      is calling it from the `IGuiGame` methods instead of letting them fall through to Forge.

### Stage 4 — lobby and deck selection

- [ ] `IGuiBase`, enough to start a game without Forge's screens.
- [ ] Read the existing `decks\*.dck`; Commander only, as that is all we play.
- [ ] Four-player pod setup with AI opponents and the `Realistic` profile.
- [ ] Network lobby — the owners play over Radmin LAN, so a joining player needs an **OPEN** slot
      (see the root `CLAUDE.md` on multiplayer).

### Stages

1. **Read-only table** — *done*. Live game, camera modes, hover-to-inspect, art, zones, trackers.
2. **Presentation** — *in progress*. Stack bar, land art, animation, polish.
3. **Take over interaction** — implement `IGuiGame` decision by decision, falling back to Forge's
   UI for anything not yet built. **The large one**, and where the hidden-information warning above
   applies.
4. **Lobby and deck selection** — `IGuiBase`, Commander only, reading the existing `decks\*.dck`.

Stage 3 is where this stops being a viewer and becomes a client. It should not begin until 1–2 feel
right to sit in front of.

---

## Known limits right now

- Nothing is clickable. Hover shows details; all input goes to Forge's window.
- `Table3D.layout()` rebuilds every card's placement **every frame**. At these counts that is far
  cheaper than tracking changes and means the view can never drift out of step with the snapshot —
  but it is why there are no animations, since nothing persists between frames. Animation needs
  `Drawn` keyed by card id across frames. Do that deliberately; do not "optimise" the rebuild away
  without replacing the guarantee.
- Graveyard, exile and library are pads with counts, not browsable piles.
- The seated camera is a fixed pose, not a true first-person head position.
- Basic lands have no art (see TODO).

---

## Conventions

Same as the rest of the repo — see the root `CLAUDE.md`. In particular: pull `--rebase` before
working and before every push; never delete or overwrite an original deck; UTF-8 **without BOM**;
and re-run `build-tools.ps1` **and** `build-ui3d.ps1` after pulling anything that touches them.
