// The first method Forge no longer sees - does it always get answered?
//
// Gui3D now answers confirm() itself. That means Forge's window never shows the question, so if
// the 3D window cannot answer it there is no dialog anywhere and the game thread sits blocked
// inside confirm() for good. Every other failure in this project is cosmetic next to that.
//
// Four cases, which are the four ways a confirm can end:
//   1. the player answers in 3D        -> that answer is used, Forge is never asked
//   2. nobody answers, it times out    -> Forge is asked, the game continues
//   3. the 3D window is not drawing    -> Forge is asked immediately, not after the timeout
//   4. the player presses H            -> Forge is asked
//
//   javac -cp "<desktop jar>;ui3d\classes" -d scratch\probe\classes scratch\probe\Ui3dConfirmTest.java
//   java -Dforge.ui3d.handoff.ms=800 -cp "<desktop jar>;ui3d\classes;scratch\probe\classes" Ui3dConfirmTest

import forge.gui.interfaces.IGuiGame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

public class Ui3dConfirmTest {

    static boolean pass = true;
    static final AtomicBoolean forgeAsked = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // Forge's window: records that it was asked, and always answers "no", so a true result
        // can only have come from the 3D side.
        IGuiGame real = (IGuiGame) Proxy.newProxyInstance(
            Ui3dConfirmTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (p, m, a) -> {
                if (m.getName().equals("confirm")) { forgeAsked.set(true); return Boolean.FALSE; }
                return defaultValue(m.getReturnType());
            });
        IGuiGame wrapped = wrap(real);

        AtomicBoolean beating = new AtomicBoolean(true);
        Thread beat = new Thread(() -> {
            while (beating.get()) { mtg3d.Handoff.alive(); sleep(40); }
        });
        beat.setDaemon(true);
        beat.start();
        sleep(120);

        // 1. answered in 3D
        forgeAsked.set(false);
        new Thread(() -> { waitForAsk(); mtg3d.Gui3D.answerAsk(true); }).start();
        boolean r1 = wrapped.confirm(null, "Use this ability?");
        check("answered in 3D", r1 && !forgeAsked.get(), "result=" + r1 + " forgeAsked=" + forgeAsked.get());

        // 2. nobody answers -> Forge is asked
        forgeAsked.set(false);
        long t0 = System.currentTimeMillis();
        boolean r2 = wrapped.confirm(null, "Nobody will answer this");
        long took = System.currentTimeMillis() - t0;
        check("timeout falls back to Forge", !r2 && forgeAsked.get() && took >= 700,
            "result=" + r2 + " forgeAsked=" + forgeAsked.get() + " after " + took + "ms");

        // 3. window not drawing -> Forge asked straight away
        beating.set(false);
        sleep(2300);
        forgeAsked.set(false);
        t0 = System.currentTimeMillis();
        boolean r3 = wrapped.confirm(null, "Window is dead");
        took = System.currentTimeMillis() - t0;
        check("dead window goes straight to Forge", !r3 && forgeAsked.get() && took < 400,
            "forgeAsked=" + forgeAsked.get() + " after " + took + "ms");
        beating.set(true);
        Thread b2 = new Thread(() -> { while (beating.get()) { mtg3d.Handoff.alive(); sleep(40); } });
        b2.setDaemon(true);
        b2.start();
        sleep(120);

        // 4. the player hands it back
        forgeAsked.set(false);
        new Thread(() -> { waitForAsk(); mtg3d.Handoff.handBack(); }).start();
        t0 = System.currentTimeMillis();
        boolean r4 = wrapped.confirm(null, "Hand this one back");
        took = System.currentTimeMillis() - t0;
        check("hand-back goes to Forge", !r4 && forgeAsked.get() && took < 1200,
            "forgeAsked=" + forgeAsked.get() + " after " + took + "ms");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** Waits until Gui3D is showing a question, the way the render thread would see it. */
    static void waitForAsk() {
        for (int i = 0; i < 200; i++) {
            if (mtg3d.Gui3D.pendingAsk() != null) return;
            sleep(10);
        }
    }

    static IGuiGame wrap(IGuiGame real) throws Exception {
        java.lang.reflect.Constructor<?> c = Class.forName("mtg3d.Gui3D")
            .getDeclaredConstructor(IGuiGame.class, forge.player.PlayerControllerHuman.class);
        c.setAccessible(true);
        InvocationHandler h = (InvocationHandler) c.newInstance(real, null);
        return (IGuiGame) Proxy.newProxyInstance(
            Ui3dConfirmTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, h);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-34s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return (char) 0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        return null;
    }
}
