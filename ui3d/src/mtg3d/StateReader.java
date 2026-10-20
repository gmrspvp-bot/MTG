package mtg3d;

import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Forge's live game into a Snapshot for the renderer.
 *
 * Reads through Forge's **view layer** - GameView, PlayerView, CardView - and not through Game,
 * Player and Card. That is the whole point of this class and it is not an implementation detail:
 *
 *   Forge already decides what each player is allowed to see. CardView.canBeShownTo(viewer) is
 *   the engine's own ruling, and it is the same one Forge's own UI relies on. The earlier version
 *   of this file read the real objects and applied a rule of its own ("show the hand of whoever
 *   this client controls"), which was adequate while this was only a viewer and would not survive
 *   becoming a client: it knew nothing about revealed cards, cards an effect lets one opponent
 *   see, face-down permanents their controller may look at, or a player under another's control.
 *
 * Everything still runs on the game thread, and the Snapshot handed to the renderer is still a
 * plain object with no Forge types in it. Only the source changed.
 */
public final class StateReader {
    private StateReader() {}

    public static Snapshot read(Game game) {
        Snapshot s = new Snapshot();
        try {
            GameView gv = game.getView();
            if (gv == null) return s;
            PlayerView viewer = localViewer(game);

            PlayerView turn = gv.getPlayerTurn();
            s.phase = gv.getPhase() == null ? "" : pretty(gv.getPhase().toString());
            s.phaseKey = gv.getPhase() == null ? "" : gv.getPhase().name();
            s.turnLabel = "Turn " + gv.getTurn() + (turn == null ? "" : " - " + turn.getName());

            // Seats come from the REGISTERED players, not gv.getPlayers(): that one is
            // Game.getPlayers(), which drops a player the moment they lose - their seat would
            // vanish and every seat after it would shift round the table. Their views are the
            // same objects the rest of the view layer hands out, so `pv == viewer` still holds.
            List<PlayerView> order = new ArrayList<>();
            for (forge.game.player.Player p : game.getRegisteredPlayers()) order.add(p.getView());
            if (order.isEmpty()) for (PlayerView pv : gv.getPlayers()) order.add(pv);
            try {
                s.dayNight = game.isDay() ? "DAY" : game.isNight() ? "NIGHT" : "";
            } catch (Throwable t) {
                // neither
            }
            s.gameOver = gv.isGameOver();
            if (s.gameOver) {
                String w = gv.getWinningPlayerName();
                s.winner = w == null ? "" : w;
            }

            // So a click on a seat in the 3D window can find the PlayerView again. Same
            // reason as the card registry: Forge wants the object, the renderer only has an
            // index.
            Gui3D.rememberSeats(order);

            readCombat(gv, viewer, order, s);

            for (PlayerView pv : order) {
                Snapshot.SeatV seat = new Snapshot.SeatV();
                seat.name = pv.getName();
                seat.life = pv.getLife();
                seat.isYou = (viewer != null && pv == viewer);
                seat.lost = pv.getHasLost();
                seat.active = (pv == turn);
                seat.hasPriority = pv.getHasPriority();
                seat.handSize = zone(pv, ZoneType.Hand);
                seat.librarySize = zone(pv, ZoneType.Library);
                seat.graveyardSize = zone(pv, ZoneType.Graveyard);
                seat.exileSize = zone(pv, ZoneType.Exile);
                seat.poison = counter(pv, "POISON");
                readCommanderDamage(pv, order, seat);
                readMarks(game, pv, seat);

                for (CardView cv : cards(pv, ZoneType.Battlefield)) {
                    Snapshot.CardV v = card(cv, viewer);
                    v.attacking = isAttacking(cv);
                    if (v.creature) seat.creatures.add(v);
                    else if (v.land) seat.lands.add(v);
                    else seat.others.add(v);
                }
                // The command zone is public, so it is read for every seat, not just the viewer's.
                for (CardView cv : cards(pv, ZoneType.Command)) {
                    Snapshot.CardV v = card(cv, viewer);
                    try {
                        if (cv.isCommander()) v.commanderCasts = pv.getCommanderCast(cv);
                    } catch (Throwable t) {
                        // no tax shown
                    }
                    seat.command.add(v);
                }
                s.seats.add(seat);
            }

            // The hand. Asking canBeShownTo of every card means a revealed hand, or a card an
            // effect lets us see, appears without this class knowing the rule that made it so.
            if (viewer != null) {
                for (CardView cv : cards(viewer, ZoneType.Hand)) s.hand.add(card(cv, viewer));
            }

            readStack(gv, viewer, order, s);

            // What the engine is asking, as the input wrapper saw it on its way to Forge's
            // window. Read here rather than pushed from Gui3D, so the renderer still receives
            // everything in one finished snapshot instead of some of it arriving out of band.
            Gui3D.Prompt p = Gui3D.PROMPT;
            s.promptText = p.message;
            s.okLabel = p.okLabel;
            s.cancelLabel = p.cancelLabel;
            s.okEnabled = p.okEnabled;
            s.cancelEnabled = p.cancelEnabled;
            s.selectableIds.addAll(p.selectable);
            // Only recent dialogs: a stale title still showing minutes later would be worse
            // than none, because it would say the game is waiting when it is not.
            if (!p.forgeDialog.isEmpty()
                    && System.currentTimeMillis() - p.forgeDialogAt < 20_000L) {
                s.forgeDialog = p.forgeDialog;
            }
            readPassed(game, s);
        } catch (Throwable t) {
            // A half-built snapshot is better than killing the game thread; the view shows less.
        }
        return s;
    }

    /** The view belonging to the player this client controls, or null in an all-AI game. */
    private static PlayerView localViewer(Game game) {
        try {
            for (forge.game.player.Player p : game.getRegisteredPlayers()) {
                Object c = p.getController();
                if (!(c instanceof forge.player.PlayerControllerHuman)) continue;
                forge.player.PlayerControllerHuman h = (forge.player.PlayerControllerHuman) c;
                if (h.isGuiPlayer()) return h.getLocalPlayerView();
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    private static int zone(PlayerView pv, ZoneType z) {
        try {
            return pv.getZoneSize(z);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Iterable<CardView> cards(PlayerView pv, ZoneType z) {
        try {
            Iterable<CardView> it = pv.getCards(z);
            return it == null ? new ArrayList<CardView>() : it;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * Designations and player counters for one seat. Monarch, initiative, city's blessing, the
     * ring-bearer and speed are not in the view layer at all, so they come from Game/Player -
     * which is safe for the hidden-information rule only because every one of them is public:
     * the whole table knows who the monarch is. Nothing private may be added here this way.
     */
    private static void readMarks(Game game, PlayerView pv, Snapshot.SeatV seat) {
        try {
            forge.game.player.Player p = null;
            for (forge.game.player.Player q : game.getRegisteredPlayers()) if (q.getView() == pv) { p = q; break; }
            if (p != null) {
                if (p.isMonarch()) seat.marks.add("MONARCH");
                if (p.hasInitiative()) seat.marks.add("INITIATIVE");
                if (p.hasBlessing()) seat.marks.add("CITY'S BLESSING");
                forge.game.card.Card bearer = p.getRingBearer();
                // A face-down ring-bearer is still the ring-bearer, but its name is not public.
                if (bearer != null) seat.marks.add(bearer.isFaceDown() ? "RING-BEARER" : "RING-BEARER: " + bearer.getName());
                if (!p.noSpeed()) seat.marks.add("SPEED " + p.getSpeed());
            }
        } catch (Throwable t) {
            // designations just do not show
        }
        try {
            for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> c
                    : pv.getCounters().entrySet()) {
                if (c.getCount() > 0) seat.marks.add(c.getElement().getName().toUpperCase() + " " + c.getCount());
            }
        } catch (Throwable t) {
            // no counters shown
        }
    }

    private static java.lang.reflect.Field firstPriority;

    /**
     * Who has passed priority this round. Forge keeps the player who started the round (the one
     * who cast the last spell, or the active player at the start of a step) in PhaseHandler's
     * private pFirstPriority, and resolves when priority would come back round to them. So the
     * passed players are those from that one, in turn order, up to but not including whoever holds
     * priority now. Read by reflection because there is no getter; it is public knowledge (every
     * player sees each pass), and if the field is ever renamed the panel just shows no passes.
     */
    private static void readPassed(Game game, Snapshot s) {
        try {
            forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
            forge.game.player.Player prio = ph.getPriorityPlayer();
            if (firstPriority == null) {
                firstPriority = forge.game.phase.PhaseHandler.class.getDeclaredField("pFirstPriority");
                firstPriority.setAccessible(true);
            }
            forge.game.player.Player first = (forge.game.player.Player) firstPriority.get(ph);
            if (prio == null || first == null || first == prio) return;
            List<forge.game.player.Player> reg = game.getRegisteredPlayers();
            forge.game.player.Player p = first;
            for (int guard = 0; p != null && p != prio && guard < reg.size(); guard++) {
                int seat = reg.indexOf(p);
                if (seat >= 0 && seat < s.seats.size()) s.seats.get(seat).passed = true;
                p = game.getNextPlayerAfter(p);
            }
        } catch (Throwable t) {
            // no pass markers
        }
    }

    private static int counter(GameEntityView e, String name) {
        try {
            for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> c
                    : e.getCounters().entrySet()) {
                if (name.equalsIgnoreCase(c.getElement().getName())) return c.getCount();
            }
        } catch (Throwable t) {
            // none
        }
        return 0;
    }

    /** How much commander damage this seat has taken, from every commander at the table. */
    private static void readCommanderDamage(PlayerView pv, List<PlayerView> order, Snapshot.SeatV seat) {
        try {
            for (PlayerView other : order) {
                List<CardView> cmds = other.getCommanders();
                if (cmds == null) continue;
                for (CardView cmd : cmds) {
                    int dmg = pv.getCommanderDamage(cmd);
                    if (dmg > 0) seat.commanderDamage.add(new Snapshot.CmdDmg(cmd.getName(), dmg));
                }
            }
        } catch (Throwable t) {
            // trackers just show less
        }
    }

    private static boolean isAttacking(CardView cv) {
        try {
            return cv.isAttacking();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Attackers, who they are attacking, and what is blocking them. */
    private static void readCombat(GameView gv, PlayerView viewer, List<PlayerView> order, Snapshot s) {
        try {
            CombatView combat = gv.getCombat();
            if (combat == null) return;
            s.combat = true;
            for (CardView a : combat.getAttackers()) {
                Snapshot.CombatArc arc = new Snapshot.CombatArc();
                arc.attackerId = a.getId();
                arc.attackerName = a.getName();
                GameEntityView def = combat.getDefender(a);
                // The far end is a seat or a permanent; a planeswalker or battle is a CardView,
                // so both have to be carried and the renderer uses whichever is set.
                if (def instanceof PlayerView) {
                    arc.defenderSeat = order.indexOf((PlayerView) def);
                    arc.defenderName = def.getName();
                } else if (def instanceof CardView) {
                    CardView dc = (CardView) def;
                    arc.defenderCardId = dc.getId();
                    arc.defenderName = dc.getName();
                    if (dc.getController() != null) arc.defenderSeat = order.indexOf(dc.getController());
                }
                Iterable<CardView> blockers = combat.getBlockers(a);
                if (blockers != null) for (CardView b : blockers) arc.blockedBy.add(b.getId());
                s.combatArcs.add(arc);
            }
        } catch (Throwable t) {
            // no arcs; the rest of the view is unaffected
        }
    }

    /** The stack, bottom first, so the bar fills left to right in the order things were cast. */
    /**
     * What a stack item is aimed at, for the target arcs. The item and each sub-instance under it
     * (a modal spell's second mode, a spell's follow-up ability) can carry its own targets, so the
     * whole chain is walked. Targets are announced publicly, so nothing here is hidden information;
     * a target card the viewer cannot see still only contributes its id.
     */
    private static void readTargets(StackItemView si, List<PlayerView> order, Snapshot.CardV v) {
        try {
            for (StackItemView it = si; it != null; it = it.getSubInstance()) {
                if (it.getTargetCards() != null) {
                    for (CardView t : it.getTargetCards()) if (!v.targetIds.contains(t.getId())) v.targetIds.add(t.getId());
                }
                if (it.getTargetPlayers() != null) {
                    for (PlayerView p : it.getTargetPlayers()) {
                        int seat = order.indexOf(p);
                        if (seat >= 0 && !v.targetSeats.contains(seat)) v.targetSeats.add(seat);
                    }
                }
                if (it.getSubInstance() == it) break;
            }
        } catch (Throwable t) {
            // no arcs for this item
        }
    }

    private static void readStack(GameView gv, PlayerView viewer, List<PlayerView> order, Snapshot s) {
        try {
            List<Snapshot.CardV> topFirst = new ArrayList<>();
            for (StackItemView si : gv.getStack()) {
                CardView src = si.getSourceCard();
                Snapshot.CardV v = src == null ? new Snapshot.CardV() : card(src, viewer);
                String text = si.getText();
                if (text != null && !text.isEmpty()) v.stackText = text;
                if (v.name == null || v.name.isEmpty()) v.name = v.stackText;
                readTargets(si, order, v);
                topFirst.add(v);
            }
            for (int i = topFirst.size() - 1; i >= 0; i--) s.stack.add(topFirst.get(i));
        } catch (Throwable t) {
            // the stack just does not show
        }
    }

    /**
     * One card, as this viewer is allowed to see it.
     *
     * A card that may not be shown - face down, or hidden from this viewer for any other reason -
     * gets nothing identifying at all: no name, no type line, no text, no mana cost. The renderer
     * cannot leak what it was never given, and CardArt keys its picture on the name, so a blank
     * name also stops the real artwork being fetched.
     */
    private static Snapshot.CardV card(CardView cv, PlayerView viewer) {
        Snapshot.CardV v = new Snapshot.CardV();
        try {
            v.id = cv.getId();
            // So a click in the 3D window can find this CardView again. Forge does not hand
            // out a selectable list during ordinary priority, so the registry is the only
            // route from a card the player clicked back to the object Forge wants.
            Gui3D.remember(v.id, cv);
            v.tapped = cv.isTapped();
            if (cv.getController() != null) v.controller = cv.getController().getName();

            boolean visible;
            try {
                visible = !cv.isFaceDown() && cv.canBeShownTo(viewer);
            } catch (Throwable t) {
                visible = !cv.isFaceDown();
            }
            if (!visible) {
                v.faceDown = true;
                v.typeLine = "Creature";
                v.creature = true;
                return v;
            }

            CardView.CardStateView st = cv.getCurrentState();
            v.name = st == null ? cv.getName() : st.getName();
            forge.card.CardTypeView type = st == null ? null : st.getType();
            v.typeLine = type == null ? "" : type.toString();
            if (type != null) {
                v.creature = type.isCreature();
                v.land = type.isLand();
                v.artifact = type.isArtifact();
                v.enchantment = type.isEnchantment();
                v.planeswalker = type.isPlaneswalker();
            }
            if (st != null) {
                v.manaCost = st.getManaCost() == null ? "" : st.getManaCost().toString();
                v.text = st.getOracleText() == null ? "" : st.getOracleText();
                if (v.creature) {
                    v.power = String.valueOf(st.getPower());
                    v.toughness = String.valueOf(st.getToughness());
                }
            }
            v.commander = cv.isCommander();
            v.token = cv.isToken();
            CardView host = cv.getAttachedTo();
            if (host != null) { v.attachedTo = host.getName(); v.attachedToId = host.getId(); }

            try {
                List<com.google.common.collect.Multiset.Entry<forge.game.card.CounterType>> cs =
                    new ArrayList<>(cv.getCounters().entrySet());
                cs.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
                int total = 0;
                for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> e : cs) {
                    if (e.getCount() <= 0) continue;
                    v.counterText.add(e.getElement().getName() + " x" + e.getCount());
                    total += e.getCount();
                }
                v.counters = total;
            } catch (Throwable t) {
                // counters just do not show
            }
        } catch (Throwable t) {
            if (v.name == null || v.name.isEmpty()) v.name = "";
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
