package com.utilitysuite.world.seed;

import com.utilitysuite.mixin.world.BiomeManagerAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Zero-allocation client-side detector for worldgen seed clues inside loaded chunks and nearby slimes.
 * Kept separate from {@link SeedScannerModule} so unit tests can construct the module without bootstrapping
 * Minecraft block/biome registries.
 */
final class ClientChunkClueScanner {

    interface ClueSink {
        void onStructure(String type, int chunkX, int chunkZ);

        void onBuriedTreasure(int chunkX, int chunkZ);

        void onSlimeChunk(int chunkX, int chunkZ);
    }

    private static final Predicate<BlockState> DESERT_MARKER = s -> s.is(Blocks.BLUE_TERRACOTTA);
    private static final Predicate<BlockState> SWAMP_MARKER = s -> s.is(Blocks.CAULDRON);
    private static final Predicate<BlockState> JUNGLE_MARKER =
            s -> s.is(Blocks.DISPENSER) || s.is(Blocks.STICKY_PISTON);
    private static final Predicate<BlockState> IGLOO_MARKER = s -> s.is(Blocks.RED_BED);

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final List<Slime> slimeBuffer = new ArrayList<>();

    long readHashedSeed(ClientLevel level) {
        return ((BiomeManagerAccessor) level.getBiomeManager()).utilitysuite$biomeZoomSeed();
    }

    void scanChunk(ClientLevel level, LevelChunk chunk, ClueSink sink) {
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        int minX = cx << 4;
        int minZ = cz << 4;

        Holder<Biome> biome = level.getBiome(probe.set(minX + 8, 68, minZ + 8));
        if (biome.is(Biomes.BEACH) || biome.is(Biomes.SNOWY_BEACH) || biome.is(Biomes.STONY_SHORE)) {
            scanBuriedTreasure(level, minX, minZ, cx, cz, sink);
            return;
        }
        if (biome.is(Biomes.DEEP_OCEAN) || biome.is(Biomes.DEEP_COLD_OCEAN)
                || biome.is(Biomes.DEEP_LUKEWARM_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
            scanOceanMonument(level, minX, minZ, cx, cz, sink);
            return;
        }
        if (biome.is(Biomes.DESERT)) {
            scanDesertPyramid(level, chunk, minX, minZ, cx, cz, sink);
        } else if (biome.is(Biomes.SWAMP)) {
            scanSwampHut(level, chunk, minX, minZ, cx, cz, sink);
        } else if (biome.is(Biomes.JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) {
            scanJungleTemple(level, chunk, minX, minZ, cx, cz, sink);
        } else if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)
                || biome.is(Biomes.SNOWY_SLOPES)) {
            scanIgloo(level, chunk, minX, minZ, cx, cz, sink);
        }
    }

    /** Reads visible obsidian pillar bedrock-cap heights in {@code minecraft:the_end} into {@code outHeights10}. */
    boolean scanEndPillars(ClientLevel level, int[] outHeights10) {
        if (level.dimension() != Level.END) {
            return false;
        }
        boolean updated = false;
        for (int i = 0; i < EndPillars.COUNT; i++) {
            if (outHeights10[i] > 0) {
                continue;
            }
            int px = EndPillars.pillarX(i);
            int pz = EndPillars.pillarZ(i);
            if (!level.hasChunk(px >> 4, pz >> 4)) {
                continue;
            }
            int top = -1;
            for (int y = 108; y >= 72; y--) {
                if (level.getBlockState(probe.set(px, y, pz)).is(Blocks.BEDROCK)) {
                    top = y;
                    break;
                }
            }
            if (top < 0) {
                for (int y = 105; y >= 72; y--) {
                    if (level.getBlockState(probe.set(px, y, pz)).is(Blocks.OBSIDIAN)) {
                        top = y + 1;
                        break;
                    }
                }
            }
            if (top >= 76 && top <= 103 && (top - 76) % 3 == 0) {
                outHeights10[i] = top;
                updated = true;
            }
        }
        return updated;
    }

