package net.runelite.client.plugins.gpushared.shim;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Java-side bridge for the "URRL_Scene" shared memory ring buffer.
 *
 * Layout (~32 MB):
 *
 * Header (16 bytes):
 *   [0]  write_head (uint32) — Java increments after writing a slot
 *   [4]  read_head  (uint32) — UE increments after reading a slot
 *   [8]  pad[8]
 *
 * Slots (SLOT_COUNT × SLOT_BYTES, starting at byte 16):
 *   [0]  command          (byte)  — 1=ZONE_DATA, 2=ZONE_CLEAR, 3=SCENE_CLEAR
 *   [1]  pad[3]
 *   [4]  zone_x           (int32)
 *   [8]  zone_z           (int32)
 *   [12] opaque_int_count (int32)
 *   [16] alpha_int_count  (int32)
 *   [20] level_offset_0        (int32) — int index of end of opaque plane-0 geometry
 *   [24] level_offset_1        (int32) — int index of end of opaque plane-1 geometry
 *   [28] level_offset_2        (int32) — int index of end of opaque plane-2 geometry
 *   [32] level_offset_3        (int32) — int index of end of opaque plane-3 (== opaque_int_count)
 *   [36] alpha_level_offset_0  (int32) — int index of end of alpha plane-0 geometry
 *   [40] alpha_level_offset_1  (int32) — int index of end of alpha plane-1 geometry
 *   [44] alpha_level_offset_2  (int32) — int index of end of alpha plane-2 geometry
 *   [48] alpha_level_offset_3  (int32) — int index of end of alpha plane-3 (== alpha_int_count)
 *   [52] data[]                — opaque ints then alpha ints (5 ints per vertex)
 *
 * Each vertex is encoded as 5 ints by GpuIntBuffer.put22224() + put2222():
 *   int[0] = (lh << 16) | (lx & 0xffff)   (height=lh, local-x=lx)
 *   int[1] = lz & 0xffff                   (local-z)
 *   int[2] = hsl                            (packed HSL color)
 *   int[3] = (tu << 16) | (tt & 0xffff)    (texture U + ID)
 *   int[4] = (tf << 16) | (tv & 0xffff)    (texture flags + V)
 *
 * Memory ordering:
 *   Java: write slot fields + data, releaseFence(), write write_head
 *   UE:   acquire-load write_head, read slot, Memcpy data, release-store read_head
 */
public class SceneGraphBridge
{
	public static final int CMD_ZONE_DATA    = 1;
	public static final int CMD_ZONE_CLEAR   = 2;
	public static final int CMD_SCENE_CLEAR  = 3;
	public static final int CMD_ENTITY_BATCH = 4;

	// Header offsets
	private static final int OFF_WRITE_HEAD       = 0;
	private static final int OFF_READ_HEAD        = 4;
	private static final int OFF_UE_FLAGS         = 8;   // UE→Java flags (relaxed, read each frame)
	private static final int OFF_JAVA_FLAGS       = 12;  // Java→UE flags (relaxed, written each frame)
	private static final int SLOTS_BASE           = 16;

	// UE→Java flag bits
	public static final int FLAG_PASSTHROUGH      = 1;   // UE is rendering; skip GL scene draw

	// Java→UE flag bits
	//   bit 0      = HIDE_ROOFS: show only planes <= player plane
	//   bits [2:1] = player plane (0-3), valid when HIDE_ROOFS is set
	public static final int JAVA_FLAG_HIDE_ROOFS  = 1;

	// Per-slot offsets (from each slot's base)
	private static final int SLOT_OFF_COMMAND      = 0;
	private static final int SLOT_OFF_ZONE_X       = 4;
	private static final int SLOT_OFF_ZONE_Z       = 8;
	private static final int SLOT_OFF_OPAQUE_COUNT = 12;
	private static final int SLOT_OFF_ALPHA_COUNT  = 16;
	private static final int SLOT_OFF_LO0          = 20;  // end of plane-0 geometry
	private static final int SLOT_OFF_LO1          = 24;  // end of plane-1 geometry
	private static final int SLOT_OFF_LO2          = 28;  // end of plane-2 geometry
	private static final int SLOT_OFF_LO3          = 32;  // end of plane-3 (== opaque_count)
	private static final int SLOT_OFF_ALO0         = 36;  // end of alpha plane-0 geometry
	private static final int SLOT_OFF_ALO1         = 40;  // end of alpha plane-1 geometry
	private static final int SLOT_OFF_ALO2         = 44;  // end of alpha plane-2 geometry
	private static final int SLOT_OFF_ALO3         = 48;  // end of alpha plane-3 (== alpha_count)
	private static final int SLOT_OFF_DATA         = 52;

