package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * Turns one VoxelSection into an overlay mesh.
 *
 * Every exposed block face is split into a 4x4 grid of sub-quads (one sub-quad = 1/4 block =
 * 4 texture pixels). Light is traced at each sub-quad corner: a ray towards the sun and rays to
 * the nearby emitters. Corner results are memoised by exact world position (1/4-block fixed
 * point), so corners shared by neighbouring sub-quads, faces and blocks are traced exactly once
 * and neighbouring quads stay seam-free while shadow edges stay sharp at 1/4-block resolution.
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
	/** Sub-quads per block face axis (4x4 = 16 sub-quads of 1/4 block each). */
	private static final int SUB = 4;
	private static final float SUBF = 1f / SUB;
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

	/** Per-bake cache: fixed-point corner position (x4) -> traced light. */
	private final Long2ObjectOpenHashMap<Vert> vcache = new Long2ObjectOpenHashMap<>();

	private int[] qPos = new int[16384];
	private int[] qCol = new int[16384 * 4]; // 4 vertex colours per quad
	private int quads;

	// Emitter candidates for the section being baked
	private final float[] cx = new float[MAX_CAND], cy = new float[MAX_CAND], cz = new float[MAX_CAND], cl = new float[MAX_CAND];
	private final int[] cbx = new int[MAX_CAND], cby = new int[MAX_CAND], cbz = new int[MAX_CAND];
	private int candCount;

	// Small top-K scratch
	private final float[] bestStr = new float[8];
	private final int[] bestIdx = new int[8];

	// Scratch while baking one section
	private VtConfig cfg;
	private boolean sunOn, lightOn;
	private float strength, lx, ly, lz;

	/** Traced light of one corner vertex. A -1 sentinel on sunR / bR means "not computed yet". */
	private static final class Vert {
		float sunR = -1f, sunG, sunB; // transmittance towards the sun, per channel
		float bR = -1f, bG, bB;       // torch light that actually arrives, per channel
		float p;                      // torch light that would arrive with no blockers
	}

	SectionBaker(VoxelLighting vl) {
		this.vl = vl;
	}

	/** Fixed-point corner position (1/4-block units), packed like BlockPos.asLong. */
	private static long vkey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
	}

	private Vert vertex(int x, int y, int z) {
		long k = vkey(x, y, z);
		Vert v = vcache.get(k);
		if (v == null) {
			v = new Vert();
			vcache.put(k, v);
		}
		return v;
	}

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

		vcache.clear();

		this.cfg = cfg;
		this.sunOn = sunOn;
		this.lightOn = lightOn;
		this.strength = vl.sunStrength;
		this.lx = vl.lx;
		this.ly = vl.ly;
		this.lz = vl.lz;

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
					} else if (vl.isOpaque(bx + nx, wy, bz + nz)) {
						continue;
					}

					emitFace(bx + x, by + y, bz + z, d, x | (y << 4) | (z << 8) | (d << 12));
				}
			}
		}

		upload(s);
	}

	/** Emits the SUB x SUB sub-quads of one exposed block face at world block position (ox,oy,oz). */
	private void emitFace(int ox, int oy, int oz, int d, int basePos) {
		int[] O = FACE[d][0];
		int[] F1 = FACE[d][1], F3 = FACE[d][3];
		int ux = F1[0] - O[0], uy = F1[1] - O[1], uz = F1[2] - O[2];
		int vx = F3[0] - O[0], vy = F3[1] - O[1], vz = F3[2] - O[2];
		// Face origin in 1/4-block fixed point
		int kx0 = (ox << 2) + (O[0] << 2);
		int ky0 = (oy << 2) + (O[1] << 2);
		int kz0 = (oz << 2) + (O[2] << 2);
		float dot = DX[d] * lx + DY[d] * ly + DZ[d] * lz;

		for (int si = 0; si < SUB * SUB; si++) {
			int i = si & (SUB - 1), j = si / SUB;
			int iu = i * ux, iuy = i * uy, iuz = i * uz;
			int jv = j * vx, jvy = j * vy, jvz = j * vz;
			int c0 = cornerLight(kx0 + iu + jv, ky0 + iuy + jvy, kz0 + iuz + jvz, dot);
			int c1 = cornerLight(kx0 + iu + ux + jv, ky0 + iuy + uy + jvy, kz0 + iuz + uz + jvz, dot);
			int c2 = cornerLight(kx0 + iu + ux + jv + vx, ky0 + iuy + uy + jvy + vy, kz0 + iuz + uz + jvz + vz, dot);
			int c3 = cornerLight(kx0 + iu + jv + vx, ky0 + iuy + jvy + vy, kz0 + iuz + jvz + vz, dot);
			if ((c0 | c1 | c2 | c3) == 0) continue;
			addQuad(basePos | (si << 15), c0, c1, c2, c3);
		}
	}

	/**
	 * Traces sun + block light for one corner (fixed-point 1/4-block coords) and returns the
	 * packed ARGB overlay colour, or 0 when nothing is visible.
	 */
	private int cornerLight(int kx, int ky, int kz, float dot) {
		float wx = kx * SUBF, wy = ky * SUBF, wz = kz * SUBF;

		// ---- sun (per channel)
		float sr = 0f, sg = 0f, sb = 0f;
		if (sunOn) {
			if (dot <= 0f) {
				sr = sg = sb = cfg.selfShade;
			} else {
				Vert vt = vertex(kx, ky, kz);
				if (vt.sunR < 0f) {
					vl.trace(wx, wy, wz, lx, ly, lz, cfg.quality.rayRange, VoxelLighting.NO_TARGET, 0, 0);
					vt.sunR = vl.trR;
					vt.sunG = vl.trG;
					vt.sunB = vl.trB;
				}
				sr = (1f - vt.sunR) * cfg.shadowStrength * strength;
				sg = (1f - vt.sunG) * cfg.shadowStrength * strength;
				sb = (1f - vt.sunB) * cfg.shadowStrength * strength;
			}
		}

		// ---- block light (per channel)
		float br = 0f, bg = 0f, bb = 0f, p = 0f;
		if (lightOn) {
			Vert vt = vertex(kx, ky, kz);
			if (vt.bR < 0f) vertexLight(wx, wy, wz, vt);
			br = vt.bR;
			bg = vt.bG;
			bb = vt.bB;
			p = vt.p;
		}

		// Per-channel darkness (sun occlusion + torch occlusion) and warm glow.
		float dr = 1f - (1f - sr * (1f - br)) * (1f - (p - br) * cfg.torchShadow);
		float dg = 1f - (1f - sg * (1f - bg)) * (1f - (p - bg) * cfg.torchShadow);
		float db = 1f - (1f - sb * (1f - bb)) * (1f - (p - bb) * cfg.torchShadow);
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

	/** Traces the nearby emitters from one corner vertex; fills v.b* (arrives) and v.p (no blockers). */
	private void vertexLight(float ox, float oy, float oz, Vert v) {
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
		v.bR = 1f - keepB_r;
		v.bG = 1f - keepB_g;
		v.bB = 1f - keepB_b;
		v.p = 1f - keepP;
	}

	// ------------------------------------------------------------------ mesh

	private void addQuad(int pos, int c0, int c1, int c2, int c3) {
		if (quads == qPos.length) {
			qPos = Arrays.copyOf(qPos, quads * 2);
			qCol = Arrays.copyOf(qCol, quads * 8);
		}
		qPos[quads] = pos;
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
			int x = p & 15, y = (p >> 4) & 15, z = (p >> 8) & 15, d = (p >> 12) & 7, si = (p >> 15) & 15;
			int i = si & (SUB - 1), j = si / SUB;
			int[] O = FACE[d][0];
			int[] F1 = FACE[d][1], F3 = FACE[d][3];
			int ux = F1[0] - O[0], uy = F1[1] - O[1], uz = F1[2] - O[2];
			int vx = F3[0] - O[0], vy = F3[1] - O[1], vz = F3[2] - O[2];
			float ox = DX[d] * EPS, oy = DY[d] * EPS, oz = DZ[d] * EPS;
			for (int v = 0; v < 4; v++) {
				float fu = (i + UC[v]) * SUBF, fv = (j + VC[v]) * SUBF;
				float px = x + O[0] + fu * ux + fv * vx;
				float py = y + O[1] + fu * uy + fv * vy;
				float pz = z + O[2] + fu * uz + fv * vz;
				int c = qCol[q * 4 + v];
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
