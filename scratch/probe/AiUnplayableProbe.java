import java.io.File;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * The "AI" number Forge shows beside a deck: the cards its AI cannot play (card scripts marked
 * AI:RemoveDeck:All, or :Random for random decks). Lists them per deck so they can be fixed with
 * a custom card script, swapped out, or played around. Args: .dck files.
 * Run from forge-installer-2.0.15 with the Forge jar.
 */
public class AiUnplayableProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        for (String path : a) {
            Deck d = DeckSerializer.fromFile(new File(path));
            if (d == null) { System.out.println("UNREADABLE " + path); continue; }
            StringBuilder all = new StringBuilder(), random = new StringBuilder();
            int nAll = 0, nRandom = 0;
            for (DeckSection s : DeckSection.values()) {
                if (!d.has(s)) continue;
                for (PaperCard c : d.get(s).toFlatList()) {
                    if (c.getRules().getAiHints().getRemAIDecks()) { nAll++; all.append(all.length() > 0 ? "; " : "").append(c.getName()); }
                    else if (c.getRules().getAiHints().getRemRandomDecks()) { nRandom++; random.append(random.length() > 0 ? "; " : "").append(c.getName()); }
                }
            }
            System.out.println("DECK " + d.getName() + " | can't play: " + nAll + (nAll > 0 ? " (" + all + ")" : "")
                + " | weak in random decks: " + nRandom + (nRandom > 0 ? " (" + random + ")" : ""));
        }
        System.exit(0);
    }
}
