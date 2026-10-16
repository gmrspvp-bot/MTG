// Writes board-state snapshots into a game's own log, so they appear in order with
// Forge's normal lines (as "Information: BOARD ..."). Subscribed to each game by
// SimRunner before the game starts. Never throws into the game.
//
// Lines written:
//   BOARD turn <n> (<active player>) | <player> | life .. | hand .. | lands L (U untapped) | open mana M
//       | commander <zone> | creatures C (U untapped): name P/T[*=tapped][+equipment] ...
//       | hand cards: Name(mv); ...
//       One per player at the start of every turn (shows what was left open over the
//       previous turn).
//   BOARD attack | <attacker> -> <defender> | attackers N (power P) | defender life ..
//       | untapped creatures U (power P): ... | open mana M
//       One per attacked player, when attackers are declared.
//   BOARD cast | <card> (mv N) by <player> | open mana: <opponent> M, ...
//       For every spell cast; open mana of each opponent at that moment.

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class BoardRecorder {
    private final Game game;

    public BoardRecorder(Game game) { this.game = game; }

    @Subscribe
    public void onEvent(Object ev) {
        try {
            if (ev instanceof GameEventTurnBegan) {
                Player active = game.getPhaseHandler().getPlayerTurn();
                String head = "turn " + game.getPhaseHandler().getTurn() + " (" + (active == null ? "?" : active.getName()) + ") | ";
                for (Player p : game.getPlayers()) log(head + playerLine(p));
            } else if (ev instanceof GameEventAttackersDeclared) {
                logAttack();
            } else if (ev instanceof GameEventSpellAbilityCast) {
                logCast();
            }
        } catch (Throwable t) {
            // A snapshot must never break the game.
        }
    }

    private void log(String msg) {
        game.getGameLog().add(GameLogEntryType.INFORMATION, "BOARD " + msg);
    }

    private static int openMana(Player p) {
        try { return forge.ai.ComputerUtilMana.getAvailableManaEstimate(p); } catch (Throwable t) { return -1; }
    }

    private static String creatureList(List<Card> creatures) {
        // Group identical creatures (token swarms) as "Name P/T x3".
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Card c : creatures) {
            StringBuilder s = new StringBuilder(c.getName()).append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
            if (c.isTapped()) s.append('*');
            StringBuilder eq = new StringBuilder();
            for (Card e : c.getEquippedBy()) { if (eq.length() > 0) eq.append(','); eq.append(e.getName()); }
            if (eq.length() > 0) s.append('[').append(eq).append(']');
            groups.merge(s.toString(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey());
            if (e.getValue() > 1) out.append(" x").append(e.getValue());
        }
        return out.toString();
    }

    private String playerLine(Player p) {
        if (p.hasLost()) return p.getName() + " | out";
        int lands = 0, untappedLands = 0, untappedCreatures = 0;
        List<Card> creatures = new java.util.ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand()) { lands++; if (!c.isTapped()) untappedLands++; }
            if (c.isCreature()) { creatures.add(c); if (!c.isTapped()) untappedCreatures++; }
        }
        // p.getCommanders() keeps a stale zone once the card moves, so look for it directly.
        String cmd = "Elsewhere";
        for (Card c : p.getCardsIn(ZoneType.Command)) { if (c.isCommander()) cmd = "Command"; }
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) { if (c.isCommander() && c.getOwner() == p) cmd = "Battlefield"; }
        return p.getName() + " | life " + p.getLife() + " | hand " + p.getCardsIn(ZoneType.Hand).size()
            + " | lands " + lands + " (" + untappedLands + " untapped) | open mana " + openMana(p)
            + " | commander " + cmd + " | creatures " + creatures.size() + " (" + untappedCreatures + " untapped): "
            + creatureList(creatures) + " | hand cards: " + handList(p);
    }

    /** "Name(mv); Name(mv)" for the cards in hand (lets reports spot cards stuck in hand). */
    private static String handList(Player p) {
        StringBuilder s = new StringBuilder();
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (s.length() > 0) s.append("; ");
            s.append(c.getName()).append('(').append(c.isLand() ? "land" : String.valueOf(c.getCMC())).append(')');
        }
        return s.toString();
    }

    private void logAttack() {
        forge.game.combat.Combat combat = game.getCombat();
        if (combat == null) return;
        Player attacker = combat.getAttackingPlayer();
        for (Player d : combat.getDefendingPlayers()) {
            List<Card> attackers = combat.getAttackersOf(d);
            if (attackers.isEmpty()) continue;
            int power = 0;
            for (Card a : attackers) power += Math.max(0, a.getNetPower());
            List<Card> blockers = new java.util.ArrayList<>();
            int blockPower = 0;
            for (Card c : d.getCardsIn(ZoneType.Battlefield)) {
                if (c.isCreature() && !c.isTapped()) { blockers.add(c); blockPower += Math.max(0, c.getNetPower()); }
            }
            log("attack | " + attacker.getName() + " -> " + d.getName() + " | attackers " + attackers.size() + " (power " + power
                + ") | defender life " + d.getLife() + " | untapped creatures " + blockers.size() + " (power " + blockPower + "): "
                + creatureList(blockers) + " | open mana " + openMana(d));
        }
    }

    private void logCast() {
        SpellAbility sa = game.getStack().peekAbility();
        if (sa == null || !sa.isSpell() || sa.getHostCard() == null) return;
        Player caster = sa.getActivatingPlayer();
        StringBuilder s = new StringBuilder("cast | ").append(sa.getHostCard().getName()).append(" (mv ")
            .append(sa.getHostCard().getCMC()).append(") by ").append(caster == null ? "?" : caster.getName()).append(" | open mana: ");
        boolean first = true;
        for (Player p : game.getPlayers()) {
            if (p == caster || p.hasLost()) continue;
            if (!first) s.append(", ");
            first = false;
            s.append(p.getName()).append(' ').append(openMana(p));
        }
        log(s.toString());
    }
}
