# Decks

Only the latest version of each deck lives here, so Forge's deck picker shows only current decks. Older versions, originals included, are in `archive\` (moved there 2026-10-22 at the owner's request); never delete a deck.

This folder is the source of truth. Every launch, `play.ps1` makes `forge-data\decks\commander` match it exactly: decks that are not here, or that differ from the copy here, are **moved** to `forge-data\decks-archive\` (never deleted) and the version from here is copied in. That keeps both machines showing the same picker without anyone tidying by hand — before it did that, the second owner's picker still listed all 31 decks after the archive commit, and his `Angels` had been resaved through Forge's deck editor as `Deck Type=Constructed` with the `[commander]` section gone, which a copy-if-newer rule could never have put right. `play.ps1 -DecksOnly` does just that alignment and exits, without starting Forge.

## Who owns what

Every deck is named `<owner> - <deck>` (2026-10-30, at the owner's request), in the file name and in the
`Name=` the Forge deck picker shows, so the picker groups by owner. `Other` is for decks nobody in the pod
has claimed. Renaming a deck means renaming both, or the picker and the file disagree.

| Owner | Decks |
|---|---|
| Dominic | Adrix and Nev, Angels, DackToCapsFace, Frodo, Haytham Kenway, Olivia, Scion, Sigurd, Tinybones |
| Alec | Athreos (was `Alec.dck`), Eivor, Eldrazi, Kaalia, Kotori |
| Derand | Captain America v7, Deadpool v3, Doom Prevails v3, Dr. Eggman, Mr House v4 |
| Kieran | Kratos and Atreus, Rin and Seri |
| James | Satya |
| Claude | Neyali (was `Claudes Neyali.dck`) |
| Other | Baylen, Caelorna - Octopus Fortress, Caesar - Bulk Legion, Cloud, Goreclaw, Tidus v3, Toph, Vivi, Zinnia |

Sim logs record the deck name, so a deck's name now carries its owner's prefix into every new
`results\` run. `scripts\house-stats.ps1` and `scripts\dice-audit.ps1` match Mr House by deck name and
were widened to accept the prefix; they still read the older logs that have none. Decks in `archive\`
keep their original names - they are history, and `play.ps1` and `run-sims.ps1` never look there.

**Use these in simulation runs:**

| Deck | File to run | Notes |
|---|---|---|
| Captain America | `Derand - Captain America v7.dck` | **Default.** Since 2026-10-19 10:55 this is the former v8 (the owner had the first v7 replaced): v6 + 25 swaps chosen with the owner (Kediss, Stoneforge, Kaldra, Embercleave, Akroma's Will, Cloudsteel Kirin, The Reaver Cleaver, Genji Glove, Leyline Axe, Captain America's Shield, Blasphemous Act, Winds of Abandon...); most of these cards aren't owned yet. Full list in the deck's Description. Results logged as "Captain America v7" before that time are the first v7 (kept in git history) |
| Mr House | `Derand - Mr House v4.dck` | v2 fixed the mana base (14% → 22.5%); v3/v4 bulk upgrades |
| Doom Prevails | `Derand - Doom Prevails v3.dck` | bulk upgrades |
| Tidus | `Other - Tidus v3.dck` | bulk upgrades |
| Deadpool | `Derand - Deadpool v3.dck` | bulk upgrades |
| Angels | `Dominic - Angels.dck` | Seluma, Light of Aysen. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Olivia | `Dominic - Olivia.dck` | Olivia, Opulent Outlaw. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Scion | `Dominic - Scion.dck` | Scion of the Ur-Dragon. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Tinybones | `Dominic - Tinybones.dck` | Tinybones, Trinket Thief. Mono-black discard, 34 Swamps. Added Oct 2026; no upgrades yet |
| Baylen | `Other - Baylen.dck` | Baylen, the Haymaker (Naya). Added 2026-10-22 from `manabox\decks\baylen.txt`, printings kept. The AI can't play Sungrass Prairie or Bolt Bend |
| Kotori | `Alec - Kotori.dck` | Kotori, Pilot Prodigy (Azorius vehicles/artifacts): 14 Vehicles, 35 artifacts, 34 lands. Added 2026-10-29 by the owner; printings kept as supplied, no upgrades yet |
| Haytham Kenway | `Dominic - Haytham Kenway.dck` | Haytham Kenway (Azorius Knights). Built 2026-10-29 to a NZD 800 Card Kingdom budget, then revised the same day against EDHREC (706 decks; Knights 52 vs Voltron 2, so go-wide was the consensus too). Herald of Hoofbeats gives every other Knight horsemanship - effectively unblockable - and Shields of Velis Vel turns a whole opponent's board into Assassins, which Haytham's protection then walls off. Not yet simmed, and most of it isn't owned |
| Goreclaw | `Other - Goreclaw.dck` | Goreclaw, Terror of Qal Sisma (mono-green stompy). Built 2026-10-29 by Claude as a deck tuned to how the Forge **AI** actually plays, using this repo's own measured gaps (`AI-TODO.md`): it taps out every main phase, attacks on 50-81% of turns, fires removal on sight and barely uses activated abilities. So: one colour (no mana-base failures), ramp to tap out into, attack triggers instead of activations (Goreclaw, Pathbreaker Ibex, Toski), trample everywhere to beat the AI's over-blocking, and few fat threats rather than a wide board the pod's sweepers punish. Heroic Intervention and Nykthos were cut for exactly this reason. 0 Game Changers - every green one needs judgement the AI hasn't got. USD 374 / NZD 666 at Card Kingdom; not yet simmed |
| Sigurd | `Dominic - Sigurd.dck` | Sigurd, Jarl of Ravensthorpe (Naya). Added 2026-10-30 by the owner; printings kept as supplied, no upgrades yet. A Saga deck, which is what Sigurd is for: his boast puts a lore counter on one of your Sagas or takes one off, and every lore counter added puts a +1/+1 counter on a creature. 22 Sagas, 35 enchantments, 36 lands, with Satsuki, The Ozolith, Power Conduit, Nesting Grounds and Karn's Bastion to move counters around. 1 Game Changer (Worldly Tutor) |
| Kaalia | `Alec - Kaalia.dck` | Kaalia of the Vast (Mardu Angels/Demons/Dragons). Added 2026-10-30 from Alec; printings kept as supplied, no upgrades yet. 99 + commander, 32 lands, 0 Game Changers. **Has a 3D model** - `ui3d\models\kaalia of the vast.g3dj` matches it by name, so it stands on the card with no wiring. 32 lands is lean for a curve with this much six-and-up in it; Alec's call, not changed |
| Kratos and Atreus | `Kieran - Kratos and Atreus.dck` | Kratos, Stoic Father + Atreus, Impulsive Son (Partner: Father & Son, Jeskai). Kieran's, added 2026-10-31. God tribal, which is what Kratos wants - he takes an experience counter whenever you attack with a God or a God dies - so 15 Gods plus Arcane Adaptation, Maskwood Nexus and Leyline of Transformation to make everything else one, and Banner of Kinship, Urza's Incubator and Kindred Discovery to cash the tribe in. 98 + two commanders, 38 lands, 0 Game Changers. Mana base fixed 2026-10-31 at Kieran's request, lands only: blue had 13 guaranteed sources against 29 pips and seven double-blue cards. Temple of the False God -> Mystic Monastery, Diamond City -> Raugrin Triome, Exotic Orchard -> Seachrome Coast, and one Mountain -> Island. Now W 19 / U 17 / R 18 against pips of 27/29/29, and four lands make all three colours instead of two. Still 38 lands; no spell was touched. **Both commanders have 3D models**, and their weapons light up while the two are on the battlefield together - see `ui3d\models\README.md`, *Lighting part of a model*. Four cards were supplied under Secret Lair / Marvel names Forge 2.0.15 does not carry and are written under the names it does: Battle of Olympus -> World at War, Castle Shimura -> Eiganjo Castle, Monica, the Marvel -> Aurelia, the Warleader, Pierre's Truce -> Dawn's Truce. Same cards, same rules text, different printing |

Archived in `archive\`: Captain America original to v6 (v6 was the default for every test up to 2026-10-18), Deadpool original and v2, Doom Prevails original and v2, Mr House original to v3, Tidus original and v2. Neither `play.ps1` nor `run-sims.ps1` looks in `archive\`: to sim an old version again, copy it back up for the run.

Note: `scripts\convert-manabox.ps1` regenerates the *original* `.dck` files from the ManaBox exports; it never touches the v2 files. Running it now writes the originals back into this folder, next to the latest versions; move them to `archive\` again afterwards.
