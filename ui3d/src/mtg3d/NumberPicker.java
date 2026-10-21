package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

/**
 * Picking a number: an X cost, "choose a number", how much life to pay. A big number with - and +
 * either side, LEFT / RIGHT (or the wheel, forwarded by the owner) to change it, ENTER to confirm,
 * ESC to cancel - and, for an X cost, a live line saying what that X will actually cost, because
 * the number alone is not what you are deciding.
 *
 * Presentation only: {@link #open} takes the range and a {@link Result} callback (null = cancelled),
 * so IGuiGame.getInteger can be pointed at it later. Same arrival animation as the other panels.
 */
public final class NumberPicker {

    public interface Result {
        void chosen(Integer value);
    }

    /** Turns the value into an extra line, e.g. X=3 -> "you pay {3}{R}{R}". May be null. */
    public interface Hint {
        String describe(int value);
    }

    private static final float OPEN_RATE = 5.0f, W = 460f, H = 250f;

    private boolean open, closing;
    private float anim;
    private String title = "", prompt = "";
    private int min, max, value;
    private Hint hint;
    private Result result;
    private boolean minusHover, plusHover, okHover, cancelHover, prevTouch;

    public void open(String title, String prompt, int min, int max, int initial, Hint hint, Result result) {
        this.title = title == null ? "" : title;
        this.prompt = prompt == null ? "" : prompt;
        this.min = min;
        this.max = Math.max(min, max);
        this.value = Math.max(min, Math.min(this.max, initial));
        this.hint = hint;
        this.result = result;
        open = true;
        closing = false;
        anim = 0f;
        prevTouch = true;
    }

    public boolean isOpen() { return open; }

    /** Shut it without answering - the question went back to Forge (timeout, or H). */
    public void close() {
        if (open && !closing) closing = true;
    }

    private boolean live() { return open && !closing && anim > 0.85f; }

    /** Mouse wheel, forwarded by the owner (libGDX only delivers it through an InputProcessor). */
    public void scrollBy(float amount) {
        if (live()) value = Math.max(min, Math.min(max, value - (int) Math.signum(amount)));
    }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    private void finish(boolean cancelled) {
        Result r = result;
        result = null;
        closing = true;
        if (r != null) r.chosen(cancelled ? null : value);
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        float px = (sw - W) / 2f, py = (sh - H) / 2f - (1f - t) * 120f;
        float cy = py + H / 2f - 6f;                       // centre line of the number row
        float btn = 64f;
        float[] minus = { px + 40f, cy - btn / 2f, btn, btn };
        float[] plus = { px + W - 40f - btn, cy - btn / 2f, btn, btn };

        if (live()) handleInput(minus, plus, px, py, sh);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f * t);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f * t);
        shapes.rect(px, py, W, H);
        shapes.setColor(0.18f, 0.56f, 0.71f, t);
        shapes.rect(px, py + H - 4f, W, 4f);
        shapes.setColor(value > min ? (minusHover ? 0.20f : 0.10f) : 0.05f, value > min ? (minusHover ? 0.46f : 0.26f) : 0.10f,
            value > min ? (minusHover ? 0.60f : 0.34f) : 0.14f, t);
        shapes.rect(minus[0], minus[1], minus[2], minus[3]);
        shapes.setColor(value < max ? (plusHover ? 0.20f : 0.10f) : 0.05f, value < max ? (plusHover ? 0.46f : 0.26f) : 0.10f,
            value < max ? (plusHover ? 0.60f : 0.34f) : 0.14f, t);
        shapes.rect(plus[0], plus[1], plus[2], plus[3]);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.setColor(0.85f, 0.95f, 1f, t);
        fontBig.draw(ui, title, px + 22f, py + H - 20f);
        font.setColor(0.60f, 0.76f, 0.88f, t);
        font.draw(ui, prompt, px + 22f, py + H - 46f);
        fontBig.getData().setScale(2.2f);
        fontBig.setColor(0.88f, 0.95f, 1f, t);
        gl.setText(fontBig, "-");
        fontBig.draw(ui, "-", minus[0] + (btn - gl.width) / 2f, cy + gl.height / 2f);
        gl.setText(fontBig, "+");
        fontBig.draw(ui, "+", plus[0] + (btn - gl.width) / 2f, cy + gl.height / 2f);
        fontBig.getData().setScale(4.0f);
        fontBig.setColor(1f, 0.86f, 0.42f, t);
        String v = String.valueOf(value);
        gl.setText(fontBig, v);
        fontBig.draw(ui, v, px + (W - gl.width) / 2f, cy + gl.height / 2f);
        fontBig.getData().setScale(1.6f);
        String h = hint == null ? "" : hint.describe(value);
        if (h != null && !h.isEmpty()) {
            font.setColor(0.75f, 0.90f, 0.70f, t);
            gl.setText(font, h);
            font.draw(ui, h, px + (W - gl.width) / 2f, cy - 46f);
        }
        font.setColor(0.40f, 0.52f, 0.62f, t);
        font.draw(ui, min + "-" + max + "   arrows / wheel", px + 22f, py + 24f);
        font.setColor(okHover ? new Color(1f, 0.92f, 0.60f, t) : new Color(0.65f, 0.95f, 1f, t));
        font.draw(ui, "Confirm", px + W - 100f, py + 24f);
        font.setColor(cancelHover ? new Color(1f, 0.80f, 0.70f, t) : new Color(0.55f, 0.68f, 0.78f, t));
        font.draw(ui, "Cancel", px + W - 180f, py + 24f);
        ui.end();
    }

    private void handleInput(float[] minus, float[] plus, float px, float py, int sh) {
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        minusHover = in(minus, mx, my);
        plusHover = in(plus, mx, my);
        okHover = mx >= px + W - 105f && mx <= px + W - 30f && my >= py + 8f && my <= py + 32f;
        cancelHover = mx >= px + W - 185f && mx <= px + W - 115f && my >= py + 8f && my <= py + 32f;
        if (Keys3D.just(Input.Keys.ESCAPE)) { finish(true); return; }
        if (Keys3D.just(Input.Keys.ENTER)) { finish(false); return; }
        if (Keys3D.just(Input.Keys.LEFT) || Keys3D.just(Input.Keys.DOWN)) value = Math.max(min, value - 1);
        if (Keys3D.just(Input.Keys.RIGHT) || Keys3D.just(Input.Keys.UP)) value = Math.min(max, value + 1);
        boolean touch = Pad.touch();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;
        if (!clicked) return;
        if (minusHover) value = Math.max(min, value - 1);
        else if (plusHover) value = Math.min(max, value + 1);
        else if (okHover) finish(false);
        else if (cancelHover) finish(true);
    }

    private static boolean in(float[] r, float x, float y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
