package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Delayed-destroy observations and local mining sounds, separate from packet sequencing. */
final class MiningFeedback {
    private final Minecraft client;
    private final Map<BlockPos, Long> pendingDelayedDestroys = new LinkedHashMap<>();
    private float hitSoundTicks;
    private BlockPos pendingBreakSoundPos;
    private BlockState pendingBreakSoundState;

    MiningFeedback(Minecraft client) {
        this.client = client;
    }

    void reset() {
        this.pendingDelayedDestroys.clear();
        this.pendingBreakSoundPos = null;
        this.pendingBreakSoundState = null;
        this.resetHitSound();
    }

    void addPending(BlockPos pos, long tick) {
        if (pos != null) this.pendingDelayedDestroys.put(pos.immutable(), tick);
    }

    boolean hasPending(BlockPos pos) {
        return pos != null && this.pendingDelayedDestroys.containsKey(pos);
    }

    void removePending(BlockPos pos) {
        if (pos != null) this.pendingDelayedDestroys.remove(pos);
    }

    void cleanupPending(LocalPlayer player, ClientLevel level, long currentTick) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = this.pendingDelayedDestroys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            BlockPos pos = entry.getKey();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                iterator.remove();
                MineDebugLog.write("mine pending cleared pos=" + MineDebugLog.pos(pos) + " reason=state_cleared");
                continue;
            }
            float progress = state.getDestroyProgress(player, level, pos);
            int timeout = progress <= 0.0F ? 200
                    : Math.max(8, Math.min((int) Math.ceil(1.0F / progress) + 10, 200));
            if (currentTick - entry.getValue() >= timeout) {
                iterator.remove();
                MineDebugLog.write("mine pending timeout pos=" + MineDebugLog.pos(pos)
                        + " timeoutTicks=" + timeout + " state=" + MineDebugLog.describeState(state));
            }
        }
    }

    void resetHitSound() {
        this.hitSoundTicks = 0.0F;
    }

    void queueBreakSound(BlockPos pos, BlockState state) {
        if (pos == null || state == null || state.isAir()) return;
        this.pendingBreakSoundPos = pos.immutable();
        this.pendingBreakSoundState = state;
    }

    void clearPendingBreakSound(BlockPos pos) {
        if (pos != null && pos.equals(this.pendingBreakSoundPos)) {
            this.pendingBreakSoundPos = null;
            this.pendingBreakSoundState = null;
        }
    }

    void flushPendingBreakSound(BlockPos pos) {
        if (pos != null && pos.equals(this.pendingBreakSoundPos) && this.pendingBreakSoundState != null) {
            this.playBreakSound(pos, this.pendingBreakSoundState);
            this.pendingBreakSoundPos = null;
            this.pendingBreakSoundState = null;
        }
    }

    void playHitSound(LocalPlayer player, ClientLevel level, BlockState state, BlockPos pos, boolean force) {
        if (state.isAir()) return;
        if (!force && this.hitSoundTicks % 4.0F != 0.0F) {
            this.hitSoundTicks += 1.0F;
            return;
        }
        SoundType sound = state.getSoundType();
        this.client.getSoundManager().play(new SimpleSoundInstance(
                sound.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                Math.max((sound.getVolume() + 1.0F) / 4.0F, 0.35F), sound.getPitch() * 0.5F,
                SoundInstance.createUnseededRandom(), pos));
        this.hitSoundTicks += 1.0F;
    }

    void playBreakSound(BlockPos pos, BlockState state) {
        if (pos == null || state == null || state.isAir()) return;
        SoundType sound = state.getSoundType();
        this.client.getSoundManager().play(new SimpleSoundInstance(
                sound.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                Math.max((sound.getVolume() + 1.0F) / 2.0F, 0.7F), sound.getPitch() * 0.8F,
                SoundInstance.createUnseededRandom(), pos));
    }
}
