package com.comfyui.cache;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;

/**
 * 链接能力封装。
 *
 * <p>关键事实：
 * <ul>
 *   <li>Linux / macOS：创建符号链接不需要任何额外权限。</li>
 *   <li>Windows：创建符号链接需要“创建符号链接”特权，普通用户默认没有；
 *       打开“开发者模式”后会放开。目录联接（junction）则任何用户都能创建。</li>
 * </ul>
 */
public final class LinkSupport {

    public static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private LinkSupport() {
    }

    /** 链接方式。 */
    public enum LinkKind {
        /** 符号链接：Windows 需要权限，其它系统不需要。 */
        SYMLINK("符号链接"),
        /** Windows 目录联接：不需要任何权限，只能用于目录。 */
        JUNCTION("目录联接"),
        /** 硬链接：不需要任何权限，但只能在同一块磁盘上使用。 */
        HARD_LINK("硬链接");

        private final String label;

        LinkKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static LinkKind fromName(String name) {
            for (LinkKind kind : values()) {
                if (kind.name().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
            return SYMLINK;
        }
    }

    /** 当前环境的能力检测结果。 */
    public record Report(boolean fileSymlink, boolean dirSymlink, boolean junction, String fileSymlinkError) {

        public boolean fileLinksUsable() {
            return fileSymlink;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("文件符号链接：").append(fileSymlink ? "可用" : "不可用");
            sb.append("　目录符号链接：").append(dirSymlink ? "可用" : "不可用");
            if (IS_WINDOWS) {
                sb.append("　目录联接：").append(junction ? "可用" : "不可用");
            }
            return sb.toString();
        }
    }

    /** 在指定目录里真实地试一次创建链接，判断当前环境支持到什么程度。 */
    public static Report probe(Path workDir) {
        Path dir = workDir;
        if (dir == null) {
            dir = Path.of(System.getProperty("java.io.tmpdir"));
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Path fileTarget = dir.resolve(".comfyui-cache-probe-" + tag + ".txt");
        Path fileLink = dir.resolve(".comfyui-cache-probe-" + tag + ".lnk");
        Path dirTarget = dir.resolve(".comfyui-cache-probe-dir-" + tag);
        Path dirLink = dir.resolve(".comfyui-cache-probe-dirlnk-" + tag);
        Path junctionPath = dir.resolve(".comfyui-cache-probe-junc-" + tag);
        boolean fileSymlink = false;
        boolean dirSymlink = false;
        boolean junction = false;
        String fileError = "";
        try {
            Files.createDirectories(dir);
            Files.writeString(fileTarget, "probe");
            try {
                Files.createSymbolicLink(fileLink, fileTarget);
                fileSymlink = true;
            } catch (IOException | UnsupportedOperationException e) {
                fileError = describe(e);
            }
            Files.createDirectories(dirTarget);
            try {
                Files.createSymbolicLink(dirLink, dirTarget);
                dirSymlink = true;
            } catch (IOException | UnsupportedOperationException ignored) {
                // 目录符号链接不可用不是致命问题
            }
            if (IS_WINDOWS) {
                try {
                    createJunction(junctionPath, dirTarget);
                    junction = true;
                } catch (Exception ignored) {
                    junction = false;
                }
            }
        } catch (Exception e) {
            if (fileError.isEmpty()) {
                fileError = describe(e);
            }
        } finally {
            deleteQuietly(fileLink);
            deleteQuietly(fileTarget);
            deleteQuietly(dirLink);
            deleteQuietly(junctionPath);
            deleteQuietly(dirTarget);
        }
        return new Report(fileSymlink, dirSymlink, junction, fileError);
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path);
            }
        } catch (IOException ignored) {
            // 探测用的临时文件，删不掉也无所谓
        }
    }

    public static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (IS_WINDOWS && (message.contains("privilege") || message.contains("特权") || message.contains("权限"))) {
            return "当前用户没有创建链接的权限（Windows 需要开发者模式或管理员权限）";
        }
        return message;
    }

    /** 是否是符号链接或目录联接。 */
    public static boolean isLink(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                return true;
            }
            return isJunction(path);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isJunction(Path path) {
        if (!IS_WINDOWS) {
            return false;
        }
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    public static LinkKind kindOf(Path path) {
        if (Files.isSymbolicLink(path)) {
            return LinkKind.SYMLINK;
        }
        if (isJunction(path)) {
            return LinkKind.JUNCTION;
        }
        return null;
    }

    /** 链接（或联接）最终指向的位置；不是链接时返回 null。 */
    public static Path linkTarget(Path path) {
        if (Files.isSymbolicLink(path)) {
            try {
                Path raw = Files.readSymbolicLink(path);
                return raw.isAbsolute() ? raw.normalize() : path.getParent().resolve(raw).normalize();
            } catch (IOException e) {
                return null;
            }
        }
        if (isJunction(path)) {
            try {
                return path.toRealPath();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public static void createFileSymlink(Path link, Path target) throws IOException {
        Files.createSymbolicLink(link, target.toAbsolutePath().normalize());
    }

    /**
     * 在原位置创建链接，返回实际使用的链接方式：
     * 优先符号链接；如果系统不允许（Windows 普通用户），且缓存文件与原位置在同一块磁盘上，
     * 则退回硬链接（同样不需要任何权限）。
     */
    public static LinkKind createFileLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath().normalize());
            return LinkKind.SYMLINK;
        } catch (IOException | UnsupportedOperationException e) {
            Path volumeSample = link.getParent() == null ? link : link.getParent();
            if (FileUtil.isSameStore(volumeSample, target)) {
                try {
                    Files.createLink(link, target);
                    return LinkKind.HARD_LINK;
                } catch (IOException | UnsupportedOperationException hardLinkError) {
                    e.addSuppressed(hardLinkError);
                }
            }
            throw e;
        }
    }

    public static void createDirLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath().normalize());
        } catch (IOException | UnsupportedOperationException e) {
            if (!IS_WINDOWS) {
                throw e;
            }
            createJunction(link, target);
        }
    }

    /** 通过 mklink /J 创建目录联接（Windows，无需管理员权限）。 */
    public static void createJunction(Path link, Path target) throws IOException {
        if (!IS_WINDOWS) {
            throw new IOException("目录联接只适用于 Windows");
        }
        String linkText = link.toAbsolutePath().normalize().toString();
        String targetText = target.toAbsolutePath().normalize().toString();
        if (linkText.contains("\"") || targetText.contains("\"")) {
            throw new IOException("路径中包含引号，无法创建目录联接");
        }
        ProcessBuilder builder = new ProcessBuilder(
                "cmd.exe", "/c", "mklink /J \"" + linkText + "\" \"" + targetText + "\"");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), consoleCharset()).trim();
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("创建目录联接被中断");
        }
        if (code != 0 || !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("创建目录联接失败：" + (output.isEmpty() ? "退出码 " + code : output));
        }
    }

    private static Charset consoleCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (Exception ignored) {
                // 落到默认编码
            }
        }
        return Charset.defaultCharset();
    }
}
