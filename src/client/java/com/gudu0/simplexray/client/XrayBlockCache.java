package com.gudu0.simplexray.client;

import com.gudu0.simplexray.SimpleXray;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class XrayBlockCache {
    Logger logger = SimpleXray.LOGGER;

    // Both pos and block are stored so the renderer can look up the color (keyed on Block)
    // without a per-frame world lookup for each cached position.
    public record CachedBlock(BlockPos pos, Block block) {}

    // ConcurrentHashMap because the renderer reads CACHE on the render thread while chunk
    // load/unload events fire on the client tick thread.
    private static final Map<ChunkPos, List<CachedBlock>> CACHE = new ConcurrentHashMap<>();

    // scanQueue and scanWorld are only ever touched on the client tick thread, so they
    // don't need to be thread-safe — no concurrent access is possible.
    private static final int CHUNKS_PER_TICK = 10;
    private static final Deque<WorldChunk> scanQueue = new ArrayDeque<>();
    private static ClientWorld scanWorld = null;

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(XrayBlockCache::scanChunk);
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> CACHE.remove(chunk.getPos()));

        // Drains the scan queue at a bounded rate so rescanLoadedChunks() never blocks
        // a single tick for the full scan — chunks appear progressively instead.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (scanWorld == null || scanQueue.isEmpty()) return;
            for (int i = 0; i < CHUNKS_PER_TICK && !scanQueue.isEmpty(); i++) {
                scanChunk(scanWorld, scanQueue.poll());
            }
            if (scanQueue.isEmpty()) scanWorld = null;
        });
    }

    public static Collection<List<CachedBlock>> getAllMatches() {
        return CACHE.values();
    }

    // Removes all cached positions of a specific block type without touching the world.
    // Used by removeBlock: the cache already contains every position of that type, so
    // there's no need to rescan — just filter the existing entries out.
    public static void evictBlock(Block block) {
        CACHE.forEach((chunkPos, matches) -> matches.removeIf(cb -> cb.block() == block));
        CACHE.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // Called whenever the enabled-block list changes — a newly added block type may already
    // appear in chunks that were scanned under the old list and won't be in the cache yet.
    // Populates scanQueue rather than scanning immediately; the tick handler drains it at
    // CHUNKS_PER_TICK per tick so no single tick is ever blocked for the full scan.
    // Clearing the queue first means a second call (e.g. player adds two blocks quickly)
    // cancels the in-progress scan and restarts with the current enabled-block list.
    public static void rescanLoadedChunks() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return;

        int chunkRadius = client.options.getViewDistance().getValue();
        ChunkPos center = new ChunkPos(player.getBlockPos());

        List<WorldChunk> chunks = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunk(center.x + dx, center.z + dz);
                if (chunk instanceof WorldChunk worldChunk) chunks.add(worldChunk);
            }
        }

        // Sort nearest-first so the player sees outlines close to them appear immediately.
        chunks.sort(Comparator.comparingInt(c -> {
            int dx = c.getPos().x - center.x, dz = c.getPos().z - center.z;
            return dx * dx + dz * dz;
        }));

        scanQueue.clear();
        scanQueue.addAll(chunks);
        scanWorld = world;
    }

    private static void scanChunk(ClientWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        List<CachedBlock> matches = new ArrayList<>();

        // getBottomY/getTopY account for the dimension's actual build limits (e.g. -64 to 319 in
        // the overworld) rather than hard-coding.
        int minY = world.getBottomY();
        int maxY = world.getTopY();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos blockPos = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                    Block block = chunk.getBlockState(blockPos).getBlock();
                    if (XrayConfig.isEnabled(block)) {
                        matches.add(new CachedBlock(blockPos, block));
                    }
                }
            }
        }

        // Remove the entry entirely when empty so getAllMatches() never hands the renderer
        // an empty list to iterate — keeps the render loop tight.
        if (matches.isEmpty()) {
            CACHE.remove(pos);
        } else {
            CACHE.put(pos, matches);
        }
    }

    public static void onBlockChanged(BlockPos pos, Block newBlock) {
        ChunkPos chunkPos = new ChunkPos(pos);
        // computeIfAbsent creates the list only if this chunk has no cache entry yet
        // (e.g. an xray block was just placed in a chunk that previously had none).
        List<CachedBlock> matches = CACHE.computeIfAbsent(chunkPos, p -> new ArrayList<>());
        matches.removeIf(cb -> cb.pos().equals(pos));

        if (XrayConfig.isEnabled(newBlock)) {
            matches.add(new CachedBlock(pos, newBlock));
        }

        if (matches.isEmpty()) {
            CACHE.remove(chunkPos); // same empty-list cleanup as scanChunk
        }
    }
}