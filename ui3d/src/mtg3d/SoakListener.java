package mtg3d;

import forge.game.Game;

/**
 * Drives the 3D table's read path over an ordinary all-AI game, with no window and no human.
 *
 * {@link StateReader#read} is the part most exposed to Forge: it walks every zone of every seat
 * through the view layer, and a card shape it does not expect - a token, a face-down, a melded
 * pair, an attached aura whose host has just left - throws inside a catch that hides it. Playing
 * a game by hand reaches a handful of those shapes an hour. A batch of AI games reaches thousands
 * in a few minutes, and needs nobody sitting there.
 *
 * This is deliberately NOT a test of the renderer: no OpenGL, no camera, no input. It tests the
 * bridge, which is where the crashes have actually been.
 *
 * Subscribed by SimRunner when -Dforge.ui3d.soak=true. {@link Soak} counts what it finds.
 */
public final class SoakListener {

    private final Game game;

    public SoakListener(Game game) { this.game = game; }

    @com.google.common.eventbus.Subscribe
    public void onEvent(Object ev) {
        try {
            Snapshot s = StateReader.read(game);
            check(s);
        } catch (Throwable t) {
            // read() guards itself, so reaching here means the failure was in check()
            Soak.caught("SoakListener", t);
        }
    }

    /**
     * Things that are not exceptions but are still wrong. A snapshot the renderer would draw
     * incorrectly is a bug even though nothing threw, and these are the invariants the view
     * relies on without ever stating them.
     */
    private static void check(Snapshot s) {
        if (s == null) { Soak.caught("snapshot.null", new IllegalStateException("read returned null")); return; }
        for (Snapshot.SeatV seat : s.seats) {
            if (seat == null) { Soak.caught("seat.null", new IllegalStateException("null seat")); continue; }
            if (seat.name == null || seat.name.isEmpty()) {
                Soak.caught("seat.name.blank", new IllegalStateException("seat with no name"));
            }
            if (seat.handSize < 0 || seat.librarySize < 0) {
                Soak.caught("seat.zone.negative", new IllegalStateException(
                    seat.name + " hand " + seat.handSize + " library " + seat.librarySize));
            }
            cards(seat.creatures, "creatures");
            cards(seat.others, "others");
            cards(seat.lands, "lands");
            cards(seat.command, "command");
            cards(seat.graveyard, "graveyard");
            cards(seat.exile, "exile");
            // A creature band holding a land (or the reverse) means the type sort went wrong,
            // which is how the battlefield ends up drawn in the wrong row.
            for (Snapshot.CardV c : seat.lands) {
                if (c != null && !c.land && !c.faceDown) {
                    Soak.caught("band.land.notLand", new IllegalStateException(c.name + " / " + c.typeLine));
                }
            }
        }
        cards(s.stack, "stack");
        // An all-AI game has no viewer, so our own hand must stay empty. Anything here would mean
        // the read path is exposing cards with nobody entitled to see them.
        if (!s.hand.isEmpty()) {
            Soak.caught("hand.leaked", new IllegalStateException(
                "no local viewer, yet " + s.hand.size() + " cards in our hand"));
        }
    }

    private static void cards(java.util.List<Snapshot.CardV> list, String zone) {
        if (list == null) return;
        for (Snapshot.CardV c : list) {
            if (c == null) { Soak.caught(zone + ".card.null", new IllegalStateException("null card")); continue; }
            if (c.name == null) Soak.caught(zone + ".card.name.null", new IllegalStateException("card id " + c.id));
        }
    }
}
