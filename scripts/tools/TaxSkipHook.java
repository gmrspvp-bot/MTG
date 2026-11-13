// Tax triggers on a big stack (owner, 2026-11-05). Smothering Tithe with Font of Mythos out asks
// every opponent "pay {2}?" once per card drawn, and a player with no mana still had to click
// through each one.
//
// EvalCacheAgent routes PlayerControllerHuman.payCostToPreventEffect here (original renamed
// payCostToPreventEffect$orig). That runs on the host for joined players too and asks them through
// their own GUI, so a client needs no update. Two things happen before Forge asks:
//   - A player who cannot pay a mana-only cost is not asked at all. Any other kind of cost (life,
//     sacrifice, discard) still asks, and a failed check counts as "can pay".
//   - When a tax trigger resolves with more than 5 triggers on the stack, its payer is asked
//     "Skip all / Don't pay this one / Pay". Skipping covers the tax cards named in that question
//     until the stack empties, including new triggers from those cards. The other two answer only
//     this trigger, so the next one asks again and "Skip all" is always one click away. (Until
//     2026-11-13 the first answer held for the whole stack: the owner said "ask each time" with 6
//     Tithe triggers, his draws then pushed it past 40, and he had to cancel about 210 payments.)
//
// Only triggers can be skipped: Mana Leak on your own spell always asks, and so do echo and
// cumulative upkeep, where saying no costs you the permanent.
//
// EvalCacheAgent also routes MagicStack.resolveStack here, which is how a skip ends.
// Logged as "TAXSKIP ..." on the console and as an INFORMATION line in the game log.
// -Dforge.taxskip=false turns it off, -Dforge.taxskip.triggers=N moves the limit.
// Test: scratch\probe\TaxSkipProbe.java.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.MagicStack;
import forge.player.PlayerControllerHuman;
import forge.util.collect.FCollectionView;

public final class TaxSkipHook {
    private TaxSkipHook() {}

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.taxskip", "true"));
    /** More triggers than this on the stack and the player is offered the skip. */
    static final int TRIGGERS = Integer.getInteger("forge.taxskip.triggers", 5);

    private static volatile MethodHandle origPay;
    private static volatile MethodHandle origResolve;

    /** A player's answer for the current stack: a skip of these tax cards, or empty for "ask each". */
    private static final Map<Player, Set<String>> ANSWERED = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean payCostToPreventEffect(PlayerControllerHuman self, Cost cost, SpellAbility sa,
            boolean alreadyPaid, FCollectionView<Player> allPayers) throws Throwable {
        if (origPay == null) {
            origPay = MethodHandles.privateLookupIn(PlayerControllerHuman.class, MethodHandles.lookup())
                .findVirtual(PlayerControllerHuman.class, "payCostToPreventEffect$orig",
                    MethodType.methodType(boolean.class, Cost.class, SpellAbility.class, boolean.class,
                        FCollectionView.class));
        }
        if (ENABLED && declines(self, cost, sa)) return false;
        return (boolean) origPay.invoke(self, cost, sa, alreadyPaid, allPayers);
    }

    public static void resolveStack(MagicStack self) throws Throwable {
        if (origResolve == null) {
            origResolve = MethodHandles.privateLookupIn(MagicStack.class, MethodHandles.lookup())
                .findVirtual(MagicStack.class, "resolveStack$orig", MethodType.methodType(void.class));
        }
        origResolve.invoke(self);
        if (self.size() == 0 && !ANSWERED.isEmpty()) {
            synchronized (ANSWERED) {
                ANSWERED.keySet().removeIf(p -> p.getGame().getStack() == self);
            }
        }
    }

    /** True when this payment is answered "no" without Forge asking the player. */
    static boolean declines(PlayerControllerHuman self, Cost cost, SpellAbility sa) {
        Player p = self.getPlayer();
        if (p == null || sa == null || sa.getHostCard() == null) return false;
        Game g = p.getGame();
        String tax = sa.getHostCard().getName();
        if (!canPay(cost, sa, p)) {
            say(g, p + " can't pay " + cost.toSimpleString() + " for " + tax);
            return true;
        }
        if (!skippable(sa)) return false;

        Set<String> skipping = ANSWERED.get(p);
        if (skipping == null || !skipping.contains(tax)) {
            Set<String> names = new LinkedHashSet<>();
            String a = ask(self, sa, tax, cost, names);
            if (a == null || a.startsWith(PAY)) return false;          // Forge's own payment prompt
            if (a.equals(NOT_THIS)) {
                say(g, p + " doesn't pay " + cost.toSimpleString() + " for " + tax);
                return true;
            }
            synchronized (ANSWERED) {
                Set<String> s = ANSWERED.computeIfAbsent(p, k -> new LinkedHashSet<>());
                s.addAll(names);
            }
        }
        say(g, p + " skips " + cost.toSimpleString() + " for " + tax);
        return true;
    }

    static final String SKIP_ALL = "Skip all until the stack clears", NOT_THIS = "Don't pay this one", PAY = "Pay ";

    /** Only a mana-only cost is judged: that is the one kind the check below can be trusted on. */
    static boolean canPay(Cost cost, SpellAbility sa, Player p) {
        if (cost == null) return true;
        for (CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostPartMana)) return true;
        }
        try {
            return ComputerUtilCost.canPayCost(cost, sa, p, true);
        } catch (Throwable t) {
            System.out.println("TAXSKIP check failed, asking the player instead (" + t + ")");
            return true;
        }
    }

    private static boolean skippable(SpellAbility sa) {
        SpellAbility root = sa.getRootAbility();
        return root.isTrigger() && !root.isKeyword(Keyword.ECHO) && !root.isKeyword(Keyword.CUMULATIVE_UPKEEP);
    }

    /**
     * The question, on a big stack: the answer picked, with the tax cards a skip covers put in
     * names. Null on a small stack (Forge asks as usual), so it can still be asked if the stack grows.
     */
    private static String ask(PlayerControllerHuman self, SpellAbility sa, String tax, Cost cost, Set<String> names) {
        names.add(tax);
        int triggers = 0;
        for (SpellAbilityStackInstance si : self.getGame().getStack()) {
            SpellAbility s = si.getSpellAbility();
            if (s == null || !s.isTrigger()) continue;
            triggers++;
            if (hasTax(s) && skippable(s) && s.getHostCard() != null) names.add(s.getHostCard().getName());
        }
        if (triggers <= TRIGGERS) return null;
        String price = cost.toSimpleString();
        String q = tax + ": pay " + price + "? " + triggers + " triggers on the stack. Skip all covers "
            + String.join(", ", names) + ".";
        String a = null;
        try {
            // getChoices, not one(): it is the call the network protocol and the 3D table carry.
            java.util.List<String> got = self.getGui().getChoices(q, 1, 1, Arrays.asList(SKIP_ALL, NOT_THIS, PAY + price));
            a = got == null || got.isEmpty() ? null : got.get(0);
        } catch (Throwable t) {
            System.out.println("TAXSKIP question failed, Forge asks instead (" + t + ")");
        }
        System.out.println("TAXSKIP " + self.getPlayer() + " on " + triggers + " triggers (" + names + "): " + a);
        return a;
    }

    private static boolean hasTax(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.hasParam("UnlessCost")) return true;
        }
        return false;
    }

    private static void say(Game g, String line) {
        System.out.println("TAXSKIP " + line);
        g.getGameLog().add(GameLogEntryType.INFORMATION, line);
    }
}
