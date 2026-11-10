package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import io.netty.buffer.Unpooled;
import neko.voidwarehouse.restock.PrinterPlaceHelloPayload;
import neko.voidwarehouse.restock.PrinterPlaceHelloResultPayload;
import neko.voidwarehouse.restock.PrinterPlacePayload;
import neko.voidwarehouse.restock.PrinterPlaceStatePayload;
import neko.voidwarehouse.restock.PrinterPlaceResultPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementNetworkTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({"DOWN, 0", "UP, 1", "NORTH, 2", "SOUTH, 3", "WEST, 4", "EAST, 5"})
    void directionEncodingPreservesTheSharedOrdinalWire(Direction face, int wireId) {
        var expected = legacyDirectionPayload(wireId);
        var actual = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var payload = new PrinterPlacePayload(17, List.of("minecraft:stone"), BlockPos.ZERO,
                    new BlockHitResult(Vec3.ZERO, face, BlockPos.ZERO, false), 42, "minecraft:overworld");

            PrinterPlacePayload.CODEC.encode(actual, payload);

            assertEquals(expected, actual, "Direction migration must preserve bytes understood by the server");
            var decoded = PrinterPlacePayload.CODEC.decode(expected);
            assertEquals(face, decoded.hit().getDirection());
            assertEquals(42, decoded.expectedStateId());
            assertEquals("minecraft:overworld", decoded.dimensionId());
            assertFalse(expected.isReadable());
        } finally {
            expected.release();
            actual.release();
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 6, Integer.MAX_VALUE})
    void invalidDirectionOrdinalsRemainRejected(int wireId) {
        var buffer = legacyDirectionPayload(wireId);
        try {
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> PrinterPlacePayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf legacyDirectionPayload(int wireId) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeVarInt(17);
        buffer.writeVarInt(1);
        buffer.writeUtf("minecraft:stone", 256);
        buffer.writeLong(0L); // Packed target and clicked BlockPos.ZERO.
        buffer.writeLong(0L);
        buffer.writeVarInt(wireId);
        buffer.writeDouble(0.0D);
        buffer.writeDouble(0.0D);
        buffer.writeDouble(0.0D);
        buffer.writeBoolean(false);
        buffer.writeVarInt(42);
        buffer.writeUtf("minecraft:overworld", 256);
        return buffer;
    }

    @Test
    void registersAllDirectionsAndPreservesExistingIntegratedServerCodecs() throws Exception {
        //#if MC >= 260100
        var outbound = (PayloadTypeRegistryImpl<RegistryFriendlyByteBuf>) PayloadTypeRegistry.serverboundPlay();
        var inbound = (PayloadTypeRegistryImpl<RegistryFriendlyByteBuf>) PayloadTypeRegistry.clientboundPlay();
        //#else
        //$$ var outbound = (PayloadTypeRegistryImpl<RegistryFriendlyByteBuf>) PayloadTypeRegistry.playC2S();
        //$$ var inbound = (PayloadTypeRegistryImpl<RegistryFriendlyByteBuf>) PayloadTypeRegistry.playS2C();
        //#endif
        VoidWarehousePlacementNetwork.init();
        assertNotNull(outbound.get(PrinterPlaceHelloPayload.TYPE));
        assertNotNull(inbound.get(PrinterPlaceHelloResultPayload.TYPE));
        assertNotNull(outbound.get(PrinterPlacePayload.TYPE));
        assertNotNull(outbound.get(PrinterPlaceStatePayload.TYPE));
        assertNotNull(inbound.get(PrinterPlaceResultPayload.TYPE));
        var registered = outbound.get(PrinterPlacePayload.TYPE);
        var stateRegistered = outbound.get(PrinterPlaceStatePayload.TYPE);
        var initialized = VoidWarehousePlacementNetwork.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, false);
        assertDoesNotThrow(VoidWarehousePlacementNetwork::init);
        assertSame(registered, outbound.get(PrinterPlacePayload.TYPE));
        assertSame(stateRegistered, outbound.get(PrinterPlaceStatePayload.TYPE));

        var hit = new BlockHitResult(new Vec3(1.125, 63.875, -2.75), Direction.UP, new BlockPos(1, 63, -3), true);
        var original = new PrinterPlacePayload(17, List.of("minecraft:stone", "minecraft:dirt"),
                new BlockPos(1, 64, -3), hit, 1234, "minecraft:the_nether");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            registered.codec().encode(buffer, original);
            var decoded = PrinterPlacePayload.CODEC.decode(buffer);
            assertEquals(17, decoded.nonce());
            assertEquals(List.of("minecraft:stone", "minecraft:dirt"), decoded.candidates());
            assertEquals(new BlockPos(1, 64, -3), decoded.target());
            assertEquals(1234, decoded.expectedStateId());
            assertEquals("minecraft:the_nether", decoded.dimensionId());
            assertEquals(hit.getBlockPos(), decoded.hit().getBlockPos());
            assertEquals(Direction.UP, decoded.hit().getDirection());
            assertEquals(new Vec3(1.125, 63.875, -2.75), decoded.hit().getLocation());
            assertTrue(decoded.hit().isInside());
            assertFalse(buffer.isReadable());
            inbound.get(PrinterPlaceHelloResultPayload.TYPE).codec().encode(buffer, new PrinterPlaceHelloResultPayload(19, true));
            assertEquals(new PrinterPlaceHelloResultPayload(19, true), PrinterPlaceHelloResultPayload.CODEC.decode(buffer));
            outbound.get(PrinterPlaceHelloPayload.TYPE).codec().encode(buffer, new PrinterPlaceHelloPayload(21));
            assertEquals(new PrinterPlaceHelloPayload(21), PrinterPlaceHelloPayload.CODEC.decode(buffer));
            inbound.get(PrinterPlaceResultPayload.TYPE).codec().encode(buffer, new PrinterPlaceResultPayload(22, 6));
            assertEquals(new PrinterPlaceResultPayload(22, 6), PrinterPlaceResultPayload.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void stateWireKeepsPhysicalHitIndependentOfSchematicProperties() {
        var hit = new BlockHitResult(new Vec3(-10.25, 64, 7.75), Direction.UP, new BlockPos(-11, 63, 7), false);
        var placement = new PrinterPlacePayload(37, List.of("minecraft:chest"), new BlockPos(-11, 64, 7),
                hit, 4321, "minecraft:overworld");
        var original = new PrinterPlaceStatePayload(placement, "minecraft:chest",
                Map.of("facing", "north", "type", "left", "waterlogged", "false"));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PrinterPlaceStatePayload.CODEC.encode(buffer, original);
            var decoded = PrinterPlaceStatePayload.CODEC.decode(buffer);
            assertEquals(37, decoded.placement().nonce());
            assertEquals(List.of("minecraft:chest"), decoded.placement().candidates());
            assertEquals(new BlockPos(-11, 64, 7), decoded.placement().target());
            assertEquals(4321, decoded.placement().expectedStateId());
            assertEquals("minecraft:overworld", decoded.placement().dimensionId());
            assertEquals(new BlockPos(-11, 63, 7), decoded.placement().hit().getBlockPos());
            assertEquals(Direction.UP, decoded.placement().hit().getDirection());
            assertEquals(new Vec3(-10.25, 64, 7.75), decoded.placement().hit().getLocation());
            assertFalse(decoded.placement().hit().isInside());
            assertEquals("minecraft:chest", decoded.blockId());
            assertEquals(Map.of("facing", "north", "type", "left", "waterlogged", "false"), decoded.properties());
            assertThrows(UnsupportedOperationException.class, () -> decoded.properties().put("facing", "south"));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void capturesRegisteredPropertyNamesAndSerializedValuesFromRequiredState() {
        var state = Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        var properties = VoidWarehousePlacementNetwork.stateProperties(state);
        assertEquals(Map.of("facing", "east", "type", "left", "waterlogged", "true"), properties);
        assertThrows(UnsupportedOperationException.class, () -> properties.put("facing", "west"));
        assertEquals(Map.of(), VoidWarehousePlacementNetwork.stateProperties(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void plansTheRawLogStageWithTheBlueprintAxisBeforeStripping() {
        var required = Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        var plan = VoidWarehousePlacementNetwork.planStateRequest(
                List.of("minecraft:oak_log", "minecraft:stripped_oak_log"), required);
        assertNotNull(plan);
        assertEquals(List.of("minecraft:oak_log"), plan.candidates());
        assertEquals("minecraft:oak_log", plan.blockId());
        assertEquals(Map.of("axis", "x"), plan.properties());
    }

    @Test
    void plansDirtWithoutFarmlandOnlyPropertiesBeforeHoeing() {
        var required = Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);
        var plan = VoidWarehousePlacementNetwork.planStateRequest(
                List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt"), required);
        assertNotNull(plan);
        assertEquals(List.of("minecraft:dirt"), plan.candidates());
        assertEquals("minecraft:dirt", plan.blockId());
        assertEquals(Map.of(), plan.properties());
    }

    @Test
    void plansIceWithoutWaterLevelBeforeBreakingItIntoWater() {
        var plan = VoidWarehousePlacementNetwork.planStateRequest(List.of("minecraft:ice"), Blocks.WATER.defaultBlockState());
        assertNotNull(plan);
        assertEquals(List.of("minecraft:ice"), plan.candidates());
        assertEquals("minecraft:ice", plan.blockId());
        assertEquals(Map.of(), plan.properties());
    }

    @Test
    void keepsChestPropertiesForTheActualChestMaterial() {
        var required = Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        var plan = VoidWarehousePlacementNetwork.planStateRequest(List.of("minecraft:chest"), required);
        assertNotNull(plan);
        assertEquals(List.of("minecraft:chest"), plan.candidates());
        assertEquals("minecraft:chest", plan.blockId());
        assertEquals(Map.of("facing", "east", "type", "left", "waterlogged", "true"), plan.properties());
    }

    @Test
    void anEmptyCandidateOnlyCoolsThatMaterialAndTheNextPlanUsesItsAlternative() {
        var state = new VoidWarehousePlacementState<BlockPos>();
        int hello = state.beginHello(0);
        state.receiveHello(hello, true, 0);
        var candidates = List.of("minecraft:oak_log", "minecraft:stripped_oak_log");
        var required = Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        var first = VoidWarehousePlacementNetwork.planStateRequest(state.eligibleCandidates(candidates, 0), required);
        assertNotNull(first);
        int nonce = state.beginRequest(first.candidates(), BlockPos.ZERO, 0);
        assertTrue(nonce > 0);
        state.receiveResult(nonce, 2, 1);
        assertEquals(List.of("minecraft:stripped_oak_log"), state.eligibleCandidates(candidates, 1));
        var next = VoidWarehousePlacementNetwork.planStateRequest(state.eligibleCandidates(candidates, 1), required);
        assertNotNull(next);
        assertEquals(List.of("minecraft:stripped_oak_log"), next.candidates());
        assertEquals("minecraft:stripped_oak_log", next.blockId());
        assertEquals(Map.of("axis", "x"), next.properties());
    }

    @Test
    void noEligibleBlockMaterialCannotBecomeABlueprintItemRequest() {
        assertNull(VoidWarehousePlacementNetwork.planStateRequest(List.of(), Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        assertNull(VoidWarehousePlacementNetwork.planStateRequest(
                List.of("minecraft:stick", "minecraft:nonexistent_item"), Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
    }

    @Test
    void stateCodecAllowsEmptyPropertiesAndExactSharedLimits() {
        var placement = stateTestPlacement();
        var properties = new LinkedHashMap<String, String>();
        for (int i = 0; i < 64; i++) properties.put("p" + i, "v");
        properties.remove("p0");
        properties.put("n".repeat(64), "v".repeat(128));
        for (var payload : List.of(new PrinterPlaceStatePayload(placement, "minecraft:stone", Map.of()),
                new PrinterPlaceStatePayload(placement, "b".repeat(256), properties))) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                PrinterPlaceStatePayload.CODEC.encode(buffer, payload);
                var decoded = PrinterPlaceStatePayload.CODEC.decode(buffer);
                assertEquals(payload.blockId(), decoded.blockId());
                assertEquals(payload.properties(), decoded.properties());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void stateCodecRejectsOversizeFieldsBeforeSending() {
        var properties = new LinkedHashMap<String, String>();
        for (int i = 0; i < 65; i++) properties.put("p" + i, "v");
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "minecraft:stone", properties),
                IllegalArgumentException.class);
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "b".repeat(257), Map.of()),
                io.netty.handler.codec.EncoderException.class);
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "minecraft:stone", Map.of("n".repeat(65), "v")),
                io.netty.handler.codec.EncoderException.class);
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "minecraft:stone", Map.of("p", "v".repeat(129))),
                io.netty.handler.codec.EncoderException.class);
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "minecraft:stone", Map.of("", "v")),
                IllegalArgumentException.class);
        assertStateEncodingRejected(new PrinterPlaceStatePayload(stateTestPlacement(), "minecraft:stone", null),
                RuntimeException.class);
    }

    @Test
    void stateCodecRejectsUnboundedCountsAndAmbiguousPropertyKeys() {
        for (int count : new int[]{-1, 65}) {
            var buffer = stateHeader(count);
            try {
                assertThrows(IllegalArgumentException.class, () -> PrinterPlaceStatePayload.CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        for (String secondName : new String[]{"facing", ""}) {
            var buffer = stateHeader(2);
            try {
                buffer.writeUtf("facing", 64);
                buffer.writeUtf("north", 128);
                buffer.writeUtf(secondName, 64);
                buffer.writeUtf("south", 128);
                assertThrows(IllegalArgumentException.class, () -> PrinterPlaceStatePayload.CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void invalidStateDataDoesNotStartARequest() {
        var target = BlockPos.ZERO.above();
        var physicalHit = new BlockHitResult(new Vec3(0.5, 1, 0.5), Direction.UP, BlockPos.ZERO, false);
        assertFalse(VoidWarehousePlacementNetwork.isStatePlacementSupported());
        assertFalse(VoidWarehousePlacementNetwork.requestStatePlace(new Item[]{Items.STONE}, target,
                new BlockHitResult(new Vec3(Double.NaN, 1, 0.5), Direction.UP, BlockPos.ZERO, false),
                0, Blocks.STONE.defaultBlockState()));
        assertFalse(VoidWarehousePlacementNetwork.requestStatePlace(new Item[]{Items.STONE}, target,
                new BlockHitResult(new Vec3(0.5, Double.POSITIVE_INFINITY, 0.5), Direction.UP, BlockPos.ZERO, false),
                0, Blocks.STONE.defaultBlockState()));
        assertFalse(VoidWarehousePlacementNetwork.requestStatePlace(new Item[]{Items.STONE}, target,
                new BlockHitResult(new Vec3(0.5, 1, Double.NEGATIVE_INFINITY), Direction.UP, BlockPos.ZERO, false),
                0, Blocks.STONE.defaultBlockState()));
        assertFalse(VoidWarehousePlacementNetwork.requestStatePlace(new Item[]{Items.STONE}, target, physicalHit,
                -1, Blocks.STONE.defaultBlockState()));
        assertFalse(VoidWarehousePlacementNetwork.requestStatePlace(new Item[]{Items.STONE}, target, physicalHit, 0, null));
    }

    private static PrinterPlacePayload stateTestPlacement() {
        return new PrinterPlacePayload(37, List.of("minecraft:stone"), BlockPos.ZERO.above(),
                new BlockHitResult(new Vec3(0.5, 1, 0.5), Direction.UP, BlockPos.ZERO, false), 0, "minecraft:overworld");
    }

    private static RegistryFriendlyByteBuf stateHeader(int count) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        PrinterPlacePayload.CODEC.encode(buffer, stateTestPlacement());
        buffer.writeUtf("minecraft:stone", 256);
        buffer.writeVarInt(count);
        return buffer;
    }

    private static void assertStateEncodingRejected(PrinterPlaceStatePayload payload, Class<? extends RuntimeException> error) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            assertThrows(error, () -> PrinterPlaceStatePayload.CODEC.encode(buffer, payload));
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsOnlyDistinctOrdinaryBlockItemsAndCapsCandidatesAtEight() {
        //#if MC >= 260200
        //$$ Item redBox = Items.DYED_SHULKER_BOX.red();
        //$$ Item redBed = Items.BED.red();
        //#else
        Item redBox = Items.RED_SHULKER_BOX;
        Item redBed = Items.RED_BED;
        //#endif
        assertEquals(List.of(), VoidWarehousePlacementNetwork.candidateIds(null));
        assertEquals(List.of("minecraft:stone", "minecraft:wheat_seeds", "minecraft:dirt"), VoidWarehousePlacementNetwork.candidateIds(new Item[] {
                null, Items.AIR, Items.STICK, Items.STONE, Items.SHULKER_BOX, redBox,
                Items.POWDER_SNOW_BUCKET, Items.WATER_BUCKET, Items.OAK_DOOR, redBed, Items.OAK_SIGN, Items.LILAC,
                Items.WHEAT_SEEDS, Items.STONE, Items.DIRT
        }));
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:andesite",
                        "minecraft:diorite", "minecraft:granite", "minecraft:deepslate", "minecraft:cobbled_deepslate"),
                VoidWarehousePlacementNetwork.candidateIds(new Item[] {Items.STONE, Items.DIRT, Items.COBBLESTONE,
                        Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK}));
    }

    @Test
    void rejectsOverlongListsBeforeReadingUnboundedData() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(1);
            buffer.writeVarInt(9);
            assertThrows(IllegalArgumentException.class, () -> PrinterPlacePayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsDimensionIdentifiersAboveTheSharedWireLimit() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var hit = new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false);
        try {
            var payload = new PrinterPlacePayload(1, List.of("minecraft:stone"), BlockPos.ZERO.above(),
                    hit, 0, "x".repeat(257));
            assertThrows(io.netty.handler.codec.EncoderException.class,
                    () -> PrinterPlacePayload.CODEC.encode(buffer, payload));
        } finally {
            buffer.release();
        }
    }
}
//#endif
