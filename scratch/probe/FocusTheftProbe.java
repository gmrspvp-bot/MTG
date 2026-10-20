/**
 * Does one of our Swing banners pull Forge's window in front of whatever has focus?
 * (ui3d handover item 1: "stack resolving keeps pulling me to forge GUI".)
 *
 * Opens a frame standing in for Forge's, waits while the caller focuses some other window,
 * then shows the thing under test and prints a marker; the caller reads the foreground window
 * before and after. Modes (first argument):
 *   banner  - PlayHook.showBanner (YOUR TURN / YOUR RESPONSE)
 *   focus   - requestFocusInWindow on a component of the frame (what Forge's showTab does)
 * Run with scripts\tools\classes and the Forge jar on the classpath.
 */
public class FocusTheftProbe {
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "banner";
        final javax.swing.JFrame f = new javax.swing.JFrame("FORGE STAND-IN");
        final javax.swing.JButton b = new javax.swing.JButton("button");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().add(b);
            f.setSize(500, 400);
            f.setLocation(100, 100);
            f.setVisible(true);
        });
        System.out.println("READY");
        Thread.sleep(4000);                      // caller focuses another window now
        System.out.println("ACT " + mode);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            if (mode.equals("banner")) PlayHook.showBanner("YOUR RESPONSE", "test", new java.awt.Color(150, 85, 15), 4000);
            else b.requestFocusInWindow();
        });
        Thread.sleep(2500);
        System.out.println("DONE");
        System.exit(0);
    }
}
