package com.comfyui.cache.ui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;

/** 统一的配色、字体和小控件工厂。 */
public final class Theme {

    public static final Color BG = new Color(0xF4F6F9);
    public static final Color PANEL = Color.WHITE;
    public static final Color LINE = new Color(0xDCE1E8);
    public static final Color TEXT = new Color(0x1E2733);
    public static final Color MUTED = new Color(0x6B7684);
    public static final Color FAINT = new Color(0x93A0AE);
    public static final Color ACCENT = new Color(0x2F6FEB);
    public static final Color ACCENT_DARK = new Color(0x1F55C0);
    public static final Color ACCENT_SOFT = new Color(0xE9F0FE);
    public static final Color OK = new Color(0x1B7F4B);
    public static final Color OK_SOFT = new Color(0xE6F4EC);
    public static final Color WARN = new Color(0xA96A00);
    public static final Color WARN_SOFT = new Color(0xFDF3E2);
    public static final Color ERROR = new Color(0xC03A2B);
    public static final Color ERROR_SOFT = new Color(0xFBECEA);

    public static Font FONT;
    public static Font FONT_MEDIUM;
    public static Font FONT_BOLD;
    public static Font FONT_SMALL;
    public static Font FONT_TITLE;
    public static Font FONT_MONO;

    private Theme() {
    }

    public static void install() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观继续
        }
        String family = pickFamily();
        FONT = new Font(family, Font.PLAIN, 13);
        FONT_MEDIUM = new Font(family, Font.PLAIN, 13);
        FONT_BOLD = new Font(family, Font.BOLD, 13);
        FONT_SMALL = new Font(family, Font.PLAIN, 12);
        FONT_TITLE = new Font(family, Font.BOLD, 16);
        FONT_MONO = new Font(pickMonoFamily(), Font.PLAIN, 12);

        var defaults = UIManager.getLookAndFeelDefaults();
        List<Object> keys = new ArrayList<>(defaults.keySet());
        for (Object key : keys) {
            if (defaults.get(key) instanceof FontUIResource font) {
                int size = font.getSize();
                int scaled = size <= 11 ? 12 : size + 1;
                defaults.put(key, new FontUIResource(family, font.getStyle(), scaled));
            }
        }
        UIManager.put("Table.showGrid", false);
        UIManager.put("Table.intercellSpacing", new Dimension(1, 1));
    }

    private static String pickFamily() {
        List<String> available = families();
        String[] preferred = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
                "Source Han Sans SC", "Segoe UI", "Helvetica Neue", "Dialog"};
        for (String name : preferred) {
            if (available.contains(name)) {
                return name;
            }
        }
        return "Dialog";
    }

    private static String pickMonoFamily() {
        List<String> available = families();
        String[] preferred = {"JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "Monospaced"};
        for (String name : preferred) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.MONOSPACED;
    }

    private static List<String> families() {
        return List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    }

    // ------------------------------------------------------------- 控件工厂

    public static JButton primaryButton(String text, Icon icon) {
        return button(text, icon, true);
    }

    public static JButton button(String text, Icon icon) {
        return button(text, icon, false);
    }

    private static JButton button(String text, Icon icon, boolean primary) {
        JButton button = new JButton(text, icon);
        button.setFont(FONT);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? ACCENT_DARK : LINE),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        button.setBackground(primary ? ACCENT : PANEL);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setOpaque(true);
        button.setBorderPainted(true);
        if (icon != null) {
            button.setIconTextGap(7);
        }
        return button;
    }

    /** 只有图标的方形按钮。 */
    public static JButton iconButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(28, 28));
        button.setMinimumSize(new Dimension(28, 28));
        button.setMaximumSize(new Dimension(28, 28));
        button.setBackground(PANEL);
        button.setBorder(BorderFactory.createLineBorder(LINE));
        button.setOpaque(true);
        return button;
    }

    public static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT);
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_SMALL);
        label.setForeground(MUTED);
        return label;
    }

    public static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_BOLD);
        label.setForeground(TEXT);
        return label;
    }

    public static Border padded(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    public static void placeholder(JTextField field, String text) {
        field.putClientProperty("placeholder", text);
        field.setForeground(MUTED);
        field.setText(text);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (isPlaceholder(field)) {
                    field.setText("");
                    field.setForeground(TEXT);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().isEmpty()) {
                    field.setForeground(MUTED);
                    field.setText(text);
                }
            }
        });
    }

    public static boolean isPlaceholder(JTextField field) {
        Object placeholder = field.getClientProperty("placeholder");
        return placeholder != null && field.getText().equals(placeholder);
    }

    public static String text(JTextField field) {
        return isPlaceholder(field) ? "" : field.getText().trim();
    }

    public static void clearPlaceholder(JTextField field) {
        if (isPlaceholder(field)) {
            field.setText("");
            field.setForeground(TEXT);
        }
    }

    public static JCheckBox checkBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setFont(FONT);
        box.setForeground(TEXT);
        box.setOpaque(false);
        box.setFocusPainted(false);
        return box;
    }

    public static void setEnabledDeep(JComponent component, boolean enabled) {
        component.setEnabled(enabled);
        for (var child : component.getComponents()) {
            if (child instanceof JComponent jc) {
                setEnabledDeep(jc, enabled);
            }
        }
    }
}
