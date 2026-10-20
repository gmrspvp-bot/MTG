# 3D table - play-test checklist

Everything below is built and hooked up to Forge, but none of it has been tried in a real game
yet. Play a normal game in the 3D window and tick things off as you meet them. You don't need
to do it all in one game.

**Start a game with the 3D window:**

```
powershell -ExecutionPolicy Bypass -File scripts\play.ps1 -Ui3d
```

**If something is wrong:** click the red cog (top right of the 3D window) and say what you
expected. It saves the game state with your note, so the bug can be found later. If the 3D
window ever stops answering, press **H** to hand the question back to Forge's window, or wait
about 45 seconds and it hands it back by itself.

Each line: **what to do** -> what should happen.

---

## 1. Starting the game

- [ ] **Who goes first:** click a player's seat -> that player starts.
- [ ] **Mulligan screen:** your opening hand appears big in the middle with **Keep 7** and
      **Mulligan**. Keep (or SPACE) -> the game starts with that hand.
- [ ] **Mulligan once:** press Mulligan (or M) -> a new hand; the button now says "to 5".
- [ ] **London bottom:** after a mulligan, the screen asks you to put cards on the bottom -> click
      cards (they lift and dim), Confirm lights at the right number, the cards go to the bottom.

## 2. A normal turn

- [ ] **YOUR TURN** gold banner sweeps across when your turn starts, with a short sound.
- [ ] **Other players' turns:** "CAPTAIN'S TURN" (blue band) when the turn passes; on your own
      turn a small gold strip names each step (upkeep, draw, main 1, combat, main 2, end step).
