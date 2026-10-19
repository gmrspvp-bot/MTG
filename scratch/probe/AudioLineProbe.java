import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Opens (and closes, without writing anything) the same line format Cues uses. */
public class AudioLineProbe {
    public static void main(String[] a) throws Exception {
        AudioFormat fmt = new AudioFormat(44100f, 16, 1, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, 44100 / 4);
        System.out.println("line open: " + line.isOpen() + "  buffer " + line.getBufferSize());
        line.close();
    }
}
