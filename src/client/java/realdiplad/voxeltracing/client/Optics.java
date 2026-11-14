package realdiplad.voxeltracing.client;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.List;

/**
 * Optical description of one BlockState, derived generically from the game's own data so that
 * every vanilla and modded block works without a hand-written list:
 *  - geometry      : exact collision AABBs (stairs, slabs, fences, panes, walls, carpets, ...)
 *  - light transport: OPAQUE blocks stop light, TINT blocks (glass, ice, slime, honey, water)
 *                     colour it once per crossing, FOLIAGE absorbs it per unit of path length
 *  - albedo        : average map colour, used for colour bleeding in global illumination
 *  - emission      : vanilla light level plus a colour
 */
final class Optics {
	static final int AIR = 0, OPAQUE = 1, BOXES = 2, TINT = 3, FOLIAGE = 4;

	private static final float[] FULL = {0f, 0f, 0f, 1f, 1f, 1f};
	private static final int MAX_BOXES = 24;
	private static final int MAX_PALETTE = 65000;

	/** Palette index -> optics. Index 0 is always "nothing". */
	static Optics[] byIdx = new Optics[1024];
	private static int count = 0;
	private static Optics[] byState = new Optics[0];

	static final Optics NOTHING = new Optics(AIR, null, false, 1f, 1f, 1f, 0.5f, 0.5f, 0.5f, 0, 0f, 0f, 0f);

	final int kind;
	/** minX,minY,minZ,maxX,maxY,maxZ per box, block-local 0..1. */
	final float[] boxes;
	final boolean isFull;
	/** Whether the overlay is drawn on this block's own faces (solid render layer only). */
	final boolean receives;
	/** TINT: transmittance per crossing. FOLIAGE: transmittance per block of path length. */
	final float tr, tg, tb;
	final float lnr, lng, lnb;
	final float ar, ag, ab;
	final int light;
	final float er, eg, eb;
	/** Bit d set: this block fully covers the unit face d (0:-X 1:+X 2:-Y 3:+Y 4:-Z 5:+Z). */
	final int covers;
	int idx;

	private Optics(int kind, float[] boxes, boolean receives, float tr, float tg, float tb,
				   float ar, float ag, float ab, int light, float er, float eg, float eb) {
		this.kind = kind;
		this.boxes = boxes == null ? new float[0] : boxes;
		this.isFull = boxes != null && boxes.length == 6 && isFullBox(boxes);
		this.receives = receives;
		this.tr = tr;
		this.tg = tg;
		this.tb = tb;
		this.lnr = (float) Math.log(Math.max(tr, 0.02f));
		this.lng = (float) Math.log(Math.max(tg, 0.02f));
		this.lnb = (float) Math.log(Math.max(tb, 0.02f));
		this.ar = ar;
		this.ag = ag;
		this.ab = ab;
		this.light = light;
		this.er = er;
		this.eg = eg;
		this.eb = eb;
		this.covers = computeCovers(kind, this.boxes);
		if (count >= byIdx.length) byIdx = Arrays.copyOf(byIdx, count * 2);
		this.idx = count++;
		byIdx[this.idx] = this;
	}

	// ------------------------------------------------------------------ lookup

	static Optics of(BlockState st) {
		int id = Block.getId(st);
		if (id < 0) return build(st);
		if (id >= byState.length) byState = Arrays.copyOf(byState, Math.max(id + 1, byState.length * 2 + 1024));
		Optics o = byState[id];
		if (o == null) {
			o = build(st);
			byState[id] = o;
		}
		return o;
	}

	/** Forget every derived entry (world change). Index 0 stays valid. */
	static void reset() {
		for (int i = 1; i < count; i++) byIdx[i] = null;
		count = 1;
		byState = new Optics[0];
	}

	boolean sameAs(Optics o) {
		return o == this || (kind == o.kind && light == o.light && receives == o.receives
				&& tr == o.tr && tg == o.tg && tb == o.tb && ar == o.ar && ag == o.ag && ab == o.ab
				&& Arrays.equals(boxes, o.boxes));
	}

	// ------------------------------------------------------------------ derivation

	private static Optics build(BlockState st) {
		if (st.isAir()) return NOTHING;
		if (count >= MAX_PALETTE) return NOTHING;
		try {
			return compute(st);
		} catch (Throwable t) {
			return NOTHING; // a modded block whose shape needs a real level: treat as open
		}
	}

