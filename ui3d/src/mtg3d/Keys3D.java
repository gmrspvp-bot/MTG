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

    /**
     * The pad is asked here too, so a controller works everywhere a key does.
     *
     * Panels test keys, not devices - SearchPanel's arrows, OptionPanel's paging, NumberPicker's
     * up and down. Teaching each of them about a controller would be the same mistake that left
     * every panel unclickable from the pad this morning, so the mapping lives in Pad.justAs and
     * nothing else has to know.
     */
    public static boolean just(int key) {
        if (blocked) return false;
        return Gdx.input.isKeyJustPressed(key) || Pad.justAs(key);
    }

    public static boolean down(int key) {
        if (blocked) return false;
        return Gdx.input.isKeyPressed(key) || Pad.downAs(key);
    }
}
