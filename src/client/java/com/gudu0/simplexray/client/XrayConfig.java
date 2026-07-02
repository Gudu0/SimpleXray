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

    // Kept as the migration reference for saves written by the old palette-index format,
    // and as the default ARGB color for newly added blocks.
    public static final int[] COLOR_PALETTE = {
            0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55,
            0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF, 0xFFFFFFFF
    };

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("xraymod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // LinkedHashMap preserves insertion order so the "Enabled" panel shows blocks in the
    // order the player added them, not an arbitrary map order.
    private static final Map<Block, Integer> ENABLED_BLOCKS = new LinkedHashMap<>();

    private static boolean modEnabled = true;

    public static boolean isModEnabled() { return modEnabled; }

    public static void setModEnabled(boolean enabled) {
        modEnabled = enabled;
        save();
        // Rescan on re-enable so the cache catches anything added/removed while disabled.
        if (enabled) XrayBlockCache.rescanLoadedChunks();
    }

    public static List<Block> getEnabledBlocks() {
        return new ArrayList<>(ENABLED_BLOCKS.keySet());
    }

    public static boolean isEnabled(Block block) {
        return ENABLED_BLOCKS.containsKey(block);
    }

    public static void addBlock(Block block) {
        long t0 = System.nanoTime();

        boolean blockAlreadyInEnabled = ENABLED_BLOCKS.containsKey(block);
        if (!blockAlreadyInEnabled) ENABLED_BLOCKS.put(block, COLOR_PALETTE[0]);
        long t1 = System.nanoTime();

        if (!blockAlreadyInEnabled) save();
        long t2 = System.nanoTime();

        // Full rescan needed: the new block type may already appear in chunks that were
        // scanned before it was added, so those chunks' cache entries are stale.
        if (!blockAlreadyInEnabled && modEnabled) XrayBlockCache.rescanLoadedChunks();
        long t3 = System.nanoTime();
//        debugMsg(String.format("[xray] addBlock — map: %dms  save: %dms  rescan: %dms  total: %dms", ms(t1, t0), ms(t2, t1), ms(t3, t2), ms(t3, t0)));
    }

    public static void removeBlock(Block block) {
        long t0 = System.nanoTime();
        ENABLED_BLOCKS.remove(block);
        long t1 = System.nanoTime();
        save();
        long t2 = System.nanoTime();
        // Evict rather than rescan — the cache already has every position of this block
        // type recorded, so we just filter those entries out without touching the world.
        if (modEnabled) XrayBlockCache.evictBlock(block);
        long t3 = System.nanoTime();
//        debugMsg(String.format("[xray] removeBlock — map: %dms  save: %dms  evict: %dms  total: %dms", ms(t1, t0), ms(t2, t1), ms(t3, t2), ms(t3, t0)));
    }

    private static long ms(long end, long start) {
        return (end - start) / 1_000_000;
    }

    private static void debugMsg(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal(msg), false);
    }

    /** Returns the full ARGB color stored for this block. */
    public static int getColor(Block block) {
        return ENABLED_BLOCKS.getOrDefault(block, COLOR_PALETTE[0]);
    }

    /** Updates the in-memory color immediately (for live slider preview) without writing to disk. */
    public static void setColorNoSave(Block block, int argbColor) {
        if (ENABLED_BLOCKS.containsKey(block)) {
            ENABLED_BLOCKS.put(block, argbColor);
        }
    }

    private static class ConfigData {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        Boolean modEnabled = null; // null = absent from file → default true
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data == null || data.blocks == null) return;

            modEnabled = data.modEnabled == null || data.modEnabled;

            ENABLED_BLOCKS.clear();
            for (Map.Entry<String, Integer> entry : data.blocks.entrySet()) {
                Identifier id = Identifier.tryParse(entry.getKey());
                if (id == null) continue;

                Block block = Registries.BLOCK.get(id);
                if (block == Blocks.AIR) continue; // saved block no longer exists (e.g. mod removed) — skip it

                int stored = entry.getValue();
                // Migrate old format: palette indices were stored as 0–7 (small positive ints).
                // Full ARGB colors have the 0xFF alpha byte set, making them negative as signed ints.
                int color = (stored >= 0 && stored < COLOR_PALETTE.length)
                        ? COLOR_PALETTE[stored]
                        : stored;
                ENABLED_BLOCKS.put(block, color);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        // Writes on every mutation rather than on shutdown so a crash mid-session doesn't
        // lose changes. The tradeoff (extra disk I/O) is acceptable because mutations are
        // rare, user-driven actions — not a hot path.
        ConfigData data = new ConfigData();
        data.modEnabled = modEnabled;
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