- [ ] **YOUR RESPONSE:** hold priority on someone else's turn for 10 seconds -> an amber band in
      the 3D window (and nothing pops up on Forge's window).
- [ ] **Piles:** library, graveyard and exile are piles that grow; a creature dying lands on top of
      its graveyard pile.
- [ ] **An ability on the stack** marks its card on the table in teal.
- [ ] **Drawing:** the drawn card slides from your library into your hand.
- [ ] **Hand hover:** the card under the mouse rises; the big card picture shows on the left with
      keyword explanations underneath (flying, lifelink...).
- [ ] **Play a land / cast a spell:** click it in your hand -> it plays.
- [ ] **A click that does nothing** (casting with no mana, clicking at the wrong time) -> a short
      red message beside the card says why (it's a best guess - Forge doesn't give a reason).
- [ ] **Pass:** SPACE passes priority. X cancels.
- [ ] **Game log** (top right) shows the last 8 things that happened. L hides it.

## 3. Paying mana

- [ ] **Paying by hand:** when Forge can't tap your lands automatically, a green **Pay mana**
      banner appears and the lands that can pay glow green -> click one, it turns gold and taps;
      once paid, the banner goes.
- [ ] **ESC while paying** cancels the spell.
- [ ] **Floating mana** (from a ritual, or mana left over) shows as "MANA  R2" under your name.

## 4. Spells that ask questions

- [ ] **Targets:** cast a spell with a target (Swords to Plowshares...) -> a violet
      **Choose targets** banner, legal targets glow violet, everything else dims, and a violet arc
      follows the mouse from your spell. Click a target -> it turns gold and Forge carries on.
- [ ] **A player as target:** in the same mode, click a player's seat or table monitor.
- [ ] **ESC while targeting** cancels the spell.
- [ ] **Choose one** (a modal spell, a colour, a card type, a player) -> a column of big buttons;
      click one or press its number.
- [ ] **Choose two** (Cryptic Command style) -> "0 of 2 chosen", Confirm lights at two.
- [ ] **X costs / choose a number** -> a big number with - and +, arrows or wheel, ENTER.
- [ ] **Searching a library / picking from a graveyard** -> the card grid; click the card(s).
- [ ] **Scry:** first a card grid to pick which go to the **bottom** (pick none to keep all on top),
      then an **order** panel for the ones on top - drag them, 1 = drawn first.
- [ ] **Surveil:** same idea, graveyard instead of bottom.
- [ ] **A card with two abilities** (Weathered Wayfarer: cast it or search; a land with two
      colours) -> a column of buttons, one per ability you can use now; click one. ESC plays nothing.
- [ ] **An ability on the stack** shows its text in teal under the card, so two abilities of the
      same card can be told apart.
- [ ] **Clicking your own untapped land** when nothing is being paid for -> a warning first;
      click it again within 3 seconds to tap it for floating mana.
- [ ] **Several triggers at once** (e.g. two Angels' abilities) -> an order panel, drag into order.

## 5. Combat

- [ ] **Declaring attackers:** during declare attackers, your creatures that can attack glow green.
      Drag one onto an opponent (their table monitor, their side of the table, or one of their
      cards) -> it turns red with a red arc to them. Drop it back on itself to take it off.
      SPACE declares.
- [ ] **Attacking different players** with different creatures in the same combat.
- [ ] **Blocking:** when you're attacked, the attackers show red; drag one of your creatures onto
      an attacker -> blue arc. Several blockers on one attacker each get an arc. SPACE confirms.
- [ ] **Damage split:** an attacker of yours blocked by two or more creatures -> the **Assign combat
      damage** panel; -/+ per blocker, lethal goes green, Auto fills it, Confirm.
      (If your attacker has **trample**, Forge's own dialog asks instead - that's expected.)
- [ ] **Attack and block arcs** for other players' combats show (red / blue).
- [ ] **Death:** a creature that dies slides onto its owner's graveyard pad.
- [ ] **Life changes** float up as red "-7" / green "+3" by the player's life total.

## 6. The table

- [ ] **Browse a graveyard / exile:** click any player's GY or EXI pad -> the cards open in the grid.
- [ ] **Equipment / auras** sit tucked under the creature they're on.
- [ ] **Tokens** have a silver frame and a TOKEN banner; **face-down** cards show FACE DOWN and
      their power/toughness.
- [ ] **Command zone:** your commander sits on the bronze pad when it's not on the battlefield,
      with "tax +N" after it's been cast.
- [ ] **Monarch / initiative / poison / energy** show under the player who has them.

## 7. Menu and settings (ESC)

- [ ] **ESC opens the menu** (unless you were holding the camera with TAB - then it releases it).
- [ ] **Sound / Game log / Follow the turn** toggle and show their new state.
- [ ] **Graphics:** High -> Medium -> Low. The frame rate changes at once; anti-aliasing changes
      the next time the 3D window opens. Try Low if the window stutters.
- [ ] **Undo** after tapping a land for mana -> the land untaps.
- [ ] **Concede** asks "Concede?" first; Yes -> you lose the game.

## 8. The end

- [ ] **End screen:** when the game ends, a results panel (winner, turns, life, commander damage)
      with **Rematch / Next game / Quit**. Each button should do what it says.
- [ ] **ESC on the end screen** closes it and leaves the final board to look at.

## 9. Joining someone else's game (new 2026-10-21)

Until now the 3D table only opened for games hosted on your own PC. It should now also open
when you **join** a game someone else is hosting. Start with `Play 3D.cmd` as usual, then join
their game from Forge's online menu.

- [ ] **The 3D window opens** when the joined game starts, showing the host's table with you in
      your own seat. (Forge's window stays open behind it, as in a local game.)
- [ ] **Your hand, life totals and the battlefield** update as the game goes.
- [ ] **Playing a card / SPACE to pass / OK and Cancel** work from the 3D window.
- [ ] **Targets, declaring attackers and blockers, paying mana** - these modes are switched on
      from Forge's prompt text in a joined game, so they are the most likely to need fixing.
      Note any that don't appear.
- [ ] **The game is recorded on your PC** (new 2026-10-20, 3D on or off): after a joined game,
      `scratch\play-logs\` has a `game_<time>_joined.log` with your turns, lands, spells, attacks
      and blocks. Spells and combat are the parts not yet seen working in a real game.
- [ ] **Not available in a joined game** (expected, not a bug): monarch / initiative markers and
      "who has passed priority" in the left panel.

---

**Anything that went to Forge's window instead of the 3D one** is worth noting too (what it was
asking) - those are the questions still left to bring across.
