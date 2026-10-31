// Let a joining player take over a seat the AI is holding (2026-10-28).
//
// jim's connection died on turn 15 with him mid-combat. Forge held his seat for five minutes,
// his own client gave up trying after about two, and when the timer ran out the AI took the
// seat and played the rest of his game for him. He could not get back in: once the timer has
// fired the host has nothing to match a login against, so every attempt is refused with
// "[Reconnect] LoginEvent for ... during active match - seat unavailable" and the socket closed.
//
// The owner's call (2026-10-28): any seat that is playable - one the AI inherited from a
// disconnect, or an AI that was in the lobby from the start - may be claimed, by whoever joins
// first. No time limit and no name matching.
//
// HOW IT WORKS
//
// Forge already has the whole machine for this; what it lacks is a way in. On a login during an
// active match it looks the username up in FServerManager.disconnectedClients, and on a hit it
// calls RemoteClient.swapChannel - the same client object, the same slot, the same gui - so the
// returning player lands back in their own seat with their own hand. Everything after the lookup
// works. Only the lookup fails.
//
// So this does two small things rather than one large one:
//
//   A. channelRead - before Forge looks the name up, PARK a claimable seat under whatever name
//      is logging in. Forge's own reconnect path then runs unmodified and does all the work.
//   B. swapChannel - after a client lands on a slot, if the live Player in that seat is being
//      run by the AI, hand it back to a human controller.
//
// B is the mirror of what Forge does when it gives a seat away:
//     p.dangerouslySetController(new PlayerControllerAi(game, p, new LobbyPlayerAi(p.getName())))
// and it is deliberately NOT done here on the netty thread. Swapping the controller out from
// under an AI that is halfway through choosing attackers is how you get a decision answered by
// nobody. The swap is queued and CombatGuardHook runs it on the game thread at the next phase
// begin, which is the same place the combat guard already does its work.
//
// This class lives in forge.gamemodes.net.server because RegisterClientHandler and
// RemoteClient.setGui are package-private. It is compiled by build-tools.ps1 with everything
// else and javac files it under the right folder from the package line.
//
// SWITCH: -Dforge.seatclaim=false turns the whole thing off and Forge behaves as it always did.
//
// NOT YET PROVEN IN A REAL GAME. It compiles and the path it takes is Forge's own, but it needs
// a host and a second client to exercise, which is two machines or two Forge instances - see
// the note at the bottom of this file.

package forge.gamemodes.net.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.net.event.LoginEvent;
import forge.player.PlayerControllerHuman;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

public final class SeatClaimHook {
    private SeatClaimHook() {}

    private static volatile MethodHandle origRead, origSwap;

    /** Off with -Dforge.seatclaim=false. */
    private static boolean on() {
        return !"false".equalsIgnoreCase(System.getProperty("forge.seatclaim", "true"));
    }

    // ---- A: let the login in -------------------------------------------------------------------

    /**
     * EvalCacheAgent routes FServerManager$RegisterClientHandler.channelRead here.
     *
     * Everything that is not a login during a live match falls straight through. For one that is,
     * a claimable seat is parked under the joiner's name first, so that the lookup Forge is about
     * to do finds something and its own reconnect runs.
     */
    public static void channelRead(Object self, ChannelHandlerContext ctx, Object msg)
            throws Throwable {
        // `self` is FServerManager$RegisterClientHandler, which is a PRIVATE inner class - no
        // source file may name it, which is why the agent hands it over as Object and why the
        // original is reached by reflection on self.getClass() rather than a named type.
        if (origRead == null) {
            Class<?> handler = self.getClass();
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(handler, MethodHandles.lookup());
            origRead = l.findVirtual(handler, "channelRead$orig",
                MethodType.methodType(void.class, ChannelHandlerContext.class, Object.class));
        }
        try {
            if (on() && msg instanceof LoginEvent) {
                park(((LoginEvent) msg).getUsername());
            }
        } catch (Throwable t) {
            // A seat not offered is the behaviour we already had. Never fail a login over it.
            System.out.println("SEATCLAIM: could not offer a seat (" + t + ")");
        }
        origRead.invoke(self, ctx, msg);
    }

