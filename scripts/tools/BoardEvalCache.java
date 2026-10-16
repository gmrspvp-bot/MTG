// Cache for Forge's ComputerUtil.evaluateBoardPosition(player, ai), which predicts the
// next combats by running the whole blocking AI and took about two thirds of all CPU
// time in profiling. Forge caches it only until the next priority pass (AiCache), even
// though the board usually hasn't changed. Here results are kept per board fingerprint
// (turn, phase, active player, life/poison, and every permanent's controller, power,
// toughness, tapped/sick state, damage, counters and attachments).
//
// EvalCacheAgent routes ComputerUtil.evaluateBoardPosition here; the original method is
// renamed evaluateBoardPosition$orig. -Dforge.evalcache.check=N recomputes every Nth cache
// hit and counts mismatches; totals print at exit as "EVALCACHE ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class BoardEvalCache {
    private BoardEvalCache() {}

    private static volatile MethodHandle orig;
    private static final int MAX = 50000;
    private static final Map<Long, Integer> CACHE = new LinkedHashMap<Long, Integer>(1 << 14, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> e) { return size() > MAX; }
    };
    private static final int CHECK_EVERY = Integer.getInteger("forge.evalcache.check", 0);
    private static final AtomicLong CALLS = new AtomicLong(), HITS = new AtomicLong(), CHECKS = new AtomicLong(), MISMATCHES = new AtomicLong();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("EVALCACHE calls " + CALLS.get() + ", hits "
            + HITS.get() + ", checked " + CHECKS.get() + ", mismatches " + MISMATCHES.get())));
    }

    private static int original(Player p, Player ai) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findStatic(forge.ai.ComputerUtil.class, "evaluateBoardPosition$orig",
                MethodType.methodType(int.class, Player.class, Player.class));
            orig = h;
        }
        return (int) h.invokeExact(p, ai);
    }

    public static int evaluateBoardPosition(Player p, Player ai) throws Throwable {
        CALLS.incrementAndGet();
        long key;
        try {
            key = fingerprint(p.getGame()) * 1000003L + p.getId() * 101L + ai.getId();
        } catch (Throwable t) {
            return original(p, ai);
        }
        Integer v;
        synchronized (CACHE) { v = CACHE.get(key); }
        if (v != null) {
            long h = HITS.incrementAndGet();
            if (CHECK_EVERY > 0 && h % CHECK_EVERY == 0) {
                CHECKS.incrementAndGet();
                if (original(p, ai) != v) MISMATCHES.incrementAndGet();
            }
            return v;
        }
        int r = original(p, ai);
        synchronized (CACHE) { CACHE.put(key, r); }
        return r;
    }

    private static volatile MethodHandle origDanger;

    /** ComputerUtil.aiLifeInDanger(ai, serious, payment), cached the same way (stored as 0/1). */
    public static boolean aiLifeInDanger(Player ai, boolean serious, int payment) throws Throwable {
        MethodHandle h = origDanger;
        if (h == null) {
            h = MethodHandles.lookup().findStatic(forge.ai.ComputerUtil.class, "aiLifeInDanger$orig",
                MethodType.methodType(boolean.class, Player.class, boolean.class, int.class));
            origDanger = h;
        }
        CALLS.incrementAndGet();
        long key;
        try {
            key = (fingerprint(ai.getGame()) * 1000003L + ai.getId() * 101L + (serious ? 1 : 2)) * 7919L + payment + 0x5DEECE66DL;
        } catch (Throwable t) {
            return (boolean) h.invokeExact(ai, serious, payment);
        }
        Integer v;
        synchronized (CACHE) { v = CACHE.get(key); }
        if (v != null) {
            long n = HITS.incrementAndGet();
            if (CHECK_EVERY > 0 && n % CHECK_EVERY == 0) {
                CHECKS.incrementAndGet();
                if (((boolean) h.invokeExact(ai, serious, payment)) != (v == 1)) MISMATCHES.incrementAndGet();
            }
            return v == 1;
        }
        boolean r = (boolean) h.invokeExact(ai, serious, payment);
        synchronized (CACHE) { CACHE.put(key, r ? 1 : 0); }
        return r;
    }

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h * 0xBF58476D1CE4E5B9L;
    }

    static long fingerprint(Game game) {
        long h = mix(17, game.getId());
        h = mix(h, game.getPhaseHandler().getTurn());
        h = mix(h, game.getPhaseHandler().getPhase() == null ? -1 : game.getPhaseHandler().getPhase().ordinal());
        Player active = game.getPhaseHandler().getPlayerTurn();
        h = mix(h, active == null ? -1 : active.getId());
        h = mix(h, game.getCombat() == null ? 0 : game.getCombat().getAttackers().size() + 1);
        for (Player p : game.getPlayers()) {
            h = mix(h, p.getId());
            h = mix(h, p.getLife());
            h = mix(h, p.getPoisonCounters());
            h = mix(h, p.getCardsIn(ZoneType.Hand).size());
        }
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            h = mix(h, c.getId());
            h = mix(h, c.getController() == null ? -1 : c.getController().getId());
            h = mix(h, c.isTapped() ? 1 : 0);   // open mana matters (pumps, activated abilities)
            if (!c.isCreature() && !c.isPlaneswalker()) continue;
            h = mix(h, c.getNetPower());
            h = mix(h, c.getNetToughness());
            h = mix(h, (c.isTapped() ? 1 : 0) | (c.isSick() ? 2 : 0));
            h = mix(h, c.getDamage());
            h = mix(h, c.getCounters().hashCode());
            for (Card e : c.getEquippedBy()) h = mix(h, e.getId());
        }
        return h;
    }
}
