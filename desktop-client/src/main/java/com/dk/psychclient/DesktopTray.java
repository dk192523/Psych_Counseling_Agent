package com.dk.psychclient;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** AWT owns the native tray; every application callback runs on the JavaFX thread. */
final class DesktopTray implements AutoCloseable {
    private final SystemTray systemTray;
    private final TrayIcon icon;
    private final JDialog popupOwner;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DesktopTray(SystemTray systemTray, BufferedImage logo, String openLabel, String exitLabel,
                        String tooltip, Runnable open, Runnable exit) {
        this.systemTray = systemTray;
        // Swing renders CJK itself; the Windows native AWT menu can lose its glyphs.
        // The dialog is the popup itself, so focus and dismissal share one window.
        popupOwner = new JDialog();
        popupOwner.setUndecorated(true);
        popupOwner.setAlwaysOnTop(true);
        popupOwner.setType(java.awt.Window.Type.POPUP);
        popupOwner.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        boolean translucent = popupOwner.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        if (translucent) popupOwner.setBackground(new Color(0, 0, 0, 0));
        MenuSurface surface = new MenuSurface(translucent);
        MenuButton openItem = new MenuButton(openLabel, new MenuIcon(logo), false);
        openItem.addActionListener(event -> activate(open));
        MenuButton exitItem = new MenuButton(exitLabel, new MenuIcon(null), true);
        exitItem.addActionListener(event -> activate(exit));
        surface.add(openItem);
        surface.add(Box.createVerticalStrut(4));
        JSeparator separator = new JSeparator();
        separator.setForeground(new Color(228, 234, 232));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        surface.add(separator);
        surface.add(Box.createVerticalStrut(4));
        surface.add(exitItem);
        popupOwner.setContentPane(surface);
        popupOwner.pack();
        if (!translucent && popupOwner.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
            popupOwner.setShape(new RoundRectangle2D.Double(
                    0, 0, popupOwner.getWidth(), popupOwner.getHeight(), 18, 18));
        }
        bindKey("ESCAPE", "dismiss", () -> popupOwner.setVisible(false));
        bindKey("UP", "previous", () -> {
            if (openItem.isFocusOwner()) exitItem.requestFocusInWindow();
            else openItem.requestFocusInWindow();
        });
        bindKey("DOWN", "next", () -> {
            if (exitItem.isFocusOwner()) openItem.requestFocusInWindow();
            else exitItem.requestFocusInWindow();
        });
        bindKey("ENTER", "activate", () -> {
            if (exitItem.isFocusOwner()) exitItem.doClick();
            else openItem.doClick();
        });
        popupOwner.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) {
                popupOwner.setVisible(false);
            }
            @Override public void windowGainedFocus(WindowEvent event) {
                openItem.requestFocusInWindow();
            }
        });
        // Leave the native PopupMenu unset: Windows still dispatches right-button
        // events to TrayIcon's MouseListener, which opens the Swing menu below.
        icon = new TrayIcon(logo, tooltip);
        icon.setImageAutoSize(true);
        icon.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) showMenu(event);
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger() || event.getButton() == MouseEvent.BUTTON3) showMenu(event);
            }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    EventQueue.invokeLater(() -> activate(open));
                }
            }
        });
    }

    private void showMenu(MouseEvent event) {
        Point anchor = new Point(event.getX(), event.getY());
        EventQueue.invokeLater(() -> {
            if (closed.get() || popupOwner.isVisible()) return;
            GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration candidate = screen.getDefaultConfiguration();
                if (candidate.getBounds().contains(anchor)) {
                    configuration = candidate;
                    break;
                }
            }
            Rectangle bounds = new Rectangle(configuration.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            bounds.x += insets.left;
            bounds.y += insets.top;
            bounds.width -= insets.left + insets.right;
            bounds.height -= insets.top + insets.bottom;
            int width = popupOwner.getWidth();
            int height = popupOwner.getHeight();
            int x = anchor.x - width + 16;
            int y = anchor.y - height - 4;
            if (y < bounds.y) y = anchor.y + 4;
            x = Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - width));
            y = Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - height));
            popupOwner.setLocation(x, y);
            popupOwner.setVisible(true);
            popupOwner.toFront();
            popupOwner.requestFocus();
        });
    }

    private void activate(Runnable callback) {
        popupOwner.setVisible(false);
        onFx(callback);
    }

    private void bindKey(String key, String name, Runnable action) {
        popupOwner.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key), name);
        popupOwner.getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static final class MenuSurface extends JPanel {
        private final boolean translucent;

        private MenuSurface(boolean translucent) {
            this.translucent = translucent;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = translucent ? 8 : 0;
            if (translucent) {
                for (int spread = 6; spread > 0; spread--) {
                    g.setColor(new Color(25, 43, 38, 4 + (6 - spread) * 2));
                    g.fillRoundRect(inset - spread, inset - spread + 2,
                            getWidth() - inset * 2 + spread * 2,
                            getHeight() - inset * 2 + spread * 2, 18 + spread, 18 + spread);
                }
            } else {
                g.setColor(new Color(250, 252, 251));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            g.setColor(new Color(250, 252, 251));
            g.fillRoundRect(inset, inset, getWidth() - inset * 2 - 1,
                    getHeight() - inset * 2 - 1, 14, 14);
            g.setColor(new Color(218, 227, 223));
            g.drawRoundRect(inset, inset, getWidth() - inset * 2 - 1,
                    getHeight() - inset * 2 - 1, 14, 14);
            g.dispose();
        }
    }

    private static final class MenuButton extends JButton {
        private final boolean exit;

        private MenuButton(String text, Icon icon, boolean exit) {
            super(text, icon);
            this.exit = exit;
            setUI(new BasicButtonUI());
            setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
            setForeground(new Color(42, 59, 53));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setHorizontalAlignment(SwingConstants.LEFT);
            setIconTextGap(10);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(216, 36));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            getModel().addChangeListener(event -> repaint());
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            boolean highlighted = getModel().isRollover() || isFocusOwner();
            if (pressed || highlighted) {
                g.setColor(exit
                        ? (pressed ? new Color(248, 222, 219) : new Color(252, 239, 237))
                        : (pressed ? new Color(210, 231, 223) : new Color(230, 241, 236)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
            if (isFocusOwner()) {
                g.setColor(exit ? new Color(222, 182, 177) : new Color(156, 190, 175));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class MenuIcon implements Icon {
        private final BufferedImage logo;

        private MenuIcon(BufferedImage logo) {
            this.logo = logo;
        }

        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (logo != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double scale = 18.0 / Math.max(logo.getWidth(), logo.getHeight());
                int width = Math.max(1, (int) Math.round(logo.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(logo.getHeight() * scale));
                g.drawImage(logo, x + (18 - width) / 2, y + (18 - height) / 2, width, height, null);
            } else {
                g.setColor(new Color(125, 91, 87));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(x + 3, y + 4, 12, 12, 45, 270, Arc2D.OPEN));
                g.drawLine(x + 9, y + 2, x + 9, y + 9);
            }
            g.dispose();
        }
    }

    static DesktopTray install(Runnable open, Runnable exit) throws IOException {
        return install("打开咨询工作台", "退出", "Counseling", open, exit);
    }

    static DesktopTray install(String openLabel, String exitLabel, String tooltip,
                               Runnable open, Runnable exit) throws IOException {
        if (!SystemTray.isSupported()) throw new IOException("System tray is unavailable");
        URL resource = DesktopTray.class.getResource("/icons/logo.png");
        if (resource == null) throw new IOException("Missing desktop logo");
        BufferedImage logo = ImageIO.read(resource);
        if (logo == null) throw new IOException("Invalid desktop logo");
        AtomicReference<DesktopTray> installed = new AtomicReference<>();
        AtomicReference<AWTException> failure = new AtomicReference<>();
        try {
            // Install before enabling hide-on-close, so a hidden window always has a tray entry.
            EventQueue.invokeAndWait(() -> {
                SystemTray systemTray = SystemTray.getSystemTray();
                DesktopTray tray = new DesktopTray(systemTray, logo, openLabel, exitLabel, tooltip, open, exit);
                try {
                    systemTray.add(tray.icon);
                    installed.set(tray);
                } catch (AWTException unavailable) {
                    tray.close();
                    failure.set(unavailable);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // The AWT task can still finish after interruption; remove any late installation.
            EventQueue.invokeLater(() -> {
                DesktopTray tray = installed.get();
                if (tray != null) tray.close();
            });
            throw new IOException("Interrupted while installing desktop tray", interrupted);
        } catch (InvocationTargetException unavailable) {
            throw new IOException("Unable to install desktop tray", unavailable.getCause());
        }
        if (failure.get() != null) throw new IOException("Unable to install desktop tray", failure.get());
        return installed.get();
    }

    private void onFx(Runnable callback) {
        if (closed.get()) return;
        Platform.runLater(() -> {
            if (!closed.get()) callback.run();
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            popupOwner.setVisible(false);
            popupOwner.dispose();
            systemTray.remove(icon);
        };
        if (EventQueue.isDispatchThread()) cleanup.run();
        else EventQueue.invokeLater(cleanup);
    }
}
