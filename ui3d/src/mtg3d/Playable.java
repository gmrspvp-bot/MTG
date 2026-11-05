package mtg3d;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import forge.player.PlayerControllerHuman;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which cards OUTSIDE the hand and the battlefield this client can play right now.
 *
 * The 3D table could show a graveyard and an exile pile but never said whether anything in one
 * could be cast, and clicking a card in the browser did nothing at all. So Ragavan exiled the top
 * of an opponent's library, Forge granted "until end of turn, you may cast that card", and the
 * player had no way to reach it: the card sat in a pile the table treated as scenery (the owner,
 * 2026-11-03). The same hole covered everything else that plays from a pile - escape, flashback,
 * foretell, plot, adventure, impulse draws, Breeches, Valakut Exploration, a commander in the
 * command zone.
 *
 * The answer comes from the rules engine, not from a rule of our own. Forge's own window labels a
 * card with {@code InputPassPriority.getActivateAction(card)}, and that method is exactly
 * {@code card.getAllPossibleAbilities(player, true)} with a caption on the front - so this class
 * calls the same engine method with the same arguments. The 3D side still decides nothing about
 * legality: a highlight means "Forge says there is something here", and the click that follows is
 * judged by Forge as every other click from this window is.
 *
 * Only asked while this client's player holds priority, which is the moment the question means
 * anything: a click will be taken. That also keeps the cost off every other snapshot - and the
 * cost is real, because the engine builds each card's ability list from scratch.
 *
 * Runs on the game thread, like the rest of StateReader - these are real Card objects, not views.
 */
public final class Playable {
    private Playable() {}

    /**
     * The zones asked about in full. The hand and the battlefield are left out because they are
     * already clickable. Libraries are asked about too, but only their top card: that is the only
     * one anything plays from (The Reality Chip, Future Sight, Bolas's Citadel), and the table only
     * shows it to a player Forge lets see it.
     */
    private static final List<ZoneType> ZONES =
        Arrays.asList(ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command);

    /** Cards already announced this game, so a standing permission is not re-announced each turn. */
    private static final Set<Integer> announced = new HashSet<>();

    /** How many were playable last time it was asked, and how long the asking took - for logs. */
    public static volatile int lastCount;
    public static volatile long lastMillis;

    /**
     * The engine builds each card's ability list from scratch, and a late game has a hundred and
     * fifty cards sitting in graveyards and exile piles. Snapshots are built per game event on the
     * game thread, so asking for all of them on every one of those is a stutter waiting to happen
     * - and this table has already had one performance bug of exactly that shape (a disarm that
     * threw on every proxy call, 2026-11-02). So the answer is reused for a fraction of a second.
     *
     * Staleness is harmless: the worst case is a frame or two where a gold frame is late, or
     * lingers after a land was tapped, and Forge judges the click either way. Nothing here is
     * permission.
     */
    private static final long CACHE_MS = 250L;
    private static Game cachedGame;
    private static long cachedAt;
    private static Set<Integer> cached = Collections.emptySet();
    /** Logged once a game: a pass this slow is worth knowing about even though it is throttled. */
    private static boolean warnedSlow;

