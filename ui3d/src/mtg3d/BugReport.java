package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * "Something went wrong" - typed in the window it went wrong in, with the board attached.
 *
 * Diagnosing why a land could not be played took two screenshots, a guess, and a round trip. The
 * useful information was all on screen at the time and none of it survived. This writes it down
 * instead: what the player typed, plus the turn, the phase, who had priority, every seat's zones
 * and battlefield, the stack, and - the part that actually solved that bug - exactly what Forge
 * was asking for at that moment, including whether it had offered any cards for selection.
 *
 * Reports land in `scratch/bug-reports/`, which is in git, so one of us can read a report the
 * other produced without anything having to be pasted.
 */
public final class BugReport {

    private static final int MAX_CHARS = 600;

    private boolean open;
    private final StringBuilder text = new StringBuilder();
    private String lastSaved;
    private long lastSavedAt;

    /** Screen rect of the cog, so the click test and the drawing cannot drift apart. */
    private final float[] cog = { 0f, 0f, 0f, 0f };

    public boolean isOpen() {
        return open;
    }

    public void toggle() {
        open = !open;
        if (open) text.setLength(0);
    }

    public void close() {
        open = false;
    }

    /** Characters from libGDX's keyTyped. Only meaningful while the box is open. */
    public void type(char c) {
        if (!open) return;
        if (c == '\r' || c == '\n') return;                 // Enter submits, handled by the caller
        if (c == '\b') {
            if (text.length() > 0) text.deleteCharAt(text.length() - 1);
            return;
        }
        if (c < ' ') return;
        if (text.length() < MAX_CHARS) text.append(c);
    }

    /** Is the mouse over the cog right now? */
    public boolean overCog(float mx, float my) {
        return mx >= cog[0] && mx <= cog[0] + cog[2] && my >= cog[1] && my <= cog[1] + cog[3];
    }

    // ---- drawing --------------------------------------------------------------------------------

    /** The cog itself, top right. Drawn every frame whether or not the box is open. */
    public void drawCog(ShapeRenderer shapes, SpriteBatch ui, BitmapFont font, int w, int h, Matrix4 flat) {
        float size = 26f, x = w - size - 14f, y = h - size - 14f;
        cog[0] = x; cog[1] = y; cog[2] = size; cog[3] = size;
        boolean hot = overCog(Gdx.input.getX(), h - Gdx.input.getY()) || open;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(hot ? 0.90f : 0.62f, hot ? 0.22f : 0.14f, hot ? 0.18f : 0.12f, 0.95f);
        shapes.rect(x, y, size, size);
        // Four teeth and a hole: enough to read as a cog at this size without a texture.
        shapes.setColor(0.03f, 0.08f, 0.12f, 1f);
        shapes.rect(x + 11f, y + 4f, 4f, size - 8f);
        shapes.rect(x + 4f, y + 11f, size - 8f, 4f);
        shapes.setColor(hot ? 1f : 0.80f, 0.55f, 0.45f, 1f);
        shapes.rect(x + 10f, y + 10f, 6f, 6f);
        shapes.end();
    }

    /** The box, when open. Call inside a SpriteBatch pass; shapes are drawn first via drawBox. */
    public void drawBoxShapes(ShapeRenderer shapes, int w, int h, Matrix4 flat) {
        if (!open) return;
        float bw = 560f, bh = 220f, x = w - bw - 14f, y = h - bh - 50f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.04f, 0.06f, 0.09f, 0.96f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(0.78f, 0.22f, 0.18f, 1f);
        shapes.rect(x, y + bh - 3f, bw, 3f);
        shapes.setColor(0.02f, 0.10f, 0.14f, 1f);
        shapes.rect(x + 14f, y + 54f, bw - 28f, bh - 96f);     // the text area
        shapes.end();
    }

    public void drawBoxText(SpriteBatch ui, BitmapFont font, BitmapFont fontBig, int w, int h) {
        if (!open) return;
        float bw = 560f, bh = 220f, x = w - bw - 14f, y = h - bh - 50f;

        fontBig.setColor(1f, 0.72f, 0.62f, 1f);
        fontBig.draw(ui, "Report a bug", x + 16f, y + bh - 16f);
        font.setColor(0.62f, 0.70f, 0.78f, 1f);
        font.draw(ui, "What went wrong? The board state is attached automatically.",
            x + 16f, y + bh - 40f);

        font.setColor(0.86f, 0.92f, 0.98f, 1f);
        String shown = text.length() == 0 ? "" : text.toString();
        font.draw(ui, shown + "_", x + 24f, y + bh - 68f, bw - 48f, -1, true);

        font.setColor(0.52f, 0.62f, 0.72f, 1f);
        font.draw(ui, "ENTER saves     ESC cancels     " + text.length() + "/" + MAX_CHARS,
            x + 16f, y + 22f);

        if (lastSaved != null && System.currentTimeMillis() - lastSavedAt < 6000) {
            font.setColor(0.55f, 0.90f, 0.60f, 1f);
            font.draw(ui, "saved: " + lastSaved, x + 16f, y - 6f);
        }
    }

    /** Shown briefly after saving, even once the box has closed. */
    public void drawToast(SpriteBatch ui, BitmapFont font, int w, int h) {
        if (open || lastSaved == null) return;
        if (System.currentTimeMillis() - lastSavedAt > 6000) return;
        font.setColor(0.55f, 0.90f, 0.60f, 1f);
        font.draw(ui, "bug report saved: " + lastSaved, w - 520f, h - 50f);
    }

    // ---- writing --------------------------------------------------------------------------------

