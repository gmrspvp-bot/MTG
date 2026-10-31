# Card mechanics - bugs to look at

Cards or abilities that behave wrongly in Forge (rules or AI), as reported by the owners.
Separate from the 3D table's list (`ui3d\README.md`). Noted only until someone picks one up;
claim it in `CLAIMS.md` first.

## Fixed

- [x] **Villainous choice lets a player pick an impossible option** (fixed 2026-10-29,
      Stoneforge: `scripts\tools\VillainousChoiceHook.java`, an agent hook on
      `VillainousChoiceEffect.resolve` - Forge's own resolve, with each player offered only the
      options they can carry out: no discard with an empty hand, no sacrifice with nothing that
      matches `SacValid`; one option left is taken without asking, logged `VILLAINAI`. Off with
      `-Dforge.villainous.legal=false`. Test `scratch\probe\VillainousProbe.java`: Eggman vs an
      empty hand -> Myr Battlesphere comes in (with the switch off: the opponent dodges it);
      vs a hand of two -> free choice; The Dalek Emperor vs no creatures -> the Dalek token.
      Applies to played games and sims.) Reported by the owner, 2026-10-29, Dr.
      Eggman). Rule 701.55b (Comprehensive Rules of 2025-11-14): "a player may not choose an
      option that is illegal or impossible" - so an opponent with an empty hand cannot pick Dr.
      Eggman's "discards a card" and must let him put the Construct/Robot/Vehicle in. Forge 2.0.15
      predates that wording: `VillainousChoiceEffect.resolve` hands every option in `Choices$` to
      `chooseSpellAbilitiesForEffect` with no legality check, and the opponents in the owner's
      game of 2026-10-29 (15:10, 15:16, 15:28, `console_2026-10-29_140835.log`) chose the discard
      each time. Applies to every villainous choice card (Doctor Who and since). Fix idea: an agent
      hook on `VillainousChoiceEffect.resolve` that drops options which cannot be done for that
      player (discard with no cards in hand, sacrifice with nothing valid, ...) before the choice.

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

## House rules (owner's call)

- [x] **A player who concedes sits out until their next upkeep, then leaves** (owner,
      2026-11-01; done by Stoneforge). Conceding no longer removes a player on the spot: their
      seat passes priority, attacks and blocks with nothing, says no to every "may" and never
      pays an "unless" cost, while choices the rules force (a sacrifice, a discard) are made for
      them by the AI. Their permanents stay and can be attacked. At the start of their next
      upkeep they leave as a normal concede. Whatever they were being asked when they conceded
      is answered with nothing (blocks or attackers they had picked are taken back). A normal,
      immediate concede still happens when no other human is playing (a game against the AI
      just ends) or when only one player would be left; anyone sitting out then leaves too.
      `scripts\tools\ConcedeHook.java` (agent hook on `PlayerControllerHuman.concede`, removal
      from `CombatGuardHook`); SeatClaimHook never hands a sitting-out seat to someone joining.
      `-Dforge.concede.sitout=false` turns it off. Test `scratch\probe\ConcedeProbe.java`
      (a real four-player game): conceding on turn 3 -> out at the conceder's turn-4 upkeep;
      conceding in their own main phase on turn 4 -> sits out turns 5-7, out at the turn-8
      upkeep; with no other human -> out at once.

- [x] **Ignored dice are still seen by "whenever you roll" cards** (owner, 2026-10-21; done by
      Stoneforge). A die that an "ignore the lowest" effect throws away (Wyll, Blade of
      Frontiers, or any card like it) still triggers every "whenever you roll" card (Mr. House,
      or any card like it) - **except** the card whose own ability made the roll. So Clown Car
      with Wyll: Clown Car uses the 4 dice it keeps, Mr. House triggers on all 5. Mr. House's own
      {4},{T}: Wyll's extra die stays ignored for House. Forge itself (and, as far as can be told,
      the rules) never counts an ignored die, so this is `scripts\tools\DiceIgnoreHook.java`
      (three agent hooks on RollDiceEffect / TriggerRolledDie). Applies to sims too.
      `-Dforge.dice.seeignored=false` turns it off. Test: `scratch\probe\HouseCardsProbe.java`
      `housewyll` / `houseability` with `none ai x x high` (dice forced to 4-6): Clown Car 4 kept
      + 1 ignored -> 5 House triggers; House's own roll 1 kept + 1 ignored -> 1.
