package com.comfyui.cache.ui;

import com.comfyui.cache.LinkSupport;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 打开文件夹、复制到剪贴板之类的小工具。 */
public final class Openers {

    private Openers() {
    }

    public static void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** 打开文件所在的位置；Windows 上会直接在资源管理器里选中该文件。 */
    public static void reveal(Path path) {
        try {
            if (path == null) {
                return;
            }
            Path absolute = path.toAbsolutePath().normalize();
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                absolute = absolute.getParent();
            }
            if (absolute == null) {
                return;
            }
            if (LinkSupport.IS_WINDOWS) {
                Path parent = Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                        ? absolute : absolute.getParent();
                if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                    new ProcessBuilder("explorer.exe", absolute.toString()).start();
                } else if (parent != null) {
                    new ProcessBuilder("explorer.exe", "/select," + absolute).start();
                }
            } else if (Desktop.isDesktopSupported()) {
                Path parent = Files.isDirectory(absolute) ? absolute : absolute.getParent();
                if (parent != null) {
                    Desktop.getDesktop().open(parent.toFile());
                }
            }
        } catch (IOException ignored) {
            // 打不开就算了，不影响主流程
        }
    }

    /** 用系统默认程序打开某个文件或文件夹。 */
    public static void open(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else if (LinkSupport.IS_WINDOWS) {
                new ProcessBuilder("explorer.exe", path.toString()).start();
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