    /** Writes the report and closes the box. Returns the file, or null if it could not be written. */
    public File submit(Snapshot snap) {
        String what = text.toString().trim();
        open = false;
        try {
            File dir = reportDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date());
            File f = new File(dir, "bug_" + stamp + ".md");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                write(w, what, snap, stamp);
            }
            lastSaved = f.getName();
            lastSavedAt = System.currentTimeMillis();
            System.out.println("UI3D: bug report written to " + f.getAbsolutePath());
            return f;
        } catch (Throwable t) {
            System.out.println("UI3D: could not write the bug report (" + t + ")");
            return null;
        }
    }

    private static File reportDir() {
        String dir = System.getProperty("forge.ui3d.bugs");
        if (dir != null) return new File(dir);
        // play.ps1 runs Forge with the install folder as the working directory; the demo runs from
        // the repo root.
        File rel = new File("../scratch/bug-reports");
        return rel.getParentFile().isDirectory() ? rel : new File("scratch/bug-reports");
    }

    private void write(Writer w, String what, Snapshot s, String stamp) throws Exception {
        w.write("# Bug report " + stamp + "\n\n");
        w.write("## What happened\n\n");
        w.write((what.isEmpty() ? "_(nothing typed)_" : what) + "\n\n");

        w.write("## Game\n\n");
        w.write("- turn: `" + s.turnLabel + "`\n");
        w.write("- phase: `" + s.phase + "` (`" + s.phaseKey + "`)\n");
        w.write("- combat: " + s.combat + ", targeting: " + s.targeting + "\n");
        w.write("- input wrapper active: " + Gui3D.active() + "\n\n");

        // What Forge was asking. This is the part that solved the land-click bug: the prompt and
        // whether any cards had been offered for selection.
        w.write("## What Forge was asking\n\n");
        w.write("- prompt: `" + oneLine(s.promptText) + "`\n");
        w.write("- OK: `" + s.okLabel + "` (" + (s.okEnabled ? "enabled" : "disabled") + ")"
            + ", Cancel: `" + s.cancelLabel + "` (" + (s.cancelEnabled ? "enabled" : "disabled") + ")\n");
        w.write("- cards Forge offered for selection: " + s.selectableIds.size()
            + (s.selectableIds.isEmpty() ? "  **(none - ordinary priority)**" : " " + s.selectableIds)
            + "\n\n");

        if (!s.stack.isEmpty()) {
            w.write("## Stack (bottom first)\n\n");
            for (Snapshot.CardV c : s.stack) {
                w.write("- " + c.name + " (" + c.controller + ")"
                    + (c.stackText.isEmpty() ? "" : " - " + oneLine(c.stackText)) + "\n");
            }
            w.write("\n");
        }

        if (!s.combatArcs.isEmpty()) {
            w.write("## Combat\n\n");
            for (Snapshot.CombatArc a : s.combatArcs) {
                w.write("- " + a.attackerName + " -> " + a.defenderName
                    + (a.blockedBy.isEmpty() ? " (unblocked)" : " blocked by " + a.blockedBy) + "\n");
            }
            w.write("\n");
        }

        w.write("## Seats\n\n");
        for (Snapshot.SeatV seat : s.seats) {
            w.write("### " + seat.name
                + (seat.isYou ? " (you)" : "") + (seat.active ? " - active" : "")
                + (seat.hasPriority ? " - has priority" : "") + "\n\n");
            w.write("- life " + seat.life + (seat.poison > 0 ? ", poison " + seat.poison : "")
                + ", hand " + seat.handSize + ", library " + seat.librarySize
                + ", graveyard " + seat.graveyardSize + ", exile " + seat.exileSize + "\n");
            if (!seat.commanderDamage.isEmpty()) {
                StringBuilder cd = new StringBuilder();
                for (Snapshot.CmdDmg d : seat.commanderDamage) {
                    if (cd.length() > 0) cd.append(", ");
                    cd.append(d.amount).append(" from ").append(d.from);
                }
                w.write("- commander damage: " + cd + "\n");
            }
            zone(w, "creatures", seat.creatures);
            zone(w, "other permanents", seat.others);
            zone(w, "lands", seat.lands);
            w.write("\n");
        }

        if (!s.hand.isEmpty()) {
            w.write("## Your hand\n\n");
            for (Snapshot.CardV c : s.hand) w.write("- " + describe(c) + "\n");
            w.write("\n");
        }

        w.write("## Where to look\n\n");
        w.write("- console log: `" + System.getProperty("forge.playlog.dir", "scratch/play-logs")
            + "` (newest `console_*.log`)\n");
        w.write("- game log: same folder, newest `game_*.log`\n");
    }

    private void zone(Writer w, String name, List<Snapshot.CardV> cards) throws Exception {
        if (cards.isEmpty()) return;
        w.write("- " + name + ": ");
        StringBuilder b = new StringBuilder();
        for (Snapshot.CardV c : cards) {
            if (b.length() > 0) b.append("; ");
            b.append(describe(c));
        }
        w.write(b + "\n");
    }

    private static String describe(Snapshot.CardV c) {
        StringBuilder b = new StringBuilder(c.faceDown ? "(face down)" : c.name);
        if (!c.manaCost.isEmpty()) b.append(' ').append(c.manaCost);
        if (c.creature && !c.power.isEmpty()) b.append(' ').append(c.power).append('/').append(c.toughness);
        if (c.tapped) b.append(" [tapped]");
        if (c.attacking) b.append(" [attacking]");
        if (c.blocking) b.append(" [blocking]");
        if (c.commander) b.append(" [commander]");
        if (c.token) b.append(" [token]");
        if (!c.attachedTo.isEmpty()) b.append(" [on ").append(c.attachedTo).append(']');
        if (!c.counterText.isEmpty()) b.append(' ').append(c.counterText);
        return b.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
