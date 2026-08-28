package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

/** Pure limits and status classification used by {@link BedrockController}. */
final class BedrockSchedulingPolicy {
    private BedrockSchedulingPolicy() {
    }

    static int verticalActiveCap(int throughput) {
        return Math.max(1, throughput);
    }

    static int submitCap(int throughput, int cleanupPressure) {
        int base = Math.max(1, throughput);
        int pressureSteps = Math.max(0, cleanupPressure) / lowCleanupPressureThreshold(throughput);
        return Math.max(1, base - pressureSteps);
    }

    static int lowCleanupPressureThreshold(int throughput) {
        return Math.max(8, Math.max(1, throughput));
    }

    static int mediumCleanupPressureThreshold(int throughput) {
        return Math.max(14, Math.max(1, throughput) + 6);
    }

    static int highCleanupPressureThreshold(int throughput) {
        return Math.max(20, Math.max(1, throughput) * 2);
    }

    static int cleanupLimit(int throughput, int blockedCleanupDemand, int baseLimit, int bonusLimit) {
        int base = Math.max(baseLimit, Math.max(1, throughput) * 5);
        return base + Math.min(bonusLimit, Math.max(0, blockedCleanupDemand));
    }

    static boolean countsTowardsActiveCap(BedrockTarget.Status status) {
        // A waiting/retracting target still owns its piston, head and power footprint until the
        // server confirms the outcome. Excluding these states lets admission reuse the same
        // coordinates while packets are in flight, which corrupts the next machine.
        return status != null
                && status != BedrockTarget.Status.FAILED
                && status != BedrockTarget.Status.STUCK
                && status != BedrockTarget.Status.RETRACTED;
    }

    static boolean isFastLane(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.EXTENDED
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }
}
