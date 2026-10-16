import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

public class EquipSnapProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "EquipSnapProbe").createGame();
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=COMBAT_BEGIN", "humanlife=40", "ailife=40",
            "humanbattlefield=Grizzly Bears;Colossus Hammer;Bonesplitter;Forest", "aibattlefield=Hill Giant"));
        st.applyToGame(game);
        Thread.sleep(3000);
        Player p0 = game.getPlayers().get(0);
        Card bear = null, hammer = null, splitter = null;
        for (Card c : p0.getCardsIn(ZoneType.Battlefield)) { if (c.getName().equals("Grizzly Bears")) bear = c; if (c.getName().equals("Colossus Hammer")) hammer = c; if (c.getName().equals("Bonesplitter")) splitter = c; }
        final Card fb = bear, fh = hammer, fs = splitter;
        game.getAction().invoke(() -> fh.attachToEntity(fb, null));     // "Catch" attaches the Hammer
        Thread.sleep(1500);
        System.out.println("ATTACHED   " + describe(p0));
        GameSnapshot snap = new GameSnapshot(game); snap.makeCopy();   // snapshot at the next phase start
        game.getAction().invoke(() -> { fh.unattachFromEntity(fb); fs.attachToEntity(fb, null); p0.setLife(20, null); });
        Thread.sleep(1500);
        System.out.println("CHANGED    " + describe(p0));
        snap.restoreGameState(game); final GameSnapshot fsnap = snap; final int[] n = {0}; game.getAction().invoke(() -> n[0] = PlayHook.Rollback.repairAttachments(fsnap, game)); Thread.sleep(1000); System.out.println("repaired " + n[0]);
        Thread.sleep(1500);
        System.out.println("RESTORED   " + describe(p0));
        System.exit(0);
    }
    static String describe(Player p) {
        StringBuilder sb = new StringBuilder("life=" + p.getLife() + " ");
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isCreature()) { sb.append(c.getName()).append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness()).append(" equipped by ").append(c.getEquippedBy()); }
        return sb.toString();
    }
}

