package me.aleksilassila.litematica.printer.core.action;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** Pure resource arbitration for feature actions. */
public final class ActionCoordinator {
    private final Map<ResourceLease, ActionTicket> leases = new EnumMap<>(ResourceLease.class);
    private long nextTicketId;

    public synchronized Optional<ActionTicket> tryAdmit(ActionRequest request, long nowNanos) {
        this.expire(nowNanos);
        for (ResourceLease resource : request.resources()) {
            ActionTicket holder = this.leases.get(resource);
            if (holder != null && !holder.request().owner().equals(request.owner())) {
                return Optional.empty();
            }
        }

        ActionTicket ticket = new ActionTicket(++this.nextTicketId, request);
        for (ResourceLease resource : request.resources()) {
            this.leases.put(resource, ticket);
        }
        return Optional.of(ticket);
    }

    public synchronized void release(ActionTicket ticket) {
        this.leases.entrySet().removeIf(entry -> entry.getValue().id() == ticket.id());
    }

    public synchronized void releaseOwner(String owner) {
        this.leases.entrySet().removeIf(entry -> entry.getValue().request().owner().equals(owner));
    }

    public synchronized void reset() {
        this.leases.clear();
    }

    public synchronized boolean isHeld(ResourceLease resource) {
        return this.leases.containsKey(resource);
    }

    public synchronized boolean isHeldByOther(ResourceLease resource, String owner) {
        ActionTicket ticket = this.leases.get(resource);
        return ticket != null && !ticket.request().owner().equals(owner);
    }

    public synchronized int activeLeaseCount() {
        return this.leases.size();
    }

    private void expire(long nowNanos) {
        Iterator<Map.Entry<ResourceLease, ActionTicket>> iterator = this.leases.entrySet().iterator();
        while (iterator.hasNext()) {
            ActionTicket ticket = iterator.next().getValue();
            long deadline = ticket.request().deadlineNanos();
            if (deadline > 0L && nowNanos >= deadline) {
                iterator.remove();
            }
        }
    }
}
