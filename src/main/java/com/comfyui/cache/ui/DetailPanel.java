package com.comfyui.cache.ui;

import com.comfyui.cache.CacheEntry;
import com.comfyui.cache.EntryStatus;
import com.comfyui.cache.ScanNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.nio.file.Path;

/** 右侧详情面板：显示选中模型的路径、状态，并提供缓存 / 取消缓存按钮。 */
public final class DetailPanel extends JPanel {

    public interface Listener {
        void onCacheRequested(ScanNode node);

        void onUncacheRequested(ScanNode node);
    }

    private final Listener listener;
    /** 初始给一个空格，否则空标签的高度是 0，会让布局算不出这一行该多高。 */
    private final JLabel nameLabel = new JLabel(" ");
    private final JLabel badge = new BadgeLabel();
    private final JLabel subtitle = Theme.muted(" ");
    private final JTextField modelPath = pathField();
    private final JTextField cachePath = pathField();
    private final JTextField backupPath = pathField();
    private final JButton openModel = Theme.iconButton(Icons.of(Icons.OPEN, Theme.MUTED), "在资源管理器中显示");
    private final JButton openCache = Theme.iconButton(Icons.of(Icons.OPEN, Theme.MUTED), "在资源管理器中显示");
    private final JButton openBackup = Theme.iconButton(Icons.of(Icons.OPEN, Theme.MUTED), "在资源管理器中显示");
    private final JLabel sizeValue = valueLabel();
    private final JLabel typeValue = valueLabel();
    private final JLabel timeValue = valueLabel();
    private final JLabel hintLabel = new JLabel();
    private final JButton cacheButton = Theme.primaryButton("缓存到高速盘", Icons.of(Icons.CACHE, Color.WHITE));
    private final JButton uncacheButton = Theme.button("取消缓存", Icons.of(Icons.TRASH, Theme.ERROR));
    private ScanNode current;
    private boolean cached;

