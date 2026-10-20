package mtg3d;

import com.badlogic.gdx.Gdx;

/**
 * Every keyboard read in the 3D window goes through here.
 *
 * libGDX POLLS GLFW key state rather than consuming events, so a Swing dialog having focus does
 * not stop the game seeing keys - typing a bug report was also passing priority and ending turns
 * underneath it. Guarding one call site was not enough either: nine files poll keys, and the
 * panels run outside the input handler that was guarded.
 *
 * So the gate lives at the read itself. Anything that wants a key asks here, and here is the only
 * place that has to know when the window is not really listening.
 */
public final class Keys3D {
    private Keys3D() {}

    private static volatile boolean blocked;

    /** Set while a dialog outside the 3D window owns the keyboard. */
    public static void block(boolean on) {
        blocked = on;
    }

    public static boolean blocked() {
        return blocked;
    }

    public static boolean just(int key) {
        return !blocked && Gdx.input.isKeyJustPressed(key);
    }

    public static boolean down(int key) {
        return !blocked && Gdx.input.isKeyPressed(key);
    }
}
