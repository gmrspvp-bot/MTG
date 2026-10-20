// Records a JOINED network game on the joining PC (2026-10-20, owner: "make it so that the human
// players' moves and actions are remembered ... including in a multiplayer game").
//
// PlayHook records every game this PC HOSTS (all players, other humans included). A player who
// joins someone else's game never got a log: the game runs on the host, and the host does not
// send its game log to clients (GameView.gameLog is transient; no protocol message carries it).
// What a client does receive is the host's views - players, zones, stack, combat, phase - kept up
// to date by applyDelta / setGameView. ClientHook wraps the joined game's GUI in this recorder
// (outermost, after the 3D table's wrapper if that is on). Every call is forwarded unchanged;
// after the calls that bring new state, the views are compared with the last look and the
// differences are written as lines in the same format as Forge's own log:
//
//   Turn: Turn 5 (Xenil)                       Phase: Xenil's Main phase, precombat
//   Land: X played Plains (71)                 Add To Stack: X cast Swords to Plowshares targeting [...]
//   Combat: X assigned A (12), B (14) to attack Y.     Combat: Y assigned C (30) to block A (12).
//   Combat: Y didn't block B (14).             Life: Life: Y 40 > 33
//   Zone Change: A (12) was put into Graveyard from Battlefield.
//   Information: BOARD turn 5 (Xenil) | <player> | life .. | hand .. | lands ..   (every turn,
//                every player; hand cards only for us - other hands are hidden from a client)
//
// so scratch\play-summary.ps1 and the other log readers work on it. The file is
// game_<time>_joined.log in the play-log folder, written when the game ends (or as
// _joined_unfinished when Forge closes mid-game). Built from snapshots, so anything that came and
// went between two updates (a spell cast and countered inside one update) can be missing; the
// file says so in its first Information line. -Dforge.clientrecord=false turns it off.

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gui.interfaces.IGuiGame;

public final class ClientRecorder implements InvocationHandler {

    /** Calls after which the host's views may have changed. */
    private static final Set<String> STATE_CALLS = new HashSet<>(Arrays.asList(
        "setGameView", "applyDelta", "updatePhase", "updateTurn", "updateStack", "updateZones",
        "updateCards", "updateCardsNetSafe", "updateLives", "showCombat", "openView",
        "updateButtons", "showPromptMessage", "finishGame", "afterGameEnd"));

