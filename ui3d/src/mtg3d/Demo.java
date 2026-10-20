package mtg3d;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import java.util.List;

/**
 * Runs the 3D table on invented data, with no Forge behind it.
 *
 * This exists so the view can be developed and looked at in a couple of seconds instead of
 * starting a whole game, and so a rendering problem can never be confused with a problem in the
 * bridge to Forge. It also cycles combat on and off every eight seconds, which is the only way to
 * watch the camera lift to the overhead pose and settle back without playing to combat.
 *
 *   powershell -ExecutionPolicy Bypass -File scripts\run-ui3d.ps1
 */
public final class Demo {

    /** Which lands start tapped. Fixed seed, so two runs of the demo render the same table. */
    private static final java.util.Random TAP_DICE = new java.util.Random(7);
    private static final boolean DEMO_EVENTS = Boolean.getBoolean("forge.ui3d.demoEvents");

    public static void main(String[] args) {
        final Snapshot s = build();
        final long start = System.currentTimeMillis();
        final boolean[] eventsDone = new boolean[5];

        // Forge's phases, so the demo drives the same keys the phase strip matches on.
        final String[] phases = {
            "UNTAP", "UPKEEP", "DRAW", "MAIN1", "COMBAT_BEGIN", "COMBAT_DECLARE_ATTACKERS",
            "COMBAT_DECLARE_BLOCKERS", "COMBAT_FIRST_STRIKE_DAMAGE", "COMBAT_DAMAGE",
            "COMBAT_END", "MAIN2", "END_OF_TURN", "CLEANUP",
        };
        final String[] pretty = {
            "Untap", "Upkeep", "Draw", "Main phase, precombat", "Beginning of combat",
            "Declare attackers", "Declare blockers", "First strike damage", "Combat damage",
            "End of combat", "Main phase, postcombat", "End step", "Cleanup",
        };
        final int[] state = { 3, 0, 0 };    // phase index, turn number, active seat
        final int dragonId = find(s, "Ancient Bronze Dragon").id;
        // For screenshot checks, which cannot press keys: start on a given phase, knock a seat
        // out, or end the game.  -Dforge.ui3d.demoPhase=COMBAT_DECLARE_BLOCKERS
        //   -Dforge.ui3d.demoLost=Zerma   -Dforge.ui3d.demoWinner=Xenil
        String startPhase = System.getProperty("forge.ui3d.demoPhase");
        if (startPhase != null) state[0] = Math.max(0, java.util.Arrays.asList(phases).indexOf(startPhase));
        String lostName = System.getProperty("forge.ui3d.demoLost");
        for (Snapshot.SeatV v : s.seats) {
            if (v.name.equals(lostName)) {
                v.lost = true; v.life = 0; v.creatures.clear(); v.others.clear(); v.lands.clear(); v.command.clear();
            }
        }
        final String responding = System.getProperty("forge.ui3d.demoResponding");
        String winner = System.getProperty("forge.ui3d.demoWinner");
        if (winner != null) { s.gameOver = true; s.winner = winner; }

        Table3D.Supplier source = () -> {
            int n = s.seats.size();
            // Driven by keys, not by a clock. A demo that marches through turns on its own looks
            // nothing like a game: you can never stop on the step you wanted to look at, and the
            // board changes under you while you are reading it.
            //   SPACE  next phase (wrapping into the next player's turn)
            //   ENTER  skip straight to the next player's turn
            //   BACKSPACE  previous phase
            if (Keys3D.just(com.badlogic.gdx.Input.Keys.SPACE)) {
                state[0]++;
                if (state[0] >= phases.length) { state[0] = 0; state[1]++; state[2] = (state[2] + 1) % n; }
            }
            if (Keys3D.just(com.badlogic.gdx.Input.Keys.ENTER)) {
                state[0] = 3; state[1]++; state[2] = (state[2] + 1) % n;
            }
            if (Keys3D.just(com.badlogic.gdx.Input.Keys.BACKSPACE)) {
                state[0] = Math.max(0, state[0] - 1);
            }

            // -Dforge.ui3d.demoEvents=true: a fixed timeline for screenshot checks of the moments
            // (life popups, the YOUR TURN sweep), which otherwise need keys pressed at the right time.
            if (DEMO_EVENTS) {
                long ms = System.currentTimeMillis() - start;
                if (ms > 2000 && !eventsDone[0]) { eventsDone[0] = true; state[2] = 1; }
                if (ms > 3000 && !eventsDone[1]) {
                    eventsDone[1] = true;
                    s.seats.get(1).life -= 7;
                    s.seats.get(0).life += 3;
                }
                if (ms > 4000 && !eventsDone[2]) { eventsDone[2] = true; state[2] = 0; state[1]++; }
                // A creature dies (slides to its owner's graveyard pad) and a card is drawn
                // (slides off your library into the hand).
                if (ms > 2500 && !eventsDone[3]) {
                    eventsDone[3] = true;
                    java.util.List<Snapshot.CardV> cs = s.seats.get(1).creatures;
                    for (int i = 0; i < cs.size(); i++) {
                        if (cs.get(i).name.equals("Esper Sentinel")) { s.seats.get(1).graveyard.add(0, cs.remove(i)); break; }
                    }
                }
                if (ms > 5000 && !eventsDone[4]) {
                    eventsDone[4] = true;
                    s.hand.add(card(904, "Smothering Tithe", "Enchantment", false, false, "{3}{W}",
                        "Whenever an opponent draws a card, that player may pay {2}. If the player doesn't, you create a Treasure token."));
                }
            }
            int phase = state[0], turn = state[1], active = state[2];
            String key = phases[phase];
            s.phaseKey = key;
            s.phase = pretty[phase];
            s.combat = key.startsWith("COMBAT_") && !key.equals("COMBAT_END");
            s.turnLabel = "Turn " + (turn + 1) + " - " + s.seats.get(active).name;
            // A prompt, so the bar at the bottom can be seen and shot without a real game.
            // Several lines, like Forge real prompts, so the bar height is exercised.
            String nl2 = String.valueOf((char) 10);
            s.promptText = s.combat
                ? "Priority: " + s.seats.get(active).name + nl2 + "Turn: " + (turn + 1)
                  + nl2 + "Phase: Declare attackers" + nl2 + "Stack: " + s.stack.size() + " to resolve."
                : "Priority: " + s.seats.get(active).name + nl2 + "Turn: " + (turn + 1)
                  + nl2 + "Phase: Main phase, precombat" + nl2 + "Stack: Empty";
            s.okLabel = s.combat ? "Attack" : "OK";
            s.cancelLabel = "Cancel";
            s.okEnabled = true;
            s.cancelEnabled = s.combat;

            for (int i = 0; i < n; i++) {
                s.seats.get(i).active = (i == active);
                s.seats.get(i).hasPriority = (i == active);
            }

            // Attackers from declare-attackers onwards, so stepping through combat shows them
            // appear, tap, get blocked, and then clear at end of combat.
            boolean attacking = s.combat && !key.equals("COMBAT_BEGIN");
            for (int i = 0; i < n; i++) {
                for (Snapshot.CardV c : s.seats.get(i).creatures) {
                    c.attacking = attacking && i == active && c.creature;
                    c.tapped = c.attacking;
                    c.blocking = false;
                }
            }

            s.combatArcs.clear();
            if (attacking) {
                boolean blocked = !key.equals("COMBAT_DECLARE_ATTACKERS");
                int k = 0;
                for (Snapshot.CardV c : s.seats.get(active).creatures) {
                    if (!c.attacking) continue;
                    Snapshot.CombatArc arc = new Snapshot.CombatArc();
                    arc.attackerId = c.id;
                    arc.attackerName = c.name;
                    arc.defenderSeat = (active + 1 + k % Math.max(1, n - 1)) % n;
                    arc.defenderName = s.seats.get(arc.defenderSeat).name;
                    List<Snapshot.CardV> theirs = s.seats.get(arc.defenderSeat).creatures;
                    if (k == 0 && blocked && !theirs.isEmpty()) {
                        theirs.get(0).blocking = true;
                        arc.blockedBy.add(theirs.get(0).id);
                    }
                    s.combatArcs.add(arc);
                    k++;
                }
            }

            // The stack fills during declare blockers, so the bar can be watched growing.
            s.stack.clear();
            String[][] spells = {
                { "Swords to Plowshares", "Xenil", "Exile Ancient Bronze Dragon" },
                { "Arcane Denial", "Michael", "Counter Swords to Plowshares" },
                { "Boros Charm", "Captain", "Deal 4 damage to Xenil" },
            };
            int onStack = key.equals("COMBAT_DECLARE_BLOCKERS") ? 3
                : key.equals("COMBAT_DECLARE_ATTACKERS") ? 1 : 0;
            for (int i = 0; i < Math.min(onStack, spells.length); i++) {
                Snapshot.CardV v = card(950 + i, spells[i][0], "Instant", false, false, "{W}", "");
                v.controller = spells[i][1];
                v.stackText = spells[i][2];
                // Targets, so the violet target arcs show: a permanent, a spell, a player.
                if (i == 0) v.targetIds.add(dragonId);
                if (i == 1) v.targetIds.add(950);
                if (i == 2) v.targetSeats.add(0);
                s.stack.add(v);
            }
            // Priority round the table while spells are up: the seats it has already moved past
            // have passed, so the phase panel's priority block shows all three states.
            int prio = (active + onStack) % n;
            for (int i = 0; i < n; i++) {
                s.seats.get(i).hasPriority = (i == prio);
                s.seats.get(i).passed = false;
            }
            for (int j = 0, p = active; j < onStack && p != prio; j++, p = (p + 1) % n) s.seats.get(p).passed = true;
            // -Dforge.ui3d.demoResponding=<name>: hand priority to a seat whose turn it is
            // NOT, which is the only way to see the responding state without a real game -
            // the demo otherwise always gives priority to the active player. Applied last,
            // because the priority round above would overwrite it.
            if (responding != null) {
                for (Snapshot.SeatV v : s.seats) {
                    v.hasPriority = v.name.equalsIgnoreCase(responding);
                    if (v.hasPriority) v.passed = false;
                }
            }
            return s;
        };
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MTG 3D table - demo data");
        WindowMemory.apply(cfg, 1600, 950);   // last size and position, if any
        GraphicsQuality.configure(cfg);
        new Lwjgl3Application(new Table3D(source), cfg);
    }

