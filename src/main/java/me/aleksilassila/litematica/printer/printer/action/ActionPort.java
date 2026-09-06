package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Feature-facing action capability, independent of the queue implementation. */
public interface ActionPort {
    enum ActionSource { GENERIC, PRINT, FILL, COVER, FLUID }

    enum SendResult {
        SENT,
        WAITING_FOR_LOOK,
        NO_QUEUED_ACTION,
        NO_PLAYER,
        STALE_POSITION,
        HELD_ITEM_CHANGED,
        RESERVE_LIMIT,
        NO_GAME_MODE,
        INTERACTION_REJECTED;

        public boolean isSent() { return this == SENT; }

        public boolean isWaiting() { return this == WAITING_FOR_LOOK; }
    }

    boolean queueClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int clickRepeatCount,
            @Nullable Item[] expectedItems,
            @NotNull ActionSource source
    );

    void useProtocolHitModifier(@NotNull Vec3 hitModifier);

    boolean setQueueCompletionListener(@Nullable Consumer<SendResult> completionListener);

    boolean setExpectedStackPredicate(@Nullable Predicate<ItemStack> expectedStackPredicate);

    SendResult sendQueue(@Nullable LocalPlayer player);

    void cancelQueue();

    boolean isWaitingForLook();

    @Nullable PlayerLook getLook();

    void setLook(@Nullable PlayerLook look);

    void setWaitForHorizontalLook(boolean waitForHorizontalLook);

    void setNeedWaitModifyLookFromAction(boolean actionRequiresWaitModifyLook);

    void setShift(LocalPlayer player, boolean shift);

    boolean isPrinterInteractionActive();

    boolean isEasyPlaceProtocolActive();

    boolean consumeManualAnvilScreenAllowance();

    boolean consumeTaskAnvilScreenSuppression();

    void prioritizeManualAnvilScreen();

    void armPrintSignEdit(BlockPos blockPos);

    void confirmPrintSignEditSent(BlockPos blockPos);

    void cancelPrintSignEdit(BlockPos blockPos);

    boolean consumePrintSignEdit(BlockPos blockPos);

    boolean isResourceHeld(ResourceLease resource);

    boolean tryAcquire(String owner, EnumSet<ResourceLease> resources, long timeoutNanos);

    void releaseOwner(String owner);

    boolean isResourceHeldByOther(ResourceLease resource, String owner);
}
