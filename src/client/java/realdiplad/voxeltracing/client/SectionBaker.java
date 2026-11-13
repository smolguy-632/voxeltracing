package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Turns one VoxelSection into overlay meshes.
 *
 * Per exposed face we decide shading with voxel rays (sun + nearby emitters), as before.
 * Faces whose sun ray is hard-blocked by an opaque voxel are routed to a "trapezoid" pass:
 * the blocker's cube is projected onto the face's plane along the sun direction, the projection
 * is clipped to the merged rectangle of shadowed faces, and the result is fan-triangulated.
 * Each face belongs to exactly one pass, so shadow polygons never overlap and need no stencil.
 */
final class SectionBaker {
	private static final float EPS = 0.003f;
	private static final int MAX_CAND = 128;
	private static final int MAX_BLK = 256;
	private static final int MEMO_DIM = 18;
	private static final int MEMO_SIZE = MEMO_DIM * MEMO_DIM * MEMO_DIM;
	private static final float Q_A = 14f, Q_W = 5f;

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
	// Per face: in-plane axes u, v and normal axis n (0=x, 1=y, 2=z)
	private static final int[] UA = {0, 0, 0, 0, 2, 2};
	private static final int[] VA = {2, 2, 1, 1, 1, 1};
	private static final int[] NA = {1, 1, 2, 2, 0, 0};

	private static final int WR = 255, WG = 176, WB = 96;

	private final VoxelLighting vl;
	private BufferBuilder builder;
	private final int[] base = new int[3];

	private final float[] sunMemo = new float[MEMO_SIZE];
	private final float[] bMemo = new float[MEMO_SIZE];
	private final float[] pMemo = new float[MEMO_SIZE];
	private final int[] sunBlk = new int[MEMO_SIZE];

	// Shade cells (rectangles) and sun-trapezoid cells, one grid each, so each face is in exactly one.
	private final int[] grid = new int[6 * 16 * 16 * 16];
	private final int[] layerCount = new int[6 * 16];
	private final int[] tGrid = new int[6 * 16 * 16 * 16];
	private final int[] tBlk = new int[6 * 16 * 16 * 16];
	private final int[] tLayerCount = new int[6 * 16];

	// Merged output rectangles: pos packs u | v<<4 | (w-1)<<8 | (h-1)<<12 | layer<<16 | d<<20
	private int[] qPos = new int[2048];
	private int[] qCol = new int[2048];
	private int quads;
	private int[] tPos = new int[512];
	private int[] tCol = new int[512];
	private int[] tOwner = new int[512];
	private int trapRects;

	// Blockers that cast hard sun shadows in this section
	private final HashMap<Long, Integer> blkMap = new HashMap<>();
	private final int[] blkX = new int[MAX_BLK], blkY = new int[MAX_BLK], blkZ = new int[MAX_BLK];
	private int blkCount;

	// Triangle output for the trapezoid pass
	private float[] triV = new float[9 * 1024];
	private int[] triC = new int[3 * 1024];
	private int triN;

	// Emitter candidates for the section being baked
	private final float[] cx = new float[MAX_CAND], cy = new float[MAX_CAND], cz = new float[MAX_CAND], cl = new float[MAX_CAND];
	private final int[] cbx = new int[MAX_CAND], cby = new int[MAX_CAND], cbz = new int[MAX_CAND];
	private int candCount;

	private final float[] bestStr = new float[8];
	private final int[] bestIdx = new int[8];

