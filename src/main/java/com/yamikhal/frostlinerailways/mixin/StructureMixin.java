package com.yamikhal.frostlinerailways.mixin;

import com.yamikhal.frostlinerailways.rail.sites.RailSites;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * The exclusion band (RAILWAYS.md §A8.15): a structure start of any structure, from any mod, that would stand on or
 * next to the track, a station or a rail site is replaced by an invalid start, so the structure is not generated.
 * Starts that already carry references (a start being made again for a chunk that had one) are left alone.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void frostlineRailways$exclusion(RegistryAccess access, ChunkGenerator generator, BiomeSource biomes, RandomState random,
                                             StructureTemplateManager templates, long seed, ChunkPos chunk, int references,
                                             LevelHeightAccessor height, Predicate<Holder<Biome>> validBiome,
                                             CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (references == 0 && start != null && start.isValid()
                && RailSites.exclude((Structure) (Object) this, access, generator, random, seed, start)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
