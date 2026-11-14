package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * Turns one VoxelSection into an overlay mesh.
 *
 * Every exposed block face is shaded at true texture-pixel resolution: the face is treated as a
 * 16x16 grid (one cell = one texture pixel) and refined with a quadtree. Each region's four
 * corners are traced towards the sun / nearby emitters; identical corners merge into one quad,
 * anything that differs subdivides further, down to single-pixel quads along shadow edges.
 * Flat-lit faces stay one quad, so the cost concentrates exactly where detail lives.
 * Corner results are memoised by exact world position (1/16-block fixed point), keeping
 * neighbouring quads seam-free.
 *
 * Rays resolve on the per-block 16x16x16 sub-voxel grid and carry an RGB transmittance, so
 * shadows from partial shapes (stairs, slabs, fences, panes...) are true to their silhouette
 * and stained glass tints both the sun shadows and the torch glow beneath it. The per-channel
 * darkness collapses into one translucent quad: channels that pass more light lift the overlay
 * colour, which reads as coloured light inside the shadow.
 */
final class SectionBaker {
	private static final float EPS = 0.003f;
	private static final int MAX_CAND = 128;
	/** Texture-pixel resolution: subdivisions per block face axis. */
	private static final int RES = 16;
	private static final float RESF = 1f / RES;
	private static final int[] UC = {0, 1, 1, 0}, VC = {0, 0, 1, 1};