    /**
     * The ids this client can act on right now, across every seat's graveyard, exile and command
     * zone and the top of every library. Empty when it is not our priority, or when anything at
     * all goes wrong: an empty set means "no highlight", which is how the table behaved before this
     * existed.
     */
    static Set<Integer> ids(Game game) {
        // Forge's own answer first. PlayerControllerHuman.pushActionableCards walks hand,
        // battlefield and everything playable elsewhere and pushes the result through
        // IGuiGame.setWeaklySelectable as priority is handed over - which crosses the network,
        // so it is the only answer a JOINED client can have, having no Card objects to ask. The
        // owner's 2026-11-04 game was joined to someone else's host, so the engine query below
        // found nothing and no pile lit up, even though these pushes were arriving all game.
        Set<Integer> pushed = Gui3D.actionableIds();
        // It is not the whole answer, though. The push only lists what the AI's payment check
        // thinks is affordable, which is not the rule: Dire Fleet Daredevil's "mana of any type"
        // left a Bolt out with an Island untapped, while the engine still offered it. Trusting the
        // push whenever it had anything in it - a land in hand was enough - took the gold frame,
        // and so the click, off cards in other players' piles (the owner, 2026-11-05;
        // scratch\probe\PileProbe.java). So a local game always asks the engine too.
        if (game == null) return pushed;
        PlayerControllerHuman pch = Gui3D.localController();
        if (pch == null) return pushed;
        long now = System.currentTimeMillis();
        Set<Integer> out = new HashSet<>(pushed);
        if (game == cachedGame && now - cachedAt < CACHE_MS) {
            out.addAll(cached);
            return out;
        }
        long start = now;
        Set<Integer> engine = new HashSet<>();
        try {
            forge.game.player.Player me = pch.getPlayer();
            // The rules state and not the live Input: snapshots are built on game events, and the
            // one that hands over priority fires before Forge has installed the Input.
            if (me == null || game.getPhaseHandler().getPriorityPlayer() != me) return pushed;
            List<Card> candidates = new ArrayList<>();
            for (Card c : game.getCardsIn(ZONES)) candidates.add(c);
            for (forge.game.player.Player p : game.getPlayers()) {
                forge.game.card.CardCollectionView library = p.getCardsIn(ZoneType.Library);
                if (!library.isEmpty()) candidates.add(library.get(0));
            }
            for (Card c : candidates) {
                try {
                    if (!c.getAllPossibleAbilities(me, true).isEmpty()) engine.add(c.getId());
                } catch (Throwable t) {
                    // One card the engine cannot answer for must not cost the other ninety-nine.
                }
            }
        } catch (Throwable t) {
            return pushed;
        }
        out.addAll(engine);
        lastCount = out.size();
        lastMillis = System.currentTimeMillis() - start;
        if (lastMillis > 40L && !warnedSlow) {
            warnedSlow = true;
            System.out.println("UI3D: asking which cards in the piles are playable took "
                + lastMillis + "ms (" + lastCount + " playable); throttled to one pass every "
                + CACHE_MS + "ms");
        }
        cachedGame = game;
        cachedAt = System.currentTimeMillis();
        cached = engine;
        return out;
    }

    /**
     * Says so on the table the first time a card in a pile becomes playable.
     *
     * This is the discoverability half of the fix, and the half the owner actually hit: a
     * highlight only helps a player who is already looking at the pile, and Ragavan's trigger
     * grants its permission in the middle of combat damage with the attention somewhere else
     * entirely. Named once per card per game, so a commander sitting in the command zone or a
     * card with a permanent permission does not say it again every turn.
     */
    static void announce(Snapshot s) {
        if (s == null) return;
        String name = null;
        int extra = 0;
        for (Snapshot.SeatV seat : s.seats) {
            for (int k = 0; k < 3; k++) {
                for (Snapshot.CardV c : k == 0 ? seat.library : k == 1 ? seat.graveyard : seat.exile) {
                    if (!c.playable || c.name == null || c.name.isEmpty()) continue;
                    if (!announced.add(c.id)) continue;
                    if (name == null) name = c.name + " in " + seat.name + "'s " + (k == 0 ? "library" : k == 1 ? "graveyard" : "exile");
                    else extra++;
                }
            }
        }
        if (name == null) return;
        Gui3D.note("You can play " + name + (extra > 0 ? " (and " + extra + " more)" : "")
            + " - click the pile to open it");
    }

    /**
     * True in a joined game, where this client cannot know all it may play: it has no Card objects
     * to ask, and the host's push leaves out anything its AI thinks is unaffordable, or everything
     * if its UI_SHOW_ACTIONABLE_HIGHLIGHTS preference is off.
     *
     * The pile browser uses this to let the player try any card rather than show a dead list: the
     * host judges it exactly as it judges a click on the table, so the worst case is nothing
     * happening. Silence is the one outcome worth avoiding - it is what the owner got.
     */
    static boolean blind() {
        return Gui3D.localController() == null;
    }

    /** A new game must not inherit the last one's announcements. */
    static void reset() {
        announced.clear();
        cachedGame = null;
        cached = Collections.emptySet();
        cachedAt = 0L;
        warnedSlow = false;
    }
}
