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

The demo is driven by keys, not a clock. A demo that marches through turns on its own looks
nothing like a game: you can never stop on the step you wanted to look at, and the board changes
under you while you are reading it. Attackers appear at declare-attackers, get blocked at
declare-blockers, and the stack fills there too, so each part can be stopped on and studied.

### Controls

| Key | What |
|---|---|
| `LEFT` / `RIGHT` | Walk round the table to the next seat |
| `DOWN` (or `HOME`) | Back to your own seat, and follow the turn again |
| `S` | Open the search panel on demo data (temporary, until stage 3 drives it) |
| `SPACE` / `X` | **When a question is on screen** - answer yes / no |
| `SPACE` | **Demo only** - step to the next phase |
| `ENTER` | **Demo only** - skip to the next player's turn |
| `BACKSPACE` | **Demo only** - step back a phase |
| middle-drag | Grab the table and move it; `DOWN` recentres |
| `SPACE` | **In a game** - pass priority / confirm (guarded) |
| `X` | **In a game** - cancel (guarded) |
| left-click | **In a game** - play the card under the cursor, if it is marked playable |
| red cog, top right | Report a bug - type what went wrong, ENTER saves it with the board state attached |
| `G` | Pick up the card in your hand under the pointer; `LEFT`/`RIGHT` (or the mouse) place it, `SPACE` drops it, `ESC` puts it back |
| `H` | Hand the decision the game is waiting on back to Forge's window |
| `TAB` | Toggle seated / overhead |
| `ESC` | Give the camera back to the game |
| right-drag | Orbit freely round the table (stops following the turn) |
| mouse wheel | Zoom (when the search panel isn't open); `DOWN` resets |

The camera follows the turn by default. Arrow keys take over; `DOWN` hands it back.

### Switches

| Property | Effect |
|---|---|
| `-Dforge.ui3d=true` | Opens the 3D window (set by `play.ps1 -Ui3d`) |
| `-Dforge.ui3d.pics=<dir>` | Override where card art is read from |
| `-Dforge.ui3d.nofocus=false` | Let Forge's prompt buttons take keyboard focus again (they raise its window) |
| `-Dforge.ui3d.strict=true` | Say on screen whenever Forge answers a decision the 3D window does not own yet |
| `-Dforge.ui3d.shots=<dir>` | Save the frame as `shotNNN.png` every `forge.ui3d.shotEvery` seconds (default 0.5). For checking the view without looking at the screen: an agent can't see the window, and Windows screen capture comes out white for this GL surface. |

---

## How it is put together

| File | What |
|---|---|
| `Snapshot.java` | Plain immutable data for the renderer. **No Forge imports.** |
| `StateReader.java` | Forge `Game` → `Snapshot`. Only ever called on the game thread. |
| `Bridge.java` | Subscribes to the game's event bus, publishes snapshots, opens the window. |
| `CardArt.java` | Finds and loads card pictures from Forge's cache. |
| `Table3D.java` | The view's entry points: `create`, `render`, `resize`, `dispose`, screenshot mode. The top of the chain below. |
| `TableBase.java` | **The view, split 2026-10-21** into a chain of abstract classes, each extending the one above it in this list. Base: all fields, `Drawn`/`Pose`, geometry and maths helpers. |
| `TableCamera.java` | Seats, orbit, lift (seated/overhead), zoom: `updateCamera`, `setViewSeat`, `step`. |
| `TableLayout.java` | `layout()` and every `place*` / `add`; attachments, command zone, hands, zones; the `ModelInstance` pool; the table surround. |
| `TableScene.java` | 3D drawn after the cards: combat and target arcs (`ribbon`), the hanging scoreboard. |
| `TableHud.java` | Every 2D overlay: phase panel, seat/zone labels, centre tracker, stack labels, card chips, prompt bar, yes/no banner, event log, game over, hover card. |
| `TableInput.java` | `handleInput`, clicks on cards / seats / pads / buttons, `pick`. |
| `SearchPanel.java` | The animated panel every search opens into. Presentation done; not yet wired to the game. |
| `DeckLibrary.java` | Reads `decks\*.dck` for the lobby. Text parse, no card database. |
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

> **Resolved 2026-10-20** - `StateReader` now defers to `CardView.canBeShownTo(viewer)`. The
> warning is kept because it is the reason that mattered.
>
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

- [x] **Declaring blockers is a table, not arrows on the board** (2026-11-01, the owner: blocking
  "aint working"; attacking is fine). `BlockPanel` opens on `InputBlock`: attackers down the
  left, one row each, with what they are attacking and the blockers already in front of them;
  your creatures down the right. Drag one onto a row to block, drag it off to take it back.
  Power and toughness on every creature, and hovering a name pops the card out full size beside
  the table. Attacking keeps the board drag, which works - the two are different shapes, one
  arrow per attacker against several of yours onto one of several identical attackers.
  The panel decides nothing: rows are rebuilt from the snapshot every frame and it calls back
  into `TableInput.blockWith` / `unblockCard`, so Forge's two-click `InputBlock` model stays the
  only thing that assigns a block and a block Forge refuses never appears. **V** puts it aside
  and gives the board drag back. Visible in the demo (`block`), which was wired at the same time
  so it can be looked at without a four-player game. **Not yet used in a real game.**

Roughly in priority order. Claim anything bigger than a quick fix in the repo-root `CLAIMS.md`
before you start, and push the claim before writing code.

### Known risks — look at these before building more on top

- [x] **Closing the 3D window does not kill Forge** (checked 2026-10-20). libGDX's `Lwjgl3Application` never calls `System.exit`: closing the window runs `glfwTerminate` and ends only the `mtg3d` thread. `scratch\probe\Ui3dCloseTest.java` opens the window beside a Swing frame standing in for Forge, closes it, and opens a second one in the same JVM: the frame stays up both times. `Bridge.start()` now reopens the window with the next game if the player closed it (it used to open once per process, ever).
- [x] **A second game in the same session** (fixed 2026-10-20). Forge has no `unsubscribe`, so `Bridge` keeps the `current` game and every `Listener` ignores events from a game that is no longer current. `scratch\probe\Ui3dRematchTest.java`: after a rematch, an event from the old game leaves the new game's snapshot in place.
- [x] **Texture memory** (fixed 2026-10-20). `CardArt` now drops the least recently drawn
      textures once near its cap, on the render thread, with a grace period of 600 frames so a
      card hidden behind the search panel is not thrown away. The cap also had to be enforced
      when the texture is *created*, not only when the load is queued: a fresh board queues
      everything at once while the cache is still small, and the count sailed past the cap as
      those completed. The probe `Ui3dEvictTest` asks for 500 distinct faces then only 12 -
      peak 400, settling to 284.

### Stage 2 — presentation (current)

- [x] Card art from Forge's cache
- [x] Typed bands: creatures / other permanents / lands
- [x] Per-player life monitors, zone pads with counts
- [x] Opponent hands as face-down backs
- [x] Floating stack bar — above the table centre, filling left to right in cast order, laid out
      along the viewed seat's local X so it stays broadside to the camera from every seat
- [x] Generated faces for any card with no cached picture (covers basic lands, which are never
      cached and are most of the table) — name, type line, mana cost, P/T, tinted by kind
- [x] **Tap/untap animation** (2026-10-20). `Table3D` keeps a `Pose` per card id (where it was last drawn) and eases it towards the pose `layout()` computes from the snapshot each frame, so a tap is a quarter-turn over about 0.25 s. `layout()` is still rebuilt every frame, so the view still always converges on the true state. Rates: `TURN_RATE`, `MOVE_RATE`.
- [x] **Tapped cards no longer overlap** (2026-10-20). `placeRow`/`placeBand` give a tapped card a `CARD_H`-wide slot.
- [x] **Card movement** (2026-10-20). Same mechanism: a card keeps its id from hand to stack to battlefield, so it slides between them instead of teleporting. A card that leaves the view is forgotten, so one coming back appears where it is. A second copy of an id in one frame (an ability on the stack shares its source's id) is drawn at its target without disturbing the first.
- [x] **Attachments** (2026-10-20): equipment, auras and fortifications leave their row and lie under their host, each peeking 0.4 further out towards the table centre (in the host seat's frame, so a tapped host does not push them under its neighbour). They turn and slide with the host; an aura on an opponent's creature sits under that creature. Needs the host's id, so `CardV.attachedToId` was added (filled in `StateReader.card()`). `placeAttachments` in `Table3D`.
- [x] **P/T and counters on real-art cards** (2026-10-20): `drawCardBadges` puts a chip with the current P/T over the printed P/T box, and the counters (first kind, `+` if more) stacked above it. Pinned through the card's drawn transform, so they follow taps and moves; skipped when the card is under 34 px wide on screen, and for attachments lying under a host.
- [x] Tokens marked on their generated face (they have no printing and never will)
- [x] **Command zone** (2026-10-20): a bronze pad on each seat's left (mirroring library/graveyard/exile on the right) holding that player's command zone - the commander between casts, emblems, a companion; partners side by side. Labelled "COMMAND  tax +N" from `PlayerView.getCommanderCast`. Cards are placed with `add()`, so a commander slides home and back out (it keeps its id). Only drawn in a commander game. New: `SeatV.command`, `CardV.commanderCasts`, read for every seat in `StateReader` (public zone); `placeCommandZone` in `Table3D`.
- [x] **Attack arcs** (2026-10-20): `drawCombatArcs` in `Table3D` draws a flat ribbon over the table from each attacker to its target (seat pad, or the planeswalker/battle itself), with an arrowhead and pulses running towards the target. Unblocked attacks are bright red-orange; a blocked one fades, and each blocker gets a blue arc onto its attacker. Both ends follow the eased card poses. `Demo` fills `combatArcs` (one attacker gets blocked 2 s into combat). Checked via screenshot mode.
- [x] **Priority highlight** (2026-10-20): the seat holding priority gets a lit pad (`seatPriorityModel`) and "> priority" by its name.
- [x] ~~**Phase on the table**~~ (2026-10-20; removed 2026-10-25 at the owner's request - it covered the board; the phase is in the corner panel and the bottom bar): written in gold between the centre monitors and the active player's creatures.
- [x] **Hover panel shows the card picture at full size** (2026-10-20): real cached art is drawn large beside the cursor (5:7, up to 62% of the window height), on whichever side has room, with a strip under it for live state the print cannot show: current P/T, counters, attached to, attacking/blocking/tapped/token, stack text. Drawn faces (uncached cards, face-down backs) keep the text panel, via `CardArt.hasPicture`. The panel now draws after every label so nothing bleeds through. `-Dforge.ui3d.hover=<card name>` forces a hover for screenshot checks.
- [x] **Seat pads** smaller (7.6 x 3.4, was 9.2 x 4.2) and darker (2026-10-20).
- [x] **Mouse camera** (2026-10-20): right-drag orbits freely (left is kept for stage 3 clicks), wheel zooms 0.55-1.7x when no panel has the wheel, DOWN / HOME resets both. Not exercised by an automated test (needs real mouse input).
- [x] **Window size and position remembered** (2026-10-20): `WindowMemory`, in the user's Java preferences (HKCU registry, nothing in the repo), saved every ~2 s on change because the window thread is a daemon and never disposes when Forge exits. Tested: moved/resized demo window reopened in the same place.
- [x] Face-down and morphed cards render as backs, and carry no identifying data at all
- [x] **Stadium scoreboard** (2026-10-20): a jumbotron hanging over the stack (y 5.4), one 3.6 x 1.35 face turned to each seat (an n-sided ring; a body box behind them at 4 seats), each showing the turn and every player's life - active in gold on a lit band, you in cyan, "> " on priority. Phase deliberately left to the phase strip. A FrameBuffer texture redrawn only when a total changes; drawn unlit like a screen; fades out as the camera lifts, so the overhead view is clear. The four table monitors stay (they carry commander damage). `drawScoreboard` / `refreshScoreboard` in `Table3D`.

- [x] **Phase panel, next steps** (2026-10-21): a PRIORITY block under the steps - stack depth, and a row per seat reading deciding (lit) / passed / waiting / out. `SeatV.passed` comes from Forge's private `PhaseHandler.pFirstPriority` by reflection (no getter): the players from it, in turn order, up to the current priority holder have passed. Also fixed: the panel's background was drawn AFTER its labels (88% opaque, so every label was dimmed, and it covered the hover card and game-over banner); and the header was too short for its two lines, so "your seat" overlapped Untap.
- [x] **Game over** (2026-10-20): a lost player's seat stays put but its pad goes dull red, its label and scoreboard row read OUT; when the game ends a translucent band says GAME OVER and who won (`GameView.isGameOver` / `getWinningPlayerName`). **Seat order now comes from `game.getRegisteredPlayers()` mapped to their views**, because `gv.getPlayers()` is `Game.getPlayers()`, which drops a player the moment they lose and would shift every seat after them. `SeatV.lost`, `Snapshot.gameOver`/`winner`. Demo: `-Dforge.ui3d.demoLost=Zerma -Dforge.ui3d.demoWinner=Xenil`.
- [x] **Spell targets** (2026-10-20): violet arcs from each stack item to what it targets - a permanent, a player's seat, or another stack item (a counterspell hops across the bar) - drawn whenever the stack holds a targeted spell, not only in combat. `CardV.targetIds`/`targetSeats`, read from `StackItemView.getTargetCards/Players` along the whole sub-instance chain. Targets with no drawn card (graveyard, hand) get no arc. Demo: `-Dforge.ui3d.demoPhase=COMBAT_DECLARE_BLOCKERS` starts with three targeted spells on the stack.
- [x] **Emblems, day/night, monarch, the ring, the initiative** (2026-10-20): emblems sit in the command zone. A line of marks under each seat's totals - MONARCH, INITIATIVE, CITY'S BLESSING, RING-BEARER (named unless face down), SPEED n, and every player counter (POISON, ENERGY, EXPERIENCE, RAD...; poison was read before but never drawn). Day/night follows the phase on the table and heads the scoreboard. `SeatV.marks`, `Snapshot.dayNight`. The designations are not in the view layer, so `readMarks` reads `Game`/`Player` - acceptable only because all of it is public.
- [x] **Sound** (2026-10-20): `Cues` - four synthesised cues fired on a change between snapshots, not on every priority pass (which in Forge is nearly every step): your turn begins (two rising notes), an opponent puts something on the stack (a ping - time to think about a response), something attacks you (a low double knock), the game ends (rising chord if you won, falling if not). javax.sound on its own daemon thread, no audio files, no OpenAL; no device = silently off. **M** mutes, `-Dforge.ui3d.sound=false` starts muted. Triggers checked by `scratch\probe\CuesProbe.java` (11/11, muted).

### Stage 3 — taking over interaction

The large one. Implement `IGuiGame` decision by decision, falling back to Forge's Swing UI for
anything not yet built, so the game is always playable mid-way through the work.

- [x] **Read through Forge's view layer** (done 2026-10-20). `StateReader` reads `GameView`,
      `PlayerView` and `CardView`, and asks `CardView.canBeShownTo(viewer)` rather than applying
      a visibility rule of its own. Revealed cards, a card an effect lets one opponent see, and a
      face-down permanent its controller may check are now the engine's ruling, not ours.
      **Only verified by compiling** - it needs a real game with `-Ui3d`, and properly a
      two-human network game, before anyone trusts it.
- [x] **Read `forge.GuiMobile` first** (2026-10-21, notes below). `GuiMobile` itself is only the
      `IGuiBase` (platform: files, threads, sound). The interesting class is the mobile
      `IGuiGame`, `forge.screens.match.MatchController`, plus the UI-independent input layer in
      `forge.gamemodes.match.input` that BOTH Forge UIs share.
- [x] **How input is routed while both UIs exist - DECIDED 2026-10-21.** The owner (Xenil) has
      **approved the proposal below**: one pass-through `IGuiGame` per local human, forwarding
      everything by default, taking over one method at a time. Reason given: it means playing
      sooner, and the middle man goes away gradually rather than in one jump. Two conditions
      were attached - see *Agreed plan* below.

#### How Forge asks a human for decisions (read 2026-10-21, from the 2.0.15 jars)

There are two quite different channels, and they need different answers:

1. **Inputs** - priority, declaring attackers and blockers, choosing targets, paying mana, the
   mulligan. `PlayerControllerHuman` puts an `Input` (`InputPassPriority`, `InputAttack`,
   `InputBlock`, `InputSelectTargets`, `InputPayMana`, `InputConfirmMulligan`, ...) on its
   `InputQueue` and the game thread waits for it to finish. The Input is a little state machine
   that does not care which UI drives it: a UI only forwards clicks, through the public
   `forge.interfaces.IGameController` that `PlayerControllerHuman` implements -
   `selectCard(CardView, ...)`, `selectPlayer(PlayerView, ...)`, `selectAbility(SpellAbilityView)`,
   `selectButtonOk()`, `selectButtonCancel()`, `useMana(byte)`, `alphaStrike()`, `undoLastAction()`.
   The Input tells the UI what to show through `IGuiGame.showPromptMessage`, `updateButtons`
   (the OK/Cancel labels) and `setSelectables` (which cards are clickable).
2. **Choices** - `IGuiGame.one / oneOrNone / many / getChoices / order / getInteger / confirm /
   showConfirmDialog / showOptionDialog`. These are plain method calls made ON THE GAME THREAD,
   which simply blocks until the method returns an answer. The mobile UI does it with
   `forge.util.WaitCallback`: the game thread calls `invokeAndWait()`, the UI thread later calls
   `accept(answer)`.

Two facts make a gradual takeover possible:
- `PlayerControllerHuman.getGui()` / **`setGui(IGuiGame)` are public.** The human's GUI can be
  swapped after the game has started, without touching Forge's match setup.
- Inputs are shared. A click from the 3D window through `IGameController` is indistinguishable
  from a click in Forge's window - both land in the same Input.

#### Routing input: proposal

**One pass-through `IGuiGame` in front of Forge's own window, per local human.**

- When `Bridge.attach` finds the GUI player's `PlayerControllerHuman`, it takes `pch.getGui()`
  (Forge's `CMatchUI`) and calls `pch.setGui(new Gui3D(original))`.
- `Gui3D` forwards **every** method to the original by default, so Forge's window keeps
  working exactly as now. Build it as a `java.lang.reflect.Proxy` over `IGuiGame` (about 100
  methods) so it needs no change when Forge adds methods, with a table of the methods 3D has
  taken over.
- **Choices** (channel 2): each one is answered by exactly one UI. Once 3D implements, say,
  `many()`, `Gui3D` shows `SearchPanel` and blocks the game thread on a `WaitCallback` until the
  player picks; `CMatchUI` never sees that call. Not yet implemented means forwarded, so Forge's
  dialog answers it. This is what "only one of them answers a given decision" means in practice:
  per method, decided in one place, and the game is playable at every step of the work.
- **Inputs** (channel 1): both windows stay live, by design. `Gui3D` copies `showPromptMessage`,
  `updateButtons` and `setSelectables` into the snapshot (and still forwards them, so Forge's
  prompt stays correct). The 3D window shows the prompt and an OK/Cancel pair, and forwards its
  clicks to `IGameController`. Whichever window is clicked first wins; that is the same as
  clicking Forge's OK twice, which Forge already handles (the second click reaches the next
  Input, or nothing).
- **Threads:** 3D clicks are forwarded with `SwingUtilities.invokeLater`, so they arrive on the
  EDT exactly as Forge's own clicks do. The render thread never blocks. Blocking happens only on
  the game thread, inside a taken-over choice method, and only until the render thread delivers
  the answer.
- **Hidden information** is unchanged: `Gui3D` sits on one human's controller and only ever sees
  that human's decisions. In a network game each client wraps only its own.
- **Off switch:** `-Dforge.ui3d.input=false` skips `setGui`, which gives today's read-only view back.

**First steps, in order, once this is agreed:**
1. `Gui3D` forwarding everything, plus prompt/buttons/selectables copied into the snapshot. No
   behaviour change; this proves the wrapping is safe in a real game.
2. The pass-priority key (stage 2 item): SPACE calls `selectButtonOk()` when the prompt's OK means
   "pass". After that, click-a-card through `selectCard`.
3. Take over `many` / `one` / `oneOrNone` with `SearchPanel` (stage 3 "choosing from a list").

**Not proposed:** replacing `CMatchUI` outright with our own `IGuiGame` passed to
`HostedMatch.startMatch`. That is stage 4 (starting a game without Forge's screens), and before
then it would leave every unimplemented decision with nothing to answer it.
- [x] **Click a card to play it - done 2026-10-21.** Left-click a card the green marker shows as
      playable and it is sent to Forge as a guarded `selectCard`. Only cards Forge has already
      declared selectable can be sent, so the 3D side chooses among Forge's own legal options
      and can never offer a play the rules disallow. Targeting through the same path still needs
      doing.
#### Making it playable: attended and unattended work

Split by **whether a human has to be at the keyboard**, because that decides what is worth doing
when. The owner is not always available, and some of this genuinely cannot be judged without them.

**Attended** - needs someone playing. These are play-flow changes whose failures are invisible from
here: a prompt that never arrives, a click that answers the wrong decision, a step silently
skipped. Probes can show the logic is right; only a game shows it is *wired* right. Nearly every
real bug so far (the land that would not play, the bug box that would not type, seats that gave no
hint they were clickable) was found in a game and could not have been found without one.

**Unattended** - safe to build and verify alone. Looks, layout, visibility, and anything provable
by a probe or by rendering to a PNG and looking at it. Whether it looks *good* still wants the
owner's eye, but it can be built and reviewed in a batch later rather than blocking on them.

Within the attended list the order is the arc of a game, because that is the order you hit them in.
Anything not yet taken over falls through to Forge's window, so the game stays playable throughout.

---

### From the games of 2026-10-21 (evening)

- [x] **Artifacts cannot always be tapped for mana.** Found: `takeAbility` answered `null` for a
      single clicked ability whose `canPlay()` is false, and `null` means "the player chose
      nothing", so Forge dropped the click silently - Arcane Signet was refused four times in one
      game. `SpellAbilityView` has no mana-ability test to check instead, so it now answers
      `NOT_HANDLED` and lets Forge decide.
- [x] **SPACE sometimes acts as "skip to end of turn".** Found by reading `AutoPassHook`'s trace:
      a Cancel arriving from the network client. Forge's Cancel at ordinary priority *is* End
      Turn. When the cancel button says "End Turn" it now asks for a second press within three
      seconds; every other cancel goes straight through.
- [x] **No chance to respond to an attack.** The phase stops are keyed by the TURN PLAYER, and
      `takeSkipPhase` was reading the argument as "is this my seat" - so the window only insisted
      on being stopped during its own turn, and Forge's invisible toggles still ran everybody
      else's. A network client is worse: Forge never asks its UI at all, it reads the skip set on
      the host, so a client now sends the host a `YieldUpdate.SkipPhase` per phase.
- [x] **Boards grew as wide as the cards, not as wide as the piles.** The band slab and the seat
      plinth were still measuring card by card after permanents started stacking, so twenty
      Treasures gave a seat a twenty-slot board with one pile in the middle of it. Everything
      that measures a row now measures it through `rowGroups` / `rowWidth`.
- [x] **Cards with no picture.** Two halves. `CardArt` cut the file name at the FIRST dot, so
      "Mr. House, President and CEO.fullborder.jpg" was filed under "Mr"; it now takes the
      extension and the marker suffix off, indexes both halves of a split card, and falls back to
      a punctuation-free match. The other half is simply that Forge had only downloaded 546
      pictures against 995 cards in our decks - `scripts\card-images.ps1` says which are missing
      and fetches them (see **Card pictures** below).
- [x] **Arranging your hand.** `G` picks a card up, left/right or the mouse place it, SPACE drops
      it. Forge reorders the real zone (`IGameController.reorderHand`), so the order is the
      game's and holds on the host in a joined game.

### From the game of 2026-10-28 (evening) - game stalled 13 minutes

- [ ] **A reveal prompt nobody could see stalled the whole table** *(attended)*. Alec (joined
      client) cast Grim Servant; its trigger searched, then `GameAction.reveal` asked each human
      in turn to OK the revealed card (`PlayerControllerHuman.reveal` -> `InputConfirm`,
      showAndWait). The host's (Derand's) confirm came first and was invisible everywhere:
      - the 3D table logged `nothing to confirm right now` and refused every card click
        (`Forge refused the click ... input=InputConfirm`);
      - Forge's own prompt panel showed "Yielding until stack clears" with OK greyed out - the
        auto-yield message covered the confirm, so there was nothing to click there either;
      - Forge's "Waiting for Alec... (mm:ss)" counts UP and names the turn player, not the
        player Forge is waiting on, so everyone blamed Alec. Alec left and rejoined (reconnect
        "Replayed current prompt" worked) and nothing changed - it was never his prompt.
      Unstuck from outside by attaching an agent that called `selectButtonOK()` on Derand's input
      queue; Dominic's reveal confirm came next and he could answer his. Wanted: reveal confirms
      shown on the table (the revealed card + OK), and an auto-yield must never hide a pending
      `InputConfirm`. Log: `scratch\play-logs\console_2026-10-28_225524.log` from line ~3527
      (23:08:09); the game's thread dump showed `Game-0` parked in `PlayerControllerHuman.reveal`.
- [ ] **Cancel on a client during a reveal does nothing, silently** *(attended)*. Alec's client
      sent ~20 `selectButtonCancel` while the table was stuck; an OK-only confirm ignores them
      with no message. A refused press should say what Forge is actually waiting for, and on whom.

### Waiting on the Forge UI going away

Things that are only safe once the 3D table is the only window, because each one leaves part of
the Forge UI stale or blank while it is still on screen.

- [ ] **Stop driving the Forge UI's stack panel (`updateStack`).** Owner approved 2026-10-22,
      to be done when the Forge UI goes. On 2026-10-22 the Forge rules engine pushed a stack item
      with no activating player while unfreezing the stack after **Heliod's Intervention**
      resolved, and its own `GameLogFormatter` threw on the game thread - that killed the game
      (`Game Result: unfinished`). The Forge UI then threw a *second* NPE on the EDT, in
      `VStack.updateStack`, over the same half-made item (`getSourceCard()` null), and the bug
      dialog that followed is a modal on the event thread, which is what actually wedged the
      window. The engine NPE is not ours to fix; the second one goes away entirely if the Forge
      UI's stack panel is never driven. The 3D table already draws the stack from the GameView,
      so taking `updateStack` over costs nothing - except that the Forge UI's own stack panel
      goes blank, which is why it waits.

### Card pictures

The table draws out of Forge's own cache, `forge-data\cache\pics\cards`, which Forge only fills
with what it has had a reason to download - on a fresh machine that is nearly nothing, and every
card comes up as a drawn stand-in. The cache is not in git (hundreds of megabytes), so each
machine fills it once:

```powershell
.\scripts\card-images.ps1                          # what is missing, deck by deck
.\scripts\card-images.ps1 -Download                # fetch it all from Scryfall
.\scripts\card-images.ps1 -Card "Mr. House, President and CEO" -Download   # just this one
```

Pictures land in a `MTG3D` folder inside the cache - a folder, not a real set code, so Forge's own
window carries on using its own files. `CardArt` rescans every few seconds while a game is
running, so they appear without a restart.

### Owner's list, 2026-10-23 — not started (do not fix until the owner says)

From the games of 2026-10-22. The AI side of the same list is in `AI-TODO.md` at the repo root.

- [x] **"Pass anyway?" opened Forge's window** (fixed 2026-10-25, Stoneforge: it was the owner's OWN passes in games he hosted, the counterspell reminder on other players' turns, while the 3D table briefly could not ask; it now waits up to 3 s, then passes unasked with a PASSCHECK log line saying why - never Forge's window. The old note below, blaming joined games, was wrong.) `ConfirmPassHook` asks
      `Gui3D.askYesNo` and falls back to Forge's window when that returns nothing - which it does
      in a joined game, because the 3D question goes through the local `PlayerControllerHuman`
      and a joined game has only the network controller. Four pop-ups in the owner's joined game
      of 2026-10-22 17:41. Fix: give `askYesNo` the joined-game path the clicks already use
      (`remoteClick`), so the question shows in 3D and the answer returns to the hook. Test: a
      probe in remote mode, plus `Ui3dRemoteTest`.
- [ ] **Splitting combat damage opens Forge's window - hosted AND joined games** (2026-10-24 01:35, the owner's own Captain America attack, one blocker, trample). Partly done 2026-10-25: ESC no longer closes the damage panel unanswered (which sent it to Forge), and every way out now logs `DAMAGE3D: ... - <why>`; the next occurrence will say which. Original note: The host
      sends `assignCombatDamage` over the network and it arrives in a shape `takeAssignCombatDamage`
      does not accept ("shape not taken" in the console, same game). Fix: log the exact argument
      shape that arrives in a joined game, accept it, fill anything missing (e.g. the defender)
      from the 3D table's own snapshot, and answer through the same return path. Test: a
      joined-game case in `Ui3dTakeoverTest`, then a real double block in a joined game.
- [x] **Network sync loses track on big turns** (fixed 2026-10-26, Stoneforge: `TrackerSyncHook` swaps the list for a copy-on-write one; race test 1,438 errors in 3,274 reads without it, 0 in 2 million with it. The Guava-table NoSuchElementException is a separate, rarer race, not covered.) (Forge's code, but it is what joined players see).
      On a turn with dozens of triggers, Forge's host-side sync reads the game while it changes:
      `Tracker.getDelayedPropsFor` throws ConcurrentModificationException, the delta walk "ships
      what was collected", and the client shows blank names / stale cards ("null activated" in
      the log). Fix: an EvalCacheAgent hook that has `getDelayedPropsFor` read a copy of the list,
      as `ActionableCardsHook` does for the pay-mana race. Only provable in a busy networked game.
      **Crash-log review 2026-10-25:** the top recurring error. Sync warnings in every networked
      session (5), the walk gave up 6 times (stale client views), one crash window on the host
      (2026-10-24 00:29, via the pay-mana highlights being sent to a client), and the same race
      behind the "Network encode error for Dominic ... setHighlighted" (ArrayIndexOutOfBounds),
      a NoSuchElementException in a Guava table iterator, and the "null activated" NPEs in
      GameLogFormatter / NetworkGameEventListener. One hook should stop the whole family.
- [x] **Dividing an amount among targets that include a player went to Forge's window** (joined game 2026-10-22 01:28; fixed 2026-10-26, Stoneforge): `takeGenericAmount` handed any player target to Forge on purpose. A player is now a named face in the same panel; every refusal logs `AMOUNT3D: ... - <why>`. Test: a card + player case in `Ui3dTakeoverTest`.
- [x] **Forge's window throws "Error doing rescale" when it comes back at game end.** (fixed 2026-10-26, Stoneforge: `ImageScaleHook`; reproduced and fixed in `ImageScaleProbe`) Crash-log
      review 2026-10-25: as the 3D table closed after the 2026-10-24 02:17 game, Forge's restored
      window resized its card panels through a 2-3 pixel size, and `ImageCache.scaleImage` threw
      32 times on the Swing thread (`CardPanel.componentResized` -> `updateImage`) - the kind of
      error Forge reports with its crash window. Harmless to the game (it was over). Fix: an
      EvalCacheAgent hook on `ImageCache.scaleImage` that skips sizes under 3x3 (returns no image
      for that frame) instead of throwing; or restore the window at its real size before Forge
      lays it out.
- [x] **Card pictures on demand.** (on-demand part done 2026-10-26, Stoneforge: `CardArt.fetchSoon` asks Forge's own `ImageFetcher` for any card or token with no picture, once per key; `PictureFetchProbe` fetched two uncached Cat tokens from Scryfall into Forge's cache and the table found them. The launch pre-download was left out - on-demand covers every card within seconds of its first appearance.) A card or token with no cached picture should ask Forge's own
      downloader (its image key, as Forge's window does) when it first appears on the table, so
      any card from any deck - friends' decks in joined games, Clown Robots, Balloons - gets its
      picture within seconds, in Forge's own folders. Plus a quick pre-download of the decks'
      exact printings (and their tokens) when `Play 3D.cmd` / `Update.cmd` starts, skipping what
      is cached. Scryfall-less oddities keep the drawn face.
- [x] **Centre screens redesigned to the owner's sketch** (done 2026-10-25, Stoneforge; checked by demo screenshot, still to see in a real game) (`ui3d\design\centre-screen-sketch-2026-10-23.jpg`
      - a portrait photo of a landscape screen; turn it a quarter left to read it). Left half: the
      player's commander as its card picture. Right side, top to bottom: player name (large), life
      total, one line per enemy commander with the commander damage it has dealt this player,
      counters and designations as symbols (poison, monarch...). Bottom: floating mana as beads,
      one coloured bead per mana floating (W U B R G C), none when the pool is empty. Owner's choices: enemy
      commanders as TEXT (name + damage, long names shortened), on the screen of the player who
      TOOK the damage, under the life total, a line appearing once that commander has hit them
      (e.g. Captain America's screen: `Olivia, Opulent Outlaw   4`). Life total BIG, like the
      scoreboard. All settled - build when the owner says go. Supersedes the item above about the
      flat screens' text. Claim it first - the readout was last reworked by journeybeforepancakes
      (81c22e10).
- [x] **The writing on the flat (TAB-view) screens does not grow with them.** (superseded by the redesign, which sizes the readout to the screen) The screens are a
      third larger flat, the readout is not; life totals read, the LIB/GY/EX numbers are tiny.
- [ ] **Check by eye in a seated game:** the left/right screens now follow where each player's
      seat is (they were mirrored). Only the overhead view was screenshot-checked.

### Owner's list, 2026-10-21 — not started

Asked for during the first four-player game played from the 3D table. Written down as given,
with where each one lands. Nothing here is claimed yet.

**Opened zones — looking at a hand, searching a library** *(unclaimed; investigated 2026-10-21,
not built — Xenil's Claude ran out of session, everything below is what the digging found)*

Both of the owner's reports ("looking at Rosa's hand", "search for a Plains in my library") are
the same mechanism: Forge's floating zone viewer, opened by `IGuiGame.openZones`. Three things
to know before starting:

> **All three done 2026-10-21 (Stoneforge).** `peekZone` reads the players from `args[2]`'s keys
> (confirmed against `CMatchUI.openZones`) and registers each card for clicks; when Forge offers
> some of the shown cards (a library search), the panel shows just those and a pick is sent as
> that card's click (`Table3D`, reopened as a pick panel if the offer arrives after the zone
> opened); `openZones` and `showErrorDialog` now count in the tally as "(Forge window)".
> `scratch\probe\Ui3dPeekTest.java` 3/3 (Rosa's hand, not ours; counted). The pick itself needs
> a real search to confirm (TESTING.md).

- [x] **`peekZone` is probably reading the wrong player.** It takes `args[0]` as the zone's
      owner, but `CMatchUI.openZones` builds its list from the **map keys in `args[2]`**;
      `args[0]` looks like the viewer. That would explain why no mirror appeared at all for
      another player's hand. Signature is
      `openZones(PlayerView, Collection<ZoneType>, Map<PlayerView, Object>)`.
- [x] **The mirror is browse-only by design** (`minPick 0, maxPick 0` in `Table3D`), on the
      stated grounds that Forge's window is still showing the zone. That is fine for a Duress
      and useless for a library search, where you must *pick*. Making it selectable is mostly
      done already: register the cards in `Gui3D.known` and route the chosen one through
      `Gui3D.clickCard`, which Forge accepts from any window.
- [x] **`-Strict` is blind to this whole category.** `noteFallback` skips void methods as
      "display calls", and `openZones` is void — so the fallback tally reads "the 3D window
      handled everything" while the player is being dragged to Forge's window repeatedly.
      Worth counting display calls separately from decisions.

**Abilities — say which one, everywhere it appears** *(attended wiring, unattended look)*

- [x] Activating an ability on a card with more than one - **taken over 2026-10-20**
      (Stoneforge). `getAbilityToPlay` is in `TAKEN_OVER` (`Gui3D.takeAbility`): Forge's own rules
      first (nothing offered = nothing; a single non-prompting ability plays if it can), then an
      `OptionPanel` column, one button per *playable* ability in Forge's words ("Cast X" for the
      card itself), ESC = nothing played, H / timeout hands it to Forge. 7 cases in
      `Ui3dTakeoverTest` (21/21). **Drop-down at the card - done 2026-10-21:** `Choice.anchorCardId`
      (set by `takeAbility`) makes `Table3D` call `OptionPanel.anchorAt` with the card's screen
      position: a compact menu beside the card (right if there is room, else left; never off
      screen), light dim, long ability text cut to fit. Lists over 8 stay a centred panel. Demo:
      `-Dforge.ui3d.openPanel=ability`.
- [x] While it is on the stack, show *which* ability is resolving - done 2026-10-20: under each
      ability or trigger on the stack, its text in the ribbon's teal (two lines, the card name
      stripped), below who controls it (`TableHud.drawStackLabels`). Demo stack now has a
      Weathered Wayfarer activation.
- [x] **Done 2026-10-21 (Stoneforge)** for the card: a teal slab (the ability ribbon's colour)
      under any permanent whose ability or trigger is on the stack, and under the card whose
      abilities are being chosen between (`TableLayout.abilitySourceIds`, `sourceModel`). Combat,
      targeting and mana-payment slabs still win, as they are what is being decided. Demo: the
      blockers-step stack has a Resplendent Angel activation. *The specific ability on the card*
      was done 2026-10-26 (Stoneforge): `drawAbilityOnSources` writes the ability's own words
      (cost and effect, as on the stack label) in teal over its source permanent; two abilities
      of one card on the stack stack their lines.
- [ ] *(original wording)* Highlight the source card on the battlefield, and the specific ability on it, while that
      ability is on the stack or being chosen.

**Cosmetics** *(unattended)*

- [x] **Piles - done 2026-10-21 (Stoneforge).** Each pad carries a pile as tall as the zone (6 mm
      a card, capped at 100), easing up as cards arrive; library like a card back, graveyard dark,
      exile pale violet; the count sits on top of the pile, and dying / drawn cards slide onto /
      off its top (`TableLayout.placeZones`, `pileTop`). **Also done:** each pile's pad shows as a
      rim in the owner's seat colour (`zoneLabelColour`, darkened), and the top card of a
      graveyard or exile pile lies face up on it (scenery - a click still browses the pile).
- [ ] *(original wording)* Library, graveyard and exile should be **stacks that grow** as cards arrive, not flat
      pads with a number. Counts are already in `SeatV.librarySize` / `graveyardSize` /
      `exileSize`; this is geometry, not new data.
- [x] Raise each battlefield a little off the felt - **done 2026-10-21 (Stoneforge)**: a plinth
      (`TableLayout.placePlinth`, `FIELD_RAISE` 0.12) under each seat's three rows, sized to the
      widest row; rows, band slabs, attachments and the seat pad sit on it, the seat frame stays on
      the felt round it. Each seat's plinth is 2 mm higher than the last so overlapping corners
      never flicker.
- [x] (done 2026-10-26, Stoneforge: every tray's own label now carries its owner's name in their seat colour, placed by the trays' own helpers - zoneX / new commandX - which the labels had drifted from; board names moved to the middle of the board's inner edge, seated view only) Make it obvious which battlefield the command zone, exile, graveyard and library belong
      to. *(Partly in hand: owner-named zone pads and coloured seat frames are written but not
      yet built — see `CLAIMS.md`. The stacks above should carry the same colour.)*
- [x] **Overhead hand - done 2026-10-21 (Stoneforge)**: in the overhead pose the hand is a column
      down the right of the screen, under the game log (`TableLayout.placeHandOverhead`): each card
      on the camera's ray through its screen spot, at a distance that makes it ~150 px tall,
      parallel to the screen, overlapped so the name lines show; hovering pulls a card out to the
      left; the pose easing slides the hand between seat and column. Clicks work as anywhere.
- [ ] *(original wording)* **Overhead (birds-eye) mode: keep your hand visible and usable** — move it to the side
      rather than dropping it. Today the hand is anchored at your own seat and is edge-on or
      off-screen from above, which makes the overhead pose read-only.
- [x] **Centre monitors - done 2026-10-21 (Stoneforge)**: a near-black bezel with glowing glass
      inset (dark amber for whoever's turn it is, cyan for yours, steel blue otherwise, dull red
      when out) on a stand; a "lib / gy / ex" line under life and commander damage. The glass is
      kept dim on purpose so the life total drawn over it stays readable.
- [ ] *(original wording)* **The centre monitors**: make them look like actual screens, and put the public numbers
      on them — library, graveyard and exile counts alongside life.
- [x] **Card pictures that never arrive - found and fixed 2026-10-21 (Stoneforge).** Two causes
      in `CardArt`: (1) cards with several arts are saved numbered (`Plains1.fullborder.jpg`) but
      looked up as "plains", so no basic land ever found its picture - numbered files now also
      count under the plain name; (2) the picture index was built once, when the window opened,
      so pictures Forge downloads during the game (most of them, on a fresh install) were never
      seen - a card with no picture now triggers a background rescan, at most every 4 s, and the
      real picture replaces the stand-in. `scratch\probe\CardArtProbe.java` (3/3); demo screenshot
      shows the Plains with art. The FaceMaker stand-in is unchanged and still covers anything
      Forge has not downloaded.
- [ ] *(original wording)* **Card pictures that never arrive**: either fix the fetch or draw a better stand-in.
      `CardArt` already falls back to a generated face (`FaceMaker`); the plain SPELL / CREATURE
      placeholders are what is being seen. Worth finding out *why* a given card has no art
      before improving the fallback — a fresh clone has no `forge-data\cache`, so the first
      games of any new install are mostly placeholders.

**Turn and phase banners** *(unattended to build, attended to judge)*

- [x] A change-of-turn banner for **every** player's turn - done 2026-10-20 (Stoneforge):
      "CAPTAIN'S TURN" in a smaller steel-blue band sliding in from the right (YOUR TURN stays
      the big gold one), `TableHud.drawTurnBanner`.
- [x] On your own turn, a banner **per phase** - done 2026-10-20: a slim gold strip above the
      table for upkeep, draw, main 1, combat, main 2 and end step (untap, cleanup and the combat
      sub-steps left out on purpose), `drawPhaseBanner` / `PHASE_BANNERS`. Both in the 3D window,
      so neither pulls focus. Demo timeline (`-Dforge.ui3d.demoEvents=true`) shows all three.
      (Relates to the earlier bug report, *"please signify more significantly change of player
      turn"*. `PlayHook.TurnBanner` is Swing on Forge's window — doing this in the 3D window
      instead would avoid pulling focus, which is the standing complaint.)

- [x] **Bug (owner, 2026-10-20): the "YOUR RESPONSE" pop-up showed on Forge's window, not in 3D.**
      Fixed 2026-10-21 (Stoneforge): with `forge.ui3d=true`, `PlayHook.ResponseWatcher` no longer
      shows its Swing banner; `TableHud.drawResponseBanner` draws an amber YOUR RESPONSE band in the
      3D window after the same 10 s (`forge.responsebanner.ms`) of holding priority on someone
      else's turn, gone as soon as you act. Not screenshotted (the demo has no such moment).

**Revealing hands when a player is out** *(attended — needs care)*

- [x] When a player dies, make every remaining hand public knowledge **to them**. (done 2026-10-26, Stoneforge: once the player at this client is out, StateReader reads every remaining hand into SeatV.revealedHand and the table lays them face up, turned to face them, as real cards to point at. In games they host or play against the AI; a joined game cannot, the host never sends hidden cards. Demo: -Dforge.ui3d.demoLost=Xenil.)

> This is the one item that cuts against *Hidden information* above. The rule is that another
> player's hand is never put into the `Snapshot` at all, which is what makes a leak impossible
> rather than merely unlikely. Doing this means the reader must pass hands for a viewer who is
> **eliminated**, and only then — so it has to key off the engine's own view of who is out, not
> off anything the UI believes. On a host JVM running two humans this is exactly the shape of
> the `ConfirmPassHook` leak of 2026-10-20. Build it with a probe that asserts a *living*
> viewer still gets nothing.

---

### Everything Forge's window still answers (census, 2026-10-21)

`IGuiGame` has **105 methods**. 24 are taken over. This is the rest, so "how much longer do we
need Forge's window?" has an answer that can be checked rather than guessed. Regenerate with
`javap -cp forge-gui-desktop-2.0.15-jar-with-dependencies.jar forge.gui.interfaces.IGuiGame`.

Closing Forge's window needs the **decisions** done and the **windows** suppressed. The
notifications can be ignored for as long as we like - the snapshot already carries what they
announce.

#### Decisions - Forge is asking the player something (7 names, 9 overloads)

These are the ones that matter. Each one that is not taken over is a moment the player is pulled
to the other window.

> **All taken over 2026-10-21** (Xenil's Claude: the three generic dialogs, the two-list
> `order`, `assignGenericAmount`, `insertInList`; Stoneforge, after Xenil's Claude ran out:
> `manipulateCardList` as OrderPanel's Top / Bottom rows, and the free-text `showInputDialog`
> on a new `TextPanel` with game keys blocked while typing). `Ui3dTakeoverTest` 35/35 covers
> every one. Only `sideboard` is left with Forge, and it never fires in Commander.

- [x] `showConfirmDialog(String, String)` and two more overloads -> `boolean`.
      A yes/no with custom button labels. `Gui3D.Ask` already does exactly this for `confirm`;
      this is the generic version, and should land in the same place.
- [ ] `showOptionDialog(String, String, FSkinProp, String[], int)` -> `int`.
      Pick one of N named buttons. `OptionPanel` is built for it.
- [ ] `showInputDialog(String, String, FSkinProp, String, List, boolean)` -> `String`.
      Free text, or a pick from a list. The list form is `OptionPanel`; the free-text form has
      no panel yet and is the only place the 3D window would need a text field (see the note on
      `getTextInput` in `BugReport` - libGDX's is not implemented, so this means Swing or our
      own field).
- [ ] `assignGenericAmount(CardView, Object[], int, boolean, String)` -> `Map<Object,Integer>`.
      Split a number across several things. `DamagePanel` already does this shape for combat
      damage and should generalise.
- [ ] `insertInList(String, T, List<T>)` -> `List<T>`.
      Put one item into an ordered list. `OrderPanel` is close but orders a whole list rather
      than inserting into one.
- [ ] `manipulateCardList(String, Iterable, Iterable, boolean, boolean, boolean)` -> `List<CardView>`.
      The two-list dialog, titled **"Select order for simultaneous abilities"**. Screenshot from
      the owner, 2026-10-21: a left list ("Select Order") and a right one ("Resolve first"),
      four movers between them (`>`, `>>`, `<`, `<<`), **OK** under the left list, an **Auto**
      button under the right, and an **"Always use this order"** tickbox along the bottom.
      The three booleans are almost certainly those options; confirm against the jar before
      building. It fires on any pile of simultaneous triggers - the owner met it with three
      identical mill triggers, where the order does not matter and **Auto** is the only sane
      answer, so Auto should be the default action and the lists the fallback.
      The hardest of the set, the one with no panel, and the most often seen.
- [ ] `sideboard(CardPool, CardPool, String)` -> `List<PaperCard>`.
      Never fires in Commander. Can stay with Forge indefinitely.

#### Windows of Forge's own - must be suppressed, not just mirrored

Void, so **`-Strict` is blind to them** (`noteFallback` skips void as "display"). `openZones` hid
here for days and was what kept dragging the owner to Forge's window.

> **Done 2026-10-21 (Stoneforge)**, each checked against `CMatchUI` in the jar; all in
> `Gui3D.TAKEN_OVER`; `Ui3dTakeoverTest` 29/29.

- [x] `showErrorDialog(String)` / `showErrorDialog(String, String)` - modal error box
      (`SOptionPane.showErrorDialog`). Now shown in the 3D message panel (`takeMessage`).
- [x] `alertUser()` - `CPrompt.alert` (a reminder flash on Forge's prompt). Swallowed.
- [x] `flashIncorrectAction()` - `CPrompt.remind`, same flash on an illegal click. Swallowed:
      the 3D window already toasts why a click was refused.
- [x] `showRevealedCards(cards)` - opened Forge's floating zone window (`tempShowZones`). Now
      mirrored as a "Revealed" peek in the search panel and Forge's window stays shut;
      `hideRevealedCards` closes the mirror (and the panel).
- [x] `showCombat()` - only switches Forge's combat tab and redraws it. Left with Forge.
      `showWaitingTimer` - not implemented by the desktop UI (interface default). `openView` -
      builds Forge's match screen; the game needs it. Both left with Forge.
- [x] **`-Strict` counts these.** Void calls that open a window of Forge's own (`openZones`,
      `showErrorDialog`, `showRevealedCards`) count as "<name> (Forge window)" when they reach
      Forge; taken-over ones do not count.

**Trample** (was "left to Forge's dialog because how the defender's share is keyed could not
be confirmed"): confirmed in the jar - `VAssignCombatDamage.getDamageMap` returns the defender's
share under the **null** key. `takeDamage` now handles it: DamagePanel's Player column, the
player's share under null; a split that gives the player damage before every blocker has lethal
goes back to Forge's dialog instead.

#### Getters - state, harmless (8)

`getGameView`, `getGameController`, `getDraftLog`, `getSpectator`, `getOriginalGameController`,
`isUiSetToSkipPhase` (taken), `isCurrentPlayer`-style queries. Nothing to do.

#### Notifications - safe to leave with Forge (the remaining ~55)

Redraw and bookkeeping calls. The 3D window rebuilds its snapshot from the game's own event bus,
so it already knows everything these announce; they can keep going to Forge's window forever
without the player ever noticing.

`afterGameEnd`, `applyDelta`, `applyYieldUpdate`, `awaitNextInput`, `cancelAwaitNextInput`,
`clearSelectables`*, `clearWeaklySelectable`, `disableOverlay`, `enableOverlay`, `finishGame`,
`handleGameEvent(s)`, `handleLandPlayed`, `hideManaPool`, `notifyStackAddition`,
`notifyStackRemoval`, `refreshCardDetails`, `refreshField`, `refreshYieldUi`, `setCard`,
`setCurrentPlayer`, `setGameController`*, `setGamePause`, `setGameSpeed`, `setGameView`,
`setHighlighted`, `setNetGame`, `setOriginalGameController`, `setPanelSelection`,
`setPlayerAvatar`, `setSelectables`*, `setSpectator`, `setWeaklySelectable`, `showManaPool`,
`showPromptMessage`*, `updateAutoPassPrompt`, `updateButtons`*, `updateCard(s)`,
`updateCardsNetSafe`, `updateDayTime`, `updateDependencies`, `updateDrawOffer`, `updateLives`,
`updateManaPool`, `updatePhase`, `updatePlayerControl`, `updateRevealedCards`, `updateShards`,
`updateStack`, `updateTurn`, `updateZones`.

`*` = already **observed** on the way past (`Gui3D.observe`): these feed the prompt mirror, the
selectable set and the controller, so they are doing work for us even though Forge still handles
them.

#### Not an IGuiGame method, but in the way

- [x] **Forge's global key hook** - `FControl` is a `KeyEventDispatcher` on the
      `KeyboardFocusManager`, so it saw every key in the JVM whichever window had focus. It ended
      four of the owner's turns through the End Turn menu item and typed into Forge during bug
      reports. Removed while the 3D table is running (2026-10-21), `-Dforge.ui3d.unhook=false`
      puts it back.
- [ ] **Focus theft on stack resolution** - reported repeatedly, cause still unknown. Not the
      prompt buttons (disarmed) and not `UI_REMIND_ON_PRIORITY` (off). With the key hook gone,
      worth re-testing before hunting further: it may have been the same thing all along.

---

### Attended - needs someone playing

> **Hooked up 2026-10-21 (owner asked), not yet play-tested - see `ui3d\TESTING.md`.**
> Every visual half below is now wired to Forge:
> - **Questions** (new `Gui3D` takeovers, all through `Handoff`, so an unanswered one goes back
>   to Forge): `one` / `oneOrNone` / `getChoices` / `many` - cards to `SearchPanel`, anything
>   else (modes, colours, types, players) to `OptionPanel`; `getInteger` to `NumberPicker`;
>   `order` (pure ordering: scry top, triggers) to `OrderPanel`; `assignCombatDamage` to
>   `DamagePanel` **except** when Forge passes a defender (trample), which stays with Forge's dialog
>   because how that share is keyed could not be confirmed from the jar. Two-list `order` /
>   `many` (sideboarding) also stay with Forge.
> - **Board modes** switch on from the Input Forge is waiting on (`Gui3D.PROMPT.inputName`,
>   `TableInput.syncBoardModes`): `InputSelectTargets` -> targeting mode (legal = Forge's
>   selectables, else every permanent; clicks are `selectCard` / `selectPlayer`);
>   `InputAttack` -> drag to attack (drop = `selectPlayer` then `selectCard`); `InputBlock` ->
>   drag to block (`selectCard` attacker, then blocker); `InputPayMana*` -> pay mode;
>   `InputConfirmMulligan` / `InputLondonMulligan` -> `MulliganPanel`. In wired combat the arcs
>   drawn come from the live combat, so they show what Forge accepted.
> - **Game actions:** end screen -> `nextGameDecision` (Rematch = NEW, Next game = CONTINUE,
>   Quit = QUIT); menu Undo -> `undoLastAction`; Concede (asks first) -> `concede`.
> - **Tests:** `scratch\probe\Ui3dTakeoverTest.java` (14/14: answered, cancelled, timed out, left to
>   Forge). `Ui3dConfirmTest` and `Ui3dGuiProxyTest` now initialise Forge first (they failed to
>   start with a localiser NPE on a fresh run); with that, 4/4 and 105/105 forwarded.
> - **Graphics quality** (ESC menu): High / Medium / Low - frame cap and vsync at once; texture
>   filtering for newly loaded card art; anti-aliasing and the felt size when the window next
>   opens. `GraphicsQuality.java`, saved in the user's Java preferences.

> **Handover 2026-10-21 (Xenil's Claude, out of usage).** All claims released. Next three, in
> the order the owner is hitting them:
>
> 1. **Forge's window still steals focus on stack resolution.** Reported three times. The
>    prompt-button fix and `UI_REMIND_ON_PRIORITY=false` did not cover it, so it is a third
>    source - most likely Forge updating its stack display. See `scratch/bug-reports/`
>    `bug_2026-10-20_145119.md`.
> 2. **Mana payment.** `bug_2026-10-20_143053.md`: tapping a Plains before casting a creature
>    took the player out of their turn rather than floating the mana. Probably the land click
>    being read as an ordinary `selectCard` when Forge wanted a cost payment.
> 3. **Verify the new choose-from-a-list takeover in a real game.** It compiles and the probes
>    pass, but no search has actually been performed through it.


**A. Starting a game**

- [x] Choose who starts - done 2026-10-21.
- [x] Mulligan, keep or throw back - `SPACE` already covered it.
- [ ] **London mulligan: put N cards back.** A choose-from-hand. `SearchPanel` with `min=max=N`.
- [ ] **Opening-hand actions** (Leylines, Chancellors). Rare; a yes/no.

**B. A simple turn**

- [x] Play a card, pass priority (`SPACE`), cancel (`X`) - done 2026-10-21.
- [x] **Yes/no confirmations - done 2026-10-21.** The **first method Forge no longer sees**.
      `confirm` is answered in the 3D window: a loud banner naming the card, `SPACE` for yes,
      `X` for no, `H` to hand it back. If nobody answers, the window stops drawing, or `H` is
      pressed, `Handoff` gives it to Forge and the game carries on.
      `scratch/probe/Ui3dConfirmTest.java` covers all four endings. PASS.
- [ ] **Mana payment.** Forge auto-taps when unambiguous; when it is not, it asks which lands.
- [ ] **Auto-pass / yields**, or playing from 3D stays far more clicking than Forge's window.

**C. Spells that ask questions**

- [ ] **Targeting.** Click a creature, player or permanent. Reuses the card-click and player-click
      paths that already work.
- [x] **Choose a card from a list - done 2026-10-21.** Library searches, graveyard picks
      and list targets open in `SearchPanel` via `Handoff`. **Not yet played** - needs a
      real Path to Exile or Weathered Wayfarer to confirm the pick reaches Forge.
- [ ] **Modal spells** ("choose one"), **X costs**, **optional and additional costs**.
- [ ] **Choose a player, a colour, a card type, a number.**

**D. Combat**

- [ ] **Declare attackers**, including which opponent or planeswalker each attacks.
- [ ] **Declare blockers**, assigning each blocker to an attacker.
- [ ] **Damage assignment order** when one attacker is blocked by several creatures.

**E. Housekeeping**

- [ ] **Ordering simultaneous triggers.** `SearchPanel` already remembers pick order.
- [ ] **Scry and surveil** - top or bottom, in order.
- [ ] **Discard to hand size.**
- [ ] **Game over, concede, next game.**
- [ ] **Undo**, and Forge's "restart this phase".

**F. Lobby** (was stage 4)

- [ ] `IGuiBase`, enough to start a game without Forge's screens.
- [ ] Four-player pod setup with AI opponents and the `Realistic` profile.
- [x] **3D table for a JOINED game** - built 2026-10-21, untested with a real second PC (see TESTING.md section 9). The 3D table attached only in `PlayHook` (`Match.startGame`), which runs only on the HOST, so a player who joined someone else's game never got it. Now `scripts\tools\ClientHook` (agent hook on `GuiDesktop.getNewGuiGame`) hands the GUI made inside `NetConnectUtil.join` to `Bridge.wrapClientGui` -> `Gui3D.installRemote`: forwards everything to Forge's window, rebuilds the snapshot from the host's `GameView` on every update (coalesced, on the EDT) via `StateReader.readView`, and sends 3D clicks to the client's `NetGameController`. Board modes are inferred from the prompt text (no Input objects on a client). Not available when joined: monarch/initiative/ring/speed and who has passed. Tests: `scratch\probe\Ui3dRemoteTest.java` 11/11 (real GameView, stub window, recording controller); the agent hook was seen installing in `GuiDesktop`; the existing Gui3D tests still pass.
- [ ] Network lobby - the owners play over Radmin LAN, so a joining player needs an **OPEN** slot
      (see the root `CLAUDE.md`).

---

### Unattended — safe to build and verify alone

**Looks and layout**

- [ ] **Redesign the left panel.** It now carries the turn, the seat you are at, thirteen phase
      rows, priority, the follow tickbox and every player's life - grown by accretion, never
      designed. It is the densest thing on screen and the first place the eye goes. Wants
      proper grouping, spacing and type sizes rather than one more row bolted on.
- [x] Seat pads smaller and darker - done 2026-10-20 (7.6 x 3.4, see *Seat pads* above).
- [x] The mulligan and opening hand - **look done 2026-10-21**: see `MulliganPanel` under *Visual halves*; showing it at the right moment is the attended wiring (A. London mulligan).
- [x] Card movement between zones - done 2026-10-20: per-id poses eased every frame (`Pose`/`ease` in `Table3D`), hand -> stack -> battlefield slides.
- [x] A proper table surface and surround - done 2026-10-21. Generated felt (`Felt.felt`: the same deep blue, a fine cloth grain, a lighter lamp pool in the middle and a faint centre ring), a dark padded rail round the edge (outside the playing area, so no layout moved), and a floor with a dim pool of light under the table (`Felt.floor`, drawn unlit). No image files; fixed seeds, so screenshots are repeatable. **Noticed, not fixed (prompt bar is Xenil's):** in the overhead pose the zone labels (GY / EXI / LIB / COMMAND) draw on top of the prompt bar.
- [x] Tokens, emblems and face-down cards read at a glance - done 2026-10-21 (`FaceMaker`). **Tokens:** silver frame round the card and a full-width TOKEN banner (the old corner tag vanished at table distance). **Face down:** the back says FACE DOWN and shows the creature's power/toughness large - public information (a morph is a 2/2 anyone can see, pumps included); `StateReader` now fills only those two numbers for a face-down card on the battlefield, from the face-down state, never in a hidden zone. Backs are cached per P/T. **Emblems:** a gold medallion naming whose emblem it is, with the ability written out, instead of a blank SPELL face. Checked with `FacePreview` (now includes these cases).

**Visibility**

- [x] Browse a graveyard or exile pile - done 2026-10-21. Left-click any seat's GY or EXI pad (both public) and the pile opens in `SearchPanel` browse mode, most recent first. The pads are scenery, so it is a ray hit on the table plane turned into the seat's frame (`openPileUnderMouse`); only when no card is under the mouse. New `SeatV.graveyard`/`exile`; a face-down exiled card stays blank. Checked by screenshot (`-Dforge.ui3d.openPile=0,graveyard`); **the click itself wants a check in a real game**.
- [x] Show what a spell on the stack is targeting - done 2026-10-20: violet target arcs (see *Spell targets* above).
- [x] Draw what Forge is asking - prompt bar above the hand, with clickable OK/Cancel
      carrying their real labels and enabled state (done 2026-10-21).
- [x] Forge's card-list dialogs (the AI-cant-play-these deck warning) now open in the 3D
      window's search panel instead of only in Forge's (done 2026-10-21).
- [x] Say why a click did nothing - done 2026-10-21 (`TableHud.toast`, `TableInput.whyNot`): a refused click shows a short message by the card for ~2 s instead of only a console line. Forge gives no reason (the card is just not selectable), so it is the best guess the snapshot allows - not your priority; a land outside main phase / with a stack / already played one; a spell with wrong timing or mana; someone else's card. Demo: `-Dforge.ui3d.openPanel=toast`.
- [x] A visible log of recent events - done 2026-10-21. Top right under the bug cog: the last 8 lines of Forge's own game log at MEDIUM verbosity (what Forge's window shows this player), oldest at the top, coloured by entry type, older lines faded. **L** hides it, `-Dforge.ui3d.log=false` starts hidden. `Snapshot.log`; `scratch\probe\GameLogOrderProbe.java` shows Forge returns these newest first.

**Visual halves of attended items** (added 2026-10-21) - build and screenshot each against
`Demo` data now; wire it to Forge when its attended item is taken. The attended item it serves
is in brackets. Building the look first means the attended session is spent on wiring, not design.

- [x] Attack drag - **look done 2026-10-21** (`TableBase.CombatPlan`): creatures that may attack glow green, drag one onto an opponent (monitor, pad, any of their cards, or just their side of the table) and it turns red with a red arc to that seat; drop it back to take it off; a lighter arc follows the mouse while dragging; "Declare attackers (N attacking)" banner, SPACE confirms, ESC cancels. **To wire (attended):** `InputAttack`. Demo: `-Dforge.ui3d.openPanel=attack`. *(D. Declare attackers)*
- [x] Block assignment - **look done 2026-10-21** (`CombatPlan` with `blocking`): drag a glowing creature onto an attacker (red slab) - blue slab and a blue arc onto it; several blockers on one attacker each get their own arc. **To wire (attended):** `InputBlock`. Demo: `-Dforge.ui3d.openPanel=block`. *(D. Declare blockers)*
- [x] Damage assignment panel - **look done 2026-10-21**, `DamagePanel.java`: attacker and its damage, each blocker in order with -/+ and its lethal amount (framed green once reached), a Player column with trample, "N left to assign", Auto (A) fills lethal in order and puts the rest last, Confirm at zero. Forge enforces the ordering rule; the panel shows it. **To wire (attended):** Forge's damage-assignment decision. Demo: `-Dforge.ui3d.openPanel=damage`. *(D. Damage assignment order)*
- [x] Targeting mode - **look done 2026-10-21** (`TableBase.Targeting`): non-targets' pictures dim, legal targets get a violet slab (gold once picked), a violet arc follows the mouse from the source (snapping to a legal target under it), gold arcs to picked targets, "Choose a creature ... (0 of 1)" banner; clicks pick / unpick, the last pick finishes, ESC cancels, and no card is played meanwhile. **To wire (attended):** fill `Targeting` from Forge's `InputSelectTargets` and send picks through `selectCard` / `selectPlayer`. Demo: `-Dforge.ui3d.openPanel=target`. *(C. Targeting)*
- [x] Option panel - **look done 2026-10-21**, `OptionPanel.java`: a column of large buttons with optional detail line and colour swatch, `min/max` picks ("choose two" shows a counter and a Confirm that lights at the minimum), keys 1-9, ESC cancels, `Result` gives the chosen indices. **To wire (attended):** point `IGuiGame.one / many / getChoices` with text or player items at it. Demo: `-Dforge.ui3d.openPanel=modes|two|colour|player`. *(C. Modal spells / choose a ...)*
- [x] Number picker - **look done 2026-10-21**, `NumberPicker.java`: big number, -/+ buttons, arrows or wheel, ENTER / ESC, and a `Hint` line that says what the X costs ("you pay {3}{W}{W} - total 5 mana"). **To wire (attended):** `IGuiGame.getInteger`. Demo: `-Dforge.ui3d.openPanel=x`. *(C. X costs)*
- [x] Scry / surveil - **look done 2026-10-21**, `OrderPanel.java` in two-row mode: drag within or between rows, a click sends a card across, positions numbered ("1 = drawn first"). **To wire (attended):** Forge's scry / surveil / `order` decisions. Demo: `-Dforge.ui3d.openPanel=scry|surveil`. *(E. Scry and surveil)*
- [x] Trigger ordering - **look done 2026-10-21**, `OrderPanel` in one-row mode, each trigger shown as its source card with what it does underneath, "1 = resolves last". **To wire (attended):** `IGuiGame.order` for simultaneous triggers. Demo: `-Dforge.ui3d.openPanel=triggers`. *(E. Ordering triggers)*
- [x] Mulligan screen - **look done 2026-10-21**, `MulliganPanel.java`: the hand fanned large over a dimmed table, Keep N / Mulligan (to N-1) (SPACE / M); London mode lifts and dims the cards you click, Confirm lights at exactly N. **To wire (attended):** Forge's mulligan Input and the London bottom choice. Demo: `-Dforge.ui3d.openPanel=mulligan|bottom`. *(A. London mulligan)*
- [x] Mana payment - **look done 2026-10-21** (`TableBase.ManaPlan`): lands that can pay glow green, a click taps one (gold), "Pay {2}{W}{W} (1 of 4 paid)" counts down, ESC cancels. **To wire (attended):** `InputPayMana` / `useMana`. Demo: `-Dforge.ui3d.openPanel=mana`. *(B. Mana payment)*
- [x] End-of-game screen - **look done 2026-10-21**, `EndScreen.java`: opens by itself once when the game ends - "You win!" / "X wins", turns, every player's final life or OUT and the most commander damage they took; Rematch / Next game / Quit; ESC leaves the final board (the GAME OVER band stays). **To wire (attended):** `IGameController.nextGameDecision`. *(E. Game over, concede, next game)*
- [x] ESC menu - **look done 2026-10-21** (`TableInput.openMenu`, on `OptionPanel`): ESC opens it unless it is releasing a TAB-held camera. Sound / game log / follow-the-turn toggle live; Undo and Concede are listed as not wired yet; a key list. **To wire (attended):** concede and undo. *(E. Concede, Undo)*

**Works now, read-only** - the data is already in the `Snapshot`, so these are useful the day
they land:

- [x] Life-change popups - done 2026-10-21 (`TableHud.noticeChanges/drawLifePops`): red "-7" / green "+3" rising and fading from the seat's centre monitor over 1.6 s; changes within 0.35 s add up, so a burst of damage is one number. Nothing on the first snapshot.
- [x] Draw and death animations - done 2026-10-21 (`TableLayout.placeDying/seedFromLibrary`). A card new to your hand starts at your library pad and slides into the fan; a card that leaves the battlefield or the stack for a graveyard or exile pile keeps being drawn for 0.6 s while it slides onto that pad (its last pose still exists when layout runs). Tokens just go. Nothing animates on the first frame. Demo `-Dforge.ui3d.demoEvents=true` now also kills a creature at 2.5 s and draws a card at 5 s.
- [x] Hand cards lift under the mouse - done 2026-10-21 (`TableLayout.placeHand`): the hovered hand card rises 0.35 and stands 12 degrees more upright, eased like every other move; small enough that it still covers the mouse, so it does not flicker. Playable cards already glow via the selectable marker.
- [x] YOUR TURN sweep - done 2026-10-21 (`TableHud.drawYourTurn`): a gold band slides in across the middle, holds, fades (1.7 s). Demo: `-Dforge.ui3d.demoEvents=true` plays a fixed timeline (turn passes at 2 s, life changes at 3 s, your turn again at 4 s) for screenshot checks.
- [x] Each player's floating mana pool - done 2026-10-21: "MANA  B3  R1" under the seat's totals, each symbol in its colour, only while the pool is not empty. `SeatV.mana` (W U B R G C) from `PlayerView.getMana`; `TableHud.drawManaPool`.
- [x] Keyword reminders - done 2026-10-21 (`Keywords.java`): up to four "Flying - can only be blocked by ..." lines under the text in both hover panels, ~40 keywords, whole-word match on the rules text.
- [x] **Marker slabs were invisible** - fixed 2026-10-21. Every slab under a card (Forge's playable/selectable green, the red attacking marker, and now the target / block / paid ones) sat at `-CARD_THICK`, which put its top under the row's band slab, so none of them ever showed. Now `TableLayout.MARKER_DROP` (-0.014): the rim clears the band and stays under the picture.

**Under the floor**

- [x] **`Table3D` split** - done 2026-10-21 (the owner asked). Seven files, one concern each, as a chain of abstract classes (see *How it is put together*). Moved, not rewritten: a line-by-line comparison shows only `private` removed, the `source` field and five section dividers changed; screenshots of five demo states are pixel-identical apart from animation timing. **Add new code to the file for its concern**; a lower layer that needs a higher one declares it `abstract` in `TableBase`. `scratch\probe\SplitTool.java` (member scanner) and `scratch\probe\ImageDiff.java` (screenshot diff) were used and are kept. The demo's tapped lands are now seeded, so screenshots of two runs compare.
- [x] `layout()` allocations - done 2026-10-21. A per-model `ModelInstance` pool (`inst(Model)`, reset once a frame) at the ten per-frame placement sites; transforms and the card-picture material are overwritten on every reuse. Measured on the demo in combat with a 4 MB young generation: **56 young GCs per 12 s before, 20-26 after** (two runs each). The scoreboard and stack frame still allocate (they were under Xenil's claim); the remaining garbage is overlay text, vectors and colours.
- [x] Screenshot mode - done 2026-10-20: `-Dforge.ui3d.shots=<dir>` (+ `shotEvery`) saves frames as PNGs; with the demo's `-Dforge.ui3d.demoPhase/demoLost/demoWinner` and `-Dforge.ui3d.hover=<card>` a known state renders with no game and no mouse.

#### Agreed plan (owner approved 2026-10-21)

The proposal above is **accepted**. Work it in this order. Claim each step in `CLAIMS.md` first.

1. [x] **`Gui3D` forwarding everything - done 2026-10-21.** Reflection `Proxy` over `IGuiGame`,
   installed by `Bridge.attach` through `pch.setGui(...)`; prompt, button labels and selectable
   card ids are mirrored on the way past and read into the `Snapshot`. No behaviour change, and
   nothing is taken over yet (`Gui3D.TAKEN_OVER` is empty, by design).
   `scratch/probe/Ui3dGuiProxyTest.java`: all **105** interface methods forward, every call reaches
   the real UI, an exception from it arrives as itself rather than wrapped, and the prompt
   mirrors correctly. PASS. Off switch `-Dforge.ui3d.input=false` works.
2. **Two safeguards, before any choice method is taken over.** These were the owner's conditions:
   - [x] **Probe the double-click race** (2026-10-21, `scratch\probe\DoubleClickProbe.java`).
         **The race is real**: an unguarded second click passed a step the player never saw at
         every gap tried (0, 40, 150, 400 ms) - Forge posts the next priority decision before a
         second click can arrive. Fixed by `mtg3d.ClickGuard`, which the probe shows holding:
         - **3D clicks**: `ClickGuard.ok/cancel(pch, token)`, where `token = ClickGuard.token(pch)`
           is taken when the prompt is drawn. Delivered on the EDT only if that Input is still the
           active one, otherwise dropped. Safe at every gap.
         - **Forge's own window**: its clicks carry no token, and "the prompt Forge last showed" is
           not one either (Forge refreshes it on the EDT as the old decision closes, after the next
           is already active - the probe tried it). So `ClickGuard.forgeWindow(pch)` returns a
           controller that drops clicks within `DEBOUNCE_MS` (300) of a new decision becoming
           active (timed exactly via the `InputQueue` observer). Safe at 0/40/150 ms; a click
           400 ms later counts as meant for the new prompt. Also stops Forge's own double click.
         - **For Gui3D (step 1)**: in `setGameController`, forward `ClickGuard.forgeWindow(pch)`
           to Forge's window instead of the real controller; send every 3D click through
           `ClickGuard.ok/cancel` (selectCard etc. to be added the same way when step 3 needs them).
   - [x] **A way out of a blocked game thread - done 2026-10-21.** `Handoff` waits for the 3D
         window, a timeout, or the window going quiet, and on either of the last two hands the
         decision back to Forge. It watches a per-frame **heartbeat**, not just a clock: a window
         that has stopped drawing cannot answer, so the fallback fires in a frame or two instead
         of sitting out the timeout, while a player genuinely thinking still gets the full time.
         `H` hands the current decision back on demand. Exactly one answer ever counts, so a
         reply arriving after a handoff is dropped rather than landing on the next decision.
         `scratch/probe/Ui3dHandoffTest.java` covers all five cases. PASS.
3. [x] **Pass priority from the 3D window - done 2026-10-21.** `SPACE` sends a guarded OK, `X` a
   guarded cancel, both carrying the `ClickGuard` token captured when the prompt was drawn, so a
   click is dropped if the game has moved on. `Gui3D` also hands Forge's own window a debounced
   controller (`ClickGuard.forgeWindow`) - without that only one ordering of the race was
   closed. In the demo nothing is installed, so `SPACE` still steps the phase and the two never
   contend for the key. **Not yet played in a real game.**
4. **`many` / `one` / `oneOrNone` through `SearchPanel`**, which is already built and waiting.

A note on the reflection proxy: it adapts automatically when Forge changes a method, which is the
point - but it also means a changed signature fails at **runtime, mid-game**, rather than at
compile time. Worth a startup check that every method the table has taken over still exists.

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
- `Table3D.layout()` rebuilds every card's target placement **every frame**. At these counts that
  is far cheaper than tracking changes and means the view can never drift out of step with the
  snapshot. Animation sits on top without breaking that: the only thing persisted between frames
  is each card's last drawn `Pose` (keyed by card id), eased towards the fresh target. Keep it that
  way; do not "optimise" the rebuild away without replacing the guarantee.
- Graveyard, exile and library are pads with counts, not browsable piles.
- The seated camera is a fixed pose, not a true first-person head position.
- A card shows a drawn stand-in until Forge has downloaded its picture (basic lands included
  since 2026-10-21; pictures downloaded mid-game appear within a few seconds).

---

## Naming: three different things called "Forge"

Say which one is meant. They fail differently, they are fixed differently, and only one of them
is on its way out.

| Say | What it is | Can we change it? |
|---|---|---|
| **Forge rules engine** | `forge.game.*` - the stack, the phases, combat, priority, what is legal | No. Bugs here are reported and worked around, never patched by us |
| **Forge card database** | `res/cardsfolder` - the card scripts, what each card actually does | No, except our own fixes in `forge-data\custom\cards` |
| **Forge UI** | `forge.screens.*`, `CMatchUI`, `VStack`, `FControl` - the Swing window | Yes. This is the part the 3D table is replacing, method by method |

So "the Forge rules engine pushed a stack item with no activating player" and "the Forge UI threw
in `VStack.updateStack`" are two different problems in one crash, and only the second is ours to
do anything about. "Forge crashed" says neither.

## Conventions

Same as the rest of the repo — see the root `CLAUDE.md`. In particular: pull `--rebase` before
working and before every push; never delete or overwrite an original deck; UTF-8 **without BOM**;
and re-run `build-tools.ps1` **and** `build-ui3d.ps1` after pulling anything that touches them.
