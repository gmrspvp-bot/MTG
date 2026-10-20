# Card mechanics - bugs to look at

Cards or abilities that behave wrongly in Forge (rules or AI), as reported by the owners.
Separate from the 3D table's list (`ui3d\README.md`). Noted only until someone picks one up;
claim it in `CLAIMS.md` first.

## Mr House

- [ ] **Dice doubled by Wyll, Blade of Frontiers: Mr. House should see every roll** (owner,
      2026-10-20; reported once before, never written down). Example: Clown Car rolls 4 dice
      with Wyll out, so each die is rolled twice. Clown Car correctly ignores the lower roll of
      each pair, but Mr. House, President and CEO still sees *all* the rolls (each one is a die
      roll), so he should be making tokens for the ignored rolls too. In Forge he does not - he
      only counts the kept results.
- [ ] **Clown Car didn't seem to work** (same game, 2026-10-20). Not yet known what went wrong.
      Note: `forge-data\custom\cards\clown_car.txt` is our own custom script (an AI fix), so
      check that first; `scripts\tools\state-clown.txt` is an old test board for it.
- [ ] **Lotho, Corrupt Shirriff wasn't working** (owner, 2026-10-20, same games). What failed is
      not known yet (it triggers when a player casts their second spell in a turn and makes a
      Treasure - check its exact text before testing). No custom script for it in
      `forge-data\custom\cards\`, so if it is broken it is Forge's own script or the AI.

## Olivia (Xenil's deck)

- [ ] **Belladonna Took seemed to trigger too often** (owner, 2026-10-20). Not checked yet -
      compare how many times it triggered in a game log against its text (how often it may
      trigger per turn). No custom script for it in `forge-data\custom\cards\`, so it is Forge's
      own script.