    void scanSlimes(ClientLevel level, LocalPlayer player, ClueSink sink) {
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        slimeBuffer.clear();
        level.getEntities(EntityType.SLIME, player.getBoundingBox().inflate(48.0),
                e -> e != null && e.isAlive(), slimeBuffer);
        for (int i = 0; i < slimeBuffer.size(); i++) {
            Slime s = slimeBuffer.get(i);
            int y = s.getBlockY();
            if (y >= 36) {
                continue; // keep well below swamp surface band (50..70)
            }
            int bx = s.getBlockX();
            int bz = s.getBlockZ();
            // Skip chunk borders so a hopping slime that crossed a boundary never corrupts chunk clues.
            int lx = bx & 15;
            int lz = bz & 15;
            if (lx < 3 || lx > 12 || lz < 3 || lz > 12) {
                continue;
            }
            Holder<Biome> b = level.getBiome(probe.set(bx, y, bz));
            if (!b.is(Biomes.SWAMP) && !b.is(Biomes.MANGROVE_SWAMP)) {
                sink.onSlimeChunk(bx >> 4, bz >> 4);
            }
        }
        slimeBuffer.clear();
    }

    private void scanBuriedTreasure(ClientLevel level, int minX, int minZ, int cx, int cz, ClueSink sink) {
        int wx = minX + 9;
        int wz = minZ + 9;
        for (int y = 85; y >= 35; y--) {
            if (level.getBlockState(probe.set(wx, y, wz)).is(Blocks.CHEST)) {
                BlockState above = level.getBlockState(probe.set(wx, y + 1, wz));
                if (above.is(Blocks.SAND) || above.is(Blocks.GRAVEL) || above.is(Blocks.SANDSTONE)
                        || above.is(Blocks.STONE) || above.is(Blocks.RED_SAND)) {
                    sink.onBuriedTreasure(cx, cz);
                    return;
                }
            }
        }
    }

    private void scanOceanMonument(ClientLevel level, int minX, int minZ, int cx, int cz, ClueSink sink) {
        // Exact roof crown sea-lantern invariant across all 4 monument orientations: (chunkMinX+1, 59, chunkMinZ+1)
        if (!level.getBlockState(probe.set(minX + 1, 59, minZ + 1)).is(Blocks.SEA_LANTERN)) {
            return;
        }
        if (level.getBlockState(probe.set(minX + 1, 60, minZ + 1)).is(Blocks.PRISMARINE_BRICKS)
                && level.getBlockState(probe.set(minX + 2, 59, minZ + 2)).is(Blocks.PRISMARINE_BRICKS)
                && level.getBlockState(probe.set(minX + 1, 58, minZ + 1)).is(Blocks.WATER)
                && level.getBlockState(probe.set(minX + 1, 55, minZ + 1)).is(Blocks.PRISMARINE)) {
            sink.onStructure("ocean_monument", cx, cz);
        }
    }

