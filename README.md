# Voxel Tracing 1.4.2

CPU-side voxel ray tracing for Minecraft 1.20.1 (Fabric). There are no shaders: rays are traced on the CPU
against the real block grid, baked into overlay meshes, and drawn over the vanilla image as a lighting
correction (a multiply pass for shadows / occlusion / tint, a boost pass for bounced light).

## What it does

- **Sun shadows** that follow the vanilla sun, crisp down to 1/16 block, tinted by glass, slime, honey,
  ice and water, absorbed by leaves.
- **Global illumination**: cosine-weighted hemisphere rays per vertex that either reach the sky or hit a
  surface that is itself lit by sun and sky. This gives ambient occlusion, colour bleeding off sunlit
  grass / wood / wool into shadow, and brighter undersides near lit ground.
- **Emitter shadows and colour**: torches, lava, glowstone etc. only light what they can actually see, with
  their own colour (warm, soul-fire blue, redstone red...).
- **Every block**: geometry comes from each BlockState's real collision boxes, optics from its render layer,
  map colour and light level. Stairs, slabs, fences, walls, panes, paths, farmland, carpets, snow layers,
  and modded blocks need no special cases.

## Controls

`V` toggles the effect, `B` cycles quality (LOW / MEDIUM / HIGH).

## Config (`config/voxeltracing.properties`)

| key | meaning |
| --- | --- |
| `quality` | LOW / MEDIUM / HIGH: radius, ray lengths, GI rays per vertex, bake budget |
| `gi`, `giStrength` | global illumination on/off and 0..1 strength |
| `shadowStrength` | how much of the daylight look is the sun (what a fully shadowed face loses) |
| `crispShadows` | true: shadow edges resolved to 1/16 block, false: 1/4 block |
| `sunShadows`, `blockLight` | toggle sun shadowing / emitter shadowing |
| `sunTilt` | sideways tilt of the sun path (0 matches the sun drawn by vanilla) |
| `warmStrength`, `torchShadow` | emitter colour tint and "unseen torch light" darkening |

## How it is built

`Optics` (per-BlockState optical data) -> `Sect` (16^3 snapshots) -> `Tracer` (exact shape ray marcher) ->
`Shading` (sun / GI / emitters) -> `Baker` (adaptive face meshes) -> `VoxelRenderer` (two blend passes).
`VoxelLighting` owns the world access and the nearest-first bake scheduler.

Where vanilla already shows darkness (caves, interiors) the effect fades out in proportion to the vanilla sky
light, so occlusion is never counted twice.
