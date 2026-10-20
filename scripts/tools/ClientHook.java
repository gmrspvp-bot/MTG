// Joined network games (2026-10-21): EvalCacheAgent routes forge.GuiDesktop.getNewGuiGame()
// here (original renamed getNewGuiGame$orig).
//
// PlayHook attaches the 3D table when a game starts - but Match.startGame only runs on the
// machine HOSTING the game. A player who joins someone else's game never gets there: the game
// runs on the host and the client only receives views of it, so the 3D table never opened
// (the owner's game on 2026-10-20 was exactly this).
//
// When a client joins, NetConnectUtil.join asks GuiDesktop for a new IGuiGame and gives it to
// FGameClient; everything the host sends lands on that object. So when -Dforge.ui3d=true and the
// call comes from join, the new GUI is handed to mtg3d.Bridge.wrapClientGui, and whatever comes
// back (the 3D table's wrapper, which forwards everything to Forge's window) is used instead.
// Every other caller - local games, simulations - gets Forge's GUI untouched.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.gui.interfaces.IGuiGame;

public final class ClientHook {
    private ClientHook() {}

    private static volatile MethodHandle orig;

    public static IGuiGame getNewGuiGame(forge.GuiDesktop self) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findVirtual(forge.GuiDesktop.class, "getNewGuiGame$orig",
                MethodType.methodType(IGuiGame.class));
            orig = h;
        }
        IGuiGame gui = (IGuiGame) h.invokeExact(self);
        try {
            if ("true".equals(System.getProperty("forge.ui3d")) && calledFromJoin()) {
                Object w = Class.forName("mtg3d.Bridge").getMethod("wrapClientGui", Object.class).invoke(null, gui);
                if (w instanceof IGuiGame) return (IGuiGame) w;
            }
        } catch (Throwable t) {
            System.out.println("CLIENTHOOK: 3D view not available for the joined game (" + t + ")");
        }
        return gui;
    }

    /** True when this GUI is being made for a client joining someone else's game. */
    static boolean calledFromJoin() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if ("forge.gamemodes.net.NetConnectUtil".equals(e.getClassName()) && "join".equals(e.getMethodName())) return true;
        }
        return false;
    }
}
