package me.aleksilassila.litematica.printer.utils;

//#if MC >= 260100
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import me.aleksilassila.litematica.printer.utils.minecraft.IdentifierUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the tested Minecraft version's own tag data, not a hard-coded carpet fixture. */
class CoverMaterialFilterTest {
    private static final List<String> DEFAULT_FILTERS = List.of(
            "#minecraft:carpets", "#minecraft:slabs", "#minecraft:rails");
    private final List<Runnable> restoreTags = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //#if MC >= 260200
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        //#endif
    }

    @AfterEach
    void restoreTags() {
        for (int index = this.restoreTags.size() - 1; index >= 0; index--) {
            this.restoreTags.get(index).run();
        }
    }

    @Test
    void defaultListAcceptsEveryCarpetSlabAndRailFromVanillaTags() throws Exception {
        Set<Item> expected = new LinkedHashSet<>();
        for (String tag : List.of("wool_carpets", "slabs", "rails")) {
            Set<Item> members = readVanillaItemTag(tag);
            expected.addAll(members);
            for (Item item : members) {
                bindTags(item.builtInRegistryHolder(), List.of(TagKey.create(
                        BuiltInRegistries.ITEM.key(), IdentifierUtils.of("minecraft", tag))));
            }
        }
        List<Item> actual = RegistryFilterResolver.resolveItems(DEFAULT_FILTERS);
        for (Item item : expected) {
            assertTrue(actual.contains(item), () -> "Missing allowed material: " + BuiltInRegistries.ITEM.getKey(item));
        }
        assertTrue(actual.contains(itemById("minecraft:white_carpet")));
        assertTrue(actual.contains(Items.TUFF_SLAB));
        assertTrue(actual.contains(Items.RAIL));
        assertFalse(actual.contains(Items.STONE));
        assertFalse(actual.contains(itemById("minecraft:white_wool")));
    }

    @Test
    void carpetAliasAlsoWorksWhenOnlyBlockTagIsBound() throws Exception {
        Item carpet = itemById("minecraft:white_carpet");
        bindTags(carpet.builtInRegistryHolder(), List.of());
        bindTags(((BlockItem) carpet).getBlock().builtInRegistryHolder(), List.of(TagKey.create(
                BuiltInRegistries.BLOCK.key(), IdentifierUtils.of("minecraft:wool_carpets"))));
        assertTrue(RegistryFilterResolver.resolveItems(DEFAULT_FILTERS).contains(carpet));
    }

    @Test
    void customIdsAndTagsRemainAnAlternativeList() throws Exception {
        bindTags(Items.GLASS.builtInRegistryHolder(), List.of(TagKey.create(
                BuiltInRegistries.ITEM.key(), IdentifierUtils.of("test:cover_materials"))));
        List<Item> actual = RegistryFilterResolver.resolveItems(List.of(
                "minecraft:white_carpet", "#test:cover_materials"));
        assertTrue(actual.contains(itemById("minecraft:white_carpet")));
        assertTrue(actual.contains(Items.GLASS));
        assertFalse(actual.contains(Items.TUFF_SLAB));
    }

    private static Set<Item> readVanillaItemTag(String tag) throws Exception {
        String path = "/data/minecraft/tags/item/" + tag + ".json";
        Set<Item> items = new LinkedHashSet<>();
        try (var stream = CoverMaterialFilterTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "Vanilla tag resource missing: " + path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                for (JsonElement value : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                    String id = value.getAsString();
                    if (id.startsWith("#minecraft:")) {
                        items.addAll(readVanillaItemTag(id.substring("#minecraft:".length())));
                    } else {
                        items.add(itemById(id));
                    }
                }
            }
        }
        return items;
    }

    private static Item itemById(String id) {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> BuiltInRegistries.ITEM.getKey(item).toString().equals(id))
                .findFirst().orElseThrow();
    }

    private <T> void bindTags(Holder.Reference<T> holder, Collection<TagKey<T>> tags) throws Exception {
        Method method = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        method.setAccessible(true);
        List<TagKey<T>> previous = holder.tags().toList();
        this.restoreTags.add(() -> {
            try {
                method.invoke(holder, previous);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });
        method.invoke(holder, tags);
    }
}
//#endif
