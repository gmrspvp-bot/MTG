// Starts Forge's mobile/"Adventure" interface (forge-gui-mobile-dev jar, a libGDX UI that also has
// a Classic mode for normal games) after applying the runtime AI additions in AiPatches, like
// SimLauncher does for the desktop window. Used by scripts\play.ps1 -Modern.

public class ModernLauncher {
    public static void main(String[] args) throws Exception {
        AiPatches.apply();
        Class.forName("forge.app.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        // libGDX returns here once its window is closed. Our Swing windows (PlayHook's rollback
        // button) would otherwise keep Java running invisibly in the background.
        System.exit(0);
    }
}
