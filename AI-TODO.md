# AI and rules to-do

The AI side of the to-do list: Forge's AI (`scripts\tools\*.java`, custom card scripts in
`forge-data\custom\cards\`) and house rules. The 3D table has its own list in `ui3d\README.md`
(TODO); card rules bugs go in `CARD-MECHANICS.md`.

Claim anything bigger than a quick fix in `CLAIMS.md` first. Anything that changes how the AI
plays needs an A/B run before it becomes a default (`scripts\run-sims.ps1 -JvmArgs`, 120+ games
an arm; the owner's PC runs 6 at a time at most). Put the result in the commit message.

## Owner's list, 2026-10-23 — not started (do not fix until the owner says)

- [ ] **Test the "spare the kill" rule** (`HumanHooks.leaderToFear` / `spareKills`, on by
      default since 2026-10-22, switch `-Dforge.ai.sparekills`). It never triggered in a
      12-game smoke test, so whether it helps is unknown. Overnight A/B, on vs off, 120 games
      each, with Angels in the pod (the runaway leader it is meant for). If it rarely triggers,
      try a looser leader ratio (1.25 instead of 1.5).
- [ ] **Wyll house rule (owner's call):** Wyll adds an extra die for EVERY die rolled, instead of
      one extra die per roll. Forge plays Wyll as printed - "that many dice plus one", once per
      roll - which is the official ruling, so this would be a house rule like the ignored-dice
      one: `DiceIgnoreHook` / the RollDice replacement, a switch, a note in `CARD-MECHANICS.md`.
      Only if the owner wants it.
- [ ] **Rin and Seri: lethal shot.** The AI should use Rin and Seri's tap ability whenever the
      damage (Cats it controls) kills a player. Per round the AI already uses it nearly as often
      as the humans (0.44 vs 0.55), so an every-turn rule is not worth it - the AI taps out and
      has no mana for it (the same reason the dice hold did nothing).
- [ ] **Simulation-only copies of Olivia and Tidus** (owners' agreement first - not our decks):
      Forge's AI cannot play Signets or filter lands (Olivia has 5 such cards, Tidus v3 2, Alec 3,
      Baylen 2; `scratch\probe\AiUnplayableProbe.java` lists them). A custom card script cannot
      fix that without changing the cards' rules for human players too, so the fix is AI-friendly
      copies for sims (Talismans / Arcane Signet, duals) - small effect, a dead card or two a deck.
- [ ] **Mana discipline - the biggest gap with the humans.** Humans keep Treasures, rocks or open
      mana for their engines (Mr. House dice, Captain America throws, Rin and Seri) and win through
      triggers; the AI spends everything in its main phases and wins through combat. Holding lands
      back for dice did not help (overnight A/B 2026-10-22, now off). What is missing is judgement:
      when an activation is worth more than another spell.
- [ ] **Mr House: Treasures into Mr. House's own ability.** Each Treasure mana adds a die; the owner
      won a game spending 4 Treasures into it. Our script already says `AIManaPref$ Treasure`;
      check in sims whether the AI actually activates it with Treasures and how many dice it rolls.
- [ ] **Removal aimed at the table's leader,** not only at what threatens the AI itself (2026-10-22:
      Scorching Shot on Loki while Angels had Lyra and Seluma out and 100+ life).
- [ ] **Count runaway life as a threat** in `HumanHooks.threat()` (Angels on 111 life went
      unpunished; life total is deliberately not counted today).
- [ ] **Learn from the new decks once there are more human games:** Baylen, Eldrazi, Frodo, Eivor
      (added 2026-10-22), the same way as Mr House (`scratch\house-style.ps1`).
