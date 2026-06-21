/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.dataObjects.render.textures;

import com.seibel.distanthorizons.coreapi.util.ColorUtil;

/**
 * A small texture representing one face of a block state,
 * baked from the block's model so LODs can show the block's
 * actual texture instead of a single flat color. <br><br>
 */
public class BlockFaceTexture
{
	public final int width;
	public final int height;
	/**
	 * Pixel colors in ARGB order. <br>
	 * Indexed via <code>(v * width) + u</code> where (0,0) is the face's top left pixel.
	 */
	public final int[] argbPixels;
	
	/** true if these pixels should be multiplied by a position specific tint before rendering (IE grass or leaf colors) */
	public final boolean tinted;
	// TODO: Check if this is still needed (since I think I removed the silhouette thing due to being able to see through the world)
	/** true if any pixel is fully transparent, meaning alpha discarding is needed when rendering */
	public final boolean cutout;
	/** true if any pixel is semi-transparent (IE glass or water), meaning the transparent render pass is needed */
	public final boolean semiTransparent;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BlockFaceTexture(int width, int height, int[] argbPixels, boolean tinted)
	{
		this.width = width;
		this.height = height;
		this.argbPixels = argbPixels;
		this.tinted = tinted;
		
		boolean cutoutFound = false;
		boolean semiTransparentFound = false;
		for (int i = 0; i < argbPixels.length; i++)
		{
			int alpha = ColorUtil.getAlpha(argbPixels[i]);
			if (alpha == 0)
			{
				cutoutFound = true;
			}
			else if (alpha != 255)
			{
				semiTransparentFound = true;
			}
		}
		this.cutout = cutoutFound;
		this.semiTransparent = semiTransparentFound;
	}
	
	public static BlockFaceTexture createSolidColor(int argbColor, boolean tinted)
	{ return new BlockFaceTexture(1, 1, new int[] { argbColor }, tinted); }



}