    /**
     * Put a claimable seat in disconnectedClients under `username`, if there is one going.
     *
     * Does nothing at all unless a match is running, so ordinary lobby joins are untouched. Does
     * nothing if that name is already parked - a real reconnect inside its own window owns its
     * seat and must not be redirected to somebody else's.
     */
    private static void park(String username) throws Exception {
        FServerManager sm = FServerManager.getInstance();
        if (sm == null || !sm.isMatchActive() || username == null || username.isEmpty()) return;

        Map<String, RemoteClient> parked = disconnectedClients(sm);
        if (parked == null || parked.containsKey(username)) return;

        Game game = game(sm);
        if (game == null) return;

        // A seat worth taking: run by the AI, and still in the game. Somebody who has already
        // lost is not "playable" in the sense that matters - inheriting a dead seat would be a
        // worse experience than being refused.
        Player claim = null;
        for (Player p : game.getPlayers()) {
            if (p == null || p.hasLost()) continue;
            if (!(p.getController() instanceof PlayerControllerAi)) continue;
            // Nor a seat sitting out after a concede (ConcedeHook$Passive, named because that
            // class is in the default package and cannot be imported from here).
            if (p.getController().getClass().getName().equals("ConcedeHook$Passive")) continue;
            // Never hand over a seat somebody is already parked against: that is their seat and
            // they are inside their reconnect window.
            if (parkedAlready(parked, p.getName())) continue;
            claim = p;
            break;
        }
        if (claim == null) return;

        int slot = slotOf(sm, claim.getName());
        RemoteClient rc = slot >= 0 ? sm.getClientBySlotIndex(slot) : null;
        if (rc == null) {
            // A seat that was AI from the start has no client object to re-use, so make one. The
            // channel is null until swapChannel fills it in, which is safe because nothing sends
            // to a parked client: broadcast walks `clients`, and this is not in it yet.
            rc = new RemoteClient(null);
            rc.setIndex(slot);
            rc.setGui(new RemoteClientGuiGame(rc));
        }
        rc.setUsername(username);
        parked.put(username, rc);
        claimedSeatName.put(username, claim.getName());
        System.out.println("SEATCLAIM: offering " + claim.getName() + "'s seat (slot " + slot
            + ", run by the AI) to " + username);
    }

    /** Is some other name already parked against this seat? */
    private static boolean parkedAlready(Map<String, RemoteClient> parked, String seatName) {
        for (String who : claimedSeatName.keySet()) {
            if (parked.containsKey(who) && seatName.equals(claimedSeatName.get(who))) return true;
        }
        return false;
    }

    /** username -> the name of the player whose seat they were offered. */
    private static final Map<String, String> claimedSeatName =
        new java.util.concurrent.ConcurrentHashMap<>();

    // ---- B: hand the seat back to a human ------------------------------------------------------

    /**
     * EvalCacheAgent routes RemoteClient.swapChannel here.
     *
     * This runs for every reconnect, ours and Forge's own. In both cases the question is the
     * same: is the player in that seat currently being run by the AI? If so, give it back. A
     * player who reconnected inside the five minutes is not AI-controlled and nothing happens.
     */
    public static void swapChannel(RemoteClient self, Channel channel) throws Throwable {
        if (origSwap == null) {
            MethodHandles.Lookup l =
                MethodHandles.privateLookupIn(RemoteClient.class, MethodHandles.lookup());
            origSwap = l.findVirtual(RemoteClient.class, "swapChannel$orig",
                MethodType.methodType(void.class, Channel.class));
        }
        origSwap.invoke(self, channel);
        if (!on()) return;
        try {
            String seat = claimedSeatName.remove(self.getUsername());
            if (seat == null) return;                       // an ordinary reconnect: nothing to do
            synchronized (pending) { pending.add(new Claim(self, seat)); }
            System.out.println("SEATCLAIM: " + self.getUsername() + " has landed on " + seat
                + "'s seat - handing it back from the AI at the next phase");
        } catch (Throwable t) {
            System.out.println("SEATCLAIM: could not queue the hand-back (" + t + ")");
        }
    }

