package net.runelite.client.plugins.gpu.vao;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.client.plugins.gpu.VAO;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class VAOList
{
	// this needs to be larger than the largest single model
	private static final int VAO_SIZE = 4 * 1024 * 1024;

	private int curIdx;
	public final List<VAO> vaos = new ArrayList<>();

	public VAO get(int size)
	{
		assert size <= VAO_SIZE;

		while (curIdx < vaos.size())
		{
			VAO vao = vaos.get(curIdx);
			if (!vao.vbo.mapped)
			{
				vao.vbo.map();
			}

			int rem = vao.vbo.vb.remaining() * Integer.BYTES;
			if (size <= rem)
			{
				return vao;
			}

			curIdx++;
		}

		VAO vao = new VAO(VAO_SIZE);
		vao.init();
		vao.vbo.map();
		vaos.add(vao);
		log.debug("Allocated VAO {} request {}", vao.vao, size);
		return vao;
	}

	public int unmap()
	{
		int sz = 0;
		for (int i = 0; i < vaos.size(); ++i) // NOPMD: ForLoopCanBeForeach
		{
			VAO vao = vaos.get(i);
			if (vao.vbo.mapped)
			{
				++sz;
				vao.vbo.unmap();
			}
		}
		curIdx = 0;
		return sz;
	}

	public void free()
	{
		for (VAO vao : vaos)
		{
			vao.destroy();
		}
		vaos.clear();
		curIdx = 0;
	}

	public void addRange(Projection projection, Scene scene)
	{
		for (int i = 0; i <= curIdx && i < vaos.size(); ++i)
		{
			VAO vao = vaos.get(i);
			if (vao.vbo.mapped)
			{
				vao.addRange(projection, scene);
			}
		}
	}

	void debug()
	{
		log.debug("{} vaos allocated", vaos.size());
		for (VAO vao : vaos)
		{
			log.debug("vao {} mapped: {} num ranges: {} length: {}", vao, vao.vbo.mapped, vao.off, vao.vbo.mapped ? vao.vbo.vb.position() : -1);
			if (vao.off > 1)
			{
				for (int i = 0; i < vao.off; ++i)
				{
					log.debug("  {} {} {}", vao.lengths[i], vao.projs[i], vao.scenes[i]);
				}
			}
		}
	}
}
