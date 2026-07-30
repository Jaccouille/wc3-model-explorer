package com.hiveworkshop.ui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Photoshop/Substance-style circular angle dial.
 * Click or drag anywhere in the circle to set the angle.
 * Displays the current angle as a line from center and a degree label.
 * <p>
 * Supports full 360° wrapping or a clamped arc range.
 * For clamped ranges, the valid arc is highlighted and the needle is constrained.
 */
public class AngleDial extends JComponent {
    private float angle;
    private final float minAngle;
    private final float maxAngle;
    private final boolean wrap;
    private final int diameter;
    private final String label;
    private final List<ChangeListener> listeners = new ArrayList<>();
    private boolean dragging;

    /** Full 360° dial (wrapping). */
    public AngleDial(int diameter, float initialAngle, String label) {
        this(diameter, initialAngle, 0, 360, true, label);
    }

    /** Dial with a clamped range (no wrapping). */
    public AngleDial(int diameter, float initialAngle, float min, float max, String label) {
        this(diameter, initialAngle, min, max, false, label);
    }

    private AngleDial(int diameter, float initialAngle, float min, float max, boolean wrap, String label) {
        this.diameter = diameter;
        this.angle = initialAngle;
        this.minAngle = min;
        this.maxAngle = max;
        this.wrap = wrap;
        this.label = label;
        int w = diameter + 8;
        int h = diameter + 24; // extra space for label below
        setPreferredSize(new Dimension(w, h));
        setMinimumSize(new Dimension(w, h));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                updateAngleFromMouse(e.getX(), e.getY());
                dragging = true;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging) updateAngleFromMouse(e.getX(), e.getY());
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragging = false;
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    private void updateAngleFromMouse(int mx, int my) {
        int cx = getWidth() / 2;
        int cy = diameter / 2 + 4;
        double dx = mx - cx;
        double dy = cy - my; // flip Y (up = positive)
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        float newAngle = (float) deg;
        if (wrap) {
            newAngle = ((newAngle % 360) + 360) % 360;
        } else {
            newAngle = Math.max(minAngle, Math.min(maxAngle, newAngle));
        }
        if (Float.compare(newAngle, angle) != 0) {
            angle = newAngle;
            repaint();
            fireStateChanged();
        }
    }

    public float getAngle() { return angle; }
    public int getAngleInt() { return Math.round(angle); }

    public void setAngle(float angle) {
        if (wrap) {
            angle = ((angle % 360) + 360) % 360;
        } else {
            angle = Math.max(minAngle, Math.min(maxAngle, angle));
        }
        if (Float.compare(angle, this.angle) != 0) {
            this.angle = angle;
            repaint();
            fireStateChanged();
        }
    }

    public void addChangeListener(ChangeListener l) { listeners.add(l); }

    private void fireStateChanged() {
        ChangeEvent e = new ChangeEvent(this);
        for (ChangeListener l : listeners) l.stateChanged(e);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int cx = w / 2;
        int cy = diameter / 2 + 4;
        int r = diameter / 2;

        Color fg = UIManager.getColor("TextField.foreground");
        if (fg == null) fg = Color.DARK_GRAY;
        Color dimColor = UIManager.getColor("Label.disabledForeground");
        if (dimColor == null) dimColor = Color.GRAY;
        Color bg = getBackground() != null ? getBackground() : UIManager.getColor("Panel.background");

        // Fill circle background
        g2.setColor(bg);
        g2.fill(new Ellipse2D.Float(cx - r, cy - r, diameter, diameter));

        if (!wrap) {
            // Draw dimmed full circle, then highlight the valid arc
            g2.setColor(dimColor);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, diameter, diameter));

            // Highlight valid arc (thicker)
            // Arc2D uses AWT angles: 0=east (3 o'clock), positive=CCW — matches our convention
            float startArc = minAngle;
            float extentArc = maxAngle - minAngle;
            g2.setColor(fg);
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new Arc2D.Float(cx - r, cy - r, diameter, diameter, startArc, extentArc, Arc2D.OPEN));

            // Draw tick marks at min and max
            for (float deg : new float[]{minAngle, maxAngle}) {
                double rad = Math.toRadians(deg);
                float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(fg);
                g2.draw(new Line2D.Float(cx + cos * (r - 5), cy - sin * (r - 5),
                        cx + cos * (r + 2), cy - sin * (r + 2)));
            }
        } else {
            // Full circle outline
            g2.setColor(fg);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, diameter, diameter));

            // Tick marks at cardinal directions
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(dimColor);
            for (int deg = 0; deg < 360; deg += 90) {
                double rad = Math.toRadians(deg);
                float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                g2.draw(new Line2D.Float(cx + cos * (r - 4), cy - sin * (r - 4),
                        cx + cos * (r + 1), cy - sin * (r + 1)));
            }
        }

        // Draw angle line (needle) from center to edge
        double rad = Math.toRadians(angle);
        float lx = (float) Math.cos(rad) * (r - 2);
        float ly = (float) Math.sin(rad) * (r - 2);
        g2.setColor(fg);
        g2.setStroke(new BasicStroke(2f));
        g2.draw(new Line2D.Float(cx, cy, cx + lx, cy - ly));

        // Center dot
        int dotR = 3;
        g2.fillOval(cx - dotR, cy - dotR, dotR * 2, dotR * 2);

        // Label + value below circle
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
        g2.setColor(fg);
        String text = label + " " + Math.round(angle) + "\u00B0";
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        g2.drawString(text, cx - tw / 2, cy + r + fm.getAscent() + 2);

        g2.dispose();
    }
}
