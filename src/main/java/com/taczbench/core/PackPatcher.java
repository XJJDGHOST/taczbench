package com.taczbench.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 直接改写原枪包里的数据文件（改之前先整份备份）。
 * <p>
 * 为什么不用「覆盖数据包」：沙盒里试过，写进 {@code world/datapacks/} 的包需要被
 * {@code level.dat} 的 Enabled 列表收录才会生效，运行时很难可靠地做到；
 * 而 TACZ 读的就是枪包文件本身，改文件是确定生效的。备份策略保证可还原。
 */
public final class PackPatcher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PackPatcher() {
    }

    /** @return 备份文件路径 */
    public static String patch(GunEntry entry, String newText) throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        Path pack = entry.packPath;
        if (pack == null) {
            throw new IOException("找不到枪包路径: " + entry.sourceLabel);
        }
        if ("dir".equals(entry.packKind)) {
            Path target = pack.resolve(entry.entryPath);
            if (!Files.isRegularFile(target)) {
                throw new IOException("文件不存在: " + target);
            }
            Path backup = target.resolveSibling(target.getFileName() + ".bak-" + stamp);
            Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(target, newText, StandardCharsets.UTF_8);
            return backup.toString();
        }
        // zip / jar：整包备份后重写
        Path backup = pack.resolveSibling(pack.getFileName() + ".bak-" + stamp);
        Files.copy(pack, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Path tmp = pack.resolveSibling(pack.getFileName() + ".tmp");
        byte[] payload = newText.getBytes(StandardCharsets.UTF_8);
        boolean replaced = false;
        try (ZipFile zip = new ZipFile(pack.toFile());
             OutputStream os = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                ZipEntry copy = new ZipEntry(ze.getName());
                copy.setTime(ze.getTime());
                zos.putNextEntry(copy);
                if (ze.getName().equals(entry.entryPath)) {
                    zos.write(payload);
                    replaced = true;
                } else {
                    try (InputStream in = zip.getInputStream(ze)) {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
        }
        if (!replaced) {
            Files.deleteIfExists(tmp);
            throw new IOException("包里没有这个条目: " + entry.entryPath);
        }
        Files.move(tmp, pack, StandardCopyOption.REPLACE_EXISTING);
        return backup.toString();
    }

    public static void restore(GunEntry entry, String backupPath) throws IOException {
        Path backup = Path.of(backupPath);
        if (!Files.isRegularFile(backup)) {
            throw new IOException("备份不见了: " + backupPath);
        }
        if ("dir".equals(entry.packKind)) {
            Path target = entry.packPath.resolve(entry.entryPath);
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(backup, entry.packPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 这个条目当前有没有备份文件（按名字前缀找最近的）。 */
    public static boolean hasBackup(GunEntry entry) {
        try {
            if ("dir".equals(entry.packKind)) {
                Path target = entry.packPath.resolve(entry.entryPath);
                return listBackups(target.getParent(), target.getFileName().toString() + ".bak-") > 0;
            }
            return listBackups(entry.packPath.getParent(), entry.packPath.getFileName().toString() + ".bak-") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int listBackups(Path dir, String prefix) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }
}
