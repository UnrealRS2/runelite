package net.runelite.client.plugins.gpushared.shim;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Java-side bridge for the "URRL_Entities" shared memory.
 *
 * Dedicated buffer for per-frame entity geometry — separate from the zone ring
 * buffer to avoid the 2 GB Java ByteBuffer limit.
 *
 * Layout (256 MB + 16 byte header):
 *   [0]  write_seq  (uint32) — Java increments after writing a batch
 *   [4]  read_seq   (uint32) — UE increments after consuming a batch
 *   [8]  int_count  (uint32) — number of int32s of entity data written
 *   [12] pad[4]
 *   [16] data[]              — entity geometry ints (6 ints/vertex)
 *
 * Protocol:
 *   Java: write data[], set int_count, releaseFence(), ++write_seq
 *   UE:   acquire-load write_seq; if != read_seq: read, ++read_seq
 */
public class EntityBridge
{
	private static final int OFF_WRITE_SEQ = 0;
	private static final int OFF_READ_SEQ  = 4;
	private static final int OFF_INT_COUNT = 8;
	private static final int DATA_BASE     = 16;

	private static final int DATA_BYTES    = 256 * 1024 * 1024;
	private static final int TOTAL_BYTES   = DATA_BASE + DATA_BYTES;

	public static final int MAX_DATA_INTS  = DATA_BYTES / 4;

	// ── State ──────────────────────────────────────────────────────────────────

	// Delegate JNI calls to a SceneGraphBridge instance — the shim registers
	// those symbols under SceneGraphBridge's class name, not EntityBridge's.
	private final SceneGraphBridge shim = new SceneGraphBridge();
	private long nativeHandle = 0;
	private ByteBuffer buf;
	private int localWriteSeq = 0;

	// ── Lifecycle ──────────────────────────────────────────────────────────────

	public void init(String name)
	{
		nativeHandle = shim.openSceneMemory(name);
		if (nativeHandle == 0)
		{
			throw new RuntimeException("Failed to open entity shared memory: " + name);
		}
		buf = shim.mapSceneBuffer(nativeHandle).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(OFF_WRITE_SEQ, 0);
		buf.putInt(OFF_READ_SEQ,  0);
		buf.putInt(OFF_INT_COUNT, 0);
		localWriteSeq = 0;
	}

	public void shutdown()
	{
		if (nativeHandle != 0)
		{
			shim.closeSceneMemory(nativeHandle);
			nativeHandle = 0;
			buf = null;
		}
	}

	// ── Send ───────────────────────────────────────────────────────────────────

	/**
	 * Write the full entity batch for this frame directly from a flipped IntBuffer —
	 * zero heap allocation, single bulk copy into shared memory.
	 * Blocks briefly if UE hasn't consumed the previous frame's batch yet.
	 */
	public void sendEntityBatch(IntBuffer src, int intCount)
	{
		if (buf == null)
		{
			return;
		}

		// Wait for UE to consume the previous batch
		while (buf.getInt(OFF_READ_SEQ) != localWriteSeq)
		{
			Thread.yield();
		}

		int maxInts = Math.min(intCount, MAX_DATA_INTS);
		buf.putInt(OFF_INT_COUNT, maxInts);

		if (maxInts > 0)
		{
			// Direct bulk transfer: src (direct IntBuffer) → SHM IntBuffer, no int[] needed
			IntBuffer dst = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
				.position(DATA_BASE)
				.asIntBuffer();
			IntBuffer limited = src.duplicate();
			limited.limit(maxInts);
			dst.put(limited);
		}

		localWriteSeq++;
		VarHandle.releaseFence();
		buf.putInt(OFF_WRITE_SEQ, localWriteSeq);
	}
}
