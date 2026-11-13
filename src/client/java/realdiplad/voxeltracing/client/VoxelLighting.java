package realdiplad.voxeltracing.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * Owns all voxel data and does the work:
 *  - section storage (16x16x16 sub-voxel grid per block) + a 3D-DDA voxel ray marcher
 *  - sun model (quantised so shadows are rebaked rarely, not every tick)
 *  - a priority queue of sections to (re)bake, drained under a strict per-frame time budget
 * Everything runs on the render thread, so there is no locking.
 *
 * Rays march on the per-block 16x16x16 grid: partial shapes (stairs, slabs, fences, panes...)
 * are walked sub-voxel by sub-voxel, and light passing through glass is attenuated and
 * coloured per channel (see VoxelSection.attenuate).
 */
public final class VoxelLighting {
	public static final VoxelLighting INSTANCE = new VoxelLighting();

	static final int NO_SUN = -1;

	final Long2ObjectOpenHashMap<VoxelSection> sections = new Long2ObjectOpenHashMap<>();
	ClientLevel level;

	// Sun state. dir points FROM the world TOWARDS the sun.
	int sunKey = NO_SUN;
	float sunStrength;
	float lx = 0f, ly = 1f, lz = 0f;

	// Camera section
	int camSx = Integer.MIN_VALUE, camSy, camSz;

	/** RGB transmittance of the last trace() call. */
	float trR = 1f, trG = 1f, trB = 1f;

	private final SectionBaker baker = new SectionBaker(this);
	private final ArrayList<VoxelSection> queue = new ArrayList<>();
	private int queueHead;
	private boolean needsScan = true;

	// One-entry section cache for the ray marcher
	private VoxelSection cacheSec;
	private int cSx, cSy, cSz;

	private final float[] acc = new float[3];

	private VoxelLighting() {
	}

	// ------------------------------------------------------------------ frame hook

	public void beginFrame(ClientLevel lvl, Camera camera, float tickDelta) {
		if (lvl == null) return;
		VtConfig cfg = VtConfig.get();
		if (lvl != level) {
			reset();
			level = lvl;
		}
		if (!cfg.enabled) {
			if (!sections.isEmpty()) reset();
			return;
		}

		updateSun(lvl, cfg, tickDelta);

		Vec3 cam = camera.getPosition();
		int csx = Mth.floor(cam.x) >> 4, csy = Mth.floor(cam.y) >> 4, csz = Mth.floor(cam.z) >> 4;
		if (csx != camSx || csy != camSy || csz != camSz) {
			camSx = csx;
			camSy = csy;
			camSz = csz;
			needsScan = true;
		}

		if (needsScan) {
			scan(cfg);
			needsScan = false;
		}
		drainQueue(cfg);
	}

	// ------------------------------------------------------------------ sun

	private void updateSun(ClientLevel lvl, VtConfig cfg, float tickDelta) {
		int newKey = NO_SUN;
		float strength = 0f;
		DimensionType dt = lvl.dimensionType();
		if (cfg.sunShadows && dt.hasSkyLight() && !dt.hasCeiling()) {
			float tod = lvl.getTimeOfDay(tickDelta); // vanilla celestial angle, 0 = noon
			int steps = cfg.quality.sunSteps;
			int step = Mth.clamp((int) (tod * steps), 0, steps - 1);
			float angle = (step + 0.5f) / steps * Mth.TWO_PI;
			// Vanilla sky: sun = (-sin a, cos a, 0). Small constant tilt so shadows aren't purely east-west.
			float x = -Mth.sin(angle), y = Mth.cos(angle), z = 0.30f;
			float inv = 1f / Mth.sqrt(x * x + y * y + z * z);
			x *= inv;
			y *= inv;
			z *= inv;
			if (y > 0.06f) {
				newKey = step;
				strength = smoothstep(0.06f, 0.40f, y);
				lx = x;
				ly = y;
				lz = z;
			}
		}
		sunStrength = strength;
		if (newKey != sunKey) {
			sunKey = newKey;
			needsScan = true;
		}
	}

