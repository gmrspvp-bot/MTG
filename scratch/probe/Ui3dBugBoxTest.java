// Does the bug box actually appear on screen?
//
// The first version used libGDX's getTextInput, which is declared on Input but not implemented
// by the LWJGL3 backend: it silently did nothing. The cog "worked", no error was logged, and no
// box ever appeared - a failure that looks identical to success from the code's side. So this
// checks the only thing that matters: that a window shows up.
//
//   javac -cp ui3d\classes -d scratch\probe\classes scratch\probe\Ui3dBugBoxTest.java
//   java -cp "ui3d\classes;scratch\probe\classes;<mobile jar>" Ui3dBugBoxTest

import mtg3d.BugReport;
import mtg3d.Snapshot;

import java.awt.Window;

public class Ui3dBugBoxTest {
    public static void main(String[] args) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP: headless");
            return;
        }
        int before = showing();
        new BugReport().ask(new Snapshot());

        // The dialog is modal and opens on the EDT, so wait for it to appear from this thread.
        Window dialog = null;
        for (int i = 0; i < 60 && dialog == null; i++) {
            Thread.sleep(100);
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w instanceof java.awt.Dialog) dialog = w;
            }
        }
        System.out.println("windows showing before: " + before);
        System.out.println("dialog appeared:        " + (dialog != null)
            + (dialog == null ? "" : "  title=\"" + ((java.awt.Dialog) dialog).getTitle() + "\""));

        boolean pass = dialog != null && "Report a bug".equals(((java.awt.Dialog) dialog).getTitle());
        if (dialog != null) dialog.dispose();
        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static int showing() {
        int n = 0;
        for (Window w : Window.getWindows()) if (w.isShowing()) n++;
        return n;
    }
}
