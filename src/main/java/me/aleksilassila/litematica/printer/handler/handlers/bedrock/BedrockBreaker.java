package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.interaction.ToolPreparationResult;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BedrockBreaker {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private final Minecraft client;
    private final BedrockPlacer placer;
    private final Map<BlockPos, CleanupAttempt> pending = new HashMap<>();
    private Object connection;
    private Object player;
    private ClientLevel level;
    private Object epoch;
    private CleanupAttempt active;

    BedrockBreaker(Minecraft client, BedrockPlacer placer) {
        this.client = client;
        this.placer = placer;
    }

    /** One owner for residue from targets and the retired-machine cleanup queue. */
    boolean breakCleanup(BlockPos pos, Direction direction) {
        if (!this.refresh() || pos == null || direction == null) return false;
        if (this.active != null) return this.advanceActive();
        var previous = this.pending.get(pos);
        if (previous != null && !previous.retryNeeded) return false;
        return this.startCleanup(pos, direction, previous != null);
    }

    private boolean startCleanup(BlockPos pos, Direction direction, boolean fallback) {
        if (!this.canMine(pos)
                || this.placer.isInventorySettling() || !NetworkUtils.canSendPacketBatch()) return false;
        BlockState state = this.level.getBlockState(pos);
        if (!BedrockTargetBlocks.isCleanupResidue(state) || state.is(Blocks.MOVING_PISTON)) return false;
        float hardness = state.getDestroySpeed(this.level, pos);
        if (hardness < 0.0F) return false;
        if (hardness > 0.0F) {
            if (!this.placer.prepareTool(state, true)
                    || !toolReady(RuntimeAccess.get().toolSwitchService().prepareForBreak(pos, state, false), this.placer)
                    || !this.placer.prepareCriticalToolTick()) return false;
        }
        float progress = hardness == 0.0F || this.client.player.getAbilities().instabuild
                ? 1.0F : state.getDestroyProgress(this.client.player, this.level, pos);
        if (!(progress > 0.0F)) return false;
        var packets = new ArrayList<Packet<?>>();
        if (!appendAction(packets, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction)) return false;
        boolean open = progress < 0.7F || fallback;
        if (!open && progress < 1.0F
                && !appendAction(packets, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction)) return false;
        if (!NetworkUtils.sendPacketBatch(packets)) return false;
        if (hardness > 0.0F) this.placer.recordToolUse();
        var attempt = new CleanupAttempt(pos.immutable(), direction, state, this.level.getGameTime(), open, fallback,
                normalizedTool(this.client.player.getMainHandItem()));
        this.pending.put(attempt.pos, attempt);
        if (open) this.active = attempt;
        return true;
    }

    boolean hasActiveMining() {
        return this.active != null && this.sameContext()
                && (!BedrockEnvironment.isChunkLoaded(this.level, this.active.pos)
                || !this.level.getBlockState(this.active.pos).isAir());
    }

    boolean isPendingAt(BlockPos pos) {
        return pos != null && this.sameContext() && this.pending.containsKey(pos)
                && (!BedrockEnvironment.isChunkLoaded(this.level, pos) || !this.level.getBlockState(pos).isAir());
    }

    void advanceMining() {
        if (this.refresh() && this.active != null) this.advanceActive();
    }

    void onServerBlockUpdate(BlockPos pos) {
        if (this.bindContext() && pos != null && BedrockEnvironment.isChunkLoaded(this.level, pos)
                && this.level.getBlockState(pos).isAir()) {
            this.pending.remove(pos);
            if (this.active != null && this.active.pos.equals(pos)) this.active = null;
        }
    }

    void reset() {
        if (this.sameWorld()) this.abortActive();
        this.pending.clear();
        this.active = null;
        this.connection = this.player = this.epoch = null;
        this.level = null;
    }

    private boolean bindContext() {
        if (this.client.gameMode == null) {
            this.reset();
            return false;
        }
        var listener = this.client.getConnection();
        Object currentConnection = listener == null ? null : listener.getConnection();
        var runtime = RuntimeAccess.currentOrNull();
        Object currentEpoch = runtime == null ? null : runtime.epoch();
        if (this.connection != currentConnection || this.player != this.client.player
                || this.level != this.client.level || !Objects.equals(this.epoch, currentEpoch)) {
            this.reset();
            this.connection = currentConnection;
            this.player = this.client.player;
            this.level = this.client.level;
            this.epoch = currentEpoch;
        }
        return this.connection != null && this.player != null && this.level != null;
    }

    private boolean sameWorld() {
        var listener = this.client.getConnection();
        return listener != null && this.connection == listener.getConnection()
                && this.player == this.client.player && this.level == this.client.level;
    }

    private boolean sameContext() {
        var runtime = RuntimeAccess.currentOrNull();
        return this.sameWorld() && this.client.gameMode != null
                && Objects.equals(this.epoch, runtime == null ? null : runtime.epoch());
    }

    private boolean refresh() {
        if (!this.bindContext()) return false;
        long now = this.level.getGameTime();
        var iterator = this.pending.values().iterator();
        while (iterator.hasNext()) {
            var attempt = iterator.next();
            if (!BedrockEnvironment.isChunkLoaded(this.level, attempt.pos)) {
                // Unknown AIR cannot acknowledge this START. Cancel the hand owner while the
                // target/coordinator keeps the footprint for a later loaded observation.
                if (attempt == this.active) {
                    this.abortActive();
                    this.active = null;
                }
                iterator.remove();
                continue;
            }
            BlockState state = this.level.getBlockState(attempt.pos);
            boolean changed = state.getBlock() != attempt.state.getBlock();
            long sentTick = attempt.stopTick < 0 ? attempt.startTick : attempt.stopTick;
            boolean timedOut = !attempt.retryNeeded && (attempt != this.active || attempt.stopTick >= 0)
                    && now - sentTick >= this.placer.cleanupConfirmationTimeoutTicks();
            if (changed || !this.canMine(attempt.pos)) {
                if (attempt == this.active) {
                    if (!state.isAir()) this.abortActive();
                    this.active = null;
                }
                iterator.remove();
            } else if (timedOut) {
                // Unknown results retain their retry phase. Repeating START-only forever never
                // finishes when the server's actual delta was lower than the client estimate.
                attempt.retryNeeded = true;
                if (attempt == this.active && !attempt.fallback) {
                    this.abortActive();
                    this.active = null;
                }
            }
        }
        return true;
    }

    private boolean advanceActive() {
        var attempt = this.active;
        if (attempt.retryNeeded) return this.startCleanup(attempt.pos, attempt.direction, true);
        if (attempt.stopTick >= 0) return false;
        if (!sameTool(attempt.tool, normalizedTool(this.client.player.getMainHandItem()))
                || !toolReady(RuntimeAccess.get().toolSwitchService().prepareForBreak(
                        attempt.pos, attempt.state, false), this.placer)) {
            this.abortActive();
            this.pending.remove(attempt.pos);
            this.active = null;
            return false;
        }
        float progress = this.level.getBlockState(attempt.pos)
                .getDestroyProgress(this.client.player, this.level, attempt.pos);
        long elapsed = this.level.getGameTime() - attempt.startTick;
        // The server evaluates the current delta * (elapsed + 1), not the number of client calls.
        if (elapsed <= 0 || !(progress * (elapsed + 1) >= 0.7F)) return false;
        var packets = new ArrayList<Packet<?>>();
        if (!appendAction(packets, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                attempt.pos, attempt.direction) || !NetworkUtils.sendPacketBatch(packets)) return false;
        attempt.stopTick = this.level.getGameTime();
        return true;
    }

    private boolean canMine(BlockPos pos) {
        return BedrockEnvironment.isChunkLoaded(this.level, pos)
                && BedrockEnvironment.canInteract(pos) && this.level.getWorldBorder().isWithinBounds(pos);
    }

    private void abortActive() {
        if (this.active == null) return;
        var packets = new ArrayList<Packet<?>>();
        if (appendAction(packets, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                this.active.pos, this.active.direction)) NetworkUtils.sendPacketBatch(packets);
    }

    private static boolean appendAction(List<Packet<?>> packets, ServerboundPlayerActionPacket.Action action,
                                        BlockPos pos, Direction direction) {
        var packet = NetworkUtils.createPacket(sequence -> new ServerboundPlayerActionPacket(
                action, pos, direction
                //#if MC >= 11900
                , sequence
                //#endif
        ));
        if (packet == null) return false;
        packets.add(packet);
        return true;
    }

    private static ItemStack normalizedTool(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        var copy = stack.copy();
        copy.setCount(1);
        copy.setDamageValue(0);
        return copy;
    }

    private static boolean sameTool(ItemStack first, ItemStack second) {
        //#if MC > 12004
        return ItemStack.isSameItemSameComponents(first, second);
        //#else
        //$$ return ItemStack.isSameItemSameTags(first, second);
        //#endif
    }

    private static final class CleanupAttempt {
        final BlockPos pos;
        final Direction direction;
        final BlockState state;
        final long startTick;
        final ItemStack tool;
        final boolean fallback;
        long stopTick;
        boolean retryNeeded;

        CleanupAttempt(BlockPos pos, Direction direction, BlockState state, long tick,
                       boolean open, boolean fallback, ItemStack tool) {
            this.pos = pos;
            this.direction = direction;
            this.state = state;
            this.startTick = tick;
            this.tool = tool;
            this.fallback = fallback;
            this.stopTick = open ? -1 : tick;
        }
    }

    public static boolean breakBlock(BlockPos pos) {
        return breakBlock(pos, Direction.DOWN, true);
    }

    public static boolean breakBlock(BlockPos pos, boolean predictRemoval) {
        return breakBlock(pos, Direction.DOWN, predictRemoval);
    }

    public static boolean breakBlock(BlockPos pos, Direction direction, boolean predictRemoval) {
        return breakBlock(pos, direction, predictRemoval, null);
    }

    public static boolean breakBlock(BlockPos pos, boolean predictRemoval, BedrockPlacer placer) {
        return breakBlock(pos, Direction.DOWN, predictRemoval, placer);
    }

    public static boolean breakBlock(BlockPos pos, Direction direction, boolean predictRemoval, BedrockPlacer placer) {
        if (placer != null) return placer.breakCleanupBlock(pos, direction);
        if (CLIENT.level == null || CLIENT.player == null || !BedrockEnvironment.canInteract(pos)) {
            return false;
        }
        var state = CLIENT.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // MOVING_PISTON has destroySpeed -1: the server's calcBlockBreakingDelta is negative, so it
        // can never reach the 0.7 STOP threshold nor the failedToMine 1.0 auto-break. Attempting it
        // just spams hit sounds forever ("敲击声但没有回收"). Wait for it to settle into a PISTON /
        // PISTON_HEAD / air and let the normal residue path handle it.
        if (state.is(Blocks.MOVING_PISTON)) {
            return false;
        }

        boolean cleanupResidue = BedrockTargetBlocks.isCleanupResidue(state);
        boolean switched = cleanupResidue ? BedrockInventory.switchToCleanupTool(state) : BedrockInventory.switchToBestTool(state);
        if (!switched) {
            return false;
        }
        var preparation = RuntimeAccess.get().toolSwitchService().prepareForBreak(pos, state, false);
        if (!toolReady(preparation, null)) return false;

        if (CLIENT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension) {
            BlockBreakResult result = gameModeExtension.litematica_printer$continueDestroyBlock(
                    false,
                    pos,
                    direction,
                    false,
                    false
            );
            return result != BlockBreakResult.FAILED && result != BlockBreakResult.ABORTED;
        }
        return false;
    }

    static boolean prepareCriticalTool(BlockPos pistonPos, BlockState pistonState) {
        return prepareCriticalTool(pistonPos, pistonState, null);
    }

    static boolean prepareCriticalTool(BlockPos pistonPos, BlockState pistonState, BedrockPlacer placer) {
        if (CLIENT.level == null || CLIENT.player == null || pistonPos == null
                || pistonState == null || pistonState.isAir()) {
            return false;
        }
        if (!(placer == null ? BedrockInventory.switchToBestTool(pistonState) : placer.prepareTool(pistonState, false))) {
            return false;
        }
        return toolReady(RuntimeAccess.get().toolSwitchService().prepareForBreak(pistonPos, pistonState, false), placer)
                && BedrockInventory.canBreakInCriticalBundle(CLIENT.player, pistonState, CLIENT.player.getMainHandItem())
                && (placer == null || placer.prepareCriticalToolTick());
    }

    private static boolean toolReady(ToolPreparationResult preparation, BedrockPlacer placer) {
        if (preparation == ToolPreparationResult.SWITCHED_WAITING_SYNC && placer != null) {
            // Tweakeroo may move a replacement tool after our ordinary selection.
            placer.confirmToolSwitch();
        }
        return preparation == ToolPreparationResult.READY;
    }

}
