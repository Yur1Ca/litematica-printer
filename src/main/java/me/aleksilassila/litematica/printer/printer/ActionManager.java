package me.aleksilassila.litematica.printer.printer;

import java.util.function.Function;
import me.aleksilassila.litematica.printer.feature.FeatureActionPolicies;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehousePlacementPolicy;
//#if MC >= 12111
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehousePlacementNetwork;
//#endif
//#if MC > 12100
import fi.dy.masa.litematica.util.EasyPlaceUtils;
//#endif
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.VoidWarehouseTriggerItem;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

//#if MC > 12105
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
//#else
//$$ import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
//#endif

@SuppressWarnings("SpellCheckingInspection")
public class ActionManager {
    private static final float LOOK_SETTLED_EPSILON_DEGREES = 1.0F;
    private static final double STALE_WAIT_MOVE_DISTANCE_SQR = 0.75D * 0.75D;
    private QueuedClick queuedClick;
    private final InteractionScreenSessions screenSessions = new InteractionScreenSessions();
    @Nullable
    private PlayerLook look;
    private boolean needWaitModifyLook = false;
    private boolean waitForHorizontalLook = true;
    private boolean actionRequiresWaitModifyLook = false;
    private long lastQueuedLookTick = Long.MIN_VALUE;
    private float lastQueuedLookYaw;
    private float lastQueuedLookPitch;
    private boolean printerInteractionActive;
    private boolean easyPlaceProtocolActive;
    private boolean activeEasyPlacePolicy;
    private final Function<ActionSource, PlacementPolicy> policies;

    public enum ActionSource {
        GENERIC,
        PRINT,
        FILL,
        COVER,
        FLUID,
        MINE
    }

    public enum SendResult {
        /** The interaction was accepted locally or its use packet was submitted; server confirmation is separate. */
        SENT,
        WAITING_FOR_LOOK,
        NO_QUEUED_ACTION,
        NO_PLAYER,
        STALE_POSITION,
        HELD_ITEM_CHANGED,
        RESERVE_LIMIT,
        NO_GAME_MODE,
        INTERACTION_REJECTED;

        public boolean isSent() {
            return this == SENT;
        }

        public boolean isWaiting() {
            return this == WAITING_FOR_LOOK;
        }
    }

    public ActionManager() {
        this(FeatureActionPolicies::forSource);
    }

    public ActionManager(Function<ActionSource, PlacementPolicy> policies) {
        this.policies = policies;
    }

    public boolean isWaitingForLook() {
        return this.needWaitModifyLook;
    }

    @Nullable
    public PlayerLook getLook() {
        return this.look;
    }

    public void setLook(@Nullable PlayerLook look) {
        this.look = look;
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift) {
        return this.queueClick(target, side, hitModifier, useShift, 1);
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int clickRepeatCount) {
        return this.queueClick(target, side, hitModifier, useShift, clickRepeatCount, null);
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int clickRepeatCount, @Nullable Item[] expectedItems) {
        return this.queueClick(target, side, hitModifier, useShift, clickRepeatCount, expectedItems, ActionSource.GENERIC);
    }

