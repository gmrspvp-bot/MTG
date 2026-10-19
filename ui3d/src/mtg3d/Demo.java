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

    public static void main(String[] args) {
        final Snapshot s = build();
        final long start = System.currentTimeMillis();

        Table3D.Supplier source = () -> {
            // Run a fake clock: a turn every twelve seconds, passing round the table, with combat
            // in its second half. That exercises both the walk round to the active seat and the
            // lift to the overhead pose, without having to play a real game to see them.
            long sec = (System.currentTimeMillis() - start) / 1000L;
            int n = s.seats.size();
            int turn = (int) (sec / 12);
            int active = turn % n;
            for (int i = 0; i < n; i++) s.seats.get(i).active = (i == active);
            s.combat = (sec % 12) >= 7;
            // Priority: the active player's, passing round the table once a second during combat,
            // so the priority highlight can be seen moving.
            int prio = (active + (s.combat ? (int) (sec % 12) - 7 : 0)) % n;
            for (int i = 0; i < n; i++) s.seats.get(i).hasPriority = (i == prio);
            s.phase = s.combat ? "Declare attackers" : "Main phase, precombat";
            s.turnLabel = "Turn " + (turn + 1) + " - " + s.seats.get(active).name;
            // Build the stack up during combat so the bar can be seen filling left to right.
            s.stack.clear();
            int onStack = s.combat ? (int) ((sec % 12) - 6) : 0;
            String[][] spells = {
                { "Swords to Plowshares", "Xenil", "Exile Ancient Bronze Dragon" },
                { "Arcane Denial", "Michael", "Counter Swords to Plowshares" },
                { "Boros Charm", "Captain", "Deal 4 damage to Xenil" },
            };
            for (int i = 0; i < Math.min(onStack, spells.length); i++) {
                Snapshot.CardV v = card(950 + i, spells[i][0], "Instant", false, false, "{W}", "");
                v.controller = spells[i][1];
                v.stackText = spells[i][2];
                s.stack.add(v);
            }
            // During combat, the active seat's creatures attack, so the markers show up.
            for (int i = 0; i < n; i++) {
                for (Snapshot.CardV c : s.seats.get(i).creatures) {
                    c.attacking = s.combat && i == active && c.creature;
                    c.tapped = c.attacking;   // attackers tap, so the demo shows the tap/untap animation
                    c.blocking = false;
                }
            }
            // Arcs: attackers spread over the other seats; from a couple of seconds into combat the
            // first one is blocked by the defender's first creature, so both arc colours show.
            s.combatArcs.clear();
            if (s.combat) {
                int k = 0;
                for (Snapshot.CardV c : s.seats.get(active).creatures) {
                    if (!c.attacking) continue;
                    Snapshot.CombatArc arc = new Snapshot.CombatArc();
                    arc.attackerId = c.id;
                    arc.attackerName = c.name;
                    arc.defenderSeat = (active + 1 + k % (n - 1)) % n;
                    arc.defenderName = s.seats.get(arc.defenderSeat).name;
                    List<Snapshot.CardV> theirs = s.seats.get(arc.defenderSeat).creatures;
                    if (k == 0 && (sec % 12) >= 9 && !theirs.isEmpty()) {
                        theirs.get(0).blocking = true;
                        arc.blockedBy.add(theirs.get(0).id);
                    }
                    s.combatArcs.add(arc);
                    k++;
                }
            }
            return s;
        };

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MTG 3D table - demo data");
        WindowMemory.apply(cfg, 1600, 950);   // last size and position, if any
        cfg.useVsync(true);
        cfg.setForegroundFPS(60);
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
            c.tapped = Math.random() < 0.35;
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
