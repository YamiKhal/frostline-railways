# Frostline Railways — Forge 1.20.1

Everything rail related in Frostline. The core **Frostline** mod has no railway code; this
mod depends on it.

| id / system | what |
|---|---|
| `frostline_railways:corridor` | the railway's seeded centre line `x_c(z)`, a density function |
| `frostline_railways:relief_cap` | pulls mountains down toward a target height along the corridor |
| `compat.railways`, `mixin.railways` | Railways Untold direction (legacy; loads only with Railways Untold) |
| `frostline_railways:climate` | Railways Untold event trigger on a climate value (legacy) |
| Frostline Rail | railway generated with the world from the seed (in development) |

Settings: `config/frostline_railways.toml` (`RailwaysConfig`).

Plan, research and tracker: `RAILWAYS.md` in the Frostline datapack. Mod split: `CUSTOMMOD.md`.

## Building

```
./gradlew build
```

`build/libs/frostline_railways-1.0.0.jar` → `mods/`, next to `frostline-1.0.0.jar`. JDK 17.
Compiles against Railways Untold (local jar) and Create 6.0.8 (maven, `compileOnly`);
neither is required at runtime.
