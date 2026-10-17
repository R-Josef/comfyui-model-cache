package com.comfyui.cache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一个模型目录以及它下面所有被缓存的条目。 */
public final class CacheSource {

    private final Path modelsDir;
    private final List<CacheEntry> entries = new ArrayList<>();

    public CacheSource(Path modelsDir) {
        this.modelsDir = modelsDir.toAbsolutePath().normalize();
    }

    public Path modelsDirPath() {
        return modelsDir;
    }

    public String modelsDir() {
        return modelsDir.toString();
    }

    public List<CacheEntry> entries() {
        return entries;
    }

    public String displayName() {
        Path name = modelsDir.getFileName();
        return name == null ? modelsDir.toString() : name.toString();
    }

    public static String key(Path path) {
        String text = path.toAbsolutePath().normalize().toString();
        return LinkSupport.IS_WINDOWS ? text.toLowerCase(Locale.ROOT) : text;
    }

    public static String key(String rel) {
        String normalized = rel.replace('/', '\\');
        return LinkSupport.IS_WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    public CacheEntry find(String rel) {
        String wanted = key(rel);
        for (CacheEntry entry : entries) {
            if (key(entry.rel()).equals(wanted)) {
                return entry;
            }
        }
        return null;
    }

    /** 是否有某个已缓存目录把 rel 包含在内（这种情况下 rel 已经在缓存盘上了）。 */
    public CacheEntry cachedAncestorOf(String rel) {
        String wanted = key(rel);
        for (CacheEntry entry : entries) {
            if (entry.kind() != CacheEntry.Kind.DIR) {
                continue;
            }
            String prefix = key(entry.rel()) + "\\";
            if (wanted.startsWith(prefix)) {
                return entry;
            }
        }
        return null;
    }

    /** 是否有已缓存条目位于 rel 目录内部。 */
    public List<CacheEntry> cachedDescendantsOf(String rel) {
        String prefix = key(rel) + "\\";
        List<CacheEntry> result = new ArrayList<>();
        for (CacheEntry entry : entries) {
            if (key(entry.rel()).startsWith(prefix)) {
                result.add(entry);
            }
        }
        return result;
    }

    void add(CacheEntry entry) {
        entries.add(entry);
    }

    void remove(CacheEntry entry) {
        entries.remove(entry);
    }

    @Override
    public String toString() {
        return modelsDir.toString();
    }
}
