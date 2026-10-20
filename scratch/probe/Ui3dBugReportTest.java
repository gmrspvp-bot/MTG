// Does a bug report actually contain enough to diagnose with?
//
// The point of the cog is that the useful information survives. A report that writes a timestamp
// and the typed text would look like it worked and be worthless, so this builds a snapshot with
// the things that have actually mattered in this project - a card that cannot be clicked, an
// empty selectable list, a stack item, combat - and checks they come out the other side.
//
//   javac -cp ui3d\classes -d scratch\probe\classes scratch\probe\Ui3dBugReportTest.java
//   java  -cp "ui3d\classes;scratch\probe\classes" Ui3dBugReportTest

import mtg3d.BugReport;
import mtg3d.Snapshot;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class Ui3dBugReportTest {
    static boolean pass = true;

    public static void main(String[] args) throws Exception {
        File dir = new File("scratch/probe/bug-out");
        System.setProperty("forge.ui3d.bugs", dir.getPath());

        Snapshot s = new Snapshot();
        s.turnLabel = "Turn 4 - Xenil";
        s.phase = "Main phase, precombat";
        s.phaseKey = "MAIN1";
        s.promptText = "Priority: Xenil";
        s.okLabel = "OK";
        s.okEnabled = true;
        // The land-click bug in one line: Forge offered nothing, which is normal for priority.
        // A report that omitted this would have left the same guessing as before.

        Snapshot.SeatV me = new Snapshot.SeatV();
        me.name = "Xenil"; me.isYou = true; me.active = true; me.hasPriority = true;
        me.life = 40; me.handSize = 7; me.librarySize = 91;
        Snapshot.CardV cmd = new Snapshot.CardV(1, "Seluma, Light of Aysen", "Legendary Creature - Angel", true, false);
        cmd.commander = true; cmd.power = "4"; cmd.toughness = "4"; cmd.counterText.add("+1/+1 x2");
        me.creatures.add(cmd);
        s.seats.add(me);

        Snapshot.CardV land = new Snapshot.CardV(2, "Plains", "Basic Land - Plains", false, true);
        s.hand.add(land);

        Snapshot.CardV onStack = new Snapshot.CardV(3, "Arcane Signet", "Artifact", false, false);
        onStack.controller = "Xenil"; onStack.stackText = "Arcane Signet";
        s.stack.add(onStack);

        BugReport r = new BugReport();
        File f = r.submit(s, "the land will not play");

        if (f == null || !f.isFile()) { System.out.println("FAIL: no file written"); return; }
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        need(text, "the land will not play", "the typed description");
        need(text, "Turn 4 - Xenil", "the turn");
        need(text, "MAIN1", "the phase key");
        need(text, "none - ordinary priority", "that Forge offered no selectable cards");
        need(text, "Seluma", "the commander on the battlefield");
        need(text, "[commander]", "commander marking");
        need(text, "+1/+1 x2", "counters");
        need(text, "Plains", "the card in hand that could not be played");
        need(text, "Arcane Signet", "the stack");
        need(text, "console log", "where to look next");

        System.out.println();
        System.out.println("report: " + f.getAbsolutePath() + "  (" + text.length() + " chars)");
        System.out.println(pass ? "PASS" : "FAIL");
    }

    static void need(String text, String needle, String why) {
        boolean ok = text.contains(needle);
        if (!ok) pass = false;
        System.out.printf("  %-45s %s%n", why, ok ? "ok" : "MISSING: " + needle);
    }
}
