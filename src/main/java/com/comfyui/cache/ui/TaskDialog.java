package com.comfyui.cache.ui;

import com.comfyui.cache.FileUtil;
import com.comfyui.cache.ProgressSink;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.function.Consumer;

/** 带进度和取消按钮的模态窗口，长耗时操作都在后台线程里跑。 */
public final class TaskDialog extends JDialog {

    public interface Task {
        String run(ProgressSink sink) throws Exception;
    }

    private final JTextArea messageArea = new JTextArea(2, 44);
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton cancelButton = Theme.button("取消", null);
    private final Task task;
    private final Consumer<String> onSuccess;
    private final Consumer<Throwable> onFailure;
    private volatile boolean cancelled;

    private TaskDialog(Window owner, String title, String initialMessage, Task task,
                       Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.task = task;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(Theme.PANEL);
        content.setBorder(Theme.padded(16, 18, 14, 18));

        messageArea.setText(initialMessage);
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setFont(Theme.FONT);
        messageArea.setForeground(Theme.MUTED);
        messageArea.setBackground(Theme.PANEL);
        messageArea.setBorder(null);
        content.add(messageArea, BorderLayout.NORTH);

        bar.setIndeterminate(true);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(420, 18));
        content.add(bar, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        south.add(cancelButton, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        cancelButton.addActionListener(e -> {
            cancelled = true;
            cancelButton.setEnabled(false);
            cancelButton.setText("正在取消…");
            messageArea.setText("正在取消，请稍候…");
        });

        setContentPane(content);
        setResizable(false);
        pack();
        setLocationRelativeTo(owner);
    }

    public static void run(Window owner, String title, String initialMessage, Task task,
                           Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        TaskDialog dialog = new TaskDialog(owner, title, initialMessage, task, onSuccess, onFailure);
        dialog.runTask();
    }

    private void runTask() {
        Thread worker = new Thread(() -> {
            String summary;
            try {
                summary = task.run(new Sink());
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    dispose();
                    onFailure.accept(error);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                dispose();
                onSuccess.accept(summary);
            });
        }, "comfyui-cache-task");
        worker.setDaemon(true);
        worker.start();
        setVisible(true);
    }

    private final class Sink implements ProgressSink {
        @Override
        public void message(String message) {
            SwingUtilities.invokeLater(() -> {
                if (!cancelled && message != null) {
                    messageArea.setText(message);
                }
            });
        }

        @Override
        public void progress(long done, long total) {
            SwingUtilities.invokeLater(() -> {
                if (cancelled) {
                    return;
                }
                if (total <= 0) {
                    if (!bar.isIndeterminate()) {
                        bar.setIndeterminate(true);
                        bar.setString("");
                    }
                    return;
                }
                if (bar.isIndeterminate()) {
                    bar.setIndeterminate(false);
                }
                bar.setValue((int) Math.min(1000, Math.max(0, done * 1000 / total)));
                bar.setString(FileUtil.humanSize(done) + " / " + FileUtil.humanSize(total));
            });
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