    private static final class Claim {
        final RemoteClient client;
        final String seatName;
        Claim(RemoteClient c, String s) { client = c; seatName = s; }
    }

    private static final Deque<Claim> pending = new ArrayDeque<>();

    /**
     * Run the queued hand-backs. Called by CombatGuardHook, on the game thread, at a phase begin.
     *
     * Deliberately not done on the netty thread where the login arrived: dangerouslySetController
     * while the AI is halfway through declaring attackers would leave that decision with nobody
     * to answer it. A phase boundary is the one moment nothing is inside an Input.
     */
    public static void applyPending(Game game) {
        if (game == null) return;
        Claim c;
        for (;;) {
            synchronized (pending) { c = pending.poll(); }
            if (c == null) return;
            try {
                handBack(game, c);
            } catch (Throwable t) {
                System.out.println("SEATCLAIM: could not hand " + c.seatName + " back (" + t + ")");
            }
        }
    }

    /**
     * The mirror of Forge's own takeover: a new PlayerControllerHuman pointed at this client's
     * gui, set on the live Player, and then the whole game state pushed down to them.
     */
    private static void handBack(Game game, Claim c) {
        Player seat = null;
        for (Player p : game.getPlayers()) {
            if (p != null && c.seatName.equals(p.getName())) { seat = p; break; }
        }
        if (seat == null) {
            System.out.println("SEATCLAIM: " + c.seatName + " is no longer in the game");
            return;
        }
        if (!(seat.getController() instanceof PlayerControllerAi)) {
            System.out.println("SEATCLAIM: " + c.seatName + " is already being played by somebody");
            return;
        }
        RemoteClientGuiGame gui = c.client.getGui();
        if (gui == null) {
            gui = new RemoteClientGuiGame(c.client);
            c.client.setGui(gui);
        }
        PlayerControllerHuman human =
            new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
        human.setGui(gui);
        seat.dangerouslySetController(human);
        gui.setGameController(seat.getView(), human);
        gui.setOriginalGameController(seat.getView(), human);
        gui.openView(new forge.trackable.TrackableCollection<>(seat.getView()));
        System.out.println("SEATCLAIM: " + c.client.getUsername() + " is now playing "
            + c.seatName + " - the AI has stood down");
    }

    // ---- getting at Forge's private bits -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, RemoteClient> disconnectedClients(FServerManager sm) throws Exception {
        Field f = FServerManager.class.getDeclaredField("disconnectedClients");
        f.setAccessible(true);
        return (Map<String, RemoteClient>) f.get(sm);
    }

    private static Game game(FServerManager sm) throws Exception {
        Field f = FServerManager.class.getDeclaredField("localLobby");
        f.setAccessible(true);
        ServerGameLobby lobby = (ServerGameLobby) f.get(sm);
        if (lobby == null || lobby.getHostedMatch() == null) return null;
        return lobby.getHostedMatch().getGame();
    }

    /** The lobby slot showing this name, or -1. */
    private static int slotOf(FServerManager sm, String name) throws Exception {
        Field f = FServerManager.class.getDeclaredField("localLobby");
        f.setAccessible(true);
        ServerGameLobby lobby = (ServerGameLobby) f.get(sm);
        if (lobby == null) return -1;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            forge.gamemodes.match.LobbySlot s = lobby.getSlot(i);
            if (s != null && name.equals(s.getName())) return i;
        }
        return -1;
    }
}

// TESTING IT, when there is a quiet moment:
//   1. One machine is enough. Start Forge, host a game with two humans and one AI.
//   2. Start a SECOND Forge on the same machine and join 127.0.0.1 as the second human.
//   3. Once the game is running, close the second Forge outright.
//   4. Re-open it and join again under ANY name. It should land in the seat it left.
//   5. Then try joining a THIRD time under another name: it should land in the AI's seat.
// The console says what it decided at every step - every line here starts with SEATCLAIM.
