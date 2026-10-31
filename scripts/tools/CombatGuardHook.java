// Crash guard for desktop games after "Restart this phase" (PlayHook.Rollback). EvalCacheAgent
// routes forge.game.phase.PhaseHandler.onPhaseBegin() here (original renamed onPhaseBegin$orig).
// Forge creates the Combat object when beginning of combat begins; after a GameSnapshot restore
// that can be skipped, and the next combat step threw "Cannot invoke Combat.initConstraints()
// because this.combat is null" - three of the owner's games ended that way (2026-10-18/19, each
// right after a rollback). If a combat step begins without a Combat, create an empty one first.
// Normal games always have one, so this never fires there.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;

import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;

public final class CombatGuardHook {
    private CombatGuardHook() {}

    private static volatile MethodHandle orig, setCombat;
    private static final EnumSet<PhaseType> NEEDS_COMBAT = EnumSet.of(PhaseType.COMBAT_DECLARE_ATTACKERS,
        PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END);

    public static void onPhaseBegin(PhaseHandler ph) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(PhaseHandler.class, MethodHandles.lookup());
            orig = l.findVirtual(PhaseHandler.class, "onPhaseBegin$orig", MethodType.methodType(void.class));
            setCombat = l.findSetter(PhaseHandler.class, "combat", Combat.class);
        }
        try {
            PhaseType p = ph.getPhase();
            if (p != null && NEEDS_COMBAT.contains(p) && ph.getCombat() == null && ph.getPlayerTurn() != null) {
                setCombat.invoke(ph, new Combat(ph.getPlayerTurn()));
                System.out.println("COMBATGUARD: no combat at " + p + " (after a rollback?) - created an empty one");
            }
        } catch (Throwable t) {
            // never block the game because of the guard itself
        }
        // A phase boundary is also the one safe moment to give a seat the AI is holding back to
        // a player who has just joined - nothing is inside an Input here. See SeatClaimHook.
        try {
            forge.gamemodes.net.server.SeatClaimHook.applyPending(
                ph.getPlayerTurn() == null ? null : ph.getPlayerTurn().getGame());
        } catch (Throwable t) {
            // the guard is not the place to fail over a seat hand-back
        }
        // House rule: a player who conceded and sat out leaves at the start of their next upkeep.
        try {
            ConcedeHook.beforePhase(ph);
        } catch (Throwable t) {
            System.out.println("CONCEDE: failed at phase begin (" + t + ")");
        }
        orig.invoke(ph);
    }
}
