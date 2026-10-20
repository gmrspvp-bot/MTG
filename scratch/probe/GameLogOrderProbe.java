import forge.game.*;

/** Which way round GameLog hands entries back: added A then B, does the list start with A or B? */
public class GameLogOrderProbe {
    public static void main(String[] a) {
        GameLog log = new GameLog();
        log.add(GameLogEntryType.STACK_ADD, "first added");
        log.add(GameLogEntryType.STACK_ADD, "second added");
        System.out.println("all:    " + log.getAllEntries());
        System.out.println("medium: " + log.getLogEntriesForVerbosity(GameLogVerbosity.MEDIUM));
    }
}
