package com.comfyui.cache.ui;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/** 统一风格的提示框。 */
public final class Dialogs {

    private Dialogs() {
    }

    public static void info(Component parent, String title, String message) {
        show(parent, title, message, JOptionPane.INFORMATION_MESSAGE);
    }

    public static void warn(Component parent, String title, String message) {
        show(parent, title, message, JOptionPane.WARNING_MESSAGE);
    }

    public static void error(Component parent, String title, String message) {
        show(parent, title, message, JOptionPane.ERROR_MESSAGE);
    }

    public static boolean confirm(Component parent, String title, String message, String okText) {
        Object content = content(message);
        Object[] options = {okText, "取消"};
        int result = JOptionPane.showOptionDialog(parent, content, title,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        return result == 0;
    }

    private static void show(Component parent, String title, String message, int type) {
        JOptionPane.showMessageDialog(parent, content(message), title, type);
    }

    private static Object content(String message) {
        if (!message.contains("\n") && message.length() <= 70) {
            JLabel label = new JLabel(message);
            label.setFont(Theme.FONT);
            return label;
        }
        JTextArea area = new JTextArea(message, 0, 42);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.FONT);
        area.setForeground(Theme.TEXT);
        area.setBackground(Theme.PANEL);
        area.setBorder(null);
        int lines = Math.min(22, message.split("\n").length + message.length() / 42 + 1);
        area.setRows(Math.max(2, lines));
        if (message.length() > 700) {
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(null);
            scroll.setPreferredSize(new Dimension(560, 320));
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(scroll, BorderLayout.CENTER);
            return wrapper;
        }
        area.setAlignmentX(SwingConstants.LEFT);
        return area;
    }
}