	SectionBaker(VoxelLighting vl) {
		this.vl = vl;
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

		base[0] = s.sx << 4;
		base[1] = s.sy << 4;
		base[2] = s.sz << 4;

		Arrays.fill(sunMemo, -1f);
		Arrays.fill(bMemo, -1f);
		Arrays.fill(sunBlk, -1);
		Arrays.fill(grid, 0);
		Arrays.fill(layerCount, 0);
		Arrays.fill(tGrid, 0);
		Arrays.fill(tBlk, 0);
		Arrays.fill(tLayerCount, 0);
		quads = 0;
		trapRects = 0;
		blkCount = 0;
		blkMap.clear();

		final float strength = vl.sunStrength;
		final float lx = vl.lx, ly = vl.ly, lz = vl.lz;
		final float range = cfg.quality.rayRange;
		final int minY = vl.level.getMinBuildHeight(), maxY = vl.level.getMaxBuildHeight();
		final int bx = base[0], by = base[1], bz = base[2];
		final long[] op = s.opaque;

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
						if ((op[ni >>> 6] & (1L << ni)) != 0L) continue;
					} else if (vl.isOpaque(bx + nx, wy, bz + nz)) {
						continue;
					}

					int wx = bx + nx, wz = bz + nz;
					int mi = (nx + 1) + MEMO_DIM * ((ny + 1) + MEMO_DIM * (nz + 1));

					// ---- sun
					float sunA = 0f;
					boolean hard = false;
					if (sunOn) {
						float dot = DX[d] * lx + DY[d] * ly + DZ[d] * lz;
						if (dot <= 0f) {
							sunA = cfg.selfShade;
						} else {
							float t = sunMemo[mi];
							if (t < 0f) {
								t = vl.trace(wx + 0.5, wy + 0.5, wz + 0.5, lx, ly, lz, range,
									VoxelLighting.NO_TARGET, 0, 0);
								sunMemo[mi] = t;
								if (t == 0f && vl.hitAny) sunBlk[mi] = registerBlocker(vl.hitX, vl.hitY, vl.hitZ);
							}
							hard = t == 0f && sunBlk[mi] >= 0;
							sunA = (1f - t) * cfg.shadowStrength;
						}
						sunA *= strength;
					}

					// ---- block light
					float b = 0f, p = 0f;
					if (lightOn) {
						b = bMemo[mi];
						if (b < 0f) {
							lightVoxel(wx, wy, wz, mi, cfg);
							b = bMemo[mi];
						}
						p = pMemo[mi];
					}

					float dark = 1f - (1f - sunA * (1f - b)) * (1f - (p - b) * cfg.torchShadow);
					float warm = b * cfg.warmStrength;
					float sum = dark + warm;
					if (sum < 0.02f) continue;

					int qa = (int) (Math.min(0.8f, sum) * Q_A + 0.5f);
					if (qa == 0) continue;
					int qw = (int) (warm / sum * Q_W + 0.5f);
					int layer, u, v;
					if (d < 2) { layer = y; u = x; v = z; }
					else if (d < 4) { layer = z; u = x; v = y; }
					else { layer = x; u = z; v = y; }