	private static final int SLOT_COUNT      = 256;
	private static final int SLOT_BYTES      = 2 * 1024 * 1024;
	private static final int TOTAL_SHM_BYTES = SLOTS_BASE + SLOT_COUNT * SLOT_BYTES;

	private static final int MAX_SLOT_DATA_INTS = (SLOT_BYTES - SLOT_OFF_DATA) / 4;

	// ── JNI ──────────────────────────────────────────────────────────────────

	public native long openSceneMemory(String name);
	public native void closeSceneMemory(long handle);
	public native ByteBuffer mapSceneBuffer(long handle);

	// ── State ─────────────────────────────────────────────────────────────────

	private long nativeHandle = 0;
	private ByteBuffer buf;
	private int localWriteHead = 0;

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	public void init(String name)
	{
		nativeHandle = openSceneMemory(name);
		if (nativeHandle == 0)
		{
			throw new RuntimeException("Failed to open scene shared memory: " + name);
		}
		buf = mapSceneBuffer(nativeHandle).order(ByteOrder.LITTLE_ENDIAN);
		// Reset ring; UE will park its read_head at write_head=0 on its own init.
		buf.putInt(OFF_WRITE_HEAD, 0);
		buf.putInt(OFF_READ_HEAD, 0);
		localWriteHead = 0;
	}

	public void shutdown()
	{
		if (nativeHandle != 0)
		{
			closeSceneMemory(nativeHandle);
			nativeHandle = 0;
		}
	}

	// ── UE→Java flags ─────────────────────────────────────────────────────────

	/** Read the flags UE wrote into the SHM header at offset 8. Returns 0 if not connected. */
	public int readUEFlags()
	{
		if (buf == null) return 0;
		VarHandle.acquireFence();
		return buf.getInt(OFF_UE_FLAGS);
	}

	public boolean isPassthrough()
	{
		return (readUEFlags() & FLAG_PASSTHROUGH) != 0;
	}

	// ── Protocol helpers ──────────────────────────────────────────────────────

	private boolean isFull()
	{
		VarHandle.acquireFence();
		int readHead = buf.getInt(OFF_READ_HEAD);
		// Signed subtraction works across 32-bit wrap as long as the distance
		// never exceeds Integer.MAX_VALUE, which is impossible at SLOT_COUNT = 256.
		return (localWriteHead - readHead) >= SLOT_COUNT;
	}

	private void waitForSpace()
	{
		while (isFull())
		{
			Thread.yield();
		}
	}

	/** Byte offset of slot [head % SLOT_COUNT] from the start of the buffer. */
	private int slotBase(int head)
	{
		return SLOTS_BASE + (head & (SLOT_COUNT - 1)) * SLOT_BYTES;
	}

	private void commitSlot()
	{
		localWriteHead++;
		VarHandle.releaseFence();
		buf.putInt(OFF_WRITE_HEAD, localWriteHead);
	}

	// ── Public send methods ───────────────────────────────────────────────────

	/** Write Java→UE flags (relaxed store — UE polls this each tick). */
	public void setJavaFlags(int flags)
	{
		if (buf == null) return;
		buf.putInt(OFF_JAVA_FLAGS, flags);
		VarHandle.releaseFence();
	}

