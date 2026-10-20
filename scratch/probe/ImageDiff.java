import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Percentage of pixels that differ by more than a small tolerance between two same-size PNGs. */
public class ImageDiff {
    public static void main(String[] a) throws Exception {
        BufferedImage x = ImageIO.read(new File(a[0])), y = ImageIO.read(new File(a[1]));
        if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
            System.out.println("SIZE DIFFERS " + x.getWidth() + "x" + x.getHeight() + " vs " + y.getWidth() + "x" + y.getHeight());
            return;
        }
        long diff = 0, total = (long) x.getWidth() * x.getHeight();
        for (int j = 0; j < x.getHeight(); j++) {
            for (int i = 0; i < x.getWidth(); i++) {
                int p = x.getRGB(i, j), q = y.getRGB(i, j);
                int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                      + Math.abs((p & 255) - (q & 255));
                if (d > 24) diff++;
            }
        }
        System.out.printf("%.3f%% of pixels differ%n", 100.0 * diff / total);
    }
}
