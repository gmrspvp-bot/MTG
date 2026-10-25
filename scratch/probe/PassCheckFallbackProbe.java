/**
 * ConfirmPassHook with the 3D table on but unable to ask (2026-10-25): it must not open Forge's
 * window. Here no game is wired to the 3D table, so Gui3D.askYesNo refuses every time; confirm()
 * should retry for about ASK_WAIT_MS, log "PASSCHECK ... (no game wired to the 3D table)" and
 * let the pass through (true). Headless, so a Swing box would throw instead of appearing.
 * Run from forge-installer-2.0.15 with ui3d\classes, the tools classes and both jars.
 */
public class PassCheckFallbackProbe {
    public static void main(String[] a) throws Exception {
        System.setProperty("forge.ui3d", "true");
        System.setProperty("java.awt.headless", "true");
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        System.setOut(new java.io.PrintStream(buf, true));
        long t0 = System.currentTimeMillis();
        boolean pass = ConfirmPassHook.confirm("You're about to end your turn, but you have mana.\n\nPass anyway?");
        long ms = System.currentTimeMillis() - t0;
        System.setOut(old);
        String out = buf.toString();
        System.out.print(out);
        boolean ok = pass && out.contains("PASSCHECK") && out.contains("no game wired") && ms >= 2800 && ms < 6000;
        System.out.println("passed=" + pass + " after " + ms + " ms");
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
