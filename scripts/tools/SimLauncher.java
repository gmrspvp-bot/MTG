// Starts Forge like "java -jar forge.jar <args>", after applying the runtime AI
// additions in AiPatches. Sim mode goes through SimRunner (Forge's sim loop plus
// board-state snapshots in the game log); -Dforge.simrunner=false uses Forge's own.
//
// Usage (from forge-installer-2.0.15):
//   java -cp <classes>;<forge jar> SimLauncher sim -d ... -f Commander ...

public class SimLauncher {
    public static void main(String[] args) throws Exception {
        AiPatches.apply();
        if (args.length > 0 && "sim".equalsIgnoreCase(args[0]) && !"false".equals(System.getProperty("forge.simrunner"))) {
            SimRunner.run(args);
            System.exit(0);
        }
        forge.view.Main.main(args);
    }
}