	private static float smoothstep(float a, float b, float v) {
		float t = Mth.clamp((v - a) / (b - a), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	// ------------------------------------------------------------------ scheduling

	private void scan(VtConfig cfg) {
		queue.clear();
		queueHead = 0;
		cacheSec = null;

		int hr = cfg.quality.horizontalRadius, vr = cfg.quality.verticalRadius;
		int y0 = Math.max(level.getMinSection(), camSy - vr);
		int y1 = Math.min(level.getMaxSection() - 1, camSy + vr);

		for (int dx = -hr; dx <= hr; dx++) {
			for (int dz = -hr; dz <= hr; dz++) {
				if (dx * dx + dz * dz > hr * hr + hr) continue;
				int sx = camSx + dx, sz = camSz + dz;
				if (!level.getChunkSource().hasChunk(sx, sz)) continue;
				for (int sy = y0; sy <= y1; sy++) {
					long key = SectionPos.asLong(sx, sy, sz);
					VoxelSection s = sections.get(key);
					if (s == null) {
						s = new VoxelSection(sx, sy, sz);
						sections.put(key, s);
					}
					if (s.dirty || s.bakedKey != sunKey) {
						int dy = sy - camSy;
						s.sortKey = (s.urgent ? 0 : 100000) + dx * dx + dy * dy + dz * dz;
						queue.add(s);
					}
				}
			}
		}
		queue.sort((a, b) -> Integer.compare(a.sortKey, b.sortKey));

		// Evict sections that drifted well outside the area (their VBOs are freed too).
		int evH = hr + 3, evV = vr + 2;
		ObjectIterator<VoxelSection> it = sections.values().iterator();
		while (it.hasNext()) {
			VoxelSection s = it.next();
			if (Math.abs(s.sx - camSx) > evH || Math.abs(s.sz - camSz) > evH || Math.abs(s.sy - camSy) > evV) {
				s.close();
				it.remove();
			}
		}
	}

	private void drainQueue(VtConfig cfg) {
		if (queueHead >= queue.size()) return;
		long deadline = System.nanoTime() + (long) (cfg.quality.bakeBudgetMs * 1_000_000.0);
		cacheSec = null;
		while (queueHead < queue.size()) {
			VoxelSection s = queue.get(queueHead++);
			if (s.removed) continue;
			if (!s.dirty && s.bakedKey == sunKey) continue;
			baker.bake(s, cfg);
			if (System.nanoTime() >= deadline) break; // always makes progress: at least one bake per frame
		}
	}

	// ------------------------------------------------------------------ world change hooks

	public void onBlockChanged(ClientLevel lvl, BlockPos pos, BlockState oldS, BlockState newS) {
		VtConfig cfg = VtConfig.get();
		if (!cfg.enabled || lvl != level) return;
		// Water flowing, doors opening, crops growing... none of it changes opacity/emission.
		if (VoxelSection.classify(oldS) == VoxelSection.classify(newS)) return;

		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		int r = Math.max(1, (cfg.quality.rayRange + 15) >> 4);
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					VoxelSection s = sections.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
					if (s == null) continue;
					if (dx == 0 && dy == 0 && dz == 0) s.invalidateData();
					s.dirty = true;
					if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= 1) s.urgent = true;
				}
			}
		}
		cacheSec = null;
		needsScan = true;
	}

	public void onChunkLoad(ClientLevel lvl, int cx, int cz) {
		if (!VtConfig.get().enabled || lvl != level) return;
		int minS = lvl.getMinSection(), maxS = lvl.getMaxSection();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int sy = minS; sy < maxS; sy++) {
					VoxelSection s = sections.get(SectionPos.asLong(cx + dx, sy, cz + dz));
					if (s == null) continue;
					if (dx == 0 && dz == 0) s.invalidateData();
					s.dirty = true;
					s.urgent = true;
				}
			}
		}
		cacheSec = null;
		needsScan = true;
	}

	public void onChunkUnload(ClientLevel lvl, int cx, int cz) {
		if (lvl != level) return;
		for (int sy = lvl.getMinSection(); sy < lvl.getMaxSection(); sy++) {
			VoxelSection s = sections.remove(SectionPos.asLong(cx, sy, cz));
			if (s != null) s.close();
		}
		cacheSec = null;
	}

	/** Re-bake everything (quality / settings changed). */
	public void invalidateAll() {
		for (VoxelSection s : sections.values()) s.dirty = true;
		sunKey = Integer.MIN_VALUE; // force sun re-evaluation
		needsScan = true;
	}

	public void reset() {
		for (VoxelSection s : sections.values()) s.close();
		sections.clear();
		queue.clear();
		queueHead = 0;
		cacheSec = null;
		camSx = Integer.MIN_VALUE;
		sunKey = Integer.MIN_VALUE;
		needsScan = true;
	}

	// ------------------------------------------------------------------ voxel access

	/** Returns the section (creating a placeholder if needed), or null outside the world's height. */
	VoxelSection getSection(int sx, int sy, int sz, boolean load) {
		if (sy < level.getMinSection() || sy >= level.getMaxSection()) return null;
		long key = SectionPos.asLong(sx, sy, sz);
		VoxelSection s = sections.get(key);
		if (s == null) {
			s = new VoxelSection(sx, sy, sz);
			sections.put(key, s);
		}
		if (load && s.loadState == VoxelSection.NOT_TRIED) s.load(level);
		return s;
	}

	boolean isOpaque(int x, int y, int z) {
		VoxelSection s = section(x, y, z);
		if (s == null || s.opaque == null) return false;
		int bi = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		return (s.opaque[bi >>> 6] & (1L << bi)) != 0L;
	}

	private VoxelSection section(int x, int y, int z) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		if (cacheSec != null && sx == cSx && sy == cSy && sz == cSz) return cacheSec;
		VoxelSection s = getSection(sx, sy, sz, true);
		cacheSec = s;
		cSx = sx;
		cSy = sy;
		cSz = sz;
		return s;
	}

	/**
	 * Amanatides-Woo traversal on the per-block 16x16x16 grid, from (ox,oy,oz) along a UNIT
	 * direction for maxLen blocks. The start block is assumed open. Stops early when it enters
	 * the target block (tx,ty,tz). Every block is evaluated on the sub-voxel grid; light passing
	 * through partial shapes and glass is attenuated / coloured per channel.
	 * Results land in trR/trG/trB.
	 */
	void trace(double ox, double oy, double oz, double dx, double dy, double dz, double maxLen,
			   int tx, int ty, int tz) {
		acc[0] = acc[1] = acc[2] = 1f;
		int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		final double inf = Double.POSITIVE_INFINITY;
		double invX = dx != 0 ? 1.0 / Math.abs(dx) : inf;
		double invY = dy != 0 ? 1.0 / Math.abs(dy) : inf;
		double invZ = dz != 0 ? 1.0 / Math.abs(dz) : inf;
		double tMaxX = dx > 0 ? (x + 1 - ox) * invX : dx < 0 ? (ox - x) * invX : inf;
		double tMaxY = dy > 0 ? (y + 1 - oy) * invY : dy < 0 ? (oy - y) * invY : inf;
		double tMaxZ = dz > 0 ? (z + 1 - oz) * invZ : dz < 0 ? (oz - z) * invZ : inf;

		final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
		while (true) {
			double tn;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) {
					tn = tMaxX;
					x += stepX;
					tMaxX += invX;
				} else {
					tn = tMaxZ;
					z += stepZ;
					tMaxZ += invZ;
				}
			} else if (tMaxY < tMaxZ) {
				tn = tMaxY;
				y += stepY;
				tMaxY += invY;
			} else {
				tn = tMaxZ;
				z += stepZ;
				tMaxZ += invZ;
			}
			if (tn > maxLen) break;
			if (y < minY || y >= maxY) break; // left the world vertically: nothing more to hit
			if (x == tx && y == ty && z == tz) break; // arrived at the emitter block

			// The exit of the current block is the next crossing, whichever it is.
			double exitD = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
			double len = Math.min(exitD, maxLen) - tn;
			if (len > 0) {
				VoxelSection s = section(x, y, z);
				if (s != null && s.loadState == VoxelSection.READY && (s.opaque != null || s.ptype != null
						|| s.foliage != null || s.glass != null)) {
					double lx = ox + dx * tn - ((x >> 4) << 4);
					double ly = oy + dy * tn - ((y >> 4) << 4);
					double lz = oz + dz * tn - ((z >> 4) << 4);
					int bi = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
					if (!s.attenuate(bi, lx, ly, lz, dx, dy, dz, len, acc)) {
						acc[0] = acc[1] = acc[2] = 0f;
						break;
					}
				}
			}
		}
		trR = acc[0];
		trG = acc[1];
		trB = acc[2];
	}
}
