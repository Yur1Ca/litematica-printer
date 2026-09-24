package me.aleksilassila.litematica.printer.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

public final class TickContext {
    public final Minecraft mc;
    @Nullable
    public final ClientLevel level;
    @Nullable
    public final LocalPlayer player;
    @Nullable
    public final ClientPacketListener connection;
    @Nullable
    public final MultiPlayerGameMode gameMode;
    @Nullable
    public final GameType gameType;
    @Nullable
    public final HitResult hitResult;
    @Nullable
    public final BlockHitResult blockHitResult;
    public final long gameTime;

    private TickContext(Minecraft mc) {
        this.mc = mc;
        this.level = mc.level;
        this.player = mc.player;
        this.connection = mc.getConnection();
        this.gameMode = mc.gameMode;
        this.gameType = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
        this.hitResult = mc.hitResult;
        this.blockHitResult = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK
                ? (BlockHitResult) mc.hitResult
                : null;
        this.gameTime = this.level == null ? 0L : this.level.getGameTime();
    }

    public static TickContext capture() {
        return new TickContext(Minecraft.getInstance());
    }

    public boolean isReady() {
        return this.level != null
                && this.player != null
                && this.connection != null
                && this.gameMode != null
                && this.gameType != null;
    }
}
