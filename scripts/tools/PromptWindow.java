// A floating, always-on-top copy of Forge's prompt panel (2026-10-20, second owner's request).
// In four-player games the docked prompt is easy to miss and the board feels crammed, so this
// mirrors the prompt into its own small window, styled to match the Jarvis skin
// (scripts\make-jarvis-skin.ps1): deep navy, cyan text, amber when it is waiting on you.
//
// It is a mirror, not a replacement: the docked prompt keeps working, and clicking a button here
// just forwards doClick() to the real FButton. The window is deliberately non-focusable
// (setFocusableWindowState(false)) so it never steals keyboard focus from Forge - card hovering,
// zooming and the keyboard shortcuts all behave exactly as before.
//
// Position is stored as an OFFSET from Forge's own window, not as a screen coordinate, so moving
// Forge to another monitor takes the prompt with it. That was the complaint that prompted this:
// popups kept reappearing on the primary screen after the game window had been moved.
//
// A 100ms Swing timer polls the live prompt rather than hooking every update path; CPrompt
// changes button state from several places and polling is far less invasive than patching them all.

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

public final class PromptWindow {
    private PromptWindow() {}

    /** Jarvis palette: navy while idle, amber while a decision is actually owed. */
    private static final Color IDLE_BG = new Color(0x05, 0x13, 0x1C), IDLE_EDGE = new Color(0x1C, 0x5D, 0x73);
    private static final Color IDLE_TEXT = new Color(0x9F, 0xE8, 0xFF);
    private static final Color WAIT_BG = new Color(0x10, 0x26, 0x3A), WAIT_EDGE = new Color(0xFF, 0xA6, 0x2B);
    private static final Color WAIT_TEXT = new Color(0xFF, 0xE3, 0xB0);

    private static JWindow window;
    private static JPanel root;
    private static JLabel title;
    private static JEditorPane message;
    private static JButton mirrorOk, mirrorCancel;

    private static volatile Object prompt;          // forge.screens.match.controllers.CPrompt
    private static Point dragOffset;

    /**
     * Question mode. Our own confirmations (ConfirmPassHook's "pass anyway?") are asked inside
     * this window instead of a separate JOptionPane, so there is only ever one prompt on screen
     * and it is always in the same place and the same style. Non-null while a question is open.
     */
    private static volatile java.util.concurrent.CountDownLatch askLatch;
    private static volatile boolean askAnswer;
    /** Where we sit relative to Forge's own window, so we follow it between monitors. */
    private static Point anchor;

    private static File posFile() {
        String dir = System.getProperty("forge.promptwindow.dir");
        if (dir == null) dir = System.getProperty("forge.playlog.dir", ".");
        return new File(dir, "prompt-window.properties");
    }

    /**
     * Forge's main window: the largest showing Frame that is not ours. Also used by
     * ConfirmPassHook, so its dialogs open over the game instead of on the primary screen.
     */
    public static Window forgeFrame() {
        Window best = null;
        long bestArea = -1;
        for (Window w : Window.getWindows()) {
            if (w == window || !(w instanceof Frame) || !w.isShowing()) continue;
            long area = (long) w.getWidth() * w.getHeight();
            if (area > bestArea) { bestArea = area; best = w; }
        }
        return best;
    }

    /** Called by PromptHook on every setMessage; the first call builds the window. */
    public static void attach(Object cPrompt) {
        prompt = cPrompt;
        if (window == null) SwingUtilities.invokeLater(PromptWindow::build);
    }

