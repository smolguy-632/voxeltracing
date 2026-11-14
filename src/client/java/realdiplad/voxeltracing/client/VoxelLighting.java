package realdiplad.voxeltracing.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * World access for the tracer plus the bake scheduler: keeps section snapshots, tracks the sun,
 * and re-bakes overlay meshes nearest-first within a per-frame time budget.
 */
public final class VoxelLighting {
	public static final VoxelLighting INSTANCE = new VoxelLighting();

	static final int NO_SUN = -1;
	static final int EMIT_MIN = 8;
	private static final long CHUNK_SETTLE_NS = 1_200_000_000L;
	private static final long EDIT_SETTLE_NS = 150_000_000L;

	final Long2ObjectOpenHashMap<Sect> sections = new Long2ObjectOpenHashMap<>();
	ClientLevel level;
	int minY, maxY, minSec, maxSec;

	int sunKey = Integer.MIN_VALUE;
	float sunStrength;
	float daylight;
	float lx = 0f, ly = 1f, lz = 0f;
	float sunR = 1f, sunG = 0.95f, sunB = 0.85f;

	private final Baker baker = new Baker(this);
	private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
	private final ArrayList<Sect> queue = new ArrayList<>();
	private int qi;
	private int camSx = Integer.MIN_VALUE, camSy, camSz;
	private boolean needsScan = true;
	private boolean waiting;
	private long lastScan;

	private Sect cacheS;
	private int cSx, cSy, cSz;

	private VoxelLighting() {
	}

	// ------------------------------------------------------------------ world access (used by tracing)

	/** The ready section at these section coordinates, or null (outside the world / chunk not loaded). */
	Sect sect(int sx, int sy, int sz) {
		if (sy < minSec || sy >= maxSec || level == null) return null;
		long k = SectionPos.asLong(sx, sy, sz);
		Sect s = sections.get(k);
		if (s == null) {
			s = new Sect(sx, sy, sz);
			sections.put(k, s);
		}
		if (s.state == Sect.PENDING) s.load(level, EMIT_MIN);
		return s.state == Sect.READY ? s : null;
	}