	// Face order: DOWN, UP, NORTH(-Z), SOUTH(+Z), WEST(-X), EAST(+X)
	private static final int[] DX = {0, 0, 0, 0, -1, 1};
	private static final int[] DY = {-1, 1, 0, 0, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1, 0, 0};
	/** Counter-clockwise (seen from outside) corner offsets of each face: O, O+U, O+U+V, O+V. */
	private static final int[][][] FACE = {
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
		{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
		{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
		{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
		{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
	};

	// Warm light colour (normalised)
	private static final float[] WARMN = {1f, 176f / 255f, 96f / 255f};

	private final VoxelLighting vl;
	private BufferBuilder builder;

	// ------------------------------------------------------------------ corner cache
	// Flat open-addressing table with structure-of-arrays values: no per-corner objects, no
	// pointer chasing, zero allocation while tracing. Sentinel -1 in cSunR / cBR marks an
	// inserted-but-not-yet-traced corner.

	private long[] cKey = new long[1 << 13];
	private byte[] cUsed = new byte[1 << 13];
	private float[] cSunR = new float[1 << 13], cSunG = new float[1 << 13], cSunB = new float[1 << 13];
	private float[] cBR = new float[1 << 13], cBG = new float[1 << 13], cBB = new float[1 << 13], cP = new float[1 << 13];
	private int cMask = (1 << 13) - 1;
	private int cCount;

	private static int mix(long k) {
		k ^= k >>> 33;
		k *= 0xff51afd7ed558ccdL;
		k ^= k >>> 33;
		k *= 0xc4ceb9fe1a85ec53L;
		k ^= k >>> 33;
		return (int) k;
	}

	/** Slot for a fixed-point corner position; inserts (with -1 sentinels) when absent. */
	private int cornerSlot(long key) {
		if (cCount * 3 >= cKey.length * 2) grow();
		int i = mix(key) & cMask;
		while (cUsed[i] != 0) {
			if (cKey[i] == key) return i;
			i = (i + 1) & cMask;
		}
		cUsed[i] = 1;
		cKey[i] = key;
		cSunR[i] = -1f;
		cBR[i] = -1f;
		cCount++;
		return i;
	}

	private void grow() {
		long[] oKey = cKey;
		byte[] oUsed = cUsed;
		float[] oSunR = cSunR, oSunG = cSunG, oSunB = cSunB;
		float[] oBR = cBR, oBG = cBG, oBB = cBB, oP = cP;
		int old = oKey.length;
		int cap = old << 1;
		cKey = new long[cap];
		cUsed = new byte[cap];
		cSunR = new float[cap];
		cSunG = new float[cap];
		cSunB = new float[cap];
		cBR = new float[cap];
		cBG = new float[cap];
		cBB = new float[cap];
		cP = new float[cap];
		cMask = cap - 1;
		cCount = 0;
		for (int j = 0; j < old; j++) {
			if (oUsed[j] == 0) continue;
			long key = oKey[j];
			int i = mix(key) & cMask;
			while (cUsed[i] != 0) i = (i + 1) & cMask;
			cUsed[i] = 1;
			cKey[i] = key;
			cSunR[i] = oSunR[j];
			cSunG[i] = oSunG[j];
			cSunB[i] = oSunB[j];
			cBR[i] = oBR[j];
			cBG[i] = oBG[j];
			cBB[i] = oBB[j];
			cP[i] = oP[j];
			cCount++;
		}
	}

	SectionBaker(VoxelLighting vl) {
		this.vl = vl;
	}

	/** Fixed-point corner position (1/16-block units). No practical collisions within a loaded area. */
	private static long vkey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFF) << 16) | (long) (y & 0xFFFF);
	}

	// Emitter candidates for the section being baked
	private final float[] cx = new float[MAX_CAND], cy = new float[MAX_CAND], cz = new float[MAX_CAND], cl = new float[MAX_CAND];
	private final int[] cbx = new int[MAX_CAND], cby = new int[MAX_CAND], cbz = new int[MAX_CAND];
	private int candCount;

	// Small top-K scratch
	private final float[] bestStr = new float[8];
	private final int[] bestIdx = new int[8];

	// Scratch while baking one section / one face
	private VtConfig cfg;
	private boolean sunOn, lightOn;
	private float strength, lx, ly, lz;
	private int minSub;
	// Face-local quadtree state
	private int fKx0, fKy0, fKz0;
	private int fUx, fUy, fUz, fVx, fVy, fVz;
	private float fDot;
	// Quad accumulation
	private int[] qPos = new int[16384];
	private int[] qSub = new int[16384];
	private int[] qCol = new int[16384 * 4]; // 4 vertex colours per quad
	private int quads;

	void bake(VoxelSection s, VtConfig cfg) {
		if (s.loadState == VoxelSection.NOT_TRIED) s.load(vl.level);
		s.dirty = false;
		s.urgent = false;
		s.bakedKey = vl.sunKey;

		if (s.loadState != VoxelSection.READY || s.opaque == null) {
			s.clearMesh();
			return;
		}

		final boolean sunOn = vl.sunKey != VoxelLighting.NO_SUN && vl.sunStrength > 0.01f;
		gatherEmitters(s, cfg);
		final boolean lightOn = candCount > 0;
		if (!sunOn && !lightOn) {
			s.clearMesh();
			return;
		}

		// reset the flat corner cache (keep grown capacity)
		Arrays.fill(cUsed, (byte) 0);
		cCount = 0;

		this.cfg = cfg;
		this.sunOn = sunOn;
		this.lightOn = lightOn;
		this.strength = vl.sunStrength;
		this.lx = vl.lx;
		this.ly = vl.ly;
		this.lz = vl.lz;
		this.minSub = cfg.crispShadows ? 1 : 2;

		final int minY = vl.level.getMinBuildHeight(), maxY = vl.level.getMaxBuildHeight();
		final int bx = s.sx << 4, by = s.sy << 4, bz = s.sz << 4;
		final long[] op = s.opaque;

		quads = 0;
		for (int w = 0; w < 64; w++) {
			long bits = op[w];
			while (bits != 0L) {
				int bit = Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				int idx = (w << 6) | bit;
				int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;

				for (int d = 0; d < 6; d++) {
					int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
					int wy = by + ny;
					if (wy < minY || wy >= maxY) continue;
					boolean inside = nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16;
					if (inside) {
						int ni = (ny << 8) | (nz << 4) | nx;
						if ((op[ni >>> 6] & (1L << ni)) != 0L) continue;   // face buried against another solid
						// face fully covered by a partial block's shape (wall, stair back, fence post...)
						if (s.ptype != null && (s.ptype[ni] & 255) == VoxelSection.T_PARTIAL_SOLID) {
							VoxelSection.Mask m = s.masks.get((s.pmask[ni] & 255) - 1);
							if ((m.covered & (1 << (d ^ 1))) != 0) continue;
						}
					} else if (vl.isOpaque(bx + nx, wy, bz + nz)) {
						continue;
					}

					emitFace(bx + x, by + y, bz + z, d, x | (y << 4) | (z << 8) | (d << 12));
				}
			}
		}

		upload(s);
	}

	/** Sets up face-local quadtree state and refines the whole 16x16 grid. */
	private void emitFace(int ox, int oy, int oz, int d, int basePos) {
		int[] O = FACE[d][0];
		int[] F1 = FACE[d][1], F3 = FACE[d][3];
		fUx = F1[0] - O[0];
		fUy = F1[1] - O[1];
		fUz = F1[2] - O[2];
		fVx = F3[0] - O[0];
		fVy = F3[1] - O[1];
		fVz = F3[2] - O[2];
		fKx0 = ((ox + O[0]) << 4);
		fKy0 = ((oy + O[1]) << 4);
		fKz0 = ((oz + O[2]) << 4);
		fDot = DX[d] * lx + DY[d] * ly + DZ[d] * lz;
		emitRegion(basePos, 0, 0, RES);
	}

	/**
	 * Quadtree over the face's 16x16 texel grid: (u,v) = origin in texels, s = region size.
	 * Regions whose four corners trace to the same colour merge into one quad; anything that
	 * differs keeps subdividing down to single texels.
	 */
	private void emitRegion(int basePos, int u, int v, int s) {
		int c00 = cornerLight(fKx0 + u * fUx + v * fVx, fKy0 + u * fUy + v * fVy, fKz0 + u * fUz + v * fVz);
		int c10 = cornerLight(fKx0 + (u + s) * fUx + v * fVx, fKy0 + (u + s) * fUy + v * fVy, fKz0 + (u + s) * fUz + v * fVz);
		int c11 = cornerLight(fKx0 + (u + s) * fUx + (v + s) * fVx, fKy0 + (u + s) * fUy + (v + s) * fVy, fKz0 + (u + s) * fUz + (v + s) * fVz);
		int c01 = cornerLight(fKx0 + u * fUx + (v + s) * fVx, fKy0 + u * fUy + (v + s) * fVy, fKz0 + u * fUz + (v + s) * fVz);

		if (s <= minSub) {
			if ((c00 | c10 | c11 | c01) != 0)
				addQuad(basePos, u | (v << 5) | (s << 10), c00, c10, c11, c01);
			return;
		}
		if (c00 == c10 && c10 == c11 && c11 == c01) {
			// Four equal corners can still hide a light pool or a thin shadow crossing the
			// middle: verify the centre before merging the whole region.
			int h = s >> 1;
			int cc = cornerLight(fKx0 + (u + h) * fUx + (v + h) * fVx,
					fKy0 + (u + h) * fUy + (v + h) * fVy, fKz0 + (u + h) * fUz + (v + h) * fVz);
			if (cc == c00) {
				if (c00 != 0)
					addQuad(basePos, u | (v << 5) | (s << 10), c00, c10, c11, c01);
				return;
			}
		}
		int h = s >> 1;
		emitRegion(basePos, u, v, h);
		emitRegion(basePos, u + h, v, h);
		emitRegion(basePos, u + h, v + h, h);
		emitRegion(basePos, u, v + h, h);
	}

	/**
	 * Traces sun + block light for one corner (fixed-point 1/16-block coords) and returns the
	 * packed ARGB overlay colour, or 0 when nothing is visible.
	 */
	private int cornerLight(int kx, int ky, int kz) {
		float wx = kx * RESF, wy = ky * RESF, wz = kz * RESF;

		// ---- sun (per channel)
		float sr = 0f, sg = 0f, sb = 0f;
		if (sunOn) {
			if (fDot <= 0f) {
				sr = sg = sb = cfg.selfShade * strength;
			} else {
				int slot = cornerSlot(vkey(kx, ky, kz));
				if (cSunR[slot] < 0f) {
					vl.trace(wx, wy, wz, lx, ly, lz, cfg.quality.rayRange, VoxelLighting.NO_TARGET, 0, 0);
					cSunR[slot] = vl.trR;
					cSunG[slot] = vl.trG;
					cSunB[slot] = vl.trB;
				}
				// S-curve on the RAW occlusion of the worst channel, then scale by
				// shadowStrength: a full shadow stops at shadowStrength (0.42) instead of
				// saturating to opaque black, while per-channel ratios (glass tint) survive.
				float oR = 1f - cSunR[slot], oG = 1f - cSunG[slot], oB = 1f - cSunB[slot];
				float oMax = Math.max(oR, Math.max(oG, oB));
				float amt = 0f;
				if (oMax > 1e-4f) amt = cfg.shadowStrength * strength * crisp(oMax) / oMax;
				sr = amt * oR;
				sg = amt * oG;
				sb = amt * oB;
			}
		}

		// ---- block light (per channel)
		float br = 0f, bg = 0f, bb = 0f, p = 0f;
		if (lightOn) {
			int slot = cornerSlot(vkey(kx, ky, kz));
			if (cBR[slot] < 0f) vertexLight(slot, wx, wy, wz);
			br = cBR[slot];
			bg = cBG[slot];
			bb = cBB[slot];
			p = cP[slot];
		}

		// Per-channel darkness (sun occlusion + torch occlusion) and warm glow.
		// The torch-leak term only applies where the sun isn't shining on this face: during
		// the day vanilla's sky light hides the leak, so darkening it would paint phantom
		// patches onto sunlit walls.
		float tOcc = (sunOn && fDot > 0f) ? 0f : cfg.torchShadow;
		float dr = 1f - (1f - sr * (1f - br)) * (1f - Math.max(0f, p - br) * tOcc);
		float dg = 1f - (1f - sg * (1f - bg)) * (1f - Math.max(0f, p - bg) * tOcc);
		float db = 1f - (1f - sb * (1f - bb)) * (1f - Math.max(0f, p - bb) * tOcc);
		float wr = br * cfg.warmStrength * WARMN[0];
		float wg = bg * cfg.warmStrength * WARMN[1];
		float wb = bb * cfg.warmStrength * WARMN[2];

		float aD = Math.max(dr, Math.max(dg, db));
		float wMax = Math.max(wr, Math.max(wg, wb));
		float a = Math.min(0.8f, aD + wMax);
		if (a < 0.02f) return 0;

		// Channels that pass more light lift the overlay colour: that reads as coloured light
		// inside the shadow. Warm glow adds its own tint.
		float rr = clamp01((aD - dr) / a + wr / a);
		float gr = clamp01((aD - dg) / a + wg / a);
		float br2 = clamp01((aD - db) / a + wb / a);
		return ((int) (rr * 255f))
				| ((int) (gr * 255f) << 8)
				| ((int) (br2 * 255f) << 16)
				| ((int) (a * 255f) << 24);
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : v > 1f ? 1f : v;
	}

	/**
	 * Contrast (S-)curve on RAW sun occlusion (0..1). Below ~0.03 it becomes invisible,
	 * above ~0.18 it saturates to a full shadow; the caller then scales by shadowStrength,
	 * so a full shadow lands at shadowStrength (never opaque black) and per-channel
	 * ratios (glass tint) are preserved.
	 */
	private static float crisp(float x) {
		if (!VtConfig.get().crispShadows) return x;
		float t = clamp01((x - 0.03f) / 0.15f);
		return t * t * (3f - 2f * t);
	}

	// ------------------------------------------------------------------ light

	private void gatherEmitters(VoxelSection s, VtConfig cfg) {
		candCount = 0;
		if (!cfg.blockLight) return;
		float x0 = s.sx << 4, y0 = s.sy << 4, z0 = s.sz << 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					VoxelSection n = (dx | dy | dz) == 0 ? s : vl.getSection(s.sx + dx, s.sy + dy, s.sz + dz, true);
					if (n == null || n.loadState != VoxelSection.READY || n.emitterCount == 0) continue;
					for (int k = 0; k < n.emitterCount; k++) {
						int v = n.emitters[k];
						int lvl = (v >> 12) & 15;
						int ex = (n.sx << 4) + (v & 15);
						int ey = (n.sy << 4) + ((v >> 8) & 15);
						int ez = (n.sz << 4) + ((v >> 4) & 15);
						float px = ex + 0.5f, py = ey + 0.5f, pz = ez + 0.5f;
						float ddx = Math.max(Math.max(x0 - px, 0f), px - (x0 + 16f));
						float ddy = Math.max(Math.max(y0 - py, 0f), py - (y0 + 16f));
						float ddz = Math.max(Math.max(z0 - pz, 0f), pz - (z0 + 16f));
						if (ddx * ddx + ddy * ddy + ddz * ddz >= (float) (lvl * lvl)) continue;
						if (candCount >= MAX_CAND) return;
						cx[candCount] = px;
						cy[candCount] = py;
						cz[candCount] = pz;
						cl[candCount] = lvl;
						cbx[candCount] = ex;
						cby[candCount] = ey;
						cbz[candCount] = ez;
						candCount++;
					}
				}
			}
		}
	}

	/** Traces the nearby emitters for one corner (writes cBR/cBG/cBB/cP at its cache slot). */
	private void vertexLight(int slot, float ox, float oy, float oz) {
		final int kmax = Math.min(cfg.quality.lightRays, 8);
		int k = 0;
		for (int c = 0; c < candCount; c++) {
			float dx = cx[c] - ox, dy = cy[c] - oy, dz = cz[c] - oz;
			float d2 = dx * dx + dy * dy + dz * dz;
			float lvl = cl[c];
			if (d2 >= lvl * lvl) continue;
			float str = (lvl - (float) Math.sqrt(d2)) / 15f;
			if (k < kmax || str > bestStr[k - 1]) {
				int pos = k < kmax ? k++ : kmax - 1;
				while (pos > 0 && bestStr[pos - 1] < str) {
					bestStr[pos] = bestStr[pos - 1];
					bestIdx[pos] = bestIdx[pos - 1];
					pos--;
				}
				bestStr[pos] = str;
				bestIdx[pos] = c;
			}
		}

		float keepB_r = 1f, keepB_g = 1f, keepB_b = 1f, keepP = 1f;
		for (int i = 0; i < k; i++) {
			int c = bestIdx[i];
			float str = bestStr[i];
			float tr = 1f, tg = 1f, tb = 1f;
			if (!(cbx[c] == Mth.floor(ox) && cby[c] == Mth.floor(oy) && cbz[c] == Mth.floor(oz))) {
				float dx = cx[c] - ox, dy = cy[c] - oy, dz = cz[c] - oz;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float inv = 1f / dist;
				vl.trace(ox, oy, oz, dx * inv, dy * inv, dz * inv, dist, cbx[c], cby[c], cbz[c]);
				tr = vl.trR;
				tg = vl.trG;
				tb = vl.trB;
			}
			keepP *= 1f - str;
			keepB_r *= 1f - str * tr;
			keepB_g *= 1f - str * tg;
			keepB_b *= 1f - str * tb;
		}
		cBR[slot] = 1f - keepB_r;
		cBG[slot] = 1f - keepB_g;
		cBB[slot] = 1f - keepB_b;
		cP[slot] = 1f - keepP;
	}

	// ------------------------------------------------------------------ mesh

	private void addQuad(int pos, int sub, int c0, int c1, int c2, int c3) {
		if (quads == qPos.length) {
			qPos = Arrays.copyOf(qPos, quads * 2);
			qSub = Arrays.copyOf(qSub, quads * 2);
			qCol = Arrays.copyOf(qCol, quads * 8);
		}
		qPos[quads] = pos;
		qSub[quads] = sub;
		qCol[quads * 4] = c0;
		qCol[quads * 4 + 1] = c1;
		qCol[quads * 4 + 2] = c2;
		qCol[quads * 4 + 3] = c3;
		quads++;
	}

	private void upload(VoxelSection s) {
		if (quads == 0) {
			s.clearMesh();
			return;
		}
		if (builder == null) builder = new BufferBuilder(1 << 16);
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		for (int q = 0; q < quads; q++) {
			int p = qPos[q];
			int x = p & 15, y = (p >> 4) & 15, z = (p >> 8) & 15, d = (p >> 12) & 7;
			int sub = qSub[q];
			int u = sub & 31, v = (sub >> 5) & 31, sz = (sub >> 10) & 63;
			int[] O = FACE[d][0];
			int[] F1 = FACE[d][1], F3 = FACE[d][3];
			int ux = F1[0] - O[0], uy = F1[1] - O[1], uz = F1[2] - O[2];
			int vx = F3[0] - O[0], vy = F3[1] - O[1], vz = F3[2] - O[2];
			float ox = DX[d] * EPS, oy = DY[d] * EPS, oz = DZ[d] * EPS;
			for (int k = 0; k < 4; k++) {
				float fu = (u + UC[k] * sz) * RESF, fv = (v + VC[k] * sz) * RESF;
				float px = x + O[0] + fu * ux + fv * vx;
				float py = y + O[1] + fu * uy + fv * vy;
				float pz = z + O[2] + fu * uz + fv * vz;
				int c = qCol[q * 4 + k];
				int r = c & 255, g = (c >> 8) & 255, b = (c >> 16) & 255, a = (c >>> 24) & 255;
				builder.vertex(px + ox, py + oy, pz + oz)
						.color(r, g, b, a)
						.endVertex();
			}
		}
		BufferBuilder.RenderedBuffer rb = builder.end();
		if (s.vb == null) s.vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
		s.vb.bind();
		s.vb.upload(rb);
		VertexBuffer.unbind();
		s.quadCount = quads;
	}
}
