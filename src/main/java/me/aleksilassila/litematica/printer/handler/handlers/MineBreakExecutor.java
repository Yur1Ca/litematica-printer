package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.ToolSelectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

final class MineBreakExecutor {

    private final Minecraft client;
    private final TweakerooAdapter tweakeroo;
    private final Map<BlockState, Float> currentProgressCache = new IdentityHashMap<>();
    private final Map<BlockState, ToolChoice> bestToolCache = new IdentityHashMap<>();
    private boolean resolveBestTool;
    private ItemStack analyzedHand;
    private DataComponentPatch analyzedComponents;
    private int analyzedCount;
    private int analyzedSlot;
    private int inventoryRevision;

    MineBreakExecutor(Minecraft client, TweakerooAdapter tweakeroo) {
        this.client = client;
        this.tweakeroo = tweakeroo;
    }

    public void beginBatch() {
        this.reset();
        this.resolveBestTool = Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()
                || this.tweakeroo.isToolSwitchEnabled();
    }

    public void reset() {
        this.currentProgressCache.clear();
        this.bestToolCache.clear();
        this.analyzedHand = null;
        this.analyzedComponents = null;
        this.resolveBestTool = false;
    }

    @Nullable
    public Target analyze(BlockPos pos, BlockState state) {
        LocalPlayer player = this.client.player;
        ClientLevel level = this.client.level;
        if (player == null || level == null || !level.getWorldBorder().isWithinBounds(pos)) {
            return null;
        }
        ItemStack currentStack = player.getMainHandItem();
        if (player.getAbilities().instabuild) {
            return new Target(
                    pos.immutable(),
                    state,
                    1.0F,
                    1.0F,
                    Direction.DOWN,
                    currentStack.getItem(),
                    false,
                    false
            );
        }
        this.refreshToolContext(player, currentStack);
        float currentProgress = this.getCurrentProgress(player, level, state, currentStack, pos);
        ToolChoice toolChoice = this.getBestToolChoice(player, level, state, currentStack, currentProgress, pos);
        float bestProgress = toolChoice.progress();
        if (bestProgress <= 0.0F && !player.getAbilities().instabuild) {
            return null;
        }
        return new Target(
                pos.immutable(),
                state,
                currentProgress,
                bestProgress,
                Direction.DOWN,
                toolChoice.item(),
                toolChoice.currentPreservesDrops(),
                toolChoice.preservesDrops()
        );
    }

    public boolean isCurrentToolEffective(Target target) {
        LocalPlayer player = this.client.player;
        if (player == null || !this.shouldResolveBestTool()) {
            return true;
        }
        if (target.bestPreservesDrops && !target.currentPreservesDrops) {
            return false;
        }
        return target.currentProgress >= target.bestProgress;
    }

    public boolean hasSameBestTool(Target target, @Nullable Item item) {
        return target != null && target.bestToolItem == item;
    }

    /** Same-tick dispatch may switch, replace or damage the tool between two ranked positions. */
    private void refreshToolContext(LocalPlayer player, ItemStack hand) {
        var inventory = player.getInventory();
        int slot = InventoryUtils.getSelectedSlot(inventory);
        int revision = inventory.getTimesChanged();
        var components = hand.getComponentsPatch();
        if (hand == this.analyzedHand && slot == this.analyzedSlot && revision == this.inventoryRevision
                && hand.getCount() == this.analyzedCount && components.equals(this.analyzedComponents)) return;
        this.currentProgressCache.clear();
        this.bestToolCache.clear();
        this.analyzedHand = hand;
        this.analyzedSlot = slot;
        this.inventoryRevision = revision;
        this.analyzedCount = hand.getCount();
        this.analyzedComponents = components;
    }

