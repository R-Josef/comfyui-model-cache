package com.comfyui.cache.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/** 全部图标都用 Java2D 现画，不依赖任何外部资源或字体。 */
public final class Icons {

    /** 在 16x16 的坐标里绘制。 */
    public interface Painter {
        void paint(Graphics2D g);
    }

    private static final class VectorIcon implements Icon {
        private final Painter painter;
        private final int size;
        private final Color color;
        private final float width;

        VectorIcon(Painter painter, int size, Color color, float width) {
            this.painter = painter;
            this.size = size;
            this.color = color;
            this.width = width;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                double scale = size / 16.0;
                g2.scale(scale, scale);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                painter.paint(g2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private Icons() {
    }

    public static Icon of(Painter painter, Color color) {
        return new VectorIcon(painter, 16, color, 1.35f);
    }

    public static Icon of(Painter painter, Color color, int size) {
        return new VectorIcon(painter, size, color, 1.35f);
    }

    public static Icon of(Painter painter, Color color, int size, float stroke) {
        return new VectorIcon(painter, size, color, stroke);
    }

    // ------------------------------------------------------------- 各个图标

    public static final Painter FOLDER = g -> {
        GeneralPath path = new GeneralPath();
        path.moveTo(2.0, 13.0);
        path.lineTo(2.0, 4.4);
        path.quadTo(2.0, 3.2, 3.2, 3.2);
        path.lineTo(6.0, 3.2);
        path.lineTo(7.4, 5.4);
        path.lineTo(12.8, 5.4);
        path.quadTo(14.0, 5.4, 14.0, 6.6);
        path.lineTo(14.0, 13.0);
        path.quadTo(14.0, 14.0, 13.0, 14.0);
        path.lineTo(3.0, 14.0);
        path.quadTo(2.0, 14.0, 2.0, 13.0);
        path.closePath();
        g.draw(path);
    };

    public static final Painter FILE = g -> {
        GeneralPath path = new GeneralPath();
        path.moveTo(4.0, 2.2);
        path.lineTo(9.4, 2.2);
        path.lineTo(12.6, 5.4);
        path.lineTo(12.6, 13.8);
        path.lineTo(4.0, 13.8);
        path.closePath();
        g.draw(path);
        g.draw(new Line2D.Double(9.2, 2.4, 9.2, 5.5));
        g.draw(new Line2D.Double(9.2, 5.5, 12.4, 5.5));
    };

    public static final Painter LINK = g -> {
        g.draw(new RoundRectangle2D.Double(1.6, 5.9, 7.2, 4.4, 4.4, 4.4));
        g.draw(new RoundRectangle2D.Double(7.2, 5.7, 7.2, 4.4, 4.4, 4.4));
    };

    public static final Painter DISK = g -> {
        g.draw(new RoundRectangle2D.Double(2.0, 3.4, 12.0, 9.2, 2.4, 2.4));
        g.draw(new Line2D.Double(2.0, 10.4, 14.0, 10.4));
        g.draw(new Line2D.Double(5.4, 6.9, 10.6, 6.9));
    };

    public static final Painter CACHE = g -> {
        g.draw(new Line2D.Double(8.0, 2.2, 8.0, 9.0));
        GeneralPath arrow = new GeneralPath();
        arrow.moveTo(4.8, 6.2);
        arrow.lineTo(8.0, 9.4);
        arrow.lineTo(11.2, 6.2);
        g.draw(arrow);
        g.draw(new Line2D.Double(2.6, 13.2, 13.4, 13.2));
    };

    public static final Painter TRASH = g -> {
        g.draw(new Line2D.Double(2.4, 4.4, 13.6, 4.4));
        g.draw(new Line2D.Double(6.2, 4.4, 6.2, 2.6));
        g.draw(new Line2D.Double(9.8, 4.4, 9.8, 2.6));
        g.draw(new Line2D.Double(6.2, 2.6, 9.8, 2.6));
        GeneralPath body = new GeneralPath();
        body.moveTo(3.6, 4.6);
        body.lineTo(4.4, 13.4);
        body.lineTo(11.6, 13.4);
        body.lineTo(12.4, 4.6);
        g.draw(body);
        g.draw(new Line2D.Double(6.6, 6.8, 6.8, 11.2));
        g.draw(new Line2D.Double(9.4, 6.8, 9.2, 11.2));
    };

    public static final Painter REFRESH = g -> {
        g.draw(new Arc2D.Double(2.6, 2.6, 10.8, 10.8, 55, 275, Arc2D.OPEN));
        GeneralPath head = new GeneralPath();
        head.moveTo(8.2, 1.4);
        head.lineTo(10.6, 3.6);
        head.lineTo(8.0, 5.0);
        g.draw(head);
    };

    public static final Painter PLUS = g -> {
        g.draw(new Line2D.Double(8.0, 3.4, 8.0, 12.6));
        g.draw(new Line2D.Double(3.4, 8.0, 12.6, 8.0));
    };

    public static final Painter MINUS = g -> g.draw(new Line2D.Double(3.4, 8.0, 12.6, 8.0));

    public static final Painter SEARCH = g -> {
        g.draw(new Ellipse2D.Double(2.6, 2.6, 8.4, 8.4));
        g.draw(new Line2D.Double(9.4, 9.4, 13.8, 13.8));
    };

    public static final Painter INFO = g -> {
        g.draw(new Ellipse2D.Double(2.0, 2.0, 12.0, 12.0));
        g.draw(new Line2D.Double(8.0, 7.2, 8.0, 11.4));
        g.draw(new Line2D.Double(8.0, 4.6, 8.0, 5.2));
    };

    public static final Painter WARN = g -> {
        GeneralPath path = new GeneralPath();
        path.moveTo(8.0, 2.2);
        path.lineTo(14.4, 13.4);
        path.lineTo(1.6, 13.4);
        path.closePath();
        g.draw(path);
        g.draw(new Line2D.Double(8.0, 6.2, 8.0, 9.8));
        g.draw(new Line2D.Double(8.0, 11.4, 8.0, 11.9));
    };

    public static final Painter CHECK = g -> {
        GeneralPath path = new GeneralPath();
        path.moveTo(3.0, 8.6);
        path.lineTo(6.4, 12.0);
        path.lineTo(13.2, 4.4);
        g.draw(path);
    };

    public static final Painter OPEN = g -> {
        GeneralPath box = new GeneralPath();
        box.moveTo(8.6, 3.2);
        box.lineTo(3.0, 3.2);
        box.lineTo(3.0, 13.0);
        box.lineTo(12.8, 13.0);
        box.lineTo(12.8, 7.4);
        g.draw(box);
        g.draw(new Line2D.Double(7.4, 8.6, 13.6, 2.4));
        g.draw(new Line2D.Double(9.4, 2.4, 13.6, 2.4));
        g.draw(new Line2D.Double(13.6, 2.4, 13.6, 6.6));
    };

    public static final Painter DATABASE = g -> {
        g.draw(new Ellipse2D.Double(2.6, 2.0, 10.8, 4.0));
        g.draw(new Line2D.Double(2.6, 4.0, 2.6, 12.2));
        g.draw(new Line2D.Double(13.4, 4.0, 13.4, 12.2));
        g.draw(new Arc2D.Double(2.6, 10.2, 10.8, 4.0, 180, 180, Arc2D.OPEN));
    };

    public static final Painter FOLDER_LINK = g -> {
        GeneralPath path = new GeneralPath();
        path.moveTo(1.6, 12.4);
        path.lineTo(1.6, 4.2);
        path.quadTo(1.6, 3.0, 2.8, 3.0);
        path.lineTo(5.6, 3.0);
        path.lineTo(7.0, 5.0);
        path.lineTo(12.2, 5.0);
        path.quadTo(13.4, 5.0, 13.4, 6.2);
        path.lineTo(13.4, 8.2);
        g.draw(path);
        g.draw(new RoundRectangle2D.Double(7.0, 9.0, 5.2, 3.2, 3.2, 3.2));
        g.draw(new RoundRectangle2D.Double(10.4, 8.8, 5.2, 3.2, 3.2, 3.2));
    };

    /** 程序图标（窗口 / 任务栏）。 */
    public static Icon appIcon(int size) {
        return new VectorIcon(g -> {
            g.setColor(new Color(0x2F6FEB));
            g.fill(new RoundRectangle2D.Double(0.6, 0.6, 14.8, 14.8, 4.0, 4.0));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            GeneralPath path = new GeneralPath();
            path.moveTo(3.0, 11.6);
            path.lineTo(3.0, 4.6);
            path.quadTo(3.0, 3.6, 4.0, 3.6);
            path.lineTo(6.4, 3.6);
            path.lineTo(7.6, 5.4);
            path.lineTo(12.0, 5.4);
            path.quadTo(13.0, 5.4, 13.0, 6.4);
            path.lineTo(13.0, 8.2);
            g.draw(path);
            g.draw(new Line2D.Double(9.6, 12.0, 12.0, 9.6));
            g.draw(new Line2D.Double(11.0, 9.4, 12.4, 9.4));
            g.draw(new Line2D.Double(12.4, 9.4, 12.4, 10.8));
        }, size, Color.WHITE, 1.5f);
    }

    /** 把程序图标画成图片，用于窗口 / 任务栏图标。 */
    public static java.awt.image.BufferedImage image(int size) {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            appIcon(size).paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image;
    }
}
