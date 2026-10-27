// A cast that gets undone says so (2026-10-27). EvalCacheAgent routes
// forge.game.GameActionUtil.rollbackAbility(SpellAbility, Zone, int, CostPayment, Card) here
// (original renamed rollbackAbility$orig).
//
// The owner clicked Dack Fayden seven times in a row and nothing happened. The log said why and
// the table did not: Forge offered the cast as playable, opened the mana payment, then found the
// cost could not be paid - he had Rule of Law out, one spell a turn, and had already cast one -
// and silently restored the game to how it was. The card goes back to the hand, the mana comes
// back, and from the player's side the click simply evaporated.
//
// Forge enforces that restriction when the cost is paid rather than when the ability is offered,
// so there is nothing the 3D window can check beforehand. This is the other end: the moment a
// rollback happens, tell the window, which puts a line on the table.
//
// It also counts them. The same rollback crashing inside GameSnapshot.assignGameState is what
// ended the owner's game minutes later, so how often this path runs is worth knowing.
//
// Changes no behaviour: the original is always called.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.Card;
import forge.game.cost.CostPayment;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;

public final class RollbackHook {
    private RollbackHook() {}

    private static volatile MethodHandle orig;
    private static int count;

    public static void rollbackAbility(SpellAbility sa, Zone zone, int zonePosition,
                                       CostPayment payment, Card card) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l =
                MethodHandles.privateLookupIn(forge.game.GameActionUtil.class, MethodHandles.lookup());
            orig = l.findStatic(forge.game.GameActionUtil.class, "rollbackAbility$orig",
                MethodType.methodType(void.class, SpellAbility.class, Zone.class, int.class,
                    CostPayment.class, Card.class));
        }
        try {
            count++;
            String name = card != null ? card.getName()
                : sa != null && sa.getHostCard() != null ? sa.getHostCard().getName() : "That spell";
            System.out.println("ROLLBACK: " + name + " was begun and undone (" + count + " so far)");
            // The 3D window, if it is running. By reflection, because scripts\tools is built
            // against Forge alone and must not need ui3d on its classpath.
            try {
                Class<?> gui = Class.forName("mtg3d.Gui3D");
                gui.getMethod("rolledBack", String.class).invoke(null, name);
            } catch (ClassNotFoundException e) {
                // no 3D window in this run: the console line above is the whole story
            }
        } catch (Throwable t) {
            System.out.println("ROLLBACK: could not report it (" + t + ")");
        }
        orig.invokeExact(sa, zone, zonePosition, payment, card);
    }
}