    public static IGuiGame wrap(IGuiGame inner) {
        ClientRecorder r = new ClientRecorder(inner);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> r.write("unfinished (Forge closed mid-game)")));
        System.out.println("CLIENTRECORDER: recording the joined game on this PC");
        return (IGuiGame) Proxy.newProxyInstance(ClientRecorder.class.getClassLoader(),
            new Class<?>[] { IGuiGame.class }, r);
    }

    private final IGuiGame inner;
    private Integer meId;                       // our player's id (openView / setOriginalGameController)

    // ---- one game's record; reset() starts the next one
    private final List<String> lines = new ArrayList<>();
    private final Map<Integer, String> header = new LinkedHashMap<>();  // player id -> "Player: ..." line
    private boolean written;
    private boolean started;                    // first look done (the baseline)
    private int turn = -1;
    private PhaseType phase;
    private final Map<Integer, CardView> battlefield = new HashMap<>();   // id -> view at last look
    private Set<Integer> myHand = new HashSet<>();
    private final Set<Integer> stackSeen = new HashSet<>();
    private final Set<Integer> cast = new HashSet<>();                    // cards seen as a stack source
    private final Map<Integer, Integer> life = new HashMap<>();
    private final Set<Integer> playedLandThisTurn = new HashSet<>();      // player ids
    private boolean attacksLogged, blocksLogged;

    private ClientRecorder(IGuiGame inner) { this.inner = inner; }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        String name = m.getName();
        if (m.getDeclaringClass() == Object.class) {
            if (name.equals("equals")) return proxy == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("toString")) return "ClientRecorder(" + inner + ")";
        }
        try {
            if (name.equals("setOriginalGameController") && args != null && args[0] instanceof PlayerView) meId = ((PlayerView) args[0]).getId();
            if (name.equals("openView") && args != null && args[0] instanceof Collection && meId == null) {
                for (Object o : (Collection<?>) args[0]) { if (o instanceof PlayerView) { meId = ((PlayerView) o).getId(); break; } }
            }
        } catch (Throwable t) { /* never break the game */ }
        Object result;
        try {
            result = m.invoke(inner, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        if (STATE_CALLS.contains(name)) {
            try {
                observe(name.equals("finishGame") || name.equals("afterGameEnd"));
            } catch (Throwable t) {
                System.out.println("CLIENTRECORDER: " + t);
            }
        }
        return result;
    }

    private synchronized void observe(boolean gameEnded) {
        GameView gv = inner.getGameView();
        if (gv == null || gv.getPlayers() == null || gv.getPlayers().isEmpty()) return;
        int t = gv.getTurn();
        if (started && t < turn) {                 // a new game (rematch) on the same window
            write("unfinished (a new game started)");
            reset();
        }
        if (!started) {
            if (t < 1) return;                     // still mulligans: wait for turn 1
            begin(gv);
        }
        if (written) return;

        PlayerView active = gv.getPlayerTurn();
        if (t != turn && active != null) {
            turn = t;
            phase = null;
            playedLandThisTurn.clear();
            attacksLogged = blocksLogged = false;
            add("Turn: Turn " + t + " (" + active.getName() + ")");
            for (PlayerView p : gv.getPlayers()) add("Information: BOARD turn " + t + " (" + active.getName() + ") | " + playerLine(gv, p));
        }
        PhaseType ph = gv.getPhase();
        if (ph != null && ph != phase && active != null) {
            phase = ph;
            add("Phase: " + active.getName() + "'s " + ph.nameForUi);
        }
        stack(gv);
        zones(gv, active);
        combat(gv, ph);
        lives(gv);
        if (gameEnded || gv.isGameOver()) {
            String w = null;
            try { w = gv.getWinningPlayerName(); } catch (Throwable x) { /* none */ }
            write(w == null || w.isEmpty() ? "unknown" : w + " has won");
        }
    }

    private void begin(GameView gv) {
        started = true;
        // Our seat first: play-summary.ps1 takes the first (HUMAN) line as the player to follow.
        List<PlayerView> ps = new ArrayList<>(gv.getPlayers());
        ps.sort(Comparator.comparing(p -> meId != null && p.getId() == meId ? 0 : 1));
        for (PlayerView p : ps) {
            StringBuilder cmd = new StringBuilder();
            try { for (CardView c : p.getCommanders()) { if (cmd.length() > 0) cmd.append(" + "); cmd.append(c.getName()); } } catch (Throwable x) { /* none */ }
            boolean mine = meId != null && p.getId() == meId;
            String who = mine || !p.isAI() ? "HUMAN" : "AI";
            header.put(p.getId(), "Player: " + p.getName() + " (" + who + ") deck=?"
                + (cmd.length() > 0 ? " (commander " + cmd + ")" : "")
                + (mine ? " [this PC]" : ""));
            life.put(p.getId(), p.getLife());
        }
        lines.add("Information: RECORDED ON A JOINED CLIENT - rebuilt from the host's views, so damage, "
            + "mulligan and resolve lines are missing and other players' hands are hidden.");
        // Baseline: what is already there is not news.
        for (PlayerView p : gv.getPlayers()) {
            for (CardView c : cards(p, ZoneType.Battlefield)) battlefield.put(c.getId(), c);
        }
        myHand = ids(me(gv), ZoneType.Hand);
        for (StackItemView s : gv.getStack()) stackSeen.add(s.getId());
    }

    private void reset() {
        lines.clear(); header.clear(); battlefield.clear(); myHand.clear(); stackSeen.clear(); cast.clear(); life.clear();
        playedLandThisTurn.clear();
        written = false; started = false; turn = -1; phase = null;
    }

    // ---- what changed

    private void stack(GameView gv) {
        Set<Integer> now = new HashSet<>();
        for (StackItemView s : gv.getStack()) {
            now.add(s.getId());
            if (!stackSeen.add(s.getId())) continue;
            PlayerView who = s.getActivatingPlayer();
            CardView src = s.getSourceCard();
            if (src != null) cast.add(src.getId());
            String verb = s.isTrigger() ? "triggered" : s.isAbility() ? "activated" : "cast";
            StringBuilder l = new StringBuilder("Add To Stack: ").append(who == null ? "?" : who.getName())
                .append(' ').append(verb).append(' ').append(src == null ? s.getText() : src.getName());
            List<String> targets = new ArrayList<>();
            if (s.getTargetCards() != null) for (CardView c : s.getTargetCards()) targets.add(c.getName() + " (" + c.getId() + ")");
            if (s.getTargetPlayers() != null) for (PlayerView p : s.getTargetPlayers()) targets.add(p.getName());
            if (!targets.isEmpty()) l.append(" targeting ").append(targets);
            add(l.toString());
        }
        stackSeen.retainAll(now);
    }

    private void zones(GameView gv, PlayerView active) {
        Map<Integer, CardView> now = new HashMap<>();
        for (PlayerView p : gv.getPlayers()) for (CardView c : cards(p, ZoneType.Battlefield)) now.put(c.getId(), c);
        for (Map.Entry<Integer, CardView> e : now.entrySet()) {
            if (battlefield.containsKey(e.getKey())) continue;
            CardView c = e.getValue();
            PlayerView ctl = c.getController();
            boolean land = c.getCurrentState() != null && c.getCurrentState().isLand();
            // A land played from hand: one per player per turn, on their own turn, with nothing
            // on the stack. Others (fetched, put onto the battlefield) are zone changes.
            if (land && ctl != null && active != null && ctl.getId() == active.getId()
                    && gv.getStack().isEmpty() && playedLandThisTurn.add(ctl.getId())) {
                add("Land: " + ctl.getName() + " played " + c.getName() + " (" + c.getId() + ")");
            } else {
                add("Zone Change: " + c.getName() + " (" + c.getId() + ") was put onto the Battlefield"
                    + (ctl == null ? "" : " under " + ctl.getName()) + ".");
            }
        }
        for (Map.Entry<Integer, CardView> e : battlefield.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            CardView c = e.getValue();
            ZoneType z = null;
            try { z = c.getZone(); } catch (Throwable x) { /* gone */ }
            String to = z == null || z == ZoneType.Battlefield ? (c.isToken() ? "nowhere (token)" : "another zone") : z.name();
            add("Zone Change: " + c.getName() + " (" + c.getId() + ") was put into " + to + " from Battlefield.");
        }
        battlefield.clear();
        battlefield.putAll(now);
        PlayerView me = me(gv);
        if (me != null) {
            Set<Integer> hand = ids(me, ZoneType.Hand);
            for (CardView c : cards(me, ZoneType.Graveyard)) {
                if (myHand.contains(c.getId()) && !hand.contains(c.getId()) && !cast.contains(c.getId())) add("Discard: " + me.getName() + " discards " + c.getName() + " (" + c.getId() + ").");
            }
            myHand = hand;
        }
    }

    private void combat(GameView gv, PhaseType ph) {
        CombatView cv = gv.getCombat();
        if (cv == null || ph == null) return;
        PlayerView active = gv.getPlayerTurn();
        // Attackers are final once declare attackers is over (during it, the view can show a
        // declaration still being made); blocks likewise after declare blockers.
        if (!attacksLogged && ph.isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            attacksLogged = true;
            for (GameEntityView d : cv.getDefenders()) {
                List<String> as = new ArrayList<>();
                for (CardView a : cv.getAttackersOf(d)) as.add(a.getName() + " (" + a.getId() + ")");
                if (!as.isEmpty()) add("Combat: " + (active == null ? "?" : active.getName()) + " assigned " + String.join(", ", as) + " to attack " + d.getName() + ".");
            }
        }
        if (!blocksLogged && ph.isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            blocksLogged = true;
            for (CardView a : cv.getAttackers()) {
                GameEntityView d = cv.getDefender(a);
                String defender = d instanceof PlayerView ? d.getName()
                    : d instanceof CardView && ((CardView) d).getController() != null ? ((CardView) d).getController().getName() : "?";
                List<CardView> bs = cv.getBlockers(a) == null ? Collections.emptyList() : new ArrayList<>(cv.getBlockers(a));
                if (bs.isEmpty()) { add("Combat: " + defender + " didn't block " + a.getName() + " (" + a.getId() + ")."); continue; }
                List<String> names = new ArrayList<>();
                for (CardView b : bs) names.add(b.getName() + " (" + b.getId() + ")");
                String blocker = bs.get(0).getController() == null ? defender : bs.get(0).getController().getName();
                add("Combat: " + blocker + " assigned " + String.join(", ", names) + " to block " + a.getName() + " (" + a.getId() + ").");
            }
        }
    }

    private void lives(GameView gv) {
        for (PlayerView p : gv.getPlayers()) {
            Integer was = life.put(p.getId(), p.getLife());
            if (was != null && was != p.getLife()) add("Life: Life: " + p.getName() + " " + was + " > " + p.getLife());
        }
    }

    // ---- BOARD lines (BoardRecorder's format, from views)

    private String playerLine(GameView gv, PlayerView p) {
        if (p.getHasLost()) return p.getName() + " | out";
        int lands = 0, untappedLands = 0, untappedCreatures = 0;
        List<CardView> creatures = new ArrayList<>();
        Map<Integer, List<String>> gear = new HashMap<>();
        for (CardView c : cards(p, ZoneType.Battlefield)) {
            CardView on = c.getAttachedTo();
            if (on != null) gear.computeIfAbsent(on.getId(), k -> new ArrayList<>()).add(c.getName());
        }
        for (CardView c : cards(p, ZoneType.Battlefield)) {
            CardView.CardStateView st = c.getCurrentState();
            if (st == null) continue;
            if (st.isLand()) { lands++; if (!c.isTapped()) untappedLands++; }
            if (st.isCreature()) { creatures.add(c); if (!c.isTapped()) untappedCreatures++; }
        }
        String cmd = "Elsewhere";
        for (CardView c : cards(p, ZoneType.Command)) if (c.isCommander()) cmd = "Command";
        for (PlayerView q : gv.getPlayers()) for (CardView c : cards(q, ZoneType.Battlefield)) {
            if (c.isCommander() && c.getOwner() != null && c.getOwner().getId() == p.getId()) cmd = "Battlefield";
        }
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (CardView c : creatures) {
            StringBuilder s = new StringBuilder(c.getName()).append(' ').append(c.getCurrentState().getPower()).append('/').append(c.getCurrentState().getToughness());
            if (c.isTapped()) s.append('*');
            List<String> g = gear.get(c.getId());
            if (g != null) s.append('[').append(String.join(",", g)).append(']');
            groups.merge(s.toString(), 1, Integer::sum);
        }
        StringBuilder cl = new StringBuilder();
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (cl.length() > 0) cl.append("; ");
            cl.append(e.getKey());
            if (e.getValue() > 1) cl.append(" x").append(e.getValue());
        }
        boolean mine = meId != null && p.getId() == meId;
        StringBuilder hand = new StringBuilder();
        if (mine) {
            for (CardView c : cards(p, ZoneType.Hand)) {
                if (hand.length() > 0) hand.append("; ");
                CardView.CardStateView st = c.getCurrentState();
                hand.append(c.getName()).append('(').append(st != null && st.isLand() ? "land" : String.valueOf(st == null ? 0 : st.getManaCost().getCMC())).append(')');
            }
        } else {
            hand.append("hidden");
        }
        return p.getName() + " | life " + p.getLife() + " | hand " + p.getZoneSize(ZoneType.Hand)
            + " | lands " + lands + " (" + untappedLands + " untapped) | open mana " + untappedLands
            + " | commander " + cmd + " | creatures " + creatures.size() + " (" + untappedCreatures + " untapped): "
            + cl + " | hand cards: " + hand;
    }

    // ---- helpers

    private PlayerView me(GameView gv) {
        if (meId == null) return null;
        for (PlayerView p : gv.getPlayers()) if (p.getId() == meId) return p;
        return null;
    }

    private static List<CardView> cards(PlayerView p, ZoneType z) {
        if (p == null) return Collections.emptyList();
        try {
            Iterable<CardView> it = p.getCards(z);
            if (it == null) return Collections.emptyList();
            List<CardView> out = new ArrayList<>();
            for (CardView c : it) if (c != null) out.add(c);
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();   // a zone being changed by the network thread
        }
    }

    private static Set<Integer> ids(PlayerView p, ZoneType z) {
        Set<Integer> s = new HashSet<>();
        for (CardView c : cards(p, z)) s.add(c.getId());
        return s;
    }

    private void add(String line) {
        lines.add(line);
    }

    synchronized void write(String result) {
        if (written || !started || lines.size() < 2) return;
        written = true;
        try {
            java.io.File dir = new java.io.File(System.getProperty("forge.playlog.dir", "../scratch/play-logs"));
            dir.mkdirs();
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new java.util.Date());
            boolean unfinished = result.startsWith("unfinished");
            java.io.File out = new java.io.File(dir, "game_" + stamp + "_joined" + (unfinished ? "_unfinished" : "") + ".log");
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8))) {
                for (String h : header.values()) w.println(h);
                for (String l : lines) w.println(l);
                w.println();
                w.println("Game Result: " + result);
            }
            System.out.println("CLIENTRECORDER: game log saved to " + out.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("CLIENTRECORDER: could not save the game log (" + t + ")");
        }
    }
}
