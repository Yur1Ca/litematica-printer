package me.aleksilassila.litematica.printer.integration.inventory;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import java.util.EnumSet;

/** Reflection-backed Take It Out capability isolated from feature code. */
public final class TakeItOutAdapter implements InventoryProvider {
    private static final String LEASE_OWNER = "take_it_out";
    private final ActionPort actionBroker;
    private boolean resourcesAcquired;
    private long activeToken;
    private Item requestedItem;
    private int acquireRetries;

    public TakeItOutAdapter(ActionPort actionBroker) {
        this.actionBroker = actionBroker;
    }

    @Override
    public String id() {
        return "take_it_out";
    }

    @Override
    public MaterialReservation request(MaterialRequest request) {
        if (!this.acquireResources()) {
            return pending(request);
        }
        Item availableItem = findAvailableItem(request);
        if (availableItem != null) {
            this.releaseResources();
            return MaterialReservation.available(request, availableItem);
        }
        for (Item item : request.acceptedItems()) {
            if (TakeItOutUtils.tryRequestItem(item)) {
                this.activeToken = request.token();
                this.requestedItem = item;
                return pending(request);
            }
        }
        this.releaseResources();
        return unavailable(request);
    }

    @Override
    public MaterialReservation status(MaterialRequest request) {
        Item availableItem = findAvailableItem(request);
        if (availableItem != null) {
            this.releaseResources();
            return MaterialReservation.available(request, availableItem);
        }
        if (!this.resourcesAcquired) {
            if (this.acquireRetries++ >= 10) {
                this.acquireRetries = 0;
                this.releaseResources();
                return unavailable(request);
            }
            return this.request(request);
        }
        if (this.activeToken == request.token()
                && this.requestedItem != null
                && TakeItOutUtils.isAwaitingItem(this.requestedItem)) {
            return pending(request);
        }
        this.releaseResources();
        return unavailable(request);
    }

    @Override
    public void reset() {
        TakeItOutUtils.resetPending();
        this.activeToken = 0L;
        this.requestedItem = null;
        this.acquireRetries = 0;
        this.releaseResources();
    }

    private static MaterialReservation pending(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
    }

    private static MaterialReservation unavailable(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
    }

    private boolean acquireResources() {
        if (this.resourcesAcquired) {
            return true;
        }
        this.resourcesAcquired = this.actionBroker.tryAcquire(
                LEASE_OWNER,
                EnumSet.of(ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER),
                0L
        );
        return this.resourcesAcquired;
    }

    private void releaseResources() {
        if (!this.resourcesAcquired) {
            return;
        }
        this.actionBroker.releaseOwner(LEASE_OWNER);
        this.resourcesAcquired = false;
        this.activeToken = 0L;
        this.requestedItem = null;
        this.acquireRetries = 0;
    }

    private static Item findAvailableItem(MaterialRequest request) {
        for (Item item : request.acceptedItems()) {
            if (InventoryUtils.playerHasItemInInventory(Minecraft.getInstance().player, item)) {
                return item;
            }
        }
        return null;
    }
}
