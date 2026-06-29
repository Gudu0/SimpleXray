package com.gudu0.simplexray.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gudu0.simplexray.SimpleXray;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XrayConfig {
    Logger logger = SimpleXray.LOGGER;

    // Fixed 8-entry palette; color is picked by cycling an index, not a real color picker.
    // See CLAUDE.md "Known limitations" if a proper picker is ever wanted — it's a non-trivial UI addition.
    public static final int[] COLOR_PALETTE = {
            0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55,
            0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF, 0xFFFFFFFF
    };

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("xraymod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // LinkedHashMap preserves insertion order so the "Enabled" panel shows blocks in the
    // order the player added them, not an arbitrary map order.
    private static final Map<Block, Integer> ENABLED_BLOCKS = new LinkedHashMap<>();

    public static List<Block> getEnabledBlocks() {
        return new ArrayList<>(ENABLED_BLOCKS.keySet());
    }

    public static boolean isEnabled(Block block) {
        return ENABLED_BLOCKS.containsKey(block);
    }

    public static void addBlock(Block block) {
        long t0 = System.nanoTime(); // start time

        boolean blockAlreadyInEnabled = ENABLED_BLOCKS.containsKey(block); // check to make sure we're not trying to readd a block, unnecessary
        if (!blockAlreadyInEnabled) ENABLED_BLOCKS.put(block, 0);
        long t1 = System.nanoTime(); // time after puting block in enabled list

        if (!blockAlreadyInEnabled) save();
        long t2 = System.nanoTime(); // time after saving
        // Full rescan needed: the new block type may already appear in chunks that were
        // scanned before it was added, so those chunks' cache entries are stale.

        if (!blockAlreadyInEnabled) XrayBlockCache.rescanLoadedChunks(); // don't rescan if it was already in the list
        long t3 = System.nanoTime(); // time after rescan
//        debugMsg(String.format("[xray] addBlock — map: %dms  save: %dms  rescan: %dms  total: %dms",ms(t1, t0), ms(t2, t1), ms(t3, t2), ms(t3, t0)));
    }

    public static void removeBlock(Block block) {
        long t0 = System.nanoTime();
        ENABLED_BLOCKS.remove(block);
        long t1 = System.nanoTime();
        save();
        long t2 = System.nanoTime();
        // Evict rather than rescan — the cache already has every position of this block
        // type recorded, so we just filter those entries out without touching the world.
        XrayBlockCache.evictBlock(block);
        long t3 = System.nanoTime();
//        debugMsg(String.format("[xray] removeBlock — map: %dms  save: %dms  evict: %dms  total: %dms",ms(t1, t0), ms(t2, t1), ms(t3, t2), ms(t3, t0)));
    }

    private static long ms(long end, long start) {
        return (end - start) / 1_000_000;
    }

    private static void debugMsg(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal(msg), false);
    }

    public static int getColor(Block block) {
        return COLOR_PALETTE[ENABLED_BLOCKS.getOrDefault(block, 0)];
    }

    public static void cycleColor(Block block) {
        int current = ENABLED_BLOCKS.getOrDefault(block, 0);
        ENABLED_BLOCKS.put(block, (current + 1) % COLOR_PALETTE.length);
        save();
        // No cache rescan — color doesn't affect which positions are cached, only how they render.
    }

    private static class ConfigData {
        Map<String, Integer> blocks = new LinkedHashMap<>();
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data == null || data.blocks == null) return;

            ENABLED_BLOCKS.clear();
            for (Map.Entry<String, Integer> entry : data.blocks.entrySet()) {
                Identifier id = Identifier.tryParse(entry.getKey());
                if (id == null) continue;

                Block block = Registries.BLOCK.get(id);
                if (block == Blocks.AIR) continue; // saved block no longer exists (e.g. mod removed) — skip it

                // clamp guards against a hand-edited JSON with an out-of-range index
                int colorIndex = MathHelper.clamp(entry.getValue(), 0, COLOR_PALETTE.length - 1);
                ENABLED_BLOCKS.put(block, colorIndex);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void save() {
        // Writes on every mutation rather than on shutdown so a crash mid-session doesn't
        // lose changes. The tradeoff (extra disk I/O) is acceptable because mutations are
        // rare, user-driven actions — not a hot path.
        ConfigData data = new ConfigData();
        for (Map.Entry<Block, Integer> entry : ENABLED_BLOCKS.entrySet()) {
            data.blocks.put(Registries.BLOCK.getId(entry.getKey()).toString(), entry.getValue());
        }
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}