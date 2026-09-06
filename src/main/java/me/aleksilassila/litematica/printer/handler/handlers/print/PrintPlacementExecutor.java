package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.HudStatus;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.printer.PlacementRateController;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

public final class PrintPlacementExecutor {
    private final ActionPort actionBroker;
    private final CooldownUtils cooldownUtils;
    private final InventorySwitchGuard inventorySwitchGuard;
    private final HudStatsManager hudStats;
    private final MissingMaterialTracker missingMaterials;
    private final LitematicaAdapter litematica;
    private final FallingPlacementTracker fallingPlacements;
    private final MaterialRequestCoordinator materialRequests;
    private final PlacementRateController placementRateController;
    private static final Item[] EMPTY_HAND_ITEMS = {Items.AIR};
    private static final long RESERVE_NOTICE_COOLDOWN_TICKS = 100L;
    private long lastReserveNoticeTick = Long.MIN_VALUE;

    public PrintPlacementExecutor(
            ActionPort actionBroker,
            CooldownUtils cooldownUtils,
            InventorySwitchGuard inventorySwitchGuard,
            HudStatsManager hudStats,
            MissingMaterialTracker missingMaterials,
            LitematicaAdapter litematica,
            FallingPlacementTracker fallingPlacements,
            MaterialRequestCoordinator materialRequests,
            PlacementRateController placementRateController
    ) {
        this.actionBroker = actionBroker;
        this.cooldownUtils = cooldownUtils;
        this.inventorySwitchGuard = inventorySwitchGuard;
        this.hudStats = hudStats;
        this.missingMaterials = missingMaterials;
        this.litematica = litematica;
        this.fallingPlacements = fallingPlacements;
        this.materialRequests = materialRequests;
        this.placementRateController = placementRateController;
    }