    private ToolChoice getBestToolChoice(
            LocalPlayer player,
            ClientLevel level,
            BlockState state,
            ItemStack currentStack,
            float currentProgress,
            BlockPos pos
    ) {
        ToolChoice cached = this.bestToolCache.get(state);
        if (cached != null) {
            return cached;
        }
        float bestProgress = currentProgress;
        Item bestItem = currentStack.getItem();
        boolean preferSilkTouch = ToolSelectionUtils.prefersSilkTouchForDrops(state);
        boolean currentPreservesDrops = preferSilkTouch
                && this.tweakeroo.isCurrentToolUsable(currentStack)
                && ToolSelectionUtils.hasSilkTouch(currentStack);
        boolean bestPreservesDrops = currentPreservesDrops;
        if (!this.shouldResolveBestTool()) {
            ToolChoice choice = new ToolChoice(bestItem, bestProgress, currentPreservesDrops, bestPreservesDrops);
            this.bestToolCache.put(state, choice);
            return choice;
        }
        for (ItemStack stack : InventoryUtils.getMainStacks(player.getInventory())) {
            if (stack.isEmpty() || !this.tweakeroo.isCurrentToolUsable(stack)) {
                continue;
            }
            float progress = this.getDestroyProgress(player, level, state, stack, pos);
            boolean stackPreservesDrops = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(stack);
            if ((stackPreservesDrops && !bestPreservesDrops)
                    || stackPreservesDrops == bestPreservesDrops && progress > bestProgress) {
                bestProgress = progress;
                bestItem = stack.getItem();
                bestPreservesDrops = stackPreservesDrops;
            }
        }
        ToolChoice choice = new ToolChoice(bestItem, bestProgress, currentPreservesDrops, bestPreservesDrops);
        this.bestToolCache.put(state, choice);
        return choice;
    }

    private boolean shouldResolveBestTool() {
        return this.resolveBestTool;
    }

    private float getDestroyProgress(
            LocalPlayer player,
            ClientLevel level,
            BlockState state,
            ItemStack stack,
            BlockPos pos
    ) {
        if (!this.tweakeroo.isCurrentToolUsable(stack)) {
            return 0.0F;
        }
        // Use the live state hardness, not Block.defaultDestroyTime(). The latter is only the
        // block's registered default and becomes stale for state-aware/custom implementations.
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }

    private float getCurrentProgress(
            LocalPlayer player,
            ClientLevel level,
            BlockState state,
            ItemStack stack,
            BlockPos pos
    ) {
        Float cached = this.currentProgressCache.get(state);
        if (cached != null) {
            return cached;
        }
        float progress = this.getDestroyProgress(player, level, state, stack, pos);
        this.currentProgressCache.put(state, progress);
        return progress;
    }

    public static final class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final float currentProgress;
        private final float bestProgress;
        private final Direction direction;
        private final Item bestToolItem;
        private final boolean currentPreservesDrops;
        private final boolean bestPreservesDrops;

        private Target(BlockPos pos, BlockState state, float currentProgress, float bestProgress, Direction direction, Item bestToolItem,
                       boolean currentPreservesDrops, boolean bestPreservesDrops) {
            this.pos = pos;
            this.state = state;
            this.currentProgress = currentProgress;
            this.bestProgress = bestProgress;
            this.direction = direction;
            this.bestToolItem = bestToolItem;
            this.currentPreservesDrops = currentPreservesDrops;
            this.bestPreservesDrops = bestPreservesDrops;
        }

        public BlockPos pos() {
            return this.pos;
        }

        public BlockState state() {
            return this.state;
        }

        public float progress() {
            return this.bestProgress;
        }

        public float currentProgress() {
            return this.currentProgress;
        }

        public Item bestToolItem() {
            return this.bestToolItem;
        }

        public boolean shouldSwitchToRecoveryTool(ItemStack currentStack) {
            return this.bestPreservesDrops && !ToolSelectionUtils.hasSilkTouch(currentStack);
        }
    }

    private record ToolChoice(Item item, float progress, boolean currentPreservesDrops, boolean preservesDrops) {
    }
}
