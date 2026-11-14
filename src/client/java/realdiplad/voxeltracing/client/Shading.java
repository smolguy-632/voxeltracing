package realdiplad.voxeltracing.client;

import net.minecraft.util.Mth;

/**
 * Everything that turns rays into light, evaluated per vertex:
 *  sun()    - transmittance (RGB) of the sun through glass / leaves / water, 0 when blocked
 *  gi()     - one-bounce global illumination ratio (RGB): cosine-weighted hemisphere rays that
 *             either reach the sky or hit a surface which is itself lit by sun and sky, so light
 *             bleeds colour from sunlit walls/grass into shadow and corners get ambient occlusion
 *  lights() - visibility of nearby emitters (torches, lava, glowstone...) with their colour
 * combine() merges them into the final colour multiplier relative to what vanilla already drew.
 */
final class Shading {
	static final double NOFF = 0.004;
	private static final float ESUN = 2.2f, EAMB = 1.0f;
	private static final float GI_CHROMA = 0.5f;

	private final VoxelLighting w;
	private final Tracer tz;
	private final Tracer tz2;

	// per-bake state
	private VtConfig cfg;
	boolean sunOn;
	float st;
	private float lx, ly, lz;
	float skyB;
	private int giRays;
	private double giRange, shadowRange, hitShadowRange;
	private float gRefR, gRefG, gRefB;
	private float sR, sG, sB;

	// per-face state
	private float faceLam, faceK, faceBs;

	// emitter candidates for the current block
	int cn;
	private final double[] cx = new double[8], cy = new double[8], cz = new double[8], cd = new double[8];
	private final float[] cl = new float[8], cr = new float[8], cg = new float[8], cb = new float[8];
	private int maxLights;
	private double lightRadius;

	// output
	float oR, oG, oB;

	Shading(VoxelLighting w) {
		this.w = w;
		this.tz = new Tracer(w);
		this.tz2 = new Tracer(w);
	}

	void begin(VtConfig c) {
		cfg = c;
		VtConfig.Quality q = c.quality;
		sunOn = w.sunKey != VoxelLighting.NO_SUN && w.sunStrength > 0.001f;
		st = sunOn ? w.sunStrength : 0f;
		lx = w.lx;
		ly = w.ly;
		lz = w.lz;
		skyB = 0.10f + 0.90f * w.daylight;
		giRays = c.gi ? q.giRays : 0;
		giRange = q.giRange;
		shadowRange = q.shadowRange;
		hitShadowRange = Math.min(q.shadowRange, 32);
		maxLights = Math.min(q.maxLights, 8);
		lightRadius = q.lightRadius;
		float sunE = ESUN * st * Math.max(0f, ly);
		gRefR = 0.35f * (sunE * w.sunR + EAMB * skyB);
		gRefG = 0.35f * (sunE * w.sunG + EAMB * skyB);
		gRefB = 0.35f * (sunE * w.sunB + EAMB * skyB);
	}

	void beginFace(float lam, float k, float blockShare) {
		faceLam = lam;
		faceK = k;
		faceBs = blockShare;
	}

	// ------------------------------------------------------------------ sun

	void sun(double px, double py, double pz, int nx, int ny, int nz) {
		tz.march(px + nx * NOFF, py + ny * NOFF, pz + nz * NOFF, lx, ly, lz, shadowRange);
		if (tz.hit) {
			oR = oG = oB = 0f;
		} else {
			oR = tz.tr;
			oG = tz.tg;
			oB = tz.tb;
		}
	}

	// ------------------------------------------------------------------ global illumination

	private void sky(double dy) {
		float t = (float) Math.min(1.0, Math.max(0.0, dy));
		t = t * t * (3f - 2f * t);
		sR = (0.88f + (0.46f - 0.88f) * t) * skyB;
		sG = (0.93f + (0.62f - 0.93f) * t) * skyB;
		sB = 1.00f * skyB;
	}

