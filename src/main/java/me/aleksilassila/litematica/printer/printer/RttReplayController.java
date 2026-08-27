package me.aleksilassila.litematica.printer.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;

/**
 * RTT(往返延迟)自适应重放间隔。
 *
 * <p>打印在服务器下容易放错的一个根因是「重放间隔 < RTT」:客户端在服务端确认上一次交互之前就发出了下一个,
 * 客户端预测与服务端真实状态因此不一致,表现为放错/幽灵方块。该控制器把玩家的实测 ping 折算成 tick 数,
 * 作为放置之间的最小间隔下限,保证发包节奏不快于一次往返,从而让每次放置尽量落在上一次被服务端确认之后。
 *
 * <p>RTT 来源采用 {@link PlayerInfo#getLatency()}(玩家列表 ping,毫秒):纯客户端、无 mixin、无服务端依赖,
 * 且该 API 在所有目标版本上稳定。单机/局域网或被代理隐藏 ping 时返回 0,此时不施加任何额外间隔(本就无需放慢)。
 * 用 EWMA 平滑抖动,避免 ping 跳变导致间隔忽快忽慢。
 */
public final class RttReplayController implements RuntimeComponent {
    private static final int MILLIS_PER_TICK = 50;
    /** EWMA 平滑系数:新样本占比。越小越平滑、对抖动越不敏感。 */
    private static final double SMOOTHING = 0.25D;
    /** RTT 上限保护(tick),避免极端高延迟把间隔拉到离谱的大,卡死打印。 */
    private static final int MAX_EXTRA_TICKS = 40;

    private double smoothedRttMillis = 0.0D;
    private long sampledTick = Long.MIN_VALUE;
    private double sampledRttMillis = 0.0D;

    public RttReplayController() {
    }

    /**
     * 返回 RTT 建议的最小重放间隔 tick 数；调用方再与基础间隔取最大值。
     *
     * @param safetyPercent 安全系数(百分比):以 RTT 的该百分比作为最小间隔,100 表示恰好一个往返。
     * @return 最小间隔 tick 数([0, {@link #MAX_EXTRA_TICKS}]);无有效 ping 时为 0。
     */
    public int getExtraIntervalTicks(int safetyPercent) {
        double rttMillis = this.sampleRttMillis();
        if (rttMillis <= 0.0D) {
            return 0;
        }
        return intervalTicksFor(rttMillis, safetyPercent);
    }

    static int intervalTicksFor(double rttMillis, int safetyPercent) {
        double effectiveMillis = rttMillis * Math.max(0, safetyPercent) / 100.0D;
        int ticks = (int) Math.ceil(effectiveMillis / MILLIS_PER_TICK);
        return Math.max(0, Math.min(MAX_EXTRA_TICKS, ticks));
    }

    /** 当前平滑后的 RTT 估计(毫秒),供 HUD/调试展示。 */
    public int getEstimatedRttMillis() {
        return (int) Math.round(this.smoothedRttMillis);
    }

    public void reset() {
        this.smoothedRttMillis = 0.0D;
        this.sampledTick = Long.MIN_VALUE;
        this.sampledRttMillis = 0.0D;
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.reset(); }

    private double sampleRttMillis() {
        long currentTick = currentGameTick();
        if (currentTick != Long.MIN_VALUE && currentTick == this.sampledTick) {
            return this.sampledRttMillis;
        }
        int rawLatency = readLatencyMillis();
        if (rawLatency <= 0) {
            // ping 不可用(单机/局域网/代理隐藏)。保留已平滑值不更新,返回 0 表示不施加额外间隔。
            this.sampledRttMillis = this.smoothedRttMillis > 0.0D ? this.smoothedRttMillis : 0.0D;
            this.sampledTick = currentTick;
            return this.sampledRttMillis;
        }
        if (this.smoothedRttMillis <= 0.0D) {
            this.smoothedRttMillis = rawLatency;
        } else {
            this.smoothedRttMillis = this.smoothedRttMillis * (1.0D - SMOOTHING) + rawLatency * SMOOTHING;
        }
        this.sampledRttMillis = this.smoothedRttMillis;
        this.sampledTick = currentTick;
        return this.sampledRttMillis;
    }

    private static long currentGameTick() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? Long.MIN_VALUE : client.level.getGameTime();
    }

    private static int readLatencyMillis() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientPacketListener connection = client.getConnection();
        if (player == null || connection == null) {
            return 0;
        }
        PlayerInfo info = connection.getPlayerInfo(player.getUUID());
        if (info == null) {
            return 0;
        }
        return Math.max(0, info.getLatency());
    }
}
