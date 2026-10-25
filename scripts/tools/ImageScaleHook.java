// "Error doing rescale. Target size was 2x3 but must be at least 3x3" - 32 times at once on the
// Swing thread when Forge's window came back at the end of the owner's game of 2026-10-24 02:17
// (crash-log review 2026-10-25). Restoring the window resizes every CardPanel, and some pass
// through a size of 2-3 pixels on the way; ImageCache.scaleImage hands that to the scaling
// library, which throws for anything under 3x3, and the exception reaches Forge's error handler
// (the kind it reports with its crash window).
//
// The size asked for is not the problem - scaleImage refuses anything under 3x3 itself. Asked for
// a height only (width -1, "keep the proportions"), it works the width out, and a card 3 pixels
// high comes out 2 wide, which the library rejects.
//
// EvalCacheAgent routes ImageCache.scaleImage here (original renamed scaleImage$orig). That
// error, and any size under 3x3, is answered with null - "no image for this size", which
// scaleImage already returns for the sizes it knows it cannot do and which its callers handle -
// so the panel draws no picture for that one frame and gets it at its real size a moment later.
// Test: scratch\probe\ImageScaleProbe.java (throws without the hook, null with it).

import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.CardView;

public final class ImageScaleHook {
    private ImageScaleHook() {}

    private static volatile MethodHandle orig;
    static final int MIN_SIDE = 3;

    public static BufferedImage scaleImage(String key, int width, int height, boolean mayBeHidden, CardView card) throws Throwable {
        if ((width >= 0 && width < MIN_SIDE) || (height >= 0 && height < MIN_SIDE)) return null;
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.privateLookupIn(forge.ImageCache.class, MethodHandles.lookup()).findStatic(forge.ImageCache.class,
                "scaleImage$orig", MethodType.methodType(BufferedImage.class, String.class, int.class, int.class, boolean.class, CardView.class));
            orig = h;
        }
        try {
            return (BufferedImage) h.invokeExact(key, width, height, mayBeHidden, card);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Error doing rescale")) return null;
            throw e;
        }
    }
}
