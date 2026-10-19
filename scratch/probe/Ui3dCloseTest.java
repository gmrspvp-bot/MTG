import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

// ui3d risk check: does closing the 3D window take the JVM (and so Forge) with it, and can a
// second 3D window be opened afterwards in the same process?
// A Swing JFrame stands in for Forge. The 3D window (Table3D on an empty Snapshot) runs on a
// daemon thread exactly like mtg3d.Bridge.start(), and is closed with Gdx.app.exit() after 3 s.
public class Ui3dCloseTest {
    static volatile boolean closed1, closed2;

    public static void main(String[] a) throws Exception {
        javax.swing.JFrame forge = new javax.swing.JFrame("stand-in for Forge");
        javax.swing.SwingUtilities.invokeAndWait(() -> { forge.setSize(300, 100); forge.setVisible(true); });
        for (int round = 1; round <= 2; round++) {
            final int r = round;
            Thread t = new Thread(() -> {
                try {
                    Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
                    cfg.setTitle("close test " + r);
                    cfg.setWindowedMode(640, 400);
                    new Lwjgl3Application(new mtg3d.Table3D(() -> new mtg3d.Snapshot()), cfg);
                    System.out.println("window " + r + ": Lwjgl3Application returned normally");
                } catch (Throwable e) {
                    System.out.println("window " + r + ": FAILED " + e);
                }
                if (r == 1) closed1 = true; else closed2 = true;
            }, "mtg3d-" + r);
            t.setDaemon(true);
            t.start();
            Thread.sleep(3000);
            Gdx.app.postRunnable(() -> Gdx.app.exit());
            t.join(10000);
            System.out.println("after closing window " + r + ": JVM alive, Forge stand-in visible=" + forge.isVisible()
                + ", window thread finished=" + !t.isAlive());
        }
        Thread.sleep(1000);
        System.out.println("RESULT: both rounds survived; window1 closed=" + closed1 + " window2 closed=" + closed2);
        forge.dispose();
        System.exit(0);
    }
}
