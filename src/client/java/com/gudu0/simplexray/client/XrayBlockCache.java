package com.gudu0.simplexray.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class XrayBlockCache {

    // Both pos and block are stored so the renderer can look up the color (keyed on Block)
    // without a per-frame world lookup for each cached position.
    public record CachedBlock(BlockPos pos, Block block) {}

    // ConcurrentHashMap because the renderer reads CACHE on the render thread while chunk
    // load/unload events fire on the client tick thread.
    private static final Map<ChunkPos, List<CachedBlock>> CACHE = new ConcurrentHashMap<>();

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(XrayBlockCache::scanChunk);
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> CACHE.remove(chunk.getPos()));
    }

    public static Collection<List<CachedBlock>> getAllMatches() {
        return CACHE.values();
    }

    // Called whenever the enabled-block list changes — a newly added block type may already
    // appear in chunks that were scanned under the old list and won't be in the cache yet.
    public static void rescanLoadedChunks() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return;

        int chunkRadius = client.options.getViewDistance().getValue();
        ChunkPos center = new ChunkPos(player.getBlockPos());

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunk(center.x + dx, center.z + dz);
                if (chunk instanceof WorldChunk worldChunk) {
                    scanChunk(world, worldChunk);
                }
            }
        }
    }

    private static void scanChunk(ClientWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        List<CachedBlock> matches = new ArrayList<>();

        // getBottomY/getTopY account for the dimension's actual build limits (e.g. -64 in
        // the overworld) rather than hard-coding 0–256, which would miss deepslate-level ores.
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