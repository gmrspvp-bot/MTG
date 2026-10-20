package mtg3d;

import java.io.File;
import java.nio.file.Files;

/**
 * Why card pictures never arrived in the 3D table (owner's list, 2026-10-21), and the fix:
 *   1. a numbered art file ("Plains1.fullborder.jpg") is found for the card "Plains";
 *   2. a picture Forge saves AFTER the window opened is picked up by the background rescan.
 * Uses a temporary folder, no GL needed (only the index is checked, via hasPicture).
 * Run with ui3d\classes and the Forge jars on the classpath.
 */
public class CardArtProbe {
    static boolean pass = true;

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("cardart").toFile();
        File set = new File(root, "TRK");
        set.mkdirs();
        Files.write(new File(set, "Plains1.fullborder.jpg").toPath(), new byte[] { 1 });
        CardArt art = new CardArt(root);
        Thread.sleep(500);                                   // the first index runs on its own thread
        check("Plains1.fullborder.jpg found for Plains", art.hasPicture(new Snapshot.CardV(1, "Plains", "Basic Land - Plains", false, true)), "");

        Snapshot.CardV bolt = new Snapshot.CardV(2, "Lightning Bolt", "Instant", false, false);
        check("no Lightning Bolt yet", !art.hasPicture(bolt), "");
        Files.write(new File(set, "Lightning Bolt.fullborder.jpg").toPath(), new byte[] { 1 });   // Forge downloads it mid-game
        art.get(bolt);                                       // a miss asks for a rescan (rate-limited)
        long t0 = System.currentTimeMillis();
        while (!art.hasPicture(bolt) && System.currentTimeMillis() - t0 < 12000) {
            art.get(bolt);
            Thread.sleep(200);
        }
        check("a picture saved later is picked up", art.hasPicture(bolt), (System.currentTimeMillis() - t0) + " ms");

        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-42s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }
}
