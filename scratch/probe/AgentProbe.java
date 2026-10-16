public class AgentProbe {
    public static void main(String[] a) throws Exception {
        Class.forName("forge.ai.ComputerUtil"); Class<?> c = Class.forName("forge.ai.AiBlockController");
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) if (m.getName().startsWith("assignBlockersForCombat")) System.out.println("METHOD " + m);
    }
}
