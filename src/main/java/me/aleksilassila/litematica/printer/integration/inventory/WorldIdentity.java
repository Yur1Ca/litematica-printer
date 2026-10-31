package me.aleksilassila.litematica.printer.integration.inventory;

//#if MC >= 12111
import net.minecraft.client.Minecraft;

/**
 * 「我现在在哪个存档／哪个维度」的唯一口径。
 *
 * <p>任何要跨会话保存状态的组件都得用它来打标签，否则换世界之后会把别的存档的数据当成自己的。
 * 实现委托给 {@link SelectedContainerCache}（那边最早写这套判定），这样两个用家的标签
 * 天然一致 —— 不会出现「容器缓存认为是 A 存档、账本认为是 B 存档」的错位。</p>
 */
public final class WorldIdentity {
    private WorldIdentity() {
    }

    /** 存档/服务器标识；拿不到身份时退化成进程内唯一的临时键（此时跨会话必定对不上，安全方向）。 */
    public static String worldId(Minecraft client) {
        return SelectedContainerCache.worldId(client);
    }

    /** 维度标识。 */
    public static String dimensionId(Minecraft client) {
        return SelectedContainerCache.dimensionId(client);
    }
}
//#endif
