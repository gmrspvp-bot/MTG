# AI and rules to-do

The AI side of the to-do list: Forge's AI (`scripts\tools\*.java`, custom card scripts in
`forge-data\custom\cards\`) and house rules. The 3D table has its own list in `ui3d\README.md`
(TODO); card rules bugs go in `CARD-MECHANICS.md`.

Claim anything bigger than a quick fix in `CLAIMS.md` first. Anything that changes how the AI
plays needs an A/B run before it becomes a default (`scripts\run-sims.ps1 -JvmArgs`, 120+ games
an arm; the owner's PC runs 6 at a time at most). Put the result in the commit message.

## Owner's list, 2026-10-23 — not started (do not fix until the owner says)

- [x] **Test the "spare the kill" rule** (2026-10-26: 280 games an arm - with it on, Angels (the runaway leader) won 103 vs 115 off, the rest within noise; it spared a player only 9 times, so it is kept but barely matters. `-Dforge.ai.spareratio` added to try a looser threshold; superseded for now by the threat overhaul's A/B.) (`HumanHooks.leaderToFear` / `spareKills`, on by
      default since 2026-10-22, switch `-Dforge.ai.sparekills`). It never triggered in a
      12-game smoke test, so whether it helps is unknown. Overnight A/B, on vs off, 120 games
      each, with Angels in the pod (the runaway leader it is meant for). If it rarely triggers,
      try a looser leader ratio (1.25 instead of 1.5).
- [ ] **Wyll house rule (owner's call):** Wyll adds an extra die for EVERY die rolled, instead of
      one extra die per roll. Forge plays Wyll as printed - "that many dice plus one", once per
      roll - which is the official ruling, so this would be a house rule like the ignored-dice
      one: `DiceIgnoreHook` / the RollDice replacement, a switch, a note in `CARD-MECHANICS.md`.
      Only if the owner wants it.
- [x] **Rin and Seri: lethal shot.** (done 2026-10-26, Stoneforge, general: any activated single-target damage ability that would kill an opponent now fires at them - lowest life first, unless the kill-sparing rule says keep them. `AiPatches.lethalShot`, SHOTAI log, -Dforge.ai.lethalshot=false. Test LethalShotProbe: Rin and Seri + 5 Dogs = 6 at a player on 4 - shot; on 9 - none. Note: Rin and Seri's damage counts DOGS, its lifegain Cats.) The AI should use Rin and Seri's tap ability whenever the
      damage (Dogs it controls) kills a player. Per round the AI already uses it nearly as often
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
- [x] **Mr House: Treasures into Mr. House's own ability.** (checked 2026-10-26, Stoneforge: in 600 sim games of 2026-10-23 the AI paid 228 of 441 activations with Treasures, 463 Treasures in all - 2 extra dice each on average. `AIManaPref$ Treasure` in our Mr. House script does it; nothing to fix.) Each Treasure mana adds a die; the owner
      won a game spending 4 Treasures into it. Our script already says `AIManaPref$ Treasure`;
      check in sims whether the AI actually activates it with Treasures and how many dice it rolls.
- [ ] **Threat detection overhaul - the owner's top AI complaint (2026-10-23).** BUILT 2026-10-26 (Stoneforge) behind `-Dforge.ai.danger=true`: threat() adds life lead, engines, recursion and commander-damage pressure; removal (destroy/exile and single-target burn) re-aims at the leader's board (`ThreatRetarget.leaderSwap`). DangerProbe passes. **Waiting on the overnight A/B (scratchdanger-runs.txt, on vs off) before it becomes the default.** Original plan: One rating of
      "who is winning", used by attacks, removal and the spare-the-kill rule alike. Today
      `HumanHooks.threat()` counts creature power (flyers 1.5x, lifelink extra), +1 per nonland
      permanent, a card-quality score and +8 for a commander - and misses:
      - **life total / who is winning**: Angels on 111 life rated like a player on 20;
      - **engines** that grow every turn: Rin and Seri (damage = Dogs), Mr. House (dice -> Robots),
        Fireweaver / Artillerist pings, lifegain each turn;
      - **the clock**: how many turns each player needs to kill someone (combat through blockers,
        commander damage, drain per turn) - what humans read first;
      - **recursion**: Seluma / Karmic Guide / Sun Titan bring things back, so one removal spell
        means little against that board;
      - **one judgement everywhere**: removal uses Forge's own per-creature evaluation, so it aimed
        at what threatened the AI itself (Scorching Shot on Loki, not Lyra, 2026-10-22), while
        attacks use `threat()`.
      Plan: a per-player "danger" score (clock + engines + recursion + life, board as now), used
      by retarget / goForKill / spareKills and by a removal re-aim (like `retargetToBest`) that
      prefers the leader's key creature. A/B against today's rating with Angels and Rin and Seri
      in the pods, and check the owner's games for the cases above.
- [ ] **Learn from the new decks once there are more human games:** Baylen, Eldrazi, Frodo, Eivor
      (added 2026-10-22) - now part of the per-deck routine below (`scripts\deck-style.ps1`).

## Per-deck play styles from human games (since 2026-10-27)

Different decks need to be played differently (owner, 2026-10-26), so the AI should learn each
deck from the people who play it. Routine, semi-automatic:

1. After games are played, run `scripts\deck-style.ps1`. It compares every deck's recorded human
   games with its sim games (decks matched by commander) and writes `scratch\deck-style\index.md`
   (games per deck, how many are new) and one report per deck: a People / AI table, suggestions
   for the biggest gaps, and per-card notes (cast once affordable, held how long, instant speed,
   ability use).
2. Claude reads the reports, drops what is small-sample noise, draws or misplays, and brings the
   rest to the owner as suggestions.
3. Only what the owner approves gets built, per deck, with an A/B run before it becomes a default.

Leads from the first run (2026-10-27, 61 recorded games vs ~7,200 sim player-games), unchecked -
do not build until the owner says:
- [ ] **All decks: the AI casts most spells after combat** (51-63% of own-turn spells in main 2;
      people about 11-19%).
- [ ] **Mr House:** people activate The Deck of Many Things 5.7 times a game it is out, the AI 0.8
      (Jan Jansen 1.5 vs 0.2, Treasure Chest 1.0 vs 0.3); the AI over-uses Vexing Puzzlebox (4.1
      vs 1.5). People attack on 19% of their turns with creatures, the AI 50%, but with more
      creatures when they do (6.5 vs 4.3).
- [ ] **Captain America:** people hold Swords to Plowshares / Path to Exile for 3-4 turns and
      cast them on opponents' turns; the AI fires them at once. People attack on 53% of turns
      with creatures, the AI 81%.
- [ ] **Angels:** people attack the life leader (53% vs AI 35%) more than the lowest-life player
      (33% vs AI 51%) and block rarely (10% vs 30%).
- Decks with fewer than 3 human games (Baylen, Eivor, Frodo, Abzan Siege) are hints only; Alec
  (Athreos) and Abzan Siege had no sim games in the folders read.
