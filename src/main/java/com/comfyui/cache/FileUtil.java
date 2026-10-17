package com.comfyui.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 文件系统小工具集：所有递归遍历都先识别链接，绝不会穿过链接指向的目录。 */
public final class FileUtil {

    private FileUtil() {
    }

    /** 用户主动取消操作时抛出。 */
    public static final class CancelledException extends Exception {
        public CancelledException() {
            super("操作已取消");
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        return String.format("%.2f %s", value, units[unit]);
    }

    public static String humanSize(long bytes, boolean withBytes) {
        String text = humanSize(bytes);
        if (withBytes && bytes >= 0) {
            return text + "（" + String.format("%,d", bytes) + " 字节）";
        }
        return text;
    }

    /** 相对路径统一用当前系统的分隔符表示，便于直接显示。 */
    public static String relString(Path base, Path child) {
        try {
            Path rel = base.relativize(child);
            return rel.toString();
        } catch (IllegalArgumentException e) {
            return child.toString();
        }
    }

    public static Path nearestExisting(Path path) {
        Path p = path.toAbsolutePath().normalize();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        return p == null ? Path.of(System.getProperty("user.home")) : p;
    }

    public static long usableSpace(Path anyPathInsideVolume) {
        try {
            FileStore store = Files.getFileStore(nearestExisting(anyPathInsideVolume));
            return store.getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    public static String storeName(Path anyPathInsideVolume) {
        try {
            return Files.getFileStore(nearestExisting(anyPathInsideVolume)).name();
        } catch (IOException e) {
            return "?";
        }
    }

    public static boolean isSameStore(Path a, Path b) {
        try {
            return Files.getFileStore(nearestExisting(a)).equals(Files.getFileStore(nearestExisting(b)));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    /**
     * 递归删除。遇到符号链接 / 目录联接只删除链接本身，绝不进入链接指向的目录。
     * （Java 自带的 walkFileTree 在 Windows 上会跟随 junction，这里必须自己走。）
     */
    public static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (LinkSupport.isLink(target)) {
            Files.delete(target);
            return;
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                for (Path child : stream) {
                    children.add(child);
                }
            }
            for (Path child : children) {
                deleteTree(child);
            }
        }
        Files.delete(target);
    }

    /** 统计目录大小（不跟随链接）。 */
    public static long treeSize(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (LinkSupport.isLink(root)) {
            return 0;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return Files.size(root);
        }
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                total += treeSize(child);
            }
        }
        return total;
    }

    public static int countFiles(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || LinkSupport.isLink(root)) {
            return 0;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 1;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                count += countFiles(child);
            }
        }
        return count;
    }

    /** 删除空目录，一路向上直到 stopAt（不含 stopAt）为止。 */
    public static void pruneEmptyDirectories(Path start, Path stopAt) {
        Path stop = stopAt.toAbsolutePath().normalize();
        Path current = start == null ? null : start.toAbsolutePath().normalize();
        while (current != null && current.startsWith(stop) && !current.equals(stop)) {
            try {
                if (!isEmptyDirectory(current)) {
                    return;
                }
                Files.delete(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    /** 复制单个文件，带进度回调。 */
    public static void copyFile(Path source, Path target, long alreadyDone, long total, ProgressSink sink)
            throws IOException, CancelledException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] buffer = new byte[1024 * 1024];
        long done = alreadyDone;
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (sink != null) {
                    sink.checkCancelled();
                }
                out.write(buffer, 0, read);
                done += read;
                if (sink != null) {
                    sink.progress(done, total);
                }
            }
        }
    }

    /** 复制整棵目录树，返回复制后的总字节数。不跟随链接。 */
    public static long copyTree(Path sourceDir, Path targetDir, long baseDone, long total, ProgressSink sink)
            throws IOException, CancelledException {
        Files.createDirectories(targetDir);
        long done = baseDone;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                Path targetChild = targetDir.resolve(name);
                if (LinkSupport.isLink(child)) {
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    done = copyTree(child, targetChild, done, total, sink);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    copyFile(child, targetChild, done, total, sink);
                    done += Files.size(child);
                }
            }
        }
        return done;
    }

    /** 同目录改名，优先使用原子移动。 */
    public static void renameInPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            Files.move(from, to);
        }
    }

    public static BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isRegularFileNoFollow(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }
}
