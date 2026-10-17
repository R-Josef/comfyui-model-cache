package com.comfyui.cache.ui;

import com.comfyui.cache.CacheEngine;
import com.comfyui.cache.CacheSource;
import com.comfyui.cache.EntryStatus;
import com.comfyui.cache.ScanNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 左侧模型文件树。 */
public final class BrowserPanel extends JPanel {

    public interface Listener {
        void onSelectionChanged(ScanNode node);
    }

    private static final Icon FOLDER_ICON = Icons.of(Icons.FOLDER, Theme.MUTED);
    private static final Icon FOLDER_LINK_ICON = Icons.of(Icons.FOLDER_LINK, Theme.OK);
    private static final Icon FILE_ICON = Icons.of(Icons.FILE, Theme.MUTED);
    private static final Icon FILE_FAINT_ICON = Icons.of(Icons.FILE, Theme.FAINT);
    private static final Icon LINK_ICON = Icons.of(Icons.LINK, Theme.OK);
    private static final Icon BROKEN_ICON = Icons.of(Icons.WARN, Theme.ERROR);

    private final Listener listener;
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("模型目录")));
    private final JTextField search = new JTextField();
    private final JCheckBox onlyModels = Theme.checkBox("仅显示模型文件", true);
    private final JLabel busyLabel = Theme.muted("正在扫描模型目录…");
    private final Timer debounce;
    private ScanNode scan;
    private Map<String, EntryStatus> statuses = new HashMap<>();
    private Path modelsDir;
    private String emptyHint = "还没有选择模型目录";

    public BrowserPanel(Listener listener) {
        this.listener = listener;
        setBackground(Theme.PANEL);
        setLayout(new BorderLayout());

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(Theme.PANEL);
        header.setBorder(Theme.padded(10, 12, 8, 12));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(Theme.sectionTitle("模型文件"), BorderLayout.WEST);
        titleRow.add(onlyModels, BorderLayout.EAST);
        header.add(titleRow);
        header.add(Box.createVerticalStrut(8));

        search.setFont(Theme.FONT);
        search.setPreferredSize(new Dimension(200, 30));
        Theme.placeholder(search, "搜索文件名或路径…");
        header.add(search);
        header.add(Box.createVerticalStrut(6));

        busyLabel.setVisible(false);
        header.add(busyLabel);
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setFont(Theme.FONT);
        tree.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 6));
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(e -> {
            if (listener != null) {
                listener.onSelectionChanged(selectedNode());
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);

        debounce = new Timer(220, e -> rebuild());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        onlyModels.addActionListener(e -> rebuild());
    }

    public void setBusy(boolean busy, String message) {
        busyLabel.setText(message == null ? "正在扫描模型目录…" : message);
        busyLabel.setVisible(busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    public void setEmptyHint(String hint) {
        this.emptyHint = hint;
        if (scan == null) {
            rebuild();
        }
    }

    public void setOnlyModelFiles(boolean value) {
        onlyModels.setSelected(value);
    }

    public boolean onlyModelFiles() {
        return onlyModels.isSelected();
    }

    public void setData(ScanNode root, Map<String, EntryStatus> statusMap, Path modelsDir) {
        this.scan = root;
        this.statuses = statusMap == null ? new HashMap<>() : statusMap;
        this.modelsDir = modelsDir;
        rebuild();
        setBusy(false, null);
    }

    public ScanNode getSelectedNode() {
        return selectedNode();
    }

    public String getSelectedRel() {
        ScanNode node = selectedNode();
        return node == null ? null : node.rel;
    }

    public void selectRelative(String rel) {
        if (rel == null || rel.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode node = findNode(rel);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    // ------------------------------------------------------------- 内部实现

    private ScanNode selectedNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof ScanNode scanNode) {
            return scanNode;
        }
        return null;
    }

    private DefaultMutableTreeNode findNode(String rel) {
        Object root = tree.getModel().getRoot();
        if (!(root instanceof DefaultMutableTreeNode rootNode)) {
            return null;
        }
        return findNode(rootNode, rel);
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, String rel) {
        if (node.getUserObject() instanceof ScanNode scanNode && scanNode.rel.equals(rel)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode found = findNode((DefaultMutableTreeNode) node.getChildAt(i), rel);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void rebuild() {
        String keepRel = getSelectedRel();
        if (scan == null) {
            DefaultMutableTreeNode hint = new DefaultMutableTreeNode(emptyHint);
            tree.setModel(new DefaultTreeModel(hint));
            tree.setRootVisible(true);
            tree.setEnabled(false);
            return;
        }
        tree.setEnabled(true);
        DefaultMutableTreeNode root = build(scan);
        tree.setModel(new DefaultTreeModel(root));
        expandDefault(root);
        if (keepRel != null) {
            selectRelative(keepRel);
        }
        if (tree.getSelectionPath() == null && root.getChildCount() > 0) {
            tree.setSelectionRow(0);
        }
    }

    private boolean isFiltering() {
        return onlyModels.isSelected() || !Theme.text(search).isEmpty();
    }

    private boolean visibleFile(ScanNode node) {
        if (node.backup) {
            return false;
        }
        String query = Theme.text(search).toLowerCase();
        if (!query.isEmpty() && !node.rel.toLowerCase().contains(query) && !node.name.toLowerCase().contains(query)) {
            return false;
        }
        if (onlyModels.isSelected() && !CacheEngine.isModelFile(node.name)
                && !statuses.containsKey(CacheSource.key(node.rel))) {
            return false;
        }
        return true;
    }

    private DefaultMutableTreeNode build(ScanNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        if (node.directory) {
            for (ScanNode child : node.children) {
                if (child.directory) {
                    DefaultMutableTreeNode childNode = build(child);
                    if (childNode != null) {
                        treeNode.add(childNode);
                    }
                } else if (visibleFile(child)) {
                    treeNode.add(new DefaultMutableTreeNode(child));
                }
            }
            if (!node.rel.isEmpty() && isFiltering() && treeNode.getChildCount() == 0) {
                return null;
            }
        }
        return treeNode;
    }

    private void expandDefault(DefaultMutableTreeNode root) {
        int count = countNodes(root);
        boolean searchActive = !Theme.text(search).isEmpty();
        if (searchActive || count <= 1500) {
            expandAll(root);
        } else {
            tree.expandPath(new TreePath(root.getPath()));
            for (int i = 0; i < root.getChildCount() && i < 40; i++) {
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(i)).getPath()));
            }
        }
    }

    private void expandAll(DefaultMutableTreeNode node) {
        tree.expandPath(new TreePath(node.getPath()));
        for (int i = 0; i < node.getChildCount(); i++) {
            expandAll((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    private int countNodes(DefaultMutableTreeNode node) {
        int count = 1;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countNodes((DefaultMutableTreeNode) node.getChildAt(i));
        }
        return count;
    }

    private final class Renderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(owner, value, selected, expanded, leaf, row, hasFocus);
            Object user = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (!(user instanceof ScanNode scanNode)) {
                setFont(Theme.FONT_MEDIUM);
                setForeground(Theme.MUTED);
                setIcon(FOLDER_ICON);
                setText(String.valueOf(user));
                return this;
            }
            setFont(Theme.FONT);
            EntryStatus status = statuses.get(CacheSource.key(scanNode.rel));
            StringBuilder text = new StringBuilder(scanNode.name);
            boolean hasStatus = status != null && status.state() != EntryStatus.State.NOT_CACHED;
            if (scanNode.link && !hasStatus) {
                text.append(scanNode.directory ? "   （目录链接）" : "   （链接）");
            } else if (scanNode.backup) {
                text.append("   （备份）");
            }
            if (hasStatus) {
                text.append("   ·  ").append(status.text());
            }
            setText(text.toString());

            if (scanNode.directory) {
                setIcon(scanNode.link ? FOLDER_LINK_ICON : FOLDER_ICON);
            } else if (status != null && status.state() == EntryStatus.State.BROKEN) {
                setIcon(BROKEN_ICON);
            } else if (scanNode.link || (status != null && status.state() == EntryStatus.State.OK)) {
                setIcon(LINK_ICON);
            } else if (scanNode.backup || !CacheEngine.isModelFile(scanNode.name)) {
                setIcon(FILE_FAINT_ICON);
            } else {
                setIcon(FILE_ICON);
            }

            if (!selected) {
                Color color = Theme.TEXT;
                if (scanNode.link || (status != null && status.state() == EntryStatus.State.OK)) {
                    color = Theme.OK;
                }
                if (status != null && status.state() == EntryStatus.State.ATTENTION) {
                    color = Theme.WARN;
                }
                if (status != null && status.state() == EntryStatus.State.BROKEN) {
                    color = Theme.ERROR;
                }
                if (status == null && !scanNode.directory && !CacheEngine.isModelFile(scanNode.name)) {
                    color = Theme.FAINT;
                }
                setForeground(color);
            }
            return this;
        }
    }
}
