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

    private String lastSaved;
    private long lastSavedAt;
    private volatile boolean asking;

    /** Screen rect of the cog, so the click test and the drawing cannot drift apart. */
    private final float[] cog = { 0f, 0f, 0f, 0f };

    /**
     * Opens a real text box and writes the report when it is answered.
     *
     * The first version drew its own box and collected characters from libGDX's keyTyped. It
     * opened, and then nothing could be typed into it - and an in-window text field also has to
     * reimplement a caret, selection, backspace and paste before it is as good as the one every
     * other program already has. libGDX provides getTextInput for exactly this, which on desktop
     * puts up a proper dialog, so the typing problem and the readability problem both go away by
     * not writing a text field at all.
     */
    public void ask(final Snapshot snap) {
        if (asking) return;
        asking = true;
        // A Swing dialog, not libGDX's getTextInput. getTextInput is declared on Input but the
        // LWJGL3 backend does not implement it - it silently does nothing, which is exactly what
        // happened: the cog opened no box and logged no error. Forge is a Swing application in
        // this same JVM, so JOptionPane is available, gives a real text field with a caret and
        // paste, and parents onto the game window.
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.JTextArea area = new javax.swing.JTextArea(5, 44);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(area);
                Object[] content = {
                    "What went wrong? The board state is attached automatically.", scroll
                };
                // Built by hand rather than showConfirmDialog, so it can be put on top. The 3D
                // window is a GLFW window, not a Swing one, so Swing cannot parent to it - and
                // without alwaysOnTop the box opens behind the 3D view, which is where the owner
                // kept finding it: "this dialogue opened on forge ui, not the 3d ui".
                javax.swing.JOptionPane pane = new javax.swing.JOptionPane(content,
                    javax.swing.JOptionPane.PLAIN_MESSAGE, javax.swing.JOptionPane.OK_CANCEL_OPTION);
                javax.swing.JDialog dlg = pane.createDialog(parentWindow(), "Report a bug");
                dlg.setAlwaysOnTop(true);
                dlg.toFront();
                area.requestFocusInWindow();
                dlg.setVisible(true);
                Object picked = pane.getValue();
                int r = (picked instanceof Integer) ? (Integer) picked
                    : javax.swing.JOptionPane.CANCEL_OPTION;
                dlg.dispose();
                if (r == javax.swing.JOptionPane.OK_OPTION) submit(snap, area.getText());
            } catch (Throwable t) {
                System.out.println("UI3D: could not open the bug box (" + t + ")");
            } finally {
                asking = false;
            }
        });
    }

    /** The biggest showing window, so the dialog lands on the game rather than behind it. */
    private static java.awt.Window parentWindow() {
        java.awt.Window best = null;
        long bestArea = -1;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (!(w instanceof java.awt.Frame) || !w.isShowing()) continue;
            long area = (long) w.getWidth() * w.getHeight();
            if (area > bestArea) { bestArea = area; best = w; }
        }
        return best;
    }

    /**
     * True while the bug dialog is open.
     *
     * The 3D window must stop reading the keyboard while it is: libGDX polls GLFW key state
     * rather than consuming events, so it keeps seeing keys even when a Swing dialog has
     * focus - typing a bug report was also passing priority and ending turns.
     */
    public boolean isAsking() {
        return asking;
    }

    /** Is the mouse over the cog right now? */
    public boolean overCog(float mx, float my) {
        return mx >= cog[0] && mx <= cog[0] + cog[2] && my >= cog[1] && my <= cog[1] + cog[3];
    }

    // ---- drawing --------------------------------------------------------------------------------

    /** The cog, top right. Bigger and brighter than the first attempt, which was hard to pick out. */
    public void drawCog(ShapeRenderer shapes, SpriteBatch ui, BitmapFont font, int w, int h, Matrix4 flat) {
        float size = 34f, x = w - size - 16f, y = h - size - 16f;
        cog[0] = x; cog[1] = y; cog[2] = size; cog[3] = size;
        boolean hot = overCog(Gdx.input.getX(), h - Gdx.input.getY());

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(hot ? 0.98f : 0.74f, hot ? 0.30f : 0.16f, hot ? 0.24f : 0.14f, 1f);
        shapes.rect(x, y, size, size);
        shapes.setColor(0.03f, 0.08f, 0.12f, 1f);
        shapes.rect(x + 14f, y + 5f, 6f, size - 10f);
        shapes.rect(x + 5f, y + 14f, size - 10f, 6f);
        shapes.setColor(1f, hot ? 0.80f : 0.62f, hot ? 0.70f : 0.52f, 1f);
        shapes.rect(x + 13f, y + 13f, 8f, 8f);
        shapes.end();
    }

    /** The hover label and the saved confirmation. */
    public void drawCogText(SpriteBatch ui, BitmapFont font, int w, int h) {
        boolean hot = overCog(Gdx.input.getX(), h - Gdx.input.getY());
        if (hot) {
            font.setColor(1f, 0.78f, 0.70f, 1f);
            font.draw(ui, "report a bug", w - 150f, h - 58f);
        }
        if (lastSaved != null && System.currentTimeMillis() - lastSavedAt < 8000) {
            font.setColor(0.55f, 0.92f, 0.62f, 1f);
            font.draw(ui, "bug report saved: " + lastSaved, w - 400f, h - 76f);
        }
    }

    // ---- writing --------------------------------------------------------------------------------

    /** Writes the report and closes the box. Returns the file, or null if it could not be written. */
    public File submit(Snapshot snap, String typed) {
        String what = typed == null ? "" : typed.trim();
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

        // Which decisions Forge is still making. The single most useful line for working out
        // why the other window keeps taking over, and it costs nothing to record.
        String fb = Gui3D.fallbackSummary();
        String nn = System.lineSeparator() + System.lineSeparator();
        w.write("## Decisions Forge still answered this game" + nn);
        w.write((fb.isEmpty() ? "_none - the 3D window handled everything asked of it_" : fb) + nn);

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
