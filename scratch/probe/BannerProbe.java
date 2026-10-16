public class BannerProbe { public static void main(String[] a) throws Exception {
  java.lang.reflect.Method m = PlayHook.TurnBanner.class.getDeclaredMethod("show", String.class); m.setAccessible(true);
  javax.swing.SwingUtilities.invokeAndWait(() -> { try { m.invoke(null, "YOUR TURN  (preview)"); } catch (Exception e) { e.printStackTrace(); } });
  Thread.sleep(3000); System.out.println("banner shown"); System.exit(0); } }
