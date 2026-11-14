package com.taczbench.command;

import com.mojang.brigadier.CommandDispatcher;
import com.taczbench.WorkshopService;
import com.taczbench.core.GunEntry;
import com.taczbench.core.WorkshopRepository;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 服务端也能用的几条命令（整合包作者调完可以不开界面核对）。 */
public final class WorkshopCommand {

    private WorkshopCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("taczbench")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("applied")
                        .executes(ctx -> applied(ctx.getSource())))
                .then(Commands.literal("rescan")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> rescan(ctx.getSource()))));
    }

    private static int list(CommandSourceStack source) {
        WorkshopRepository repo = WorkshopService.repository();
        List<GunEntry> guns = repo.ofKind(WorkshopRepository.KIND_GUNS);
        List<GunEntry> attachments = repo.ofKind(WorkshopRepository.KIND_ATTACHMENTS);
        source.sendSuccess(() -> Component.literal("TaCZ Bench：枪械 " + guns.size() + " 把，配件 " + attachments.size() + " 个"), false);
        int limit = Math.min(guns.size(), 20);
        for (int i = 0; i < limit; i++) {
            GunEntry e = guns.get(i);
            source.sendSuccess(() -> Component.literal("  " + e.namespace + ":" + e.idWithoutSuffix() + "  [" + e.type + "]"), false);
        }
        if (guns.size() > limit) {
            int total = guns.size();
            source.sendSuccess(() -> Component.literal("  …… 共 " + total + " 把，界面上可搜索"), false);
        }
        return guns.size();
    }

    private static int applied(CommandSourceStack source) {
        List<String> files = WorkshopService.appliedFiles();
        if (files.isEmpty()) {
            source.sendSuccess(() -> Component.literal("覆盖层是空的，所有数值都是原厂值"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("覆盖层里有 " + files.size() + " 个文件："), false);
        for (String f : files) {
            source.sendSuccess(() -> Component.literal("  " + f), false);
        }
        return files.size();
    }

    private static int rescan(CommandSourceStack source) {
        WorkshopRepository repo = WorkshopService.rescan();
        source.sendSuccess(() -> Component.literal("重新扫描完成：枪械 "
                + repo.ofKind(WorkshopRepository.KIND_GUNS).size() + " 把，配件 "
                + repo.ofKind(WorkshopRepository.KIND_ATTACHMENTS).size() + " 个"), false);
        return repo.size();
    }
}
