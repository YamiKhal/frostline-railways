package com.yamikhal.frostlinerailways.mixin;

import com.yamikhal.frostlinerailways.rail.sites.RailSites;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rail sites (RAILWAYS.md §A8.15): after vanilla has made a chunk's structure starts from the structure sets, add the
 * rail sites the site plan starts in this chunk. They need no structure set; the plan decides where they go.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createStructures", at = @At("TAIL"))
    private void frostlineRailways$railSites(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager manager,
                                             ChunkAccess chunk, StructureTemplateManager templates, CallbackInfo ci) {
        RailSites.createStarts((ChunkGenerator) (Object) this, access, state, manager, chunk, templates);
    }
}
