package com.comfyui.cache;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 一条缓存记录：某个模型（或整个模型目录）已经被搬到了缓存盘。 */
public final class CacheEntry {

    /** 备份文件的后缀：模型后缀名后面加一个井号，ComfyUI 就不会再加载它。 */
    public static final String BACKUP_SUFFIX = "#";

    public enum Kind {
        /** 单个模型文件。 */
        FILE("文件"),
        /** 整个模型子目录。 */
        DIR("目录");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Kind fromName(String name) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
            return FILE;
        }
    }

    private final String rel;
    private final Kind kind;
    private final long size;
    private final LinkSupport.LinkKind link;
    private final String cachedAt;

    public CacheEntry(String rel, Kind kind, long size, LinkSupport.LinkKind link, String cachedAt) {
        this.rel = rel;
        this.kind = kind;
        this.size = size;
        this.link = link;
        this.cachedAt = cachedAt;
    }

    /** 相对于模型目录的路径，例如 {@code diffusion_models\seedvr2.safetensors}。 */
    public String rel() {
        return rel;
    }

    public Kind kind() {
        return kind;
    }

    public long size() {
        return size;
    }

    public LinkSupport.LinkKind link() {
        return link;
    }

    public String cachedAt() {
        return cachedAt;
    }

    public String name() {
        int index = Math.max(rel.lastIndexOf('\\'), rel.lastIndexOf('/'));
        return index < 0 ? rel : rel.substring(index + 1);
    }

    /** 备份路径（相对模型目录）。目录模式不使用备份。 */
    public String backupRel() {
        return rel + BACKUP_SUFFIX;
    }

    public static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rel", rel);
        map.put("kind", kind.name());
        map.put("size", size);
        map.put("link", link.name());
        map.put("cachedAt", cachedAt);
        return map;
    }

    public static CacheEntry fromJson(Map<String, Object> map) {
        String rel = Json.optString(map, "rel", "");
        if (rel.isEmpty()) {
            return null;
        }
        return new CacheEntry(
                rel,
                Kind.fromName(Json.optString(map, "kind", "FILE")),
                Json.optLong(map, "size", -1),
                LinkSupport.LinkKind.fromName(Json.optString(map, "link", "SYMLINK")),
                Json.optString(map, "cachedAt", ""));
    }

    public String kindLabel() {
        return kind.label();
    }

    public String sizeLabel() {
        return size < 0 ? "未知" : FileUtil.humanSize(size);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " " + rel;
    }
}
