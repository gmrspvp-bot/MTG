# Card mechanics - bugs to look at

Cards or abilities that behave wrongly in Forge (rules or AI), as reported by the owners.
Separate from the 3D table's list (`ui3d\README.md`). Noted only until someone picks one up;
claim it in `CLAIMS.md` first.

## Fixed

- [x] **Lotho, Clown Car and Mr. House "not working" in the owner's game of 2026-10-20 23:31**
      (fixed 2026-10-21, Stoneforge). Not the cards: every one of the HUMAN player's mandatory
      triggers was being dropped. Forge puts a human's trigger on the stack through
      `getAbilityToPlay(card, [that trigger], null)`; the 3D table had taken that call over two
      minutes before the game (`Gui3D.takeAbility`) and applied the rules for a *click* - one
      ability, play it only if "playable" - and a trigger is never playable, so it answered
      nothing. Lotho missed five triggers, Clown Car's dice never rolled, so Mr. House never saw a
      roll. Only Blind Obedience (extort, a different path) worked. Fixed: with no click behind
      the call, a single option comes straight back, as in Forge's own window.
      Tests: `scratch\probe\Ui3dTakeoverTest.java` (22/22, "a trigger (no click) is never
      dropped"); `scratch\probe\HouseCardsProbe.java lothoother none human x gui3d` - a human's
      Lotho through the 3D wrapper triggers (-1 life, +1 Treasure). The same probe shows all
      three cards working under the AI.

## Not a bug

- **Belladonna Took "triggers too often"** (Olivia). It triggers for every token that enters,
  and Olivia's Smothering Tithe makes a Treasure on every opponent's draw - so 2-3 times on most
  opponents' turns. Its counting (1st: life, 2nd: draw, 3rd: counter) checked against the log
  of 2026-10-21 and correct.
- **Clown Car's "SVar 'EvenResults' not found" console lines**: harmless - Forge builds the
  stack text before the dice are rolled. Even results do put +1/+1 counters on the car.

## Open - needs the owner's call

- [ ] **Mr. House and the die Wyll ignores.** With Wyll, Blade of Frontiers out, Clown Car's X
      dice become X+1 with the lowest ignored. Forge triggers Mr. House only for the kept dice
      (probe: 5 rolled, 4 kept, House triggered for the kept 4s only). The owner expects the
      ignored die to count too. As far as can be told, Forge follows the rules here (an ignored
      roll is treated as not rolled), so changing it would be a house rule - only if the owner
      wants that.
