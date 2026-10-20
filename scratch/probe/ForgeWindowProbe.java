package mtg3d;

/**
 * ForgeWindow (2026-10-21): a frame standing in for Forge's is minimised, comes back on
 * restore(), and a frame the player minimised themselves is never restored by us.
 * Opens one small frame for about a second.
 */
public class ForgeWindowProbe {
    static boolean pass = true;

    public static void main(String[] a) throws Exception {
        final javax.swing.JFrame f = new javax.swing.JFrame("FORGE STAND-IN");
        javax.swing.SwingUtilities.invokeAndWait(() -> { f.setSize(300, 200); f.setLocation(0, 0); f.setVisible(true); });
        Thread.sleep(300);
        javax.swing.SwingUtilities.invokeAndWait(ForgeWindow::minimiseNow);
        Thread.sleep(300);
        check("minimised", (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0 && ForgeWindow.isMinimised(), "");
        ForgeWindow.restore("probe");
        Thread.sleep(400);
        check("restored", (f.getExtendedState() & java.awt.Frame.ICONIFIED) == 0 && !ForgeWindow.isMinimised(), "");
        // Minimised by the player, not by us: restore() leaves it alone.
        javax.swing.SwingUtilities.invokeAndWait(() -> f.setExtendedState(java.awt.Frame.ICONIFIED));
        ForgeWindow.restore("probe again");
        Thread.sleep(300);
        check("a player's own minimise is left alone", (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0, "");
        // With no 3D game running, askYesNo says so (null) and the caller uses its own box.
        check("askYesNo without a game -> null", Gui3D.askYesNo("Pass anyway?", "Yes", "No") == null, "");
        javax.swing.SwingUtilities.invokeAndWait(f::dispose);
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-40s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }
}
