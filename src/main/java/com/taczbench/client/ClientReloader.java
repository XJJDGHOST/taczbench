package com.taczbench.client;

import com.taczbench.TaczBench;
import net.minecraft.client.Minecraft;

/**
 * 调 TACZ 自己的客户端重载（等价于 {@code /tacz reload} 里客户端那一半：
 * {@code ClientAssetsManager.reloadAllPack()}）。
 * <p>
 * 用反射而不是直接 import，是为了「没装 TACZ 也能加载本模组」。
 * <p>
 * 服务端数据重载是异步的，立刻刷新一次可能还是旧数据，所以再排一次 5 秒后的刷新。
 */
public final class ClientReloader {

    private static int countdown;
    private static boolean warned;

    private ClientReloader() {
    }

    /** 改完值之后调一次：立刻刷新 + 1.5 秒后再刷一次（等服务端重载落地）。 */
    public static void requestReload() {
        doReload();
        countdown = 100;
    }

    /** 由客户端 tick 驱动。 */
    public static void tick() {
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) {
                doReload();
            }
        }
    }

    private static void doReload() {
        try {
            Class<?> cls = Class.forName("com.tacz.guns.client.resource.ClientAssetsManager");
            cls.getMethod("reloadAllPack").invoke(null);
            TaczBench.LOGGER.info("已调用 TACZ 客户端重载");
        } catch (ClassNotFoundException e) {
            if (!warned) {
                warned = true;
                TaczBench.LOGGER.info("没有装 TACZ，跳过客户端重载");
            }
        } catch (Throwable t) {
            TaczBench.LOGGER.warn("调用 TACZ 客户端重载失败（服务端数据不受影响）", t);
        }
    }

    /** 只是给调用方确认「当前在客户端线程」。 */
    public static boolean onClientThread() {
        return Minecraft.getInstance().isSameThread();
    }
}
