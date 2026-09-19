package com.yamikhal.frostlinerailways.mixin;

import com.yamikhal.frostlinerailways.rail.sites.RailSites;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structures along the line (RAILWAYS.md §A8.15), in the chunk's structure_starts step.
 *
 *   tryGenerateStructure  the exclusion band: the start a structure set's entry just generated is replaced by an
 *                         invalid one when it stands on or next to the track, a station or a rail site. Vanilla then
 *                         tries the set's next entry, exactly as if the structure had not fit. Worldgen only (not
 *                         /place), and whatever the structure's class does in generate()
 *   createStructures      after vanilla's starts: the rail sites the site plan starts in this chunk (no structure set)
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"))
    private void frostlineRailways$enter(StructureSet.StructureSelectionEntry entry, StructureManager manager, RegistryAccess access,
                                         RandomState random, StructureTemplateManager templates, long seed, ChunkAccess chunk,
                                         ChunkPos chunkPos, SectionPos section, CallbackInfoReturnable<Boolean> cir) {
        RailSites.enter(entry.structure(), access, random, seed);
    }

    @ModifyVariable(method = "tryGenerateStructure", at = @At("STORE"), ordinal = 0)
    private StructureStart frostlineRailways$exclusion(StructureStart start) {
        return RailSites.filter(start, (ChunkGenerator) (Object) this);
    }

    @Inject(method = "tryGenerateStructure", at = @At("RETURN"))
    private void frostlineRailways$leave(StructureSet.StructureSelectionEntry entry, StructureManager manager, RegistryAccess access,
                                         RandomState random, StructureTemplateManager templates, long seed, ChunkAccess chunk,
                                         ChunkPos chunkPos, SectionPos section, CallbackInfoReturnable<Boolean> cir) {
        RailSites.leave();
    }

    @Inject(method = "createStructures", at = @At("TAIL"))
    private void frostlineRailways$railSites(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager manager,
                                             ChunkAccess chunk, StructureTemplateManager templates, CallbackInfo ci) {
        RailSites.createStarts((ChunkGenerator) (Object) this, access, state, manager, chunk, templates);
    }
}