	void gi(double px, double py, double pz, int nx, int ny, int nz, int seed) {
		oR = oG = oB = 1f;
		final int n = giRays;
		if (n <= 0) return;

		double ox = px + nx * NOFF, oy = py + ny * NOFF, oz = pz + nz * NOFF;
		double t1x, t1y, t1z, t2x, t2y, t2z;
		if (ny != 0) {
			t1x = 1;
			t1y = 0;
			t1z = 0;
			t2x = 0;
			t2y = 0;
			t2z = 1;
		} else if (nx != 0) {
			t1x = 0;
			t1y = 1;
			t1z = 0;
			t2x = 0;
			t2y = 0;
			t2z = 1;
		} else {
			t1x = 1;
			t1y = 0;
			t1z = 0;
			t2x = 0;
			t2y = 1;
			t2z = 0;
		}

		double rot = rand(seed) * 6.283185307179586;
		float sumR = 0f, sumG = 0f, sumB = 0f, refR = 0f, refG = 0f, refB = 0f;

		for (int i = 0; i < n; i++) {
			double u = (i + 0.5) / n;
			double r = Math.sqrt(u);
			double phi = rot + i * 2.399963229728653;
			double a = r * Math.cos(phi), b = r * Math.sin(phi), c = Math.sqrt(1.0 - u);
			double dx = t1x * a + t2x * b + nx * c;
			double dy = t1y * a + t2y * b + ny * c;
			double dz = t1z * a + t2z * b + nz * c;

			float rr, rg, rb;
			if (dy >= 0) {
				sky(dy);
				rr = sR;
				rg = sG;
				rb = sB;
			} else {
				rr = gRefR;
				rg = gRefG;
				rb = gRefB;
			}

			tz.march(ox, oy, oz, dx, dy, dz, giRange);
			float thR = tz.tr, thG = tz.tg, thB = tz.tb;
			float lr, lg, lb;
			if (tz.hit) {
				Optics ho = tz.hitOptics;
				double hx = ox + dx * tz.hitT, hy = oy + dy * tz.hitT, hz = oz + dz * tz.hitT;
				int hnx = tz.hitNx, hny = tz.hitNy, hnz = tz.hitNz;
				float vr = 0f, vg = 0f, vb = 0f;
				double nl = hnx * lx + hny * ly + hnz * lz;
				if (sunOn && st > 0.02f && nl > 0) {
					tz2.march(hx + hnx * NOFF, hy + hny * NOFF, hz + hnz * NOFF, lx, ly, lz, hitShadowRange);
					if (!tz2.hit) {
						vr = tz2.tr;
						vg = tz2.tg;
						vb = tz2.tb;
					}
				}
				int qx = Mth.floor(hx + hnx * 0.5), qy = Mth.floor(hy + hny * 0.5), qz = Mth.floor(hz + hnz * 0.5);
				float amb = Math.max(w.skyLight(qx, qy, qz) * skyB, 0.5f * w.blockLight(qx, qy, qz)) / 15f;
				float eSun = nl > 0 ? (float) (ESUN * st * nl) : 0f;
				lr = thR * ho.ar * (eSun * w.sunR * vr + EAMB * amb);
				lg = thG * ho.ag * (eSun * w.sunG * vg + EAMB * amb);
				lb = thB * ho.ab * (eSun * w.sunB * vb + EAMB * amb);
			} else {
				lr = thR * rr;
				lg = thG * rg;
				lb = thB * rb;
			}
			sumR += lr;
			sumG += lg;
			sumB += lb;
			refR += rr;
			refG += rg;
			refB += rb;
		}
		float gr = Mth.clamp(sumR / Math.max(refR, 1e-4f), 0f, 3f);
		float gg = Mth.clamp(sumG / Math.max(refG, 1e-4f), 0f, 3f);
		float gb = Mth.clamp(sumB / Math.max(refB, 1e-4f), 0f, 3f);
		// keep the colour bleed but halve its chroma: it tints shadows, it must not repaint them
		float mean = (gr + gg + gb) / 3f;
		oR = mean + (gr - mean) * GI_CHROMA;
		oG = mean + (gg - mean) * GI_CHROMA;
		oB = mean + (gb - mean) * GI_CHROMA;
	}

	// ------------------------------------------------------------------ emitters

