package com.taczbench.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把改动写成「覆盖数据包」，塞进存档的 {@code world/datapacks/} 里。
 * <p>
 * 这样做的好处：
 * <ul>
 *   <li>不动原枪包，任何一个数据文件都能一键还原；</li>
 *   <li>删掉这个数据包 = 全部改动消失，不会留下半截的坏枪包；</li>
 *   <li>走的是原版数据包机制，与 TACZ 版本、与其它模组都不冲突。</li>
 * </ul>
 */
public final class OverlayWriter {

    public static final String PACK_DIR_NAME = "taczbench_overlay";
    /** 1.20.1 的数据包格式号。 */
    public static final int PACK_FORMAT = 15;

    private static final Pattern SAFE_PATH =
            Pattern.compile("^data/[A-Za-z0-9_.-]+/data/(guns|attachments)/[A-Za-z0-9_.-]+\\.json$");

    private OverlayWriter() {
    }

    public static Path packDir(Path worldDir) {
        return worldDir.resolve("datapacks").resolve(PACK_DIR_NAME);
    }

    /**
     * 让覆盖层与 {@code files}（包内路径 → 文本）保持一致：
     * 写新文件、删掉不再需要的文件、顺带保证 pack.mcmeta 存在。
     *
     * @return 实际写入的文件数
     */
    public static int sync(Path worldDir, Map<String, String> files) throws IOException {
        Path dir = packDir(worldDir);
        Files.createDirectories(dir);
        writePackMeta(dir);

        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String p = e.getKey().replace('\\', '/');
            if (!SAFE_PATH.matcher(p).matches()) {
                throw new IllegalArgumentException("不安全的覆盖路径: " + p);
            }
            safe.put(p, e.getValue());
        }

        // 删掉已经不需要的旧文件
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.equals("pack.mcmeta")) {
                    continue;
                }
                if (!safe.containsKey(rel)) {
                    stale.add(p);
                }
            }
        }
        for (Path p : stale) {
            Files.deleteIfExists(p);
        }

        int written = 0;
        for (Map.Entry<String, String> e : safe.entrySet()) {
            Path target = dir.resolve(e.getKey());
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.exists(target) && Files.size(target) == bytes.length) {
                byte[] old = Files.readAllBytes(target);
                if (java.util.Arrays.equals(old, bytes)) {
                    continue;
                }
            }
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            written++;
        }
        return written;
    }

    /** 清空覆盖层（保留空目录，方便下次直接写）。 */
    public static void clear(Path worldDir) throws IOException {
        Path dir = packDir(worldDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.forEach(all::add);
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Path p = all.get(i);
            if (p.equals(dir)) {
                continue;
            }
            String rel = dir.relativize(p).toString().replace('\\', '/');
            if (rel.equals("pack.mcmeta")) {
                continue;
            }
            Files.deleteIfExists(p);
        }
    }

    /** 覆盖层里现在有哪些文件（包内路径）。 */
    public static List<String> listApplied(Path worldDir) {
        Path dir = packDir(worldDir);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (!rel.equals("pack.mcmeta")) {
                    out.add(rel);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void writePackMeta(Path dir) throws IOException {
        Path meta = dir.resolve("pack.mcmeta");
        if (Files.exists(meta)) {
            return;
        }
        String json = "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"description\": \"TaCZ Bench 调校覆盖层（删掉这个文件夹即可还原全部枪械数据）\"\n"
                + "  }\n"
                + "}\n";
        Files.write(meta, json.getBytes(StandardCharsets.UTF_8));
    }
}
