package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

/**
 * Typing an answer - Forge's free-text showInputDialog, the last of its questions that had no
 * 3D panel (2026-10-21). libGDX's own getTextInput is declared but not implemented by the LWJGL3
 * backend, so this is a plain field of our own: characters arrive through Table3D's input handler
 * (keyTyped / keyDown), ENTER confirms, ESC cancels, BACKSPACE deletes; OK / Cancel are clickable.
 *
 * While it is open the game's own keys are blocked (Keys3D.block), so typing "space" does not
 * also pass priority - the mistake the bug box once made.
 */
public final class TextPanel {

    public interface Result {
        /** The text; null if cancelled. */
        void done(String text);
    }

    private boolean open;
    private String title = "", prompt = "";
    private final StringBuilder text = new StringBuilder();
    private boolean numeric;
    private Result result;
    private float blink;
    private boolean okHover, cancelHover, prevTouch;
    private float bx, by, bw, bh;

    public void open(String title, String prompt, String initial, boolean numeric, Result result) {
        this.title = title == null ? "" : title;
        this.prompt = prompt == null ? "" : prompt;
        text.setLength(0);
        if (initial != null) text.append(initial);
        this.numeric = numeric;
        this.result = result;
        open = true;
        prevTouch = true;
        Keys3D.block(true);
    }

    public boolean isOpen() { return open; }

    /** Shut without answering - the question went back to Forge. */
    public void close() {
        if (!open) return;
        open = false;
        result = null;
        Keys3D.block(false);
    }

    private void finish(boolean cancelled) {
        Result r = result;
        String t = text.toString();
        close();
        if (r != null) r.done(cancelled ? null : t);
    }

    /** From the input handler. True when the character was taken. */
    public boolean typed(char c) {
        if (!open) return false;
        if (c == '\r' || c == '\n') { finish(false); return true; }
        if (c == '\b') { if (text.length() > 0) text.setLength(text.length() - 1); return true; }
        if (c == 27) { finish(true); return true; }
        if (c < 32 || text.length() >= 200) return true;
        if (numeric && !(Character.isDigit(c) || (c == '-' && text.length() == 0))) return true;
        text.append(c);
        return true;
    }

    /** From the input handler: ENTER / ESC also arrive as key presses on some layouts. */
    public boolean key(int keycode) {
        if (!open) return false;
        if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) { finish(false); return true; }
        if (keycode == Input.Keys.ESCAPE) { finish(true); return true; }
        return true;                                         // everything else is ours while open
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig, float dt) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        blink += dt;
        float w = 640f, h = 190f, px = (sw - w) / 2f, py = (sh - h) / 2f;
        bw = 90f; bh = 34f; bx = px + w - 2 * bw - 34f; by = py + 18f;
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        okHover = mx >= bx + bw + 14f && mx <= bx + 2 * bw + 14f && my >= by && my <= by + bh;
        cancelHover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        boolean touch = Pad.touch();
        if (touch && !prevTouch) {
            if (okHover) finish(false);
            else if (cancelHover) finish(true);
        }
        prevTouch = touch;
        if (!open) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f);
        shapes.rect(px, py, w, h);
        shapes.setColor(0.18f, 0.56f, 0.71f, 1f);
        shapes.rect(px, py + h - 4f, w, 4f);
        shapes.setColor(0.07f, 0.17f, 0.24f, 1f);
        shapes.rect(px + 20f, py + 66f, w - 40f, 42f);          // the field
        shapes.setColor(cancelHover ? 0.45f : 0.30f, 0.12f, 0.12f, 1f);
        shapes.rect(bx, by, bw, bh);
        shapes.setColor(0.12f, okHover ? 0.50f : 0.36f, 0.20f, 1f);
        shapes.rect(bx + bw + 14f, by, bw, bh);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        fontBig.setColor(0.85f, 0.95f, 1f, 1f);
        fontBig.draw(ui, title, px + 22f, py + h - 18f);
        font.setColor(0.60f, 0.76f, 0.88f, 1f);
        font.draw(ui, prompt, px + 22f, py + h - 48f, w - 44f, -1, true);
        String shown = text + ((blink % 1f) < 0.55f ? "|" : " ");
        GlyphLayout gl = new GlyphLayout(fontBig, shown);
        // Long text: show its end, so the caret stays in view.
        while (gl.width > w - 64f && shown.length() > 1) { shown = shown.substring(1); gl.setText(fontBig, shown); }
        fontBig.setColor(1f, 1f, 1f, 1f);
        fontBig.draw(ui, shown, px + 32f, py + 98f);
        font.setColor(0.92f, 0.85f, 0.85f, 1f);
        font.draw(ui, "Cancel", bx + 22f, by + 22f);
        font.setColor(0.88f, 1f, 0.90f, 1f);
        font.draw(ui, "OK", bx + bw + 14f + 34f, by + 22f);
        font.setColor(0.45f, 0.60f, 0.72f, 1f);
        font.draw(ui, "type, ENTER to confirm, ESC to cancel" + (numeric ? "  (a number)" : ""), px + 22f, py + 40f);
        ui.end();
    }
}
