// A floating, always-on-top copy of Forge's prompt panel (2026-10-20, second owner's request).
// In four-player games the docked prompt is easy to miss and the board feels crammed, so this
// mirrors the prompt into its own small window that can be dragged anywhere; where it is left is
// where it reappears next time (scratch\prompt-window.properties).
//
// It is a mirror, not a replacement: the docked prompt keeps working, and clicking a button here
// just forwards doClick() to the real FButton. The window is deliberately non-focusable
// (setFocusableWindowState(false)) so it never steals keyboard focus from Forge - card hovering,
// zooming and the keyboard shortcuts all behave exactly as before.
//
// A 100ms Swing timer polls the live prompt rather than hooking every update path; CPrompt
// changes button state from several places and polling is far less invasive than patching them all.

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
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

    /** Amber when Forge is waiting on a decision, grey when it is not. */
    private static final Color WAITING_BG = new Color(60, 46, 16), IDLE_BG = new Color(28, 28, 30);
    private static final Color WAITING_EDGE = new Color(222, 170, 52), IDLE_EDGE = new Color(70, 70, 74);

    private static JWindow window;
    private static JPanel root;
    private static JLabel title;
    private static JEditorPane message;
    private static JButton mirrorOk, mirrorCancel;
    private static javax.swing.Timer timer;

    private static volatile Object prompt;          // forge.screens.match.controllers.CPrompt
    private static Point dragOffset;

    private static File posFile() {
        String dir = System.getProperty("forge.promptwindow.dir");
        if (dir == null) dir = System.getProperty("forge.playlog.dir", ".");
        return new File(dir, "prompt-window.properties");
    }

    /** Called by PromptHook on every setMessage; the first call builds the window. */
    public static void attach(Object cPrompt) {
        prompt = cPrompt;
        if (window == null) SwingUtilities.invokeLater(PromptWindow::build);
    }

    private static void build() {
        if (window != null) return;
        title = new JLabel("Forge");
        title.setForeground(new Color(196, 196, 200));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));

        message = new JEditorPane("text/html", "");
        message.setEditable(false);
        message.setOpaque(false);
        message.setFont(message.getFont().deriveFont(14f));
        message.setBorder(new EmptyBorder(4, 0, 6, 0));

        mirrorOk = styled(new JButton("OK"));
        mirrorCancel = styled(new JButton("Cancel"));
        mirrorOk.addActionListener(e -> click(true));
        mirrorCancel.addActionListener(e -> click(false));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.add(mirrorOk);
        buttons.add(mirrorCancel);

        root = new JPanel(new BorderLayout(0, 4));
        root.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IDLE_EDGE, 2), new EmptyBorder(8, 10, 8, 10)));
        root.setBackground(IDLE_BG);
        root.add(title, BorderLayout.NORTH);
        root.add(message, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);   // never take focus away from the board
        window.setContentPane(root);
        window.setSize(360, 170);
        restorePosition();
        dragTo(root);
        dragTo(title);
        dragTo(message);

        timer = new javax.swing.Timer(100, e -> refresh());
        timer.start();
        window.setVisible(true);
    }

    private static JButton styled(JButton b) {
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        return b;
    }

    /** Dragging anywhere on the window moves it; the position is saved when the mouse is released. */
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
            @Override public void mouseReleased(MouseEvent e) { dragOffset = null; savePosition(); }
        };
        c.addMouseListener(a);
        c.addMouseMotionListener(a);
    }

    private static void restorePosition() {
        try (FileInputStream in = new FileInputStream(posFile())) {
            Properties p = new Properties();
            p.load(in);
            int x = Integer.parseInt(p.getProperty("x")), y = Integer.parseInt(p.getProperty("y"));
            int w = Integer.parseInt(p.getProperty("w", "360")), h = Integer.parseInt(p.getProperty("h", "170"));
            window.setSize(Math.max(240, w), Math.max(120, h));
            if (onAScreen(x, y)) { window.setLocation(x, y); return; }
        } catch (Throwable ignored) { }
        // Default: bottom-centre of the primary screen, clear of Forge's own prompt dock.
        Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setLocation(s.x + (s.width - window.getWidth()) / 2, s.y + s.height - window.getHeight() - 60);
    }

    /** Guards against a saved position on a monitor that is no longer attached. */
    private static boolean onAScreen(int x, int y) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().contains(x + 20, y + 20)) return true;
        }
        return false;
    }

    private static void savePosition() {
        try (FileOutputStream out = new FileOutputStream(posFile())) {
            Properties p = new Properties();
            p.setProperty("x", String.valueOf(window.getX()));
            p.setProperty("y", String.valueOf(window.getY()));
            p.setProperty("w", String.valueOf(window.getWidth()));
            p.setProperty("h", String.valueOf(window.getHeight()));
            p.store(out, "Floating prompt window position");
        } catch (Throwable ignored) { }
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

    /** Mirror the live prompt: message text, button labels and enabled state. */
    private static void refresh() {
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

            boolean waiting = sync(mirrorOk, realButton(true)) | sync(mirrorCancel, realButton(false));
            root.setBackground(waiting ? WAITING_BG : IDLE_BG);
            root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(waiting ? WAITING_EDGE : IDLE_EDGE, 2), new EmptyBorder(8, 10, 8, 10)));
            message.setForeground(waiting ? new Color(255, 236, 190) : new Color(176, 176, 180));
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
