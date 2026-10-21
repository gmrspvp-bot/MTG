package mtg3d;

import com.badlogic.gdx.Gdx;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * A game controller, read straight from GLFW.
 *
 * GLFW's gamepad API is used rather than libGDX's controllers extension for two reasons: it needs
 * no jar that is not already here (the rule for this repo is that a clone runs without fetching
 * anything), and it reports an Xbox-style mapping that GLFW has already normalised, so an Xbox
 * pad, a PlayStation pad and most generic ones all arrive as the same buttons without a per-pad
 * mapping table to maintain.
 *
 * The pad drives the REAL mouse cursor rather than a cursor of its own. Everything in the table
 * already picks cards, pads and buttons from the cursor position, so moving the actual pointer
 * means every one of those works from the pad with no second code path to keep in step - which is
 * the mistake that would otherwise be found months later, when one panel had been taught about
 * the pad and another had not.
 *
 * -Dforge.ui3d.pad=false ignores controllers entirely.
 */
public final class Pad {
    private Pad() {}

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.pad", "true"));

    /** How fast the stick pushes the pointer, in pixels per second at full tilt. */
    private static final float CURSOR_SPEED = 1100f;
    /** Sticks rest slightly off centre; below this they are treated as centred. */
    private static final float DEAD = 0.22f;

    private static GLFWGamepadState state;
    private static int jid = -1;
    private static boolean present;
    private static final boolean[] was = new boolean[GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1];
    private static final boolean[] now = new boolean[GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1];
    private static float lx, ly, rx, ry, lt, rt;
    private static boolean announced;
    /** Set once the pad has actually moved something, so the hint can say it is in use. */
    private static boolean used;

    public static boolean connected() {
        return present;
    }

    public static boolean inUse() {
        return used;
    }

