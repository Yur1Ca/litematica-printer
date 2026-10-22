package me.aleksilassila.litematica.printer.config;

import java.util.List;
import me.aleksilassila.litematica.printer.feature.SharedPlacementSettings;

/** Retains the existing config keys and their live-read semantics for all placement features. */
public final class ConfiguredPlacementSettings implements SharedPlacementSettings {
    @Override public boolean placeInAir() { return Configs.Print.PLACE_IN_AIR.getBooleanValue(); }
    @Override public List<String> replaceableList() { return Configs.Print.REPLACEABLE_LIST.getStrings(); }
}
