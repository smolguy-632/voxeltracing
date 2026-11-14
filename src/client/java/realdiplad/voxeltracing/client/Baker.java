package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Builds the overlay mesh of one section.
 *
 * Every exposed face of every light-receiving block box becomes a rectangle. Each rectangle is
 * adaptively subdivided (down to 1/16 block) wherever the sun visibility changes, so shadow edges
 * are pixel crisp while open ground stays a single quad. Vertex values are memoised per
 * (position, normal), so shared corners are traced once and neighbouring quads never disagree.
 */
final class Baker {
	private static final float EPS = 0.003f;
	private static final float THR = 0.05f;

	private final VoxelLighting w;
	private final Shading sh;
	private VtConfig cfg;
	private BufferBuilder builder;

	// output quads: 4 vertices x 3 floats each
	private float[] qp = new float[1 << 14], qm = new float[1 << 14], qb = new float[1 << 14];
	private int nq;

	// vertex memo
	private final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
	private float[] sv = new float[256 * 9];
	private double[] sp = new double[256 * 3];
	private long[] sk = new long[256];
	private int[] sf = new int[256];
	private int ns;

	// section / block / face state
	private int bX, bY, bZ;
	private int blkX, blkY, blkZ;
	private boolean lightsReady;
	private int axis, uAx, vAx, nIdx, nx, ny, nz;
	private double plane;
	private boolean sunWork, giWork, lightWork;
	private int minCell;
	private double vx, vy, vz;
	private float rR, rG, rB;
	private final float[] cm = new float[12];
	private final double[] cp = new double[12];

	Baker(VoxelLighting w) {
		this.w = w;
		this.sh = new Shading(w);
		slotOf.defaultReturnValue(-1);
	}

	void bake(Sect s, VtConfig cfg) {
		s.dirty = false;
		s.urgent = false;
		s.bakedKey = w.sunKey;
		if (s.state == Sect.PENDING) s.load(w.level, VoxelLighting.EMIT_MIN);
		if (s.state != Sect.READY || s.empty) {
			s.clearMesh();
			return;
		}
		this.cfg = cfg;
		sh.begin(cfg);
		minCell = cfg.crispShadows ? 1 : 4;
		bX = s.sx << 4;
		bY = s.sy << 4;
		bZ = s.sz << 4;
		nq = 0;
		ns = 0;
		slotOf.clear();

		for (int i = 0; i < 4096; i++) {
			int id = s.ids == null ? s.uniform : s.ids[i];
			if (id == 0) continue;
			Optics o = Optics.byIdx[id];
			if (!o.receives) continue;
			blkX = bX + (i & 15);
			blkZ = bZ + ((i >> 4) & 15);
			blkY = bY + (i >> 8);
			lightsReady = false;
			for (int bi = 0; bi < o.boxes.length; bi += 6) {
				for (int d = 0; d < 6; d++) face(o, bi, d);
			}
		}
		upload(s);
	}

	// ------------------------------------------------------------------ faces

	private void face(Optics o, int bi, int d) {
		float[] bx = o.boxes;
		axis = d >> 1;
		boolean pos = (d & 1) != 0;
		nx = axis == 0 ? (pos ? 1 : -1) : 0;
		ny = axis == 1 ? (pos ? 1 : -1) : 0;
		nz = axis == 2 ? (pos ? 1 : -1) : 0;

		float pl = pos ? bx[bi + 3 + axis] : bx[bi + axis];
		boolean boundary = pos ? pl >= 0.999f : pl <= 0.001f;
		if (boundary) {
			Optics no = w.optAt(blkX + nx, blkY + ny, blkZ + nz);
			if (no.kind == Optics.OPAQUE || (no.covers & (1 << (d ^ 1))) != 0) return;
			plane = pos ? 1.0 : 0.0;
		} else {
			plane = pl;
		}

		uAx = axis == 0 ? 1 : 0;
		vAx = axis == 2 ? 1 : 2;
		int u0 = Math.round(bx[bi + uAx] * 16f), u1 = Math.round(bx[bi + 3 + uAx] * 16f);
		int v0 = Math.round(bx[bi + vAx] * 16f), v1 = Math.round(bx[bi + 3 + vAx] * 16f);
		if (u1 <= u0 || v1 <= v0) return;

		int lcx = blkX + (boundary ? nx : 0), lcy = blkY + (boundary ? ny : 0), lcz = blkZ + (boundary ? nz : 0);
		float k = w.skyLight(lcx, lcy, lcz) / 15f;
		float bf = w.blockLight(lcx, lcy, lcz) / 15f;

		if (!lightsReady) {
			sh.gatherLights(blkX, blkY, blkZ);
			lightsReady = true;
		}
		if (k < 0.04f && sh.cn == 0) return;

		double nl = nx * w.lx + ny * w.ly + nz * w.lz;
		float lam = (float) Math.max(0.0, Math.min(1.0, nl / 0.5));
		sunWork = sh.sunOn && k >= 0.04f && lam > 0f;
		giWork = cfg.gi && cfg.quality.giRays > 0 && k >= 0.04f;
		lightWork = sh.cn > 0;
		float bs = bf / (bf + k * sh.skyB * 1.5f + 0.02f);
		sh.beginFace(lam, k, bs);
		nIdx = d;
		node(u0, v0, u1, v1);
	}