    private static Snapshot build() {
        Snapshot s = new Snapshot();
        s.turnLabel = "Turn 7 - Xenil";
        s.seats.add(seat("Xenil", 34, true, true,
            new String[] { "Plains", "Plains", "Emeria, the Sky Ruin", "Sol Ring", "Command Tower" },
            new String[] { "Seluma, Light of Aysen", "Lyra Dawnbringer", "Giada, Font of Hope", "Resplendent Angel" }));
        s.seats.add(seat("Captain", 28, false, false,
            new String[] { "Battlefield Forge", "Sacred Foundry", "Plains", "Mountain" },
            new String[] { "Captain America, First Avenger", "Esper Sentinel" }));
        s.seats.add(seat("Michael", 31, false, false,
            new String[] { "Mountain", "Reflecting Pool", "Turbulent Wetlands" },
            new String[] { "Ancient Bronze Dragon" }));
        s.seats.add(seat("Zerma", 39, false, false,
            new String[] { "Swamp", "Island", "Fetid Pools", "Command Tower", "Darkslick Shores" },
            new String[] { "Red Ghost, Intangible Genius", "Hobgoblin, Mantled Marauder" }));

        // Attachments and counters: two equipment on Lyra, Lyra carrying +1/+1 counters, and an
        // aura of Xenil's on Michael's dragon, so the table shows one sitting under an opponent's card.
        Snapshot.CardV lyra = find(s, "Lyra Dawnbringer");
        Snapshot.CardV dragon = find(s, "Ancient Bronze Dragon");
        lyra.counters = 2;
        lyra.counterText.add("+1/+1 x2");
        lyra.power = "7";
        lyra.toughness = "7";
        int aid = 960;
        for (String eq : new String[] { "Skullclamp", "Sword of Fire and Ice" }) {
            Snapshot.CardV e = card(aid++, eq, "Artifact - Equipment", false, false, "{1}", "");
            e.artifact = true;
            e.attachedTo = lyra.name;
            e.attachedToId = lyra.id;
            s.seats.get(0).others.add(e);
        }
        Snapshot.CardV aura = card(aid, "Pacifism", "Enchantment - Aura", false, false, "{1}{W}",
            "Enchant creature\nEnchanted creature can't attack or block.");
        aura.enchantment = true;
        aura.attachedTo = dragon.name;
        aura.attachedToId = dragon.id;
        s.seats.get(0).others.add(aura);

        // Designations and night, so the seat marks and the day/night label show.
        s.seats.get(2).marks.add("MONARCH");
        s.seats.get(1).marks.add("INITIATIVE");
        s.seats.get(1).marks.add("ENERGY 3");
        s.dayNight = "NIGHT";
        // Floating mana in one pool (Zerma mid-ritual), so the pool line shows.
        s.seats.get(3).mana[2] = 3;
        s.seats.get(3).mana[3] = 1;

        // Graveyards and an exile pile to browse from their pads, and a few lines of game log.
        int gid = 990;
        for (String g : new String[] { "Swords to Plowshares", "Arcane Denial", "Sol Ring" }) {
            s.seats.get(0).graveyard.add(card(gid++, g, "Instant", false, false, "{W}", ""));
        }
        s.seats.get(1).graveyard.add(card(gid++, "Boros Charm", "Instant", false, false, "{R}{W}", ""));
        s.seats.get(2).exile.add(card(gid++, "Ancient Bronze Dragon", "Creature - Dragon", true, false, "{5}{G}{G}", ""));
        String[][] lines = {
            { "TURN", "Turn 7 (Xenil)" },
            { "LAND", "Xenil played Emeria, the Sky Ruin" },
            { "STACK_ADD", "Xenil cast Swords to Plowshares targeting Ancient Bronze Dragon" },
            { "STACK_ADD", "Michael cast Arcane Denial targeting Swords to Plowshares" },
            { "STACK_RESOLVE", "Arcane Denial resolves" },
            { "COMBAT", "Xenil assigned Lyra Dawnbringer to attack Captain" },
            { "DAMAGE", "Lyra Dawnbringer deals 7 combat damage to Captain" },
            { "LIFE", "Xenil gained 7 life" },
        };
        for (String[] l : lines) s.log.add(new Snapshot.LogLine(l[0], l[1]));

        // Command zones: Michael's commander is at home after two casts (tax +4), Zerma's after
        // none. Captain's and Xenil's are on the battlefield, so theirs show the empty pad.
        Snapshot.CardV kaalia = card(980, "Kaalia of the Vast", "Legendary Creature - Human Cleric", true, false,
            "{1}{R}{W}{B}", "Flying\nWhenever Kaalia attacks an opponent, you may put an Angel, Demon, or Dragon creature card from your hand onto the battlefield tapped and attacking that opponent.");
        kaalia.commander = true;
        kaalia.power = "2";
        kaalia.toughness = "2";
        kaalia.commanderCasts = 2;
        s.seats.get(2).command.add(kaalia);
        Snapshot.CardV ghost = card(981, "Sol Ring", "Artifact", false, false, "{1}", "");
        ghost.name = "Emblem";
        ghost.typeLine = "Emblem";
        ghost.text = "Creatures you control get +1/+1.";
        s.seats.get(3).command.add(ghost);

        s.hand.add(card(900, "Wrath of the Skies", "Sorcery", false, false,
            "{X}{W}{W}", "You get X energy, then you may pay any amount of energy. Destroy each artifact, creature, and enchantment with mana value less than or equal to the amount of energy paid this way."));
        s.hand.add(card(901, "Swords to Plowshares", "Instant", false, false,
            "{W}", "Exile target creature. Its controller gains life equal to its power."));
        s.hand.add(card(902, "Land Tax", "Enchantment", false, false, "{W}",
            "At the beginning of your upkeep, if an opponent controls more lands than you, you may search your library for up to three basic land cards, reveal them, put them into your hand, then shuffle."));
        s.hand.add(card(903, "Angel of Sanctions", "Creature - Angel", true, false, "{3}{W}{W}",
            "Flying\nWhen Angel of Sanctions enters, you may exile target nonland permanent an opponent controls until Angel of Sanctions leaves the battlefield."));
        return s;
    }

