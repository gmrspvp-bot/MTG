package mtg3d;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable picture of the game for the renderer.
 *
 * The 3D view runs on LibGDX's render thread while Forge mutates its Game on the game thread, so
 * the renderer never touches Forge objects directly. StateReader builds one of these on the game
 * thread whenever something happens, and the renderer only ever reads the latest complete one.
 * That keeps the two worlds apart and means the view can also be driven by fake data (Demo), which
 * is how it is developed without starting Forge at all.
 *
 * Deliberately plain: no Forge imports, so this compiles and runs against nothing but LibGDX.
 */
public final class Snapshot {

    /** One card as the table needs to draw it. */
    public static final class CardV {
        public int id;
        public String name = "";
        public String typeLine = "";
        public String text = "";
        public String manaCost = "";
        public String power = "";       // blank for non-creatures
        public String toughness = "";
        public boolean tapped;
        public boolean creature;
        public boolean land;
        public boolean attacking;
        public boolean blocking;
        public boolean commander;
        public int counters;

        public CardV() {}

        public CardV(int id, String name, String typeLine, boolean creature, boolean land) {
            this.id = id;
            this.name = name;
            this.typeLine = typeLine;
            this.creature = creature;
            this.land = land;
        }
    }

    /** One player's seat: their totals and what is on their battlefield. */
    public static final class SeatV {
        public String name = "";
        public int life = 40;
        public int handSize;
        public int librarySize;
        public int graveyardSize;
        public boolean isYou;
        public boolean active;          // whose turn it is
        public boolean hasPriority;
        public final List<CardV> lands = new ArrayList<>();
        public final List<CardV> nonlands = new ArrayList<>();
    }

    public final List<SeatV> seats = new ArrayList<>();
    public String phase = "";
    public String turnLabel = "";
    /** Drives the camera: combat or targeting lifts the view to look down on the whole table. */
    public boolean combat;
    public boolean targeting;
    /** Cards in your hand, drawn along the bottom edge. */
    public final List<CardV> hand = new ArrayList<>();

    public SeatV you() {
        for (SeatV s : seats) if (s.isYou) return s;
        return seats.isEmpty() ? null : seats.get(0);
    }

    public int youIndex() {
        for (int i = 0; i < seats.size(); i++) if (seats.get(i).isYou) return i;
        return 0;
    }
}
