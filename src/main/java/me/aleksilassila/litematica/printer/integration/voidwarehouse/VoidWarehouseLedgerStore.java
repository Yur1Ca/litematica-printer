package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把「仓库欠着我多少东西」这张账落到磁盘上，好让它活过一次重启。
 *
 * <h2>为什么必须落盘</h2>
 * <p>账本原先只在内存里，进程一退就没了。而<b>背包不会跟着清</b>：上一轮结束时留在身上的
 * 二十几格仓库材料，下一轮进游戏时仍然占着格子，新账本却是空的 —— 于是
 * {@code countWarehouseSlots()} 返回 0，{@code shouldReturnWhilePrinting} 一律 false，
 * <b>这批余料就再也退不回去了</b>。</p>
 *
 * <p>现场实测（2026-10-31 21:10:53 那一行）：开关打开时报的是
 * 「账上 0 件 / 0 种，背包 2 个空槽」—— 36 格只剩 2 格能用，打印机只能靠这 2 格反复
 * 取一组退一组，45 秒里取了 68 次退了 33 次，然后彻底停住。重启一次就多一批认不出的死重，
 * 所以「重启再试」从来没用。</p>
 *
 * <h2>为什么敢信这份快照</h2>
 * <p>恢复时逐条校验，任何一条对不上就整条丢掉：</p>
 * <ol>
 *   <li><b>同一个存档/服务器 + 同一个维度</b> —— 换世界立刻作废；</li>
 *   <li><b>游戏时间只增不减</b> —— 同一个世界内 {@code getGameTime()} 单调，对不上就是别的存档；</li>
 *   <li><b>两小时内</b> —— 隔天再进就不认了，那时的背包多半已经换过内容；</li>
 *   <li><b>背包里实际件数 ≥ 快照件数</b> —— 少了说明被用掉或搬走了，交给每 tick 的
 *       {@code reconcile} 夹住，<b>绝不上调</b>；</li>
 *   <li><b>≥ {@link #MIN_ADOPT_COUNT} 件</b> —— 余料特征就是接近满组。</li>
 * </ol>
 *
 * <p>最坏情况：玩家自己正好攥着一模一样的满组，被当成余料退走。仓库收下的是他自己的东西，
 * 他随时能再取回来 —— 属于烦人，不属于丢东西，所以这个风险可以接受；
 * 反过来的风险（余料永远退不回去）才是致命的。</p>
 */
final class VoidWarehouseLedgerStore {
    private static final Path FILE = Paths.get("config", "litematica-printer-void-warehouse-ledger.json");
    private static final int VERSION = 1;

    /** 超过这么久的快照不再采用（秒）。 */
    private static final long MAX_AGE_SECONDS = 2L * 60L * 60L;

    /** 少于这么多件的不当余料（余料是「整组拿、只用掉几个」，天然接近满组）。 */
    static final int MIN_ADOPT_COUNT = 8;

    private VoidWarehouseLedgerStore() {
    }

    /** 一次快照。{@code items} 的键是注册表 id，值是该物品欠着的件数。 */
    record Snapshot(String world, String dimension, long gameTime, Map<String, Integer> items) {
    }

    /**
     * Save an empty ledger as a tombstone; the controller guards sessions not yet restored.
     */
    static void save(String world, String dimension, long gameTime, Map<Item, Integer> ledger) {
        if (ledger == null) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject items = new JsonObject();
            for (Map.Entry<Item, Integer> entry : ledger.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                Identifier key = BuiltInRegistries.ITEM.getKey(entry.getKey());
                if (key == null) {
                    continue;
                }
                items.addProperty(key.toString(), entry.getValue());
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            root.addProperty("savedAt", System.currentTimeMillis() / 1000L);
            root.addProperty("world", world == null ? "" : world);
            root.addProperty("dimension", dimension == null ? "" : dimension);
            root.addProperty("gameTime", gameTime);
            root.add("items", items);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (Exception ignored) {
            // 写盘失败绝不能让返还逻辑出问题：大不了这一轮退不了。
        }
    }

    /**
     * 读回快照并做「同一个世界 / 两小时内 / 时间只增不减」三道校验。
     *
     * @return 通过校验的快照；任何一条不满足都返回 {@code null}
     */
    static @Nullable Snapshot load(String world, String dimension, long gameTimeNow) {
        if (!Files.isRegularFile(FILE)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != VERSION) {
                return null;
            }
            if (!root.has("world") || !root.has("dimension") || !root.has("gameTime")
                    || !root.has("savedAt") || !root.has("items")) {
                return null;
            }
            if (!equalsText(world, root.get("world").getAsString())
                    || !equalsText(dimension, root.get("dimension").getAsString())) {
                return null;
            }
            long savedGameTime = root.get("gameTime").getAsLong();
            if (gameTimeNow < savedGameTime) {
                // 同一个世界的游戏时间只会往前走；比快照还小 ⇒ 这不是那个存档。
                return null;
            }
            long age = System.currentTimeMillis() / 1000L - root.get("savedAt").getAsLong();
            if (age < 0L || age > MAX_AGE_SECONDS) {
                return null;
            }
            Map<String, Integer> items = new LinkedHashMap<>();
            JsonObject itemsObject = root.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> entry : itemsObject.entrySet()) {
                int count = entry.getValue().getAsInt();
                if (count > 0) {
                    items.put(entry.getKey(), count);
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return new Snapshot(root.get("world").getAsString(),
                    root.get("dimension").getAsString(), savedGameTime, items);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 注册表 id → 物品；解析不出来（换了版本/加了删了模组）就返回 {@code null}。 */
    static @Nullable Item itemOf(String id) {
        try {
            Identifier key = Identifier.tryParse(id);
            if (key == null) {
                return null;
            }
            // 26.x 的 Registry#get 给的是 Optional<Reference<T>>，不是 T
            Item item = BuiltInRegistries.ITEM.get(key).map(Holder.Reference::value).orElse(null);
            return item == null || item == Items.AIR ? null : item;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean equalsText(String a, String b) {
        return a == null ? b == null || b.isEmpty() : a.equals(b);
    }
}
//#endif
