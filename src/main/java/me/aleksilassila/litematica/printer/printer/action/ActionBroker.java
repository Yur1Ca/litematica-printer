package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.core.action.ActionCoordinator;
import me.aleksilassila.litematica.printer.core.action.ActionRequest;
import me.aleksilassila.litematica.printer.core.action.ActionTicket;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Feature-facing action middleware.
 *
 * <p>Feature handlers submit actions through this boundary. Minecraft and
 * third-party integration mixins may continue to observe ActionManager, but
 * feature code no longer needs to know which queue implementation owns the
 * action or how it is reset.</p>
 */
public final class ActionBroker implements RuntimeComponent, ActionPort {
    private static final long ACTION_LEASE_TIMEOUT_NANOS = 10_000_000_000L;

    private final PrinterRuntime runtime;
    private final ActionManager delegate;
    private final ActionCoordinator coordinator = new ActionCoordinator();
    private ActionTicket activeTicket;

    private static SendResult from(ActionManager.SendResult result) {
        return SendResult.valueOf(result.name());
    }

    public ActionBroker(PrinterRuntime runtime, ActionManager delegate) {
        this.runtime = runtime;
        this.delegate = delegate;
    }

    public boolean queueClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int clickRepeatCount,
            @Nullable Item[] expectedItems,
            @NotNull ActionPort.ActionSource source
    ) {
        if (this.activeTicket != null) {
            return false;
        }
        long now = System.nanoTime();
        ActionRequest request = new ActionRequest(
                source.name().toLowerCase(),
                this.runtime.epoch(),
                EnumSet.of(ResourceLease.LOOK, ResourceLease.MAIN_HAND, ResourceLease.INTERACTION),
                now + ACTION_LEASE_TIMEOUT_NANOS
        );
        Optional<ActionTicket> admitted = this.coordinator.tryAdmit(request, now);
        if (admitted.isEmpty()) {
            return false;
        }
        ActionTicket ticket = admitted.get();
        if (!this.delegate.queueClick(
                target,
                side,
                hitModifier,
                useShift,
                clickRepeatCount,
                expectedItems,
                ActionManager.ActionSource.valueOf(source.name())
        )) {
            this.coordinator.release(ticket);
            return false;
        }
        this.activeTicket = ticket;
        return true;
    }

    public void useProtocolHitModifier(@NotNull Vec3 hitModifier) {
        this.delegate.useProtocolHitModifier(hitModifier);
    }

    public boolean setQueueCompletionListener(@Nullable Consumer<SendResult> completionListener) {
        return this.delegate.setQueueCompletionListener(
                completionListener == null ? null : result -> completionListener.accept(from(result))
        );
    }

    public boolean setExpectedStackPredicate(@Nullable Predicate<ItemStack> expectedStackPredicate) {
        return this.delegate.setExpectedStackPredicate(expectedStackPredicate);
    }

    public SendResult sendQueue(@Nullable LocalPlayer player) {
        if (this.activeTicket != null) {
            if (!this.activeTicket.canSend(this.runtime.epoch(), System.nanoTime())) {
                this.delegate.cancelQueue();
                this.releaseActiveTicket();
                return SendResult.NO_QUEUED_ACTION;
            }
        }
        ActionManager.SendResult delegateResult = this.delegate.sendQueue(player);
        SendResult result = from(delegateResult);
        if (!result.isWaiting()) {
            this.releaseActiveTicket();
        }
        return result;
    }

    public void cancelQueue() {
        this.delegate.cancelQueue();
        this.releaseActiveTicket();
    }

    public boolean isWaitingForLook() {
        return this.delegate.isWaitingForLook();
    }

    @Nullable
    public PlayerLook getLook() {
        return this.delegate.getLook();
    }

    public void setLook(@Nullable PlayerLook look) {
        this.delegate.setLook(look);
    }

    public void setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.delegate.setWaitForHorizontalLook(waitForHorizontalLook);
    }

    public void setNeedWaitModifyLookFromAction(boolean actionRequiresWaitModifyLook) {
        this.delegate.setNeedWaitModifyLookFromAction(actionRequiresWaitModifyLook);
    }

    public void setShift(LocalPlayer player, boolean shift) {
        this.delegate.setShift(player, shift);
    }

    public boolean isPrinterInteractionActive() {
        return this.delegate.isPrinterInteractionActive();
    }

    public boolean isEasyPlaceProtocolActive() {
        return this.delegate.isEasyPlaceProtocolActive();
    }

    public boolean consumeManualAnvilScreenAllowance() {
        return this.delegate.consumeManualAnvilScreenAllowance();
    }

    public boolean consumeTaskAnvilScreenSuppression() {
        return this.delegate.consumeTaskAnvilScreenSuppression();
    }

    public void prioritizeManualAnvilScreen() {
        this.delegate.prioritizeManualAnvilScreen();
    }

    public void armPrintSignEdit(BlockPos blockPos) {
        this.delegate.armPrintSignEdit(blockPos);
    }

    public void confirmPrintSignEditSent(BlockPos blockPos) {
        this.delegate.confirmPrintSignEditSent(blockPos);
    }

    public void cancelPrintSignEdit(BlockPos blockPos) {
        this.delegate.cancelPrintSignEdit(blockPos);
    }

    public boolean consumePrintSignEdit(BlockPos blockPos) {
        return this.delegate.consumePrintSignEdit(blockPos);
    }

    public void resetRuntime(String reason) {
        this.delegate.resetRuntime();
        this.activeTicket = null;
        this.coordinator.reset();
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.resetRuntime(event.reason());
    }

    public boolean isResourceHeld(ResourceLease resource) {
        return this.coordinator.isHeld(resource);
    }

    public boolean tryAcquire(String owner, EnumSet<ResourceLease> resources, long timeoutNanos) {
        long now = System.nanoTime();
        ActionRequest request = new ActionRequest(
                owner,
                this.runtime.epoch(),
                resources,
                timeoutNanos <= 0L ? 0L : now + timeoutNanos
        );
        return this.coordinator.tryAdmit(request, now).isPresent();
    }

    public void releaseOwner(String owner) {
        this.coordinator.releaseOwner(owner);
    }

    public boolean isResourceHeldByOther(ResourceLease resource, String owner) {
        return this.coordinator.isHeldByOther(resource, owner);
    }

    private void releaseActiveTicket() {
        if (this.activeTicket != null) {
            this.coordinator.release(this.activeTicket);
            this.activeTicket = null;
        }
    }
}