    private static Snapshot.SeatV seat(String name, int life, boolean you, boolean active,
                                       String[] lands, String[] creatures) {
        Snapshot.SeatV v = new Snapshot.SeatV();
        v.name = name;
        v.life = life;
        v.isYou = you;
        v.active = active;
        v.handSize = you ? 4 : 3 + (name.length() % 5);
        v.librarySize = 80;
        v.graveyardSize = 6;
        v.exileSize = 2;
        if (!you) v.commanderDamage.add(new Snapshot.CmdDmg("Seluma, Light of Aysen", 8));
        if (you) v.commanderDamage.add(new Snapshot.CmdDmg("Captain America, First Avenger", 11));
        int id = name.hashCode() & 0xffff;
        for (String l : lands) {
            Snapshot.CardV c = card(id++, l, "Land", false, true, "", "");
            c.tapped = TAP_DICE.nextFloat() < 0.35f;   // seeded: the same lands every run, so screenshots compare
            v.lands.add(c);
        }
        boolean first = true;
        for (String n : creatures) {
            Snapshot.CardV c = card(id++, n, "Creature - Angel", true, false, "{3}{W}{W}",
                "Flying, lifelink.\nAn example body so the hover panel has something to wrap.");
            c.power = "4";
            c.toughness = "4";
            c.commander = first && you;
            first = false;
            v.creatures.add(c);
        }
        return v;
    }

    private static Snapshot.CardV find(Snapshot s, String name) {
        for (Snapshot.SeatV v : s.seats) for (Snapshot.CardV c : v.creatures) if (c.name.equals(name)) return c;
        throw new IllegalStateException("demo card missing: " + name);
    }

    private static Snapshot.CardV card(int id, String name, String type, boolean creature, boolean land,
                                       String cost, String text) {
        Snapshot.CardV c = new Snapshot.CardV(id, name, type, creature, land);
        c.manaCost = cost;
        c.text = text;
        return c;
    }
}
