// Dynamic threat values. Forge values creatures with a fixed formula (power, toughness,
// keywords...) plus an optional per-card "AIEvaluationModifier" hint, and non-creature
// permanents mostly by mana value; nothing changes as a game goes on. This tracker
// watches each game and gives every permanent a threat score from what it has actually
// done, fading over time:
//   + DAMAGE_W per point of damage it dealt to an opponent (combat or not)
//   + ENGINE_W each time one of its (non-mana) abilities or triggers went on the stack
//   x DECAY at the start of every turn (about 0.85 per round in a 4-player game)
// Creatures: the score is added to the card's AIEvaluationModifier (on top of any hint the
// card script already has), so Forge's removal, blocking and attack choices react to it.
// Non-creatures: AiPatches' retargeting removal AIs read score() directly.
// Each round the top threats are written to the game log as "BOARD threat | ...".
//
// Enabled by the "threat" entry in -Dforge.aipatches; subscribed per game by SimRunner.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class ThreatTracker {
    static boolean enabled = false;
    static final double DAMAGE_W = 6, ENGINE_W = 15, DECAY = 0.96, CAP = 300;
    private static final Map<Game, ThreatTracker> BY_GAME = new WeakHashMap<>();

    private final Game game;
    private final Map<Integer, Double> score = new HashMap<>();
    private final Map<Integer, String> originalHint = new HashMap<>();   // null value = had none
    private int lastLoggedRound = -1;

    public ThreatTracker(Game game) {
        this.game = game;
        synchronized (BY_GAME) { BY_GAME.put(game, this); }
    }

    /** Threat score of a permanent (0 if unknown or tracking is off). */
    static double score(Card c) {
        if (c == null) return 0;
        ThreatTracker t;
        synchronized (BY_GAME) { t = BY_GAME.get(c.getGame()); }
        if (t == null) return 0;
        Double s = t.score.get(c.getId());
        return s == null ? 0 : s;
    }

    @Subscribe
    public void onEvent(Object ev) {
        try {
            if (ev instanceof GameEventPlayerDamaged) {
                GameEventPlayerDamaged e = (GameEventPlayerDamaged) ev;
                if (e.source() == null || e.target() == null) return;
                Card src = game.findById(e.source().getId());
                if (src == null || !src.isInPlay()) return;
                Player victim = null;
                for (Player p : game.getPlayers()) { if (p.getView() == e.target()) victim = p; }
                if (victim != null && victim.isOpponentOf(src.getController())) add(src, DAMAGE_W * e.amount());
            } else if (ev instanceof GameEventSpellAbilityCast) {
                SpellAbility sa = game.getStack().peekAbility();
                if (sa == null || sa.isSpell() || sa.isManaAbility()) return;
                Card host = sa.getHostCard();
                if (host != null && host.isInPlay()) add(host, ENGINE_W);
            } else if (ev instanceof GameEventCardChangeZone) {
                GameEventCardChangeZone e = (GameEventCardChangeZone) ev;
                if (e.from() != null && e.from().zoneType() == ZoneType.Battlefield && e.card() != null) {
                    score.remove(e.card().getId());
                    originalHint.remove(e.card().getId());
                }
            } else if (ev instanceof GameEventTurnBegan) {
                for (Map.Entry<Integer, Double> en : score.entrySet()) en.setValue(en.getValue() * DECAY);
                apply();
                logTop();
            }
        } catch (Throwable t) {
            // Never break the game.
        }
    }

    private void add(Card c, double points) {
        score.merge(c.getId(), points, Double::sum);
        if (score.get(c.getId()) > CAP) score.put(c.getId(), CAP);
        if (c.isCreature()) applyTo(c);
    }

    private void apply() {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && (score.containsKey(c.getId()) || originalHint.containsKey(c.getId()))) applyTo(c);
        }
    }

    /** AIEvaluationModifier = the card script's own hint (evaluated now) + threat score. */
    private void applyTo(Card c) {
        int id = c.getId();
        if (!originalHint.containsKey(id)) originalHint.put(id, c.hasSVar("AIEvaluationModifier") ? c.getSVar("AIEvaluationModifier") : null);
        String orig = originalHint.get(id);
        int base = 0;
        if (orig != null) {
            try { base = forge.game.ability.AbilityUtils.calculateAmount(c, orig, null); } catch (Throwable t) { base = 0; }
        }
        int threat = (int) Math.round(score.getOrDefault(id, 0.0));
        if (threat <= 0 && orig == null) { c.removeSVar("AIEvaluationModifier"); return; }
        c.setSVar("AIEvaluationModifier", String.valueOf(base + threat));
    }

    private void logTop() {
        int round = game.getPhaseHandler().getTurn();
        if (round == lastLoggedRound) return;
        lastLoggedRound = round;
        List<Card> cards = new ArrayList<>();
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) { if (score.getOrDefault(c.getId(), 0.0) >= 30) cards.add(c); }
        if (cards.isEmpty()) return;
        cards.sort((a, b) -> Double.compare(score.get(b.getId()), score.get(a.getId())));
        StringBuilder s = new StringBuilder("BOARD threat | turn ").append(round).append(" | ");
        for (int i = 0; i < Math.min(5, cards.size()); i++) {
            Card c = cards.get(i);
            if (i > 0) s.append("; ");
            s.append(c.getName()).append(" (").append(c.getController().getName()).append(") ")
             .append(Math.round(score.get(c.getId())));
        }
        game.getGameLog().add(GameLogEntryType.INFORMATION, s.toString());
    }
}
