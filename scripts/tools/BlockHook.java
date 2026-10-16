// Runs after Forge's AI has chosen its blockers (EvalCacheAgent routes
// forge.ai.AiBlockController.assignBlockersForCombat(Combat) here; the original is renamed
// assignBlockersForCombat$orig).
//  - "humanlike" AI patch: Forge blocks with a commander like with any creature, e.g. Mr. House
//    (0/4) died blocking Titania at 20 life and blocking Tidus at 29 life. A commander is taken
//    back out of a block that kills it without killing the attacker, as long as its controller
//    would still be above COMFORTABLE_LIFE after taking that damage too. Then unblocked
//    attackers get a safe block where a creature survives it (addSafeBlocks). Logged as
//    "BLOCKAI ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.ai.AiBlockController;
import forge.ai.ComputerUtilCombat;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

public final class BlockHook {
    private BlockHook() {}

    static final int COMFORTABLE_LIFE = 10;

    private static volatile MethodHandle orig;

    public static void assignBlockersForCombat(AiBlockController controller, Combat combat) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findVirtual(AiBlockController.class, "assignBlockersForCombat$orig",
                MethodType.methodType(void.class, Combat.class));
            orig = h;
        }
        h.invokeExact(controller, combat);
        if (!HumanHooks.enabled || combat == null) return;
        try {
            keepCommandersHome(combat);
        } catch (Throwable t) {
            // Never break the game: keep Forge's own blocks.
        }
        try {
            addSafeBlocks(combat);
        } catch (Throwable t) {
            // Never break the game.
        }
    }

    /**
     * Safe blocks: attacked players with untapped creatures blocked only 26% of the time (1,600
     * games); Forge mostly blocks to trade or when in danger, and lets attackers through that
     * a creature could stop for free. For each unblocked attacker on an AI player, block with
     * the creature that survives the block - one that also kills the attacker first, else the
     * least valuable one. Menace attackers (need two blockers) are left alone.
     */
    private static void addSafeBlocks(Combat combat) {
        boolean real = combat.getAttackingPlayer().getGame().getCombat() == combat;
        for (Card attacker : new java.util.ArrayList<>(combat.getAttackers())) {
            if (combat.isBlocked(attacker) || attacker.hasKeyword(forge.game.keyword.Keyword.MENACE)) continue;
            Player me = combat.getDefenderPlayerByAttacker(attacker);
            if (me == null || !(me.getController() instanceof forge.ai.PlayerControllerAi)) continue;
            Card best = null;
            boolean bestKills = false;
            int bestValue = Integer.MAX_VALUE;
            for (Card b : me.getCreaturesInPlay()) {
                if (b.isTapped() || combat.isBlocking(b) || !CombatUtil.canBlock(attacker, b, combat)) continue;
                combat.addBlocker(attacker, b);
                boolean dies = ComputerUtilCombat.blockerWouldBeDestroyed(me, b, combat);
                boolean kills = ComputerUtilCombat.attackerWouldBeDestroyed(me, attacker, combat);
                combat.undoBlockingAssignment(b);
                if (dies) continue;
                int value = forge.ai.ComputerUtilCard.evaluateCreature(b);
                if (best == null || (kills && !bestKills) || (kills == bestKills && value < bestValue)) {
                    best = b; bestKills = kills; bestValue = value;
                }
            }
            if (best == null) continue;
            combat.addBlocker(attacker, best);
            if (real) System.out.println("BLOCKAI " + me.getName() + ": " + best.getName() + " blocks " + attacker.getName()
                + (bestKills ? " (kills it, survives)" : " (safe block)"));
        }
    }

    private static void keepCommandersHome(Combat combat) {
        boolean real = combat.getAttackingPlayer().getGame().getCombat() == combat;
        for (Card attacker : new java.util.ArrayList<>(combat.getAttackers())) {
            for (Card blocker : new java.util.ArrayList<>(combat.getBlockers(attacker))) {
                if (!blocker.isCommander()) continue;
                Player me = blocker.getController();
                if (!(me.getController() instanceof forge.ai.PlayerControllerAi)) continue;
                if (!ComputerUtilCombat.blockerWouldBeDestroyed(me, blocker, combat)) continue;
                if (ComputerUtilCombat.attackerWouldBeDestroyed(me, attacker, combat)) continue;   // a trade is fine
                // Damage we take if this block is undone: everything unblocked, plus this attacker
                // unless other creatures still block it.
                int damage = 0;
                for (Card a : combat.getAttackers()) {
                    if (combat.getDefenderPlayerByAttacker(a) != me) continue;
                    boolean blockedByOthers = false;
                    for (Card b : combat.getBlockers(a)) { if (b != blocker) blockedByOthers = true; }
                    if (!blockedByOthers) damage += Math.max(0, a.getNetCombatDamage());
                }
                if (me.getLife() - damage <= COMFORTABLE_LIFE) continue;
                combat.undoBlockingAssignment(blocker);
                if (real) System.out.println("BLOCKAI " + me.getName() + ": " + blocker.getName() + " doesn't block "
                    + attacker.getName() + " (life " + me.getLife() + ", would take " + damage + ")");
            }
        }
    }
}
