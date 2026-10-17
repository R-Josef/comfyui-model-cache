package com.comfyui.cache.ui;

import com.comfyui.cache.CacheEntry;
import com.comfyui.cache.CacheSource;
import com.comfyui.cache.EntryStatus;
import com.comfyui.cache.FileUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 下方缓存管理表格。 */
public final class CacheTablePanel extends JPanel {

    public record Row(CacheSource source, CacheEntry entry, EntryStatus status) {
    }

    public interface Listener {
        void onUncache(List<Row> rows);

        void onVerifyAll();

        void onReveal(Row row);
    }

    private final Listener listener;
    private final RowTableModel model = new RowTableModel();
    private final JTable table;
    private final JButton uncacheButton = Theme.button("取消缓存", Icons.of(Icons.TRASH, Theme.ERROR));
    private final JButton revealButton = Theme.button("打开缓存位置", Icons.of(Icons.OPEN, Theme.MUTED));
    private final JButton verifyButton = Theme.button("重新检查", Icons.of(Icons.CHECK, Theme.MUTED));
    private final JLabel summary = Theme.muted("");

    public CacheTablePanel(Listener listener) {
        this.listener = listener;
        setBackground(Theme.PANEL);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));

        table = new JTable(model) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    Row data = model.get(convertRowIndexToModel(row));
                    component.setBackground(Theme.PANEL);
                    component.setForeground(switch (data.status().state()) {
                        case ATTENTION -> Theme.WARN;
                        case BROKEN -> Theme.ERROR;
                        case NOT_CACHED -> Theme.MUTED;
                        default -> Theme.TEXT;
                    });
                }
                return component;
            }
        };
        table.setFont(Theme.FONT);
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(Theme.LINE);
        table.setIntercellSpacing(new Dimension(8, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setSelectionBackground(Theme.ACCENT_SOFT);
        table.setSelectionForeground(Theme.TEXT);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(Theme.FONT_BOLD);
        table.getTableHeader().setBackground(Theme.BG);
        table.getTableHeader().setPreferredSize(new Dimension(0, 28));

        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                Component component = super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
                long size = value instanceof Long l ? l : 0;
                setText(size <= 0 ? "未知" : FileUtil.humanSize(size));
                return component;
            }
        };
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(4).setCellRenderer(right);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);
        table.getColumnModel().getColumn(6).setPreferredWidth(140);

        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && listener != null) {
                    List<Row> rows = selectedRows();
                    if (!rows.isEmpty()) {
                        listener.onReveal(rows.get(0));
                    }
                }
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(Theme.padded(8, 12, 6, 12));
        header.add(Theme.sectionTitle("缓存列表"), BorderLayout.WEST);
        header.add(summary, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setBackground(Theme.PANEL);
        buttons.setBorder(Theme.padded(8, 12, 10, 12));
        buttons.add(uncacheButton);
        buttons.add(revealButton);
        buttons.add(verifyButton);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        uncacheButton.addActionListener(e -> {
            if (listener != null) {
                listener.onUncache(selectedRows());
            }
        });
        revealButton.addActionListener(e -> {
            List<Row> rows = selectedRows();
            if (listener != null && !rows.isEmpty()) {
                listener.onReveal(rows.get(0));
            }
        });
        verifyButton.addActionListener(e -> {
            if (listener != null) {
                listener.onVerifyAll();
            }
        });
        updateButtons();
    }

    public void setRows(List<Row> rows) {
        model.setRows(rows);
        long total = 0;
        for (Row row : rows) {
            if (row.entry().size() > 0) {
                total += row.entry().size();
            }
        }
        summary.setText(rows.isEmpty() ? "还没有缓存任何模型"
                : "已缓存 " + rows.size() + " 项 · 约 " + FileUtil.humanSize(total));
        updateButtons();
    }

    private void updateButtons() {
        boolean hasSelection = table.getSelectedRowCount() > 0;
        uncacheButton.setEnabled(hasSelection);
        revealButton.setEnabled(hasSelection);
        verifyButton.setEnabled(model.getRowCount() > 0);
    }

    private List<Row> selectedRows() {
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int viewRow : table.getSelectedRows()) {
            indexes.add(table.convertRowIndexToModel(viewRow));
        }
        List<Row> rows = new ArrayList<>();
        for (int index : indexes) {
            rows.add(model.get(index));
        }
        return rows;
    }

    public void selectEntry(CacheEntry entry) {
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.get(i).entry() == entry) {
                int viewRow = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(viewRow, viewRow);
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                return;
            }
        }
    }

    private static final class RowTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"状态", "项目", "模型目录", "类型", "大小", "链接", "缓存时间"};
        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> rows) {
            this.rows = new ArrayList<>(rows);
            fireTableDataChanged();
        }

        Row get(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 4 ? Long.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> statusText(row.status());
                case 1 -> row.entry().rel();
                case 2 -> row.source().displayName();
                case 3 -> row.entry().kindLabel();
                case 4 -> row.entry().size();
                case 5 -> row.entry().link().label();
                case 6 -> row.entry().cachedAt();
                default -> "";
            };
        }

        private static String statusText(EntryStatus status) {
            if (status.text().isEmpty()) {
                return status.state().label();
            }
            return switch (status.state()) {
                case OK, NOT_CACHED -> status.text();
                case ATTENTION -> "需注意：" + status.text();
                case BROKEN -> "异常：" + status.text();
            };
        }
    }
}