    public PrintPlacementResult execute(SchematicBlockContext context, Action action, @Nullable PrintTaskAction taskAction) {
        BlockPos blockPos = context.blockPos;
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && context.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();
            if (FallingBlock.isFree(context.level.getBlockState(downPos))) {
                this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.FALLING_NO_SUPPORT);
                MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(context.requiredBlockName().getString()));
                // Do not hot-retry an unchanged unsupported column. PRINT invalidation expands a
                // support block update to its neighbours, so this target is discovered again as
                // soon as the block below changes while unrelated columns keep printing.
                return PrintPlacementResult.worldBlocked();
            }
        }

        Direction side = action.getValidSide(context.level, blockPos);
        if (side == null) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.NO_VALID_FACE);
            return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
        }

        Item[] requiredItems = normalizeRequiredItems(action.getRequiredItems(context.requiredState.getBlock()));
        Predicate<ItemStack> requiredStackPredicate = action.getRequiredStackPredicate();
        boolean reserveItems = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        int reserveCount = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        boolean itemReady;
        if (requiredStackPredicate == null) {
            itemReady = reserveItems
                    ? InventoryUtils.switchToItemsWithReserve(context.client.player, requiredItems, reserveCount)
                    : InventoryUtils.switchToItems(context.client.player, requiredItems);
        } else {
            itemReady = reserveItems
                    ? InventoryUtils.switchToMatchingStackWithReserve(
                            context.client.player,
                            requiredStackPredicate,
                            action.getRequiredCreativeStack(),
                            reserveCount
                    )
                    : InventoryUtils.switchToMatchingStack(
                            context.client.player,
                            requiredStackPredicate,
                            action.getRequiredCreativeStack()
                    );
        }
        if (!itemReady) {
            boolean retrievalPending =
                    this.inventorySwitchGuard.isWaiting()
                            || this.actionBroker.isResourceHeld(ResourceLease.INVENTORY);
            ItemStack reserveBlockedStack = reserveItems
                    ? InventoryUtils.findReserveBlockedStack(
                            context.client.player,
                            requiredItems,
                            requiredStackPredicate,
                            reserveCount
                    )
                    : ItemStack.EMPTY;
            if (retrievalPending) {
                if (!this.materialRequests.isBusy()) {
                    this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.MISSING_MATERIAL);
                    this.missingMaterials.recordMissing(
                            requiredItems,
                            requiredStackPredicate,
                            action.getRequiredCreativeStack(),
                            context.level.getGameTime()
                    );
                    return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
                }
                this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.WAITING_RETRIEVAL);
                // The HUD describes what is currently absent from the player inventory. Keep the
                // requirement visible while an external material provider is working; tick() removes it
                // as soon as the requested stack actually arrives.
                this.missingMaterials.recordMissing(
                        requiredItems,
                        requiredStackPredicate,
                        action.getRequiredCreativeStack(),
                        context.level.getGameTime()
                );
            } else if (reserveBlockedStack.isEmpty()) {
                this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.MISSING_MATERIAL);
                this.missingMaterials.recordMissing(
                        requiredItems,
                        requiredStackPredicate,
                        action.getRequiredCreativeStack(),
                        context.level.getGameTime()
                );
            } else {
                this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.RESERVE_LIMIT);
                showReserveNotice(context, reserveBlockedStack);
            }
            if (retrievalPending) {
                // 换槽或外部取货只是暂时未就绪。多阶段任务必须保留当前阶段，
                // 否则破冰放水会在材料到达前被当成永久失败并丢失目标。
                return PrintPlacementResult.materialUnavailable(this.materialRequests.blocksPrinterWhilePending());
            }
            // 真正缺少材料属于无效放置，不应消耗每 tick 的有效放置预算。
            // 对多阶段任务也不能停止整轮；调度器会仅暂停当前任务，等待背包增加材料。
            return PrintPlacementResult.materialUnavailable(false);
        }
        this.missingMaterials.resolve(requiredItems, requiredStackPredicate);
        if (!InventoryUtils.isHoldingAnyItem(context.client.player, requiredItems)
                || requiredStackPredicate != null
                && !requiredStackPredicate.test(context.client.player.getMainHandItem())) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.WAITING_ITEM_SYNC);
            return PrintPlacementResult.deferred(true);
        }

        if (!this.placementRateController.canSend(context.level.getGameTime())) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.RTT_THROTTLED);
            return PrintPlacementResult.deferred(true);
        }

        boolean useShift = getUseShift(context, action, side);
        if (!action.queueAction(this.actionBroker, blockPos, side, useShift, context.client.player, requiredItems)) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.ACTION_QUEUE_BUSY);
            return PrintPlacementResult.cancelled(true);
        }
        this.actionBroker.setExpectedStackPredicate(requiredStackPredicate);
        Vec3 hitModifier = this.litematica.usePrecisionPlacement(blockPos, context.requiredState);
        if (hitModifier != null) {
            this.actionBroker.useProtocolHitModifier(hitModifier);
        }
        this.actionBroker.setLook(adjustHorizontalLook(action.getPlayerLook(), context));
        this.actionBroker.setNeedWaitModifyLookFromAction(action.isNeedWaitModifyLook());
        boolean consumedEffectiveExecution = action.isConsumeEffectiveExecution();
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        AtomicBoolean deferred = new AtomicBoolean(false);
        boolean signPlacement = context.requiredState.getBlock() instanceof SignBlock;
        if (signPlacement) {
            this.actionBroker.armPrintSignEdit(blockPos);
        }
        this.actionBroker.setQueueCompletionListener(sendResult -> {
            if (!deferred.get()) {
                return;
            }
            if (sendResult.isSent()) {
                this.recordPlacementSent(context, action, taskAction);
                if (cooldownTicks > 0) {
                    this.cooldownUtils.setCooldown(
                            context.level,
                            PrintHandler.NAME,
                            blockPos,
                            cooldownTicks
                    );
                }
                if (taskAction != null) {
                    taskAction.onSuccess(context, action);
                }
            } else {
                if (signPlacement) {
                    this.actionBroker.cancelPrintSignEdit(blockPos);
                }
                if (sendResult == ActionPort.SendResult.RESERVE_LIMIT) {
                    showReserveNotice(context, context.client.player.getMainHandItem());
                }
                this.hudStats.recordDeferred(
                        HudStatsManager.Mode.PRINT,
                        describeSendFailure(sendResult)
                );
                if (taskAction != null) {
                    taskAction.onCancelled(context, action);
                }
            }
        });

        ActionPort.SendResult sendResult = this.actionBroker.sendQueue(context.client.player);
        if (sendResult.isWaiting()) {
            deferred.set(true);
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, HudStatus.WAITING_LOOK);
            return new PrintPlacementResult(
                    consumedEffectiveExecution,
                    true,
                    PrintPlacementResult.TaskEvent.QUEUED,
                    -1
            );
        }
        if (!sendResult.isSent()) {
            if (signPlacement) {
                this.actionBroker.cancelPrintSignEdit(blockPos);
            }
            if (sendResult == ActionPort.SendResult.RESERVE_LIMIT) {
                showReserveNotice(context, context.client.player.getMainHandItem());
            }
            this.hudStats.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
            return PrintPlacementResult.cancelled(true);
        }

        this.recordPlacementSent(context, action, taskAction);

        return new PrintPlacementResult(
                consumedEffectiveExecution,
                shouldStopAfterTaskAction(taskAction),
                PrintPlacementResult.TaskEvent.SUCCESS,
                cooldownTicks
        );
    }

    private void recordPlacementSent(
            SchematicBlockContext context,
            Action action,
            @Nullable PrintTaskAction taskAction
    ) {
        if (context.requiredState.getBlock() instanceof SignBlock) {
            this.actionBroker.confirmPrintSignEditSent(context.blockPos);
        }
        this.hudStats.trackExpectedBlockState(
                HudStatsManager.Mode.PRINT,
                context.blockPos,
                taskAction == null
                        ? context.requiredState
                        : taskAction.expectedBlockState(context, action)
        );
        this.hudStats.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        this.hudStats.recordStatus(HudStatsManager.Mode.PRINT, HudStatus.RUNNING);
        this.placementRateController.recordSent(context.level.getGameTime());
        if (context.requiredState.getBlock() instanceof FallingBlock) {
            this.fallingPlacements.mark(
                    context.blockPos,
                    context.requiredState,
                    context.currentState,
                    context.level.getGameTime()
            );
        }
    }

    private static String describeSendFailure(ActionPort.SendResult result) {
        return switch (result) {
            case STALE_POSITION -> HudStatus.MOVED_STALE;
            case HELD_ITEM_CHANGED -> HudStatus.HELD_ITEM_CHANGED;
            case RESERVE_LIMIT -> HudStatus.RESERVE_LIMIT;
            case NO_PLAYER, NO_GAME_MODE -> HudStatus.CLIENT_NOT_READY;
            case INTERACTION_REJECTED -> HudStatus.INTERACTION_REJECTED;
            case NO_QUEUED_ACTION -> HudStatus.ACTION_NOT_QUEUED;
            default -> HudStatus.ACTION_NOT_SENT;
        };
    }

    private static boolean getUseShift(SchematicBlockContext context, Action action, Direction side) {
        if (action.getShift() != null) {
            return action.getShift();
        }
        return (Implementation.isInteractive(context.level.getBlockState(context.blockPos.relative(side)).getBlock())
                && !(action instanceof ClickAction))
                || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
    }

    @Nullable
    private PlayerLook adjustHorizontalLook(@Nullable PlayerLook playerLook, SchematicBlockContext context) {
        if (playerLook == null) {
            return null;
        }
        Direction primaryLookDirection = DirectionUtils.orderedByNearest(playerLook.getYaw(), playerLook.getPitch())[0];
        if (primaryLookDirection.getAxis().isHorizontal()) {
            float currentPitch = context.client.player.getXRot();
            currentPitch = Math.max(-40.0F, Math.min(40.0F, currentPitch));
            this.actionBroker.setWaitForHorizontalLook(false);
            return new PlayerLook(playerLook.getYaw(), currentPitch);
        }
        return playerLook;
    }

    private static Item[] normalizeRequiredItems(@Nullable Item[] requiredItems) {
        return requiredItems == null || requiredItems.length == 0 ? EMPTY_HAND_ITEMS : requiredItems;
    }

    private static boolean shouldStopAfterTaskAction(@Nullable PrintTaskAction taskAction) {
        return taskAction != null && taskAction.stopIterationAfterAction();
    }

    private void showReserveNotice(SchematicBlockContext context, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        long currentTick = context.level.getGameTime();
        if (this.lastReserveNoticeTick != Long.MIN_VALUE
                && currentTick >= this.lastReserveNoticeTick
                && currentTick - this.lastReserveNoticeTick < RESERVE_NOTICE_COOLDOWN_TICKS) {
            return;
        }
        this.lastReserveNoticeTick = currentTick;
        MessageUtils.setOverlayMessage(I18n.RESERVE_ITEM_SKIP.getName(
                stack.getHoverName(),
                Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue()
        ));
    }
}
