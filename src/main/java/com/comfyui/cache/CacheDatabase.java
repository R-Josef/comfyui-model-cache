package com.comfyui.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存数据库：记录用户选择的模型目录、缓存目录，以及所有已缓存的模型。
 * 存放为人类可读的 JSON 文件，默认位置 {@code ~/.comfyui-model-cache/cache-db.json}。
 */
public final class CacheDatabase {

    public static final String FOLDER_NAME = ".comfyui-model-cache";
    public static final String FILE_NAME = "cache-db.json";
    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private Path cacheDir;
    private final List<CacheSource> sources = new ArrayList<>();
    private boolean onlyModelFiles = true;
    private String loadWarning;

    private CacheDatabase(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), FOLDER_NAME, FILE_NAME);
    }

    public static CacheDatabase load(Path file) {
        CacheDatabase db = new CacheDatabase(file);
        if (!Files.isRegularFile(file)) {
            return db;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            db.loadWarning = "读取缓存数据库失败：" + e.getMessage();
            return db;
        }
        if (text.isBlank()) {
            return db;
        }
        try {
            Map<String, Object> root = Json.asObject(Json.parse(text));
            String cacheDir = Json.optString(root, "cacheDir", "");
            if (!cacheDir.isEmpty()) {
                db.cacheDir = Path.of(cacheDir).toAbsolutePath().normalize();
            }
            Map<String, Object> settings = Json.asObject(root.get("settings"));
            db.onlyModelFiles = Json.optBoolean(settings, "onlyModelFiles", true);
            for (Object item : Json.asArray(root.get("sources"))) {
                Map<String, Object> sourceMap = Json.asObject(item);
                String modelsDir = Json.optString(sourceMap, "modelsDir", "");
                if (modelsDir.isEmpty()) {
                    continue;
                }
                CacheSource source = new CacheSource(Path.of(modelsDir));
                for (Object entryItem : Json.asArray(sourceMap.get("entries"))) {
                    CacheEntry entry = CacheEntry.fromJson(Json.asObject(entryItem));
                    if (entry != null) {
                        source.add(entry);
                    }
                }
                db.sources.add(source);
            }
        } catch (RuntimeException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                db.loadWarning = "缓存数据库内容无法解析（" + e.getMessage() + "），已备份为 "
                        + backup.getFileName() + "，程序以空配置启动。";
            } catch (IOException io) {
                db.loadWarning = "缓存数据库内容无法解析，且备份失败：" + io.getMessage();
            }
            db.sources.clear();
            db.cacheDir = null;
        }
        return db;
    }

    public void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("cacheDir", cacheDir == null ? "" : cacheDir.toString());
        root.put("updatedAt", CacheEntry.nowStamp());
        List<Object> sourceList = new ArrayList<>();
        for (CacheSource source : sources) {
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("modelsDir", source.modelsDir());
            List<Object> entryList = new ArrayList<>();
            for (CacheEntry entry : source.entries()) {
                entryList.add(entry.toJson());
            }
            sourceMap.put("entries", entryList);
            sourceList.add(sourceMap);
        }
        root.put("sources", sourceList);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("onlyModelFiles", onlyModelFiles);
        root.put("settings", settings);

        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path file() {
        return file;
    }

    public Path folder() {
        return file.getParent();
    }

    public String loadWarning() {
        return loadWarning;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir == null ? null : cacheDir.toAbsolutePath().normalize();
    }

    public boolean onlyModelFiles() {
        return onlyModelFiles;
    }

    public void setOnlyModelFiles(boolean value) {
        this.onlyModelFiles = value;
    }

    public List<CacheSource> sources() {
        return sources;
    }

    public CacheSource findSource(Path modelsDir) {
        String key = CacheSource.key(modelsDir);
        for (CacheSource source : sources) {
            if (CacheSource.key(source.modelsDirPath()).equals(key)) {
                return source;
            }
        }
        return null;
    }

    public CacheSource addSource(Path modelsDir) {
        CacheSource existing = findSource(modelsDir);
        if (existing != null) {
            return existing;
        }
        CacheSource source = new CacheSource(modelsDir);
        sources.add(source);
        return source;
    }

    /** 只有在目录下没有缓存记录时才能移除。 */
    public boolean removeSource(CacheSource source) {
        if (!source.entries().isEmpty()) {
            return false;
        }
        return sources.remove(source);
    }

    public int entryCount() {
        int count = 0;
        for (CacheSource source : sources) {
            count += source.entries().size();
        }
        return count;
    }

    public List<CacheEntry> allEntries() {
        List<CacheEntry> result = new ArrayList<>();
        for (CacheSource source : sources) {
            result.addAll(source.entries());
        }
        return result;
    }
}
