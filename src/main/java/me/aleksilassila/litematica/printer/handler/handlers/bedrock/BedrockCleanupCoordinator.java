package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Owns temporary machine residue and its retry/cooldown policy. */
final class BedrockCleanupCoordinator {
    private static final String RETRY_COOLDOWN_KEY = "cleanup_retry";
    private static final int MAX_QUEUE_SIZE = 512;

    private final Minecraft client;
    private final CooldownUtils cooldownUtils;
    private final Set<BlockPos> queue = new LinkedHashSet<>();
    private final Set<BlockPos> conservative = new LinkedHashSet<>();
    private final Set<BlockPos> blocked = new LinkedHashSet<>();
    private boolean orderDirty;

    BedrockCleanupCoordinator(Minecraft client, CooldownUtils cooldownUtils) {
        this.client = client;
        this.cooldownUtils = cooldownUtils;
    }

    void reset() {
        this.queue.clear();
        this.conservative.clear();
        this.blocked.clear();
        this.orderDirty = false;
    }

    void beginTick() {
        this.blocked.clear();
    }

    boolean isEmpty() {
        return this.queue.isEmpty();
    }

    int size() {
        return this.queue.size();
    }

    boolean contains(BlockPos pos) {
        return this.queue.contains(pos);
    }

    void add(BlockPos pos, boolean predictRemoval) {
        if (pos == null) {
            return;
        }
        this.queue.add(pos);
        if (this.queue.size() > MAX_QUEUE_SIZE) {
            BlockPos oldest = this.queue.iterator().next();
            if (!oldest.equals(pos)) {
                this.queue.remove(oldest);
                this.conservative.remove(oldest);
                this.blocked.remove(oldest);
            }
        }
        this.orderDirty = true;
        if (!predictRemoval) {
            this.conservative.add(pos);
        }
    }

    void remove(BlockPos pos) {
        this.queue.remove(pos);
        this.conservative.remove(pos);
        this.blocked.remove(pos);
        this.orderDirty = true;
    }

    void markBlocked(BlockPos pos) {
        if (pos != null) {
            this.blocked.add(pos);
            this.orderDirty = true;
        }
    }

    void process(Predicate<BlockPos> reserved, int limit) {
        if (this.queue.isEmpty()) {
            return;
        }
        this.reorder();
        int count = 0;
        Iterator<BlockPos> iterator = this.queue.iterator();
        while (iterator.hasNext() && count < limit) {
            BlockPos pos = iterator.next();
            ClientLevel level = this.client.level;
            if (level == null) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                this.conservative.remove(pos);
                this.blocked.remove(pos);
                continue;
            }
            if (reserved.test(pos)) {
                this.markBlocked(pos);
                continue;
            }
            int retryDelay = retryDelay(state);
            if (!this.cooldownUtils.isOnCooldown(level, RETRY_COOLDOWN_KEY, pos)) {
                boolean predictRemoval = !this.conservative.contains(pos);
                if (BedrockBreaker.breakBlock(pos, predictRemoval)) {
                    this.cooldownUtils.setCooldown(level, RETRY_COOLDOWN_KEY, pos, retryDelay);
                    count++;
                }
            }
        }
    }

    void cleanupBlockOrQueue(BlockPos pos, boolean predictRemoval, Predicate<BlockPos> reserved) {
        if (pos == null) {
            return;
        }
        this.add(pos, predictRemoval);
        ClientLevel level = this.client.level;
        if (level == null || reserved.test(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (BedrockTargetBlocks.isCleanupResidue(state) && BedrockBreaker.breakBlock(pos, predictRemoval)) {
            this.cooldownUtils.setCooldown(level, RETRY_COOLDOWN_KEY, pos, retryDelay(state));
        }
    }

    void expedite(BlockPos pos, BlockState state, Predicate<BlockPos> reserved) {
        ClientLevel level = this.client.level;
        if (level == null || pos == null || state == null) {
            return;
        }
        if (!BedrockTargetBlocks.isCleanupResidue(state) || reserved.test(pos)) {
            return;
        }
        if (this.cooldownUtils.isOnCooldown(level, RETRY_COOLDOWN_KEY, pos)) {
            return;
        }
        int retryDelay = retryDelay(state);
        if (BedrockBreaker.breakBlock(pos, false)) {
            this.cooldownUtils.setCooldown(level, RETRY_COOLDOWN_KEY, pos, retryDelay);
        }
    }

    int samplePressure(ClientLevel level, Predicate<BlockPos> reserved) {
        int pressure = 0;
        Iterator<BlockPos> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (pos == null || reserved.test(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                this.conservative.remove(pos);
                this.blocked.remove(pos);
                continue;
            }
            if (!state.isAir() && BedrockTargetBlocks.isCleanupResidue(state)) {
                pressure += pressureWeight(state);
            }
        }
        return pressure;
    }

    int retryDelay(BlockState state) {
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return 3;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.PISTON)) {
            return 4;
        }
        if (state.is(Blocks.MOVING_PISTON)) {
            return 6;
        }
        if (state.is(Blocks.SLIME_BLOCK)) {
            return 8;
        }
        return 6;
    }

    int schedulingPenalty(BlockState state) {
        if (state.is(Blocks.MOVING_PISTON)) {
            return 100;
        }
        if (state.is(Blocks.SLIME_BLOCK)) {
            return 80;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.PISTON)) {
            return 70;
        }
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return 55;
        }
        return 40;
    }

    private void reorder() {
        ClientLevel level = this.client.level;
        if (!this.orderDirty || this.queue.size() < 2 || level == null) {
            return;
        }
        List<BlockPos> ordered = new ArrayList<>(this.queue);
        ordered.sort((left, right) -> Integer.compare(
                this.priority(left, level.getBlockState(left)),
                this.priority(right, level.getBlockState(right))
        ));
        this.queue.clear();
        this.queue.addAll(ordered);
        this.orderDirty = false;
    }

    private int priority(BlockPos pos, BlockState state) {
        int priority = this.blocked.contains(pos) ? -10 : 0;
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return priority;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.PISTON)) {
            return priority + 1;
        }
        if (state.is(Blocks.MOVING_PISTON)) {
            return priority + 2;
        }
        if (state.is(Blocks.SLIME_BLOCK)) {
            return priority + 3;
        }
        return priority + 4;
    }

    private static int pressureWeight(BlockState state) {
        return state.is(Blocks.MOVING_PISTON) || state.is(Blocks.SLIME_BLOCK) ? 2 : 1;
    }
}
