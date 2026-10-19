package mtg3d;

/** Checks Cues fires once per meaningful change and stays quiet otherwise. Muted: no sound. */
public class CuesProbe {
    static int fails;

    static void check(String what, int got, int want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + what + ": " + got + " (want " + want + ")");
        if (got != want) fails++;
    }

    static Snapshot base(int active) {
        Snapshot s = new Snapshot();
        String[] names = { "Me", "Opp1", "Opp2" };
        for (int i = 0; i < 3; i++) {
            Snapshot.SeatV v = new Snapshot.SeatV();
            v.name = names[i];
            v.isYou = i == 0;
            v.active = i == active;
            s.seats.add(v);
        }
        return s;
    }

    public static void main(String[] a) throws Exception {
        Cues c = new Cues();
        c.toggleMute();
        c.update(base(1));                       check("first frame is silent", c.fired, 0);
        c.update(base(1));                       check("nothing changed", c.fired, 0);
        c.update(base(0));                       check("my turn starts", c.fired, 1);
        c.update(base(0));                       check("still my turn", c.fired, 1);

        Snapshot s = base(0);
        Snapshot.CardV mine = new Snapshot.CardV(10, "Bolt", "Instant", false, false);
        mine.controller = "Me";
        s.stack.add(mine);
        c.update(s);                             check("my own spell: no ping", c.fired, 1);
        Snapshot s2 = base(0);
        s2.stack.add(mine);
        Snapshot.CardV theirs = new Snapshot.CardV(11, "Counterspell", "Instant", false, false);
        theirs.controller = "Opp1";
        s2.stack.add(theirs);
        c.update(s2);                            check("opponent responds: ping", c.fired, 2);
        c.update(s2);                            check("same stack: quiet", c.fired, 2);

        Snapshot s3 = base(1);
        Snapshot.CombatArc arc = new Snapshot.CombatArc();
        arc.attackerId = 50; arc.defenderSeat = 0;
        s3.combatArcs.add(arc);
        Snapshot.CombatArc other = new Snapshot.CombatArc();
        other.attackerId = 51; other.defenderSeat = 2;
        s3.combatArcs.add(other);
        c.update(s3);                            check("attacked: knock", c.fired, 3);
        c.update(s3);                            check("same attack: quiet", c.fired, 3);

        Snapshot s4 = base(1);
        s4.gameOver = true; s4.winner = "Me";
        c.update(s4);                            check("game over", c.fired, 4);
        c.update(s4);                            check("still over", c.fired, 4);
        c.dispose();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    }
}