					int cell = (((d << 4) + layer) << 8) + (v << 4) + u;
					int val = 0x100 | (qa << 4) | qw;
					if (hard) {
						tGrid[cell] = val;
						tBlk[cell] = sunBlk[mi];
						tLayerCount[(d << 4) + layer]++;
					} else {
						grid[cell] = val;
						layerCount[(d << 4) + layer]++;
					}
				}
			}
		}

		mergeGrid(grid, null, layerCount, false);
		mergeGrid(tGrid, tBlk, tLayerCount, true);
		upload(s);
	}

	// ------------------------------------------------------------------ blockers

	private int registerBlocker(int hx, int hy, int hz) {
		long key = ((long) (hx + 0x800000) << 40) | ((long) (hy + 0x800) << 24) | (long) (hz + 0x800000);
		Integer found = blkMap.get(key);
		if (found != null) return found;
		if (blkCount >= MAX_BLK) return -1;
		blkMap.put(key, blkCount);
		blkX[blkCount] = hx;
		blkY[blkCount] = hy;
		blkZ[blkCount] = hz;
		return blkCount++;
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

	private void lightVoxel(int wx, int wy, int wz, int mi, VtConfig cfg) {
		final float ox = wx + 0.5f, oy = wy + 0.5f, oz = wz + 0.5f;
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
		bMemo[mi] = 1f - keepB;
		pMemo[mi] = 1f - keepP;
	}

	// ------------------------------------------------------------------ greedy merge

	private static boolean same(int[] g, int[] bl, int i, int val, int owner) {
		return g[i] == val && (bl == null || bl[i] == owner);
	}

	private void mergeGrid(int[] g, int[] bl, int[] layers, boolean trap) {
		for (int d = 0; d < 6; d++) {
			for (int layer = 0; layer < 16; layer++) {
				if (layers[(d << 4) + layer] == 0) continue;
				int base = ((d << 4) + layer) << 8;
				for (int v = 0; v < 16; v++) {
					for (int u = 0; u < 16; u++) {
						int i0 = base + (v << 4) + u;
						int val = g[i0];
						if (val == 0) continue;
						int owner = bl == null ? 0 : bl[i0];
						int w = 1;
						while (u + w < 16 && same(g, bl, i0 + w, val, owner)) w++;
						int h = 1;
						grow:
						while (v + h < 16) {
							for (int k = 0; k < w; k++) {
								if (!same(g, bl, base + ((v + h) << 4) + u + k, val, owner)) break grow;
							}
							h++;
						}
						for (int dv = 0; dv < h; dv++) {
							for (int k = 0; k < w; k++) g[base + ((v + dv) << 4) + u + k] = 0;
						}
						int pos = u | (v << 4) | ((w - 1) << 8) | ((h - 1) << 12) | (layer << 16) | (d << 20);
						if (trap) addTrap(pos, val, owner);
						else addRect(pos, val);
					}
				}
			}
		}
	}

	private void addRect(int pos, int val) {
		if (quads == qPos.length) {
			qPos = Arrays.copyOf(qPos, quads * 2);
			qCol = Arrays.copyOf(qCol, quads * 2);
		}
		qPos[quads] = pos;
		qCol[quads] = val;
		quads++;
	}

	private void addTrap(int pos, int val, int owner) {
		if (trapRects == tPos.length) {
			int n = trapRects * 2;
			tPos = Arrays.copyOf(tPos, n);
			tCol = Arrays.copyOf(tCol, n);
			tOwner = Arrays.copyOf(tOwner, n);
		}
		tPos[trapRects] = pos;
		tCol[trapRects] = val;
		tOwner[trapRects] = owner;
		trapRects++;
	}

	// ------------------------------------------------------------------ trapezoid projection

	/** Projects blocker cube onto the face plane along the sun, clips to the rectangle, fan-triangulates. */
	private void emitTrap(int pos, int val, int owner) {
		int u = pos & 15, v = (pos >> 4) & 15;
		int w = ((pos >> 8) & 15) + 1, h = ((pos >> 12) & 15) + 1;
		int layer = (pos >> 16) & 15, d = (pos >> 20) & 7;
		int nA = NA[d], uA = UA[d], vA = VA[d];
		double sgn = (d & 1) == 1 ? 1.0 : -1.0;
		double c = layer + (sgn > 0 ? 1 : 0);                 // plane coordinate, section-local
		double[] L = {vl.lx, vl.ly, vl.lz};
		double nL = sgn * L[nA];
		if (nL <= 1e-4) return;

		// blocker cube corners in section-local space
		double bx = blkX[owner] - base[0], by = blkY[owner] - base[1], bz = blkZ[owner] - base[2];
		double[][] P = new double[8][];
		for (int i = 0; i < 8; i++) {
			P[i] = new double[] {bx + ((i >> 2) & 1), by + ((i >> 1) & 1), bz + (i & 1)};
		}
		double[] S = new double[8];
		for (int i = 0; i < 8; i++) S[i] = sgn * (P[i][nA] - c);

		// candidate points on the lit side of the plane: projected corners + plane crossings
		double[] cu = new double[24], cv = new double[24];
		int n = 0;
		for (int i = 0; i < 8; i++) {
			if (S[i] < 0) continue;
			double t = S[i] / nL;
			cu[n] = P[i][uA] - t * L[uA];
			cv[n] = P[i][vA] - t * L[vA];
			n++;
		}
		for (int i = 0; i < 8; i++) {
			for (int j = i + 1; j < 8; j++) {
				if (Integer.bitCount(i ^ j) != 1) continue;
				if (!((S[i] > 0 && S[j] < 0) || (S[i] < 0 && S[j] > 0))) continue;
				double f = S[i] / (S[i] - S[j]);
				cu[n] = P[i][uA] + f * (P[j][uA] - P[i][uA]);
				cv[n] = P[i][vA] + f * (P[j][vA] - P[i][vA]);
				n++;
			}
		}
		if (n < 3) return;

		double[][] hull = convexHull(cu, cv, n);
		if (hull == null) return;

		// clip to the rectangle of shadowed faces on this plane
		double[][] poly = clipAxis(hull, 0, u, true);
		poly = clipAxis(poly, 0, u + w, false);
		poly = clipAxis(poly, 1, v, true);
		poly = clipAxis(poly, 1, v + h, false);
		if (poly.length < 3) return;

		// fan-triangulate, orient each triangle outward, emit
		for (int k = 1; k < poly.length - 1; k++) {
			double[] a = toVec(poly[0], uA, vA, nA, c, sgn);
			double[] b = toVec(poly[k], uA, vA, nA, c, sgn);
			double[] e = toVec(poly[k + 1], uA, vA, nA, c, sgn);
			double ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2];
			double fx = e[0] - a[0], fy = e[1] - a[1], fz = e[2] - a[2];
			double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
			double along = (nA == 0 ? nx : nA == 1 ? ny : nz) * sgn;
			double mag = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
			if (mag < 1e-9) continue;               // zero-area sliver
			if (along < 0) {
				double[] tmp = b;
				b = e;
				e = tmp;
			}
			addTri(a, b, e, val);
		}
	}

	private static double[] toVec(double[] p, int uA, int vA, int nA, double c, double sgn) {
		double[] r = new double[3];
		r[uA] = p[0];
		r[vA] = p[1];
		r[nA] = c + sgn * EPS;
		return r;
	}

	private static double[][] clipAxis(double[][] poly, int ax, double k, boolean keepGe) {
		int m = poly.length;
		if (m == 0) return poly;
		double[][] out = new double[m * 2][];
		int n = 0;
		for (int i = 0; i < m; i++) {
			double[] a = poly[i], b = poly[(i + 1) % m];
			boolean ia = keepGe ? a[ax] >= k : a[ax] <= k;
			boolean ib = keepGe ? b[ax] >= k : b[ax] <= k;
			if (ia) out[n++] = a;
			if (ia != ib) {
				double t = (k - a[ax]) / (b[ax] - a[ax]);
				out[n++] = new double[] {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
			}
		}
		return Arrays.copyOf(out, n);
	}

	/** Monotone-chain convex hull. Returns CCW points, or null if fewer than 3. */
	private static double[][] convexHull(double[] us, double[] vs, int n) {
		double[][] pts = new double[n][];
		for (int i = 0; i < n; i++) pts[i] = new double[] {us[i], vs[i]};
		Arrays.sort(pts, (p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
		double[][] h = new double[2 * n + 1][];
		int k = 0;
		for (int i = 0; i < n; i++) {
			while (k >= 2 && cross(h[k - 2], h[k - 1], pts[i]) <= 0) k--;
			h[k++] = pts[i];
		}
		for (int i = n - 2, t = k + 1; i >= 0; i--) {
			while (k >= t && cross(h[k - 2], h[k - 1], pts[i]) <= 0) k--;
			h[k++] = pts[i];
		}
		if (k < 4) return null;
		return Arrays.copyOf(h, k - 1);
	}

	private static double cross(double[] o, double[] a, double[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	private void addTri(double[] a, double[] b, double[] c, int val) {
		if (triN * 3 + 3 > triC.length) {
			triV = Arrays.copyOf(triV, triV.length * 2);
			triC = Arrays.copyOf(triC, triC.length * 2);
		}
		int o = triN * 9;
		triV[o] = (float) a[0]; triV[o + 1] = (float) a[1]; triV[o + 2] = (float) a[2];
		triV[o + 3] = (float) b[0]; triV[o + 4] = (float) b[1]; triV[o + 5] = (float) b[2];
		triV[o + 6] = (float) c[0]; triV[o + 7] = (float) c[1]; triV[o + 8] = (float) c[2];
		triC[triN] = val;
		triN++;
	}

	// ------------------------------------------------------------------ upload

	private void upload(VoxelSection s) {
		buildRects(s);
		buildTraps(s);
	}

	private void buildRects(VoxelSection s) {
		if (quads == 0) {
			if (s.vb != null) {
				s.vb.close();
				s.vb = null;
			}
			s.quadCount = 0;
			return;
		}
		if (builder == null) builder = new BufferBuilder(1 << 16);
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		for (int q = 0; q < quads; q++) {
			int p = qPos[q];
			int u = p & 15, v = (p >> 4) & 15;
			int w = ((p >> 8) & 15) + 1, h = ((p >> 12) & 15) + 1;
			int layer = (p >> 16) & 15, d = (p >> 20) & 7;
			int val = qCol[q];
			float wgt = (val & 15) / Q_W;
			int r = (int) (WR * wgt), g = (int) (WG * wgt), b = (int) (WB * wgt);
			int a = (int) (((val >> 4) & 15) / Q_A * 255f + 0.5f);
			float ox = DX[d] * EPS, oy = DY[d] * EPS, oz = DZ[d] * EPS;
			int[][] f = FACE[d];
			for (int k = 0; k < 4; k++) {
				float px, py, pz;
				if (d < 2) {
					px = u + f[k][0] * w;
					pz = v + f[k][2] * h;
					py = layer + f[k][1];
				} else if (d < 4) {
					px = u + f[k][0] * w;
					py = v + f[k][1] * h;
					pz = layer + f[k][2];
				} else {
					pz = u + f[k][2] * w;
					py = v + f[k][1] * h;
					px = layer + f[k][0];
				}
				builder.vertex(px + ox, py + oy, pz + oz).color(r, g, b, a).endVertex();
			}
		}
		BufferBuilder.RenderedBuffer rb = builder.end();
		if (s.vb == null) s.vb = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
		s.vb.bind();
		s.vb.upload(rb);
		com.mojang.blaze3d.vertex.VertexBuffer.unbind();
		s.quadCount = quads;
	}

	private void buildTraps(VoxelSection s) {
		triN = 0;
		for (int i = 0; i < trapRects; i++) emitTrap(tPos[i], tCol[i], tOwner[i]);
		if (triN == 0) {
			if (s.vbSun != null) {
				s.vbSun.close();
				s.vbSun = null;
			}
			s.trapCount = 0;
			return;
		}
		if (builder == null) builder = new BufferBuilder(1 << 16);
		builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		for (int t = 0; t < triN; t++) {
			int val = triC[t];
			float wgt = (val & 15) / Q_W;
			int r = (int) (WR * wgt), g = (int) (WG * wgt), b = (int) (WB * wgt);
			int a = (int) (((val >> 4) & 15) / Q_A * 255f + 0.5f);
			int o = t * 9;
			for (int k = 0; k < 3; k++) {
				builder.vertex(triV[o + k * 3], triV[o + k * 3 + 1], triV[o + k * 3 + 2]).color(r, g, b, a).endVertex();
			}
		}
		BufferBuilder.RenderedBuffer rb = builder.end();
		if (s.vbSun == null) s.vbSun = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
		s.vbSun.bind();
		s.vbSun.upload(rb);
		com.mojang.blaze3d.vertex.VertexBuffer.unbind();
		s.trapCount = triN * 3;
	}
}
