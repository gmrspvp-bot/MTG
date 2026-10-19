package mtg3d;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Turns Forge's live Game into a Snapshot for the renderer.
 *
 * Always called on the game thread (from a game event), never from the render thread, and the
 * result is handed over as one finished object. That is the whole concurrency story: the renderer
 * never walks Forge's object graph while the engine is changing it.
 *
 * Stage 1 shows only public information plus your own hand - the same thing Forge's own UI shows
 * the seat it belongs to. The hand is read for the player this client controls, so a shared
 * screen or a network client never renders somebody else's cards.
 */
public final class StateReader {
    private StateReader() {}

    public static Snapshot read(Game game) {
        Snapshot s = new Snapshot();
        try {
            forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
            Player turn = ph.getPlayerTurn();
            Player prio = ph.getPriorityPlayer();
            s.phase = ph.getPhase() == null ? "" : pretty(ph.getPhase().toString());
            s.turnLabel = "Turn " + game.getPhaseHandler().getTurn() + (turn == null ? "" : " - " + turn.getName());
            s.combat = game.getCombat() != null;

            // Who is attacking, so the table can mark them. Taken from the live Combat rather than
            // from each card, because a card only knows it is attacking while combat exists.
            java.util.Set<Integer> attackers = new java.util.HashSet<>();
            try {
                forge.game.combat.Combat combat = game.getCombat();
                if (combat != null) {
                    for (Card a : combat.getAttackersAndDefenders().keySet()) attackers.add(a.getId());
                }
            } catch (Throwable t) {
                // no markers, rest of the view is unaffected
            }

            Player you = guiPlayer(game);
            for (Player p : game.getRegisteredPlayers()) {
                Snapshot.SeatV seat = new Snapshot.SeatV();
                seat.name = p.getName();
                seat.life = p.getLife();
                seat.isYou = (p == you);
                seat.active = (p == turn);
                seat.hasPriority = (p == prio);
                seat.handSize = count(p, ZoneType.Hand);
                seat.librarySize = count(p, ZoneType.Library);
                seat.graveyardSize = count(p, ZoneType.Graveyard);
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    Snapshot.CardV v = card(c);
                    v.attacking = attackers.contains(v.id);
                    if (v.land) seat.lands.add(v); else seat.nonlands.add(v);
                }
                s.seats.add(seat);
            }
            if (you != null) {
                for (Card c : you.getCardsIn(ZoneType.Hand)) s.hand.add(card(c));
            }
        } catch (Throwable t) {
            // A half-built snapshot is better than killing the game thread; the view just shows less.
        }
        return s;
    }

    /** The player this client controls, or null in an all-AI game. */
    private static Player guiPlayer(Game game) {
        for (Player p : game.getRegisteredPlayers()) {
            Object c = p.getController();
            if (!(c instanceof forge.player.PlayerControllerHuman)) continue;
            try {
                if (((forge.player.PlayerControllerHuman) c).isGuiPlayer()) return p;
            } catch (Throwable t) {
                return p;
            }
        }
        return null;
    }

    private static int count(Player p, ZoneType z) {
        try {
            return p.getCardsIn(z).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Snapshot.CardV card(Card c) {
        Snapshot.CardV v = new Snapshot.CardV();
        try {
            v.id = c.getId();
            v.name = c.getName();
            v.typeLine = c.getType() == null ? "" : c.getType().toString();
            v.land = c.isLand();
            v.creature = c.isCreature();
            v.tapped = c.isTapped();
            v.commander = c.isCommander();
            v.manaCost = c.getManaCost() == null ? "" : c.getManaCost().toString();
            v.text = c.getOracleText() == null ? "" : c.getOracleText();
            if (v.creature) {
                v.power = String.valueOf(c.getNetPower());
                v.toughness = String.valueOf(c.getNetToughness());
            }
        } catch (Throwable t) {
            if (v.name == null || v.name.isEmpty()) v.name = "?";
        }
        return v;
    }

    /** MAIN1 -> "Main 1", DECLARE_ATTACKERS -> "Declare attackers". */
    private static String pretty(String phase) {
        String s = phase.replace('_', ' ').toLowerCase();
        if (s.startsWith("main")) s = "main " + s.substring(4).trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
