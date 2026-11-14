package com.taczbench.client;

import com.taczbench.TaczBench;
import com.taczbench.WorkshopService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** 客户端入口：注册按键（默认 K，可在「按键设置」里改）。 */
@Mod.EventBusSubscriber(modid = TaczBench.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TaczBenchClient {

    public static final KeyMapping OPEN_WORKSHOP = new KeyMapping(
            "key.taczbench.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.taczbench");

    private TaczBenchClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_WORKSHOP);
    }

    /** 供命令/其它地方复用：把扫描到的枪包数量拼成一行提示。 */
    public static String scanSummary() {
        var repo = WorkshopService.repository();
        return "扫到 " + repo.size() + " 个数据文件（枪械 "
                + repo.ofKind("guns").size() + "，配件 " + repo.ofKind("attachments").size() + "）";
    }
}
