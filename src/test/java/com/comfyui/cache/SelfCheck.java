package com.comfyui.cache;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不需要界面的自测程序，验证缓存引擎在真实文件系统上的行为。
 *
 * 运行方式：
 * <pre>
 *   mvn verify                       （编译 + 打包 + 自测）
 *   mvn test-compile exec:java -Dexec.mainClass=com.comfyui.cache.SelfCheck
 * </pre>
 */
public final class SelfCheck {

    private static int passed;
    private static int failed;
    private static final List<String> notes = new ArrayList<>();

    private SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("comfyui-cache-selftest-");
        System.out.println("selftest sandbox: " + base);
        try {
            testJson();
            testDatabaseRoundTrip(base.resolve("db-roundtrip"));
            testFileCacheFlow(base.resolve("file-flow"));
            testDirectoryCacheFlow(base.resolve("dir-flow"));
            testCrossVolumeRollback(base.resolve("cross-volume"));
            testDeleteTreeWithJunctionLoop(base.resolve("delete-loop"));
            testPruneEmptyDirectories(base.resolve("prune"));
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL unexpected error");
            t.printStackTrace(System.out);
        } finally {
            cleanup(base);
        }
        System.out.println();
        for (String note : notes) {
            System.out.println("note: " + note);
        }
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 测试

    private static void testJson() {
        section("Json 读写");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cacheDir", "C:\\Users\\Example\\modelcache");
        root.put("quote", "引号\"和反斜杠\\与换行\n结束");
        root.put("unicode", "中文模型名.safetensors#");
        root.put("number", 12345678901234L);
        root.put("decimal", 1.5);
        root.put("flag", true);
        root.put("empty", List.of());
        root.put("nested", List.of(Map.of("rel", "vae\\a.safetensors"), Map.of("rel", "vae\\b.safetensors")));

        String text = Json.write(root);
        Map<String, Object> parsed = Json.asObject(Json.parse(text));
        check("字符串往返一致", "引号\"和反斜杠\\与换行\n结束".equals(Json.optString(parsed, "quote", null)));
        check("中文往返一致", "中文模型名.safetensors#".equals(Json.optString(parsed, "unicode", null)));
        check("长整数往返一致", Json.optLong(parsed, "number", -1) == 12345678901234L);
        check("布尔往返一致", Json.optBoolean(parsed, "flag", false));
        check("空数组往返一致", Json.asArray(parsed.get("empty")).isEmpty());
        check("嵌套数组长度正确", Json.asArray(parsed.get("nested")).size() == 2);
        check("路径反斜杠保留", "C:\\Users\\Example\\modelcache".equals(Json.optString(parsed, "cacheDir", null)));
        check("重复解析结果一致", Json.write(parsed).equals(text));
    }

    private static void testDatabaseRoundTrip(Path base) throws Exception {
        section("数据库读写");
        Files.createDirectories(base);
        Path file = base.resolve("cache-db.json");
        CacheDatabase db = CacheDatabase.load(file);
        db.setCacheDir(base.resolve("cache"));
        db.setOnlyModelFiles(false);
        CacheSource source = db.addSource(base.resolve("models"));
        source.add(new CacheEntry("diffusion_models\\x.safetensors", CacheEntry.Kind.FILE, 1024,
                LinkSupport.LinkKind.SYMLINK, "2026-10-17 10:00:00"));
        source.add(new CacheEntry("vae", CacheEntry.Kind.DIR, 2048,
                LinkSupport.LinkKind.JUNCTION, "2026-10-17 10:01:00"));
        db.save();

        CacheDatabase reloaded = CacheDatabase.load(file);
        check("缓存目录已保存", base.resolve("cache").toAbsolutePath().normalize()
                .equals(reloaded.cacheDir()));
        check("设置已保存", !reloaded.onlyModelFiles());
        check("模型目录已保存", reloaded.sources().size() == 1);
        check("记录条数正确", reloaded.entryCount() == 2);
        CacheEntry file1 = reloaded.sources().get(0).find("diffusion_models\\x.safetensors");
        check("文件记录字段正确", file1 != null && file1.size() == 1024
                && file1.kind() == CacheEntry.Kind.FILE && file1.link() == LinkSupport.LinkKind.SYMLINK);
        CacheEntry dir1 = reloaded.sources().get(0).find("vae");
        check("目录记录字段正确", dir1 != null && dir1.kind() == CacheEntry.Kind.DIR
                && dir1.link() == LinkSupport.LinkKind.JUNCTION);
        check("备份名规则正确", file1 != null && file1.backupRel().endsWith(".safetensors#"));

        Path broken = base.resolve("broken-db.json");
        Files.writeString(broken, "{ 这不是 json", StandardCharsets.UTF_8);
        CacheDatabase brokenDb = CacheDatabase.load(broken);
        check("损坏的数据文件有提示", brokenDb.loadWarning() != null);
        boolean backedUp;
        try (var stream = Files.list(base)) {
            backedUp = stream.anyMatch(p -> p.getFileName().toString().startsWith("broken-db.json.corrupt-"));
        }
        check("损坏的数据文件被备份", backedUp);
    }

