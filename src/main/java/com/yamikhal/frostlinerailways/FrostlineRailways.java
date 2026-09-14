package com.yamikhal.frostlinerailways;

import com.mojang.serialization.Codec;
import com.yamikhal.frostlinerailways.rail.world.RailLineFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Frostline's railways. Everything rail related lives here, nothing else does.
 *
 *   frostline_railways:corridor    the railway's centre line, a density function that
 *   frostline_railways:relief_cap  reads X/Z (seeded, bent by noise), and the wrapper that
 *                                  pulls mountains down toward a target height along it.
 *
 *   frostline_railways:rail_line   Frostline Rail: the railway generated with the world
 *                                  (package rail: layout, worldgen, Create graph). Needs Create.
 *
 * Depends on the core Frostline mod. All settings: config/frostline_railways.toml
 * ({@link RailwaysConfig}); the line's shape and look: datapack frostline_rail/.
 * Create classes are only reached when {@link #createLoaded()}.
 */
@Mod(FrostlineRailways.MODID)
public class FrostlineRailways {

    public static final String MODID = "frostline_railways";

    private static boolean createLoaded;

    public static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTIONS =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MODID);

    public static final RegistryObject<Codec<? extends DensityFunction>> CORRIDOR =
            DENSITY_FUNCTIONS.register("corridor", () -> CorridorDensityFunction.CODEC.codec());

    public static final RegistryObject<Codec<? extends DensityFunction>> RELIEF_CAP =
            DENSITY_FUNCTIONS.register("relief_cap", () -> ReliefCapDensityFunction.CODEC.codec());

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, MODID);

    public static final RegistryObject<RailLineFeature> RAIL_LINE =
            FEATURES.register("rail_line", () -> new RailLineFeature(NoneFeatureConfiguration.CODEC));

    public FrostlineRailways() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        DENSITY_FUNCTIONS.register(bus);
        FEATURES.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RailwaysConfig.SPEC, MODID + ".toml");
        createLoaded = ModList.get().isLoaded("create");
        if (createLoaded) {
            com.yamikhal.frostlinerailways.rail.RailRuntime.init();
        }
    }

    public static boolean createLoaded() {
        return createLoaded;
    }
}
