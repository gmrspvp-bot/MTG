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
    private static final Set<String> TAKEN_OVER = new LinkedHashSet<>();

    private final IGuiGame original;
    private final forge.player.PlayerControllerHuman pch;

    private Gui3D(IGuiGame original, forge.player.PlayerControllerHuman pch) {
        this.original = original;
        this.pch = pch;
    }

    /** The local human's controller, for sending guarded clicks back. Null until installed. */
    private static volatile forge.player.PlayerControllerHuman controller;

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

    /** True once a game is running with the wrapper installed. */
    public static boolean active() {
        return controller != null;
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
                List<Integer> ids = new ArrayList<>();
                if (args != null && args.length >= 1 && args[0] instanceof Iterable) {
                    for (Object o : (Iterable<?>) args[0]) {
                        if (o instanceof CardView) ids.add(((CardView) o).getId());
                    }
                }
                PROMPT.selectable = ids;
                break;
            }
            case "clearSelectables":
                PROMPT.selectable = new ArrayList<>();
                break;
            default:
                break;
        }
    }
}
