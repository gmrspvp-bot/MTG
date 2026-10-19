package mtg3d;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

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
            s.phase = s.combat ? "Declare attackers" : "Main phase, precombat";
            s.turnLabel = "Turn " + (turn + 1) + " - " + s.seats.get(active).name;
            return s;
        };

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MTG 3D table - demo data");
        cfg.setWindowedMode(1600, 950);
        cfg.useVsync(true);
        cfg.setForegroundFPS(60);
        new Lwjgl3Application(new Table3D(source), cfg);
    }

    private static Snapshot build() {
        Snapshot s = new Snapshot();
        s.turnLabel = "Turn 7 - Xenil";
        s.seats.add(seat("Xenil", 34, true, true,
            new String[] { "Plains", "Plains", "Emeria, the Sky Ruin", "Sol Ring", "Secluded Courtyard" },
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
        v.handSize = 4;
        v.librarySize = 80;
        v.graveyardSize = 6;
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
            v.nonlands.add(c);
        }
        return v;
    }

    private static Snapshot.CardV card(int id, String name, String type, boolean creature, boolean land,
                                       String cost, String text) {
        Snapshot.CardV c = new Snapshot.CardV(id, name, type, creature, land);
        c.manaCost = cost;
        c.text = text;
        return c;
    }
}
