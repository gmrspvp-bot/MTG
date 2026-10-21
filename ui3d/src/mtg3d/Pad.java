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

            if (lx != 0f || ly != 0f) {
                used = true;
                moveCursor(lx * CURSOR_SPEED * dt, ly * CURSOR_SPEED * dt);
            }
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
     * Move the real pointer, clamped to the window. libGDX's y runs down from the top here,
     * which is the same direction the stick's y reports, so no flip is needed.
     */
    private static void moveCursor(float dx, float dy) {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        int x = Math.round(Math.max(0, Math.min(w - 1, Gdx.input.getX() + dx)));
        int y = Math.round(Math.max(0, Math.min(h - 1, Gdx.input.getY() + dy)));
        Gdx.input.setCursorPosition(x, y);
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

    /** A is the click: held, so the existing "was the button down last frame" tests work. */
    public static boolean clickHeld() {
        return down(GLFW.GLFW_GAMEPAD_BUTTON_A);
    }
}
