package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * One 16x16x16 block of the world, reduced to what the ray tracer needs.
 *
 * Every block lives on a 16x16x16 sub-voxel grid (one sub-voxel = one texture pixel).
 * Full opaque cubes only need one bit per block; blocks with a partial shape (stairs,
 * slabs, fences, panes, trapdoors, carpets, leaves, glass...) store a 4096-bit mask of
 * which sub-voxels are filled. Glass also carries a tint palette index so light passing
 * through it is attenuated and coloured per channel.
 *
 * Block-level bits (long[64], index (y<<8)|(z<<4)|x):
 *   opaque  - full opaque cube
 *   foliage - full-cube foliage (attenuated per block)
 *   glass   - full-cube glass (tinted per block via ptint)
 * Partial blocks: ptype/pmask/ptint per block + the shared mask list.
 *
 * Also owns the baked overlay mesh (a static VBO).
 */
final class VoxelSection {
	static final int NOT_TRIED = 0, READY = 1, NO_CHUNK = 2;
	static final int MAX_EMITTERS = 96;
	static final float FOLIAGE_T = 0.55f;         // per full block of foliage
	static final float FOLIAGE_SUB;               // per single 1/16 sub-voxel
	private static final int[] EMPTY = new int[0];
	private static final int[] SCRATCH = new int[4096]; // render thread only

	// Block categories (low 3 bits of classify()).
	static final int T_NONE = 0, T_OPAQUE = 1, T_FOLIAGE = 2, T_GLASS = 3,
			T_PARTIAL_SOLID = 4, T_PARTIAL_FOLIAGE = 5, T_PARTIAL_GLASS = 6;
	static final int TYPE_MASK = 7;

	// Glass tint palette: 0 = clear glass, 1..16 = dyed stained glass, 17 = tinted glass.
	static final int GLASS_CLEAR = 0, GLASS_TINTED = 17;
	static final int TINTS = 18;
	private static final String[] DYE_NAMES = {
		"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
	};
	private static final int[] DYE_RGB = {
		0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
		0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
	};
	/** Transmittance of one full block of each palette entry, per RGB channel. */
	static final float[][] TINT = new float[TINTS][3];
	/** Same, per single sub-voxel (= ^(1/16)), for the partial-mask grid walk. */
	static final float[][] TINT_SUB = new float[TINTS][3];

	static {
		TINT[GLASS_CLEAR] = new float[]{0.93f, 0.93f, 0.93f};
		TINT[GLASS_TINTED] = new float[]{0.22f, 0.22f, 0.22f};
		for (int i = 0; i < 16; i++) {
			int rgb = DYE_RGB[i];
			float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
			float mx = Math.max(r, Math.max(g, b));
			float k = 0.85f / mx;
			TINT[i + 1] = new float[]{r * k, g * k, b * k};
		}
		for (int i = 0; i < TINTS; i++)
			for (int c = 0; c < 3; c++)
				TINT_SUB[i][c] = (float) Math.pow(TINT[i][c], 1.0 / 16.0);
		FOLIAGE_SUB = (float) Math.pow(FOLIAGE_T, 1.0 / 16.0);
	}

	/** Shared voxelization cache: block state -> sub-voxel mask + face coverage flags. */
	private static final IdentityHashMap<BlockState, Mask> VOX_CACHE = new IdentityHashMap<>();
	/** Sentinel: the mask covers the whole block. */
	private static final Mask FULL = new Mask(null, 0x3F);

	/** A partial block's 4096-bit shape plus, per face direction, whether that side's 16x16 slice is fully filled. */
	static final class Mask {
		final long[] bits;
		final int covered;

		Mask(long[] bits, int covered) {
			this.bits = bits;
			this.covered = covered;
		}
	}

	final int sx, sy, sz;

	int loadState = NOT_TRIED;
	long[] opaque;   // null => nothing solid in this section
	long[] foliage;  // null => no full-cube foliage
	long[] glass;    // null => no full-cube glass
	byte[] ptype;    // null => no partial blocks; per block: T_PARTIAL_*
	byte[] ptint;    // null => no glass; per block: tint palette index
	byte[] pmask;    // null => no partial blocks; per block: 0 = none, else masks index + 1
	ArrayList<Mask> masks;
	int[] emitters = EMPTY;
	int emitterCount;

