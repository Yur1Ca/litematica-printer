package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import neko.voidwarehouse.restock.PrinterPlaceResultPayload;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementSoundTest {
    private static final BlockPos TARGET = new BlockPos(3, 64, -7);
    private VoidWarehousePlacementState<BlockPos> state;
    private RecordingLevel level;
    private Field tickField;
    private long previousTick;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        Field stateField = VoidWarehousePlacementNetwork.class.getDeclaredField("STATE");
        stateField.setAccessible(true);
        state = (VoidWarehousePlacementState<BlockPos>) stateField.get(null);
        state.reset();
        tickField = VoidWarehousePlacementNetwork.class.getDeclaredField("tick");
        tickField.setAccessible(true);
        previousTick = tickField.getLong(null);
        tickField.setLong(null, 0L);
        state.receiveHello(state.beginHello(0L), true, 0L);

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        level = (RecordingLevel) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(RecordingLevel.class);
        level.placedState = Blocks.STONE.defaultBlockState();
        level.sounds = new ArrayList<>();
    }

    @AfterEach
    void cleanup() throws Exception {
        state.reset();
        tickField.setLong(null, previousTick);
    }

    @Test
    void acceptedPlacementPlaysOneVanillaBlockSoundAfterConsumingTheRequest() {
        int nonce = request();
        level.onSound = () -> assertFalse(state.isPending(TARGET), "Consume before playing local feedback");

        result(nonce, PrinterPlaceResultPayload.OK);

        assertEquals(1, level.sounds.size());
        var played = level.sounds.getFirst();
        SoundType type = level.placedState.getSoundType();
        assertEquals(TARGET, played.pos());
        assertEquals(type.getPlaceSound(), played.sound());
        assertEquals(SoundSource.BLOCKS, played.source());
        assertEquals((type.getVolume() + 1.0F) / 2.0F, played.volume());
        assertEquals(type.getPitch() * 0.8F, played.pitch());
        assertFalse(played.distanceDelay());
    }

    @Test
    void usesTheAuthoritativePlacedBlockInsteadOfTheFirstMaterialCandidate() {
        int nonce = state.beginRequest(List.of("minecraft:stone", "minecraft:oak_planks"), TARGET, 0L);
        level.placedState = Blocks.OAK_PLANKS.defaultBlockState();

        result(nonce, PrinterPlaceResultPayload.OK);

        assertEquals(1, level.sounds.size());
        assertEquals(level.placedState.getSoundType().getPlaceSound(), level.sounds.getFirst().sound());
        assertNotEquals(Blocks.STONE.defaultBlockState().getSoundType().getPlaceSound(), level.sounds.getFirst().sound());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, -1, 7})
    void unsuccessfulResultsNeverPlayOrReadTheWorld(int status) {
        result(request(), status);

        assertTrue(level.sounds.isEmpty());
        assertEquals(0, level.stateReads);
        assertFalse(state.isPending(TARGET));
    }

    @Test
    void duplicateSuccessIsSilentAfterTheFirstReceipt() {
        int nonce = request();

        result(nonce, PrinterPlaceResultPayload.OK);
        result(nonce, PrinterPlaceResultPayload.OK);

        assertEquals(1, level.sounds.size());
        assertEquals(1, level.stateReads);
    }

    @Test
    void unknownSuccessIsSilent() {
        result(Integer.MAX_VALUE, PrinterPlaceResultPayload.OK);

        assertTrue(level.sounds.isEmpty());
        assertEquals(0, level.stateReads);
    }

    @Test
    void expiredSuccessCannotPlayForItsReplacementRequest() throws Exception {
        int expired = request();
        tickField.setLong(null, 100L);
        int replacement = state.beginRequest(List.of("minecraft:stone"), TARGET, 100L);

        result(expired, PrinterPlaceResultPayload.OK);

        assertTrue(level.sounds.isEmpty());
        assertTrue(state.isPending(TARGET));
        result(replacement, PrinterPlaceResultPayload.OK);
        assertEquals(1, level.sounds.size());
    }

    @Test
    void resetAndCancelledRequestsCannotPlayLateSuccessSounds() {
        int cancelled = request();
        state.cancel(cancelled);
        result(cancelled, PrinterPlaceResultPayload.OK);
        int oldSession = request();
        state.reset();
        result(oldSession, PrinterPlaceResultPayload.OK);

        assertTrue(level.sounds.isEmpty());
        assertEquals(0, level.stateReads);
    }

    @Test
    void anEmptyAuthoritativeTargetDoesNotPlayAPlacementSound() {
        level.placedState = Blocks.AIR.defaultBlockState();

        result(request(), PrinterPlaceResultPayload.OK);

        assertTrue(level.sounds.isEmpty());
        assertFalse(state.isPending(TARGET));
    }

    private int request() {
        int nonce = state.beginRequest(List.of("minecraft:stone"), TARGET, 0L);
        assertNotEquals(0, nonce);
        return nonce;
    }

    private void result(int nonce, int status) {
        VoidWarehousePlacementNetwork.handleResult(new PrinterPlaceResultPayload(nonce, status), level);
    }

    record PlayedSound(BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch,
                       boolean distanceDelay) { }

    static class RecordingLevel extends ClientLevel {
        BlockState placedState;
        List<PlayedSound> sounds;
        Runnable onSound;
        int stateReads;

        RecordingLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }

        @Override public BlockState getBlockState(BlockPos pos) {
            assertEquals(TARGET, pos);
            stateReads++;
            return placedState;
        }

        @Override public void playLocalSound(BlockPos pos, SoundEvent sound, SoundSource source,
                                             float volume, float pitch, boolean distanceDelay) {
            if (onSound != null) onSound.run();
            sounds.add(new PlayedSound(pos.immutable(), sound, source, volume, pitch, distanceDelay));
        }
    }
}
