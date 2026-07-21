package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
=======
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
=======
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

<<<<<<< HEAD
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

=======
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
public final class PrintPlacementExecutor {
    private static final Item[] EMPTY_HAND_ITEMS = {Items.AIR};

    public PrintPlacementResult execute(SchematicBlockContext context, Action action, @Nullable PrintTaskAction taskAction) {
        BlockPos blockPos = context.blockPos;
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && context.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();
            if (FallingBlock.isFree(context.level.getBlockState(downPos))) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "下落方块无支撑");
                MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(context.requiredBlockName().getString()));
                return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
            }
        }

        Direction side = action.getValidSide(context.level, blockPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "无有效放置面");
            return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
        }

        Item[] requiredItems = normalizeRequiredItems(action.getRequiredItems(context.requiredState.getBlock()));
<<<<<<< HEAD
        Predicate<ItemStack> requiredStackPredicate = action.getRequiredStackPredicate();
        boolean itemReady = requiredStackPredicate == null
                ? InventoryUtils.switchToItems(context.client.player, requiredItems)
                : InventoryUtils.switchToMatchingStack(
                        context.client.player,
                        requiredStackPredicate,
                        action.getRequiredCreativeStack()
                );
        if (!itemReady) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
            // 缺少材料属于无效放置，不应消耗每 tick 的有效放置预算（与重构前行为一致）。
=======
        int reserveCount = Configs.Placement.PLACE_RESERVE_COUNT.getIntegerValue();
        if (!InventoryUtils.hasEnoughItemsForReserve(context.client.player, requiredItems, reserveCount)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
            if (reserveCount > 0) {
                String cooldownKey = "reserve_item_skip_" + context.requiredBlockName().getString();
                InventoryUtils.setOverlayMessageWithCooldown(
                    I18n.RESERVE_ITEM_SKIP.getName(reserveCount, context.requiredBlockName().getString()),
                    cooldownKey
                );
            }
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
            return PrintPlacementResult.failure(false,
                    shouldStopAfterTaskAction(taskAction)
                            || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                            || TakeItOutUtils.isAwaitingStack());
        }
<<<<<<< HEAD
        if (!InventoryUtils.isHoldingAnyItem(context.client.player, requiredItems)
                || requiredStackPredicate != null
                && !requiredStackPredicate.test(context.client.player.getMainHandItem())) {
=======
        if (!InventoryUtils.switchToItemsWithReserve(context.client.player, requiredItems, reserveCount)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
            return PrintPlacementResult.failure(false,
                    shouldStopAfterTaskAction(taskAction)
                            || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                            || TakeItOutUtils.isAwaitingStack());
        }
        if (!InventoryUtils.isHoldingAnyItem(context.client.player, requiredItems)) {
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待物品同步");
            return PrintPlacementResult.failure(false, true);
        }

        boolean useShift = getUseShift(context, action, side);
<<<<<<< HEAD
        if (!action.queueAction(blockPos, side, useShift, context.client.player, requiredItems)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "动作队列占用");
            return PrintPlacementResult.cancelled(true);
        }
        ActionManager.INSTANCE.setExpectedStackPredicate(requiredStackPredicate);
=======
        action.queueAction(blockPos, side, useShift, context.client.player, requiredItems);
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, context.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.useProtocolHitModifier(hitModifier);
        }
        ActionManager.INSTANCE.setLook(adjustHorizontalLook(action.getPlayerLook(), context));
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.isNeedWaitModifyLook());
<<<<<<< HEAD
        boolean consumedEffectiveExecution = action.isConsumeEffectiveExecution();
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        AtomicBoolean deferred = new AtomicBoolean(false);
        ActionManager.INSTANCE.setQueueCompletionListener(sendResult -> {
            if (!deferred.get()) {
                return;
            }
            if (sendResult.isSent()) {
                recordPlacementSent(context);
                if (cooldownTicks > 0) {
                    CooldownUtils.INSTANCE.setCooldown(
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
                HudStatsManager.INSTANCE.recordDeferred(
                        HudStatsManager.Mode.PRINT,
                        describeSendFailure(sendResult)
                );
                if (taskAction != null) {
                    taskAction.onCancelled(context, action);
                }
            }
        });

        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(context.client.player);
        if (sendResult.isWaiting()) {
            deferred.set(true);
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待转头");
            return new PrintPlacementResult(
                    consumedEffectiveExecution,
                    true,
                    PrintPlacementResult.TaskEvent.QUEUED,
                    -1
            );
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
            return PrintPlacementResult.cancelled(true);
        }

        recordPlacementSent(context);

        return new PrintPlacementResult(
                consumedEffectiveExecution,
                shouldStopAfterTaskAction(taskAction),
                PrintPlacementResult.TaskEvent.SUCCESS,
                cooldownTicks
        );
    }

    private static void recordPlacementSent(SchematicBlockContext context) {
        HudStatsManager.INSTANCE.trackExpectedBlockState(
                HudStatsManager.Mode.PRINT,
                context.blockPos,
                context.requiredState
        );
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
    }

    private static String describeSendFailure(ActionManager.SendResult result) {
        return switch (result) {
            case STALE_POSITION -> "移动后动作失效";
            case HELD_ITEM_CHANGED -> "手持物品已变化";
            case NO_PLAYER, NO_GAME_MODE -> "客户端状态未就绪";
            case INTERACTION_REJECTED -> "交互被拒绝";
            case NO_QUEUED_ACTION -> "动作未入队";
            default -> "动作未发送";
        };
=======
        HudStatsManager.INSTANCE.trackExpectedBlockState(HudStatsManager.Mode.PRINT, blockPos, context.requiredState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);

        boolean consumedEffectiveExecution = action.isConsumeEffectiveExecution();
        boolean needWaitModifyLook = ActionManager.INSTANCE.sendQueue(context.client.player).needWaitModifyLook;
        PrintPlacementResult.TaskEvent taskEvent = needWaitModifyLook
                ? PrintPlacementResult.TaskEvent.QUEUED
                : PrintPlacementResult.TaskEvent.SUCCESS;

        if (needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待转头");
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
        }

        boolean skipIteration = needWaitModifyLook
                || shouldStopAfterTaskAction(taskAction);
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        return new PrintPlacementResult(consumedEffectiveExecution, skipIteration, taskEvent, cooldownTicks);
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
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
    private static PlayerLook adjustHorizontalLook(@Nullable PlayerLook playerLook, SchematicBlockContext context) {
        if (playerLook == null) {
            return null;
        }
        Direction primaryLookDirection = DirectionUtils.orderedByNearest(playerLook.getYaw(), playerLook.getPitch())[0];
        if (primaryLookDirection.getAxis().isHorizontal()) {
            float currentPitch = context.client.player.getXRot();
            currentPitch = Math.max(-40.0F, Math.min(40.0F, currentPitch));
            ActionManager.INSTANCE.setWaitForHorizontalLook(false);
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
}