    /**
     * Read the pad once, and move the pointer with the left stick. Call once a frame, first,
     * before anything looks at the cursor.
     */
    public static void poll(float dt) {
        if (!ENABLED) return;
        try {
            if (state == null) state = GLFWGamepadState.create();
            // Re-scan every frame: a pad plugged in mid-game should just start working, and
            // scanning sixteen slots is nothing next to drawing a frame.
            present = false;
            for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_LAST; j++) {
                if (!GLFW.glfwJoystickPresent(j) || !GLFW.glfwJoystickIsGamepad(j)) continue;
                if (!GLFW.glfwGetGamepadState(j, state)) continue;
                jid = j;
                present = true;
                break;
            }
            if (!present) { java.util.Arrays.fill(now, false); java.util.Arrays.fill(was, false); return; }
            if (!announced) {
                announced = true;
                System.out.println("UI3D: controller connected (" + GLFW.glfwGetGamepadName(jid) + ")");
            }

            System.arraycopy(now, 0, was, 0, now.length);
            for (int b = 0; b <= GLFW.GLFW_GAMEPAD_BUTTON_LAST; b++) {
                now[b] = state.buttons(b) == GLFW.GLFW_PRESS;
            }
            lx = dead(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
            ly = dead(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
            rx = dead(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
            ry = dead(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
            // Triggers rest at -1 and go to +1; 0..1 is easier to think about.
            lt = (state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) + 1f) / 2f;
            rt = (state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) + 1f) / 2f;

            if (lx != 0f || ly != 0f) used = true;
        } catch (Throwable t) {
            // A controller that cannot be read must never stop the game being played with a mouse.
            present = false;
        }
    }

    private static float dead(float v) {
        if (v > -DEAD && v < DEAD) return 0f;
        // Rescale so the first movement past the dead zone is gentle rather than a jump.
        float s = (Math.abs(v) - DEAD) / (1f - DEAD);
        return v < 0 ? -s : s;
    }

    /**
     * The left stick as a flick rather than a slide: one step per push, and it must come back
     * near centre before it steps again.
     *
     * The first version drove the mouse pointer round the screen with the stick. It worked and it
     * felt wrong - a controller should snap between the things you can act on, not push a cursor
     * at them (the owner, 2026-10-21). So the stick reports steps, and the table moves a
     * selection by them.
     *
     * @return -1, 0 or +1
     */
    public static int flickX() {
        return flick(leftX(), 0);
    }

    public static int flickY() {
        return flick(leftY(), 1);
    }

    private static final boolean[] armed = { true, true };
    private static final float FLICK_ON = 0.6f, FLICK_OFF = 0.35f;

    private static int flick(float v, int axis) {
        if (Math.abs(v) < FLICK_OFF) armed[axis] = true;
        if (!armed[axis] || Math.abs(v) < FLICK_ON) return 0;
        armed[axis] = false;
        used = true;
        return v < 0 ? -1 : 1;
    }

    /** True on the frame the button went down. */
    public static boolean just(int button) {
        if (!present || Keys3D.blocked()) return false;
        boolean v = button >= 0 && button < now.length && now[button] && !was[button];
        if (v) used = true;
        return v;
    }

    /** True while the button is held. */
    public static boolean down(int button) {
        return present && !Keys3D.blocked() && button >= 0 && button < now.length && now[button];
    }

    public static float leftX()  { return present && !Keys3D.blocked() ? lx : 0f; }
    public static float leftY()  { return present && !Keys3D.blocked() ? ly : 0f; }
    public static float rightX() { return present && !Keys3D.blocked() ? rx : 0f; }
    public static float rightY() { return present && !Keys3D.blocked() ? ry : 0f; }
    public static float leftTrigger()  { return present && !Keys3D.blocked() ? lt : 0f; }
    public static float rightTrigger() { return present && !Keys3D.blocked() ? rt : 0f; }

    /**
     * The one place anything asks "is a click being held?".
     *
     * The panels all tested Gdx.input.isTouched() directly, so wiring A to the mouse BUTTON
     * left every panel - mulligan, search, options, order, numbers, damage - unclickable from
     * the pad (the owner, 2026-10-21). Same lesson as Keys3D: one gate that everything reads,
     * or half the code learns about the new input and half does not.
     */
    public static boolean touch() {
        return Gdx.input.isTouched() || clickHeld();
    }

    /**
     * Pad buttons standing in for keys.
     *
     * Read by Keys3D, so every key the table already understands works from the pad without any
     * panel knowing a controller exists. This is the third time the lesson has been paid for
     * today - the mouse button, then the panels' touch test - so the mapping lives in exactly
     * one place and everything else keeps asking the same question it always asked.
     *
     *   D-pad      the arrow keys       Start   ENTER (confirm)      B   ESCAPE (close / back)
     *
     * A is deliberately NOT ENTER: it is the click, and a button that both clicked whatever was
     * under the pointer and confirmed the highlighted item would do two things at once in any
     * panel where several cards are being chosen.
     */
    /**
     * True while a panel is up. A panel takes the whole screen and has its own buttons, so the
     * face buttons mean "the buttons on this panel"; on the board they mean "the card I have
     * selected". The two never overlap, so one pair of buttons can serve both.
     */
    private static boolean panelMode;

    public static void panelMode(boolean on) {
        panelMode = on;
    }

    static boolean justAs(int key) {
        // While a panel is open, A and X are its two buttons. Panels answer SPACE and X already
        // - the mulligan keeps on SPACE and mulligans on X, the prompt bar's main button is
        // SPACE - so the pad does not need anything of its own, and a panel that gains a button
        // later gets the controller for free.
        if (panelMode) {
            if (key == com.badlogic.gdx.Input.Keys.SPACE) return just(GLFW.GLFW_GAMEPAD_BUTTON_A);
            if (key == com.badlogic.gdx.Input.Keys.X) return just(GLFW.GLFW_GAMEPAD_BUTTON_X);
        }
        switch (key) {
            case com.badlogic.gdx.Input.Keys.LEFT:   return just(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
            case com.badlogic.gdx.Input.Keys.RIGHT:  return just(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT);
            case com.badlogic.gdx.Input.Keys.UP:     return just(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP);
            case com.badlogic.gdx.Input.Keys.DOWN:   return just(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
            case com.badlogic.gdx.Input.Keys.ENTER:  return just(GLFW.GLFW_GAMEPAD_BUTTON_START);
            case com.badlogic.gdx.Input.Keys.ESCAPE: return just(GLFW.GLFW_GAMEPAD_BUTTON_B);
            default: return false;
        }
    }

    /** The held form of the same mapping. */
    static boolean downAs(int key) {
        switch (key) {
            case com.badlogic.gdx.Input.Keys.LEFT:   return down(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
            case com.badlogic.gdx.Input.Keys.RIGHT:  return down(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT);
            case com.badlogic.gdx.Input.Keys.UP:     return down(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP);
            case com.badlogic.gdx.Input.Keys.DOWN:   return down(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
            default: return false;
        }
    }

    /** A is the click: held, so the existing "was the button down last frame" tests work. */
    public static boolean clickHeld() {
        return down(GLFW.GLFW_GAMEPAD_BUTTON_A);
    }
}