    public boolean queueClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int clickRepeatCount,
            @Nullable Item[] expectedItems,
            @NotNull ActionSource source
    ) {
        if (this.queuedClick != null) {
            return false;
        }
        this.queuedClick = new QueuedClick(target, side, hitModifier, useShift, clickRepeatCount, this.policies.apply(source));
        this.queuedClick.expectItems(expectedItems);
        return true;
    }

    public void useProtocolHitModifier(@NotNull Vec3 hitModifier) {
        if (this.queuedClick != null) {
            this.queuedClick.useProtocolHit(hitModifier);
        }
    }

    public boolean setQueueCompletionListener(@Nullable Consumer<SendResult> completionListener) {
        if (this.queuedClick == null) {
            return false;
        }
        this.queuedClick.onCompletion(completionListener);
        return true;
    }

    public boolean setExpectedStackPredicate(@Nullable Predicate<ItemStack> expectedStackPredicate) {
        if (this.queuedClick == null) {
            return false;
        }
        this.queuedClick.expectStack(expectedStackPredicate);
        return true;
    }

    public boolean setWarehousePlacement(BlockPos target, int expectedStateId) {
        if (this.queuedClick != null && this.queuedClick.useProtocol) return false;
        return this.markWarehousePlacement(target, expectedStateId, null);
    }

    public boolean setWarehouseStatePlacement(BlockPos target, int expectedStateId, BlockState desiredState) {
        return desiredState != null && this.markWarehousePlacement(target, expectedStateId, desiredState);
    }

    private boolean markWarehousePlacement(BlockPos target, int expectedStateId, @Nullable BlockState desiredState) {
        QueuedClick click = this.queuedClick;
        if (click == null || click.repeatCount != 1 || expectedStateId < 0
                || click.expectedStackPredicate != null
                || !VoidWarehousePlacementPolicy.supportsItems(click.expectedItems)) return false;
        click.warehouseTarget = target.immutable();
        click.warehouseExpectedStateId = expectedStateId;
        click.warehouseDesiredState = desiredState;
        return true;
    }

    public SendResult sendQueue(@Nullable LocalPlayer player) {
        QueuedClick click = this.queuedClick;
        if (click == null) {
            return SendResult.NO_QUEUED_ACTION;
        }
        if (player == null) {
            return this.finish(click, SendResult.NO_PLAYER);
        }
        if (shouldDropStaleQueuedClick(player, click)) {
            return this.finish(click, SendResult.STALE_POSITION);
        }
        if (!needWaitModifyLook && look != null && shouldSendQueuedLook(look)) {
            NetworkUtils.sendLookPacket(player, look);
            this.recordQueuedLook(look);
        }
        if (shouldWaitForServerLook(player, click)) {
            needWaitModifyLook = true;
            return SendResult.WAITING_FOR_LOOK;
        }
        if (needWaitModifyLook) {
            needWaitModifyLook = false;
        }
        if (click.warehouseTarget == null && !isHoldingExpectedItem(player, click)) {
            return this.finish(click, SendResult.HELD_ITEM_CHANGED);
        }
        if (click.warehouseTarget != null && (click.useProtocol && click.warehouseDesiredState == null
                || click.expectedStackPredicate != null
                || !VoidWarehousePlacementPolicy.supportsItems(click.expectedItems))) {
            return this.finish(click, SendResult.INTERACTION_REJECTED);
        }
        int reserveAllowance = click.warehouseTarget == null
                ? getReserveAllowance(player, click) : Integer.MAX_VALUE;
        if (reserveAllowance <= 0) {
            return this.finish(click, SendResult.RESERVE_LIMIT);
        }
        Direction direction;
        if (look == null) {
            direction = click.side;
        } else {
            direction = DirectionUtils.getHorizontalDirection(look.yaw);
        }
        Vec3 targetCenter = Vec3.atCenterOf(click.target);
        Vec3 sideOffset = Vec3.atLowerCornerOf(DirectionUtils.getVector(click.side)).scale(0.5);
        Vec3 rotatedHitModifier = click.physicalHitModifier.yRot((direction.toYRot() + 90) % 360).scale(0.5);
        Vec3 physicalHitVec = targetCenter.add(sideOffset).add(rotatedHitModifier);
        Vec3 hitVec = click.useProtocol ? click.hitModifier : physicalHitVec;
        if (click.warehouseTarget == null) QuickShulkerBridge.onMainHandUse(player);
        boolean wasSneak = player.isShiftKeyDown();
        if (click.useShift && !wasSneak) {
            setShift(player, true);
        } else if (!click.useShift && wasSneak) {
            setShift(player, false);
        }
        if (click.warehouseTarget != null) {
            boolean sent;
            try {
                // Preserve queue look/sneak ordering, but never predict an inventory-backed use.
                BlockHitResult physicalHit = new BlockHitResult(physicalHitVec, click.side, click.target, false);
                sent = click.warehouseDesiredState == null
                        ? this.sendWarehousePlacement(click.expectedItems, click.warehouseTarget,
                                physicalHit, click.warehouseExpectedStateId)
                        : this.sendWarehouseStatePlacement(click.expectedItems, click.warehouseTarget,
                                physicalHit, click.warehouseExpectedStateId, click.warehouseDesiredState);
            } finally {
                restoreShift(player, click, wasSneak);
            }
            return this.finish(click, sent ? SendResult.SENT : SendResult.INTERACTION_REJECTED);
        }
        if (!(Reference.MINECRAFT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension)) {
            restoreShift(player, click, wasSneak);
            return this.finish(click, SendResult.NO_GAME_MODE);
        }

        boolean dispatched = false;
        this.printerInteractionActive = true;
        this.easyPlaceProtocolActive = click.useProtocol;
        this.activeEasyPlacePolicy = click.policy.easyPlaceProtocol();
        boolean litematicaEasyPlaceWasHandling = false;
        //#if MC > 12100
        litematicaEasyPlaceWasHandling = EasyPlaceUtils.isHandling();
        if (!litematicaEasyPlaceWasHandling) {
            EasyPlaceUtils.setHandling(true);
        }
        //#endif
        try {
            BlockHitResult blockHitResult = new BlockHitResult(hitVec, click.side, click.target, false);
            for (int i = 0; i < click.repeatCount; i++) {
                if (reserveAllowance <= 0) {
                    break;
                }
                me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.placement(
                        player, click.target, click.side, click.expectedItems);
                boolean interactionAccepted;
                boolean interactionDispatched;
                try (var send = NetworkUtils.trackUseItemOn(
                        player.connection.getConnection(), InteractionHand.MAIN_HAND, blockHitResult)) {
                    interactionAccepted = gameModeExtension.litematica_printer$useItemOn(
                            true, InteractionHand.MAIN_HAND, blockHitResult) != net.minecraft.world.InteractionResult.FAIL;
                    // Vanilla may send USE even when its local prediction returns FAIL.
                    interactionDispatched = interactionAccepted || send.wasSent();
                }
                dispatched |= interactionDispatched;
                if (interactionAccepted) {
                    this.armTaskAnvilScreenSuppression(click);
                }
                if (interactionDispatched && reserveAllowance != Integer.MAX_VALUE) {
                    reserveAllowance--;
                }
            }
        } finally {
            //#if MC > 12100
            if (!litematicaEasyPlaceWasHandling) {
                EasyPlaceUtils.setHandling(false);
            }
            //#endif
            this.printerInteractionActive = false;
            this.easyPlaceProtocolActive = false;
            this.activeEasyPlacePolicy = false;
            restoreShift(player, click, wasSneak);
        }
        return this.finish(click, dispatched ? SendResult.SENT : SendResult.INTERACTION_REJECTED);
    }

    protected boolean sendWarehousePlacement(Item[] candidates, BlockPos target, BlockHitResult hit, int expectedStateId) {
        //#if MC >= 12111
        return VoidWarehousePlacementNetwork.requestPlace(candidates, target, hit, expectedStateId);
        //#else
        //$$ return false;
        //#endif
    }

    protected boolean sendWarehouseStatePlacement(Item[] candidates, BlockPos target,
            BlockHitResult physicalHit, int expectedStateId, BlockState desiredState) {
        //#if MC >= 12111
        return VoidWarehousePlacementNetwork.requestStatePlace(
                candidates, target, physicalHit, expectedStateId, desiredState);
        //#else
        //$$ return false;
        //#endif
    }

    private void armTaskAnvilScreenSuppression(QueuedClick click) {
        if (!this.screenSessions.hasManualAnvilScreenAllowance(System.nanoTime())
                && click.policy.suppressAnvilScreen()
                && Reference.MINECRAFT.level != null
                && Reference.MINECRAFT.level.getBlockState(click.target).getBlock() instanceof AnvilBlock) {
            this.screenSessions.armTaskAnvilScreen(System.nanoTime());
        }
    }

    public boolean consumeTaskAnvilScreenSuppression() {
        return this.screenSessions.consumeTaskAnvilScreenSuppression(System.nanoTime());
    }

    public void prioritizeManualAnvilScreen() {
        this.screenSessions.prioritizeManualAnvilScreen(System.nanoTime());
    }

    public boolean consumeManualAnvilScreenAllowance() {
        return this.screenSessions.consumeManualAnvilScreenAllowance(System.nanoTime());
    }

    private static int getReserveAllowance(LocalPlayer player, QueuedClick click) {
        int reserveCount = click.policy.reserveCount().getAsInt();
        if (reserveCount < 0) return Integer.MAX_VALUE;
        return InventoryUtils.getConsumableSurplus(
                player, player.getMainHandItem(), click.expectedStackPredicate, reserveCount);
    }

    private void restoreShift(LocalPlayer player, QueuedClick click, boolean wasSneak) {
        if (click.useShift && !wasSneak) {
            setShift(player, false);
        } else if (!click.useShift && wasSneak) {
            setShift(player, true);
        }
    }

    private SendResult finish(QueuedClick click, SendResult result) {
        Consumer<SendResult> completionListener = click.completionListener;
        this.clearQueue();
        if (completionListener != null) {
            completionListener.accept(result);
        }
        return result;
    }

    /**
     * Cancel a queued action and complete its task callback. Inventory/container workflows use
     * this instead of silently dropping an action that may already have entered a queued state.
     */
    public void cancelQueue() {
        QueuedClick click = this.queuedClick;
        if (click == null) {
            this.clearQueue();
            return;
        }
        Consumer<SendResult> completionListener = click.completionListener;
        this.clearQueue();
        if (completionListener != null) {
            completionListener.accept(SendResult.NO_QUEUED_ACTION);
        }
    }

    public boolean isPrinterInteractionActive() {
        return this.printerInteractionActive;
    }

    public boolean isEasyPlaceProtocolActive() {
        return this.printerInteractionActive
                && this.activeEasyPlacePolicy
                && this.easyPlaceProtocolActive;
    }

    public void armPrintSignEdit(BlockPos blockPos) {
        long now = System.nanoTime();
        this.screenSessions.armPrintSignEdit(blockPos.asLong(), now);
    }

    public void confirmPrintSignEditSent(BlockPos blockPos) {
        this.screenSessions.confirmPrintSignEditSent(blockPos.asLong(), System.nanoTime());
    }

    public void cancelPrintSignEdit(BlockPos blockPos) {
        this.screenSessions.cancelPrintSignEdit(blockPos.asLong());
    }

    public boolean consumePrintSignEdit(BlockPos blockPos) {
        return this.screenSessions.consumePrintSignEdit(blockPos.asLong(), System.nanoTime());
    }

    public void setShift(LocalPlayer player, boolean shift) {
        //#if MC > 12105
        Input input = new Input(player.input.keyPresses.forward(), player.input.keyPresses.backward(), player.input.keyPresses.left(), player.input.keyPresses.right(), player.input.keyPresses.jump(), shift, player.input.keyPresses.sprint());
        ServerboundPlayerInputPacket packet = new ServerboundPlayerInputPacket(input);
        //#else
        //$$ ServerboundPlayerCommandPacket packet = new ServerboundPlayerCommandPacket(player, shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY);
        //#endif
        player.setShiftKeyDown(shift);
        NetworkUtils.sendPacket(packet);
    }

    public void setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.waitForHorizontalLook = waitForHorizontalLook;
    }

    public void setNeedWaitModifyLookFromAction(boolean actionRequiresWaitModifyLook) {
        this.actionRequiresWaitModifyLook = actionRequiresWaitModifyLook;
    }

    private boolean shouldWaitForServerLook(LocalPlayer player, QueuedClick click) {
        if ((!this.waitForHorizontalLook && !this.actionRequiresWaitModifyLook)
                || click.useProtocol
                || this.needWaitModifyLook
                || this.look == null) {
            return false;
        }
        Direction lookDirection = DirectionUtils.orderedByNearest(this.look.yaw, this.look.pitch)[0];
        return lookDirection.getAxis().isHorizontal()
                && !isPlayerLookSettled(player, this.look);
    }

    private boolean shouldDropStaleQueuedClick(LocalPlayer player, QueuedClick click) {
        if (!this.needWaitModifyLook || click.queuedPlayerPosition == null) {
            return false;
        }
        long currentTick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        if (currentTick == Long.MIN_VALUE || currentTick <= click.queuedTick) {
            return false;
        }
        return player.position().distanceToSqr(click.queuedPlayerPosition) > STALE_WAIT_MOVE_DISTANCE_SQR;
    }

    private static boolean isHoldingExpectedItem(LocalPlayer player, QueuedClick click) {
        ItemStack mainHand = player.getMainHandItem();
        // 🔴 最后一米的硬闸：改名成「虚空仓库」的工作台，右键会被服务端接管成「打开仓库界面」，
        // 方块永远放不下。不管这条点击是谁排的队，一律不许用它右键 —— 否则就是
        // 「右键 → 弹界面 → 方块没放下 → 下一 tick 再右键」的死循环（玩家按 Esc 也退不掉）。
        // 这里返回 false 只会让这一击被丢弃（HELD_ITEM_CHANGED），下一次放置会走材料选择，
        // 那时它已经被判成「不是建材」，于是转去补货要一个干净的工作台。
        if (VoidWarehouseTriggerItem.isTrigger(mainHand)) {
            return false;
        }
        if (click.expectedStackPredicate != null
                && !click.expectedStackPredicate.test(mainHand)) {
            return false;
        }
        if (click.expectedItems == null || click.expectedItems.length == 0) {
            return true;
        }
        Item heldItem = mainHand.getItem();
        for (Item expectedItem : click.expectedItems) {
            if (heldItem.equals(expectedItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayerLookSettled(LocalPlayer player, PlayerLook look) {
        return Math.abs(Mth.wrapDegrees(player.getYRot() - look.yaw)) <= LOOK_SETTLED_EPSILON_DEGREES
                && Math.abs(player.getXRot() - look.pitch) <= LOOK_SETTLED_EPSILON_DEGREES;
    }

    private boolean shouldSendQueuedLook(PlayerLook look) {
        long tick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        if (tick == Long.MIN_VALUE || this.lastQueuedLookTick != tick) {
            return true;
        }
        return Math.abs(Mth.wrapDegrees(this.lastQueuedLookYaw - look.yaw)) > LOOK_SETTLED_EPSILON_DEGREES
                || Math.abs(this.lastQueuedLookPitch - look.pitch) > LOOK_SETTLED_EPSILON_DEGREES;
    }

    private void recordQueuedLook(PlayerLook look) {
        this.lastQueuedLookTick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        this.lastQueuedLookYaw = look.yaw;
        this.lastQueuedLookPitch = look.pitch;
    }

    public void clearQueue() {
        this.queuedClick = null;
        this.needWaitModifyLook = false;
        this.waitForHorizontalLook = true;
        this.actionRequiresWaitModifyLook = false;
        this.look = null;
        this.printerInteractionActive = false;
        this.easyPlaceProtocolActive = false;
        this.activeEasyPlacePolicy = false;
    }

    public void resetRuntime() {
        this.clearQueue();
        this.screenSessions.reset();
    }
}
