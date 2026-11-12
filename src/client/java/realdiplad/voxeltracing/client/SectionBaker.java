package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;

/**
 * Turns one VoxelSection into an overlay mesh.
 *
 * Lighting is evaluated per corner vertex, not per face: for every exposed face of every opaque
 * block, a voxel-ray is traced from each of the four corner positions (exact world-space block
 * corners) to the sun, and to the nearby emitters. Corner positions are shared by neighbouring
 * faces and blocks, so the results are memoised in a hashmap keyed by the packed corner position
 * for the duration of one section bake - every unique corner is traced exactly once and the cost
 * stays comparable to the old per-voxel memo while faces meet in smooth gradients.
 * Faces that end up visibly shadowed / lit become one translucent quad with per-vertex colour;
 * everything else emits nothing.
 */
final class SectionBaker {
	private static final float EPS = 0.003f;
	private static final int MAX_CAND = 128;

	// Face order: DOWN, UP, NORTH(-Z), SOUTH(+Z), WEST(-X), EAST(+X)
	private static final int[] DX = {0, 0, 0, 0, -1, 1};
	private static final int[] DY = {-1, 1, 0, 0, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1, 0, 0};
	/** Counter-clockwise (seen from outside) corner offsets of each face. */
	private static final int[][][] FACE = {
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
		{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
		{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
		{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
		{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
	};

	// Warm light colour
	private static final int WR = 255, WG = 176, WB = 96;

	private final VoxelLighting vl;
	private BufferBuilder builder;

	/** Per-bake cache: packed corner position -> traced light. Corners are shared, trace once. */
	private final Long2ObjectOpenHashMap<Vert> vcache = new Long2ObjectOpenHashMap<>();

	private int[] qPos = new int[8192];
	private int[] qCol = new int[8192 * 4]; // 4 vertex colours per quad
	private int quads;

	// Emitter candidates for the section being baked
	private final float[] cx = new float[MAX_CAND], cy = new float[MAX_CAND], cz = new float[MAX_CAND], cl = new float[MAX_CAND];
	private final int[] cbx = new int[MAX_CAND], cby = new int[MAX_CAND], cbz = new int[MAX_CAND];
	private int candCount;

	// Small top-K scratch
	private final float[] bestStr = new float[8];
	private final int[] bestIdx = new int[8];

	/** Traced light of one corner vertex. -1 means "not computed yet". */
	private static final class Vert {
		float sunT = -1f; // transmittance towards the sun
		float b = -1f;    // torch light that actually arrives
		float p;          // torch light that would arrive with no blockers
	}

	SectionBaker(VoxelLighting vl) {
		this.vl = vl;
	}

	/** BlockPos.asLong layout: 26 bits x, 12 bits y, 26 bits z. */
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

		final float strength = vl.sunStrength;
		final float lx = vl.lx, ly = vl.ly, lz = vl.lz;
		final float range = cfg.quality.rayRange;
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

					// ---- light the four corner vertices (shared corners come from the cache)
					float dot = DX[d] * lx + DY[d] * ly + DZ[d] * lz;
					int c0 = 0, c1 = 0, c2 = 0, c3 = 0;
					float maxSum = 0f;
					for (int v = 0; v < 4; v++) {
						int vx = bx + x + FACE[d][v][0];
						int vy = by + y + FACE[d][v][1];
						int vz = bz + z + FACE[d][v][2];

						// ---- sun (per vertex)
						float sunA = 0f;
						if (sunOn) {
							if (dot <= 0f) {
								sunA = cfg.selfShade;
							} else {
								Vert vt = vertex(vx, vy, vz);
								if (vt.sunT < 0f) {
									vt.sunT = vl.trace(vx, vy, vz, lx, ly, lz, range,
										VoxelLighting.NO_TARGET, 0, 0);
								}
								sunA = (1f - vt.sunT) * cfg.shadowStrength;
							}
							sunA *= strength;
						}

						// ---- block light (per vertex)
						float b = 0f, p = 0f;
						if (lightOn) {
							Vert vt = vertex(vx, vy, vz);
							if (vt.b < 0f) vertexLight(vx, vy, vz, vt, cfg);
							b = vt.b;
							p = vt.p;
						}

						float dark = 1f - (1f - sunA * (1f - b)) * (1f - (p - b) * cfg.torchShadow);
						float warm = b * cfg.warmStrength;
						float sum = dark + warm;
						if (sum > maxSum) maxSum = sum;

						int col = 0;
						if (sum >= 0.02f) {
							float a = Math.min(0.8f, sum);
							float wgt = warm / sum;
							col = ((int) (WR * wgt))
								| ((int) (WG * wgt) << 8)
								| ((int) (WB * wgt) << 16)
								| ((int) (a * 255f) << 24);
						}
						if (v == 0) c0 = col;
						else if (v == 1) c1 = col;
						else if (v == 2) c2 = col;
						else c3 = col;
					}
					if (maxSum < 0.02f) continue;

					addQuad(x | (y << 4) | (z << 8) | (d << 12), c0, c1, c2, c3);
				}
			}
		}

		upload(s);
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

	/** Traces the nearby emitters from one corner vertex; fills v.b (arrives) and v.p (no blockers). */
	private void vertexLight(int wx, int wy, int wz, Vert v, VtConfig cfg) {
		final float ox = wx, oy = wy, oz = wz;
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

		float keepB = 1f, keepP = 1f;
		for (int i = 0; i < k; i++) {
			int c = bestIdx[i];
			float str = bestStr[i];
			float t = 1f;
			if (!(cbx[c] == wx && cby[c] == wy && cbz[c] == wz)) {
				float dx = cx[c] - ox, dy = cy[c] - oy, dz = cz[c] - oz;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float inv = 1f / dist;
				t = vl.trace(ox, oy, oz, dx * inv, dy * inv, dz * inv, dist, cbx[c], cby[c], cbz[c]);
			}
			keepP *= 1f - str;
			keepB *= 1f - str * t;
		}
		v.b = 1f - keepB;
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
			int x = p & 15, y = (p >> 4) & 15, z = (p >> 8) & 15, d = (p >> 12) & 7;
			float ox = DX[d] * EPS, oy = DY[d] * EPS, oz = DZ[d] * EPS;
			int[][] f = FACE[d];
			for (int v = 0; v < 4; v++) {
				int c = qCol[q * 4 + v];
				int r = c & 255, g = (c >> 8) & 255, b = (c >> 16) & 255, a = (c >>> 24) & 255;
				builder.vertex(x + f[v][0] + ox, y + f[v][1] + oy, z + f[v][2] + oz)
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
