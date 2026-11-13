package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * One 16x16x16 block of the world, reduced to what the ray tracer needs:
 * two 4096-bit bitsets (opaque / foliage) and a short list of exposed light emitters.
 * Also owns the baked overlay mesh (a static VBO).
 *
 * Voxel index = (y << 8) | (z << 4) | x, so bit (i & 63) of word (i >>> 6).
 */
final class VoxelSection {
	static final int NOT_TRIED = 0, READY = 1, NO_CHUNK = 2;
	static final int OPAQUE = 1, FOLIAGE = 2;
	static final int MAX_EMITTERS = 96;
	private static final int[] EMPTY = new int[0];
	private static final int[] SCRATCH = new int[4096]; // render thread only

	final int sx, sy, sz;

	int loadState = NOT_TRIED;
	long[] opaque;   // null => nothing solid in this section
	long[] foliage;
	/** Packed emitters: bits 0-11 voxel index, bits 12-15 light level. */
	int[] emitters = EMPTY;
	int emitterCount;

	boolean dirty = true;
	boolean urgent;
	boolean removed;
	int bakedKey = Integer.MIN_VALUE;
	int sortKey;

	VertexBuffer vb;       // per-face shade rectangles (QUADS)
	VertexBuffer vbSun;    // projected sun-shadow trapezoids, fan-triangulated (TRIANGLES)
	int quadCount;
	int trapCount;         // vertex count of vbSun

	VoxelSection(int sx, int sy, int sz) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
	}

	/** Bit 0 = opaque, bit 1 = foliage, bits 8+ = light emission. 0 = air / fully transparent. */
	static int classify(BlockState st) {
		if (st.isAir()) return 0;
		int c = 0;
		if (st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) c |= OPAQUE;
		else if (st.is(BlockTags.LEAVES)) c |= FOLIAGE;
		int e = st.getLightEmission();
		if (e > 0) c |= e << 8;
		return c;
	}

	void invalidateData() {
		loadState = NOT_TRIED;
		opaque = null;
		foliage = null;
		emitters = EMPTY;
		emitterCount = 0;
	}

	void load(ClientLevel level) {
		opaque = null;
		foliage = null;
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

		long[] op = new long[64];
		long[] fo = new long[64];
		int n = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int c = classify(cs.getBlockState(x, y, z));
					if (c == 0) continue;
					int i = (y << 8) | (z << 4) | x;
					if ((c & OPAQUE) != 0) op[i >>> 6] |= 1L << i;
					else if ((c & FOLIAGE) != 0) fo[i >>> 6] |= 1L << i;
					int e = c >>> 8;
					if (e > 0) SCRATCH[n++] = i | (e << 12);
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
	}

	private static boolean bit(long[] bits, int x, int y, int z) {
		int i = (y << 8) | (z << 4) | x;
		return (bits[i >>> 6] & (1L << i)) != 0;
	}

	void clearMesh() {
		if (vb != null) {
			vb.close();
			vb = null;
		}
		if (vbSun != null) {
			vbSun.close();
			vbSun = null;
		}
		quadCount = 0;
		trapCount = 0;
	}

	void close() {
		removed = true;
		clearMesh();
	}
}
