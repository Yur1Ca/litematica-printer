package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("removal")
class ThirdReviewInteractionShapeTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @EnumSource(value = RadiusShapeType.class, names = {"CUBE", "OCTAHEDRON"})
    void blockCenteredShapesIncludeTheWholeFeetBasedBoundingRange(RadiusShapeType shape) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(-0.25, 64.25, -0.75, 1.5F);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            fixture.tracker.update(fixture.player);

            PrinterBox box = fixture.box();
            assertBounds(box, -6, 59, -6, 4, 69, 4);
            assertTrue(box.contains(new BlockPos(-1, 59, -1)),
                    "The axis target five blocks below the feet belongs to both cube and octahedron work ranges");
        }
    }

    @Test void sphereKeepsThePreciseEyeCenteredBoundingRange() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(-0.25, 64.25, -0.75, 1.5F);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -6, 60, -6, 5, 71, 5);
        }
    }

    @Test void changingShapeWhileStandingStillImmediatelyChangesTheSourceBounds() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(0.25, 64, 0.25, 1.5F);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 60, -5, 6, 71, 6);

            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 59, -5, 5, 69, 5);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.OCTAHEDRON);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 59, -5, 5, 69, 5);

            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 60, -5, 6, 71, 6);
        }
    }

    @Test void fractionalEyeChangesWithinTheSameIntegerCellsRefreshSphereBounds() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(0, 64, 0, 1.0F);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 60, -5, 5, 70, 5);

            fixture.place(0.75, 64, 0.25, 1.5F);
            fixture.tracker.update(fixture.player);
            assertBounds(fixture.box(), -5, 60, -5, 6, 71, 6);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RadiusShapeType.class, names = {"CUBE", "OCTAHEDRON"})
    void physicalReachClipsAroundTheEyeWithoutMovingTheWorkShapeCenter(RadiusShapeType shape) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(0.5, 64, 0.5, 26.0F);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            fixture.tracker.update(fixture.player);

            assertBounds(fixture.box(), -7, 83, -7, 7, 96, 7);
            BlockPos nearEye = new BlockPos(0, 95, 0);
            assertTrue(ConfigUtils.canInteracted(nearEye));
            assertTrue(fixture.box().contains(nearEye),
                    "A native-reachable target inside the feet-based work shape must survive the source box intersection");
        }
    }

    @Test void disjointWorkAndPhysicalRangesHaveNoInteractionBox() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.place(0.5, 64, 0.5, 26.0F);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            fixture.tracker.update(fixture.player);
            assertNull(fixture.box(), "Feet-based work Y=59..69 cannot intersect native reach around eye Y=90");
        }
    }

    private static void assertBounds(PrinterBox box, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        assertEquals(new BlockPos(minX, minY, minZ), new BlockPos(box.minX, box.minY, box.minZ));
        assertEquals(new BlockPos(maxX, maxY, maxZ), new BlockPos(box.maxX, box.maxY, box.maxZ));
    }

    private static final class Fixture implements AutoCloseable {
        private final int previousRange = Configs.Core.WORK_RANGE.getIntegerValue();
        private final RadiusShapeType previousShape = (RadiusShapeType) Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        private final boolean previousInteractionCheck = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        private final sun.misc.Unsafe unsafe;
        private final Map<Field, Object> previousClients = new LinkedHashMap<>();
        private final LocalPlayer player;
        private final InteractionBoxTracker tracker = new InteractionBoxTracker(true);

        Fixture() throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            this.unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            this.player = (LocalPlayer) this.unsafe.allocateInstance(LocalPlayer.class);
            Field attributes = LivingEntity.class.getDeclaredField("attributes");
            attributes.setAccessible(true);
            attributes.set(this.player, new AttributeMap(Player.createAttributes().build()));
            this.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(5.0D);
            Minecraft client = (Minecraft) this.unsafe.allocateInstance(Minecraft.class);
            client.player = this.player;
            for (Class<?> owner : new Class<?>[]{ConfigUtils.class, PlayerUtils.class, PrinterBox.class}) {
                Field field = owner.getDeclaredField("client");
                field.setAccessible(true);
                this.previousClients.put(field, field.get(null));
                this.unsafe.putObject(this.unsafe.staticFieldBase(field), this.unsafe.staticFieldOffset(field), client);
            }
            Configs.Core.WORK_RANGE.setIntegerValue(5);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
        }

        void place(double x, double y, double z, float eyeHeight) throws Exception {
            set("position", new Vec3(x, y, z));
            set("blockPosition", new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
            set("eyeHeight", eyeHeight);
        }

        PrinterBox box() { return this.tracker.getBoxReference().get(); }

        private void set(String name, Object value) throws Exception {
            Field field = Entity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this.player, value);
        }

        @Override public void close() {
            Configs.Core.WORK_RANGE.setIntegerValue(this.previousRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(this.previousShape);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(this.previousInteractionCheck);
            for (var entry : this.previousClients.entrySet()) {
                Field field = entry.getKey();
                this.unsafe.putObject(this.unsafe.staticFieldBase(field), this.unsafe.staticFieldOffset(field), entry.getValue());
            }
        }
    }
}
