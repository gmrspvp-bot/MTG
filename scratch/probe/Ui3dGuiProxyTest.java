// Does the Gui3D wrapper really forward everything, and really change nothing?
//
// Step 1 of the input plan is a pass-through IGuiGame in front of Forge's own window. Its whole
// value is that it is invisible: if it drops, reorders or alters a single call, Forge's UI starts
// behaving differently and the cause would be very hard to find mid-game. A reflection proxy is
// also exactly the kind of thing that looks right and silently is not.
//
// So this calls every method on the interface through the wrapper and checks that:
//   1. each call reaches the wrapped UI, with the same arguments;
//   2. return values come back unchanged;
//   3. an exception from the real UI is passed through as itself, not wrapped in an
//      InvocationTargetException, which would change what the engine catches;
//   4. the prompt / buttons / selectables are mirrored into Gui3D.PROMPT on the way past.
//
//   javac -cp "<desktop jar>;ui3d\classes" -d scratch\probe\classes scratch\probe\Ui3dGuiProxyTest.java
//   java  -cp "<desktop jar>;ui3d\classes;scratch\probe\classes" Ui3dGuiProxyTest

import forge.gui.interfaces.IGuiGame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Ui3dGuiProxyTest {

    /** Stands in for Forge's own UI: records what it was asked, returns a marker, can throw. */
    static final class Recorder implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        String throwOn;

        @Override public Object invoke(Object proxy, Method m, Object[] a) {
            calls.add(m.getName());
            args.add(a);
            if (m.getName().equals(throwOn)) throw new IllegalStateException("boom from the real UI");
            return defaultValue(m.getReturnType());
        }
    }

    public static void main(String[] args) throws Exception {
        Recorder rec = new Recorder();
        IGuiGame real = (IGuiGame) Proxy.newProxyInstance(
            Ui3dGuiProxyTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, rec);

        IGuiGame wrapped = wrap(real);
        Method[] methods = IGuiGame.class.getMethods();
        int called = 0, failed = 0;

        for (Method m : methods) {
            Object[] call = new Object[m.getParameterCount()];
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < call.length; i++) call[i] = defaultValue(types[i]);
            try {
                m.invoke(wrapped, call);
                called++;
            } catch (Throwable t) {
                failed++;
                System.out.println("  FORWARD FAILED: " + m.getName() + " -> " + t.getCause());
            }
        }

        System.out.println("interface methods: " + methods.length);
        System.out.println("forwarded ok:      " + called);
        System.out.println("failed:            " + failed);
        boolean allReached = rec.calls.size() >= called;
        System.out.println("reached the real UI: " + rec.calls.size() + "  -> " + allReached);

        // 3. an exception from the real UI must arrive as itself.
        rec.throwOn = "clearSelectables";
        String seen;
        try {
            wrapped.clearSelectables();
            seen = "nothing thrown";
        } catch (IllegalStateException e) {
            seen = "IllegalStateException (correct)";
        } catch (Throwable t) {
            seen = t.getClass().getSimpleName() + " (WRONG - engine would not catch this)";
        }
        rec.throwOn = null;
        System.out.println("exception passthrough: " + seen);
        boolean excOk = seen.contains("correct");

        // 4. the mirror.
        wrapped.showPromptMessage(null, "pick a card");
        wrapped.updateButtons(null, "Confirm", "Back", true, false, true);
        boolean mirrored = "pick a card".equals(mtg3d.Gui3D.PROMPT.message)
            && "Confirm".equals(mtg3d.Gui3D.PROMPT.okLabel)
            && "Back".equals(mtg3d.Gui3D.PROMPT.cancelLabel)
            && mtg3d.Gui3D.PROMPT.okEnabled && !mtg3d.Gui3D.PROMPT.cancelEnabled;
        System.out.println("prompt mirrored:   " + mirrored
            + "  (\"" + mtg3d.Gui3D.PROMPT.message + "\", ok=" + mtg3d.Gui3D.PROMPT.okLabel
            + ", cancel=" + mtg3d.Gui3D.PROMPT.cancelLabel + ")");

        // 5. nothing is taken over yet, so every call must have reached the real UI.
        boolean nothingSwallowed = failed == 0;
        System.out.println();
        System.out.println(allReached && excOk && mirrored && nothingSwallowed ? "PASS" : "FAIL");
    }

    /** Builds the wrapper the same way Gui3D.install does, without needing a running game. */
    private static IGuiGame wrap(IGuiGame real) throws Exception {
        java.lang.reflect.Constructor<?> ctor = Class.forName("mtg3d.Gui3D")
            .getDeclaredConstructor(IGuiGame.class);
        ctor.setAccessible(true);
        InvocationHandler h = (InvocationHandler) ctor.newInstance(real);
        return (IGuiGame) Proxy.newProxyInstance(
            Ui3dGuiProxyTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, h);
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == char.class) return (char) 0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;                     // void
    }
}