	Optics optAt(int x, int y, int z) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		Sect s;
		if (cacheS != null && sx == cSx && sy == cSy && sz == cSz) {
			s = cacheS;
		} else {
			s = sect(sx, sy, sz);
			cacheS = s;
			cSx = sx;
			cSy = sy;
			cSz = sz;
		}
		if (s == null || s.empty) return Optics.NOTHING;
		int id = s.ids == null ? s.uniform : s.ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
		return Optics.byIdx[id];
	}

	int skyLight(int x, int y, int z) {
		return level.getBrightness(LightLayer.SKY, mp.set(x, y, z));
	}

	int blockLight(int x, int y, int z) {
		return level.getBrightness(LightLayer.BLOCK, mp.set(x, y, z));
	}

	// ------------------------------------------------------------------ per frame

	public void beginFrame(ClientLevel lvl, Camera cam, float tickDelta) {
		VtConfig cfg = VtConfig.get();
		if (!cfg.enabled || lvl == null) return;
		if (level != lvl) {
			reset();
			level = lvl;
			minY = lvl.getMinBuildHeight();
			maxY = lvl.getMaxBuildHeight();
			minSec = lvl.getMinSection();
			maxSec = lvl.getMaxSection();
			needsScan = true;
		}
		updateSun(lvl, cfg, tickDelta);

		Vec3 p = cam.getPosition();
		int sx = Mth.floor(p.x) >> 4, sy = Mth.floor(p.y) >> 4, sz = Mth.floor(p.z) >> 4;
		long now = System.nanoTime();
		if (sx != camSx || sy != camSy || sz != camSz) {
			camSx = sx;
			camSy = sy;
			camSz = sz;
			needsScan = true;
		}
		if (waiting && now - lastScan > 400_000_000L) needsScan = true;
		if (needsScan) {
			needsScan = false;
			scan(cfg, now);
		}
		drain(cfg);
	}

	private void updateSun(ClientLevel lvl, VtConfig cfg, float tickDelta) {
		DimensionType dt = lvl.dimensionType();
		int key = NO_SUN;
		float strength = 0f;
		float day = 0.5f;
		if (dt.hasSkyLight() && !dt.hasCeiling()) {
			float tod = lvl.getTimeOfDay(tickDelta);
			int steps = cfg.quality.sunSteps;
			int step = Mth.clamp((int) (tod * steps), 0, steps - 1);
			float angle = (step + 0.5f) / steps * Mth.TWO_PI;
			float x = -Mth.sin(angle), y = Mth.cos(angle), z = cfg.sunTilt;
			float inv = 1f / Mth.sqrt(x * x + y * y + z * z);
			x *= inv;
			y *= inv;
			z *= inv;
			day = smooth(-0.08f, 0.30f, y);
			if (cfg.sunShadows && y > 0.06f) {
				key = step;
				strength = smooth(0.06f, 0.40f, y);
				lx = x;
				ly = y;
				lz = z;
				float warm = smooth(0.05f, 0.50f, y);
				sunR = 1f;
				sunG = 0.62f + 0.35f * warm;
				sunB = 0.35f + 0.55f * warm;
			}
		}
		daylight = day;
		sunStrength = strength;
		if (key != sunKey) {
			sunKey = key;
			needsScan = true;
		}
	}

	private static float smooth(float a, float b, float v) {
		float t = Mth.clamp((v - a) / (b - a), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	// ------------------------------------------------------------------ scheduling

	private void scan(VtConfig cfg, long now) {
		VtConfig.Quality q = cfg.quality;
		int hr = q.horizontalRadius, vr = q.verticalRadius;
		int margin = (q.shadowRange >> 4) + 2;
		lastScan = now;
		waiting = false;
		cacheS = null;

		ObjectIterator<Sect> it = sections.values().iterator();
		while (it.hasNext()) {
			Sect s = it.next();
			if (Math.abs(s.sx - camSx) > hr + margin || Math.abs(s.sz - camSz) > hr + margin
					|| Math.abs(s.sy - camSy) > vr + margin) {
				s.close();
				it.remove();
			}
		}

		queue.clear();
		qi = 0;
		for (int dx = -hr; dx <= hr; dx++) {
			for (int dz = -hr; dz <= hr; dz++) {
				for (int dy = -vr; dy <= vr; dy++) {
					int sy = camSy + dy;
					if (sy < minSec || sy >= maxSec) continue;
					long k = SectionPos.asLong(camSx + dx, sy, camSz + dz);
					Sect s = sections.get(k);
					if (s == null) {
						s = new Sect(camSx + dx, sy, camSz + dz);
						sections.put(k, s);
					}
					if (!s.dirty && s.bakedKey == sunKey) continue;
					if (s.notBefore > now) {
						waiting = true;
						continue;
					}
					long d2 = (long) dx * dx + (long) dz * dz + (long) dy * dy * 2L;
					s.sortKey = (s.urgent ? 0L : 1_000_000L) + d2;
					queue.add(s);
				}
			}
		}
		queue.sort(Comparator.comparingLong(a -> a.sortKey));
	}

	private void drain(VtConfig cfg) {
		long deadline = System.nanoTime() + (long) (cfg.quality.bakeBudgetMs * 1_000_000.0);
		while (qi < queue.size()) {
			Sect s = queue.get(qi++);
			if (s.removed || (!s.dirty && s.bakedKey == sunKey)) continue;
			baker.bake(s, cfg);
			if (System.nanoTime() > deadline) break;
		}
	}

	// ------------------------------------------------------------------ change hooks

	public void onBlockChanged(ClientLevel lvl, BlockPos pos, BlockState oldState, BlockState newState) {
		if (level == null || lvl != level) return;
		Optics a = Optics.of(oldState), b = Optics.of(newState);
		if (a.sameAs(b)) return;
		cacheS = null;

		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		Sect own = sections.get(SectionPos.asLong(sx, sy, sz));
		if (own != null) own.state = Sect.PENDING;

		VtConfig.Quality q = VtConfig.get().quality;
		int r = Math.max(1, (Math.max(Math.max(q.shadowRange, q.giRange), q.lightRadius) + 15) >> 4);
		long settle = System.nanoTime() + EDIT_SETTLE_NS;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -r; dy <= r; dy++) {
					Sect s = sections.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
					if (s == null) continue;
					s.dirty = true;
					s.urgent = Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1;
					s.notBefore = settle;
				}
			}
		}
		needsScan = true;
	}

	public void onChunkLoad(ClientLevel lvl, int cx, int cz) {
		if (level == null || lvl != level) return;
		cacheS = null;
		long settle = System.nanoTime() + CHUNK_SETTLE_NS;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int sy = minSec; sy < maxSec; sy++) {
					Sect s = sections.get(SectionPos.asLong(cx + dx, sy, cz + dz));
					if (s == null) continue;
					if ((dx == 0 && dz == 0) || s.state == Sect.MISSING) s.state = Sect.PENDING;
					s.dirty = true;
					s.notBefore = settle;
				}
			}
		}
		needsScan = true;
	}

	public void onChunkUnload(ClientLevel lvl, int cx, int cz) {
		if (level == null || lvl != level) return;
		cacheS = null;
		for (int sy = minSec; sy < maxSec; sy++) {
			Sect s = sections.remove(SectionPos.asLong(cx, sy, cz));
			if (s != null) s.close();
		}
		needsScan = true;
	}

	public void invalidateAll() {
		cacheS = null;
		for (Sect s : sections.values()) {
			s.dirty = true;
			s.notBefore = 0L;
		}
		sunKey = Integer.MIN_VALUE;
		needsScan = true;
	}

	public void reset() {
		for (Sect s : sections.values()) s.close();
		sections.clear();
		queue.clear();
		qi = 0;
		cacheS = null;
		level = null;
		sunKey = Integer.MIN_VALUE;
		camSx = Integer.MIN_VALUE;
		needsScan = true;
		Optics.reset();
	}
}