	/** Recursive rectangle: split while the sun visibility is not uniform across it. */
	private void node(int u0, int v0, int u1, int v1) {
		int wd = u1 - u0, ht = v1 - v0;
		boolean canU = wd >= 2 * minCell, canV = ht >= 2 * minCell;
		if (sunWork && (canU || canV)) {
			int nu = Math.min(4, wd), nv = Math.min(4, ht);
			float fr = 0f, fg = 0f, fb = 0f;
			boolean first = true, uniform = true;
			for (int j = 0; j <= nv && uniform; j++) {
				for (int i = 0; i <= nu; i++) {
					fetchSun(slot(u0 + (double) wd * i / nu, v0 + (double) ht * j / nv));
					if (first) {
						fr = rR;
						fg = rG;
						fb = rB;
						first = false;
					} else if (Math.abs(rR - fr) > THR || Math.abs(rG - fg) > THR || Math.abs(rB - fb) > THR) {
						uniform = false;
						break;
					}
				}
			}
			if (!uniform) {
				int um = canU ? (u0 + u1) >> 1 : u1;
				int vm = canV ? (v0 + v1) >> 1 : v1;
				node(u0, v0, um, vm);
				if (canU) node(um, v0, u1, vm);
				if (canV) node(u0, vm, um, v1);
				if (canU && canV) node(um, vm, u1, v1);
				return;
			}
		}
		leaf(u0, v0, u1, v1);
	}

	private void leaf(int u0, int v0, int u1, int v1) {
		int[] us = {u0, u1, u1, u0};
		int[] vs = {v0, v0, v1, v1};
		boolean visible = false;
		for (int c = 0; c < 4; c++) {
			int sl = slot(us[c], vs[c]);
			fetchSun(sl);
			float sr = rR, sg = rG, sb = rB;
			fetchGi(sl);
			float gr = rR, gg = rG, gb = rB;
			fetchLight(sl);
			sh.combine(sr, sg, sb, gr, gg, gb, rR, rG, rB);
			cm[c * 3] = sh.oR;
			cm[c * 3 + 1] = sh.oG;
			cm[c * 3 + 2] = sh.oB;
			if (Math.abs(sh.oR - 1f) > 0.015f || Math.abs(sh.oG - 1f) > 0.015f || Math.abs(sh.oB - 1f) > 0.015f) {
				visible = true;
			}
			pos(us[c], vs[c]);
			cp[c * 3] = vx;
			cp[c * 3 + 1] = vy;
			cp[c * 3 + 2] = vz;
		}
		if (!visible) return;

		int o = nq * 12;
		if (o + 12 > qp.length) {
			int n = qp.length * 2;
			qp = Arrays.copyOf(qp, n);
			qm = Arrays.copyOf(qm, n);
			qb = Arrays.copyOf(qb, n);
		}
		float ex = nx * EPS, ey = ny * EPS, ez = nz * EPS;
		for (int c = 0; c < 4; c++) {
			qp[o + c * 3] = (float) (cp[c * 3] - bX) + ex;
			qp[o + c * 3 + 1] = (float) (cp[c * 3 + 1] - bY) + ey;
			qp[o + c * 3 + 2] = (float) (cp[c * 3 + 2] - bZ) + ez;
			for (int ch = 0; ch < 3; ch++) {
				float m = cm[c * 3 + ch];
				qm[o + c * 3 + ch] = Math.min(m, 1f);
				qb[o + c * 3 + ch] = Math.max(0f, Math.min(m - 1f, 1f));
			}
		}
		nq++;
	}

	// ------------------------------------------------------------------ vertex memo

	private void pos(double u, double v) {
		double px = blkX, py = blkY, pz = blkZ;
		if (axis == 0) px += plane;
		else if (axis == 1) py += plane;
		else pz += plane;
		double uu = u / 16.0, vv = v / 16.0;
		if (uAx == 0) px += uu;
		else py += uu;
		if (vAx == 1) py += vv;
		else pz += vv;
		vx = px;
		vy = py;
		vz = pz;
	}