    public DetailPanel(Listener listener) {
        this.listener = listener;
        setPreferredSize(new Dimension(330, 100));
        setLayout(new BorderLayout());
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.LINE),
                Theme.padded(14, 16, 14, 16)));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        nameLabel.setFont(Theme.FONT_TITLE);
        nameLabel.setForeground(Theme.TEXT);
        header.add(nameLabel, BorderLayout.CENTER);
        stretch(header);
        body.add(header);
        body.add(Box.createVerticalStrut(6));

        JPanel statusRow = new JPanel();
        statusRow.setLayout(new BoxLayout(statusRow, BoxLayout.X_AXIS));
        statusRow.setOpaque(false);
        compressible(subtitle);
        statusRow.add(badge);
        statusRow.add(Box.createHorizontalStrut(8));
        statusRow.add(subtitle);
        stretch(statusRow);
        body.add(statusRow);
        body.add(Box.createVerticalStrut(12));

        body.add(pathBlock("原始位置", modelPath, openModel));
        body.add(pathBlock("缓存位置", cachePath, openCache));
        body.add(pathBlock("备份文件", backupPath, openBackup));

        JPanel meta = new JPanel(new GridLayout(1, 3, 10, 0));
        meta.setOpaque(false);
        meta.add(metaBlock("大小", sizeValue));
        meta.add(metaBlock("类型", typeValue));
        meta.add(metaBlock("缓存时间", timeValue));
        stretch(meta);
        body.add(meta);
        body.add(Box.createVerticalStrut(14));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(cacheButton);
        actions.add(uncacheButton);
        stretch(actions);
        body.add(actions);
        body.add(Box.createVerticalStrut(12));

        hintLabel.setFont(Theme.FONT_SMALL);
        hintLabel.setForeground(Theme.MUTED);
        hintLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        stretch(hintLabel);
        body.add(hintLabel);

        add(body, BorderLayout.NORTH);

        cacheButton.addActionListener(e -> {
            if (current != null) {
                listener.onCacheRequested(current);
            }
        });
        uncacheButton.addActionListener(e -> {
            if (current != null) {
                listener.onUncacheRequested(current);
            }
        });
        openModel.addActionListener(e -> Openers.reveal(Path.of(modelPath.getText())));
        openCache.addActionListener(e -> Openers.reveal(Path.of(cachePath.getText())));
        openBackup.addActionListener(e -> Openers.reveal(Path.of(backupPath.getText())));
    }

    private static JTextField pathField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        field.setFont(Theme.FONT_MONO);
        field.setForeground(Theme.TEXT);
        field.setBackground(Theme.PANEL);
        field.setBorder(null);
        field.setCaretPosition(0);
        return field;
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("-");
        label.setFont(Theme.FONT);
        label.setForeground(Theme.TEXT);
        return label;
    }

    private JPanel pathBlock(String title, JTextField field, JButton open) {
        JPanel panel = new JPanel(new BorderLayout(6, 2));
        panel.setOpaque(false);
        JLabel label = Theme.muted(title);
        panel.add(label, BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(open, BorderLayout.EAST);
        panel.add(row, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 9, 0));
        stretch(panel);
        return panel;
    }

    /**
     * 纵向 BoxLayout 里，子组件必须把最大宽度放开才会横向铺满；
     * 同时把对齐方式统一到左侧，否则不同对齐的子组件会互相错位。
     */
    private static void stretch(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        int height = component.getPreferredSize().height;
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(1, height)));
    }

    /** 可以横向被压缩（文字会被裁切，靠悬停提示看全）的组件。 */
    private static void compressible(JComponent component) {
        int height = component.getPreferredSize().height;
        component.setMinimumSize(new Dimension(0, height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    private void updateHint(String html) {
        hintLabel.setText("<html><body style='width:245px'>" + html + "</body></html>");
        stretch(hintLabel);
        hintLabel.revalidate();
    }

    private JPanel metaBlock(String title, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setOpaque(false);
        panel.add(Theme.muted(title), BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    public void showEmpty(String message) {
        current = null;
        nameLabel.setText("未选择");
        subtitle.setText(" ");
        badge.setVisible(false);
        modelPath.setText("");
        cachePath.setText("");
        backupPath.setText("");
        sizeValue.setText("-");
        typeValue.setText("-");
        timeValue.setText("-");
        cacheButton.setEnabled(false);
        uncacheButton.setEnabled(false);
        nameLabel.setToolTipText(null);
        subtitle.setToolTipText(null);
        modelPath.setToolTipText(null);
        cachePath.setToolTipText(null);
        backupPath.setToolTipText(null);
        timeValue.setToolTipText(null);
        updateHint(message);
    }

    public void showNode(ScanNode node, EntryStatus status, CacheEntry entry,
                         Path modelFilePath, Path cacheFilePath, Path backupFilePath) {
        current = node;
        cached = entry != null;
        nameLabel.setText(node.name);
        nameLabel.setFont(node.name.length() > 20 ? Theme.FONT_BOLD : Theme.FONT_TITLE);
        nameLabel.setToolTipText(node.name);
        subtitle.setText(modelFilePath == null ? " " : modelFilePath.getParent().toString());
        subtitle.setToolTipText(modelFilePath == null ? null : modelFilePath.getParent().toString());
        badge.setVisible(true);
        badge.setText(status.state().label());
        badge.setForeground(switch (status.state()) {
            case OK -> Theme.OK;
            case ATTENTION -> Theme.WARN;
            case BROKEN -> Theme.ERROR;
            case NOT_CACHED -> Theme.MUTED;
        });
        ((BadgeLabel) badge).accent = switch (status.state()) {
            case OK -> Theme.OK_SOFT;
            case ATTENTION -> Theme.WARN_SOFT;
            case BROKEN -> Theme.ERROR_SOFT;
            case NOT_CACHED -> Theme.BG;
        };

        modelPath.setText(modelFilePath == null ? "" : modelFilePath.toString());
        modelPath.setCaretPosition(0);
        modelPath.setToolTipText(modelFilePath == null ? null : modelFilePath.toString());
        cachePath.setText(cacheFilePath == null ? "" : cacheFilePath.toString());
        cachePath.setCaretPosition(0);
        cachePath.setToolTipText(cacheFilePath == null ? null : cacheFilePath.toString());
        backupPath.setText(backupFilePath == null ? "" : backupFilePath.toString());
        backupPath.setCaretPosition(0);
        backupPath.setToolTipText(backupFilePath == null ? null : backupFilePath.toString());

        sizeValue.setText(node.directory ? "-" : com.comfyui.cache.FileUtil.humanSize(node.size));
        typeValue.setText(node.directory ? "文件夹" : "模型文件");
        timeValue.setText(cached ? shortTime(entry.cachedAt()) : "-");
        timeValue.setToolTipText(cached ? entry.cachedAt() : null);

        if (node.link) {
            cacheButton.setEnabled(false);
            uncacheButton.setEnabled(cached);
            updateHint("这个位置已经是一个链接，ComfyUI 会自动跟随它读取缓存盘上的文件。");
            return;
        }
        if (cached) {
            cacheButton.setEnabled(false);
            uncacheButton.setEnabled(true);
            updateHint(escape(status.fullText()));
            return;
        }
        cacheButton.setEnabled(true);
        uncacheButton.setEnabled(false);
        if (node.backup) {
            cacheButton.setEnabled(false);
            updateHint("以 # 结尾的是缓存备份文件，ComfyUI 不会加载它。"
                    + "取消对应的缓存后它会自动改回原来的名字。");
        } else if (node.directory) {
            updateHint("整体缓存这个目录：内容会移动到缓存盘，原位置变成目录联接"
                    + "（Windows 不需要管理员权限）。适合把整个模型分类目录放进固态盘。");
        } else {
            updateHint("缓存后会把这个文件复制到缓存盘，原文件改名为 "
                    + escape(node.name + "#") + " 作为备份，并在原位置建立符号链接。"
                    + "原文件会保留，因此需要两份磁盘空间。");
        }
    }

    /** 状态栏空间有限，时间只显示到分钟，完整值放在悬停提示里。 */
    private static String shortTime(String stamp) {
        if (stamp == null || stamp.length() < 16) {
            return stamp == null ? "-" : stamp;
        }
        return stamp.substring(5, 16);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 圆角状态标签。 */
    private static final class BadgeLabel extends JLabel {
        private Color accent = Theme.BG;

        BadgeLabel() {
            setFont(Theme.FONT_SMALL);
            setHorizontalAlignment(CENTER);
            setBorder(BorderFactory.createEmptyBorder());
            // 宽度固定成最宽的状态文字，避免文字变化时布局来不及更新
            FontMetrics metrics = getFontMetrics(Theme.FONT_SMALL);
            int width = 0;
            for (String text : new String[]{"未缓存", "已缓存", "需注意", "异常"}) {
                width = Math.max(width, metrics.stringWidth(text));
            }
            Dimension size = new Dimension(width + 22, 22);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(accent);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }

    }
}
