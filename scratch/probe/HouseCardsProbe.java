import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Mr House card bugs (CARD-MECHANICS.md, 2026-10-21): does Lotho, Corrupt Shirriff trigger on a
 * player's second spell, does Clown Car's enter trigger roll its dice, and how many Mr. House
 * triggers come from a roll that Wyll, Blade of Frontiers doubles. Soul Warden is the control
 * (a trigger known to work). The board is set with GameState (which registers triggers); spells
 * are cast through the real path (ComputerUtil.handlePlayingSpellAbility) by player 0, then
 * waiting triggers are put on the stack and resolved, as a priority pass would.
 *
 *   HouseCardsProbe warden|lotho|clown|house|housewyll
 *
 * Run from forge-installer-2.0.15 with the Forge jar and scripts\tools\classes on the classpath.
 */
public class HouseCardsProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        String which = a.length > 0 ? a[0] : "lotho";
        // Third argument "human": player 0 gets Forge's real human controller (as in a played
        // game), with a stand-in window that answers nothing and prints every question asked.
        boolean human = a.length > 2 && a[2].equals("human");
        // Fourth argument "norestore": Forge's experimental restore off (play.ps1 turns it on).
        if (a.length > 3 && a[3].equals("norestore"))
            FModel.getPreferences().setPref(forge.localinstance.properties.ForgePreferences.FPref.MATCH_EXPERIMENTAL_RESTORE, false);
        // "noautopass": the "Hearthstone feel" auto-pass (play.ps1, 2026-10-20) off.
        if (a.length > 3 && a[3].equals("noautopass"))
            FModel.getPreferences().setPref(forge.localinstance.properties.ForgePreferences.FPref.YIELD_AUTO_PASS_NO_ACTIONS, false);
        System.out.println("PREFS: autopass=" + FModel.getPreferences().getPrefBoolean(forge.localinstance.properties.ForgePreferences.FPref.YIELD_AUTO_PASS_NO_ACTIONS)
            + " restore=" + FModel.getPreferences().getPrefBoolean(forge.localinstance.properties.ForgePreferences.FPref.MATCH_EXPERIMENTAL_RESTORE));
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("House")).setPlayer(human ? new forge.player.LobbyPlayerHuman("House") : GamePlayerUtil.createAiPlayer("House")));
        players.add(new RegisteredPlayer(new Deck("Other")).setPlayer(GamePlayerUtil.createAiPlayer("Other")));
        Game game = new Match(new GameRules(GameType.Commander), players, "HouseCardsProbe").createGame();
        if (human) {
            forge.gui.interfaces.IGuiGame stub = (forge.gui.interfaces.IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
                HouseCardsProbe.class.getClassLoader(), new Class<?>[] { forge.gui.interfaces.IGuiGame.class },
                (p, m, args) -> {
                    String n = m.getName();
                    // As Forge's own window (CMatchUI): no click and one ability -> that ability.
                    if (n.equals("getAbilityToPlay") && args[2] == null && ((List<?>) args[1]).size() == 1) return ((List<?>) args[1]).get(0);
                    if (!n.startsWith("update") && !n.startsWith("get") && !n.startsWith("set") && !n.startsWith("show")
                            && !n.startsWith("clear") && !n.startsWith("is") && !n.equals("hashCode") && !n.equals("toString"))
                        System.out.println("  GUI asked: " + n + (args != null && args.length > 0 ? " " + args[0] : ""));
                    Class<?> t = m.getReturnType();
                    if (t == boolean.class) return false;
                    if (t == int.class) return 0;
                    return null;
                });
            forge.player.PlayerControllerHuman pch = (forge.player.PlayerControllerHuman) game.getPlayers().get(0).getController();
            forge.gui.interfaces.IGuiGame gui = stub;
            // Fifth argument "gui3d": the 3D table's wrapper in front, as in the owner's games.
            if (a.length > 4 && a[4].equals("gui3d")) {
                java.lang.reflect.Constructor<?> c = Class.forName("mtg3d.Gui3D")
                    .getDeclaredConstructor(forge.gui.interfaces.IGuiGame.class, forge.player.PlayerControllerHuman.class);
                c.setAccessible(true);
                gui = (forge.gui.interfaces.IGuiGame) java.lang.reflect.Proxy.newProxyInstance(HouseCardsProbe.class.getClassLoader(),
                    new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (java.lang.reflect.InvocationHandler) c.newInstance(stub, pch));
                System.out.println("GUI: 3D wrapper in front");
            }
            pch.setGui(gui);
            System.out.println("HUMAN controller: " + game.getPlayers().get(0).getController().getClass().getSimpleName()
                + ", experimental restore = " + FModel.getPreferences().getPrefBoolean(
                    forge.localinstance.properties.ForgePreferences.FPref.MATCH_EXPERIMENTAL_RESTORE));
        }
        game.setAge(GameStage.Play);       // triggers only run once the game is being played
        String lands = "Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains";
        String bf, hand;
        switch (which) {
            case "warden": bf = "Soul Warden;" + lands; hand = "Memnite"; break;
            case "lotho": bf = "Lotho, Corrupt Shirriff;" + lands; hand = "Ornithopter;Memnite"; break;
            case "lothoother": bf = "Lotho, Corrupt Shirriff;" + lands; hand = ""; break;
            case "wardenother": bf = "Soul Warden;" + lands; hand = ""; break;
            case "clown": bf = lands; hand = "Clown Car"; break;
            case "house": bf = "Mr. House, President and CEO;" + lands; hand = "Clown Car"; break;
            default: bf = "Mr. House, President and CEO;Wyll, Blade of Frontiers;" + lands; hand = "Clown Car"; break;
        }
        GameState st = new GameState();
        boolean otherCasts = which.endsWith("other");
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=" + (otherCasts ? "ai" : "human"), "activephase=MAIN1",
            "humanlife=40", "ailife=40", "humanbattlefield=" + bf, "humanhand=" + hand,
            "aibattlefield=Mountain;Mountain;Mountain;Mountain", "aihand=" + (otherCasts ? "Ornithopter;Memnite" : "")));
        st.applyToGame(game);
        Thread.sleep(2500);
        Player me = game.getPlayers().get(0);
        // Second argument: what a HUMAN game does that a simulation does not. "copy" = take a
        // GameSnapshot (PlayHook's per-phase Rollback, and Forge's experimental restore on every
        // human cast); "restore" = also put that copy back (Ctrl+Z / Restart this phase).
        String mode = a.length > 1 ? a[1] : "";
        if (mode.equals("copy") || mode.equals("restore")) {
            game.getAction().invoke(() -> {
                GameSnapshot s = new GameSnapshot(game);
                s.makeCopy();
                if (mode.equals("restore")) s.restoreGameState(game);
            });
            Thread.sleep(800);
            System.out.println("SNAPSHOT: " + mode);
        }
        int life0 = me.getLife();
        Player caster = otherCasts ? game.getPlayers().get(1) : me;
        List<Card> inHand = new ArrayList<>(caster.getCardsIn(ZoneType.Hand));
        for (Card c : inHand) cast(game, caster, c, c.getName().equals("Clown Car") ? 4 : 0);
        System.out.println("RESULT " + which + ": life " + life0 + " -> " + me.getLife()
            + " | treasures " + count(me, "Treasure") + " | clown robots " + count(me, "Clown Robot")
            + " | 3/3 robots " + (count(me, "Robot") - count(me, "Clown Robot")) + carCounters(me));
        List<forge.game.GameLogEntry> log = new ArrayList<>(game.getGameLog().getLogEntries(null));
        Collections.reverse(log);
        for (forge.game.GameLogEntry e : log) {
            String s = e.toString();
            if (!s.startsWith("Phase") && !s.startsWith("Mana") && !s.startsWith("Land")) System.out.println("  log: " + s);
        }
        System.exit(0);
    }

    static String carCounters(Player p) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Clown Car")) return " | Clown Car +1/+1 counters " + c.getCounters(forge.game.card.CounterEnumType.P1P1);
        }
        return "";
    }

    static int count(Player p, String name) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.getName().contains(name) || c.getType().hasSubtype(name)) n++;
        return n;
    }

    static void cast(Game game, Player p, Card c, int x) throws Exception {
        game.getAction().invoke(() -> {
            SpellAbility sa = c.getSpells().get(0);
            sa.setActivatingPlayer(p);
            if (x > 0) sa.setXManaCostPaid(x);
            forge.ai.ComputerUtil.handlePlayingSpellAbility(p, sa, null);
            for (int i = 0; i < 30; i++) {
                game.getTriggerHandler().runWaitingTriggers();
                if (game.getStack().hasSimultaneousStackEntries()) System.out.println("COLLECTED: triggers waiting to go on the stack");
                int before = game.getStack().size();
                try {
                    game.getStack().addAllTriggeredAbilitiesToStack();
                } catch (Throwable t) {
                    System.out.println("TRIGGERS THREW: " + t);
                    for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().startsWith("forge")) System.out.println("    at " + e);
                }
                if (game.getStack().size() != before) System.out.println("STACK: " + before + " -> " + game.getStack().size());
                if (game.getStack().isEmpty()) break;
                game.getStack().resolveStack();
            }
        });
        Thread.sleep(500);
    }
}
