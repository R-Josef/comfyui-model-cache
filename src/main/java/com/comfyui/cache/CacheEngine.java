package com.comfyui.cache;

import com.comfyui.cache.FileUtil.CancelledException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 缓存引擎：所有会改动磁盘的操作都在这里，包含前置检查与失败回滚。
 *
 * <p>单文件缓存流程：复制到缓存盘 → 原文件改名为 {@code 文件名#} → 在原位置创建符号链接。
 * <p>目录缓存流程：整个子目录复制到缓存盘 → 原目录移走 → 在原位置创建目录链接（Windows 用不需要权限的目录联接）。
 */
public final class CacheEngine {

    private final CacheDatabase db;

    public CacheEngine(CacheDatabase db) {
        this.db = db;
    }

    public CacheDatabase database() {
        return db;
    }

    // ---------------------------------------------------------------- 扫描

    public ScanNode scan(Path modelsDir, ProgressSink sink) throws IOException, CancelledException {
        if (!Files.isDirectory(modelsDir)) {
            throw new IOException("模型目录不存在：" + modelsDir);
        }
        ScanNode root = new ScanNode(modelsDir.getFileName() == null ? modelsDir.toString() : modelsDir.getFileName().toString(),
                "", true, 0, false, false);
        fill(root, modelsDir, sink);
        root.sort();
        return root;
    }

    private void fill(ScanNode node, Path dir, ProgressSink sink) throws IOException, CancelledException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        for (Path child : children) {
            if (sink != null) {
                sink.checkCancelled();
            }
            String name = child.getFileName().toString();
            String rel = node.rel.isEmpty() ? name : Path.of(node.rel, name).toString();
            boolean isLink = LinkSupport.isLink(child);
            boolean isDir;
            long size = 0;
            if (isLink) {
                // 链接后面是文件还是目录要跟随判断，但绝对不进入它，避免绕圈
                isDir = Files.isDirectory(child);
                if (!isDir) {
                    try {
                        size = Files.size(child);
                    } catch (IOException ignored) {
                        size = 0;
                    }
                }
            } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                isDir = true;
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                isDir = false;
                try {
                    size = Files.size(child);
                } catch (IOException ignored) {
                    size = 0;
                }
            } else {
                continue;
            }
            ScanNode childNode = new ScanNode(name, rel, isDir, size, isLink,
                    !isLink && !isDir && name.endsWith(CacheEntry.BACKUP_SUFFIX));
            node.children.add(childNode);
            if (isDir) {
                fill(childNode, child, sink);
            }
        }
    }

    // ---------------------------------------------------------------- 缓存

    public String cacheFile(CacheSource source, String rel, ProgressSink sink) throws Exception {
        Path modelsDir = source.modelsDirPath();
        Path cacheRoot = requireCacheDir();
        checkOverlap(modelsDir, cacheRoot);

        Path original = resolveInside(modelsDir, rel);
        String fileName = original.getFileName().toString();
        if (fileName.endsWith(CacheEntry.BACKUP_SUFFIX)) {
            throw new CacheException("这是缓存备份文件（以 # 结尾），ComfyUI 不会加载它，无需缓存。");
        }
        Path backup = original.resolveSibling(fileName + CacheEntry.BACKUP_SUFFIX);
        Path cached = resolveInside(cacheRoot, rel);

        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("找不到这个文件：" + original);
        }
        if (LinkSupport.isLink(original)) {
            throw new CacheException("这个位置已经是链接了，可能已经缓存过。");
        }
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("只能缓存普通文件。");
        }
        if (source.find(rel) != null) {
            throw new CacheException("这个文件已经在缓存列表里了。");
        }
        CacheEntry ancestor = source.cachedAncestorOf(rel);
        if (ancestor != null) {
            throw new CacheException("上级目录「" + ancestor.rel() + "」已经整体缓存，这个文件已经在缓存盘上了。");
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("已经存在备份文件「" + backup.getFileName() + "」，请先处理它。");
        }
        if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("缓存盘里已经有同名文件：" + cached);
        }

        long size = Files.size(original);
        ensureSpace(cacheRoot, size);

        boolean cacheTouched = false;
        boolean renamed = false;
        LinkSupport.LinkKind linkKind = LinkSupport.LinkKind.SYMLINK;
        try {
            sink.message("正在复制到缓存盘…");
            cacheTouched = true;
            FileUtil.copyFile(original, cached, 0, size, sink);
            long cachedSize = Files.size(cached);
            if (cachedSize != size) {
                throw new IOException("复制后大小不一致（原文件 " + size + " 字节，副本 " + cachedSize + " 字节）");
            }

            sink.message("正在把原文件改名为备份…");
            FileUtil.renameInPlace(original, backup);
            renamed = true;

            sink.message("正在原位置创建链接…");
            try {
                linkKind = LinkSupport.createFileLink(original, cached);
            } catch (Exception linkError) {
                throw new CacheException(describeLinkFailure(linkError), linkError);
            }
        } catch (Exception e) {
            List<String> problems = new ArrayList<>();
            if (LinkSupport.isLink(original)) {
                try {
                    Files.delete(original);
                } catch (IOException io) {
                    problems.add("无法删除未完成的链接：" + io.getMessage());
                }
            }
            if (renamed) {
                try {
                    FileUtil.renameInPlace(backup, original);
                } catch (IOException io) {
                    problems.add("无法把备份改回原名：" + io.getMessage());
                }
            }
            if (cacheTouched) {
                try {
                    FileUtil.deleteTree(cached);
                    FileUtil.pruneEmptyDirectories(cached.getParent(), cacheRoot);
                } catch (IOException io) {
                    problems.add("无法清理缓存盘上的副本：" + io.getMessage());
                }
            }
            throw new CacheException(appendProblems(e.getMessage(), problems), e);
        }

        CacheEntry entry = new CacheEntry(rel, CacheEntry.Kind.FILE, size,
                linkKind, CacheEntry.nowStamp());
        source.add(entry);
        db.save();

        return "已缓存：" + rel + "\n"
                + "大小：" + FileUtil.humanSize(size) + "\n"
                + "缓存位置：" + cached + "\n"
                + "原文件已改名为：" + backup.getFileName() + "（ComfyUI 不会再加载它，它只是备份）";
    }

    public String cacheDirectory(CacheSource source, String rel, ProgressSink sink) throws Exception {
        Path modelsDir = source.modelsDirPath();
        Path cacheRoot = requireCacheDir();
        checkOverlap(modelsDir, cacheRoot);

        Path original = resolveInside(modelsDir, rel);
        Path cached = resolveInside(cacheRoot, rel);

        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("找不到这个目录：" + original);
        }
        if (LinkSupport.isLink(original)) {
            throw new CacheException("这个目录已经是链接了。");
        }
        if (!Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("只能整体缓存目录。");
        }
        if (source.find(rel) != null) {
            throw new CacheException("这个目录已经在缓存列表里了。");
        }
        CacheEntry ancestor = source.cachedAncestorOf(rel);
        if (ancestor != null) {
            throw new CacheException("上级目录「" + ancestor.rel() + "」已经整体缓存。");
        }
        List<CacheEntry> descendants = source.cachedDescendantsOf(rel);
        if (!descendants.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < descendants.size() && i < 5; i++) {
                if (i > 0) {
                    names.append("、");
                }
                names.append(descendants.get(i).rel());
            }
            if (descendants.size() > 5) {
                names.append(" 等 ").append(descendants.size()).append(" 项");
            }
            throw new CacheException("这个目录里已经有单独缓存的模型（" + names + "）。\n"
                    + "请先取消缓存这些模型，再整体缓存目录。");
        }
        if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("缓存盘里已经存在同名目录：" + cached);
        }

        int fileCount = FileUtil.countFiles(original);
        long size = FileUtil.treeSize(original);
        if (fileCount == 0) {
            throw new CacheException("这个目录里没有文件，不需要缓存。");
        }
        ensureSpace(cacheRoot, size);

        Path holding = holdingPathFor(modelsDir);
        boolean cacheTouched = false;
        boolean movedAway = false;
        boolean linked = false;
        try {
            sink.message("正在复制到缓存盘…");
            cacheTouched = true;
            FileUtil.copyTree(original, cached, 0, size, sink);

            int copiedCount = FileUtil.countFiles(cached);
            long copiedSize = FileUtil.treeSize(cached);
            if (copiedCount != fileCount || copiedSize != size) {
                throw new IOException("复制结果不完整（文件数 " + copiedCount + "/" + fileCount
                        + "，大小 " + copiedSize + "/" + size + " 字节）");
            }

            sink.message("正在腾出原来的位置…");
            boolean holdingUsed = false;
            if (holding != null) {
                try {
                    Files.move(original, holding);
                    holdingUsed = true;
                } catch (IOException ignored) {
                    holdingUsed = false;
                }
            }
            if (!holdingUsed) {
                FileUtil.deleteTree(original);
            }
            movedAway = true;

            sink.message("正在创建目录链接…");
            try {
                LinkSupport.createDirLink(original, cached);
            } catch (Exception linkError) {
                throw new CacheException(describeLinkFailure(linkError), linkError);
            }
            linked = true;
        } catch (Exception e) {
            List<String> problems = new ArrayList<>();
            if (movedAway && !linked) {
                boolean restored = false;
                if (holding != null && Files.exists(holding, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.move(holding, original);
                        restored = true;
                    } catch (IOException io) {
                        problems.add("无法把暂存的目录移回原位：" + io.getMessage());
                    }
                }
                if (!restored && cacheTouched) {
                    try {
                        FileUtil.copyTree(cached, original, 0, size, ProgressSink.NOOP);
                        problems.add("已把缓存盘上的副本复制回原位。");
                    } catch (Exception io) {
                        problems.add("无法把数据恢复到原位：" + io.getMessage());
                    }
                }
            }
            if (cacheTouched && !linked) {
                try {
                    FileUtil.deleteTree(cached);
                    FileUtil.pruneEmptyDirectories(cached.getParent(), cacheRoot);
                } catch (IOException io) {
                    problems.add("无法清理缓存盘上的副本：" + io.getMessage());
                }
            }
            if (holding != null && Files.exists(holding, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    FileUtil.deleteTree(holding);
                } catch (IOException io) {
                    problems.add("无法清理临时目录：" + io.getMessage());
                }
            }
            throw new CacheException(appendProblems(e.getMessage(), problems), e);
        }

        if (holding != null && Files.exists(holding, LinkOption.NOFOLLOW_LINKS)) {
            try {
                FileUtil.deleteTree(holding);
            } catch (IOException ignored) {
                // 临时目录稍后可以手工删除，不影响缓存结果
            }
        }

        CacheEntry entry = new CacheEntry(rel, CacheEntry.Kind.DIR, size,
                LinkSupport.IS_WINDOWS ? LinkSupport.LinkKind.JUNCTION : LinkSupport.LinkKind.SYMLINK,
                CacheEntry.nowStamp());
        source.add(entry);
        db.save();

        return "已缓存整个目录：" + rel + "\n"
                + "文件数：" + fileCount + "，共 " + FileUtil.humanSize(size) + "\n"
                + "缓存位置：" + cached + "\n"
                + "原目录已变成" + entry.link().label() + "，ComfyUI 仍按原路径读取。";
    }

    // ---------------------------------------------------------------- 取消缓存

    public String uncache(CacheSource source, CacheEntry entry, ProgressSink sink) throws Exception {
        Path modelsDir = source.modelsDirPath();
        Path cacheRoot = requireCacheDir();
        Path original = resolveInside(modelsDir, entry.rel());
        Path cached = resolveInside(cacheRoot, entry.rel());
        if (entry.kind() == CacheEntry.Kind.DIR) {
            return uncacheDirectory(source, entry, original, cached, sink);
        }
        Path backup = original.resolveSibling(original.getFileName() + CacheEntry.BACKUP_SUFFIX);

        boolean symlink = Files.isSymbolicLink(original);
        boolean real = Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean cacheExists = Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS);
        boolean hardLink = real && cacheExists && isSameFileQuiet(original, cached);
        boolean link = symlink || hardLink;
        boolean foreignReal = real && !hardLink;

        if (link && cacheExists && !isSameFileQuiet(original, cached)) {
            Path target = LinkSupport.linkTarget(original);
            throw new CacheException("原始位置的链接指向的不是这个缓存文件"
                    + (target == null ? "" : "（实际指向 " + target + "）") + "，为避免误删已中止。");
        }

        List<String> notes = new ArrayList<>();
        if (foreignReal && backupExists) {
            Path parked = uniqueSibling(backup, "-backup-");
            FileUtil.renameInPlace(backup, parked);
            backupExists = false;
            notes.add("原位置已存在真实文件，同时还有一份备份。备份已改名为 "
                    + parked.getFileName() + " 保留下来。");
        } else if (foreignReal) {
            notes.add("原位置已经是真实文件，本次只清理缓存盘上的副本。");
        }

        if (link) {
            sink.message("正在删除原位置的链接…");
            Files.delete(original);
        }

        if (!foreignReal) {
            if (backupExists) {
                sink.message("正在恢复原文件…");
                FileUtil.renameInPlace(backup, original);
                notes.add("已把备份改回原名：" + original.getFileName());
            } else if (cacheExists) {
                sink.message("未找到备份文件，正在从缓存副本复制回模型目录…");
                FileUtil.copyFile(cached, original, 0, Files.size(cached), sink);
                notes.add("未找到备份文件，已从缓存副本复制回原位。");
            } else {
                notes.add("原文件和备份都不存在，无法恢复文件内容，只清理了记录。");
            }
        }

        sink.message("正在清理缓存盘上的副本…");
        boolean cacheRemoved = false;
        if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.delete(cached);
                cacheRemoved = true;
            } catch (IOException e) {
                notes.add("缓存副本删除失败（可能正被 ComfyUI 占用）：" + e.getMessage()
                        + "。原文件已经恢复，稍后可以再点一次「取消缓存」清理缓存盘。");
            }
        } else {
            cacheRemoved = true;
        }
        if (cacheRemoved) {
            FileUtil.pruneEmptyDirectories(cached.getParent(), cacheRoot);
        }

        if (cacheRemoved) {
            source.remove(entry);
        }
        db.save();

        StringBuilder message = new StringBuilder("已取消缓存：" + entry.rel());
        for (String note : notes) {
            message.append("\n").append(note);
        }
        return message.toString();
    }

    private String uncacheDirectory(CacheSource source, CacheEntry entry, Path original, Path cached, ProgressSink sink)
            throws Exception {
        Path cacheRoot = requireCacheDir();
        boolean link = LinkSupport.isLink(original);
        boolean realDir = !link && Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS);
        boolean cacheExists = Files.isDirectory(cached, LinkOption.NOFOLLOW_LINKS);

        if (link && cacheExists && !isSameFileQuiet(original, cached)) {
            Path target = LinkSupport.linkTarget(original);
            throw new CacheException("原始位置的链接不是指向这个缓存目录"
                    + (target == null ? "" : "（实际指向 " + target + "）") + "，为避免误删已中止。");
        }
        if (!cacheExists) {
            if (realDir) {
                source.remove(entry);
                db.save();
                return "缓存盘上的目录已经不存在，原目录是真实目录，已清理缓存记录：" + entry.rel();
            }
            throw new CacheException("缓存盘上的目录不存在，无法恢复原目录：" + cached);
        }
        if (realDir) {
            throw new CacheException("原位置已经是真实目录，同时缓存盘上还有一份副本。\n"
                    + "程序不会自动删除你的数据，请先确认要保留哪一份（可手工删除缓存盘上的副本，"
                    + "然后点「刷新」清理记录）。");
        }
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CacheException("原位置既没有目录也没有链接，无法判断该如何恢复：" + original);
        }

        long size = FileUtil.treeSize(cached);
        Path volumeSample = original.getParent() == null ? original : original.getParent();
        ensureSpace(volumeSample, size);

        Path temp = uniqueSibling(original, "-restore-");
        List<String> problems = new ArrayList<>();
        try {
            sink.message("正在把缓存目录复制回模型盘…");
            FileUtil.copyTree(cached, temp, 0, size, sink);
            int srcCount = FileUtil.countFiles(cached);
            int dstCount = FileUtil.countFiles(temp);
            long dstSize = FileUtil.treeSize(temp);
            if (srcCount != dstCount || dstSize != size) {
                throw new IOException("复制回模型盘的结果不完整（文件数 " + dstCount + "/" + srcCount
                        + "，大小 " + dstSize + "/" + size + " 字节）");
            }

            sink.message("正在替换目录链接…");
            Files.delete(original);
            FileUtil.renameInPlace(temp, original);

            sink.message("正在清理缓存盘上的目录…");
            FileUtil.deleteTree(cached);
            FileUtil.pruneEmptyDirectories(cached.getParent(), cacheRoot);
        } catch (Exception e) {
            try {
                FileUtil.deleteTree(temp);
            } catch (IOException io) {
                problems.add("无法清理临时目录 " + temp + "：" + io.getMessage());
            }
            if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    LinkSupport.createDirLink(original, cached);
                } catch (Exception io) {
                    problems.add("无法重建原目录的链接：" + io.getMessage());
                }
            }
            throw new CacheException(appendProblems(e.getMessage(), problems), e);
        }

        source.remove(entry);
        db.save();
        return "已取消缓存：" + entry.rel() + "\n目录内容已复制回模型盘，链接已删除，缓存盘上的副本已清理。";
    }

    // ---------------------------------------------------------------- 校验

    public EntryStatus verify(CacheSource source, CacheEntry entry) {
        Path cacheRoot = db.cacheDir();
        if (cacheRoot == null) {
            return new EntryStatus(EntryStatus.State.BROKEN, "未设置缓存目录", "");
        }
        Path original;
        Path cached;
        try {
            original = resolveInside(source.modelsDirPath(), entry.rel());
            cached = resolveInside(cacheRoot, entry.rel());
        } catch (CacheException e) {
            return new EntryStatus(EntryStatus.State.BROKEN, "路径异常", e.getMessage());
        }

        if (entry.kind() == CacheEntry.Kind.DIR) {
            boolean cacheExists = Files.isDirectory(cached, LinkOption.NOFOLLOW_LINKS);
            boolean link = LinkSupport.isLink(original);
            boolean realDir = !link && Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS);
            if (cacheExists && link) {
                if (isSameFileQuiet(original, cached)) {
                    return new EntryStatus(EntryStatus.State.OK, "已缓存（" + entry.link().label() + "）",
                            "整个目录位于缓存盘，ComfyUI 通过链接读取");
                }
                Path target = LinkSupport.linkTarget(original);
                return new EntryStatus(EntryStatus.State.BROKEN, "链接指向异常",
                        "原目录的链接指向 " + target + "，不是 " + cached);
            }
            if (!cacheExists && realDir) {
                return new EntryStatus(EntryStatus.State.ATTENTION, "已恢复为真实目录", "缓存盘上的副本已不存在");
            }
            if (!cacheExists) {
                return new EntryStatus(EntryStatus.State.BROKEN, "缓存目录丢失", "缓存盘上的目录不存在，链接已失效");
            }
            return new EntryStatus(EntryStatus.State.ATTENTION, "原目录未链接",
                    "原位置是真实目录，缓存盘上仍有一份副本，可取消缓存以清理");
        }

        Path backup = original.resolveSibling(original.getFileName() + CacheEntry.BACKUP_SUFFIX);
        boolean cacheExists = Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS);
        boolean real = Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean link = Files.isSymbolicLink(original)
                || (real && cacheExists && isSameFileQuiet(original, cached));

        if (cacheExists && link) {
            if (!isSameFileQuiet(original, cached)) {
                Path target = LinkSupport.linkTarget(original);
                return new EntryStatus(EntryStatus.State.BROKEN, "链接指向异常",
                        "原位置的链接指向 " + target + "，不是 " + cached);
            }
            long actualSize = -1;
            try {
                actualSize = Files.size(cached);
            } catch (IOException ignored) {
                // 保持 -1
            }
            if (entry.size() > 0 && actualSize > 0 && actualSize != entry.size()) {
                return new EntryStatus(EntryStatus.State.ATTENTION, "大小已变化",
                        "缓存副本 " + FileUtil.humanSize(actualSize) + "，记录为 " + FileUtil.humanSize(entry.size()));
            }
            if (!backupExists) {
                return new EntryStatus(EntryStatus.State.ATTENTION, "备份缺失",
                        "原文件备份（" + backup.getFileName() + "）不存在，数据只在缓存盘上");
            }
            return new EntryStatus(EntryStatus.State.OK, "已缓存（" + entry.link().label() + "）", "");
        }
        if (!cacheExists && link) {
            if (backupExists) {
                return new EntryStatus(EntryStatus.State.BROKEN, "缓存副本丢失",
                        "链接已失效，但备份还在，点「取消缓存」即可把备份恢复回原位");
            }
            return new EntryStatus(EntryStatus.State.BROKEN, "数据丢失", "缓存副本和备份都不存在");
        }
        if (cacheExists && real) {
            return new EntryStatus(EntryStatus.State.ATTENTION, "原文件已恢复",
                    "原位置是真实文件，缓存盘上仍有一份副本，可取消缓存以释放空间");
        }
        if (cacheExists) {
            return new EntryStatus(EntryStatus.State.BROKEN, "链接丢失",
                    "缓存副本存在，但原位置既没有文件也没有链接");
        }
        if (real && !backupExists) {
            return new EntryStatus(EntryStatus.State.ATTENTION, "已恢复为真实文件",
                    "缓存副本已不存在，可取消缓存以清理记录");
        }
        return new EntryStatus(EntryStatus.State.BROKEN, "文件缺失",
                "原始文件、备份和缓存副本都不存在");
    }

    // ---------------------------------------------------------------- 内部工具

    private Path requireCacheDir() throws CacheException {
        Path cacheDir = db.cacheDir();
        if (cacheDir == null) {
            throw new CacheException("还没有设置缓存目录。");
        }
        if (!Files.isDirectory(cacheDir)) {
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException e) {
                throw new CacheException("缓存目录不可用：" + cacheDir + "（" + e.getMessage() + "）");
            }
        }
        return cacheDir;
    }

    private Path resolveInside(Path base, String rel) throws CacheException {
        Path basePath = base.toAbsolutePath().normalize();
        Path resolved = basePath.resolve(rel.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new CacheException("路径超出目录范围：" + rel);
        }
        return resolved;
    }

    private void checkOverlap(Path modelsDir, Path cacheDir) throws CacheException {
        if (modelsDir.startsWith(cacheDir)) {
            throw new CacheException("模型目录不能在缓存目录里面。");
        }
        if (cacheDir.startsWith(modelsDir)) {
            throw new CacheException("缓存目录不能放在模型目录里面，否则 ComfyUI 会重复扫描。");
        }
    }

    private void ensureSpace(Path volumeSample, long needed) throws CacheException {
        long free = FileUtil.usableSpace(volumeSample);
        long reserve = 256L * 1024 * 1024;
        if (free >= 0 && free < needed + reserve) {
            throw new CacheException("可用空间不足：这次需要 " + FileUtil.humanSize(needed)
                    + "，该磁盘目前可用 " + FileUtil.humanSize(free) + "。");
        }
    }

    private Path holdingPathFor(Path modelsDir) {
        Path parent = modelsDir.getParent();
        if (parent == null || !Files.isDirectory(parent) || !FileUtil.isSameStore(parent, modelsDir)) {
            return null;
        }
        return parent.resolve(".comfyui-cache-holding-" + System.nanoTime());
    }

    private Path uniqueSibling(Path base, String marker) {
        Path parent = base.getParent();
        String name = base.getFileName().toString();
        for (int i = 0; i < 100; i++) {
            Path candidate = parent.resolve(name + marker + System.nanoTime() + (i == 0 ? "" : "-" + i));
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return parent.resolve(name + marker + System.nanoTime());
    }

    private boolean isSameFileQuiet(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean samePath(Path a, Path b) {
        String left = a.toAbsolutePath().normalize().toString();
        String right = b.toAbsolutePath().normalize().toString();
        return LinkSupport.IS_WINDOWS ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private String describeLinkFailure(Throwable error) {
        String reason = LinkSupport.describe(error);
        StringBuilder sb = new StringBuilder("创建链接失败：" + reason);
        if (LinkSupport.IS_WINDOWS) {
            sb.append("\n\nWindows 上创建符号链接需要权限，两种解决办法：\n")
                    .append("1. 在「设置 → 系统 → 开发者选项」里打开「开发者人员模式」，然后重启本程序；\n")
                    .append("2. 或在「工具」菜单里点「以管理员身份重新启动」。\n")
                    .append("另外，也可以改用「缓存整个目录」：Windows 的目录联接不需要任何权限。");
        }
        sb.append("\n\n本次操作已经回滚，原文件没有被改动。");
        return sb.toString();
    }

    private String appendProblems(String message, List<String> problems) {
        if (problems.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        sb.append("\n\n附加情况：");
        for (String problem : problems) {
            sb.append("\n· ").append(problem);
        }
        return sb.toString();
    }

    /** 供界面显示：把相对路径转成缓存盘上的绝对路径。 */
    public Path cachePathFor(String rel) throws CacheException {
        return resolveInside(requireCacheDir(), rel);
    }

    /** 供界面显示：把相对路径转成模型目录里的绝对路径。 */
    public static Path modelPathFor(CacheSource source, String rel) throws CacheException {
        Path base = source.modelsDirPath().toAbsolutePath().normalize();
        Path resolved = base.resolve(rel.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(base)) {
            throw new CacheException("路径超出目录范围：" + rel);
        }
        return resolved;
    }

    public static boolean isModelFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String[] extensions = {".safetensors", ".sft", ".ckpt", ".pt", ".pth", ".bin", ".gguf",
                ".onnx", ".pkl", ".npz", ".h5", ".pb", ".yaml", ".json", ".vae", ".engine", ".zip"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
