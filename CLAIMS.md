# Who is working on what

Two people work on this repo, each with their own Claude, and neither can see the other's working
tree. This file is how we avoid two agents rebuilding the same thing in different directions.

It exists because git only tells you about a collision **after** both sides have written code.
A claim is cheap; an afternoon of thrown-away work is not.

## Rules

1. **Pull first.** `git pull --rebase`, then read this file.
2. **Claim before you write code.** Add a row below, commit it on its own with a message starting
   `claim:`, and **push it immediately**. A claim sitting in your local tree is worthless — the
   other agent can only see what reached the remote.
3. **Work.** Normal commits, as often as you like.
4. **Release** by deleting your row. Do it in the same push as the finished work, or in a commit
   starting `release:` if you are stopping early.
5. **If you lose a race** — your push is rejected, and after rebasing you find the other agent has
   claimed the same item — **theirs wins**, because their claim reached the remote first. Take
   something else. `git log --oneline -- CLAIMS.md` settles any argument about who was first.
6. **Stale claims.** A claim with no commits against it for **24 hours** may be taken over. Say so
   in the commit message that takes it, and do not delete their row silently.
7. **Small fixes don't need a claim.** A typo, a one-line fix, a deck list tweak — just do it.
   Claim anything that will take more than one sitting or touch a file the other agent is likely
   to be in.

Nothing here is enforced. It only works if both agents actually push the claim before starting.

## Current claims

| Item | Agent | Claimed (UTC) | Notes |
|---|---|---|---|
| `ui3d` - phase tracker visuals | Xenil's Claude | 2026-10-20 | `Table3D`: the `drawPhasePanel` method and one new shapes pass for its background. Nothing else. Not the scoreboard (dmara's claim). |
| `ui3d` - hanging stadium scoreboard (four-faced, above the stack, fades out overhead) | dmara's Claude | 2026-10-20 | `Table3D`: new `scoreboard` methods/models, one call in `render()`, create/dispose lines. No camera/input or phase strip (Xenil's claim) - shows turn + life only, no phase. |
<!-- new claim rows go directly under the separator above, not at the end of the file -->

## Not claimed, and worth knowing about

These are areas the two of us keep touching, so check here before a big change:

- `scripts\tools\*.java` — the AI patches and agent hooks. Both owners' work lands here.
- `scripts\play.ps1` — runs on **both** machines. Prefer a parameter with a safe default over
  changing behaviour for everyone (see `-Skin`, `-Ui3d`).
- `decks\*.dck` — never delete or overwrite someone else's deck; improvements are a new `vN`.
- `ui3d\` — the second owner's 3D UI. Has its own TODO in `ui3d\README.md`.