	/** Collects the nearest emitters around block (X,Y,Z). */
	void gatherLights(int X, int Y, int Z) {
		cn = 0;
		if (!cfg.blockLight || maxLights <= 0) return;
		double r2 = lightRadius * lightRadius;
		int bsx = X >> 4, bsy = Y >> 4, bsz = Z >> 4;
		for (int sx = bsx - 1; sx <= bsx + 1; sx++) {
			for (int sy = bsy - 1; sy <= bsy + 1; sy++) {
				for (int sz = bsz - 1; sz <= bsz + 1; sz++) {
					Sect s = w.sect(sx, sy, sz);
					if (s == null || s.emitters == null) continue;
					for (int e = 0; e < s.nEmit; e++) {
						int p = s.emitters[e];
						int ex = (sx << 4) + (p & 15), ey = (sy << 4) + (p >> 8), ez = (sz << 4) + ((p >> 4) & 15);
						double ddx = ex - X, ddy = ey - Y, ddz = ez - Z;
						double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
						if (d2 > r2 || d2 < 0.5) continue;
						int slot;
						if (cn < maxLights) {
							slot = cn++;
						} else {
							int worst = 0;
							for (int i = 1; i < cn; i++) if (cd[i] > cd[worst]) worst = i;
							if (cd[worst] <= d2) continue;
							slot = worst;
						}
						int id = s.ids == null ? s.uniform : s.ids[p];
						Optics eo = Optics.byIdx[id];
						cd[slot] = d2;
						cx[slot] = ex + 0.5;
						cy[slot] = ey + 0.5;
						cz[slot] = ez + 0.5;
						cl[slot] = eo.light;
						cr[slot] = eo.er;
						cg[slot] = eo.eg;
						cb[slot] = eo.eb;
					}
				}
			}
		}
	}

	/** Light multiplier (rgb in oR..oB, <= 1) caused by emitters that can or cannot see this point. */
	void lights(double px, double py, double pz, int nx, int ny, int nz) {
		oR = oG = oB = 1f;
		if (cn == 0) return;
		double ox = px + nx * NOFF, oy = py + ny * NOFF, oz = pz + nz * NOFF;
		double du = 0, dv = 0, tR = 0, tG = 0, tB = 0;
		for (int i = 0; i < cn; i++) {
			double ddx = cx[i] - ox, ddy = cy[i] - oy, ddz = cz[i] - oz;
			double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
			double lvl = (cl[i] - 0.9 * d) / 15.0;
			if (lvl <= 0) continue;
			du += lvl;
			double nl = (nx * ddx + ny * ddy + nz * ddz) / d;
			if (nl <= 0.02) continue;
			float vr = 1f, vg = 1f, vb = 1f;
			double len = d - 0.75;
			if (len > 0.05) {
				tz.march(ox, oy, oz, ddx / d, ddy / d, ddz / d, len);
				if (tz.hit) continue;
				vr = tz.tr;
				vg = tz.tg;
				vb = tz.tb;
			}
			double lum = (vr + vg + vb) / 3.0;
			dv += lvl * lum;
			tR += lvl * vr * cr[i];
			tG += lvl * vg * cg[i];
			tB += lvl * vb * cb[i];
		}
		if (du <= 0) return;
		float r = (float) (dv / du);
		float dark = faceBs * cfg.torchShadow * (1f - r);
		float mix = cfg.warmStrength * faceBs * r;
		float nr = 1f, ng = 1f, nb = 1f;
		if (dv > 1e-6) {
			double m = Math.max(tR, Math.max(tG, Math.max(tB, 1e-9)));
			nr = (float) (tR / m);
			ng = (float) (tG / m);
			nb = (float) (tB / m);
		}
		oR = (1f - dark) * (1f + mix * (nr - 1f));
		oG = (1f - dark) * (1f + mix * (ng - 1f));
		oB = (1f - dark) * (1f + mix * (nb - 1f));
	}

	// ------------------------------------------------------------------ composition

	/**
	 * Final colour multiplier on top of the vanilla image. The physical estimate
	 *   skyShare * GI + sunShare * (sun visibility * cos)
	 * is faded in proportion to the vanilla sky light at the face, because where vanilla already
	 * shows darkness (caves, interiors) darkening again would count the same occlusion twice.
	 */
	void combine(float sunR, float sunG, float sunB, float gR, float gG, float gB, float lR, float lG, float lB) {
		float share = cfg.shadowStrength;
		float skyShare = 1f - share;
		float gs = cfg.giStrength;
		oR = comb(sunR, gR, lR, share, skyShare, gs);
		oG = comb(sunG, gG, lG, share, skyShare, gs);
		oB = comb(sunB, gB, lB, share, skyShare, gs);
	}

	private float comb(float sv, float g, float lm, float share, float skyShare, float gs) {
		float sunTerm = 1f + st * (sv * faceLam - 1f);
		float gg = 1f + (g - 1f) * gs;
		float mp = skyShare * gg + share * sunTerm;
		float m = (1f + faceK * (mp - 1f)) * lm;
		return m < 0f ? 0f : Math.min(m, 2f);
	}

	private static double rand(int s) {
		s ^= s >>> 16;
		s *= 0x7feb352d;
		s ^= s >>> 15;
		s *= 0x846ca68b;
		s ^= s >>> 16;
		return (s >>> 8) / 16777216.0;
	}
}
