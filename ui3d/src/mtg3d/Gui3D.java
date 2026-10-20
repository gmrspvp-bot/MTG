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
        java.util.Arrays.asList("confirm", "reveal"));

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

    /** Copies prompt, buttons and selectables into PROMPT for the 3D window to draw. */
    private void observe(String name, Object[] args) {
        switch (name) {
            case "showPromptMessage":
                if (args != null && args.length >= 2 && args[1] instanceof String) {
                    PROMPT.message = (String) args[1];
                }
                PROMPT.token = ClickGuard.token(pch);
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
                break;
            }
            case "clearSelectables":
                PROMPT.selectable = new ArrayList<>();
                selectableViews = new java.util.LinkedHashMap<>();
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
