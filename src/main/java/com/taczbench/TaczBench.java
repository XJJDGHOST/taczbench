package com.taczbench;

import com.mojang.logging.LogUtils;
import com.taczbench.command.WorkshopCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * TaCZ Bench · 永恒枪械工坊调校台。
 * <p>
 * 不修改任何 TACZ 的类，也不往枪包里写东西：读枪包 → 界面里改 → 生成一个覆盖数据包。
 * 所以无论 TACZ 是 1.1.x 还是 1.2.x、无论枪包是 zip / 文件夹 / jar / 数据包安装的，都能用。
 */
@Mod(TaczBench.MOD_ID)
@Mod.EventBusSubscriber(modid = TaczBench.MOD_ID)
public final class TaczBench {

    public static final String MOD_ID = "taczbench";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TaczBench() {
        WorkshopService.loadManifest();
        LOGGER.info("TaCZ Bench 已加载：枪械数据只读扫描 + 备份式改写");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        WorkshopCommand.register(event.getDispatcher());
    }
}
