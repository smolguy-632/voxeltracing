package realdiplad.voxeltracing.client;

import net.minecraft.util.Mth;

/**
 * Ray marcher through the block grid (Amanatides-Woo traversal in double precision).
 * Per visited block it uses the exact block shape:
 *  - OPAQUE  : stops the ray at the cell entry
 *  - BOXES   : exact ray / AABB slab test against every collision box
 *  - TINT    : multiplies the throughput by the block's colour once per crossing
 *  - FOLIAGE : Beer-Lambert absorption over the path length inside the cell
 * Results are left in the public fields. The cell containing the ray origin is treated as open
 * for full cubes, so a start point lying exactly on a block boundary never self-occludes.
 */
final class Tracer {
	private static final double INF = Double.POSITIVE_INFINITY;

	private final VoxelLighting w;

	float tr, tg, tb;
	boolean hit;
	double hitT;
	Optics hitOptics;
	int hitX, hitY, hitZ;
	int hitNx, hitNy, hitNz;

	private double bT;
	private int bAxis;

	Tracer(VoxelLighting w) {
		this.w = w;
	}

	void march(double ox, double oy, double oz, double dx, double dy, double dz, double maxLen) {
		tr = tg = tb = 1f;
		hit = false;
		hitOptics = null;

		int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double invX = dx != 0 ? 1.0 / Math.abs(dx) : INF;
		double invY = dy != 0 ? 1.0 / Math.abs(dy) : INF;
		double invZ = dz != 0 ? 1.0 / Math.abs(dz) : INF;
		double tMaxX = dx > 0 ? (x + 1 - ox) * invX : dx < 0 ? (ox - x) * invX : INF;
		double tMaxY = dy > 0 ? (y + 1 - oy) * invY : dy < 0 ? (oy - y) * invY : INF;
		double tMaxZ = dz > 0 ? (z + 1 - oz) * invZ : dz < 0 ? (oz - z) * invZ : INF;

		final int minY = w.minY, maxY = w.maxY;
		double t = 0;
		int axis = -1;
		Sect s = null;
		int csx = 0, csy = 0, csz = 0;
		boolean have = false;

		while (t <= maxLen) {
			if (y < minY || y >= maxY) return;

			int sx = x >> 4, sy = y >> 4, sz = z >> 4;
			if (!have || sx != csx || sy != csy || sz != csz) {
				s = w.sect(sx, sy, sz);
				csx = sx;
				csy = sy;
				csz = sz;
				have = true;
			}

			double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
			if (s != null && !s.empty) {
				int id = s.ids == null ? s.uniform : s.ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
				if (id != 0) {
					Optics o = Optics.byIdx[id];
					switch (o.kind) {
						case Optics.OPAQUE:
							if (axis >= 0) {
								hit = true;
								hitT = t;
								hitOptics = o;
								hitX = x;
								hitY = y;
								hitZ = z;
								hitNx = axis == 0 ? -stepX : 0;
								hitNy = axis == 1 ? -stepY : 0;
								hitNz = axis == 2 ? -stepZ : 0;
								return;
							}
							break;
						case Optics.BOXES:
							if (boxes(o, x, y, z, ox, oy, oz, dx, dy, dz, t - 1e-9, maxLen)) {
								hit = true;
								hitT = bT;
								hitOptics = o;
								hitX = x;
								hitY = y;
								hitZ = z;
								hitNx = bAxis == 0 ? (dx > 0 ? -1 : 1) : 0;
								hitNy = bAxis == 1 ? (dy > 0 ? -1 : 1) : 0;
								hitNz = bAxis == 2 ? (dz > 0 ? -1 : 1) : 0;
								return;
							}
							break;
						case Optics.FOLIAGE: {
							double len = Math.min(tExit, maxLen) - Math.max(t, 0.0);
							if (len > 0) {
								tr *= (float) Math.exp(len * o.lnr);
								tg *= (float) Math.exp(len * o.lng);
								tb *= (float) Math.exp(len * o.lnb);
								if (tr + tg + tb < 0.01f) {
									tr = tg = tb = 0f;
									return;
								}
							}
							break;
						}
						case Optics.TINT: {
							boolean tinted = false;
							if (o.isFull) {
								// full cubes (glass, ice, slime, water) absorb by depth travelled, so a ray that
								// only clips a corner is barely tinted and stacks fade smoothly
								double len = Math.min(tExit, maxLen) - Math.max(t, 0.0);
								if (len > 0) {
									tr *= (float) Math.exp(len * o.lnr);
									tg *= (float) Math.exp(len * o.lng);
									tb *= (float) Math.exp(len * o.lnb);
									tinted = true;
								}
							} else if (boxes(o, x, y, z, ox, oy, oz, dx, dy, dz, t - 1e-9, maxLen)) {
								// thin shapes (panes): one sheet, one crossing
								tr *= o.tr;
								tg *= o.tg;
								tb *= o.tb;
								tinted = true;
							}
							if (tinted && tr + tg + tb < 0.01f) {
								tr = tg = tb = 0f;
								return;
							}
							break;
						}
						default:
							break;
					}
				}
			}

			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) {
					t = tMaxX;
					x += stepX;
					tMaxX += invX;
					axis = 0;
				} else {
					t = tMaxZ;
					z += stepZ;
					tMaxZ += invZ;
					axis = 2;
				}
			} else if (tMaxY < tMaxZ) {
				t = tMaxY;
				y += stepY;
				tMaxY += invY;
				axis = 1;
			} else {
				t = tMaxZ;
				z += stepZ;
				tMaxZ += invZ;
				axis = 2;
			}
		}
	}

	/**
	 * Slab test against every box of block (x,y,z). On a hit, bT / bAxis hold the entry distance
	 * and the axis of the struck face. Boxes that already contain the ray origin are ignored.
	 */
	private boolean boxes(Optics o, int x, int y, int z, double ox, double oy, double oz,
						  double dx, double dy, double dz, double lo, double maxLen) {
		float[] b = o.boxes;
		double best = INF;
		int bestAxis = -1;
		for (int i = 0; i < b.length; i += 6) {
			double tn = -INF, tf = INF;
			int ax = -1;

			if (dx != 0) {
				double inv = 1.0 / dx;
				double t0 = (x + b[i] - ox) * inv, t1 = (x + b[i + 3] - ox) * inv;
				if (t0 > t1) {
					double tmp = t0;
					t0 = t1;
					t1 = tmp;
				}
				if (t0 > tn) {
					tn = t0;
					ax = 0;
				}
				if (t1 < tf) tf = t1;
			} else if (ox < x + b[i] || ox > x + b[i + 3]) {
				continue;
			}

			if (dy != 0) {
				double inv = 1.0 / dy;
				double t0 = (y + b[i + 1] - oy) * inv, t1 = (y + b[i + 4] - oy) * inv;
				if (t0 > t1) {
					double tmp = t0;
					t0 = t1;
					t1 = tmp;
				}
				if (t0 > tn) {
					tn = t0;
					ax = 1;
				}
				if (t1 < tf) tf = t1;
			} else if (oy < y + b[i + 1] || oy > y + b[i + 4]) {
				continue;
			}

			if (dz != 0) {
				double inv = 1.0 / dz;
				double t0 = (z + b[i + 2] - oz) * inv, t1 = (z + b[i + 5] - oz) * inv;
				if (t0 > t1) {
					double tmp = t0;
					t0 = t1;
					t1 = tmp;
				}
				if (t0 > tn) {
					tn = t0;
					ax = 2;
				}
				if (t1 < tf) tf = t1;
			} else if (oz < z + b[i + 2] || oz > z + b[i + 5]) {
				continue;
			}

			if (tn > tf || tf < lo || tn < lo || tn > maxLen || ax < 0) continue;
			if (tn < best) {
				best = tn;
				bestAxis = ax;
			}
		}
		if (bestAxis < 0) return false;
		bT = best;
		bAxis = bestAxis;
		return true;
	}
}
