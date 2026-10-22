import java.lang.reflect.Method;
import forge.player.PlayerControllerHuman;

/**
 * ActionableCardsHook is installed (2026-10-22): with the agent, PlayerControllerHuman has
 * pushActionableCards$orig, and pushActionableCards(boolean) is now a forwarder into the hook.
 * Run from forge-installer-2.0.15 with -javaagent:..\scripts\tools\evalcache-agent.jar and the
 * tools classes and Forge jar on the classpath.
 */
public class ActionableHookProbe {
    public static void main(String[] a) throws Exception {
        boolean orig = false, fwd = false;
        for (Method m : PlayerControllerHuman.class.getDeclaredMethods()) {
            if (m.getName().equals("pushActionableCards$orig") && m.getParameterCount() == 1) orig = true;
            if (m.getName().equals("pushActionableCards") && m.getParameterCount() == 1) fwd = true;
        }
        // The hook resolves the renamed original the way it will in a game.
        boolean resolves;
        try {
            java.lang.invoke.MethodHandles.lookup().findVirtual(PlayerControllerHuman.class, "pushActionableCards$orig",
                java.lang.invoke.MethodType.methodType(void.class, boolean.class));
            resolves = true;
        } catch (Throwable t) { resolves = false; }
        System.out.println("orig renamed: " + orig + ", forwarder: " + fwd + ", hook can reach the original: " + resolves);
        System.out.println(orig && fwd && resolves ? "PASS" : "FAIL");
        System.exit(orig && fwd && resolves ? 0 : 1);
    }
}
