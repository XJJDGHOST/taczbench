package com.taczbench;

import com.taczbench.core.GunEntry;
import com.taczbench.core.JsonDoc;
import com.taczbench.core.JsonNode;
import com.taczbench.core.OverlayWriter;
import com.taczbench.core.PackPatcher;
import com.taczbench.core.Schema;
import com.taczbench.core.WorkshopRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 把纯逻辑的核心层接到 Minecraft 上：找游戏目录、找存档、写枪包、触发重载。 */
public final class WorkshopService {

    private static WorkshopRepository repository;
    private static Schema schema;
    private static List<String> lastWarnings = List.of();
    /** entryKey -> 备份文件路径（第一次改之前的那份，保证还原回原厂） */
    private static final Map<String, String> APPLIED = new LinkedHashMap<>();

    private WorkshopService() {
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczBench.MOD_ID);
    }

    public static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    public static Path worldDir() {
        MinecraftServer s = server();
        return s == null ? null : s.getWorldPath(LevelResource.ROOT);
    }

    // ------------------------------------------------------------ 扫描

    public static synchronized WorkshopRepository repository() {
        if (repository == null) {
            repository = scan();
        }
        return repository;
    }

    public static synchronized WorkshopRepository rescan() {
        repository = scan();
        return repository;
    }

    private static WorkshopRepository scan() {
        WorkshopRepository repo = new WorkshopRepository();
        Path game = gameDir();

        for (Path p : list(game.resolve("mods"), ".jar")) {
            repo.addSource("mods/" + p.getFileName(), p);
        }
        Path tacz = game.resolve("tacz");
        if (Files.isDirectory(tacz)) {
            try (Stream<Path> s = Files.list(tacz)) {
                List<Path> list = new ArrayList<>(s.toList());
                list.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path p : list) {
                    if (Files.isDirectory(p) || p.getFileName().toString().toLowerCase().endsWith(".zip")) {
                        repo.addSource("tacz/" + p.getFileName(), p);
                    }
                }
            } catch (IOException e) {
                TaczBench.LOGGER.warn("读取 tacz 目录失败", e);
            }
        }
        for (Path p : list(game.resolve("resourcepacks"), ".zip")) {
            repo.addSource("resourcepacks/" + p.getFileName(), p);
        }
        Path world = worldDir();
        if (world != null) {
            Path dp = world.resolve("datapacks");
            if (Files.isDirectory(dp)) {
                try (Stream<Path> s = Files.list(dp)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        String name = p.getFileName().toString();
                        if (name.equals(OverlayWriter.PACK_DIR_NAME)) {
                            continue;
                        }
                        if (Files.isDirectory(p) || name.toLowerCase().endsWith(".zip")) {
                            repo.addSource("datapacks/" + name, p);
                        }
                    }
                } catch (IOException e) {
                    TaczBench.LOGGER.warn("读取 datapacks 目录失败", e);
                }
            }
        }
        lastWarnings = List.copyOf(repo.warnings());
        TaczBench.LOGGER.info("TaCZ Bench 扫到 {} 个数据文件（枪械 {}，配件 {}）",
                repo.size(), repo.ofKind(WorkshopRepository.KIND_GUNS).size(),
                repo.ofKind(WorkshopRepository.KIND_ATTACHMENTS).size());
        return repo;
    }

    private static List<Path> list(Path dir, String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(suffix))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            TaczBench.LOGGER.warn("读取 {} 失败", dir, e);
        }
        return out;
    }

    public static List<String> warnings() {
        return lastWarnings;
    }

    // ------------------------------------------------------------ 字段方案

    public static synchronized Schema schema() {
        if (schema != null) {
            return schema;
        }
        Schema builtin = Schema.builtin();
        Path file = configDir().resolve("schema.json");
        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, builtin.toJson(), StandardCharsets.UTF_8);
                schema = builtin;
                return schema;
            }
            schema = Schema.parse(Files.readString(file, StandardCharsets.UTF_8)).mergeOver(builtin);
            return schema;
        } catch (Exception e) {
            TaczBench.LOGGER.warn("读取字段方案失败，改用内置方案", e);
            schema = builtin;
            return schema;
        }
    }

    // ------------------------------------------------------------ 应用

    /**
     * 把改动写回枪包文件（先备份），然后让服务端重载一次资源。
     *
     * @return 实际写入的文件数
     */
    public static synchronized int apply(Map<GunEntry, String> files) throws IOException {
        if (files.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (Map.Entry<GunEntry, String> e : files.entrySet()) {
            GunEntry entry = e.getKey();
            String backup = PackPatcher.patch(entry, e.getValue());
            APPLIED.putIfAbsent(entry.key(), backup);
            written++;
            TaczBench.LOGGER.info("已写入 {} （备份 {}）", entry.sourceLabel + "/" + entry.entryPath, backup);
        }
        saveManifest();
        reload();
        return written;
    }

    /** 还原单个条目。 */
    public static synchronized boolean restoreOne(String key) throws IOException {
        String backup = APPLIED.remove(key);
        if (backup == null) {
            return false;
        }
        GunEntry entry = findByKey(repository(), key);
        if (entry != null) {
            PackPatcher.restore(entry, backup);
        }
        saveManifest();
        reload();
        return true;
    }

    /** 还原所有改过的枪（按备份回滚）。 */
    public static synchronized int restoreAll() throws IOException {
        int n = 0;
        WorkshopRepository repo = repository();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(APPLIED).entrySet()) {
            GunEntry entry = findByKey(repo, e.getKey());
            if (entry == null) {
                continue;
            }
            PackPatcher.restore(entry, e.getValue());
            APPLIED.remove(e.getKey());
            n++;
        }
        saveManifest();
        Path world = worldDir();
        if (world != null) {
            OverlayWriter.clear(world);
        }
        reload();
        return n;
    }

    public static synchronized List<String> appliedFiles() {
        return new ArrayList<>(APPLIED.keySet());
    }

    /** 保证枪包被重新扫描，然后重载数据包。 */
    private static void reload() {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }
        PackRepository repo = server.getPackRepository();
        try {
            repo.reload();   // 让 TACZ 重新扫 tacz/ 目录
        } catch (Throwable t) {
            TaczBench.LOGGER.warn("重新扫描资源包失败（继续尝试重载）", t);
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        try {
            server.reloadResources(selected);
        } catch (Throwable t) {
            TaczBench.LOGGER.warn("重载资源失败", t);
        }
    }

    public static Map<GunEntry, String> buildFiles(Map<String, Map<String, String>> edits, WorkshopRepository repo) {
        Map<GunEntry, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : edits.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            GunEntry entry = findByKey(repo, e.getKey());
            if (entry == null) {
                continue;
            }
            files.put(entry, entry.originalDoc().render(e.getValue()));
        }
        return files;
    }

    public static GunEntry findByKey(WorkshopRepository repo, String key) {
        for (GunEntry e : repo.all()) {
            if (e.key().equals(key)) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 清单

    private static Path manifestPath() {
        return configDir().resolve("applied.json");
    }

    private static void saveManifest() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"applied\": [\n");
        int i = 0;
        for (Map.Entry<String, String> e : APPLIED.entrySet()) {
            sb.append("    {\"key\": ").append(JsonDoc.quote(e.getKey()))
                    .append(", \"backup\": ").append(JsonDoc.quote(e.getValue())).append("}")
                    .append(++i < APPLIED.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        try {
            Files.createDirectories(manifestPath().getParent());
            Files.writeString(manifestPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TaczBench.LOGGER.warn("写 applied.json 失败", e);
        }
    }

    public static synchronized void loadManifest() {
        Path file = manifestPath();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JsonDoc.parse(Files.readString(file, StandardCharsets.UTF_8)).root();
            JsonNode arr = root.member("applied");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr.children()) {
                    JsonNode k = n.member("key");
                    JsonNode b = n.member("backup");
                    if (k != null && b != null) {
                        APPLIED.put(k.asString(), b.asString());
                    }
                }
            }
            TaczBench.LOGGER.info("读到 {} 条已改记录", APPLIED.size());
        } catch (Exception e) {
            TaczBench.LOGGER.warn("读 applied.json 失败", e);
        }
    }
}
