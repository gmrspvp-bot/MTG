package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

/**
 * The keys, on screen, behind a HELP button next to the bug cog.
 *
 * The controls had grown to the point where the one-line strip along the bottom could not hold
 * them and nobody could be expected to remember them: walking round the table, the views, the
 * saved views, the phase keys, the panels. A line of grey text under the board is where keys go
 * to be ignored, so this is a card you pull down and read (the owner, 2026-10-21).
 *
 * Deliberately NOT a list of everything the code can do - only what someone playing needs, in the
 * order they will want it. A help screen that documents every switch is read once and then never
 * again.
 */
public final class HelpCard {

    /** Left column, right column. A blank left entry is a heading in the right one. */
    private static final String[][] KEYS = {
        { "", "PLAYING" },
        { "SPACE", "the main button - answer, let the stack resolve, or end the step" },
        { "N  or  ->", "next phase (also cancels an auto-pass)" },
        { "X", "end turn - passes everything left in the turn" },
        { "click a card", "play it, activate it, or pick it as a target" },
        { "drag a pile", "attacking: the whole pile goes - hold SHIFT to send just one" },
        { "hover a card", "see it large, with counters and what it is attached to" },
        { "G", "pick up the card in your hand you are pointing at, to move it" },
        { "then  <- ->", "place it - SPACE drops it there, ESC puts it back" },
        { "click a GY / EXI pad", "look through that pile - anyone's, both are public" },
        { "", "" },
        { "", "MOVING ABOUT" },
        { "A  /  D", "walk round the table" },
        { "S", "back to your own seat" },
        { "W", "overhead" },
        { "TAB", "a quick look at the other pose; ESC gives the camera back" },
        { "right-drag", "orbit    -    middle-drag  move    -    wheel  zoom" },
        { "", "" },
        { "", "VIEWS" },
        { "NUM 1 - 6", "fixed views: seated, close, back, overhead, high, auto" },
        { "NUM 7 / 8 / 9", "your own saved views" },
        { "SHIFT + 7 / 8 / 9", "save where the camera is standing now" },
        { "", "" },
        { "", "" },
        { "F1  /  F2", "this help    -    report a bug" },
        { "F", "follow the turn on and off" },
        { "", "" },
        { "", "CONTROLLER  (if one is plugged in)" },
        { "left stick", "flick between cards    -    A  play the one picked out" },
        { "on a panel", "A and X are the panel's own two buttons" },
        { "", "the mouse still works - move it and it takes over again" },
        { "D-pad", "the arrow keys - right is NEXT PHASE on the board" },
        { "D-pad up", "pick up the card in your hand you are on, to move it" },
        { "Start", "ENTER / confirm    -    B  ESC / close" },
        { "X (on the board)", "hand back to Forge    -    Y  this help" },
        { "LB / RB", "walk round the table    -    L3  seat    -    R3  overhead" },
        { "right stick", "orbit, and push up for overhead" },
        { "triggers", "zoom in and out" },
        { "Back", "report a bug" },
        { "", "" },
        { "", "OTHER" },
        { "L", "hide the game log    -    M  sound" },
        { "H", "hand the current decision back to Forge's window" },
        { "ESC", "close a panel, or open the menu" },
        { "the red cog", "report a bug - the board state is attached for you" },
    };

    private final float[] btn = { 0f, 0f, 0f, 0f };
    private boolean open;

    public boolean isOpen() {
        return open;
    }

    public void toggle() {
        open = !open;
    }

    public void close() {
        open = false;
    }

    public boolean overButton(float mx, float my) {
        return mx >= btn[0] && mx <= btn[0] + btn[2] && my >= btn[1] && my <= btn[1] + btn[3];
    }

    /**
     * The button, next to the cog. Drawn in the shape pass; the label and the card come later in
     * the sprite pass, because the two cannot be interleaved.
     */
    public void drawButton(ShapeRenderer shapes, int w, int h, Matrix4 flat) {
        float bw = 62f, bh = 34f, x = w - 34f - 16f - bw - 8f, y = h - bh - 16f;
        btn[0] = x; btn[1] = y; btn[2] = bw; btn[3] = bh;
        boolean hot = overButton(Gdx.input.getX(), h - Gdx.input.getY()) || open;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(hot ? 0.22f : 0.12f, hot ? 0.46f : 0.28f, hot ? 0.62f : 0.40f, 1f);
        shapes.rect(x, y, bw, bh);
        shapes.end();
    }

    public void drawButtonText(SpriteBatch ui, BitmapFont font, int w, int h) {
        font.setColor(0.86f, 0.94f, 1f, 1f);
        font.draw(ui, open ? "CLOSE" : "HELP", btn[0], btn[1] + 26f, btn[2], 1, false);
        // The key beside the button, so nothing on screen looks like it needs a mouse.
        font.setColor(0.60f, 0.76f, 0.92f, 1f);
        font.draw(ui, "F1 / Y", btn[0], btn[1] + 12f, btn[2], 1, false);
    }

    /**
     * The card itself, hanging from under the button. Sized to its contents rather than fixed, so
     * adding a key later cannot quietly push the last line off the bottom.
     */
    public void draw(ShapeRenderer shapes, SpriteBatch ui, BitmapFont font, BitmapFont fontBig,
                     int w, int h, Matrix4 flat) {
        if (!open) return;
        final float rowH = 19f, padX = 20f, padTop = 46f, padBottom = 18f;
        float cardW = 580f;
        float cardH = padTop + padBottom + KEYS.length * rowH;
        float x = w - cardW - 16f, y = h - 58f - cardH;
        if (y < 10f) y = 10f;

        ui.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.02f, 0.07f, 0.11f, 0.96f);
        shapes.rect(x, y, cardW, cardH);
        shapes.setColor(0.18f, 0.56f, 0.71f, 1f);
        shapes.rect(x, y + cardH - 4f, cardW, 4f);
        shapes.end();
        ui.begin();

        fontBig.setColor(0.85f, 0.95f, 1f, 1f);
        fontBig.draw(ui, "Controls", x + padX, y + cardH - 16f);

        float ty = y + cardH - padTop;
        for (String[] row : KEYS) {
            if (row[0].isEmpty() && row[1].isEmpty()) { ty -= rowH; continue; }
            if (row[0].isEmpty()) {
                font.setColor(0.98f, 0.78f, 0.38f, 1f);
                font.draw(ui, row[1], x + padX, ty);
            } else {
                font.setColor(0.62f, 0.90f, 1f, 1f);
                font.draw(ui, row[0], x + padX + 10f, ty);
                font.setColor(0.76f, 0.84f, 0.90f, 1f);
                font.draw(ui, row[1], x + padX + 150f, ty);
            }
            ty -= rowH;
        }

        font.setColor(0.45f, 0.58f, 0.68f, 1f);
        GlyphLayout gl = new GlyphLayout(font, "HELP or ESC closes this");
        font.draw(ui, "HELP or ESC closes this", x + cardW - padX - gl.width, y + 14f);
    }
}
