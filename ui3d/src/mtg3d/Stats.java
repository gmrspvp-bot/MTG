package mtg3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Snapshot cannot tell you: everything that already happened.
 *
 * A Snapshot is one frame of the game - life totals now, the board now. Half of what anyone wants
 * from a statistics panel is a shape over time: whether someone has been bleeding since turn four,
 * who has cast the most, how long this is taking. So this watches the snapshots go past and keeps
 * the running totals.
 *
 * Everything here is derived from SNAPSHOT DELTAS, not from parsing the game log. The log is a
 * rolling window of the most recent lines, so anything counted from it would be wrong the moment a
 * line scrolled off, and its wording is Forge's to change. A life total that went from 40 to 33 is
 * seven life lost no matter how it was phrased.
 *
 * Seats are keyed by name because seat indices move when a player loses.
 *
 * Nothing in here can affect play: it is written only from the render thread, reads a Snapshot the
 * renderer already has, and nothing else reads it but the panel that draws it.
 */
final class Stats {

    /** How many per-turn life samples a sparkline keeps. Older ones fall off the left. */
    private static final int SPARK = 40;

    static final class Seat {
        String name = "";
        int life, peakLife = Integer.MIN_VALUE, lowLife = Integer.MAX_VALUE;
        int lifeLost, lifeGained;
        int lifeAtTurnStart;
        int spellsCast;
        int toGraveyard;
        int peakPower;
        int turnsTaken;
        /** Life at the start of each turn, oldest first - the sparkline. */
        final List<Integer> lifeLine = new ArrayList<>();
        /** Set while this seat has lost, so the panel can grey the row rather than drop it. */
        boolean lost;
    }

    private final Map<String, Seat> seats = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    /** Stack card ids seen last sample, so a new one means something was just put on the stack. */
    private Set<Integer> stackLast = new HashSet<>();
    private final Map<String, Integer> graveLast = new HashMap<>();
    private final Map<String, Integer> lifeLast = new HashMap<>();

    private String activeLast = "";
    float elapsed;                 // seconds the table has been showing this game
    private float turnStarted;
    int turns;                     // turn changes seen, not Forge's turn number
    float longestTurn;
    String longestTurnBy = "";
    /** The first seat seen to lose life, and on which of our turn counts. */
    String firstBloodOn = "";
    int firstBloodTurn = -1;

    /** Forget everything: a new game started under the same window. */
    void reset() {
        seats.clear(); order.clear(); stackLast = new HashSet<>(); graveLast.clear(); lifeLast.clear();
        activeLast = ""; elapsed = 0f; turnStarted = 0f; turns = 0; longestTurn = 0f;
        longestTurnBy = ""; firstBloodOn = ""; firstBloodTurn = -1;
    }

    Seat get(String name) {
        Seat s = seats.get(name);
        if (s == null) {
            s = new Seat();
            s.name = name;
            seats.put(name, s);
            order.add(name);
        }
        return s;
    }

    List<Seat> all() {
        List<Seat> out = new ArrayList<>();
        for (String n : order) out.add(seats.get(n));
        return out;
    }

    /**
     * Take one reading. Called once a frame from the overlay; cheap enough to not care.
     *
     * Delta counting means a frame where nothing changed costs a handful of comparisons, and a
     * frame where the snapshot was rebuilt picks up exactly what moved.
     */
    void sample(Snapshot s, float dt) {
        if (s == null || s.seats.isEmpty()) return;
        elapsed += dt;

        // A turn change: close the old one off and open a life sample on every seat.
        String active = "";
        for (Snapshot.SeatV v : s.seats) if (v.active) active = v.name;
        if (!active.isEmpty() && !active.equals(activeLast)) {
            if (!activeLast.isEmpty()) {
                float len = elapsed - turnStarted;
                if (len > longestTurn) { longestTurn = len; longestTurnBy = activeLast; }
            }
            turnStarted = elapsed;
            activeLast = active;
            turns++;
            for (Snapshot.SeatV v : s.seats) {
                Seat st = get(v.name);
                st.lifeAtTurnStart = v.life;
                st.lifeLine.add(v.life);
                while (st.lifeLine.size() > SPARK) st.lifeLine.remove(0);
            }
            get(active).turnsTaken++;
        }

        for (Snapshot.SeatV v : s.seats) {
            Seat st = get(v.name);
            st.life = v.life;
            st.lost = v.lost;
            if (v.life > st.peakLife) st.peakLife = v.life;
            if (v.life < st.lowLife) st.lowLife = v.life;

            Integer was = lifeLast.get(v.name);
            if (was != null && was != v.life) {
                if (v.life < was) {
                    st.lifeLost += was - v.life;
                    if (firstBloodTurn < 0) { firstBloodOn = v.name; firstBloodTurn = turns; }
                } else {
                    st.lifeGained += v.life - was;
                }
            }
            lifeLast.put(v.name, v.life);

            Integer gWas = graveLast.get(v.name);
            if (gWas != null && v.graveyardSize > gWas) st.toGraveyard += v.graveyardSize - gWas;
            graveLast.put(v.name, v.graveyardSize);

            int power = 0;
            for (Snapshot.CardV c : v.creatures) power += intOf(c.power);
            if (power > st.peakPower) st.peakPower = power;
        }

        // Anything new on the stack was cast or activated by its controller this moment.
        Set<Integer> now = new HashSet<>();
        for (Snapshot.CardV c : s.stack) {
            now.add(c.id);
            if (!stackLast.contains(c.id) && c.controller != null && !c.controller.isEmpty()) {
                get(c.controller).spellsCast++;
            }
        }
        stackLast = now;
    }

    // ----------------------------------------------------------------- derived, for the panel
    static int intOf(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;        // "*" and other characteristic-defining power
        }
    }

    /** Total power on a seat's battlefield right now. */
    static int boardPower(Snapshot.SeatV v) {
        int p = 0;
        for (Snapshot.CardV c : v.creatures) if (!c.phasedOut) p += intOf(c.power);
        return p;
    }

    /** The biggest creature a seat controls right now, or null. */
    static Snapshot.CardV biggest(Snapshot.SeatV v) {
        Snapshot.CardV best = null;
        for (Snapshot.CardV c : v.creatures) {
            if (c.phasedOut) continue;
            if (best == null || intOf(c.power) > intOf(best.power)) best = c;
        }
        return best;
    }

    /** Untapped lands - a fair reading of "mana available" without solving the mana base. */
    static int untappedLands(Snapshot.SeatV v) {
        int n = 0;
        for (Snapshot.CardV c : v.lands) if (!c.tapped && !c.phasedOut) n++;
        return n;
    }

    /** The worst commander damage this seat has taken, and from whom. */
    static Snapshot.CmdDmg worstCommander(Snapshot.SeatV v) {
        Snapshot.CmdDmg worst = null;
        for (Snapshot.CmdDmg d : v.commanderDamage) if (worst == null || d.amount > worst.amount) worst = d;
        return worst;
    }

    static String clock(float seconds) {
        int t = (int) seconds;
        return t < 3600 ? String.format("%d:%02d", t / 60, t % 60)
                        : String.format("%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60);
    }
}
