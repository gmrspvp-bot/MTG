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

    /**
     * The kinds of thing that go wrong, as buttons.
     *
     * The cog used to open a Swing text box, and that was the wrong shape for the job (the owner,
     * 2026-10-28). A bug happens mid-turn, with the game waiting on somebody; stopping to type a
     * paragraph in a window that is not even the 3D one means either the moment passes or the
     * report never gets written. One click on the right label is a report, filed where it
     * happened, with the whole board attached - and the board is the part that has actually
     * solved these, not the prose.
     *
     * The label is what the button says; the slug goes in the file name so a folder of reports
     * can be read, sorted and grepped by kind without opening any of them.
     *
     * Order matters: the ones that have actually bitten us are at the top, where they can be hit
     * without reading the list.
     */
    static final String[][] KINDS = {
        { "No chance to respond",   "response"   },
        { "Turn / phase skipped",   "skipped"    },
        { "Priority is wrong",      "priority"   },
        { "Click refused",          "refused"    },
        { "Creature ability",       "creature"   },
        { "Triggered ability",      "trigger"    },
        { "Activated ability",      "activated"  },
        { "Targeting",              "targeting"  },
        { "Mana / payment",         "mana"       },
        { "Attacking",              "attack"     },
        { "Blocking",               "block"      },
        { "Combat damage",          "damage"     },
        { "Stack / resolution",     "stack"      },
        { "Search / library",       "search"     },
        { "Counters / tokens",      "counters"   },
        { "Copy / clone",           "copy"       },
        { "Card text is wrong",     "cardtext"   },
        { "Card picture missing",   "art"        },
        { "Something on screen",    "display"    },
        { "Network / out of sync",  "network"    },
        { "Crash or freeze",        "crash"      },
        { "Describe it myself...",  "typed"      },
    };

    private static final float ROW_H = 21f, MENU_W = 196f, PAD = 6f;

    private String lastSaved;
    private long lastSavedAt;
    private volatile boolean asking;

    /** Screen rect of the FLAG button, so the click test and the drawing cannot drift apart. */
    private final float[] cog = { 0f, 0f, 0f, 0f };

    /**
     * Is the list of kinds on screen?
     *
     * It stays up until it is closed. That is the whole point of it: the owner asked for a menu
     * that "needs to stay on the 3d screen", so that when something goes wrong the report is one
     * click away rather than a click to open plus a click to file. It is deliberately NOT a panel
     * in TableBase.panelOpen()'s sense - the game carries on underneath it, keys still work, and
     * cards behind it are still live; it only eats clicks that land on itself.
     */
    private boolean menuOpen;

    /** Screen rect of the open menu, for the click test and for keeping clicks off the board. */
    private final float[] menu = { 0f, 0f, 0f, 0f };

    /** The row the mouse is on, or -1, worked out while drawing and reused by the click test. */
    private int hoverRow = -1;

    /** What the last flag was, so the confirmation can name it. */
    private String lastKind = "";

    // ---- the menu -------------------------------------------------------------------------------

    /** Is the list of kinds on screen? */
    public boolean isMenuOpen() { return menuOpen; }

    /** Open or shut the list. */
    public void toggleMenu() { menuOpen = !menuOpen; }

    /** Shut the list. */
    public void closeMenu() { menuOpen = false; }

    /** Is the mouse over the open menu? Used to keep a click off the board underneath it. */
    public boolean overMenu(float mx, float my) {
        return menuOpen && mx >= menu[0] && mx <= menu[0] + menu[2]
            && my >= menu[1] && my <= menu[1] + menu[3];
    }

    /**
     * Which row the mouse is on, or -1. Worked out while drawing so the hit test and the
     * highlight can never disagree about where a row is.
     */
    public int rowAt(float mx, float my) {
        if (!overMenu(mx, my)) return -1;
        int i = (int) ((menu[1] + menu[3] - PAD - my) / ROW_H);
        return i >= 0 && i < KINDS.length ? i : -1;
    }

    /** The label of a row, for a caller that wants to name it. */
    public static String label(int row) {
        return row >= 0 && row < KINDS.length ? KINDS[row][0] : "";
    }

    /** The file-name slug of a row. */
    public static String slug(int row) {
        return row >= 0 && row < KINDS.length ? KINDS[row][1] : "other";
    }

    /** Is this the row that asks for words rather than filing straight away? */
    public static boolean isTypedRow(int row) {
        return "typed".equals(slug(row));
    }

    /**
     * File a report of this kind, now, with no questions asked.
     *
     * One click is the whole interaction. `note` is whatever the player chose to add, which for
     * every row but the last is nothing at all - and that is fine, because the board state is
     * the part that has actually solved these.
     */
    public File flag(Snapshot snap, int row, String note) {
        return submit(snap, note, label(row), slug(row));
    }

    /**
     * True while something else owns the keyboard on the 3D window's behalf.
     *
     * There is no longer anything that can set this: the Swing box is gone (2026-10-28, the
     * owner: "if this new window defaults to forge ui i will not be pleased"). Every kind files
     * on the click, and the one row that wants words opens the 3D window's own TextPanel, which
     * blocks keys itself. Kept because the input loop asks, and because it says plainly in one
     * place that nothing here leaves the 3D window any more.
     */
    public boolean isAsking() {
        return asking;
    }

    /** Is the mouse over the cog right now? */
    public boolean overCog(float mx, float my) {
        return mx >= cog[0] && mx <= cog[0] + cog[2] && my >= cog[1] && my <= cog[1] + cog[3];
    }

    // ---- drawing --------------------------------------------------------------------------------

    /**
     * The FLAG button, top right, and the list of kinds under it when it is open.
     *
     * The cog it replaces was a picture of a cog that opened a window somewhere else. This says
     * what it does and the thing it opens is right underneath it, in this window.
     */
    public void drawCog(ShapeRenderer shapes, SpriteBatch ui, BitmapFont font, int w, int h, Matrix4 flat) {
        float bw = 76f, bh = 26f, x = w - bw - 16f, y = h - bh - 14f;
        cog[0] = x; cog[1] = y; cog[2] = bw; cog[3] = bh;
        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        boolean hot = overCog(mx, my);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Brighter while the list is open, so it reads as a thing that is currently switched on.
        float r = menuOpen ? 0.95f : hot ? 0.88f : 0.62f;
        shapes.setColor(r, menuOpen ? 0.34f : hot ? 0.28f : 0.15f, menuOpen ? 0.26f : hot ? 0.22f : 0.13f, 1f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(0.03f, 0.08f, 0.12f, 1f);
        shapes.rect(x + 2f, y + 2f, bw - 4f, bh - 4f);
        shapes.end();

        if (menuOpen) drawMenuBox(shapes, w, h, flat, mx, my);
    }

    /** The list itself: one row per kind, hanging off the bottom of the button. */
    private void drawMenuBox(ShapeRenderer shapes, int w, int h, Matrix4 flat, float mx, float my) {
        float mh = KINDS.length * ROW_H + PAD * 2f;
        float x = w - MENU_W - 16f, y = cog[1] - 6f - mh;
        menu[0] = x; menu[1] = y; menu[2] = MENU_W; menu[3] = mh;
        hoverRow = rowAt(mx, my);

        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Nearly opaque. It sits over the board and the board must not read through the labels,
        // which is the mistake the hover panel made before it was given a solid backing.
        shapes.setColor(0.04f, 0.06f, 0.09f, 0.95f);
        shapes.rect(x, y, MENU_W, mh);
        shapes.setColor(0.62f, 0.15f, 0.13f, 1f);
        shapes.rect(x, y + mh - 2f, MENU_W, 2f);
        if (hoverRow >= 0) {
            float ry = y + mh - PAD - (hoverRow + 1) * ROW_H;
            shapes.setColor(0.70f, 0.20f, 0.17f, 1f);
            shapes.rect(x + 3f, ry, MENU_W - 6f, ROW_H - 1f);
        }
        shapes.end();
    }

    /** The button's word, the row labels, the hover hint and the confirmation. */
    public void drawCogText(SpriteBatch ui, BitmapFont font, int w, int h) {
        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        boolean hot = overCog(mx, my);
        font.setColor(1f, hot || menuOpen ? 0.86f : 0.70f, hot || menuOpen ? 0.80f : 0.62f, 1f);
        font.draw(ui, menuOpen ? "FLAG  x" : "FLAG", cog[0] + 14f, cog[1] + 18f);
        if (hot && !menuOpen) {
            font.setColor(1f, 0.78f, 0.70f, 1f);
            font.draw(ui, "flag a bug  (F2, or Back)", w - 240f, cog[1] - 6f);
        }
        if (menuOpen) {
            float x = menu[0] + 10f, top = menu[1] + menu[3] - PAD;
            for (int i = 0; i < KINDS.length; i++) {
                boolean on = i == hoverRow;
                if (isTypedRow(i)) font.setColor(on ? 1f : 0.62f, on ? 0.95f : 0.68f, on ? 0.80f : 0.52f, 1f);
                else font.setColor(on ? 1f : 0.80f, on ? 0.94f : 0.76f, on ? 0.92f : 0.74f, 1f);
                font.draw(ui, KINDS[i][0], x, top - i * ROW_H - 5f);
            }
        }
        if (lastSaved != null && System.currentTimeMillis() - lastSavedAt < 8000) {
            font.setColor(0.55f, 0.92f, 0.62f, 1f);
            font.draw(ui, "flagged: " + lastKind + "  ->  " + lastSaved,
                w - 460f, menuOpen ? menu[1] - 10f : cog[1] - 26f);
        }
    }

    // ---- writing --------------------------------------------------------------------------------

    /** Writes the report. Returns the file, or null if it could not be written. */
    public File submit(Snapshot snap, String typed) {
        return submit(snap, typed, "Unsorted", "other");
    }

    /**
     * Writes the report under a kind.
     *
     * The kind is in the file name as well as in the text, so `scratch/bug-reports` sorts itself
     * into piles and a morning's worth of flags can be read by eye: six `_response` files in one
     * game says something that one of them on its own does not.
     */
    public File submit(Snapshot snap, String typed, String kindLabel, String kindSlug) {
        String what = typed == null ? "" : typed.trim();
        if (what.length() > MAX_CHARS) what = what.substring(0, MAX_CHARS);
        try {
            File dir = reportDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date());
            File f = new File(dir, "bug_" + stamp + "_" + kindSlug + ".md");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                write(w, what, snap, stamp, kindLabel, kindSlug);
            }
            lastSaved = f.getName();
            lastKind = kindLabel;
            lastSavedAt = System.currentTimeMillis();
            System.out.println("UI3D: flagged [" + kindSlug + "] -> " + f.getAbsolutePath());
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

    private void write(Writer w, String what, Snapshot s, String stamp,
                       String kindLabel, String kindSlug) throws Exception {
        w.write("# " + kindLabel + " - " + stamp + "\n\n");
        w.write("- kind: `" + kindSlug + "`\n\n");
        w.write("## What happened\n\n");
        w.write((what.isEmpty()
            ? "_Flagged from the table with one click - everything below is what the board"
              + " looked like at that moment._"
            : what) + "\n\n");

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
            + "\n");
        // Which Forge input is live. Without this, "I had priority and nothing worked" cannot be
        // told apart from "the 3D window thought I had priority and Forge disagreed" - and the
        // second is what the 2026-10-20 turn-50 report turned out to be: nine refused clicks on
        // the owner's own main phase, with both buttons disabled while the panel said he held
        // priority. An input of "none" means Forge was not asking this player for anything.
        w.write("- Forge's live input: `" + Gui3D.liveInputName() + "`"
            + (Gui3D.liveInputName().equals("none")
                ? "  **(Forge was not waiting on you - clicks will be refused)**" : "")
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

        // Forge's alerts, split into the priority nudge and the genuine refusal. Here because
        // the split is a judgement the 3D side makes (Gui3D, the alertUser case) and a judgement
        // nobody can check is a judgement nobody should trust: if a real refusal ever does go
        // missing, this is the line that will show it.
        w.write("## Forge's alerts" + nn);
        w.write(Gui3D.alertSplit() + nn);

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
        if (c.phasedOut) b.append(" [PHASED OUT]");
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