	public void sendZone(int mzx, int mzz,
		int[] opaqueData, int opaqueInts,
		int[] alphaData,  int alphaInts,
		int lo0, int lo1, int lo2, int lo3,
		int alo0, int alo1, int alo2, int alo3)
	{
		if (buf == null || opaqueInts <= 0)
		{
			return;
		}
		waitForSpace();

		int base      = slotBase(localWriteHead);
		int maxOpaque = Math.min(opaqueInts, MAX_SLOT_DATA_INTS);
		int maxAlpha  = Math.min(alphaInts,  MAX_SLOT_DATA_INTS - maxOpaque);

		buf.put(base + SLOT_OFF_COMMAND, (byte) CMD_ZONE_DATA);
		buf.putInt(base + SLOT_OFF_ZONE_X,       mzx);
		buf.putInt(base + SLOT_OFF_ZONE_Z,       mzz);
		buf.putInt(base + SLOT_OFF_OPAQUE_COUNT, maxOpaque);
		buf.putInt(base + SLOT_OFF_ALPHA_COUNT,  maxAlpha);
		buf.putInt(base + SLOT_OFF_LO0, Math.min(lo0, maxOpaque));
		buf.putInt(base + SLOT_OFF_LO1, Math.min(lo1, maxOpaque));
		buf.putInt(base + SLOT_OFF_LO2, Math.min(lo2, maxOpaque));
		buf.putInt(base + SLOT_OFF_LO3, Math.min(lo3, maxOpaque));
		buf.putInt(base + SLOT_OFF_ALO0, Math.min(alo0, maxAlpha));
		buf.putInt(base + SLOT_OFF_ALO1, Math.min(alo1, maxAlpha));
		buf.putInt(base + SLOT_OFF_ALO2, Math.min(alo2, maxAlpha));
		buf.putInt(base + SLOT_OFF_ALO3, Math.min(alo3, maxAlpha));

		ByteBuffer slice = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		slice.position(base + SLOT_OFF_DATA);
		IntBuffer dataInts = slice.asIntBuffer();
		dataInts.put(opaqueData, 0, maxOpaque);
		if (maxAlpha > 0 && alphaData != null)
		{
			dataInts.put(alphaData, 0, maxAlpha);
		}

		commitSlot();
	}

	public void sendSceneClear()
	{
		if (buf == null)
		{
			return;
		}
		waitForSpace();
		int base = slotBase(localWriteHead);
		buf.put(base + SLOT_OFF_COMMAND, (byte) CMD_SCENE_CLEAR);
		commitSlot();
	}

	public void sendZoneClear(int mzx, int mzz)
	{
		if (buf == null)
		{
			return;
		}
		waitForSpace();
		int base = slotBase(localWriteHead);
		buf.put(base + SLOT_OFF_COMMAND, (byte) CMD_ZONE_CLEAR);
		buf.putInt(base + SLOT_OFF_ZONE_X, mzx);
		buf.putInt(base + SLOT_OFF_ZONE_Z, mzz);
		commitSlot();
	}

	/**
	 * Send a full-frame entity geometry batch to UE.
	 * Vertex format: 6 ints per vertex (putfff4 + put2222):
	 *   [0] floatBits(worldX)   [1] floatBits(worldY/height)  [2] floatBits(worldZ)
	 *   [3] abhsl               [4] (su<<16)|texture           [5] (tf<<16)|sv
	 * intCount must be a multiple of 6 (one set of 6 per vertex, 3 verts per triangle).
	 */
	public void sendEntityBatch(int[] data, int intCount)
	{
		if (buf == null)
		{
			return;
		}
		waitForSpace();

		int base    = slotBase(localWriteHead);
		int maxInts = Math.min(intCount, MAX_SLOT_DATA_INTS);

		buf.put(base + SLOT_OFF_COMMAND, (byte) CMD_ENTITY_BATCH);
		buf.putInt(base + SLOT_OFF_ZONE_X, 0);
		buf.putInt(base + SLOT_OFF_ZONE_Z, 0);
		buf.putInt(base + SLOT_OFF_OPAQUE_COUNT, maxInts);
		buf.putInt(base + SLOT_OFF_ALPHA_COUNT,  0);

		if (maxInts > 0)
		{
			ByteBuffer slice = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			slice.position(base + SLOT_OFF_DATA);
			slice.asIntBuffer().put(data, 0, maxInts);
		}

		commitSlot();
	}
}
