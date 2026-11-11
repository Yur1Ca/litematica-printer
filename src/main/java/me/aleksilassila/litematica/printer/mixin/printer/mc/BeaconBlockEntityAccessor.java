package me.aleksilassila.litematica.printer.mixin.printer.mc;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
    @Accessor("BEACON_EFFECTS")
    static List<List<Holder<MobEffect>>> litematica_printer$getBeaconEffects() {
        throw new AssertionError();
    }

    @Mutable
    @Accessor("BEACON_EFFECTS")
    static void litematica_printer$setBeaconEffects(
            List<List<Holder<MobEffect>>> beaconEffects
    ) {
        throw new AssertionError();
    }
}
