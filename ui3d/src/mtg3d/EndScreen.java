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
 * The end of a game: who won, in how many turns, every player's final life (or OUT) and the most
 * commander damage they took, and three buttons - Rematch, Next game, Quit. It replaces the plain
 * GAME OVER band when shown; ESC (or Close) dismisses it so the final board can still be studied,
 * and the band stays as the reminder.
 *
 * Presentation only: the buttons call a {@link Choice} with "rematch" / "next" / "quit", so
 * Forge's next-game decision (IGameController.nextGameDecision) can be wired to it later.
 */
public final class EndScreen {

    public interface Choice { void chose(String what); }

    private static final float OPEN_RATE = 4.0f, W = 760f;

    private boolean open, closing;
    private float anim;
    private Snapshot snap;
    private Choice choice;
    private int hoverBtn = -1;
    private boolean prevTouch;
    private static final String[] BUTTONS = { "Rematch", "Next game", "Quit" };
    private static final String[] KEYS = { "rematch", "next", "quit" };

    public void open(Snapshot s, Choice c) {
        snap = s;
        choice = c;
        open = true;
        closing = false;
        anim = 0f;
        prevTouch = true;
    }

    public boolean isOpen() { return open; }

    private boolean live() { return open && !closing && anim > 0.85f; }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open || snap == null) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        int n = snap.seats.size();
        float rowH = 34f, h = 210f + n * rowH + 90f;
        float px = (sw - W) / 2f, py = (sh - h) / 2f - (1f - t) * 140f;
        float btnW = (W - 80f) / 3f, btnH = 50f, btnY = py + 26f;
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        if (live()) handleInput(mx, my, px, btnY, btnW, btnH);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.50f * t);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.08f, 0.12f, 0.97f * t);
        shapes.rect(px, py, W, h);
        shapes.setColor(0.95f, 0.72f, 0.22f, t);
        shapes.rect(px, py + h - 5f, W, 5f);
        float tableTop = py + h - 150f;
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            boolean won = !snap.winner.isEmpty() && s.name.equals(snap.winner);
            if (won) shapes.setColor(0.26f, 0.20f, 0.07f, t);
            else if (s.isYou) shapes.setColor(0.06f, 0.18f, 0.24f, t);
            else continue;
            shapes.rect(px + 20f, tableTop - (i + 1) * rowH, W - 40f, rowH - 4f);
        }
        for (int b = 0; b < 3; b++) {
            float bx = px + 20f + b * (btnW + 20f);
            boolean primary = b == 0;
            float k = hoverBtn == b ? 1.35f : 1f;
            if (primary) shapes.setColor(0.12f * k, 0.42f * k, 0.26f * k, t);
            else shapes.setColor(0.08f * k, 0.20f * k, 0.28f * k, t);
            shapes.rect(bx, btnY, btnW, btnH);
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        boolean youWon = false;
        for (Snapshot.SeatV s : snap.seats) if (s.isYou && s.name.equals(snap.winner)) youWon = true;
        String head = snap.winner.isEmpty() ? "A draw" : youWon ? "You win!" : snap.winner + " wins";
        fontBig.getData().setScale(2.6f);
        fontBig.setColor(1f, 0.86f, 0.42f, t);
        gl.setText(fontBig, head);
        fontBig.draw(ui, head, px + (W - gl.width) / 2f, py + h - 30f);
        fontBig.getData().setScale(1.6f);
        String turns = snap.turnLabel;
        int dash = turns.indexOf(" - ");
        if (dash > 0) turns = turns.substring(0, dash);
        font.setColor(0.62f, 0.76f, 0.88f, t);
        gl.setText(font, "Game over after " + turns.toLowerCase());
        font.draw(ui, "Game over after " + turns.toLowerCase(), px + (W - gl.width) / 2f, py + h - 98f);
        font.setColor(0.45f, 0.58f, 0.68f, t);
        font.draw(ui, "PLAYER", px + 34f, tableTop + 18f);
        font.draw(ui, "LIFE", px + 360f, tableTop + 18f);
        font.draw(ui, "MOST COMMANDER DAMAGE TAKEN", px + 440f, tableTop + 18f);
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float y = tableTop - i * rowH - 10f;
            boolean won = !snap.winner.isEmpty() && s.name.equals(snap.winner);
            Color c = won ? new Color(1f, 0.88f, 0.50f, t) : s.lost ? new Color(0.55f, 0.48f, 0.48f, t)
                : s.isYou ? new Color(0.70f, 0.95f, 1f, t) : new Color(0.85f, 0.90f, 0.95f, t);
            font.setColor(c);
            font.draw(ui, s.name + (s.isYou ? "  (you)" : "") + (won ? "  - winner" : ""), px + 34f, y);
            font.draw(ui, s.lost ? "OUT" : String.valueOf(s.life), px + 360f, y);
            int most = 0;
            String from = "";
            for (Snapshot.CmdDmg d : s.commanderDamage) if (d.amount > most) { most = d.amount; from = d.from; }
            // The commander's name up to its comma: "Captain America", not the whole epithet.
            if (from.contains(",")) from = from.substring(0, from.indexOf(','));
            font.draw(ui, most > 0 ? most + "  from " + from : "-", px + 440f, y);
        }
        for (int b = 0; b < 3; b++) {
            float bx = px + 20f + b * (btnW + 20f);
            fontBig.setColor(0.92f, 0.98f, 1f, t);
            gl.setText(fontBig, BUTTONS[b]);
            fontBig.draw(ui, BUTTONS[b], bx + (btnW - gl.width) / 2f, btnY + btnH / 2f + gl.height / 2f);
        }
        font.setColor(0.40f, 0.52f, 0.62f, t);
        gl.setText(font, "ESC closes this and leaves the final board to look at");
        font.draw(ui, "ESC closes this and leaves the final board to look at", px + (W - gl.width) / 2f, btnY - 8f);
        ui.end();
    }

    private void handleInput(float mx, float my, float px, float btnY, float btnW, float btnH) {
        hoverBtn = -1;
        for (int b = 0; b < 3; b++) {
            float bx = px + 20f + b * (btnW + 20f);
            if (mx >= bx && mx <= bx + btnW && my >= btnY && my <= btnY + btnH) hoverBtn = b;
        }
        if (Keys3D.just(Input.Keys.ESCAPE)) { closing = true; return; }
        boolean touch = Pad.touch();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;
        if (clicked && hoverBtn >= 0) {
            closing = true;
            Choice c = choice;
            choice = null;
            if (c != null) c.chose(KEYS[hoverBtn]);
        }
    }
}
