package mtg3d;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * "Forge just stole my attention" - one button, pressed by the player at the moment it happens.
 *
 * The ATTENTION lines ForgeWindow writes say when Forge's window came FORWARD. That is not the
 * same question. A window can come forward behind the 3D one and never be noticed, and Forge can
 * take the eye without its window moving at all - a dialog, a beep, the taskbar flashing. The
 * only authority on whether attention was actually stolen is the person whose attention it was.
 *
 * So this records the human judgement, timestamped, next to the machine's record of what the
 * window did. Where the two line up, the cause is known. Where a flag has no ATTENTION line near
 * it, something is taking the eye that nothing in this codebase is yet watching - which is the
 * more interesting finding of the two.
 *
 * The line goes to stdout, so it lands in the same console log as the ATTENTION lines, in order,
 * and scripts\attention-report.ps1 reads both out of the one file. It is also appended to a file
 * of its own, so a session logged with the console closed still has its flags.
 *
 * Nothing here touches the game.
 */
final class Attention {

    private Attention() {}

    private static int count;
    private static long lastFlagAt;
    private static File file;

    /** How many times the player has said it happened this session - drawn on the button. */
    static int count() {
        return count;
    }

    /** Milliseconds since the last flag, for the button's acknowledgement flash. */
    static long sinceLast() {
        return lastFlagAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastFlagAt;
    }

    /**
     * Record one. Called from the button and from the key.
     *
     * Everything on the line is what would otherwise have to be reconstructed afterwards from a
     * time of day: the turn and phase, what Forge was asking, which input it was in, and whether
     * ForgeWindow had logged a raise it could explain in the last few seconds. That last field
     * is the one that matters - "stolen, and we know why" is a fixable call in Gui3D, "stolen,
     * and nothing we watch moved" is a new lead.
     */
    static synchronized void flag(Snapshot s) {
        try {
            count++;
            lastFlagAt = System.currentTimeMillis();
            String when = new SimpleDateFormat("HH:mm:ss").format(new Date());
            String turn = s == null || s.turnLabel == null ? "" : s.turnLabel;
            String phase = s == null || s.phase == null ? "" : s.phase;
            String prompt = oneLine(Gui3D.PROMPT.message);
            String input = Gui3D.PROMPT.inputName == null || Gui3D.PROMPT.inputName.isEmpty()
                ? "[none]" : Gui3D.PROMPT.inputName;
            String recent = ForgeWindow.recentRaise();
            String line = "UI3D: STOLEN #" + count + " " + when
                + " | " + turn + " " + phase
                + " | window: " + (recent.isEmpty() ? "nothing logged in the last 10s" : recent)
                + " | prompt=\"" + prompt + "\""
                + " | input=" + input;
            System.out.println(line);
            append(line);
        } catch (Throwable ignored) {
            // a flag that fails to write must never take the game with it
        }
    }

    private static void append(String line) {
        try {
            if (file == null) {
                File dir = dir();
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                file = new File(dir, "attention-flags_"
                    + new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date()) + ".log");
            }
            try (Writer w = new FileWriter(file, true)) {
                w.write(line + System.lineSeparator());
            }
        } catch (Throwable ignored) {
            // stdout already has it
        }
    }

    /** Beside the play logs, which is where the ATTENTION lines they pair with end up. */
    private static File dir() {
        String given = System.getProperty("forge.playlog.dir");
        if (given != null && !given.isEmpty()) return new File(given);
        File rel = new File("../scratch/play-logs");
        return rel.getParentFile().isDirectory() ? rel : new File("scratch/play-logs");
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 110 ? t.substring(0, 107) + "..." : t;
    }
}
