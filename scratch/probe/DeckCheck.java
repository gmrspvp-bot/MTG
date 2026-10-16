import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.item.PaperCard;

public class DeckCheck {
    public static void main(String[] args) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        GameFormat cmdr = FModel.getFormats().getFormat("Commander");
        System.out.println("Commander format found: " + (cmdr != null));
        for (String path : args) {
            Deck d = DeckSerializer.fromFile(new java.io.File(path));
            System.out.println("=== " + d.getName());
            System.out.println("  structure: " + DeckFormat.Commander.getDeckConformanceProblem(d));
            if (cmdr != null) {
                System.out.println("  format:    " + cmdr.getDeckConformanceProblem(d));
                for (java.util.Map.Entry<PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
                    if (!cmdr.getFilterRules().test(e.getKey())) System.out.println("    not legal in Commander: " + e.getKey().getName() + " [" + e.getKey().getEdition() + "]");
                }
            }
        }
        System.exit(0);
    }
}
