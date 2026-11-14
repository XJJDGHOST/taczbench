package com.taczbench;

import com.taczbench.core.GunEntry;
import com.taczbench.core.OverlayWriter;
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

/** 把纯逻辑的核心层接到 Minecraft 上：找游戏目录、找存档、写覆盖层、触发重载。 */
public final class WorkshopService {

    private static WorkshopRepository repository;
    private static Schema schema;
    private static List<String> lastWarnings = List.of();

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

        // mods 里的枪包 jar（新版枪包可以直接当模组装）
        for (Path p : list(game.resolve("mods"), ".jar")) {
            repo.addSource("mods/" + p.getFileName(), p);
        }
        // tacz 目录下的枪包：zip 或文件夹
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
        // 资源包形式安装的枪包
        for (Path p : list(game.resolve("resourcepacks"), ".zip")) {
            repo.addSource("resourcepacks/" + p.getFileName(), p);
        }
        // 存档数据包形式安装的枪包
        Path world = worldDir();
        if (world != null) {
            Path dp = world.resolve("datapacks");
            if (Files.isDirectory(dp)) {
                try (Stream<Path> s = Files.list(dp)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        String name = p.getFileName().toString();
                        if (name.equals(OverlayWriter.PACK_DIR_NAME)) {
                            continue; // 自己的覆盖层，不当作来源
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
            String text = Files.readString(file, StandardCharsets.UTF_8);
            schema = Schema.parse(text).mergeOver(builtin);
            return schema;
        } catch (Exception e) {
            TaczBench.LOGGER.warn("读取字段方案失败，改用内置方案", e);
            schema = builtin;
            return schema;
        }
    }

    // ------------------------------------------------------------ 应用

    /**
     * 把改动写成覆盖数据包，并让服务端重载一次。
     *
     * @param files 包内路径 → 文件内容
     * @return 实际写入的文件数量
     */
    public static int apply(Map<String, String> files) throws IOException {
        MinecraftServer server = server();
        if (server == null) {
            throw new IOException("还没有进入存档，无法应用（多人游戏需要把模组装在服务端）");
        }
        Path world = server.getWorldPath(LevelResource.ROOT);
        int written = OverlayWriter.sync(world, files);
        enableAndReload(server);
        return written;
    }

    public static void clearAll() throws IOException {
        MinecraftServer server = server();
        if (server == null) {
            throw new IOException("还没有进入存档");
        }
        OverlayWriter.clear(server.getWorldPath(LevelResource.ROOT));
        enableAndReload(server);
    }

    public static List<String> appliedFiles() {
        Path world = worldDir();
        if (world == null) {
            return List.of();
        }
        return OverlayWriter.listApplied(world);
    }

    /** 保证覆盖层被启用，然后重载数据包。 */
    private static void enableAndReload(MinecraftServer server) {
        PackRepository repo = server.getPackRepository();
        // 新写入的数据包需要重新扫描目录才会被发现；这个方法在不同版本里可见性不一样，用反射兜一下。
        try {
            PackRepository.class.getMethod("reload").invoke(repo);
        } catch (Throwable ignored) {
            // 拿不到就继续，下面重载时原版也会刷新一次
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        String packId = "file/" + OverlayWriter.PACK_DIR_NAME;
        if (!selected.contains(packId)) {
            selected.add(packId);
        }
        server.reloadResources(selected);
    }

    /** 把当前所有改动拼成「包内路径 → 文本」。 */
    public static Map<String, String> buildFiles(Map<String, Map<String, String>> edits, WorkshopRepository repo) {
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : edits.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            GunEntry entry = findByKey(repo, e.getKey());
            if (entry == null) {
                continue;
            }
            String text = entry.originalDoc().render(e.getValue());
            files.put(entry.entryPath, text);
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
}