    private static void testFileCacheFlow(Path base) throws Exception {
        section("单文件缓存流程");
        Path models = base.resolve("models");
        Path target = models.resolve("diffusion_models").resolve("big.safetensors");
        Files.createDirectories(target.getParent());
        byte[] original = payload(300 * 1024);
        Files.write(target, original);
        Path cacheDir = base.resolve("cache");

        CacheDatabase db = CacheDatabase.load(base.resolve("cache-db.json"));
        db.setCacheDir(cacheDir);
        CacheSource source = db.addSource(models);
        CacheEngine engine = new CacheEngine(db);
        String rel = "diffusion_models\\big.safetensors";

        Path backup = target.resolveSibling("big.safetensors#");
        Path cached = cacheDir.resolve(rel);

        engine.cacheFile(source, rel, ProgressSink.NOOP);
        CacheEntry entry = source.find(rel);
        check("记录了缓存条目", entry != null);
        if (entry == null) {
            return;
        }
        notes.add("本机的链接方式：" + entry.link().label()
                + (entry.link() == LinkSupport.LinkKind.HARD_LINK
                ? "（当前系统不允许普通用户创建符号链接，程序自动退回同盘硬链接）" : ""));
        check("原文件已改名为备份", Files.exists(backup, LinkOption.NOFOLLOW_LINKS));
        check("原位置存在链接", Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        check("链接可读到原始内容", Arrays.equals(original, Files.readAllBytes(target)));
        check("缓存副本已生成", Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS));
        check("缓存副本大小一致", Files.size(cached) == original.length);
        check("缓存记录大小一致", entry.size() == original.length);

        EntryStatus status = engine.verify(source, entry);
        check("校验结果为正常（实际：" + status.state() + " " + status.text() + "）", status.healthy());

        engine.uncache(source, entry, ProgressSink.NOOP);
        check("备份已改回原名", !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
        check("原文件内容完好", Arrays.equals(original, Files.readAllBytes(target)));
        check("原位置不再是链接", !LinkSupport.isLink(target));
        check("缓存副本已删除", !Files.exists(cached, LinkOption.NOFOLLOW_LINKS));
        check("缓存子目录已清理", !Files.exists(cached.getParent(), LinkOption.NOFOLLOW_LINKS));
        check("缓存记录已移除", source.entries().isEmpty());
        CacheDatabase reloaded = CacheDatabase.load(base.resolve("cache-db.json"));
        check("数据文件里也没有残留", reloaded.entryCount() == 0);
    }

    private static void testDirectoryCacheFlow(Path base) throws Exception {
        section("整目录缓存流程");
        Path models = base.resolve("models");
        Path dir = models.resolve("vae");
        Files.createDirectories(dir.resolve("sub"));
        byte[] a = payload(150 * 1024);
        byte[] b = payload(90 * 1024);
        Files.write(dir.resolve("a.safetensors"), a);
        Files.write(dir.resolve("sub").resolve("b.safetensors"), b);
        Files.writeString(dir.resolve("readme.txt"), "keep me", StandardCharsets.UTF_8);
        Path cacheDir = base.resolve("cache");

        CacheDatabase db = CacheDatabase.load(base.resolve("cache-db.json"));
        db.setCacheDir(cacheDir);
        CacheSource source = db.addSource(models);
        CacheEngine engine = new CacheEngine(db);

        engine.cacheDirectory(source, "vae", ProgressSink.NOOP);
        CacheEntry entry = source.find("vae");
        check("记录了目录条目", entry != null);
        if (entry == null) {
            return;
        }
        check("原目录变成链接（" + entry.link().label() + "）", LinkSupport.isLink(dir));
        check("链接后面还是目录", Files.isDirectory(dir));
        check("通过链接能读到文件", Arrays.equals(a, Files.readAllBytes(dir.resolve("a.safetensors"))));
        check("子目录内容也在", Arrays.equals(b, Files.readAllBytes(dir.resolve("sub").resolve("b.safetensors"))));
        check("缓存盘上有完整副本", Files.isRegularFile(cacheDir.resolve("vae").resolve("sub").resolve("b.safetensors")
                , LinkOption.NOFOLLOW_LINKS));
        check("缓存盘上没有多余链接", !LinkSupport.isLink(cacheDir.resolve("vae")));
        EntryStatus status = engine.verify(source, entry);
        check("校验结果为正常（实际：" + status.state() + " " + status.text() + "）", status.healthy());

        engine.uncache(source, entry, ProgressSink.NOOP);
        check("原目录恢复为真实目录", Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !LinkSupport.isLink(dir));
        check("目录内容完好", Arrays.equals(a, Files.readAllBytes(dir.resolve("a.safetensors")))
                && Arrays.equals(b, Files.readAllBytes(dir.resolve("sub").resolve("b.safetensors"))));
        check("文本文件也在", Files.readString(dir.resolve("readme.txt")).equals("keep me"));
        check("缓存盘上的副本已删除", !Files.exists(cacheDir.resolve("vae"), LinkOption.NOFOLLOW_LINKS));
        check("缓存记录已移除", source.entries().isEmpty());
        check("模型盘上没有留下临时目录", listNames(base).stream()
                .noneMatch(name -> name.contains("cache-holding") || name.contains("restore")));
    }

    private static void testCrossVolumeRollback(Path base) throws Exception {
        section("链接创建失败时的回滚");
        Path otherVolume = findWritableOtherVolume();
        if (otherVolume == null) {
            notes.add("没有找到第二块可写磁盘，跨磁盘回滚测试已跳过。");
            return;
        }
        notes.add("跨磁盘回滚测试使用的第二块磁盘目录：" + otherVolume);
        try {
            Path models = base.resolve("models");
            Path target = models.resolve("loras").resolve("x.safetensors");
            Files.createDirectories(target.getParent());
            byte[] original = payload(64 * 1024);
            Files.write(target, original);
            Path cacheDir = otherVolume;

            CacheDatabase db = CacheDatabase.load(base.resolve("cache-db.json"));
            db.setCacheDir(cacheDir);
            CacheSource source = db.addSource(models);
            CacheEngine engine = new CacheEngine(db);
            String rel = "loras\\x.safetensors";
            Path backup = target.resolveSibling("x.safetensors#");
            Path cached = cacheDir.resolve(rel);

            String message = null;
            try {
                engine.cacheFile(source, rel, ProgressSink.NOOP);
            } catch (Exception e) {
                message = e.getMessage();
            }
            check("跨盘且无权限时缓存失败并给出提示", message != null);
            check("原文件仍然在（内容完好）", Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && Arrays.equals(original, Files.readAllBytes(target)));
            check("没有残留备份文件", !Files.exists(backup, LinkOption.NOFOLLOW_LINKS));
            check("原位置没有残留链接", !LinkSupport.isLink(target));
            check("缓存盘上已清理干净", !Files.exists(cached, LinkOption.NOFOLLOW_LINKS));
            check("没有写入缓存记录", source.entries().isEmpty());
        } finally {
            try {
                FileUtil.deleteTree(otherVolume);
            } catch (Exception e) {
                System.out.println("cleanup of second volume failed: " + e);
            }
        }
    }

    private static void testDeleteTreeWithJunctionLoop(Path base) throws Exception {
        section("删除递归不跟随链接");
        Files.createDirectories(base);
        if (!LinkSupport.IS_WINDOWS) {
            notes.add("非 Windows 系统，目录联接回环测试已跳过。");
            return;
        }
        Path dir = base.resolve("target");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("keep.txt"), "data", StandardCharsets.UTF_8);
        Path loop = dir.resolve("loop");
        LinkSupport.createJunction(loop, dir);
        check("回环联接已建立", LinkSupport.isLink(loop));

        FileUtil.deleteTree(dir);
        check("含回环链接的目录被完整删除", !Files.exists(dir, LinkOption.NOFOLLOW_LINKS));
    }