    private static void build() {
        if (window != null) return;
        title = new JLabel("Forge");
        title.setForeground(new Color(0x4F, 0xA8, 0xC8));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));

        message = new JEditorPane("text/html", "");
        message.setEditable(false);
        message.setOpaque(false);
        message.setFont(message.getFont().deriveFont(14f));
        message.setBorder(new EmptyBorder(4, 0, 6, 0));

        mirrorOk = styled(new JButton("OK"));
        mirrorCancel = styled(new JButton("Cancel"));
        mirrorOk.addActionListener(e -> { if (askLatch != null) answer(true); else click(true); });
        mirrorCancel.addActionListener(e -> { if (askLatch != null) answer(false); else click(false); });

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.add(mirrorOk);
        buttons.add(mirrorCancel);

        root = new JPanel(new BorderLayout(0, 4));
        root.setBackground(IDLE_BG);
        root.add(title, BorderLayout.NORTH);
        root.add(message, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        paint(false);

        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);   // never take focus away from the board
        window.setContentPane(root);
        window.setSize(360, 170);
        restoreAnchor();
        dragTo(root);
        dragTo(title);
        dragTo(message);

        new javax.swing.Timer(100, e -> refresh()).start();
        reposition();
        window.setVisible(true);
    }

    private static JButton styled(JButton b) {
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setBackground(new Color(0x0A, 0x26, 0x34));
        b.setForeground(new Color(0x9F, 0xE8, 0xFF));
        return b;
    }

    private static void paint(boolean waiting) {
        root.setBackground(waiting ? WAIT_BG : IDLE_BG);
        root.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(waiting ? WAIT_EDGE : IDLE_EDGE, 2), new EmptyBorder(8, 10, 8, 10)));
        message.setForeground(waiting ? WAIT_TEXT : IDLE_TEXT);
    }

    /** Dragging anywhere on the window moves it; the offset is saved when the mouse is released. */
    private static void dragTo(Component c) {
        MouseAdapter a = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragOffset = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), root);
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset == null) return;
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), root);
                Point w = window.getLocation();
                window.setLocation(w.x + p.x - dragOffset.x, w.y + p.y - dragOffset.y);
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragOffset = null;
                rememberAnchor();
                saveAnchor();
            }
        };
        c.addMouseListener(a);
        c.addMouseMotionListener(a);
    }

    /** Record where we now sit relative to Forge's window. */
    private static void rememberAnchor() {
        Window f = forgeFrame();
        if (f == null) return;
        anchor = new Point(window.getX() - f.getX(), window.getY() - f.getY());
    }

    /** Put the window back at its offset from Forge, clamped so it cannot land off-screen. */
    private static void reposition() {
        if (window == null || dragOffset != null) return;   // never fight an active drag
        Window f = forgeFrame();
        int x, y;
        if (f == null) {
            Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            x = s.x + (s.width - window.getWidth()) / 2;
            y = s.y + s.height - window.getHeight() - 60;
        } else {
            if (anchor == null) {
                // Default: bottom-centre of Forge's window, clear of the hand.
                anchor = new Point((f.getWidth() - window.getWidth()) / 2, f.getHeight() - window.getHeight() - 150);
            }
            x = f.getX() + anchor.x;
            y = f.getY() + anchor.y;
        }
        if (window.getX() != x || window.getY() != y) window.setLocation(x, y);
    }

    private static void restoreAnchor() {
        try (FileInputStream in = new FileInputStream(posFile())) {
            Properties p = new Properties();
            p.load(in);
            int w = Integer.parseInt(p.getProperty("w", "360")), h = Integer.parseInt(p.getProperty("h", "170"));
            window.setSize(Math.max(240, w), Math.max(120, h));
            anchor = new Point(Integer.parseInt(p.getProperty("dx")), Integer.parseInt(p.getProperty("dy")));
        } catch (Throwable ignored) {
            anchor = null;   // reposition() picks the default
        }
    }

    private static void saveAnchor() {
        if (anchor == null) return;
        try (FileOutputStream out = new FileOutputStream(posFile())) {
            Properties p = new Properties();
            p.setProperty("dx", String.valueOf(anchor.x));
            p.setProperty("dy", String.valueOf(anchor.y));
            p.setProperty("w", String.valueOf(window.getWidth()));
            p.setProperty("h", String.valueOf(window.getHeight()));
            p.store(out, "Floating prompt window, offset from Forge's own window");
        } catch (Throwable ignored) { }
    }

    /**
     * Asks a yes/no question in this window and blocks until it is answered, so our own
     * confirmations look and sit exactly like every other prompt. Called from the game thread;
     * falls back to a dialog parented on Forge's window if this window does not exist yet, or if
     * we are on the EDT (where blocking would deadlock the UI).
     */
    public static boolean ask(String text) {
        if (window == null || SwingUtilities.isEventDispatchThread()) {
            return javax.swing.JOptionPane.showConfirmDialog(forgeFrame(), text, "Forge",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        askAnswer = true;                 // if anything goes wrong, behave as "yes" (pass anyway)
        askLatch = latch;
        String html = "<html><b>" + text.replace("&", "&amp;").replace("<", "&lt;")
            .replace("\n\n", "<br><br>").replace("\n", "<br>") + "</b></html>";
        SwingUtilities.invokeLater(() -> {
            message.setText(html);
            mirrorOk.setText("Yes");
            mirrorCancel.setText("No");
            mirrorOk.setEnabled(true);
            mirrorCancel.setEnabled(true);
            mirrorOk.setVisible(true);
            mirrorCancel.setVisible(true);
            paint(true);
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return askAnswer;
    }

    private static void answer(boolean yes) {
        askAnswer = yes;
        java.util.concurrent.CountDownLatch latch = askLatch;
        askLatch = null;
        if (latch != null) latch.countDown();
    }

    private static void click(boolean ok) {
        JButton real = realButton(ok);
        if (real != null && real.isEnabled()) SwingUtilities.invokeLater(real::doClick);
    }

    private static JButton realButton(boolean ok) {
        try {
            Object p = prompt;
            if (p == null) return null;
            Object view = p.getClass().getMethod("getView").invoke(p);
            return (JButton) view.getClass().getMethod(ok ? "getBtnOK" : "getBtnCancel").invoke(view);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mirror the live prompt: message text, button labels and enabled state, and track Forge. */
    private static void refresh() {
        reposition();
        if (askLatch != null) return;     // a question is open; don't overwrite it with the prompt
        Object p = prompt;
        if (p == null) return;
        try {
            Object view = p.getClass().getMethod("getView").invoke(p);
            JEditorPane src = (JEditorPane) view.getClass().getMethod("getTarMessage").invoke(view);
            String text = src.getText();
            if (text != null && !text.equals(message.getText())) message.setText(text);

            JLabel games = (JLabel) view.getClass().getMethod("getLblGames").invoke(view);
            String t = games.getText();
            if (t != null && !t.equals(title.getText())) title.setText(t);

            paint(sync(mirrorOk, realButton(true)) | sync(mirrorCancel, realButton(false)));
        } catch (Throwable ignored) { }
    }

    /** Copies label/enabled/visible onto the mirror; returns true if this button is awaiting a click. */
    private static boolean sync(JButton mirror, JButton real) {
        if (real == null) { mirror.setVisible(false); return false; }
        String label = real.getText();
        if (label != null && !label.equals(mirror.getText())) mirror.setText(label);
        mirror.setEnabled(real.isEnabled());
        mirror.setVisible(real.isVisible() && label != null && !label.isEmpty());
        return real.isEnabled() && mirror.isVisible();
    }
}
