# Decks

Only the latest version of each deck lives here, so Forge's deck picker shows only current decks. Older versions, originals included, are in `archive\` (moved there 2026-10-22 at the owner's request); never delete a deck.

This folder is the source of truth. Every launch, `play.ps1` makes `forge-data\decks\commander` match it exactly: decks that are not here, or that differ from the copy here, are **moved** to `forge-data\decks-archive\` (never deleted) and the version from here is copied in. That keeps both machines showing the same picker without anyone tidying by hand — before it did that, the second owner's picker still listed all 31 decks after the archive commit, and his `Angels` had been resaved through Forge's deck editor as `Deck Type=Constructed` with the `[commander]` section gone, which a copy-if-newer rule could never have put right. `play.ps1 -DecksOnly` does just that alignment and exits, without starting Forge.

**Use these in simulation runs:**

| Deck | File to run | Notes |
|---|---|---|
| Captain America | `Captain America v7.dck` | **Default.** Since 2026-10-19 10:55 this is the former v8 (the owner had the first v7 replaced): v6 + 25 swaps chosen with the owner (Kediss, Stoneforge, Kaldra, Embercleave, Akroma's Will, Cloudsteel Kirin, The Reaver Cleaver, Genji Glove, Leyline Axe, Captain America's Shield, Blasphemous Act, Winds of Abandon...); most of these cards aren't owned yet. Full list in the deck's Description. Results logged as "Captain America v7" before that time are the first v7 (kept in git history) |
| Mr House | `Mr House v4.dck` | v2 fixed the mana base (14% → 22.5%); v3/v4 bulk upgrades |
| Doom Prevails | `Doom Prevails v3.dck` | bulk upgrades |
| Tidus | `Tidus v3.dck` | bulk upgrades |
| Deadpool | `Deadpool v3.dck` | bulk upgrades |
| Angels | `Angels.dck` | Seluma, Light of Aysen. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Olivia | `Olivia.dck` | Olivia, Opulent Outlaw. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Scion | `Scion.dck` | Scion of the Ur-Dragon. Added Oct 2026 from the second owner's collection; no upgrades yet |
| Tinybones | `Tinybones.dck` | Tinybones, Trinket Thief. Mono-black discard, 34 Swamps. Added Oct 2026; no upgrades yet |

Archived in `archive\`: Captain America original to v6 (v6 was the default for every test up to 2026-10-18), Deadpool original and v2, Doom Prevails original and v2, Mr House original to v3, Tidus original and v2. Neither `play.ps1` nor `run-sims.ps1` looks in `archive\`: to sim an old version again, copy it back up for the run. Also here: `Caelorna - Octopus Fortress.dck`, `Caesar - Bulk Legion.dck`.

Note: `scripts\convert-manabox.ps1` regenerates the *original* `.dck` files from the ManaBox exports; it never touches the v2 files. Running it now writes the originals back into this folder, next to the latest versions; move them to `archive\` again afterwards.
