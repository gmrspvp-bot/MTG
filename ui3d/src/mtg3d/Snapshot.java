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
        public boolean artifact;
        public boolean enchantment;
        public boolean planeswalker;
        public boolean attacking;
        public boolean blocking;
        public boolean commander;
        public int counters;
        /** Who controls it. Used to label stack items, since the card alone does not say. */
        public String controller = "";
        /** For a stack item: what it is actually doing, which may not be the card's own text. */
        public String stackText = "";
        /** A token: no printing exists, so it never has cached art and should look different. */
        public boolean token;
        /**
         * Face down (morph, manifest, cloaked). Nothing identifying is ever filled in for one of
         * these - see StateReader - so there is nothing here that could leak.
         */
        public boolean faceDown;
        /** What this is attached to, if anything: equipment, auras, fortifications. */
        public String attachedTo = "";
        /** For a commander: times cast from the command zone so far, so its tax is 2 x this. */
        public int commanderCasts;
        /** The host's card id, or -1: lets the table lay an attachment against the right copy. */
        public int attachedToId = -1;
        /**
         * Counters spelled out, as "+1/+1 x3" entries, biggest first. Separate from the plain
         * `counters` total above, which Table3D already uses - that file belongs to the other
         * agent's animation work at the moment, so this is additive rather than a rename.
         */
        public final List<String> counterText = new ArrayList<>();

        public CardV() {}

        public CardV(int id, String name, String typeLine, boolean creature, boolean land) {
            this.id = id;
            this.name = name;
            this.typeLine = typeLine;
            this.creature = creature;
            this.land = land;
        }
    }

    /** A commander-damage entry: how much, and from whose commander. */
    public static final class CmdDmg {
        public final String from;
        public final int amount;
        public CmdDmg(String from, int amount) { this.from = from; this.amount = amount; }
    }

    /**
     * One attack: who is swinging, at whom, and what is in the way.
     *
     * Kept as its own list rather than as fields scattered over the attacking cards, because this
     * is the shape a renderer wants - an arc has two ends, and the far end may be a player's seat
     * or a permanent (a planeswalker or a battle), which a flag on the attacker cannot express.
     */
    public static final class CombatArc {
        public int attackerId;
        public String attackerName = "";
        /** Seat index being attacked, or -1 when the target is a permanent. */
        public int defenderSeat = -1;
        /** Card id being attacked (planeswalker, battle), or -1 when the target is a player. */
        public int defenderCardId = -1;
        public String defenderName = "";
        /** Card ids blocking this attacker. Empty means it is getting through. */
        public final List<Integer> blockedBy = new ArrayList<>();
    }

    /** Every attack this combat, empty outside combat. */
    public final List<CombatArc> combatArcs = new ArrayList<>();

    /** One player's seat: their totals and what is on their battlefield. */
    public static final class SeatV {
        public String name = "";
        public int life = 40;
        public int handSize;
        public int librarySize;
        public int graveyardSize;
        public int exileSize;
        public int poison;
        /** Commander damage this seat has TAKEN, by the commander that dealt it. Public knowledge. */
        public final List<CmdDmg> commanderDamage = new ArrayList<>();
        public boolean isYou;
        public boolean active;          // whose turn it is
        public boolean hasPriority;
        /**
         * The battlefield in rows, so each kind of permanent has its own band on the table.
         * Ordered from the middle of the table outwards: creatures, then other permanents,
         * then lands nearest their owner.
         */
        public final List<CardV> creatures = new ArrayList<>();
        public final List<CardV> others = new ArrayList<>();
        public final List<CardV> lands = new ArrayList<>();
        /**
         * The command zone: a commander between casts, plus anything else that lives there
         * (emblems, a companion). Public knowledge, so every seat's is read.
         */
        public final List<CardV> command = new ArrayList<>();
    }

    public final List<SeatV> seats = new ArrayList<>();
    public String phase = "";
    /**
     * The phase as Forge names it (UNTAP, UPKEEP, MAIN1, COMBAT_DECLARE_ATTACKERS...). The pretty
     * string above is for reading; this is for matching, because a phase strip has to know which
     * step it is on and comparing prettified text would break the moment the wording changed.
     */
    public String phaseKey = "";
    public String turnLabel = "";
    /** Drives the camera: combat or targeting lifts the view to look down on the whole table. */
    public boolean combat;
    public boolean targeting;
    /** Cards in your hand, drawn along the bottom edge. */
    public final List<CardV> hand = new ArrayList<>();
    /**
     * The stack, BOTTOM FIRST - the order things were put on it, so the bar fills left to right
     * as spells are cast. Forge iterates its stack top-first, so StateReader reverses it.
     */
    public final List<CardV> stack = new ArrayList<>();

    public SeatV you() {
        for (SeatV s : seats) if (s.isYou) return s;
        return seats.isEmpty() ? null : seats.get(0);
    }

    public int youIndex() {
        for (int i = 0; i < seats.size(); i++) if (seats.get(i).isYou) return i;
        return 0;
    }
}
