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
        /** A stack item that is an activated or triggered ability rather than a spell. */
        public boolean stackIsAbility;
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
         * For a stack item: what it is aimed at - card ids (a permanent, another spell on the
         * stack, a card in a graveyard) and seat indices (a player). Targets are public, so these
         * are filled for everyone's spells. Empty for a spell with no targets.
         */
        public final List<Integer> targetIds = new ArrayList<>();
        public final List<Integer> targetSeats = new ArrayList<>();
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
        /** The attacker's power, so the arrow can be drawn as heavy as the hit it represents. */
        public int power;
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
        /** Out of the game. The seat stays where it was, so nobody else's board moves. */
        public boolean lost;
        /**
         * Public designations and player counters, as short labels ready to print: "MONARCH",
         * "INITIATIVE", "CITY'S BLESSING", "RING-BEARER: Frodo", "SPEED 3", "POISON 4",
         * "ENERGY 2". Empty for most players most of the time.
         */
        public final List<String> marks = new ArrayList<>();
        /**
         * Has passed priority since the last thing changed (a spell cast, a step begun). When it
         * comes back round to the first of them with nothing new, the top of the stack resolves
         * or the step ends - so the seats NOT yet passed are the ones everyone is waiting on.
         */
        public boolean passed;
        /** Floating mana, by colour: W, U, B, R, G, colourless. Public; usually all zero. */
        public final int[] mana = new int[6];
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
        /**
         * Graveyard and exile, top (most recent) first, for browsing from their pads. Both are
         * public zones; a face-down exiled card (foretell, hideaway) still comes through blank.
         */
        public final List<CardV> graveyard = new ArrayList<>();
        public final List<CardV> exile = new ArrayList<>();
    }

    public final List<SeatV> seats = new ArrayList<>();
    /** The game has ended; `winner` is the winning player's name, or blank for a draw. */
    public boolean gameOver;
    public String winner = "";
    /** "DAY", "NIGHT", or blank before anything has made it day or night. */
    public String dayNight = "";
    /**
     * The most recent game log lines, OLDEST first, at Forge's own MEDIUM verbosity - the same
     * log Forge's window shows this player, so nothing in it is new information.
     */
    public final List<LogLine> log = new ArrayList<>();

    /** One log line, with its Forge entry type ("STACK_ADD", "DAMAGE", ...) for colouring. */
    public static final class LogLine {
        public final String type, text;
        public LogLine(String type, String text) { this.type = type; this.text = text; }
    }
    public String phase = "";
    /**
     * The phase as Forge names it (UNTAP, UPKEEP, MAIN1, COMBAT_DECLARE_ATTACKERS...). The pretty
     * string above is for reading; this is for matching, because a phase strip has to know which
     * step it is on and comparing prettified text would break the moment the wording changed.
     */
    public String phaseKey = "";

    /**
     * What the engine is currently asking of us, mirrored by Gui3D on its way to Forge's window.
     *
     * Carried here so the 3D view can show the same prompt without asking Forge's UI anything,
     * and so a later step can answer from this window. Empty while the input wrapper is off
     * (-Dforge.ui3d.input=false) or in an all-AI game.
     */
    public String promptText = "";
    public String okLabel = "OK";
    public String cancelLabel = "Cancel";
    public boolean okEnabled;
    public boolean cancelEnabled;
    /** Card ids Forge says may be clicked right now. */
    public final List<Integer> selectableIds = new ArrayList<>();
    /** A dialog waiting in Forge's own window that the 3D side cannot answer. Empty if none. */
    public String forgeDialog = "";
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