	boolean dirty = true;
	boolean urgent;
	boolean removed;
	int bakedKey = Integer.MIN_VALUE;
	int sortKey;

	VertexBuffer vb;
	int quadCount;

	VoxelSection(int sx, int sy, int sz) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
	}

	/**
	 * Bits 0-2: category. Bits 3-10: shape fingerprint (catches stair/trapdoor rotations).
	 * Bits 11+: light emission. 0 = air / non-blocking.
	 */
	static int classify(BlockState st) {
		if (st.isAir()) return 0;
		VoxelShape shape = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		int type;
		if (st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) type = T_OPAQUE;
		else if (shape.isEmpty() || !st.blocksMotion()) type = T_NONE;
		else if (glassIndex(st) >= 0) type = T_GLASS;
		else if (st.is(BlockTags.LEAVES)) type = T_FOLIAGE;
		else type = T_PARTIAL_SOLID;
		return type | ((shape.hashCode() & 0xFF) << 3) | (st.getLightEmission() << 11);
	}

	/** Palette index for glass-family blocks, or -1 if not glass. */
	private static int glassIndex(BlockState st) {
		String p = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		if (p.equals("glass") || p.equals("glass_pane")) return GLASS_CLEAR;
		if (p.equals("tinted_glass")) return GLASS_TINTED;
		int cut = p.indexOf("_stained_glass");
		if (cut > 0 && (p.length() == cut + 14 || p.endsWith("_stained_glass_pane") && cut == p.indexOf("_stained_glass"))) {
			String dye = p.substring(0, cut);
			for (int i = 0; i < 16; i++)
				if (DYE_NAMES[i].equals(dye)) return i + 1;
		}
		return -1;
	}

	/** Block state -> 4096-bit sub-voxel mask (or FULL). Cached per block state. */
	private static Mask voxelize(BlockState st) {
		Mask cached = VOX_CACHE.get(st);
		if (cached != null) return cached;
		long[] m = new long[64];
		int[] count = {0};
		st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).forAllBoxes(
				(x1, y1, z1, x2, y2, z2) -> {
					int x0 = Math.max(0, (int) Math.floor(x1 * 16)), xe = Math.min(16, (int) Math.ceil(x2 * 16));
					int y0 = Math.max(0, (int) Math.floor(y1 * 16)), ye = Math.min(16, (int) Math.ceil(y2 * 16));
					int z0 = Math.max(0, (int) Math.floor(z1 * 16)), ze = Math.min(16, (int) Math.ceil(z2 * 16));
					for (int y = y0; y < ye; y++)
						for (int z = z0; z < ze; z++)
							for (int x = x0; x < xe; x++) {
								int i = (y << 8) | (z << 4) | x;
								if ((m[i >>> 6] & (1L << (i & 63))) == 0L) {
									m[i >>> 6] |= 1L << (i & 63);
									count[0]++;
								}
							}
				});
		Mask result;
		if (count[0] == 4096) {
			result = FULL;
		} else {
			int covered = 0;
			for (int dd = 0; dd < 6; dd++) {
				int axis = dd >> 1; // 0=y, 1=z, 2=x
				int side = (dd & 1) == 0 ? 0 : 15;
				boolean full = true;
				for (int a = 0; a < 16 && full; a++)
					for (int b = 0; b < 16; b++) {
						int y, z, x;
						if (axis == 0) {
							y = side;
							z = a;
							x = b;
						} else if (axis == 1) {
							z = side;
							y = a;
							x = b;
						} else {
							x = side;
							y = a;
							z = b;
						}
						int i = (y << 8) | (z << 4) | x;
						if ((m[i >>> 6] & (1L << (i & 63))) == 0L) {
							full = false;
							break;
						}
					}
				if (full) covered |= 1 << dd;
			}
			result = new Mask(m, covered);
		}
		if (VOX_CACHE.size() >= 4096) VOX_CACHE.clear();
		VOX_CACHE.put(st, result);
		return result;
	}

	void invalidateData() {
		loadState = NOT_TRIED;
		opaque = null;
		foliage = null;
		glass = null;
		ptype = null;
		ptint = null;
		pmask = null;
		masks = null;
		emitters = EMPTY;
		emitterCount = 0;
	}

	void load(ClientLevel level) {
		opaque = null;
		foliage = null;
		glass = null;
		ptype = null;
		ptint = null;
		pmask = null;
		masks = null;
		emitters = EMPTY;
		emitterCount = 0;

		ChunkAccess chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) {
			loadState = NO_CHUNK;
			return;
		}
		loadState = READY;

		LevelChunkSection[] arr = chunk.getSections();
		int idx = sy - level.getMinSection();
		if (idx < 0 || idx >= arr.length) return;
		LevelChunkSection cs = arr[idx];
		if (cs == null || cs.hasOnlyAir()) return;

		long[] op = null, fo = null, gl = null;
		byte[] pt = null, pi = null, pm = null;
		ArrayList<Mask> mk = null;
		int n = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState st = cs.getBlockState(x, y, z);
					if (st.isAir()) continue;
					int c = classify(st);
					int type = c & TYPE_MASK;
					int bi = (y << 8) | (z << 4) | x;
					if (type != T_NONE) {
						if (type == T_OPAQUE) {
							if (op == null) op = new long[64];
							op[bi >>> 6] |= 1L << bi;
						} else {
							Mask m = voxelize(st);
							if (m == FULL) {
								if (type == T_PARTIAL_SOLID) {
									if (op == null) op = new long[64];
									op[bi >>> 6] |= 1L << bi;
								} else if (type == T_FOLIAGE) {
									if (fo == null) fo = new long[64];
									fo[bi >>> 6] |= 1L << bi;
								} else {
									if (gl == null) gl = new long[64];
									gl[bi >>> 6] |= 1L << bi;
									if (pi == null) pi = new byte[4096];
									pi[bi] = (byte) glassIndex(st);
								}
							} else {
								if (pt == null) {
									pt = new byte[4096];
									pm = new byte[4096];
									mk = new ArrayList<>();
								}
								if (mk.size() < 255) {
									mk.add(m);
									pt[bi] = (byte) type;
									pm[bi] = (byte) mk.size();
									if (type == T_GLASS) {
										if (pi == null) pi = new byte[4096];
										pi[bi] = (byte) glassIndex(st);
									}
								} else { // mask list overflow: treat as opaque
									if (op == null) op = new long[64];
									op[bi >>> 6] |= 1L << bi;
								}
							}
						}
					}
					int e = st.getLightEmission();
					if (e > 0) SCRATCH[n++] = bi | (e << 12);
				}
			}
		}

		// Keep only emitters that touch open space (buried lava / ore-lit blocks never light anything).
		int[] keep = null;
		int kept = 0;
		for (int k = 0; k < n && kept < MAX_EMITTERS; k++) {
			int v = SCRATCH[k];
			int i = v & 4095;
			int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
			boolean exposed = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15
					|| !bit(op, x - 1, y, z) || !bit(op, x + 1, y, z)
					|| !bit(op, x, y - 1, z) || !bit(op, x, y + 1, z)
					|| !bit(op, x, y, z - 1) || !bit(op, x, y, z + 1);
			if (!exposed) continue;
			if (keep == null) keep = new int[Math.min(n, MAX_EMITTERS)];
			keep[kept++] = v;
		}
		if (keep != null) {
			emitters = keep;
			emitterCount = kept;
		}
		opaque = op;
		foliage = fo;
		glass = gl;
		ptype = pt;
		ptint = pi;
		pmask = pm;
		masks = mk;
	}

	private static boolean bit(long[] bits, int x, int y, int z) {
		if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return false;
		if (bits == null) return false;
		int i = (y << 8) | (z << 4) | x;
		return (bits[i >>> 6] & (1L << i)) != 0;
	}

	/**
	 * Multiplies acc[0..2] (RGB transmittance) by this block's attenuation along a ray that
	 * enters the block at local (lx,ly,lz) in [0,1), with unit direction (dx,dy,dz), staying
	 * inside for `len` block units. Partial blocks are walked on their 16x16x16 grid: one
	 * sub-voxel = one texture pixel, so stairs/slabs/fences/panes have true-to-shape shadows
	 * and glass tints the light passing through it. Returns false when fully blocked.
	 */
	boolean attenuate(int bi, double lx, double ly, double lz, double dx, double dy, double dz,
					  double len, float[] acc) {
		int type = ptype != null ? ptype[bi] : 0;
		if (type == 0) {
			if (opaque != null && (opaque[bi >>> 6] & (1L << bi)) != 0L) return false;
			if (foliage != null && (foliage[bi >>> 6] & (1L << bi)) != 0L)
				return mul(acc, FOLIAGE_T, FOLIAGE_T, FOLIAGE_T);
			if (glass != null && (glass[bi >>> 6] & (1L << bi)) != 0L) {
				float[] t = TINT[ptint != null ? ptint[bi] : GLASS_CLEAR];
				return mul(acc, t[0], t[1], t[2]);
			}
			return true;
		}

		// ---- partial block: walk the 16x16x16 grid inside this block
		long[] mask = masks.get((pmask[bi] & 255) - 1).bits;
		boolean solid = type == T_PARTIAL_SOLID;
		boolean foliagePart = type == T_FOLIAGE;
		// T_GLASS in ptype means a partial glass block; fall back to clear glass if the
		// tint index is somehow missing instead of crashing.
		float[] ts = TINT_SUB[type == T_GLASS && ptint != null ? (ptint[bi] & 255) : GLASS_CLEAR];

		final double inf = Double.POSITIVE_INFINITY;
		// entry point in sub-voxel units [0,16)
		double ex = Math.min(Math.max(lx, 0.0), 0.9999999) * 16.0;
		double ey = Math.min(Math.max(ly, 0.0), 0.9999999) * 16.0;
		double ez = Math.min(Math.max(lz, 0.0), 0.9999999) * 16.0;
		int x = (int) ex, y = (int) ey, z = (int) ez;
		double lenSub = len * 16.0;
		if (lenSub <= 0) return true;
		double invX = dx != 0 ? 1.0 / Math.abs(dx) : inf;
		double invY = dy != 0 ? 1.0 / Math.abs(dy) : inf;
		double invZ = dz != 0 ? 1.0 / Math.abs(dz) : inf;
		double tMaxX = dx > 0 ? (x + 1 - ex) * invX : dx < 0 ? (ex - x) * invX : inf;
		double tMaxY = dy > 0 ? (y + 1 - ey) * invY : dy < 0 ? (ey - y) * invY : inf;
		double tMaxZ = dz > 0 ? (z + 1 - ez) * invZ : dz < 0 ? (ez - z) * invZ : inf;

		while (true) {
			int i = (y << 8) | (z << 4) | x;
			if ((mask[i >>> 6] & (1L << (i & 63))) != 0L) {
				if (solid) return false;
				if (foliagePart) {
					if (!mul(acc, FOLIAGE_SUB, FOLIAGE_SUB, FOLIAGE_SUB)) return false;
				} else {
					if (!mul(acc, ts[0], ts[1], ts[2])) return false;
				}
			}
			double tn;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) {
					tn = tMaxX;
					x += dx > 0 ? 1 : -1;
					tMaxX += invX;
				} else {
					tn = tMaxZ;
					z += dz > 0 ? 1 : -1;
					tMaxZ += invZ;
				}
			} else if (tMaxY < tMaxZ) {
				tn = tMaxY;
				y += dy > 0 ? 1 : -1;
				tMaxY += invY;
			} else {
				tn = tMaxZ;
				z += dz > 0 ? 1 : -1;
				tMaxZ += invZ;
			}
			if (tn >= lenSub) return true; // left this block's span
			if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return true; // safety
		}
	}

	private static boolean mul(float[] acc, float r, float g, float b) {
		acc[0] *= r;
		acc[1] *= g;
		acc[2] *= b;
		return acc[0] > 0.06f || acc[1] > 0.06f || acc[2] > 0.06f;
	}

	void clearMesh() {
		if (vb != null) {
			vb.close();
			vb = null;
		}
		quadCount = 0;
	}

	void close() {
		removed = true;
		clearMesh();
	}
}
