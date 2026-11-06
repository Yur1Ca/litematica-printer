package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Client-thread request bookkeeping; deliberately independent of Minecraft and stock snapshots. */
final class VoidWarehousePlacementState<K> {
    private static final int REQUEST_TIMEOUT = 100;
    private static final int HELLO_INTERVAL = 100;
    private static final int MAX_PENDING = 64;
    private static final int MAX_PER_TICK = 32;

    record Pending<K>(K target, List<String> candidates, long sentAt) { }

    private final Map<Integer, Pending<K>> pending = new HashMap<>();
    private final Map<K, Integer> targets = new HashMap<>();
    private final Map<String, Long> emptyUntil = new HashMap<>();
    private final Map<K, Long> rejectedUntil = new HashMap<>();
    private final Map<K, Long> retryUntil = new HashMap<>();
    private int nextPlaceNonce = 1;
    private int nextHelloNonce = 1;
    private int helloNonce;
    private long lastHello = Long.MIN_VALUE;
    private long sendTick = Long.MIN_VALUE;
    private int sentThisTick;
    private long throttledUntil;
    private boolean enabled;
    private boolean negotiationComplete;
    private boolean materialAvailabilityChanged;

    boolean isEnabled() {
        return enabled;
    }

    /** Coalesce recoveries until the network can wake material-blocked printer work. */
    boolean consumeMaterialAvailabilityChange() {
        boolean changed = materialAvailabilityChanged;
        materialAvailabilityChanged = false;
        return changed;
    }

    /** Only the first negotiation of a session holds inventory fallback, up to its timeout. */
    boolean isNegotiating(long now) {
        tick(now);
        return !negotiationComplete;
    }

    int beginHello(long now) {
        tick(now);
        if (lastHello != Long.MIN_VALUE && now - lastHello < HELLO_INTERVAL) return 0;
        int nonce = nextHelloNonce;
        nextHelloNonce = increment(nonce);
        helloNonce = nonce;
        lastHello = now;
        return nonce;
    }

    void receiveHello(int nonce, boolean serverEnabled, long now) {
        tick(now);
        if (nonce <= 0 || nonce != helloNonce) return;
        helloNonce = 0;
        negotiationComplete = true;
        if (serverEnabled && !enabled) materialAvailabilityChanged = true;
        enabled = serverEnabled;
    }

    List<String> eligibleCandidates(List<String> candidates, long now) {
        if (candidates == null) return List.of();
        var eligible = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty() && now >= emptyUntil.getOrDefault(candidate, 0L)) {
                eligible.add(candidate);
            }
            if (eligible.size() >= 8) break;
        }
        return List.copyOf(eligible);
    }

    boolean canRequest(List<String> candidates, K target, long now) {
        tick(now);
        return enabled && target != null && !targets.containsKey(target) && pending.size() < MAX_PENDING
                && now >= throttledUntil && now >= rejectedUntil.getOrDefault(target, 0L)
                && (sendTick != now || sentThisTick < MAX_PER_TICK)
                && !eligibleCandidates(candidates, now).isEmpty();
    }

    int beginRequest(List<String> candidates, K target, long now) {
        if (!canRequest(candidates, target, now)) return 0;
        int nonce = nextPlaceNonce;
        nextPlaceNonce = increment(nonce);
        pending.put(nonce, new Pending<>(target, eligibleCandidates(candidates, now), now));
        targets.put(target, nonce);
        if (sendTick != now) {
            sendTick = now;
            sentThisTick = 0;
        }
        sentThisTick++;
        return nonce;
    }

    boolean isPending(K target) {
        return targets.containsKey(target);
    }

    int pendingCount() {
        return pending.size();
    }

    Pending<K> pendingRequest(int nonce) {
        return pending.get(nonce);
    }

    boolean isTargetAvailable(K target, long now) {
        return target != null && now >= retryUntil.getOrDefault(target, 0L);
    }

    void receiveResult(int nonce, int status, long now) {
        tick(now);
        Pending<K> request = remove(nonce);
        if (request == null) return;
        if (status == 1) { // RULE_OFF
            enabled = false;
            negotiationComplete = true;
            helloNonce = 0;
        } else if (status == 2) { // EMPTY: only these material candidates failed.
            for (String candidate : request.candidates()) emptyUntil.put(candidate, now + 100);
        } else if (status != 0) {
            long retryAt = now + (status == 6 ? 1 : 20);
            rejectedUntil.put(request.target(), retryAt);
            if (status == 4) throttledUntil = now + 5;
            else retryUntil.put(request.target(), retryAt);
        }
    }

    void cancel(int nonce) {
        remove(nonce);
    }

    void tick(long now) {
        pending.entrySet().removeIf(entry -> {
            Pending<K> request = entry.getValue();
            if (now - request.sentAt() < REQUEST_TIMEOUT) return false;
            targets.remove(request.target(), entry.getKey());
            return true;
        });
        boolean candidatesReleased = emptyUntil.values().removeIf(until -> now >= until);
        rejectedUntil.values().removeIf(until -> now >= until);
        boolean targetsReleased = retryUntil.values().removeIf(until -> now >= until);
        if (candidatesReleased || targetsReleased) materialAvailabilityChanged = true;
        if (helloNonce != 0 && now - lastHello >= REQUEST_TIMEOUT) {
            helloNonce = 0;
            // A missed refresh is not an explicit revocation. Initial sessions remain disabled;
            // confirmed sessions still get rule/permission checks on every server placement.
            negotiationComplete = true;
        }
    }

    void reset() {
        pending.clear();
        targets.clear();
        emptyUntil.clear();
        rejectedUntil.clear();
        retryUntil.clear();
        enabled = false;
        negotiationComplete = false;
        materialAvailabilityChanged = false;
        helloNonce = 0;
        lastHello = Long.MIN_VALUE;
        sendTick = Long.MIN_VALUE;
        sentThisTick = 0;
        throttledUntil = 0;
        // Keep nonce counters: an old connection's delayed answer must never match a new request.
    }

    private Pending<K> remove(int nonce) {
        Pending<K> request = pending.remove(nonce);
        if (request != null) targets.remove(request.target(), nonce);
        return request;
    }

    private static int increment(int nonce) {
        return nonce == Integer.MAX_VALUE ? 1 : nonce + 1;
    }
}