	private static Optics compute(BlockState st) {
		Block b = st.getBlock();
		String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
		int light = st.getLightEmission();

		MapColor mc = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		boolean hasColor = mc != null && mc != MapColor.NONE && mc.col != 0;
		float[] rgb = hasColor
				? new float[]{((mc.col >> 16) & 255) / 255f, ((mc.col >> 8) & 255) / 255f, (mc.col & 255) / 255f}
				: new float[]{0.55f, 0.55f, 0.55f};
		float[] alb = {clamp(rgb[0], 0.10f, 0.92f), clamp(rgb[1], 0.10f, 0.92f), clamp(rgb[2], 0.10f, 0.92f)};
		float mx = Math.max(rgb[0], Math.max(rgb[1], Math.max(rgb[2], 0.001f)));
		float[] norm = {rgb[0] / mx, rgb[1] / mx, rgb[2] / mx};

		FluidState fs = st.getFluidState();
		if (b instanceof LiquidBlock && !fs.isEmpty()) {
			float[] em = emission(light, path, norm, hasColor, true);
			if (fs.is(FluidTags.LAVA)) {
				return new Optics(OPAQUE, FULL, false, 1f, 1f, 1f, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
			}
			return new Optics(TINT, FULL, false, 0.80f, 0.90f, 0.96f, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
		}

		if (st.is(BlockTags.LEAVES)) {
			return new Optics(FOLIAGE, FULL, false, 0.14f + 0.50f * norm[0], 0.14f + 0.50f * norm[1],
					0.14f + 0.50f * norm[2], alb[0], alb[1], alb[2], light, 0f, 0f, 0f);
		}

		if (st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			float[] em = emission(light, path, norm, hasColor, true);
			return new Optics(OPAQUE, FULL, true, 1f, 1f, 1f, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
		}

		float[] em = emission(light, path, norm, hasColor, false);
		RenderType rt = ItemBlockRenderTypes.getChunkRenderType(st);
		boolean translucent = rt == RenderType.translucent();
		boolean solidLayer = rt == RenderType.solid();

		VoxelShape coll = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		float[] boxes;
		if (!coll.isEmpty()) {
			boxes = toBoxes(coll);
		} else if (path.equals("snow")) {
			// snow layers have no collision at one layer but are a visible slab
			VoxelShape vis = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			boxes = vis.isEmpty() ? null : toBoxes(vis);
		} else {
			boxes = null;
		}
		if (boxes == null || boxes.length == 0) {
			// plants, torches, rails...: no occlusion, but may still emit light
			return new Optics(AIR, null, false, 1f, 1f, 1f, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
		}

		boolean full = boxes.length == 6 && isFullBox(boxes);
		boolean glass = translucent || path.contains("glass")
				|| (full && !st.canOcclude() && rt == RenderType.cutout());
		if (glass) {
			float tr, tg, tb;
			if (path.contains("tinted")) {
				tr = 0.15f;
				tg = 0.15f;
				tb = 0.17f;
			} else if (!hasColor) {
				tr = 0.94f;
				tg = 0.96f;
				tb = 0.98f;
			} else {
				tr = 0.18f + 0.72f * norm[0];
				tg = 0.18f + 0.72f * norm[1];
				tb = 0.18f + 0.72f * norm[2];
			}
			return new Optics(TINT, boxes, false, tr, tg, tb, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
		}
		return new Optics(BOXES, boxes, solidLayer, 1f, 1f, 1f, alb[0], alb[1], alb[2], light, em[0], em[1], em[2]);
	}

	private static float[] emission(int light, String path, float[] norm, boolean hasColor, boolean solidBlock) {
		if (light <= 0) return new float[]{0f, 0f, 0f};
		float[] c = {1.00f, 0.78f, 0.50f};
		if (path.contains("soul")) {
			c = new float[]{0.40f, 0.75f, 1.00f};
		} else if (path.contains("redstone")) {
			c = new float[]{1.00f, 0.25f, 0.15f};
		} else if (solidBlock && hasColor) {
			for (int i = 0; i < 3; i++) c[i] = 0.5f * c[i] + 0.5f * norm[i];
		}
		return c;
	}

	private static float[] toBoxes(VoxelShape s) {
		List<AABB> l = s.toAabbs();
		int n = Math.min(l.size(), MAX_BOXES);
		float[] a = new float[n * 6];
		for (int i = 0; i < n; i++) {
			AABB bb = l.get(i);
			a[i * 6] = (float) bb.minX;
			a[i * 6 + 1] = (float) bb.minY;
			a[i * 6 + 2] = (float) bb.minZ;
			a[i * 6 + 3] = (float) bb.maxX;
			a[i * 6 + 4] = (float) bb.maxY;
			a[i * 6 + 5] = (float) bb.maxZ;
		}
		return a;
	}

	private static boolean isFullBox(float[] b) {
		return b[0] <= 0.001f && b[1] <= 0.001f && b[2] <= 0.001f && b[3] >= 0.999f && b[4] >= 0.999f && b[5] >= 0.999f;
	}

	private static int computeCovers(int kind, float[] b) {
		if (kind == OPAQUE) return 63;
		if (kind != BOXES) return 0;
		int m = 0;
		final float lo = 0.001f, hi = 0.999f;
		for (int i = 0; i < b.length; i += 6) {
			boolean spanX = b[i] <= lo && b[i + 3] >= hi;
			boolean spanY = b[i + 1] <= lo && b[i + 4] >= hi;
			boolean spanZ = b[i + 2] <= lo && b[i + 5] >= hi;
			if (spanY && spanZ) {
				if (b[i] <= lo) m |= 1;
				if (b[i + 3] >= hi) m |= 2;
			}
			if (spanX && spanZ) {
				if (b[i + 1] <= lo) m |= 4;
				if (b[i + 4] >= hi) m |= 8;
			}
			if (spanX && spanY) {
				if (b[i + 2] <= lo) m |= 16;
				if (b[i + 5] >= hi) m |= 32;
			}
		}
		return m;
	}

	private static float clamp(float v, float a, float b) {
		return v < a ? a : Math.min(v, b);
	}
}