    private void scanDesertPyramid(ClientLevel level, LevelChunk chunk, int minX, int minZ, int cx, int cz,
                                   ClueSink sink) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSec = level.getMinSection();
        for (int s = 0; s < sections.length; s++) {
            int sy = (minSec + s) << 4;
            if (sy + 15 < 50 || sy > 95) {
                continue;
            }
            LevelChunkSection sec = sections[s];
            if (sec.hasOnlyAir() || !sec.maybeHas(DESERT_MARKER)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = sy + ly;
                if (wy < 50 || wy > 95) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!sec.getBlockState(lx, ly, lz).is(Blocks.BLUE_TERRACOTTA)) {
                            continue;
                        }
                        int wx = minX + lx;
                        int wz = minZ + lz;
                        if (level.getBlockState(probe.set(wx + 1, wy, wz + 1)).is(Blocks.ORANGE_TERRACOTTA)
                                && level.getBlockState(probe.set(wx - 1, wy, wz - 1)).is(Blocks.ORANGE_TERRACOTTA)
                                && level.getBlockState(probe.set(wx + 1, wy, wz - 1)).is(Blocks.ORANGE_TERRACOTTA)
                                && level.getBlockState(probe.set(wx - 1, wy, wz + 1)).is(Blocks.ORANGE_TERRACOTTA)) {
                            BlockState adj = level.getBlockState(probe.set(wx + 1, wy, wz));
                            if (adj.is(Blocks.SANDSTONE) || adj.is(Blocks.CUT_SANDSTONE)) {
                                sink.onStructure("desert_pyramid", cx, cz);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private void scanSwampHut(ClientLevel level, LevelChunk chunk, int minX, int minZ, int cx, int cz, ClueSink sink) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSec = level.getMinSection();
        for (int s = 0; s < sections.length; s++) {
            int sy = (minSec + s) << 4;
            if (sy + 15 < 56 || sy > 96) {
                continue;
            }
            LevelChunkSection sec = sections[s];
            if (sec.hasOnlyAir() || !sec.maybeHas(SWAMP_MARKER)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = sy + ly;
                if (wy < 56 || wy > 96) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!sec.getBlockState(lx, ly, lz).is(Blocks.CAULDRON)) {
                            continue;
                        }
                        int wx = minX + lx;
                        int wz = minZ + lz;
                        if (!level.getBlockState(probe.set(wx, wy - 1, wz)).is(Blocks.SPRUCE_PLANKS)) {
                            continue;
                        }
                        if (level.getBlockState(probe.set(wx + 1, wy, wz)).is(Blocks.CRAFTING_TABLE)
                                || level.getBlockState(probe.set(wx - 1, wy, wz)).is(Blocks.CRAFTING_TABLE)
                                || level.getBlockState(probe.set(wx, wy, wz + 1)).is(Blocks.CRAFTING_TABLE)
                                || level.getBlockState(probe.set(wx, wy, wz - 1)).is(Blocks.CRAFTING_TABLE)) {
                            sink.onStructure("swamp_hut", cx, cz);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void scanJungleTemple(ClientLevel level, LevelChunk chunk, int minX, int minZ, int cx, int cz,
                                  ClueSink sink) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSec = level.getMinSection();
        for (int s = 0; s < sections.length; s++) {
            int sy = (minSec + s) << 4;
            if (sy + 15 < 50 || sy > 115) {
                continue;
            }
            LevelChunkSection sec = sections[s];
            if (sec.hasOnlyAir() || !sec.maybeHas(JUNGLE_MARKER)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = sy + ly;
                if (wy < 50 || wy > 115) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState bs = sec.getBlockState(lx, ly, lz);
                        if (!bs.is(Blocks.DISPENSER) && !bs.is(Blocks.STICKY_PISTON)) {
                            continue;
                        }
                        int wx = minX + lx;
                        int wz = minZ + lz;
                        int templeBlocks = 0;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    BlockState nb = level.getBlockState(probe.set(wx + dx, wy + dy, wz + dz));
                                    if (nb.is(Blocks.MOSSY_COBBLESTONE) || nb.is(Blocks.CHISELED_STONE_BRICKS)) {
                                        templeBlocks++;
                                    }
                                }
                            }
                        }
                        if (templeBlocks >= 4) {
                            sink.onStructure("jungle_temple", cx, cz);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void scanIgloo(ClientLevel level, LevelChunk chunk, int minX, int minZ, int cx, int cz, ClueSink sink) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSec = level.getMinSection();
        for (int s = 0; s < sections.length; s++) {
            int sy = (minSec + s) << 4;
            if (sy + 15 < 55 || sy > 115) {
                continue;
            }
            LevelChunkSection sec = sections[s];
            if (sec.hasOnlyAir() || !sec.maybeHas(IGLOO_MARKER)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = sy + ly;
                if (wy < 55 || wy > 115) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!sec.getBlockState(lx, ly, lz).is(Blocks.RED_BED)) {
                            continue;
                        }
                        int wx = minX + lx;
                        int wz = minZ + lz;
                        boolean hasTable = false;
                        boolean hasFurnace = false;
                        int snow = 0;
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                for (int dy = -1; dy <= 1; dy++) {
                                    BlockState nb = level.getBlockState(probe.set(wx + dx, wy + dy, wz + dz));
                                    if (nb.is(Blocks.CRAFTING_TABLE)) {
                                        hasTable = true;
                                    } else if (nb.is(Blocks.FURNACE)) {
                                        hasFurnace = true;
                                    } else if (nb.is(Blocks.SNOW_BLOCK)) {
                                        snow++;
                                    }
                                }
                            }
                        }
                        if (hasTable && hasFurnace && snow >= 4) {
                            sink.onStructure("igloo", cx, cz);
                            return;
                        }
                    }
                }
            }
        }
    }
}
