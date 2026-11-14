package com.taczbench.client;

import com.taczbench.TaczBench;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 按键打开界面。 */
@Mod.EventBusSubscriber(modid = TaczBench.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientReloader.tick();
        Minecraft mc = Minecraft.getInstance();
        while (TaczBenchClient.OPEN_WORKSHOP.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new WorkshopScreen());
            }
        }
    }
}
