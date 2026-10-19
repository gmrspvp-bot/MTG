import java.lang.reflect.*;
import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// ui3d risk check: after a rematch, does the previous game's listener overwrite the new game's
// snapshot? Drives mtg3d.Bridge.Listener directly (no window opens).
public class Ui3dRematchTest {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Game g1 = game("Old1", "Old2"), g2 = game("New1", "New2");
        Class<?> bridge = Class.forName("mtg3d.Bridge");
        Field current = bridge.getDeclaredField("current"); current.setAccessible(true);
        Field latest = bridge.getDeclaredField("latest"); latest.setAccessible(true);
        Constructor<?> lc = Class.forName("mtg3d.Bridge$Listener").getDeclaredConstructor(Game.class); lc.setAccessible(true);
        Method onEvent = Class.forName("mtg3d.Bridge$Listener").getMethod("onEvent", Object.class);
        Method read = Class.forName("mtg3d.StateReader").getDeclaredMethod("read", Game.class); read.setAccessible(true);

        current.set(null, g1); Object l1 = lc.newInstance(g1);           // game 1 attached
        current.set(null, g2); Object l2 = lc.newInstance(g2);           // rematch: game 2 attached
        latest.set(null, read.invoke(null, g2));
        onEvent.invoke(l1, new Object());                               // an event from the old game
        System.out.println("after an OLD-game event the view shows: " + seats(latest.get(null)));
        onEvent.invoke(l2, new Object());                               // an event from the new game
        System.out.println("after a NEW-game event the view shows: " + seats(latest.get(null)));
        System.exit(0);
    }
    static Game game(String p1, String p2) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{p1, p2}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        return new Match(new GameRules(GameType.Constructed), players, p1 + "-match").createGame();
    }
    static String seats(Object snap) throws Exception {
        List<?> seats = (List<?>) snap.getClass().getField("seats").get(snap);
        StringBuilder sb = new StringBuilder();
        for (Object s : seats) sb.append(s.getClass().getField("name").get(s)).append(' ');
        return sb.toString().trim();
    }
}
