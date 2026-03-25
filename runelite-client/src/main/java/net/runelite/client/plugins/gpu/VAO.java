/*
 * Copyright (c) 2025, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import static net.runelite.client.plugins.gpu.GpuPlugin.uniEntityTint;
import static net.runelite.client.plugins.gpu.GpuPlugin.updateEntityProjection;
import static org.lwjgl.opengl.GL33C.*;

public class VAO
{
	// Temporary vertex format
	// index 0: vec3(x, y, z)
	// index 1: int abhsl
	// index 2: short vec4(id, x, y, z)
	public static final int VERT_SIZE = 24;

	public final VBO vbo;
	public int vao;

	public VAO(int size)
	{
		vbo = new VBO(size);
	}

	public void init()
	{
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

		vbo.init(GL_DYNAMIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);

		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		glEnableVertexAttribArray(1);
		glVertexAttribIPointer(1, 1, GL_INT, VERT_SIZE, 12);

		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(2, 4, GL_SHORT, VERT_SIZE, 16);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	public void destroy()
	{
		vbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	public int[] lengths = new int[4];
	public Projection[] projs = new Projection[4];
	public Scene[] scenes = new Scene[4];
	public int off = 0;

	public void addRange(Projection projection, Scene scene)
	{
		assert vbo.mapped;

		if (off > 0 && lengths[off - 1] == vbo.vb.position())
		{
			return;
		}

		if (lengths.length == off)
		{
			int l = lengths.length << 1;
			lengths = Arrays.copyOf(lengths, l);
			projs = Arrays.copyOf(projs, l);
			scenes = Arrays.copyOf(scenes, l);
		}

		lengths[off] = vbo.vb.position();
		projs[off] = projection;
		scenes[off] = scene;
		off++;
	}

	public void draw()
	{
		assert !vbo.mapped;

		int start = 0;
		for (int i = 0; i < off; ++i)
		{
			int end = lengths[i];
			Projection p = projs[i];
			Scene scene = scenes[i];

			int count = end - start;

			updateEntityProjection(p);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLES, start / (VERT_SIZE / 4), count / (VAO.VERT_SIZE / 4));

			start = end;
		}
	}

	public void reset()
	{
		Arrays.fill(projs, 0, off, null);
		Arrays.fill(scenes, 0, off, null);
		off = 0;
	}
}

