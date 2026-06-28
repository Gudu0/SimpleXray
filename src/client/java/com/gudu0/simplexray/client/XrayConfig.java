package com.gudu0.simplexray.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

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
        ENABLED_BLOCKS.putIfAbsent(block, 0);
        save();
        // Full rescan needed: the new block type may already appear in chunks that were
        // scanned before it was added, so those chunks' cache entries are stale.
        XrayBlockCache.rescanLoadedChunks();
    }

    public static void removeBlock(Block block) {
        ENABLED_BLOCKS.remove(block);
        save();
        XrayBlockCache.rescanLoadedChunks(); // same reason as addBlock
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