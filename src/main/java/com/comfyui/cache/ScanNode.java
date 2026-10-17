package com.comfyui.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 模型目录扫描结果的树节点。 */
public final class ScanNode {

    private static final Comparator<ScanNode> ORDER =
            Comparator.comparing((ScanNode n) -> !n.directory).thenComparing(n -> n.name.toLowerCase());

    public final String name;
    /** 相对于模型目录的路径，根节点为空字符串。 */
    public final String rel;
    public final boolean directory;
    public final long size;
    /** 本身是符号链接 / 目录联接（扫描时不会进入）。 */
    public final boolean link;
    /** 是 {@code #} 结尾的备份文件（ComfyUI 不会加载它）。 */
    public final boolean backup;
    public final List<ScanNode> children = new ArrayList<>();

    public ScanNode(String name, String rel, boolean directory, long size, boolean link, boolean backup) {
        this.name = name;
        this.rel = rel;
        this.directory = directory;
        this.size = size;
        this.link = link;
        this.backup = backup;
    }

    public void sort() {
        children.sort(ORDER);
        for (ScanNode child : children) {
            child.sort();
        }
    }

    public void collectFiles(List<ScanNode> out) {
        if (!directory) {
            out.add(this);
            return;
        }
        for (ScanNode child : children) {
            child.collectFiles(out);
        }
    }

    public ScanNode find(String rel) {
        if (rel.isEmpty()) {
            return this;
        }
        String normalized = rel.replace('/', '\\');
        String[] parts = normalized.split("\\\\");
        ScanNode current = this;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            ScanNode next = null;
            for (ScanNode child : current.children) {
                if (child.name.equals(part)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }
}