	private int slot(double u, double v) {
		pos(u, v);
		long qx = Math.round((vx - bX) * 64.0), qy = Math.round((vy - bY) * 64.0), qz = Math.round((vz - bZ) * 64.0);
		long key = (((qx * 1025L + qy) * 1025L + qz) << 3) | nIdx;
		int sl = slotOf.get(key);
		if (sl >= 0) return sl;
		sl = ns++;
		if (sl >= sf.length) {
			int n = sf.length * 2;
			sf = Arrays.copyOf(sf, n);
			sk = Arrays.copyOf(sk, n);
			sv = Arrays.copyOf(sv, n * 9);
			sp = Arrays.copyOf(sp, n * 3);
		}
		slotOf.put(key, sl);
		sp[sl * 3] = vx;
		sp[sl * 3 + 1] = vy;
		sp[sl * 3 + 2] = vz;
		sk[sl] = key;
		sf[sl] = 0;
		return sl;
	}

	private void fetchSun(int sl) {
		if (!sunWork) {
			rR = rG = rB = 1f;
			return;
		}
		int o = sl * 9;
		if ((sf[sl] & 1) == 0) {
			sh.sun(sp[sl * 3], sp[sl * 3 + 1], sp[sl * 3 + 2], nx, ny, nz);
			sv[o] = sh.oR;
			sv[o + 1] = sh.oG;
			sv[o + 2] = sh.oB;
			sf[sl] |= 1;
		}
		rR = sv[o];
		rG = sv[o + 1];
		rB = sv[o + 2];
	}

	private void fetchGi(int sl) {
		if (!giWork) {
			rR = rG = rB = 1f;
			return;
		}
		int o = sl * 9 + 3;
		if ((sf[sl] & 2) == 0) {
			long k = sk[sl];
			sh.gi(sp[sl * 3], sp[sl * 3 + 1], sp[sl * 3 + 2], nx, ny, nz, (int) (k ^ (k >>> 32)));
			sv[o] = sh.oR;
			sv[o + 1] = sh.oG;
			sv[o + 2] = sh.oB;
			sf[sl] |= 2;
		}
		rR = sv[o];
		rG = sv[o + 1];
		rB = sv[o + 2];
	}

	private void fetchLight(int sl) {
		if (!lightWork) {
			rR = rG = rB = 1f;
			return;
		}
		int o = sl * 9 + 6;
		if ((sf[sl] & 4) == 0) {
			sh.lights(sp[sl * 3], sp[sl * 3 + 1], sp[sl * 3 + 2], nx, ny, nz);
			sv[o] = sh.oR;
			sv[o + 1] = sh.oG;
			sv[o + 2] = sh.oB;
			sf[sl] |= 4;
		}
		rR = sv[o];
		rG = sv[o + 1];
		rB = sv[o + 2];
	}

	// ------------------------------------------------------------------ upload

	private void upload(Sect s) {
		if (nq == 0) {
			s.clearMesh();
			return;
		}
		int cMul = fill(false);
		if (cMul > 0) {
			BufferBuilder.RenderedBuffer rb = builder.end();
			if (s.vbMul == null) s.vbMul = new VertexBuffer(VertexBuffer.Usage.STATIC);
			s.vbMul.bind();
			s.vbMul.upload(rb);
			VertexBuffer.unbind();
		} else if (s.vbMul != null) {
			s.vbMul.close();
			s.vbMul = null;
		}
		s.cMul = cMul;

		int cBoost = fill(true);
		if (cBoost > 0) {
			BufferBuilder.RenderedBuffer rb = builder.end();
			if (s.vbBoost == null) s.vbBoost = new VertexBuffer(VertexBuffer.Usage.STATIC);
			s.vbBoost.bind();
			s.vbBoost.upload(rb);
			VertexBuffer.unbind();
		} else if (s.vbBoost != null) {
			s.vbBoost.close();
			s.vbBoost = null;
		}
		s.cBoost = cBoost;
	}

	/** Starts the builder (only if something will be written) and returns the quad count. */
	private int fill(boolean boostPass) {
		if (builder == null) builder = new BufferBuilder(1 << 16);
		float[] src = boostPass ? qb : qm;
		int count = 0;
		boolean begun = false;
		for (int q = 0; q < nq; q++) {
			int o = q * 12;
			boolean need = false;
			for (int i = 0; i < 12; i++) {
				if (boostPass ? src[o + i] > 0.004f : src[o + i] < 0.996f) {
					need = true;
					break;
				}
			}
			if (!need) continue;
			if (!begun) {
				builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
				begun = true;
			}
			for (int c = 0; c < 4; c++) {
				int p = o + c * 3;
				builder.vertex(qp[p], qp[p + 1], qp[p + 2])
						.color(to8(src[p]), to8(src[p + 1]), to8(src[p + 2]), 255)
						.endVertex();
			}
			count++;
		}
		return count;
	}

	private static int to8(float v) {
		int i = (int) (v * 255f + 0.5f);
		return i < 0 ? 0 : Math.min(i, 255);
	}
}
