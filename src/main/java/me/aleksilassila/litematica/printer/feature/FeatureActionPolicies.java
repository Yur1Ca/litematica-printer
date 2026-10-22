package me.aleksilassila.litematica.printer.feature;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager.ActionSource;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;

/** Composition boundary: feature configuration never needs to live in the click executor. */
public final class FeatureActionPolicies {
    private static final PlacementPolicy PRINT = new PlacementPolicy(
            () -> Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue()
                    ? Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue() : -1, true, true);
    private static final PlacementPolicy BUILD = new PlacementPolicy(() -> -1, true, false);
    private static final PlacementPolicy DEFAULT = new PlacementPolicy(() -> -1, false, false);

    private FeatureActionPolicies() {}

    public static PlacementPolicy forSource(ActionSource source) {
        return switch (source) {
            case PRINT -> PRINT;
            case FILL, COVER, MINE -> BUILD;
            default -> DEFAULT;
        };
    }
}
