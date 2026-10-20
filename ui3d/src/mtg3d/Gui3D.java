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
                                "getInteger", "order", "assignCombatDamage"));

    private final IGuiGame original;
    private final forge.player.PlayerControllerHuman pch;

    private Gui3D(IGuiGame original, forge.player.PlayerControllerHuman pch) {
        this.original = original;
        this.pch = pch;
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
        Peek(String title, java.util.List<Snapshot.CardV> items) {
            this.title = title;
            this.items = items;
        }
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
            forge.game.player.PlayerView owner = (forge.game.player.PlayerView) args[0];
            if (!(args[1] instanceof Iterable)) return;
            forge.game.player.PlayerView viewer = pch == null ? null : pch.getLocalPlayerView();
            java.util.List<Snapshot.CardV> items = new ArrayList<>();
            StringBuilder zones = new StringBuilder();
            for (Object z : (Iterable<?>) args[1]) {
                if (!(z instanceof forge.game.zone.ZoneType)) continue;
                forge.game.zone.ZoneType zone = (forge.game.zone.ZoneType) z;
                if (zones.length() > 0) zones.append(", ");
                zones.append(zone);
                Iterable<CardView> cards = owner.getCards(zone);
                if (cards == null) continue;
                for (CardView cv : cards) {
                    Snapshot.CardV v = new Snapshot.CardV();
                    v.id = cv.getId();
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
            if (!items.isEmpty()) {
                peeking = new Peek(owner.getName() + " - " + zones, items);
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
                ClickGuard.watch(pch);
                checkTakenOver();
                System.out.println("UI3D: input wrapper installed (forwarding everything)");
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
        if (TAKEN_OVER.contains(method.getName())) {
            Object answered = take(method, args);
            if (answered != NOT_HANDLED) return answered;
        }
        try {
            return method.invoke(original, args);
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
        forge.player.PlayerControllerHuman c = controller;
        if (c == null || PROMPT.token == null) return false;
        ClickGuard.ok(c, PROMPT.token);
        return true;
    }

    /** Cancel, with the same guard. */
    public static boolean pressCancel() {
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
                if (!taken) System.out.println("UI3D: Forge refused the click on " + cv.getName());
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

    /** True once a game is running with the wrapper installed. */
    public static boolean active() {
        return controller != null;
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
        OrderAsk(String title, String caption, java.util.List<Snapshot.CardV> items, java.util.List<Object> entities,
                 Handoff<java.util.List<Object>> h) {
            this.title = title; this.caption = caption; this.items = items; this.entities = entities; this.handoff = h;
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
        final java.util.List<CardView> views;
        final Handoff<int[]> handoff;
        DamageAsk(Snapshot.CardV attacker, java.util.List<Snapshot.CardV> blockers, int[] lethal, int damage,
                  java.util.List<CardView> views, Handoff<int[]> h) {
            this.attacker = attacker; this.blockers = blockers; this.lethal = lethal; this.damage = damage;
            this.views = views; this.handoff = h;
        }
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
            if (args.length < 4 || args[3] != null) return NOT_HANDLED;
            if (!(args[0] instanceof CardView) || !(args[1] instanceof java.util.List) || !(args[2] instanceof Integer)) return NOT_HANDLED;
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
            damageAsk = new DamageAsk(describe(att, att.getName()), bl, lethal, dmg, views, h);
            int[] out;
            try {
                out = h.awaitOr(() -> null);
            } finally {
                damageAsk = null;
            }
            if (out == null || out.length != views.size()) return NOT_HANDLED;
            java.util.Map<CardView, Integer> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < views.size(); i++) map.put(views.get(i), out[i]);
            return map;
        } catch (Throwable t) {
            damageAsk = null;
            return NOT_HANDLED;
        }
    }

    // ---- game-level actions from the 3D window ------------------------------------------------------

    /** Rematch / next game / quit, from the end screen: IGameController.nextGameDecision. */
    public static void nextGame(String what) {
        final forge.player.PlayerControllerHuman c = controller;
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
        if (c == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { c.concede(); } catch (Throwable t) { System.out.println("UI3D: concede failed (" + t + ")"); }
        });
    }

    /** Undo the last action, where Forge allows it (tapping a land for mana, mostly). */
    public static void undo() {
        final forge.player.PlayerControllerHuman c = controller;
        if (c == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try { c.undoLastAction(); } catch (Throwable t) { System.out.println("UI3D: undo failed (" + t + ")"); }
        });
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
                break;
            }
            case "clearSelectables":
                PROMPT.selectable = new ArrayList<>();
                selectableViews = new java.util.LinkedHashMap<>();
                break;
            case "openZones":
                peekZone(args);
                break;
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
