# Voxel Tracing (Fabric, Minecraft 1.20.1)

Client-side, voxel-based ray-traced shadows and block light. It never touches vanilla lighting:
it draws a thin translucent overlay on exposed block faces, so the look stays blocky and
Minecraft-style, and it is designed to run on low-end CPUs.

## Build

Requires JDK 17+.

    ./gradlew build          # jar ends up in build/libs/
    ./gradlew runClient      # test in a dev client

Versions in `gradle.properties` come from the template you supplied (Minecraft 1.20.1,
loader 0.20.1, loom 1.19-SNAPSHOT, Fabric API 0.92.12+1.20.1). Confirm them against
https://fabricmc.net/develop/ if Gradle complains.

## Controls

| Key | Action |
|-----|--------|
| V   | Toggle on/off |
| B   | Cycle quality LOW / MEDIUM / HIGH |

Settings live in `config/voxeltracing.properties` (quality, strengths, sunShadows, blockLight).

## How it works

1. **Voxels only.** Each 16x16x16 section is reduced to two 4096-bit bitsets (opaque, foliage) plus a
   short list of exposed light emitters. Leaves let 55% of light through per voxel.
2. **Ray marching.** Amanatides-Woo 3D-DDA through those bitsets. No BlockState lookups in the hot loop.
3. **Block-level shading.** Each exposed face looks at the air voxel in front of it; that voxel is traced
   once (memoised) toward the sun and toward the few strongest nearby emitters.
   - blocked from sun -> dark; facing away from sun -> mild self-shade
   - line of sight to a torch/lava/glowstone -> warm tint
   - vanilla light "leaking" round a corner but blocked for rays -> slight darkening (torch shadows)
4. **Time of day.** Sun direction follows the vanilla sky angle, quantised (48/72/96 steps per day) so
   shadows are rebaked only at step changes. Night = no sun work at all. Nether/End = block light only.
5. **Player interaction.** A one-line mixin on `ClientLevel.sendBlockUpdated` marks nearby sections dirty.
   Changes that don't alter opacity or emission (water flow, doors, crops) are ignored.

## Why it is cheap

- All baking is on the render thread under a hard per-frame budget (0.8 / 1.5 / 2.5 ms for LOW / MEDIUM / HIGH),
  nearest sections first, so it can never stall a frame for long.
- Results are static VBOs: per frame it is one frustum test and one draw call per visible section.
- Shadowed faces are greedily merged into rectangles per plane (shade is quantised first), so a wall of
  shadow is a handful of polygons instead of one quad per face.
- Faces facing away from the sun need no ray; air voxels shared by several faces are traced once.
- Fully buried faces, empty sections and distant sections cost nothing.

## Known limits

- Shadows are per block face, not smooth (intentional, matches the voxel style). Smoothing across
  corners would be the natural next feature.
- Shadow range is capped by quality (16 / 24 / 32 blocks).
- Rendering mods that replace the position-color shader or use their own pipelines (some shader packs)
  may draw the overlay differently.