    private static void testPruneEmptyDirectories(Path base) throws Exception {
        section("空目录清理");
        Path cacheRoot = base.resolve("cache");
        Path deep = cacheRoot.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(deep);
        FileUtil.pruneEmptyDirectories(deep, cacheRoot);
        check("空目录被逐级删除", !Files.exists(cacheRoot.resolve("a"), LinkOption.NOFOLLOW_LINKS));
        check("缓存根目录保留", Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS));

        Path keep = cacheRoot.resolve("x").resolve("y");
        Files.createDirectories(keep);
        Files.writeString(keep.resolve("file.bin"), "x");
        FileUtil.pruneEmptyDirectories(keep, cacheRoot);
        check("非空目录不会被删除", Files.isDirectory(keep, LinkOption.NOFOLLOW_LINKS));
    }

    // ------------------------------------------------------------------ 工具

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    private static List<String> listNames(Path dir) throws Exception {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }
        return names;
    }

    private static Path findWritableOtherVolume() {
        String tempRoot = System.getProperty("java.io.tmpdir");
        for (String candidate : new String[]{"E:\\", "D:\\", "G:\\"}) {
            if (candidate.equalsIgnoreCase(tempRoot.substring(0, Math.min(3, tempRoot.length())))) {
                continue;
            }
            Path root = Path.of(candidate);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Path probe = Files.createTempDirectory(root, "comfyui-cache-selftest-volume-");
                return probe;
            } catch (Exception ignored) {
                // 换下一个盘
            }
        }
        return null;
    }

    private static void cleanup(Path base) {
        try {
            FileUtil.deleteTree(base);
        } catch (Exception e) {
            System.out.println("cleanup failed: " + e);
        }
    }
}
