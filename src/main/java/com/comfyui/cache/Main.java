package com.comfyui.cache;

import com.comfyui.cache.ui.Dialogs;
import com.comfyui.cache.ui.MainFrame;
import com.comfyui.cache.ui.Theme;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

/** 程序入口。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Theme.install();
        Path databaseFile = resolveDatabaseFile(args);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            SwingUtilities.invokeLater(() -> Dialogs.error(null, "ComfyUI 模型缓存",
                    "出现了一个未预料的错误：\n\n" + error));
        });
        SwingUtilities.invokeLater(() -> {
            try {
                CacheDatabase db = CacheDatabase.load(databaseFile);
                new MainFrame(db).start();
            } catch (Throwable error) {
                error.printStackTrace();
                Dialogs.error(null, "ComfyUI 模型缓存", "程序启动失败：\n\n" + error);
                System.exit(1);
            }
        });
    }

    /** 支持 {@code --db=<路径>} 或 {@code --db 路径} 指定数据文件位置。 */
    private static Path resolveDatabaseFile(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String value = null;
            if (args[i].startsWith("--db=")) {
                value = args[i].substring(5);
            } else if (args[i].equals("--db") && i + 1 < args.length) {
                value = args[i + 1];
            }
            if (value != null && !value.isBlank()) {
                Path path = Path.of(value).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    path = path.resolve(CacheDatabase.FILE_NAME);
                }
                return path;
            }
        }
        return CacheDatabase.defaultFile();
    }
}
