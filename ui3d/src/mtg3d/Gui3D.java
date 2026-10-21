package mtg3d;

import forge.game.card.CardView;
import forge.gui.interfaces.IGuiGame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A pass-through {@link IGuiGame} that sits between Forge's engine and Forge's own window.
 *
 * Step 1 of the agreed input plan (see *Agreed plan* in the ui3d README). **It changes no
 * behaviour.** Every one of the hundred-odd interface methods is forwarded to the UI that was
 * already there, so Forge plays exactly as before; a few are additionally *observed* on the way
 * past, to copy the prompt text, the button labels and the selectable cards into the snapshot so
 * the 3D window can show them.
 *
 * Why a reflection Proxy rather than a class implementing the interface: the interface has 105
 * methods and gains more as Forge changes. A proxy forwards whatever exists, so this file does
 * not need editing every time, and - more to the point for step 1 - there is no chance of
 * accidentally implementing a method with the wrong behaviour instead of forwarding it. The
 * trade is that a signature change fails at runtime rather than at compile time, which is what
 * {@link #checkTakenOver} is for.
 *
 * Later steps make selected methods answer from the 3D window instead of forwarding. That
 * decision is made per method, in {@link #TAKEN_OVER}, so exactly one UI ever answers a given
 * question. Nothing is taken over yet.
 */
public final class Gui3D implements InvocationHandler {

    /**
     * Methods the 3D view answers itself instead of forwarding. Empty in step 1, by design.
     *
     * Before adding anything here, read the two conditions in the README's *Agreed plan*: the
     * double-click race must be proved with a probe, and there must be a way out of a blocked
     * game thread. Once a method is in this set Forge never sees the call, so there is nothing to
     * fall back on if the 3D window cannot answer.
     */
    private static final Set<String> TAKEN_OVER = new LinkedHashSet<>(
        java.util.Arrays.asList("confirm", "reveal", "message",
                                "chooseSingleEntityForEffect", "chooseEntitiesForEffect",
                                // 2026-10-21, hooking up the panels: lists of anything (modes,
                                // colours, types, players, cards), numbers, ordering, damage.
                                "one", "oneOrNone", "getChoices", "many",
                                "getInteger", "order", "assignCombatDamage",
                                // 2026-10-20 (owner's list): which ability of a card to activate.
                                "getAbilityToPlay",
                                // 2026-10-21: whether a phase happens for us at all. See
                                // takeSkipPhase - this one decided whole combats were skipped.
                                "isUiSetToSkipPhase",
                                // 2026-10-21: looking into a zone. Mirrored since the 20th,
                                // but Forge's own window still opened on top of the table.
                                "openZones",
                                // 2026-10-21, working through the census: Forge's own generic
                                // dialogs. Each one was a modal box in the other window.
                                "showConfirmDialog", "showOptionDialog", "showInputDialog",
                                // 2026-10-21 (Stoneforge, census "windows of Forge's own"): the
                                // void calls that put something of Forge's on screen. Error box
                                // -> the 3D message panel; the two reminder flashes -> nothing
                                // (the 3D window has its own toasts); revealed cards -> mirrored
                                // like an opened zone instead of Forge's floating zone window.
                                "showErrorDialog", "alertUser", "flashIncorrectAction", "showRevealedCards",
                                // Splitting a number across several things - counters, damage
                                // from something that is not combat.
                                "assignGenericAmount",
                                // One card into an already ordered pile.
                                "insertInList",
                                // Cards to top / bottom of a zone, in order (Stoneforge).
                                "manipulateCardList"));

    private final IGuiGame original;
    private final forge.player.PlayerControllerHuman pch;
    /**
     * A JOINED network game (2026-10-21): the game runs on the host, so there is no local Game,
     * no PlayerControllerHuman and no Input objects here. The snapshot is built from the GameView
     * the host keeps sending (see rebuildSoon), and clicks go to the network game controller the
     * client hands this GUI (setOriginalGameController), which forwards them to the host.
     */
    private final boolean remoteMode;

    private Gui3D(IGuiGame original, forge.player.PlayerControllerHuman pch) {
        this(original, pch, false);
    }

    private Gui3D(IGuiGame original, forge.player.PlayerControllerHuman pch, boolean remoteMode) {
        this.original = original;
        this.pch = pch;
        this.remoteMode = remoteMode;
        instance = this;
    }

    /** The wrapper in use (the latest made), for calls that must reach Forge's window later. */
    private static volatile Gui3D instance;

    /** The joined game's controller (a NetGameController): where 3D clicks go. Null in a local game. */
    private static volatile forge.interfaces.IGameController remote;
    /** The joined game's GUI wrapper, so the snapshot can be rebuilt from its GameView. */
    private static volatile Gui3D remoteGui;
    /** Our seat in the joined game, from openView / setOriginalGameController. */
    private static volatile forge.game.player.PlayerView remoteViewer;
    private static volatile String remoteDay = "";
    private static final java.util.concurrent.atomic.AtomicBoolean rebuildQueued =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Day / night as the host last sent it (updateDayTime), for a joined game's snapshot. */
    static String remoteDayTime() {
        String d = remoteDay == null ? "" : remoteDay.toLowerCase();
        return d.contains("night") ? "NIGHT" : d.contains("day") ? "DAY" : "";
    }

    /**
     * Wraps the GUI Forge made for a joining client (ClientHook, via Bridge.wrapClientGui) and
     * returns the wrapper to use in its place. Forwards everything, exactly like the local wrapper;
     * the 3D window opens when the host sends the game view.
     */
    public static IGuiGame installRemote(IGuiGame clientGui) {
        if ("false".equalsIgnoreCase(System.getProperty("forge.ui3d.input", "true"))) return clientGui;
        if (clientGui == null) return clientGui;
        // Only refuse our own wrapper (no double wrapping) - any other proxy is just a GUI to wrap.
        if (Proxy.isProxyClass(clientGui.getClass()) && Proxy.getInvocationHandler(clientGui) instanceof Gui3D) return clientGui;
        Gui3D g = new Gui3D(clientGui, null, true);
        remoteGui = g;
        remote = null;
        remoteViewer = null;
        checkTakenOver();
        System.out.println("UI3D: joined a network game - the 3D table follows the host's game");
        return (IGuiGame) Proxy.newProxyInstance(Gui3D.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, g);
    }

    /**
     * Rebuild the joined game's snapshot - soon, once, on the EDT. Every update from the host lands
     * here; coalescing them means a burst of fifty updates is one rebuild, and the EDT is where
     * Forge's own window reads these same views, so reading them cannot race the network thread.
     */
    private void rebuildSoon() {
        if (!rebuildQueued.compareAndSet(false, true)) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            rebuildQueued.set(false);
            try {
                forge.game.GameView gv = original.getGameView();
                if (gv == null) return;
                clearPhaseSkipsRemote(gv.getPlayerTurn());
                PROMPT.inputName = inferInput(gv);
                Bridge.publish(StateReader.readView(gv, remoteViewer));
            } catch (Throwable t) {
                Soak.caught("Gui3D.rebuildSoon", t);
            }
        });
    }

    /**
     * In a joined game there is no Input object to name, so the board modes (targets, attack,
     * block, pay, mulligan) are switched from Forge's prompt text and the phase instead. English
     * prompts only; if it guesses wrong the worst case is a mode that does not appear - clicks still
     * go to the host, which judges them, and Forge's own window still works.
     */
    static String inferInput(forge.game.GameView gv) {
        String m = PROMPT.message == null ? "" : PROMPT.message.toLowerCase();
        String phase = gv == null || gv.getPhase() == null ? "" : gv.getPhase().name();
        if (m.contains("bottom of your library") && m.contains("select")) return "InputLondonMulligan";
        if (m.contains("mulligan") || m.contains("keep your hand") || m.contains("keep this hand")) return "InputConfirmMulligan";
        if (m.contains("declare attackers") || (phase.equals("COMBAT_DECLARE_ATTACKERS") && m.contains("attack"))) return "InputAttack";
        if (m.contains("declare blockers") || (phase.equals("COMBAT_DECLARE_BLOCKERS") && m.contains("block"))) return "InputBlock";
        if (m.contains("pay mana cost") || (m.startsWith("pay") && m.contains("mana"))) return "InputPayManaOfCostPayment";
        if (m.contains("target")) return "InputSelectTargets";
        return "InputPassPriority";
    }

    /**
     * Run a 3D click for a joined game on the EDT, against the network controller - dropped if a
     * newer prompt has arrived since it was pressed (the token is replaced on every prompt), the
     * same safety ClickGuard gives a local game.
     */
    private static boolean remoteClick(final java.util.function.Consumer<forge.interfaces.IGameController> click) {
        final forge.interfaces.IGameController r = remote;
        if (r == null) return false;
        final Object seen = PROMPT.token;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (seen != null && PROMPT.token != seen) { ClickGuard.dropped++; return; }
            try { click.accept(r); } catch (Throwable t) { System.out.println("UI3D: click to the host failed (" + t + ")"); }
        });
        return true;
    }

    /** The local human's controller, for sending guarded clicks back. Null until installed. */
    private static volatile forge.player.PlayerControllerHuman controller;
    /** The cards Forge says may be clicked, by id. Replaced wholesale, never mutated in place. */
    private static volatile java.util.Map<Integer, CardView> selectableViews =
        new java.util.LinkedHashMap<>();
    /**
     * Every CardView the snapshot has seen, by id, filled by StateReader on the game thread.
     *
     * Needed because Forge does NOT call setSelectables during ordinary priority - it outlines
     * playable cards in its own window by another route, and simply lets you click anything,
     * leaving the controller to judge. So a click has to be sendable for any card on the table,
     * not only for ones that arrived through setSelectables.
     */
    private static final java.util.Map<Integer, CardView> known =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Called by StateReader for each card it reads, so a click can find the CardView later. */
    public static void remember(int id, CardView cv) {
        if (cv != null) known.put(id, cv);
    }

    /** Seats in the order StateReader built them, so a seat index maps back to a PlayerView. */
    private static volatile java.util.List<forge.game.player.PlayerView> seats =
        new java.util.ArrayList<>();

    /** Called by StateReader once per snapshot, in the same order as Snapshot.seats. */
    public static void rememberSeats(java.util.List<forge.game.player.PlayerView> order) {
        if (order != null) seats = new java.util.ArrayList<>(order);
    }

    /**
     * Click a player - choose who starts, or target a seat.
     *
     * The very first thing a game asks is for the coin-toss winner to pick a starting player, and
     * until now there was nothing in the 3D window to click, so every game began by going back to
     * Forge's. Guarded like every other click: the token from when the prompt was drawn, re-checked
     * on the EDT, because the decision can pass while the click is queued.
     */
    public static boolean clickPlayer(int seatIndex) {
        if (remote != null) {
            java.util.List<forge.game.player.PlayerView> o = seats;
            if (seatIndex < 0 || seatIndex >= o.size() || o.get(seatIndex) == null) return false;
            final forge.game.player.PlayerView who = o.get(seatIndex);
            return remoteClick(r -> r.selectPlayer(who, null));
        }
        final forge.player.PlayerControllerHuman c = controller;
        java.util.List<forge.game.player.PlayerView> order = seats;
        if (c == null || seatIndex < 0 || seatIndex >= order.size()) return false;
        final forge.game.player.PlayerView pv = order.get(seatIndex);
        final Object seen = PROMPT.token;
        if (pv == null) return false;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                if (seen != null && ClickGuard.token(c) != seen) {
                    ClickGuard.dropped++;
                    return;
                }
                c.selectPlayer(pv, null);
            } catch (Throwable t) {
                System.out.println("UI3D: player click failed (" + t + ")");
            }
        });
        return true;
    }

    /**
     * A yes/no the 3D window is answering itself. Null when none is open.
     *
     * The first thing Forge no longer sees, so everything the safeguards exist for applies here:
     * the game thread is blocked inside confirm() with no dialog anywhere else to click, and only
     * Handoff can end that if this window cannot.
     */
    public static final class Ask {
        public final String question;
        public final String cardName;
        public final String yesLabel, noLabel;
        final Handoff<Boolean> handoff;
        Ask(String question, String cardName, String yes, String no, Handoff<Boolean> h) {
            this.question = question;
            this.cardName = cardName;
            this.yesLabel = yes;
            this.noLabel = no;
            this.handoff = h;
        }
    }

    private static volatile Ask pending;

    /**
     * A list Forge wants shown - revealed cards, or the "AI can't play these cards well" deck
     * warning at the start of a game. Forge draws it as a modal box in its own window, which left
     * the 3D player staring at a table that had silently stopped.
     */
    public static final class Reveal {
        public final String title;
        public final java.util.List<Snapshot.CardV> items;
        final Handoff<Boolean> handoff;
        Reveal(String title, java.util.List<Snapshot.CardV> items, Handoff<Boolean> h) {
            this.title = title;
            this.items = items;
            this.handoff = h;
        }
    }

    private static volatile Reveal revealing;

    /**
     * A zone Forge has opened for us to look at - another player's hand, under a Duress or a
     * Thoughtseize. Separate from Reveal because nothing is blocked on it: Forge's own window
     * still shows the zone, so this is a mirror and dismissing it answers nothing.
     */
    public static final class Peek {
        public final String title;
        public final java.util.List<Snapshot.CardV> items;
        /** Made from setSelectables (choosable cards not on the table), not from openZones. */
        public boolean fromSelectables;
        Peek(String title, java.util.List<Snapshot.CardV> items) {
            this.title = title;
            this.items = items;
        }
    }

    /**
     * Owner's game, 2026-10-21: Path to Exile's "search your library for a basic land" never
     * appeared in the 3D window and had to be done in Forge's. With Forge's "select from card
     * displays" preference on (it is), a search is an Input: Forge marks the cards you may take
     * (setSelectables) and CMatchUI opens floating windows for whatever zones hold them
     * (getZonesHolding / tempShowZones) - NOT openZones, so nothing here mirrored it, and the
     * cards were selectable but nowhere on the table to click.
     *
     * Now: when Forge offers cards that are not on the table (library, graveyard, exile, another
     * player's hand), they open in the search panel as a pick (Table3D: a pick sends Forge the
     * card's click, exactly as clicking it in Forge's floating window does; SPACE when Forge then
     * wants OK). Forge's floating window is held back while the 3D window is drawing; H shows it
     * after all (releaseSelectables). Returns true when Forge's window should not get the call.
     */
    private boolean mirrorOffTable(java.util.Collection<CardView> views, Object[] args) {
        try {
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            String zoneName = null;
            forge.game.player.PlayerView me = pch == null ? null : pch.getLocalPlayerView();
            for (CardView cv : views) {
                forge.game.zone.ZoneType z = cv.getZone();
                boolean offTable = z == forge.game.zone.ZoneType.Library || z == forge.game.zone.ZoneType.Graveyard
                    || z == forge.game.zone.ZoneType.Exile || z == forge.game.zone.ZoneType.Sideboard
                    || (z == forge.game.zone.ZoneType.Hand && me != null && cv.getOwner() != null && cv.getOwner().getId() != me.getId());
                if (!offTable) continue;
                if (zoneName == null) zoneName = z.toString().toLowerCase();
                Snapshot.CardV v = describe(cv, cv.getName());
                v.id = cv.getId();
                items.add(v);
            }
            if (items.isEmpty()) return false;
            String what = PROMPT.message == null || PROMPT.message.trim().isEmpty() ? "Choose" : PROMPT.message.trim();
            Peek p = new Peek(what + "  (" + zoneName + ")", items);
            p.fromSelectables = true;
            peeking = p;
            if (!Handoff.windowResponsive()) return false;     // nothing drawing it: Forge's window shows them
            heldSelectables = args;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The setSelectables call held back from Forge's window while the 3D panel shows it. */
    private static volatile Object[] heldSelectables;

    /** H while a search is shown in 3D: let Forge's window show it too, after all. */
    public static void releaseSelectables() {
        Gui3D g = instance;
        Object[] held = heldSelectables;
        heldSelectables = null;
        if (g == null || held == null) return;
        ForgeWindow.restore("the search, shown in Forge's window as well");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { g.original.setSelectables((Iterable<CardView>) held[0], (Integer) held[1], (Integer) held[2]); }
            catch (Throwable t) { System.out.println("UI3D: could not show the search in Forge's window (" + t + ")"); }
        });
    }

    private static volatile Peek peeking;

    public static Peek pendingPeek() {
        return peeking;
    }

    public static void dismissPeek() {
        peeking = null;
    }

    /**
     * Build the list for an opened zone.
     *
     * This is the case canBeShownTo exists for: a hand is hidden, Forge has just decided we may
     * see this one, and the engine's own answer is the only correct way to know that. The
     * battlefield rule from the last fix does not apply here and must not be reused.
     */
    private void peekZone(Object[] args) {
        try {
            if (args == null || args.length < 2 || !(args[0] instanceof forge.game.player.PlayerView)) return;
            if (!(args[1] instanceof Iterable)) return;
            // openZones(viewer, zones, players): Forge's own window (CMatchUI.openZones) shows the
            // zones of every player that is a KEY of the map in args[2]; args[0] is the player
            // doing the looking. Reading args[0] as the owner showed our own zone instead of
            // "Rosa's hand" (Xenil's Claude's finding, 2026-10-21). The map can be empty in other
            // callers; then args[0] is the best guess.
            java.util.List<forge.game.player.PlayerView> owners = new ArrayList<>();
            if (args.length > 2 && args[2] instanceof java.util.Map) {
                for (Object k : ((java.util.Map<?, ?>) args[2]).keySet()) {
                    if (k instanceof forge.game.player.PlayerView) owners.add((forge.game.player.PlayerView) k);
                }
            }
            if (owners.isEmpty()) owners.add((forge.game.player.PlayerView) args[0]);
            forge.game.player.PlayerView viewer = pch == null ? (forge.game.player.PlayerView) args[0] : pch.getLocalPlayerView();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            StringBuilder zones = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (forge.game.player.PlayerView owner : owners) {
            if (names.length() > 0) names.append(", ");
            names.append(owner.getName());
            for (Object z : (Iterable<?>) args[1]) {
                if (!(z instanceof forge.game.zone.ZoneType)) continue;
                forge.game.zone.ZoneType zone = (forge.game.zone.ZoneType) z;
                if (zones.indexOf(zone.toString()) < 0) {
                    if (zones.length() > 0) zones.append(", ");
                    zones.append(zone);
                }
                Iterable<CardView> cards = owner.getCards(zone);
                if (cards == null) continue;
                for (CardView cv : cards) {
                    Snapshot.CardV v = new Snapshot.CardV();
                    v.id = cv.getId();
                    // So a pick in the 3D panel can be sent as a click (clickCard looks it up).
                    remember(v.id, cv);
                    boolean show;
                    try { show = cv.canBeShownTo(viewer); } catch (Throwable e) { show = false; }
                    if (show) {
                        CardView.CardStateView st = cv.getCurrentState();
                        v.name = st == null ? cv.getName() : st.getName();
                        if (st != null && st.getType() != null) v.typeLine = st.getType().toString();
                        if (st != null && st.getManaCost() != null) v.manaCost = st.getManaCost().toString();
                    } else {
                        v.faceDown = true;
                        v.typeLine = "Creature";
                        v.creature = true;
                    }
                    items.add(v);
                }
            }
            }
            if (!items.isEmpty()) {
                peeking = new Peek(names + " - " + zones, items);
            }
        } catch (Throwable t) {
            // the zone just does not mirror; Forge's own window still shows it
        }
    }

    /** The list the 3D window should be showing, or null. */
    public static Reveal pendingReveal() {
        return revealing;
    }

    /** Dismiss it from the render thread. */
    public static void dismissReveal() {
        Reveal r = revealing;
        if (r != null) r.handoff.answer(Boolean.TRUE);
    }

    /** The question the 3D window should be showing, or null. */
    public static Ask pendingAsk() {
        return pending;
    }

    /**
     * A yes/no from our own hooks (scripts\tools\ConfirmPassHook: "Pass anyway?", the counterspell
     * reminder, "Skip combat anyway?"), asked in the 3D window instead of a Swing box on Forge's
     * window - which may be minimised (ForgeWindow), where a box would not even show. Called by
     * reflection, so scripts\tools needs nothing from here at compile time.
     *
     * @return the answer, or null when the 3D window cannot ask (not running, not drawing, handed
     *         back): the caller then asks with its own box, as before.
     */
    public static Boolean askYesNo(String question, String yes, String no) {
        if (!active() || !Handoff.windowResponsive() || pending != null) return null;
        Handoff<Boolean> h = Handoff.start("ask: " + question);
        pending = new Ask(question, "", yes, no, h);
        try {
            return h.awaitOr(() -> null);
        } finally {
            pending = null;
        }
    }

    /** Answer it from the render thread. Ignored if it has already been settled by a handoff. */
    public static void answerAsk(boolean yes) {
        Ask a = pending;
        if (a == null) return;
        a.handoff.answer(yes);
    }

    /** What the engine last asked of us, mirrored for the 3D window. */
    public static final class Prompt {
        public volatile String message = "";
        public volatile String okLabel = "OK";
        public volatile String cancelLabel = "Cancel";
        public volatile boolean okEnabled, cancelEnabled, allowEnter;
        /** Card ids Forge says may be clicked right now; empty when nothing is selectable. */
        public volatile List<Integer> selectable = new ArrayList<>();
        /**
         * The decision this prompt belongs to, captured when the prompt was drawn. A click from
         * the 3D window carries it, and ClickGuard drops the click if the game has moved on - the
         * race the probe proved is real.
         */
        public volatile Object token;
        /**
         * The last dialog Forge put up that the 3D window has no answer for - its own message and
         * reveal boxes. Mirrored, not taken over: the 3D side says one is waiting and where, which
         * is safe, rather than blocking the game on a list whose element type it cannot know.
         */
        public volatile String forgeDialog = "";
        public volatile long forgeDialogAt;
        /**
         * The class name of the Input the prompt belongs to - "InputAttack", "InputBlock",
         * "InputSelectTargets", "InputPayManaOfCostPayment", "InputConfirmMulligan"... The 3D window
         * switches its board modes (drag to attack, glow targets, pay with lands) on this.
         */
        public volatile String inputName = "";
    }

    public static final Prompt PROMPT = new Prompt();

    /**
     * Wraps the GUI of the local human, if there is one. Returns true if a wrapper was installed.
     *
     * -Dforge.ui3d.input=false skips this entirely and gives back the read-only view, which is
     * the off switch the plan requires at every step.
     */
    public static boolean install(forge.game.Game game) {
        if ("false".equalsIgnoreCase(System.getProperty("forge.ui3d.input", "true"))) {
            System.out.println("UI3D: input wrapper disabled (-Dforge.ui3d.input=false)");
            return false;
        }
        try {
            for (forge.game.player.Player p : game.getRegisteredPlayers()) {
                Object c = p.getController();
                if (!(c instanceof forge.player.PlayerControllerHuman)) continue;
                forge.player.PlayerControllerHuman pch = (forge.player.PlayerControllerHuman) c;
                if (!pch.isGuiPlayer()) continue;           // only ever our own seat
                IGuiGame current = pch.getGui();
                if (current == null || Proxy.isProxyClass(current.getClass())) return false;   // already wrapped
                IGuiGame wrapped = (IGuiGame) Proxy.newProxyInstance(
                    Gui3D.class.getClassLoader(),
                    new Class<?>[] { IGuiGame.class },
                    new Gui3D(current, pch));
                pch.setGui(wrapped);
                controller = pch;
                remote = null;                 // a local game: forget any earlier joined one
                remoteGui = null;
                ClickGuard.watch(pch);
                checkTakenOver();
                System.out.println("UI3D: input wrapper installed (forwarding everything)");
                clearPhaseSkips();
                javax.swing.SwingUtilities.invokeLater(Gui3D::unhookForgeKeys);
                // Say which mode we are in. Without this, a run where strict found nothing and a
                // run where the flag never arrived look identical in the log - both are silent.
                System.out.println(STRICT
                    ? "UI3D: strict mode ON - every decision Forge answers will be named here"
                    : "UI3D: strict mode off - run \"play3d -Strict\" to list what Forge still answers");
                return true;
            }
        } catch (Throwable t) {
            System.out.println("UI3D: could not install the input wrapper, carrying on read-only (" + t + ")");
        }
        return false;
    }

    /**
     * Fails loudly at startup if a method we claim to have taken over no longer exists on the
     * interface. A reflection proxy adapts silently to Forge changing, which is the point of
     * using one - but it also means a renamed method would simply stop being intercepted and the
     * 3D window would quietly stop answering. Better to know when the game starts.
     */
    private static void checkTakenOver() {
        if (TAKEN_OVER.isEmpty()) return;
        Set<String> present = new LinkedHashSet<>();
        for (Method m : IGuiGame.class.getMethods()) present.add(m.getName());
        for (String name : TAKEN_OVER) {
            if (!present.contains(name)) {
                System.out.println("UI3D: WARNING - taken-over method '" + name
                    + "' is not on IGuiGame any more; it will no longer be intercepted");
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Observe a few calls on the way past. This never changes what is forwarded, and a
        // failure here must not break the game, so it is swallowed separately.
        try {
            disarmFocusTheft();
            observe(method.getName(), args);
        } catch (Throwable ignored) {
            // the mirror is cosmetic
        }
        // A search shown in the 3D panel (mirrorOffTable): Forge's floating zone window is held
        // back - H releases it (releaseSelectables).
        if ("setSelectables".equals(method.getName()) && args != null && heldSelectables == args) return null;
        // Forge's own window is given a debounced controller rather than the real one. Its
        // clicks carry no token, so the probe's other ordering - 3D key first, then Forge's OK -
        // can only be closed on the way in. Everything that is not a click passes straight
        // through (see ClickGuard.forgeWindow).
        if ("setGameController".equals(method.getName()) && args != null && args.length == 2
                && args[1] != null && pch != null) {
            try {
                args = new Object[] { args[0], ClickGuard.forgeWindow(pch) };
            } catch (Throwable ignored) {
                // leave the original controller in place rather than break the game
            }
        }
        // Record anything Forge answers that the 3D window should eventually own. A decision
        // quietly handled by the other window is invisible from here - it only ever surfaced as
        // "it pulled me back again" - so counting them turns the remaining gaps into a list.
        noteFallback(method);
        if (TAKEN_OVER.contains(method.getName())) {
            Object answered = take(method, args);
            if (answered != NOT_HANDLED) return answered;
            // Forwarded after all (a shape the 3D side does not take): Forge asks in its own
            // window, so that window has to be on screen - and it counts in the tally, which
            // otherwise said "the 3D window handled everything" over a replacement-effect choice
            // that had in fact gone to Forge (owner's game, 2026-10-21).
            if (showsForgeWindow(method)) {
                ForgeWindow.restore(method.getName() + " is Forge's to ask");
                countFallback(method.getName() + " (shape not taken)");
            }
        }
        try {
            Object out = method.invoke(original, args);
            // A joined game: anything the host sends may have changed the board, so rebuild the
            // snapshot (coalesced). Plain getters change nothing and are skipped.
            if (remoteMode) {
                String n = method.getName();
                if (!(n.startsWith("get") || n.startsWith("is") || n.startsWith("has") || n.startsWith("can")
                        || n.startsWith("should"))) rebuildSoon();
            }
            return out;
        } catch (InvocationTargetException e) {
            throw e.getCause();          // let the engine see the real exception, not the wrapper's
        }
    }

    /**
     * Pass priority / confirm, sent from the 3D window.
     *
     * Carries the token captured when the prompt was drawn, so if the game has moved on to the
     * next decision in the meantime the click is dropped rather than silently answering something
     * the player never saw. Returns false when there is nothing to confirm, so the caller can
     * leave the key doing nothing instead of guessing.
     */
    public static boolean pressOk() {
        if (remote != null) return PROMPT.okEnabled && remoteClick(r -> r.selectButtonOk());
        forge.player.PlayerControllerHuman c = controller;
        if (c == null || !PROMPT.okEnabled || PROMPT.token == null) return false;
        ClickGuard.ok(c, PROMPT.token);
        return true;
    }

    /**
     * OK queued behind clicks just sent, for multi-pick inputs (London mulligan's bottom cards):
     * Forge only enables OK once enough cards are chosen, which has not happened yet when the
     * picks are queued, so the enabled check is skipped. Still guarded by the prompt's token.
     */
    public static boolean pressOkQueued() {
        if (remote != null) return remoteClick(r -> r.selectButtonOk());
        forge.player.PlayerControllerHuman c = controller;
        if (c == null || PROMPT.token == null) return false;
        ClickGuard.ok(c, PROMPT.token);
        return true;
    }

    /** Cancel, with the same guard. */
    public static boolean pressCancel() {
        if (remote != null) return PROMPT.cancelEnabled && remoteClick(r -> r.selectButtonCancel());
        forge.player.PlayerControllerHuman c = controller;
        if (c == null || !PROMPT.cancelEnabled || PROMPT.token == null) return false;
        ClickGuard.cancel(c, PROMPT.token);
        return true;
    }

    /**
     * Click a card from the 3D window - cast it, play it, activate it, or pick it as a target.
     *
     * Only cards Forge has said are selectable right now can be sent, so this can never offer a
     * play the rules do not allow; the 3D side is choosing among Forge's own options rather than
     * deciding anything itself. Guarded by the same token as the buttons, so a click aimed at a
     * decision that has since passed is dropped rather than applied to the next one.
     *
     * @return false when that card is not currently selectable, so the caller can ignore the click
     */
    public static boolean clickCard(int cardId) {
        if (remote != null) {
            CardView rc = selectableViews.get(cardId);
            if (rc == null) rc = known.get(cardId);
            final CardView target = rc;
            return target != null && remoteClick(r -> r.selectCard(target, java.util.Collections.emptyList(), null));
        }
        final forge.player.PlayerControllerHuman c = controller;
        CardView found = selectableViews.get(cardId);
        if (found == null) found = known.get(cardId);
        final CardView cv = found;
        final Object seen = PROMPT.token;
        if (c == null || cv == null) return false;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                // Re-check on the EDT: the game may have moved on while this was queued, which is
                // precisely the race ClickGuard exists for. Only meaningful when there was a
                // prompt to tie the click to; during ordinary priority there is no token and
                // Forge itself accepts a click on anything.
                if (seen != null && ClickGuard.token(c) != seen) {
                    ClickGuard.dropped++;
                    return;
                }
                // Forge judges legality, exactly as it does for a click in its own window: a card
                // that cannot be played right now is simply refused. The 3D side never decides
                // what is legal, which is what keeps this safe without a rules engine of its own.
                boolean taken = c.selectCard(cv, java.util.Collections.emptyList(), null);
                // Say what Forge was actually waiting on when it said no. "Forge refused the
                // click on Plains" on its own is not diagnosable: on 2026-10-20 the owner
                // clicked a land and a creature nine times during his own main phase, every
                // click was refused, the turn ran out, and nothing in the logs said why. The
                // live input is the answer - an empty one means Forge was not asking this
                // player for anything, whatever the 3D window was showing.
                if (!taken) {
                    Object live = null;
                    try { live = ClickGuard.token(c); } catch (Throwable ignored) { /* best effort */ }
                    System.out.println("UI3D: Forge refused the click on " + cv.getName()
                        + " [input=" + (live == null ? "none" : live.getClass().getSimpleName())
                        + ", prompt=" + (PROMPT.inputName.isEmpty() ? "none" : PROMPT.inputName)
                        + ", ok=" + PROMPT.okEnabled + "]");
                }
            } catch (Throwable t) {
                System.out.println("UI3D: card click failed (" + t + ")");
            }
        });
        return true;
    }

    /**
     * Has Forge explicitly offered this card for selection? Only true during the prompts that use
     * setSelectables ("choose a card to discard"), not during ordinary priority, where Forge
     * offers no such list and any card may be clicked.
     */
    public static boolean isSelectable(int cardId) {
        return selectableViews.containsKey(cardId);
    }

    /**
     * The Forge input waiting right now, by class name, or "none".
     *
     * Read live rather than from the prompt mirror, because the two disagreeing is itself the
     * bug worth catching: the 3D window can be showing a player as holding priority while Forge
     * has no input open for them, and then every click is refused with nothing to explain it.
     */
    public static String liveInputName() {
        try {
            forge.player.PlayerControllerHuman c = controller;
            if (c == null) return remote != null ? "remote" : "none";
            Object live = ClickGuard.token(c);
            return live == null ? "none" : live.getClass().getSimpleName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Is Forge auto-passing for us right now, and until when?
     *
     * Forge's YieldController can be told to pass everything until end of turn or until the
     * stack empties, and PlayerControllerHuman asks IT before it asks the GUI - so while one of
     * these is set, nothing the 3D window answers about skipping phases is even consulted. That
     * is how a turn disappears: the owner pressed one key, the whole turn ran past, and the table
     * showed no sign that anything had been switched on (2026-10-21).
     *
     * "" when nothing is auto-passing; otherwise a phrase for the screen.
     */
    public static String autoPassing() {
        try {
            forge.player.PlayerControllerHuman c = controller;
            if (c == null) return "";
            forge.gamemodes.match.YieldController y = c.getYieldController();
            if (y == null) return "";
            if (y.autoPassUntilEndOfTurn()) return "to end of turn";
            if (y.autoPassUntilStackEmpty()) return "until the stack is empty";
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Switch every phase back ON - in EVERYONE's turn, not just our own.
     *
     * YieldController keeps a set of phases to skip - separate from the auto-pass, and consulted
     * by PlayerControllerHuman BEFORE it asks the GUI. So neither clearing the auto-pass nor
     * answering "never skip" from the 3D window reaches it: the phases were switched off in Forge
     * before we were asked, and one press of SPACE ran the whole turn (the owner, turn 2 on
     * 2026-10-21, unable to play a land in his own main phase).
     *
     * The set is keyed by the TURN PLAYER, which is what this got wrong: it cleared our own key
     * only, so it fixed our own turns and left every opponent's turn as Forge had it. Forge seeds
     * the set from the PHASE_HUMAN_* preferences, where declare attackers was off - which is how
     * Alec was carried past his chance to respond to Derand's attack and dumped straight into
     * declaring blockers (2026-10-21). Clear every key.
     */
    public static void clearPhaseSkips() {
        try {
            forge.player.PlayerControllerHuman c = controller;
            if (c == null) return;
            forge.gamemodes.match.YieldController y = c.getYieldController();
            if (y == null) return;
            int cleared = 0;
            for (forge.game.player.PlayerView who : everySeat(c)) {
                for (forge.game.phase.PhaseType ph : forge.game.phase.PhaseType.values()) {
                    if (!y.isSkippingPhase(who, ph)) continue;
                    y.setSkipPhase(who, ph, false);
                    cleared++;
                }
            }
            if (cleared > 0) {
                System.out.println("UI3D: turned " + cleared + " phase stop(s) back on"
                    + " - Forge had them set to skip");
            }
        } catch (Throwable t) {
            System.out.println("UI3D: could not clear the phase skips (" + t + ")");
        }
    }

    /** Every seat at the table as a view, ours included; empty rather than null on any trouble. */
    private static java.util.List<forge.game.player.PlayerView> everySeat(forge.player.PlayerControllerHuman c) {
        java.util.List<forge.game.player.PlayerView> out = new java.util.ArrayList<>();
        try {
            forge.game.GameView gv = c.getGui() == null ? null : c.getGui().getGameView();
            if (gv != null && gv.getPlayers() != null) {
                for (forge.game.player.PlayerView pv : gv.getPlayers()) if (pv != null) out.add(pv);
            }
        } catch (Throwable t) {
            Soak.caught("Gui3D.everySeat", t);
        }
        if (out.isEmpty()) {
            try {
                forge.game.player.PlayerView me = c.getLocalPlayerView();
                if (me != null) out.add(me);
            } catch (Throwable t) {
                Soak.caught("Gui3D.everySeat", t);
            }
        }
        return out;
    }

    /** Seats we have already asked the host to stop skipping, so each is asked once. */
    private static final java.util.Set<Integer> remoteStopsSent =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    /**
     * The same thing for a JOINED game, where we cannot reach the set at all.
     *
     * On the host, PlayerControllerHuman.isUiSetToSkipPhase asks the GUI, so takeSkipPhase
     * answers and that is the end of it. For a network client it does NOT: it reads the skip set
     * held on the HOST, seeded from the preferences the client sent when it joined. The client's
     * own 3D window is never asked - which is why a player at the 3D table could not get out of
     * Forge's phase stops however the window answered. The one channel across is a YieldUpdate,
     * so send one per phase and turn them all back on.
     */
    static void clearPhaseSkipsRemote(forge.game.player.PlayerView turnPlayer) {
        final forge.interfaces.IGameController r = remote;
        if (r == null || turnPlayer == null) return;
        if (!remoteStopsSent.add(Integer.valueOf(turnPlayer.getId()))) return;
        try {
            for (forge.game.phase.PhaseType ph : forge.game.phase.PhaseType.values()) {
                r.sendYieldUpdate(new forge.gamemodes.match.YieldUpdate.SkipPhase(turnPlayer, ph, false));
            }
            System.out.println("UI3D: asked the host for every phase stop in the turn of "
                + turnPlayer + " - you get the priority, the 3D window decides what to do with it");
        } catch (Throwable t) {
            System.out.println("UI3D: could not turn the host's phase stops back on (" + t + ")");
        }
    }

    /** Cancel any auto-pass. Harmless when none is set. */
    public static void stopAutoPass() {
        try {
            forge.player.PlayerControllerHuman c = controller;
            if (c == null) return;
            // Forge's own way of calling it off, which clears both flags and tells the rest
            // of Forge it happened - poking the two setters left the UI thinking it was still
            // auto-passing.
            c.autoPassCancel();
        } catch (Throwable t) {
            System.out.println("UI3D: could not stop the auto-pass (" + t + ")");
        }
    }

    /**
     * Move on by one step, and only one.
     *
     * The same pass of priority that OK sends, with the auto-pass cleared first, so this can
     * never turn into "and the rest of the turn as well" - and so it doubles as the way out if
     * something else already armed one. Separated from OK because the two mean different things
     * to a player: OK answers a question, this says "I am done here".
     */
    public static boolean nextPhase() {
        stopAutoPass();
        clearPhaseSkips();      // self-healing: Forge can switch them off again at any time
        return pressOk();
    }

    /** True once a game is running with the wrapper installed. */
    public static boolean active() {
        return controller != null || remote != null;
    }

    /** Returned when a taken-over method decides to forward after all. */
    private static final Object NOT_HANDLED = new Object();

    /**
     * Answer a taken-over method from the 3D window.
     *
     * Only `confirm` so far. The game thread blocks here until the player answers, Handoff times
     * out, the window stops drawing, or they press H - and on any of those last three the call
     * falls through to Forge, which is still perfectly able to ask it. That fallback is the whole
     * reason this is safe to do at all.
     */
    /**
     * Whether a phase happens for us at all.
     *
     * This one decided whole combats were skipped. Forge asks the GUI, and `CMatchUI` answers
     * from `PhaseIndicator.getLabelFor(phase).getEnabled()` - the little per-player phase toggles
     * in ITS OWN window. Playing in the 3D table you never see them, cannot reach them, and have
     * no idea they are deciding anything: the owner pressed SPACE at beginning of combat on turn
     * 30 and the turn ended, because declare attackers, blockers, damage and second main were all
     * switched off, so one pass of priority ran through the lot (2026-10-21; `PHASE_HUMAN_
     * DECLAREATTACKERS=false` was sitting in the preferences).
     *
     * The 3D window is the UI here and has no such toggles, so the answer is always "do not
     * skip": every phase is offered and SPACE moves on.
     *
     * The PlayerView handed in is the TURN PLAYER, not the player being asked. Reading it as "is
     * this my seat" meant we only insisted on our own turns, and on everybody else's the
     * invisible toggles still ran - so Alec got no priority after Derand declared attackers,
     * could not sacrifice his creatures in response, and was put straight on to blocking
     * (2026-10-21). Whose turn it is makes no difference: we want the priority. What to do with
     * it is then the 3D window's decision, made out loud with the responses toggle.
     *
     * -Dforge.ui3d.phasestops=false gives the decision back to Forge's toggles.
     */
    private Object takeSkipPhase(Object[] args) {
        if ("false".equalsIgnoreCase(System.getProperty("forge.ui3d.phasestops", "true"))) return NOT_HANDLED;
        if (args == null || args.length < 1 || !(args[0] instanceof forge.game.player.PlayerView)) return NOT_HANDLED;
        return Boolean.FALSE;          // nothing is skipped on us, in anybody's turn
    }

    /**
     * Looking into a zone - an opponent's hand under a Duress, your own library on a search.
     *
     * observe() has already mirrored it into the 3D window by the time this runs (peekZone,
     * which sets `peeking`). All that is left is to decide whether Forge should ALSO throw
     * its floating card window over the table, which is what it has been doing - the owner
     * reported being dragged to Forge's UI to read a hand the 3D table was already showing
     * (2026-10-21).
     *
     * Swallowed only when the mirror actually has the cards. If peekZone came back with
     * nothing - an empty zone, a shape it did not understand - Forge opens its window as
     * before, because a zone you cannot see at all is worse than one in the wrong window.
     * take() has already refused if the 3D window is not drawing, so that case is covered
     * too. -Dforge.ui3d.zones=false keeps Forge's window whatever happens.
     */
    private Object takeOpenZones() {
        if ("false".equalsIgnoreCase(System.getProperty("forge.ui3d.zones", "true"))) return NOT_HANDLED;
        return peeking != null ? null : NOT_HANDLED;      // null: the method returns void
    }

    /**
     * Forge's generic yes/no box - showConfirmDialog(message, title[, yes, no[, defaultYes]]).
     *
     * Not the same call as `confirm`, which is the engine asking about a card; this is the one
     * anything in Forge reaches for when it wants a modal question. It was a box in the other
     * window, so it is answered here instead, through the same Ask the 3D window already draws.
     */
    private Object takeConfirmDialog(Method method, Object[] args) {
        try {
            String message = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            String title = args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
            String yes = args.length > 2 && args[2] instanceof String ? (String) args[2] : "Yes";
            String no = args.length > 3 && args[3] instanceof String ? (String) args[3] : "No";
            String question = title.isEmpty() || title.equals(message) ? message : title + " - " + message;
            Handoff<Boolean> h = Handoff.start("showConfirmDialog: " + question);
            pending = new Ask(question, "", yes, no, h);
            try {
                Boolean answer = h.awaitOr(() -> forward(method, args, Boolean.class));
                return answer == null ? Boolean.FALSE : answer;
            } finally {
                pending = null;
            }
        } catch (Throwable t) {
            System.out.println("UI3D: confirm dialog failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /**
     * showOptionDialog(message, title, icon, options, defaultOption) -> the index chosen.
     *
     * A row of named buttons. OptionPanel is exactly this, so the only work is turning the
     * labels into options and the answer back into an index. Cancelling returns the default
     * Forge asked for rather than -1: some callers do not expect a refusal, and answering with
     * what Forge would have pre-selected is the safe reading.
     */
    private Object takeOptionDialog(Method method, Object[] args) {
        try {
            String message = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            String title = args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
            java.util.List<?> options = args.length > 3 && args[3] instanceof java.util.List
                ? (java.util.List<?>) args[3] : null;
            int fallback = args.length > 4 && args[4] instanceof Integer ? (Integer) args[4] : 0;
            if (options == null || options.isEmpty()) return NOT_HANDLED;
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            java.util.List<Object> entities = new ArrayList<>();
            java.util.List<String> labels = new ArrayList<>();
            for (Object o : options) { labels.add(String.valueOf(o)); entities.add(o); }
            Handoff<java.util.List<Object>> h = Handoff.start("showOptionDialog: " + title);
            Choice c = new Choice(title.isEmpty() ? message : title, items, entities, 1, 1, h);
            c.cards = false;
            c.labels = labels;
            choosing = c;
            try {
                java.util.List<Object> picked = h.awaitOr(() -> {
                    Integer forged = forward(method, args, Integer.class);
                    return forged == null ? null : java.util.Collections.singletonList((Object) forged);
                });
                if (picked == null || picked.isEmpty()) return fallback;
                Object first = picked.get(0);
                if (first instanceof Integer) return first;      // came back from Forge
                int at = entities.indexOf(first);
                return at >= 0 ? at : fallback;
            } finally {
                choosing = null;
            }
        } catch (Throwable t) {
            System.out.println("UI3D: option dialog failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /**
     * showRevealedCards(cards): CMatchUI opens Forge's floating zone window over whatever zones
     * hold them. Mirrored as a Peek titled "Revealed" instead (the search panel shows it, as for
     * openZones), and Forge's window stays shut. A card Forge would not show us stays face down.
     * Nothing comes back from this call, so nothing is blocked. hideRevealedCards (observe)
     * closes the mirror. If there is nothing to show, Forge does it as before.
     */
    private Object takeRevealedCards(Object[] args) {
        try {
            if (args == null || args.length < 1 || !(args[0] instanceof Iterable)) return NOT_HANDLED;
            forge.game.player.PlayerView viewer = pch == null ? null : pch.getLocalPlayerView();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            for (Object o : (Iterable<?>) args[0]) {
                if (!(o instanceof CardView)) continue;
                CardView cv = (CardView) o;
                remember(cv.getId(), cv);
                boolean show;
                try { show = viewer == null || cv.canBeShownTo(viewer); } catch (Throwable e) { show = false; }
                Snapshot.CardV v = show ? describe(cv, cv.getName()) : new Snapshot.CardV();
                v.id = cv.getId();
                if (!show) { v.faceDown = true; v.typeLine = "Creature"; v.creature = true; }
                items.add(v);
            }
            if (items.isEmpty()) return NOT_HANDLED;
            peeking = new Peek(REVEALED_TITLE, items);
            return null;                                  // void: Forge's window stays shut
        } catch (Throwable t) {
            return NOT_HANDLED;
        }
    }

    /**
     * showInputDialog(message, title, icon, initial, choices, isNumeric) -> what was typed.
     *
     * Only the LIST form is taken: with `choices` the question is "pick one of these", which is
     * OptionPanel again. Free text is left with Forge on purpose - the 3D window has no text
     * field, and libGDX's getTextInput is declared but not implemented by the LWJGL3 backend
     * (see BugReport, which had to fall back to Swing for the same reason). Building one is its
     * own job, and guessing at an empty string here would answer the question wrongly.
     */
    private Object takeInputDialog(Method method, Object[] args) {
        try {
            java.util.List<?> choices = args.length > 4 && args[4] instanceof java.util.List
                ? (java.util.List<?>) args[4] : null;
            String message = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            String title = args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
            // Free text (2026-10-21, Stoneforge): TextPanel, the 3D window's own field.
            if (choices == null || choices.isEmpty()) return takeFreeText(method, args, message, title);
            java.util.List<Object> entities = new ArrayList<>();
            java.util.List<String> labels = new ArrayList<>();
            for (Object o : choices) { labels.add(String.valueOf(o)); entities.add(o); }
            Handoff<java.util.List<Object>> h = Handoff.start("showInputDialog: " + title);
            Choice c = new Choice(title.isEmpty() ? message : title, new ArrayList<>(), entities, 1, 1, h);
            c.cards = false;
            c.labels = labels;
            choosing = c;
            try {
                java.util.List<Object> picked = h.awaitOr(() -> {
                    String forged = forward(method, args, String.class);
                    return forged == null ? null : java.util.Collections.singletonList((Object) forged);
                });
                if (picked == null || picked.isEmpty()) return null;        // cancelled: Forge's own answer
                return String.valueOf(picked.get(0));
            } finally {
                choosing = null;
            }
        } catch (Throwable t) {
            System.out.println("UI3D: input dialog failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /** A typed answer Forge wants: showInputDialog without a list. */
    public static final class TextAsk {
        public final String title, prompt, initial;
        public final boolean numeric;
        final Handoff<Object> handoff;
        TextAsk(String title, String prompt, String initial, boolean numeric, Handoff<Object> h) {
            this.title = title; this.prompt = prompt; this.initial = initial; this.numeric = numeric; this.handoff = h;
        }
    }

    private static volatile TextAsk textAsk;
    private static final Object CANCELLED = new Object();

    public static TextAsk pendingText() { return textAsk; }

    /** From the render thread: the text, or null for Cancel (Forge's dialog returns null then too). */
    public static void answerText(String text) {
        TextAsk t = textAsk;
        if (t != null) t.handoff.answer(text == null ? CANCELLED : text);
    }

    /** showInputDialog(message, title, icon, initial, null, isNumeric) - a typed answer. */
    private Object takeFreeText(Method method, Object[] args, String message, String title) {
        try {
            String initial = args.length > 3 && args[3] instanceof String ? (String) args[3] : "";
            boolean numeric = args.length > 5 && Boolean.TRUE.equals(args[5]);
            Handoff<Object> h = Handoff.start("showInputDialog (text): " + title);
            textAsk = new TextAsk(title.isEmpty() ? "Forge asks" : title, message, initial, numeric, h);
            Object got;
            try {
                // Handed back: Forge's own box asks instead, and its answer is the answer.
                got = h.awaitOr(() -> { String f = forward(method, args, String.class); return f == null ? CANCELLED : f; });
            } finally {
                textAsk = null;
            }
            return got == CANCELLED || got == null ? null : got;
        } catch (Throwable t) {
            textAsk = null;
            System.out.println("UI3D: text input failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /** Ask Forge after all, for a handed-back question. Null if even that fails. */
    private <T> T forward(Method method, Object[] args, Class<T> type) {
        try {
            Object r = method.invoke(original, args);
            return type.isInstance(r) ? type.cast(r) : null;
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Throwable t) {
            return null;
        }
    }
    static final String REVEALED_TITLE = "Revealed";

    /**
     * assignGenericAmount(source, targets, amount, atLeastOne, label) -> how much each one gets.
     *
     * The same shape as assigning combat damage - a pool to split across several things - so it
     * reuses DamagePanel rather than growing a second panel that does the same job. The map
     * Forge passes carries each target's suggested or maximum share, which is exactly what the
     * panel calls "lethal" for a blocker, so the numbers mean the same thing in both.
     *
     * Only taken when every target is a card. Forge allows players in there too (splitting
     * damage between a creature and its controller, say) and the panel has nowhere to draw one,
     * so those stay with Forge rather than being drawn wrongly.
     */
    private Object takeGenericAmount(Object[] args) {
        try {
            if (args.length < 3 || !(args[1] instanceof java.util.Map) || !(args[2] instanceof Integer)) {
                return NOT_HANDLED;
            }
            java.util.Map<?, ?> targets = (java.util.Map<?, ?>) args[1];
            if (targets.isEmpty()) return NOT_HANDLED;
            java.util.List<Object> keys = new ArrayList<>();
            java.util.List<CardView> views = new ArrayList<>();
            for (java.util.Map.Entry<?, ?> e : targets.entrySet()) {
                if (!(e.getKey() instanceof CardView)) return NOT_HANDLED;   // a player: Forge's
                keys.add(e.getKey());
                views.add((CardView) e.getKey());
            }
            int amount = (Integer) args[2];
            if (amount <= 0) return NOT_HANDLED;
            CardView source = args[0] instanceof CardView ? (CardView) args[0] : views.get(0);
            java.util.List<Snapshot.CardV> shown = new ArrayList<>();
            int[] caps = new int[views.size()];
            for (int i = 0; i < views.size(); i++) {
                shown.add(describe(views.get(i), views.get(i).getName()));
                Object cap = targets.get(views.get(i));
                caps[i] = cap instanceof Integer && (Integer) cap > 0 ? (Integer) cap : amount;
            }
            Handoff<int[]> h = Handoff.start("assignGenericAmount: " + amount);
            damageAsk = new DamageAsk(describe(source, source.getName()), shown, caps, amount, views, h);
            int[] split;
            try {
                split = h.awaitOr(() -> null);
            } finally {
                damageAsk = null;
            }
            if (split == null || split.length != keys.size()) return NOT_HANDLED;
            java.util.Map<Object, Integer> out = new java.util.HashMap<>();
            for (int i = 0; i < keys.size(); i++) out.put(keys.get(i), split[i]);
            return out;
        } catch (Throwable t) {
            damageAsk = null;
            System.out.println("UI3D: amount split failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /**
     * insertInList(title, item, list) -> the list with the item somewhere in it.
     *
     * Ordering again, with one card already singled out, so OrderPanel does it: the item is put
     * at the front of the list it is joining and the player drags it to where they want. Giving
     * them the whole order to arrange is more than Forge asked for and strictly more useful -
     * the answer is a list either way.
     */
    private Object takeInsertInList(Method method, Object[] args) {
        try {
            if (args.length < 3 || !(args[2] instanceof java.util.List)) return NOT_HANDLED;
            Object item = args[1];
            if (item == null) return NOT_HANDLED;
            java.util.List<Object> all = new ArrayList<>();
            all.add(item);
            for (Object o : (java.util.List<?>) args[2]) if (o != item) all.add(o);
            if (all.size() < 2) return all;                  // nowhere else it could go
            String title = args[0] instanceof String ? (String) args[0] : "Where does it go?";
            java.util.List<Snapshot.CardV> shown = new ArrayList<>();
            for (Object o : all) shown.add(describe(o, label(o, null)));
            Handoff<java.util.List<Object>> h = Handoff.start("insertInList: " + title);
            orderAsk = new OrderAsk(title, label(item, null) + " goes where you put it", shown, all, h);
            java.util.List<Object> out;
            try {
                out = h.awaitOr(() -> null);
            } finally {
                orderAsk = null;
            }
            if (out == null || out.size() != all.size()) return NOT_HANDLED;
            return out;
        } catch (Throwable t) {
            orderAsk = null;
            System.out.println("UI3D: insert-in-list failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    private Object take(Method method, Object[] args) {
        // Never try to answer from 3D if the window is not drawing - go straight to Forge.
        if (!Handoff.windowResponsive()) return NOT_HANDLED;
        if ("reveal".equals(method.getName())) return takeReveal(args);
        // message() is Forge's modal "here is something you should know" box. Taking it over is
        // the same bargain as reveal - display and return, no decision - and it removes another
        // thing that raised Forge's window over the 3D one in the middle of a turn.
        if ("message".equals(method.getName())) return takeMessage(args);
        if ("chooseSingleEntityForEffect".equals(method.getName())) return takeChoose(args, true);
        if ("chooseEntitiesForEffect".equals(method.getName())) return takeChoose(args, false);
        switch (method.getName()) {
            case "one": case "oneOrNone": case "getChoices": case "many":
                return takeList(method, args);
            case "getInteger":
                return takeInteger(args);
            case "order":
                return takeOrder(method, args);
            case "assignCombatDamage":
                return takeDamage(args);
            case "getAbilityToPlay":
                return takeAbility(args);
            case "isUiSetToSkipPhase":
                return takeSkipPhase(args);
            case "openZones":
                return takeOpenZones();
            case "showConfirmDialog":
                return takeConfirmDialog(method, args);
            case "showOptionDialog":
                return takeOptionDialog(method, args);
            case "showInputDialog":
                return takeInputDialog(method, args);
            case "showErrorDialog":
                // Forge's modal error box (SOptionPane.showErrorDialog): (message[, title]) - the
                // same shape, and the same "read it, then carry on", as message().
                return takeMessage(args);
            case "alertUser":
            case "flashIncorrectAction":
                // CPrompt.alert / CPrompt.remind: a flash on Forge's prompt, meant to draw the eye
                // to Forge's window - the opposite of what is wanted while playing in 3D.
                return null;
            case "showRevealedCards":
                return takeRevealedCards(args);
            case "assignGenericAmount":
                return takeGenericAmount(args);
            case "manipulateCardList":
                return takeManipulate(args);
            case "insertInList":
                return takeInsertInList(method, args);
            default:
                break;
        }
        if (!"confirm".equals(method.getName())) return NOT_HANDLED;
        try {
            String question = args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
            CardView card = args.length > 0 && args[0] instanceof CardView ? (CardView) args[0] : null;
            String yes = "Yes", no = "No";
            if (args.length > 3 && args[3] instanceof java.util.List) {
                java.util.List<?> opts = (java.util.List<?>) args[3];
                if (opts.size() > 0 && opts.get(0) != null) yes = String.valueOf(opts.get(0));
                if (opts.size() > 1 && opts.get(1) != null) no = String.valueOf(opts.get(1));
            }
            Handoff<Boolean> h = Handoff.start("confirm: " + question);
            pending = new Ask(question, card == null ? "" : card.getName(), yes, no, h);
            try {
                final Object[] forwarded = args;
                final Method m = method;
                Boolean result = h.awaitOr(() -> {
                    // Handed back: let Forge ask it, exactly as if it had never been taken over.
                    try {
                        return (Boolean) m.invoke(original, forwarded);
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e.getCause());
                    }
                });
                return result == null ? Boolean.TRUE : result;
            } finally {
                pending = null;
            }
        } catch (Throwable t) {
            System.out.println("UI3D: confirm failed, handing it to Forge (" + t + ")");
            pending = null;
            return NOT_HANDLED;
        }
    }

    /**
     * Show a list in the 3D window instead of Forge's modal box.
     *
     * The elements can be anything - the deck warning passes strings, a revealed hand passes
     * CardViews - so a CardView is read properly and everything else falls back to its text.
     * Nothing here needs to understand the type, because the only job is to display it and
     * return; that is why this one is safe to take over despite the unknown element type, where
     * answering a *question* about the list would not be.
     */
    private Object takeReveal(Object[] args) {
        try {
            String title = args.length > 0 && args[0] instanceof String ? (String) args[0] : "Revealed";
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            if (args.length > 1 && args[1] instanceof Iterable) {
                for (Object o : (Iterable<?>) args[1]) {
                    Snapshot.CardV v = new Snapshot.CardV();
                    if (o instanceof CardView) {
                        CardView cv = (CardView) o;
                        v.id = cv.getId();
                        v.name = cv.getName();
                        CardView.CardStateView st = cv.getCurrentState();
                        if (st != null && st.getType() != null) v.typeLine = st.getType().toString();
                    } else {
                        v.name = String.valueOf(o);
                    }
                    items.add(v);
                }
            }
            Handoff<Boolean> h = Handoff.start("reveal: " + title);
            revealing = new Reveal(title, items, h);
            try {
                h.awaitOr(() -> Boolean.TRUE);      // nothing to hand back: it is only a display
            } finally {
                revealing = null;
            }
            return null;                            // reveal returns void
        } catch (Throwable t) {
            System.out.println("UI3D: reveal failed, letting Forge show it (" + t + ")");
            revealing = null;
            return NOT_HANDLED;
        }
    }

    /**
     * Forge's message box, shown in the 3D window. args are (text) or (text, title).
     */
    private Object takeMessage(Object[] args) {
        try {
            String text = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            String title = args.length > 1 && args[1] instanceof String ? (String) args[1] : "Forge";
            if (text.isEmpty()) return NOT_HANDLED;
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            Snapshot.CardV v = new Snapshot.CardV();
            v.name = text;
            items.add(v);
            Handoff<Boolean> h = Handoff.start("message: " + title);
            revealing = new Reveal(title, items, h);
            try {
                h.awaitOr(() -> Boolean.TRUE);
            } finally {
                revealing = null;
            }
            return null;
        } catch (Throwable t) {
            revealing = null;
            return NOT_HANDLED;
        }
    }

    /** A choice the 3D window is making: the options, and where the answer goes. */
    public static final class Choice {
        public final String title;
        public final java.util.List<Snapshot.CardV> items;
        public final int min, max;
        /**
         * True when every option is a card, so SearchPanel shows them as cards; false for lists
         * of words, players, modes, colours - those go to OptionPanel, labelled by `labels`.
         */
        public boolean cards = true;
        public java.util.List<String> labels = new ArrayList<>();
        /** A card on the table to open the choice beside, as a drop-down (-1: centred panel). */
        public int anchorCardId = -1;
        final java.util.List<Object> entities;
        final Handoff<java.util.List<Object>> handoff;
        Choice(String title, java.util.List<Snapshot.CardV> items, java.util.List<Object> entities,
               int min, int max, Handoff<java.util.List<Object>> h) {
            this.title = title; this.items = items; this.entities = entities;
            this.min = min; this.max = max; this.handoff = h;
        }
    }

    private static volatile Choice choosing;

    public static Choice pendingChoice() {
        return choosing;
    }

    /** Answer from the render thread, with the indices the player picked. */
    public static void answerChoice(java.util.List<Integer> pickedIndices) {
        Choice c = choosing;
        if (c == null) return;
        java.util.List<Object> out = new ArrayList<>();
        if (pickedIndices != null) {
            for (int i : pickedIndices) {
                if (i >= 0 && i < c.entities.size()) out.add(c.entities.get(i));
            }
        }
        c.handoff.answer(out);
    }

    /**
     * "Choose a card" - a library search, a graveyard pick, a target from a list.
     *
     * This is what Path to Exile and Weathered Wayfarer were falling through to Forge for, which
     * is also one of the things that kept dragging the player back to that window. The options
     * are shown in SearchPanel, which was built for exactly this and has been waiting.
     *
     * The options can be cards or players (GameEntityView covers both), so anything that is not
     * a CardView is shown by its name. Picks come back as indices into the list we were handed,
     * so the entity returned to Forge is the very object it offered - no lookup, no ambiguity.
     */
    private Object takeChoose(Object[] args, boolean single) {
        boolean handedBack = false;
        try {
            String title = args.length > 0 && args[0] instanceof String ? (String) args[0] : "Choose";
            java.util.List<?> options = args.length > 1 && args[1] instanceof java.util.List
                ? (java.util.List<?>) args[1] : null;
            if (options == null || options.isEmpty()) return NOT_HANDLED;

            int min, max;
            if (single) {
                boolean optional = args.length > 3 && Boolean.TRUE.equals(args[3]);
                min = optional ? 0 : 1;
                max = 1;
            } else {
                min = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : 0;
                max = args.length > 3 && args[3] instanceof Integer ? (Integer) args[3] : options.size();
            }

            java.util.List<Object> entities = new ArrayList<>();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            for (Object o : options) {
                entities.add(o);
                Snapshot.CardV v = new Snapshot.CardV();
                if (o instanceof CardView) {
                    CardView cv = (CardView) o;
                    v.id = cv.getId();
                    v.name = cv.getName();
                    CardView.CardStateView st = cv.getCurrentState();
                    if (st != null) {
                        if (st.getType() != null) v.typeLine = st.getType().toString();
                        if (st.getManaCost() != null) v.manaCost = st.getManaCost().toString();
                        v.text = st.getOracleText() == null ? "" : st.getOracleText();
                    }
                } else if (o instanceof forge.game.GameEntityView) {
                    v.name = ((forge.game.GameEntityView) o).getName();
                    v.typeLine = "Player";
                } else {
                    v.name = String.valueOf(o);
                }
                items.add(v);
            }

            Handoff<java.util.List<Object>> h = Handoff.start("choose: " + title);
            choosing = new Choice(title, items, entities, min, max, h);
            java.util.List<Object> picked;
            try {
                picked = h.awaitOr(() -> null);     // null means: Forge should ask instead
            } finally {
                choosing = null;
            }
            if (picked == null) { handedBack = true; return NOT_HANDLED; }
            if (single) return picked.isEmpty() ? null : picked.get(0);
            return picked;
        } catch (Throwable t) {
            choosing = null;
            System.out.println("UI3D: choose failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        } finally {
            if (handedBack) System.out.println("UI3D: choose handed back to Forge");
        }
    }

    // ---- 2026-10-21: the rest of the panels, hooked up --------------------------------------------

    /** A CardV for any option Forge hands us: a card in full, an ability by its host, else its text. */
    private static Snapshot.CardV describe(Object o, String label) {
        Snapshot.CardV v = new Snapshot.CardV();
        if (o instanceof CardView) {
            CardView cv = (CardView) o;
            v.id = cv.getId();
            v.name = cv.getName();
            CardView.CardStateView st = cv.getCurrentState();
            if (st != null) {
                if (st.getType() != null) v.typeLine = st.getType().toString();
                if (st.getManaCost() != null) v.manaCost = st.getManaCost().toString();
                v.text = st.getOracleText() == null ? "" : st.getOracleText();
            }
        } else if (o instanceof forge.game.spellability.SpellAbilityView) {
            // A triggered or activated ability: show its source card, with what it does under it.
            forge.game.spellability.SpellAbilityView sa = (forge.game.spellability.SpellAbilityView) o;
            CardView host = sa.getHostCard();
            if (host != null) { v.id = host.getId(); v.name = host.getName(); }
            v.stackText = label;
            if (v.name.isEmpty()) v.name = label;
        } else if (o instanceof forge.game.card.IHasCardView) {
            // A replacement effect (ReplacementEffectView) or anything else that belongs to a card:
            // its card's picture, with what it does underneath - as for an ability.
            CardView host = null;
            try { host = ((forge.game.card.IHasCardView) o).getCardView(); } catch (Throwable ignored) { }
            if (host != null) { v.id = host.getId(); v.name = host.getName(); }
            v.stackText = label;
            if (v.name == null || v.name.isEmpty()) v.name = label;
        } else {
            v.name = label;
        }
        return v;
    }

    /** The label Forge would show: its display function if it gave one, else the object's text. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String label(Object o, Object display) {
        try {
            if (display instanceof java.util.function.Function) {
                Object s = ((java.util.function.Function) display).apply(o);
                if (s != null) return String.valueOf(s);
            }
        } catch (Throwable ignored) {
            // fall back to toString
        }
        if (o instanceof forge.game.GameEntityView) return ((forge.game.GameEntityView) o).getName();
        return String.valueOf(o);
    }

    /**
     * one / oneOrNone / getChoices / many: every "pick from this list" Forge asks that is not
     * chooseEntitiesForEffect. Cards go to SearchPanel; anything else (a mode, a colour, a card
     * type, a player, a number given as text) goes to OptionPanel. Shapes:
     *   one(title, list[, display])          exactly one
     *   oneOrNone(title, list)               zero or one
     *   getChoices(title, min, max, list[, selected, display])
     *   many(title, caption, [min,] max, list[, dest], card)   - a negative count means "any"
     * A pick that does not satisfy min (cancelled a must-choose) hands the question to Forge.
     */
    private Object takeList(Method method, Object[] args) {
        try {
            String name = method.getName();
            String title = args.length > 0 && args[0] instanceof String ? (String) args[0] : "Choose";
            java.util.List<?> options = null;
            java.util.List<Integer> ints = new ArrayList<>();
            Object display = null;
            for (int i = 1; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof java.util.List && options == null) options = (java.util.List<?>) a;
                else if (a instanceof Integer) ints.add((Integer) a);
                else if (a instanceof java.util.function.Function) display = a;
            }
            if (options == null || options.isEmpty()) return NOT_HANDLED;
            // many() with a pre-filled destination list is a two-column move (sideboarding); leave it.
            if ("many".equals(name)) {
                int lists = 0;
                for (Object a : args) if (a instanceof java.util.List && !((java.util.List<?>) a).isEmpty()) lists++;
                if (lists > 1) return NOT_HANDLED;
            }
            int min, max;
            switch (name) {
                case "one": min = 1; max = 1; break;
                case "oneOrNone": min = 0; max = 1; break;
                default:
                    if (ints.size() >= 2) { min = ints.get(0); max = ints.get(1); }
                    else if (ints.size() == 1) { min = ints.get(0); max = ints.get(0); }
                    else { min = 0; max = options.size(); }
            }
            if (max < 0 || max > options.size()) max = options.size();
            if (min < 0) min = 0;
            if (min > max) min = max;

            java.util.List<Object> entities = new ArrayList<>();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            java.util.List<String> labels = new ArrayList<>();
            boolean allCards = true;
            for (Object o : options) {
                String l = label(o, display);
                entities.add(o);
                labels.add(l);
                items.add(describe(o, l));
                if (!(o instanceof CardView)) allCards = false;
            }
            String caption = "many".equals(name) && args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
            Handoff<java.util.List<Object>> h = Handoff.start(name + ": " + title);
            Choice c = new Choice(caption.isEmpty() ? title : title + " - " + caption, items, entities, min, max, h);
            c.cards = allCards;
            c.labels = labels;
            choosing = c;
            java.util.List<Object> picked;
            try {
                picked = h.awaitOr(() -> null);
            } finally {
                choosing = null;
            }
            if (picked == null || picked.size() < min) return NOT_HANDLED;   // Forge asks instead
            if ("one".equals(name) || "oneOrNone".equals(name)) return picked.isEmpty() ? null : picked.get(0);
            return picked;
        } catch (Throwable t) {
            choosing = null;
            System.out.println("UI3D: list choice failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /**
     * getAbilityToPlay(card, abilities, triggerEvent): a card with more than one thing it can do
     * right now (a creature with two activated abilities, a land with two colours, cast or
     * activate). Owner's list 2026-10-20 - it opened Forge's own pop-up list, which also pulled
     * focus to Forge's window. Shown as an OptionPanel column, one button per ability in Forge's own
     * words; ESC answers null, which is what Forge's list returns when closed (nothing played).
     * The answer is the very SpellAbilityView Forge offered.
     */
    private Object takeAbility(Object[] args) {
        try {
            CardView host = args.length > 0 && args[0] instanceof CardView ? (CardView) args[0] : null;
            java.util.List<?> options = args.length > 1 && args[1] instanceof java.util.List ? (java.util.List<?>) args[1] : null;
            if (options == null) return NOT_HANDLED;
            // Forge's own rules first (CMatchUI.getAbilityToPlay): nothing offered = nothing played.
            if (options.isEmpty()) return null;
            // No click behind it (triggerEvent null): NOT a card the player clicked, but Forge
            // putting something on the stack for them - EVERY human trigger comes through here as
            // a list of one (PlaySpellAbility.chooseOptionalAdditionalCosts). Forge's window then
            // returns a single option as it is, never checking canPlay - a trigger is never
            // "playable" in that sense. The first version of this applied the click rules here,
            // answered null, and silently dropped every one of the owner's triggers (Lotho, Clown
            // Car, Mr. House; game of 2026-10-20 23:31). Several options: a choice, nothing filtered.
            boolean clicked = args.length > 2 && args[2] != null;
            if (!clicked) {
                if (options.size() == 1) return options.get(0);
            } else if (options.size() == 1 && options.get(0) instanceof forge.game.spellability.SpellAbilityView) {
                // A click: a single ability that does not ask to be confirmed is played if it can be.
                //
                // When it cannot, hand the question to Forge rather than answering null. null is
                // "the player chose nothing", and Forge drops the click without a word - which is
                // how tapping Arcane Signet for mana did nothing four times in a row while the log
                // said only "Forge refused the click" (the owner, 2026-10-21). canPlay() is false
                // for a mana ability with nothing to pay for, yet Forge's own window offers it and
                // floats the mana. Forge is the one that knows; let it answer.
                forge.game.spellability.SpellAbilityView only = (forge.game.spellability.SpellAbilityView) options.get(0);
                if (!only.promptIfOnlyPossibleAbility()) return only.canPlay() ? only : NOT_HANDLED;
            }
            java.util.List<Object> entities = new ArrayList<>();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            java.util.List<String> labels = new ArrayList<>();
            for (Object o : options) {
                // On a click Forge greys out what cannot be played now; here it is simply not
                // offered. Without a click (optional costs of something already being played)
                // nothing is filtered, as Forge's own list does not either.
                if (clicked && o instanceof forge.game.spellability.SpellAbilityView
                        && !((forge.game.spellability.SpellAbilityView) o).canPlay()) continue;
                String l = abilityLabel(o);
                entities.add(o);
                labels.add(l);
                items.add(describe(o, l));
            }
            if (entities.isEmpty()) return null;                      // none playable right now
            String title = host == null ? "Which ability?" : host.getName() + " - which ability?";
            Handoff<java.util.List<Object>> h = Handoff.start("ability: " + title);
            Choice c = new Choice(title, items, entities, 0, 1, h);
            c.cards = false;
            c.labels = labels;
            if (host != null) c.anchorCardId = host.getId();    // a drop-down at the card
            choosing = c;
            java.util.List<Object> picked;
            try {
                picked = h.awaitOr(() -> null);
            } finally {
                choosing = null;
            }
            if (picked == null) return NOT_HANDLED;                    // handed back: Forge asks
            return picked.isEmpty() ? null : picked.get(0);            // ESC = nothing played
        } catch (Throwable t) {
            choosing = null;
            System.out.println("UI3D: ability choice failed, handing it to Forge (" + t + ")");
            return NOT_HANDLED;
        }
    }

    /** Forge's own wording for an ability, as its pop-up list shows it ("{T}: Add {W}."). */
    static String abilityLabel(Object o) {
        if (o instanceof forge.game.spellability.SpellAbilityView) {
            forge.game.spellability.SpellAbilityView sa = (forge.game.spellability.SpellAbilityView) o;
            // Casting the card itself: Forge's text is "Name - Creature 1 / 1", which reads as if
            // it were another ability. Adventures and split halves have their own names, so keep
            // whatever follows a different name.
            try {
                CardView host = sa.getHostCard();
                String s = String.valueOf(sa);
                if (sa.isSpell() && host != null && s.startsWith(host.getName() + " - ")) return "Cast " + host.getName();
            } catch (Throwable ignored) { /* fall through */ }
            String d = null;
            try { d = sa.getDescription(); } catch (Throwable ignored) { /* fall back */ }
            if (d == null || d.trim().isEmpty()) d = String.valueOf(sa);
            return d.trim();
        }
        return String.valueOf(o);
    }

    /** A number Forge wants (an X cost, "choose a number"). */
    public static final class NumberAsk {
        public final String title;
        public final int min, max;
        final Handoff<Integer> handoff;
        NumberAsk(String title, int min, int max, Handoff<Integer> h) {
            this.title = title; this.min = min; this.max = max; this.handoff = h;
        }
    }

    private static volatile NumberAsk numberAsk;

    public static NumberAsk pendingNumber() { return numberAsk; }

    /** Answer from the render thread; null means cancelled. */
    public static void answerNumber(Integer value) {
        NumberAsk n = numberAsk;
        if (n != null) n.handoff.answer(value == null ? Integer.MIN_VALUE : value);
    }

    /** getInteger(title, min, max, sortDesc | cutoff). Cancel returns null, as Forge's own dialog does. */
    private Object takeInteger(Object[] args) {
        try {
            String title = args.length > 0 && args[0] instanceof String ? (String) args[0] : "Choose a number";
            int min = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
            int max = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : 99;
            if (max == Integer.MAX_VALUE || max > 999) max = 99;     // "any number": keep the picker usable
            Handoff<Integer> h = Handoff.start("integer: " + title);
            numberAsk = new NumberAsk(title, min, max, h);
            Integer v;
            try {
                v = h.awaitOr(() -> null);
            } finally {
                numberAsk = null;
            }
            if (v == null) return NOT_HANDLED;                        // handed back: Forge asks
            return v == Integer.MIN_VALUE ? null : v;                 // cancelled, or the number
        } catch (Throwable t) {
            numberAsk = null;
            return NOT_HANDLED;
        }
    }

    /** Cards or abilities to put in order (the top of a scry, simultaneous triggers). */
    public static final class OrderAsk {
        public final String title, caption;
        public final java.util.List<Snapshot.CardV> items;
        final java.util.List<Object> entities;
        final Handoff<java.util.List<Object>> handoff;
        /**
         * Two named rows instead of one (manipulateCardList: "Top" / "Bottom"), or null. The
         * first `firstRowCount` items start in the first row, the rest in the second. Answered
         * with answerOrderRows.
         */
        public String[] rowNames;
        public int firstRowCount;
        OrderAsk(String title, String caption, java.util.List<Snapshot.CardV> items, java.util.List<Object> entities,
                 Handoff<java.util.List<Object>> h) {
            this.title = title; this.caption = caption; this.items = items; this.entities = entities; this.handoff = h;
        }
    }

    /** Separates the rows in a two-row answer (see takeManipulate). */
    static final Object ROW_BREAK = new Object();

    /** Answer a two-row order: item indices of each row, in order; null = hand it to Forge. */
    public static void answerOrderRows(java.util.List<Integer> first, java.util.List<Integer> second) {
        OrderAsk o = orderAsk;
        if (o == null) return;
        if (first == null || second == null) { o.handoff.answer(null); return; }
        java.util.List<Object> out = new ArrayList<>();
        for (int i : first) if (i >= 0 && i < o.entities.size()) out.add(o.entities.get(i));
        out.add(ROW_BREAK);
        for (int i : second) if (i >= 0 && i < o.entities.size()) out.add(o.entities.get(i));
        o.handoff.answer(out);
    }

    /**
     * manipulateCardList(title, cards, manipulable, toTop, toBottom, toAnywhere) -> the zone in
     * its new order (GuiChoose's dialog in Forge's window). PlayerControllerHuman.arrangeForMove
     * reads the answer as: manipulable cards at the front go on top, those at the end go to the
     * bottom. Shown as OrderPanel's two rows, Top and Bottom (only the one allowed if Forge allows
     * only one); everything else keeps its place between them. toAnywhere also allows the middle,
     * which this does not offer - top or bottom is always one of the legal answers.
     */
    private Object takeManipulate(Object[] args) {
        try {
            if (args.length < 6 || !(args[1] instanceof Iterable) || !(args[2] instanceof Iterable)) return NOT_HANDLED;
            java.util.List<CardView> all = new ArrayList<>();
            for (Object o : (Iterable<?>) args[1]) if (o instanceof CardView) all.add((CardView) o);
            java.util.List<CardView> movable = new ArrayList<>();
            for (Object o : (Iterable<?>) args[2]) if (o instanceof CardView && all.contains(o)) movable.add((CardView) o);
            if (movable.isEmpty()) return NOT_HANDLED;
            boolean toTop = Boolean.TRUE.equals(args[3]), toBottom = Boolean.TRUE.equals(args[4]);
            if (!toTop && !toBottom) return NOT_HANDLED;          // "anywhere" only: Forge's dialog
            String title = args[0] instanceof String ? (String) args[0] : "Arrange";
            java.util.List<Object> entities = new ArrayList<>(movable);
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            for (CardView cv : movable) { remember(cv.getId(), cv); items.add(describe(cv, cv.getName())); }
            Handoff<java.util.List<Object>> h = Handoff.start("manipulateCardList: " + title);
            OrderAsk ask = new OrderAsk(title, "", items, entities, h);
            ask.rowNames = toTop && toBottom ? new String[] { "Top", "Bottom" } : new String[] { toTop ? "Top" : "Bottom" };
            ask.firstRowCount = movable.size();                   // all start in the first row
            orderAsk = ask;
            java.util.List<Object> out;
            try {
                out = h.awaitOr(() -> null);
            } finally {
                orderAsk = null;
            }
            if (out == null) return NOT_HANDLED;
            java.util.List<CardView> top = new ArrayList<>(), bottom = new ArrayList<>();
            boolean second = false;
            for (Object o : out) {
                if (o == ROW_BREAK) { second = true; continue; }
                CardView cv = (CardView) o;
                // One row only: it is the top row if top is allowed, else the bottom one.
                if (!second && toTop) top.add(cv); else bottom.add(cv);
            }
            if (top.size() + bottom.size() != movable.size()) return NOT_HANDLED;
            java.util.List<CardView> result = new ArrayList<>(top);
            for (CardView cv : all) if (!movable.contains(cv)) result.add(cv);
            result.addAll(bottom);
            return result;
        } catch (Throwable t) {
            orderAsk = null;
            return NOT_HANDLED;
        }
    }

    private static volatile OrderAsk orderAsk;

    public static OrderAsk pendingOrder() { return orderAsk; }

    /** Answer with the item indices in their new order; null = hand it to Forge. */
    public static void answerOrder(java.util.List<Integer> indices) {
        OrderAsk o = orderAsk;
        if (o == null) return;
        if (indices == null) { o.handoff.answer(null); return; }
        java.util.List<Object> out = new ArrayList<>();
        for (int i : indices) if (i >= 0 && i < o.entities.size()) out.add(o.entities.get(i));
        o.handoff.answer(out);
    }

    /**
     * order(title, caption, list, card): pure ordering, which is what a scry's top cards and
     * simultaneous triggers use. The longer forms move cards between two lists (sideboarding) and
     * are left to Forge.
     */
    private Object takeOrder(Method method, Object[] args) {
        try {
            // The two-list form:
            //   order(title, top, remainingMin, remainingMax, source, dest, card, sideboarding, ...)
            //
            // This is the "Select order for simultaneous abilities" dialog the owner kept being
            // thrown into (screenshot, 2026-10-21): two lists, four movers, OK and Auto. We only
            // ever handled the four-argument form, so every one of these was handed to Forge.
            //
            // Taken when EVERYTHING has to be ordered - remainingMin and remainingMax both zero,
            // which is what simultaneous triggers ask for. When some items may be left behind
            // (sideboarding) the question is a different shape and stays with Forge.
            if (args.length >= 8 && args[4] instanceof java.util.List && args[5] instanceof java.util.List) {
                int remainMin = args[2] instanceof Integer ? (Integer) args[2] : -1;
                boolean sideboarding = args[7] instanceof Boolean && (Boolean) args[7];
                // Taken whenever nothing HAS to be left behind (remainingMin 0): ordering all of
                // them is then always a legal answer. Simultaneous triggers ask with max 0;
                // replacement effects ("Select order for replacement effects" - Barbarian Class +
                // Wyll on a roll, Academy Manufactor + Mondrak on a token, the owner's game of
                // 2026-10-21) ask with remainingMax = n-1, meaning only the first must be chosen.
                // Requiring max 0 sent every replacement choice to Forge's minimised window.
                if (remainMin != 0 || sideboarding) return NOT_HANDLED;
                java.util.List<Object> all = new ArrayList<>((java.util.List<?>) args[5]);   // already placed
                for (Object o : (java.util.List<?>) args[4]) if (!all.contains(o)) all.add(o);
                if (all.size() < 2) return NOT_HANDLED;
                String title2 = args[0] instanceof String ? (String) args[0] : "Order";
                String caption2 = args[1] instanceof String ? (String) args[1] : "";
                java.util.List<Snapshot.CardV> shown = new ArrayList<>();
                for (Object o : all) shown.add(describe(o, label(o, null)));
                Handoff<java.util.List<Object>> h2 = Handoff.start("order (two lists): " + title2);
                orderAsk = new OrderAsk(title2, caption2.isEmpty() ? "Resolve first at the top" : caption2,
                    shown, all, h2);
                java.util.List<Object> out2;
                try {
                    out2 = h2.awaitOr(() -> null);
                } finally {
                    orderAsk = null;
                }
                if (out2 == null || out2.size() != all.size()) return NOT_HANDLED;
                if (IGuiGame.OrderResult.class.isAssignableFrom(method.getReturnType())) {
                    // false = do not tick "always use this order": it is a per-game habit the
                    // player has not been asked about, and silently setting it would answer
                    // every future one of these without them knowing.
                    return new IGuiGame.OrderResult<>(out2, false);
                }
                return out2;
            }
            if (args.length != 4 || !(args[2] instanceof java.util.List)) return NOT_HANDLED;
            java.util.List<?> src = (java.util.List<?>) args[2];
            if (src.size() < 2) return NOT_HANDLED;              // nothing to order
            String title = args[0] instanceof String ? (String) args[0] : "Order";
            String caption = args[1] instanceof String ? (String) args[1] : "";
            java.util.List<Object> entities = new ArrayList<>(src);
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            for (Object o : src) items.add(describe(o, label(o, null)));
            Handoff<java.util.List<Object>> h = Handoff.start("order: " + title);
            orderAsk = new OrderAsk(title, caption, items, entities, h);
            java.util.List<Object> out;
            try {
                out = h.awaitOr(() -> null);
            } finally {
                orderAsk = null;
            }
            if (out == null || out.size() != src.size()) return NOT_HANDLED;
            if (IGuiGame.OrderResult.class.isAssignableFrom(method.getReturnType())) {
                return new IGuiGame.OrderResult<>(out, false);
            }
            return out;
        } catch (Throwable t) {
            orderAsk = null;
            return NOT_HANDLED;
        }
    }

    /** An attacker's damage to split among its blockers. */
    public static final class DamageAsk {
        public final Snapshot.CardV attacker;
        public final java.util.List<Snapshot.CardV> blockers;
        public final int[] lethal;
        public final int damage;
        /** Trample (Forge passed a defender): the rest may go to the player - DamagePanel's Player column. */
        public boolean trample;
        final java.util.List<CardView> views;
        final Handoff<int[]> handoff;
        DamageAsk(Snapshot.CardV attacker, java.util.List<Snapshot.CardV> blockers, int[] lethal, int damage,
                  java.util.List<CardView> views, Handoff<int[]> h) {
            this.attacker = attacker; this.blockers = blockers; this.lethal = lethal; this.damage = damage;
            this.views = views; this.handoff = h;
        }
    }

    /**
     * The answer with trample: per blocker, then the player's share as one extra last element.
     * (answerDamage(int[]) is the same without the player.)
     */
    public static void answerDamage(int[] perBlocker, int toPlayer) {
        DamageAsk d = damageAsk;
        if (d == null) return;
        if (!d.trample || perBlocker == null) { answerDamage(perBlocker); return; }
        int[] all = java.util.Arrays.copyOf(perBlocker, perBlocker.length + 1);
        all[perBlocker.length] = Math.max(0, toPlayer);
        d.handoff.answer(all);
    }

    private static volatile DamageAsk damageAsk;

    public static DamageAsk pendingDamage() { return damageAsk; }

    /** Answer with damage per blocker, in order; null = hand it to Forge. */
    public static void answerDamage(int[] perBlocker) {
        DamageAsk d = damageAsk;
        if (d != null) d.handoff.answer(perBlocker);
    }

    /**
     * assignCombatDamage(attacker, blockers, damage, defender, overrideOrder, maySkip). Taken
     * over only when there is no defender to assign to: Forge passes one when the attacker could
     * send damage past its blockers (trample and the like), and how that share is keyed in the
     * answer could not be confirmed from the jar - so those go to Forge's own dialog rather than
     * risk the damage being lost.
     */
    private Object takeDamage(Object[] args) {
        try {
            if (args.length < 4) return NOT_HANDLED;
            if (!(args[0] instanceof CardView) || !(args[1] instanceof java.util.List) || !(args[2] instanceof Integer)) return NOT_HANDLED;
            // Trample (2026-10-21): Forge passes the defender, and its own dialog
            // (VAssignCombatDamage.getDamageMap, read in the jar) returns the defender's share
            // under the NULL key - a damage target with no card. Answered the same way here.
            boolean trample = args[3] != null;
            CardView att = (CardView) args[0];
            java.util.List<CardView> views = new ArrayList<>();
            for (Object o : (java.util.List<?>) args[1]) if (o instanceof CardView) views.add((CardView) o);
            if (views.isEmpty()) return NOT_HANDLED;
            int dmg = (Integer) args[2];
            boolean deathtouch = false;
            try { deathtouch = att.getCurrentState().hasDeathtouch(); } catch (Throwable ignored) { }
            java.util.List<Snapshot.CardV> bl = new ArrayList<>();
            int[] lethal = new int[views.size()];
            for (int i = 0; i < views.size(); i++) {
                bl.add(describe(views.get(i), views.get(i).getName()));
                int l;
                try { l = views.get(i).getLethalDamage(); } catch (Throwable e) { l = 1; }
                lethal[i] = deathtouch ? Math.min(1, Math.max(0, l)) : Math.max(0, l);
            }
            Handoff<int[]> h = Handoff.start("damage: " + att.getName());
            DamageAsk ask = new DamageAsk(describe(att, att.getName()), bl, lethal, dmg, views, h);
            ask.trample = trample;
            damageAsk = ask;
            int[] out;
            try {
                out = h.awaitOr(() -> null);
            } finally {
                damageAsk = null;
            }
            int n = views.size();
            if (out == null || out.length != n + (trample ? 1 : 0)) return NOT_HANDLED;
            // Trample's rule: nothing to the player until every blocker has lethal. Forge's dialog
            // enforces it; a split that breaks it goes back to that dialog rather than to the game.
            if (trample && out[n] > 0) {
                for (int i = 0; i < n; i++) if (out[i] < lethal[i]) return NOT_HANDLED;
            }
            java.util.Map<CardView, Integer> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) map.put(views.get(i), out[i]);
            if (trample) map.put(null, out[n]);
            return map;
        } catch (Throwable t) {
            damageAsk = null;
            return NOT_HANDLED;
        }
    }

    // ---- game-level actions from the 3D window ------------------------------------------------------

    /** Rematch / next game / quit, from the end screen: IGameController.nextGameDecision. */
    public static void nextGame(String what) {
        // Quitting goes back to Forge's menus, which live in Forge's window.
        if ("quit".equals(what)) ForgeWindow.restore("quit");
        final forge.player.PlayerControllerHuman c = controller;
        final forge.gamemodes.match.NextGameDecision rd = "quit".equals(what) ? forge.gamemodes.match.NextGameDecision.QUIT
            : "rematch".equals(what) ? forge.gamemodes.match.NextGameDecision.NEW : forge.gamemodes.match.NextGameDecision.CONTINUE;
        if (c == null && remote != null) { remoteClick(r -> r.nextGameDecision(rd)); return; }
        if (c == null) return;
        final forge.gamemodes.match.NextGameDecision d = "quit".equals(what) ? forge.gamemodes.match.NextGameDecision.QUIT
            : "rematch".equals(what) ? forge.gamemodes.match.NextGameDecision.NEW : forge.gamemodes.match.NextGameDecision.CONTINUE;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { c.nextGameDecision(d); } catch (Throwable t) { System.out.println("UI3D: next game failed (" + t + ")"); }
        });
    }

    /** Concede the game (the menu asks first). */
    public static void concede() {
        final forge.player.PlayerControllerHuman c = controller;
        if (c == null && remote != null) { remoteClick(r -> r.concede()); return; }
        if (c == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { c.concede(); } catch (Throwable t) { System.out.println("UI3D: concede failed (" + t + ")"); }
        });
    }

    /** Undo the last action, where Forge allows it (tapping a land for mana, mostly). */
    public static void undo() {
        final forge.player.PlayerControllerHuman c = controller;
        if (c == null && remote != null) { remoteClick(r -> r.undoLastAction()); return; }
        if (c == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { c.undoLastAction(); } catch (Throwable t) { System.out.println("UI3D: undo failed (" + t + ")"); }
        });
    }

    /** Decisions Forge answered because the 3D window does not own them yet, and how often. */
    private static final java.util.Map<String, Integer> FALLBACKS =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    /** -Dforge.ui3d.strict=true also says so on screen, so a gap is obvious while playing. */
    public static final boolean STRICT =
        "true".equalsIgnoreCase(System.getProperty("forge.ui3d.strict", "false"));
    private static volatile String lastFallback = "";
    private static volatile long lastFallbackAt;

    /**
     * A method that returns something, is not a getter, and is not taken over, is a decision
     * Forge is still making for us. Void methods are display calls and do not count.
     */
    private static void noteFallback(Method m) {
        try {
            String n = m.getName();
            // Taken over = the 3D window owns it (when it falls back to Forge anyway, that is the
            // Handoff's own log line). Checked before the void mark below, which would hide the name.
            if (TAKEN_OVER.contains(n)) return;
            // Most void calls only redraw Forge's window. A few make it pop up a window of its own
            // - the floating zone viewer, an error box - which drags the player over to it, so
            // they count too, marked as such. Without this the tally said "the 3D window handled
            // everything" while the owner was being pulled to Forge's zone window again and again
            // (Xenil's Claude's finding, 2026-10-21).
            // Taken over first, and on the BARE name: the mark is appended below, and once it
            // is, TAKEN_OVER can never match - so a window pop we now answer ourselves was
            // still being tallied as one Forge had opened. openZones is the case: it is
            // swallowed, Forge's window never appears, and counting it said otherwise.
            if (TAKEN_OVER.contains(n)) return;
            if (m.getReturnType() == void.class) {
                if (!WINDOW_POPS.contains(n)) return;
                n = n + " (Forge window)";
            }
            if (n.startsWith("get") || n.startsWith("is") || n.startsWith("has")) return;
            synchronized (FALLBACKS) {
                // (n for a void pop-up already carries its " (Forge window)" mark)
                Integer was = FALLBACKS.get(n);
                FALLBACKS.put(n, was == null ? 1 : was + 1);
            }
            // A decision (or pop-up) going to Forge's window: that window must be visible.
            ForgeWindow.restore(n + " is Forge's to ask");
            lastFallback = n;
            lastFallbackAt = System.currentTimeMillis();
            if (STRICT) System.out.println("UI3D: Forge answered '" + n + "' (not taken over yet)");
        } catch (Throwable ignored) {
            // counting must never break a call
        }
    }

    /** One more decision Forge answered, for the tally (bug reports, -Strict). */
    private static void countFallback(String n) {
        synchronized (FALLBACKS) {
            Integer was = FALLBACKS.get(n);
            FALLBACKS.put(n, was == null ? 1 : was + 1);
        }
        lastFallback = n;
        lastFallbackAt = System.currentTimeMillis();
        if (STRICT) System.out.println("UI3D: Forge answered '" + n + "'");
    }

    /** Would forwarding this call put something on Forge's window that the player must answer or see? */
    private static boolean showsForgeWindow(Method m) {
        String n = m.getName();
        if (m.getReturnType() == void.class) return WINDOW_POPS.contains(n);
        return !(n.startsWith("get") || n.startsWith("is") || n.startsWith("has"));
    }

    /** Void IGuiGame calls that open a window of Forge's own rather than only redrawing it. */
    private static final java.util.Set<String> WINDOW_POPS = new java.util.HashSet<>(
        java.util.Arrays.asList("openZones", "showErrorDialog", "showRevealedCards"));

    /** "one x3, order x1" - for the bug report and the strict banner. Empty if none. */
    public static String fallbackSummary() {
        StringBuilder b = new StringBuilder();
        synchronized (FALLBACKS) {
            for (java.util.Map.Entry<String, Integer> e : FALLBACKS.entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(e.getKey()).append(" x").append(e.getValue());
            }
        }
        return b.toString();
    }

    /** The most recent one, for a few seconds, so the banner can name it. */
    public static String recentFallback() {
        if (!STRICT) return "";
        if (lastFallback.isEmpty() || System.currentTimeMillis() - lastFallbackAt > 6000) return "";
        return lastFallback;
    }

    /** -Dforge.ui3d.nofocus=false puts Forge's focus behaviour back. */
    private static final boolean STOP_FOCUS_THEFT =
        !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.nofocus", "true"));
    private static volatile boolean focusDisarmed;

    /**
     * Stop Forge's window pulling itself in front of the 3D one.
     *
     * CPrompt focuses its OK button whenever a decision arrives - it keeps a `focusOnEnable`
     * listener and a `lastFocusedButton` for exactly that. Focusing a component raises its
     * window, so every new prompt yanked the player out of the window they were playing in,
     * several times a turn.
     *
     * A button that is not focusable cannot be focused, so the request becomes a no-op and the
     * window stays where it is. Clicking Forge's buttons still works, which matters because
     * Forge's window is the fallback for everything not yet taken over; only keyboard focus is
     * given up, and the 3D window has its own keys.
     *
     * Done once, lazily, because the prompt's buttons do not exist until a match is built.
     */
    /**
     * Take Forge's global keyboard hook off the JVM.
     *
     * FControl is a KeyEventDispatcher registered on the KeyboardFocusManager, so it sees
     * every key pressed anywhere in this JVM whichever window has focus. That is how a
     * keystroke meant for the 3D table ended four of the owner's turns through Forge's
     * "End Turn" menu item (2026-10-21), and it is the same hook that types into Forge while
     * you are typing a bug report.
     *
     * While the 3D table is the window being played in, Forge's window is a fallback that
     * should answer questions, not listen for keys. Removing the dispatcher leaves its menus
     * and buttons working by mouse - only the global key handling goes.
     *
     * -Dforge.ui3d.unhook=false leaves it in place.
     */
    private static boolean keysUnhooked;

    static void unhookForgeKeys() {
        if (keysUnhooked || "false".equalsIgnoreCase(System.getProperty("forge.ui3d.unhook", "true"))) return;
        keysUnhooked = true;
        try {
            Class<?> fc = Class.forName("forge.control.FControl");
            Object inst = fc.getField("instance").get(null);
            if (!(inst instanceof java.awt.KeyEventDispatcher)) return;
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher((java.awt.KeyEventDispatcher) inst);
            System.out.println("UI3D: Forge's global key hook removed - its menus still work by mouse");
        } catch (Throwable t) {
            System.out.println("UI3D: could not remove Forge's global key hook (" + t + ")");
        }
    }

    private void disarmFocusTheft() {
        if (!STOP_FOCUS_THEFT || focusDisarmed) return;
        try {
            Object cprompt = original.getClass().getMethod("getCPrompt").invoke(original);
            if (cprompt == null) return;
            Object view = cprompt.getClass().getMethod("getView").invoke(cprompt);
            if (view == null) return;
            for (String getter : new String[] { "getBtnOK", "getBtnCancel" }) {
                Object btn = view.getClass().getMethod(getter).invoke(view);
                if (btn instanceof javax.swing.JComponent) {
                    ((javax.swing.JComponent) btn).setFocusable(false);
                }
            }
            focusDisarmed = true;
            System.out.println("UI3D: Forge's prompt buttons will no longer grab focus");
        } catch (Throwable t) {
            // Not fatal: the window just keeps raising itself, as it did before.
        }
    }

    /** Copies prompt, buttons and selectables into PROMPT for the 3D window to draw. */
    private void observe(String name, Object[] args) {
        if (remoteMode) observeRemote(name, args);
        observeLocal(name, args);
        // A joined game has no Input to use as the click token: every new prompt gets a fresh
        // one instead, so a click pressed before the prompt changed is dropped (remoteClick).
        if (remoteMode && ("showPromptMessage".equals(name) || "updateButtons".equals(name) || "setSelectables".equals(name))) {
            PROMPT.token = new Object();
            try { PROMPT.inputName = inferInput(original.getGameView()); } catch (Throwable t) { /* next rebuild sets it */ }
        }
    }

    /** The calls that set a joined game up: its controller, our seat, the window, day / night. */
    private void observeRemote(String name, Object[] args) {
        switch (name) {
            case "setOriginalGameController":
            case "setGameController":
                if (args != null && args.length == 2 && args[1] instanceof forge.interfaces.IGameController) {
                    remote = (forge.interfaces.IGameController) args[1];
                    if (remoteViewer == null && args[0] instanceof forge.game.player.PlayerView) {
                        remoteViewer = (forge.game.player.PlayerView) args[0];
                    }
                }
                break;
            case "openView":
                if (args != null && args.length >= 1 && args[0] instanceof Iterable) {
                    for (Object o : (Iterable<?>) args[0]) {
                        if (o instanceof forge.game.player.PlayerView) { remoteViewer = (forge.game.player.PlayerView) o; break; }
                    }
                }
                Bridge.openWindow();
                break;
            case "setGameView":
                Bridge.openWindow();
                break;
            case "updateDayTime":
                if (args != null && args.length >= 1) remoteDay = String.valueOf(args[0]);
                break;
            default:
                break;
        }
    }

    private void observeLocal(String name, Object[] args) {
        switch (name) {
            case "showPromptMessage":
                if (args != null && args.length >= 2 && args[1] instanceof String) {
                    PROMPT.message = (String) args[1];
                }
                PROMPT.token = ClickGuard.token(pch);
                PROMPT.inputName = PROMPT.token == null ? "" : PROMPT.token.getClass().getSimpleName();
                break;
            case "updateButtons":
                // (PlayerView, String okLabel, String cancelLabel, boolean ok, boolean cancel, boolean focus)
                if (args != null && args.length >= 6 && args[1] instanceof String) {
                    PROMPT.okLabel = (String) args[1];
                    PROMPT.cancelLabel = (String) args[2];
                    PROMPT.okEnabled = Boolean.TRUE.equals(args[3]);
                    PROMPT.cancelEnabled = Boolean.TRUE.equals(args[4]);
                    PROMPT.allowEnter = Boolean.TRUE.equals(args[5]);
                } else if (args != null && args.length >= 4) {
                    // the shorter overload: (PlayerView, boolean, boolean, boolean)
                    PROMPT.okEnabled = Boolean.TRUE.equals(args[1]);
                    PROMPT.cancelEnabled = Boolean.TRUE.equals(args[2]);
                    PROMPT.allowEnter = Boolean.TRUE.equals(args[3]);
                }
                PROMPT.token = ClickGuard.token(pch);
                PROMPT.inputName = PROMPT.token == null ? "" : PROMPT.token.getClass().getSimpleName();
                break;
            case "setSelectables": {
                // Both the ids (for the renderer, which must not hold Forge objects) and the
                // CardViews themselves (for selectCard, which needs the object not the id).
                List<Integer> ids = new ArrayList<>();
                java.util.Map<Integer, CardView> views = new java.util.LinkedHashMap<>();
                if (args != null && args.length >= 1 && args[0] instanceof Iterable) {
                    for (Object o : (Iterable<?>) args[0]) {
                        if (!(o instanceof CardView)) continue;
                        CardView cv = (CardView) o;
                        ids.add(cv.getId());
                        views.put(cv.getId(), cv);
                    }
                }
                PROMPT.selectable = ids;
                selectableViews = views;
                PROMPT.token = ClickGuard.token(pch);
                PROMPT.inputName = PROMPT.token == null ? "" : PROMPT.token.getClass().getSimpleName();
                mirrorOffTable(views.values(), args);
                break;
            }
            case "clearSelectables": {
                PROMPT.selectable = new ArrayList<>();
                selectableViews = new java.util.LinkedHashMap<>();
                Peek p = peeking;
                if (p != null && p.fromSelectables) peeking = null;
                heldSelectables = null;
                break;
            }
            case "openZones":
                peekZone(args);
                break;
            case "hideRevealedCards": {
                // Forge is done showing them: close our mirror of showRevealedCards (only that one).
                Peek p = peeking;
                if (p != null && REVEALED_TITLE.equals(p.title)) peeking = null;
                break;
            }
            case "message":
            case "reveal":
                // Both put a modal box in Forge's window. Noting it is the whole point: without
                // this the 3D window sits there looking idle while the game waits on a dialog
                // the player may not even have noticed.
                if (args != null && args.length >= 1 && args[0] instanceof String) {
                    PROMPT.forgeDialog = (String) args[0];
                    PROMPT.forgeDialogAt = System.currentTimeMillis();
                }
                break;
            default:
                break;
        }
    }
}
