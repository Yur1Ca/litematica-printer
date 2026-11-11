package me.aleksilassila.litematica.printer.utils.minecraft;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.mc.BeaconBlockEntityAccessor;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.List;

public final class BeaconEffectSync {
    private BeaconEffectSync() {
    }

    @SuppressWarnings("FieldCanBeLocal")
    private static boolean originalCaptured;

    private static List<List<Holder<MobEffect>>> originalBeaconEffects;
    private static final List<List<Holder<MobEffect>>> UNLOCKED_BEACON_EFFECTS = List.of(
            List.of(MobEffects.SPEED, MobEffects.HASTE),
            List.of(MobEffects.REGENERATION, MobEffects.RESISTANCE),
            List.of(MobEffects.JUMP_BOOST, MobEffects.STRENGTH),
            List.of()
    );

    public static void syncFromConfig() {
        if (!originalCaptured) {
            originalBeaconEffects = BeaconBlockEntityAccessor.litematica_printer$getBeaconEffects();
            originalCaptured = true;
        }

        if (Configs.Special.UNLOCK_BEACON_EFFECTS.getBooleanValue()) {
            if (BeaconBlockEntityAccessor.litematica_printer$getBeaconEffects() != UNLOCKED_BEACON_EFFECTS) {
                BeaconBlockEntityAccessor.litematica_printer$setBeaconEffects(UNLOCKED_BEACON_EFFECTS);
            }
            return;
        }

        if (BeaconBlockEntityAccessor.litematica_printer$getBeaconEffects() != originalBeaconEffects) {
            BeaconBlockEntityAccessor.litematica_printer$setBeaconEffects(originalBeaconEffects);
        }
    }
}
