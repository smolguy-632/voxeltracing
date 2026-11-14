package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** A 16x16x16 snapshot of the world as optics palette indices, plus its baked overlay meshes. */
final class Sect {
	static final int PENDING = 0, READY = 1, MISSING = 2;

	final int sx, sy, sz;
	int state = PENDING;

	/** 4096 optics indices (y<<8 | z<<4 | x), or null when the whole section is `uniform`. */
	char[] ids;
	char uniform;
	/** True when nothing in here can stop or tint a ray (only air-like blocks). */
	boolean empty = true;
	/** Packed local positions (x | z<<4 | y<<8) of light-emitting blocks. */
	int[] emitters;
	int nEmit;

	boolean dirty = true;
	boolean urgent;
	boolean removed;
	int bakedKey = Integer.MIN_VALUE;
	long sortKey;
	/** nanoTime before which baking is postponed (waiting for vanilla light data to arrive). */
	long notBefore;

	/** Multiply pass (colour 0..1) and boost pass (colour = extra brightness 0..1). */
	VertexBuffer vbMul, vbBoost;
	int cMul, cBoost;

	Sect(int sx, int sy, int sz) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
	}

	void load(ClientLevel level, int emitMin) {
		ids = null;
		uniform = 0;
		empty = true;
		emitters = null;
		nEmit = 0;

		ChunkAccess ch = level.getChunk(sx, sz, ChunkStatus.FULL, false);
		if (ch == null) {
			state = MISSING;
			return;
		}
		state = READY;
		int si = ch.getSectionIndexFromSectionY(sy);
		if (si < 0 || si >= ch.getSectionsCount()) return;
		LevelChunkSection sec = ch.getSection(si);
		if (sec == null || sec.hasOnlyAir()) return;

		char[] a = new char[4096];
		int[] em = null;
		int ne = 0;
		BlockState last = null;
		Optics lo = Optics.NOTHING;
		boolean allAir = true;
		boolean same = true;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState st = sec.getBlockState(x, y, z);
					if (st != last) {
						last = st;
						lo = Optics.of(st);
					}
					int i = (y << 8) | (z << 4) | x;
					a[i] = (char) lo.idx;
					if (a[i] != a[0]) same = false;
					if (lo.kind != Optics.AIR) allAir = false;
					if (lo.light >= emitMin) {
						if (em == null) em = new int[16];
						if (ne == em.length) em = java.util.Arrays.copyOf(em, ne * 2);
						em[ne++] = i;
					}
				}
			}
		}
		empty = allAir;
		emitters = em;
		nEmit = ne;
		if (same) {
			uniform = a[0];
		} else {
			ids = a;
		}
	}

	void clearMesh() {
		if (vbMul != null) {
			vbMul.close();
			vbMul = null;
		}
		if (vbBoost != null) {
			vbBoost.close();
			vbBoost = null;
		}
		cMul = 0;
		cBoost = 0;
	}

	void close() {
		removed = true;
		clearMesh();
	}
}
