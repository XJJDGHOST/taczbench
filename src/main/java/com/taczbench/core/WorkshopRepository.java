package com.taczbench.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 扫描所有可能藏着枪包的地方，把枪械/配件数据文件读进内存。
 * <p>
 * 覆盖 TACZ 支持的四种安装方式：{@code tacz/} 下的 zip 或文件夹、{@code mods/} 里的枪包 jar、
 * 资源包、以及 {@code world/datapacks/}。后扫描的优先级高（和游戏里的覆盖顺序一致）。
 */
public final class WorkshopRepository {

    public static final String KIND_GUNS = "guns";
    public static final String KIND_ATTACHMENTS = "attachments";

    private static final Pattern DATA_PATH =
            Pattern.compile("^data/([^/]+)/data/(guns|attachments)/([^/]+)\\.json$");
    private static final Pattern INDEX_PATH =
            Pattern.compile("^data/([^/]+)/index/(guns|attachments)/([^/]+)\\.json$");
    private static final long MAX_JSON_BYTES = 4L * 1024 * 1024;

    private final Map<String, GunEntry> byKey = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /** 扫描一个 zip / jar / 文件夹。出错只记录警告，不中断整体扫描。 */
    public void addSource(String label, Path path) {
        try {
            if (Files.isDirectory(path)) {
                scanDirectory(label, path);
            } else if (Files.isRegularFile(path)) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".zip") || name.endsWith(".jar")) {
                    scanZip(label, path);
                }
            }
        } catch (Exception e) {
            warnings.add(label + " 扫描失败: " + e.getMessage());
        }
    }

    private void scanZip(String label, Path path) throws IOException {        Map<String, String> dataFiles = new LinkedHashMap<>();
        Map<String, String> indexFiles = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName().replace('\\', '/');
                if (DATA_PATH.matcher(n).matches()) {
                    String text = read(zip, e);
                    if (text != null) {
                        dataFiles.put(n, text);
                    }
                } else if (INDEX_PATH.matcher(n).matches()) {
                    String text = read(zip, e);
                    if (text != null) {
                        indexFiles.put(n, text);
                    }
                }
            }
        }
        ingest(label, path, "zip", dataFiles, indexFiles);
    }

    private void scanDirectory(String label, Path root) throws IOException {
        Map<String, String> dataFiles = new LinkedHashMap<>();
        Map<String, String> indexFiles = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (DATA_PATH.matcher(rel).matches()) {
                    dataFiles.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                } else if (INDEX_PATH.matcher(rel).matches()) {
                    indexFiles.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        ingest(label, root, "dir", dataFiles, indexFiles);
    }

    private void ingest(String label, Path packPath, String packKind,
                        Map<String, String> dataFiles, Map<String, String> indexFiles) {
        for (Map.Entry<String, String> e : dataFiles.entrySet()) {
            Matcher m = DATA_PATH.matcher(e.getKey());
            if (!m.matches()) {
                continue;
            }
            String ns = m.group(1);
            String kind = m.group(2);
            String file = m.group(3);
            String indexKey = "data/" + ns + "/index/" + kind + "/" + stripDataSuffix(file) + ".json";
            String nameKey = null;
            String type = "";
            String indexText = indexFiles.get(indexKey);
            if (indexText != null) {
                try {
                    JsonNode idx = JsonDoc.parse(indexText).root();
                    if (idx.member("name") != null) {
                        nameKey = idx.member("name").asString();
                    }
                    if (idx.member("type") != null) {
                        type = idx.member("type").asString();
                    }
                } catch (RuntimeException ex) {
                    warnings.add(label + " 的 " + indexKey + " 解析失败: " + ex.getMessage());
                }
            }
            GunEntry entry = new GunEntry(kind, ns, file, e.getKey(), label, nameKey, type, e.getValue(),
                    packPath.toAbsolutePath(), packKind);
            // 后扫描的覆盖先扫描的，和游戏内资源优先级一致
            byKey.remove(entry.key());
            byKey.put(entry.key(), entry);
        }
    }

    private static String stripDataSuffix(String file) {
        return file.endsWith("_data") ? file.substring(0, file.length() - 5) : file;
    }

    private static String read(ZipFile zip, ZipEntry entry) {
        if (entry.getSize() > MAX_JSON_BYTES) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 查询

    public List<GunEntry> all() {
        List<GunEntry> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparing(GunEntry::sortKey));
        return list;
    }

    public List<GunEntry> ofKind(String kind) {
        List<GunEntry> list = new ArrayList<>();
        for (GunEntry e : byKey.values()) {
            if (e.kind.equals(kind)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparing(GunEntry::sortKey));
        return list;
    }

    public GunEntry find(String kind, String namespace, String id) {
        return byKey.get(kind + "|" + namespace + "|" + id);
    }

    public int size() {
        return byKey.size();
    }

    public void clear() {
        byKey.clear();
        warnings.clear();
    }

    public List<String> warnings() {
        return warnings;
    }

    /** 按名字/ID/命名空间模糊搜索。 */
    public List<GunEntry> search(String kind, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<GunEntry> out = new ArrayList<>();
        for (GunEntry e : ofKind(kind)) {
            if (q.isEmpty()
                    || e.fallbackName().toLowerCase(Locale.ROOT).contains(q)
                    || e.id.toLowerCase(Locale.ROOT).contains(q)
                    || e.namespace.toLowerCase(Locale.ROOT).contains(q)
                    || e.type.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }
}
