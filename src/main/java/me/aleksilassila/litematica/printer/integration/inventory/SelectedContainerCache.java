package me.aleksilassila.litematica.printer.integration.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Explicit allow-list for remote container retrieval.
 *
 * <p>Chest Tracker owns the remembered contents; this class only persists the
 * positions the player explicitly opted into. That keeps remote retrieval
 * bounded to one world/dimension instead of the global MemoryBank.</p>
 */
final class SelectedContainerCache {
    private static final Path FILE = Paths.get("config", "litematica-printer-container-cache.json");
    private final Set<Entry> entries = new LinkedHashSet<>();
    private boolean loaded;

    int add(String world, String dimension, BlockPos pos) {
        load();
        return this.entries.add(new Entry(world, dimension, pos.getX(), pos.getY(), pos.getZ())) ? 1 : 0;
    }

    boolean contains(String world, String dimension, BlockPos pos) {
        load();
        return this.entries.contains(new Entry(world, dimension, pos.getX(), pos.getY(), pos.getZ()));
    }

    int count(String world, String dimension) {
        load();
        int count = 0;
        for (Entry entry : this.entries) {
            if (entry.world.equals(world) && entry.dimension.equals(dimension)) count++;
        }
        return count;
    }

    int clear(String world, String dimension) {
        load();
        int before = this.entries.size();
        this.entries.removeIf(entry -> entry.world.equals(world) && entry.dimension.equals(dimension));
        return before - this.entries.size();
    }

    void save() {
        load();
        try {
            Files.createDirectories(FILE.getParent());
            JsonArray array = new JsonArray();
            for (Entry entry : this.entries) {
                JsonObject object = new JsonObject();
                object.addProperty("world", entry.world);
                object.addProperty("dimension", entry.dimension);
                object.addProperty("x", entry.x);
                object.addProperty("y", entry.y);
                object.addProperty("z", entry.z);
                array.add(object);
            }
            JsonObject root = new JsonObject();
            root.add("entries", array);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (Exception ignored) {
            // A cache write failure must never stop printing or material retrieval.
        }
    }

    static String worldId(Minecraft client) {
        if (client.getSingleplayerServer() != null) {
            return "singleplayer:" + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        String address = client.getCurrentServer() == null ? null : client.getCurrentServer().ip;
        if (address == null && client.getConnection() != null && client.getConnection().getConnection() != null) {
            address = String.valueOf(client.getConnection().getConnection().getRemoteAddress());
        }
        if (address != null) return "multiplayer:" + address;
        // If mappings hide the server identity, prefer a session-scoped key over
        // accidentally sharing coordinates between unrelated worlds.
        return "session:" + System.identityHashCode(client.level);
    }

    static String dimensionId(Minecraft client) {
        return client.level == null ? "unknown" : String.valueOf(client.level.dimension());
    }

    private void load() {
        if (this.loaded) return;
        this.loaded = true;
        if (!Files.isRegularFile(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("entries")) return;
            for (JsonElement element : parsed.getAsJsonObject().getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("world") || !object.has("dimension")
                        || !object.has("x") || !object.has("y") || !object.has("z")) continue;
                this.entries.add(new Entry(
                        object.get("world").getAsString(),
                        object.get("dimension").getAsString(),
                        object.get("x").getAsInt(),
                        object.get("y").getAsInt(),
                        object.get("z").getAsInt()
                ));
            }
        } catch (Exception ignored) {
            this.entries.clear();
        }
    }

    private record Entry(String world, String dimension, int x, int y, int z) {
    }
}
