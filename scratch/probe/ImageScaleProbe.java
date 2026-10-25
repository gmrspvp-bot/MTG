/**
 * ImageScaleHook (2026-10-26): with the agent, ImageCache.scaleImage asked for a picture 3 pixels high (width worked out: 2)
 * returns null instead of throwing "Error doing rescale"; a normal size still gets its picture. Without the agent the 2x3 call throws. On the Swing thread, as Forge requires.
 * Run from forge-installer-2.0.15 with the agent, the tools classes and the Forge jar.
 */
public class ImageScaleProbe {
    public static void main(String[] a) throws Exception {
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.model.FModel.initialize(null, null);
        boolean tinyOk, normalOk;
        final String key = "t:c_3_3_a_robot|PIP|16|1";      // a token picture known to be cached
        final boolean[] r = new boolean[2];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
        try { r[0] = forge.ImageCache.scaleImage(key, -1, 3, false, null) == null; }
        catch (RuntimeException e) { System.out.println("tiny threw: " + e.getMessage()); r[0] = false; }
        try { r[1] = forge.ImageCache.scaleImage(key, 120, 168, false, null) != null; }
        catch (RuntimeException e) { System.out.println("normal threw: " + e); r[1] = false; }
        });
        tinyOk = r[0]; normalOk = r[1];
        System.out.println("3 high -> no image without an error: " + tinyOk + ", 120x168 -> handled by Forge: " + normalOk);
        System.out.println(tinyOk && normalOk ? "PASS" : "FAIL");
        System.exit(tinyOk && normalOk ? 0 : 1);
    }
}
