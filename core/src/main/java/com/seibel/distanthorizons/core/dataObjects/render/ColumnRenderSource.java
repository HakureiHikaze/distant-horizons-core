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

package com.seibel.distanthorizons.core.dataObjects.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.AbstractPhantomArrayList;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.config.Config;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import com.seibel.distanthorizons.core.logging.DhLogger;

/**
 * Stores the render data used to generate OpenGL buffers.
 *
 * @see RenderDataPointUtil
 */
public class ColumnRenderSource extends AbstractPhantomArrayList
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** measured in data columns */
	public static final int WIDTH = 64;
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Render Source");
	
	
	
	/** 
	 * will be zero if an empty data source was created 
	 * @see EDhApiVerticalQuality#calculateMaxNumberOfVerticalSlicesAtDetailLevel(byte) 
	 */
	public int maxVerticalSliceCount;
	public long pos;
	public int yOffset;
	
	public final LongArrayList renderDataContainer;
	
	/**
	 * Parallel to {@link ColumnRenderSource#renderDataContainer},
	 * each byte is an index into {@link ColumnRenderSource#texturePalette}. <br>
	 * Empty when textured LODs are disabled or this section's
	 * detail level is too low to show textures.
	 *
	 * @see ColumnRenderSource#texturedLodsEnabledAtDetailLevel(byte)
	 */
	public final ByteArrayList textureSetPaletteIndices;
	/**
	 * Palette index -> {@link com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry} set id. <br>
	 * Index {@link ColumnRenderSource#FLAT_PALETTE_INDEX} is always the flat "no texture" set.
	 */
	public final ShortArrayList texturePalette = new ShortArrayList();
	
	private boolean isEmpty = true;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static ColumnRenderSource createEmpty(long pos, int maxVertSliceCount, int yOffset)
	{ return new ColumnRenderSource(pos, maxVertSliceCount, yOffset); }
	/**
	 * Creates an empty ColumnRenderSource.
	 *
	 * @param pos the relative position of the container
	 * @param maxVertSliceCount the maximum vertical size of the container
	 */
	private ColumnRenderSource(long pos, int maxVertSliceCount, int yOffset)
	{
		super(ARRAY_LIST_POOL, 1, 0, 1, 0, 0);
		
		this.pos = pos;
		this.yOffset = yOffset;
		
		this.maxVerticalSliceCount = maxVertSliceCount;
		
		this.renderDataContainer = this.pooledArraysCheckout.getLongArray(0, WIDTH * WIDTH * this.maxVerticalSliceCount);
		
		// texture ids are only stored for high detail sections where textures are visible,
		// for everything else the empty list should keep the memory overhead at zero
		int textureIndexCount = texturedLodsEnabledAtDetailLevel(this.getDataDetailLevel()) ? WIDTH * WIDTH * this.maxVerticalSliceCount : 0;
		this.textureSetPaletteIndices = this.pooledArraysCheckout.getByteArray(0, textureIndexCount);
		this.texturePalette.add(BlockTextureRegistry.FLAT_TILE_ID);
	}
	
	//endregion
	
	
	
	//========================//
	// datapoint manipulation //
	//========================//
	//region
	
	/** texture palettes are indexed by unsigned bytes */
	public static final int MAX_PALETTE_SIZE = 256;
	
	public long getDataPoint(int posX, int posZ, int verticalIndex) { return this.renderDataContainer.getLong(posX * WIDTH * this.maxVerticalSliceCount + posZ * this.maxVerticalSliceCount + verticalIndex); }
	
	public static boolean texturedLodsEnabledAtDetailLevel(byte dataDetailLevel)
	{
		return Config.Client.Advanced.Graphics.Quality.enableTexturedLods.get()
			&& dataDetailLevel <= Config.Client.Advanced.Graphics.Quality.maxTexturedLodDetailLevel.get();
	}
	
	/** @return whether this section is storing texture ids */
	public boolean hasTextureSetIds() { return !this.textureSetPaletteIndices.isEmpty(); }
	
	/**
	 * @return the {@link com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry}
	 *          set id for the given data point, the flat set id if this section isn't storing textures
	 */
	public short getTextureSetId(int posX, int posZ, int verticalIndex)
	{
		if (!this.hasTextureSetIds())
		{
			return BlockTextureRegistry.FLAT_TILE_ID;
		}
		
		int paletteIndex = this.textureSetPaletteIndices.getByte(
			posX * WIDTH * this.maxVerticalSliceCount + posZ * this.maxVerticalSliceCount + verticalIndex) 
			& 0xFF;
		return this.texturePalette.getShort(paletteIndex);
	}
	
	/**
	 * Stores the texture set id for the given data point,
	 * does nothing if this section isn't storing textures.
	 */
	public void setTextureSetId(int posX, int posZ, int verticalIndex, short textureSetId)
	{
		if (!this.hasTextureSetIds())
		{
			return;
		}
		
		this.textureSetPaletteIndices.set(
			posX * WIDTH * this.maxVerticalSliceCount + posZ * this.maxVerticalSliceCount + verticalIndex,
			this.getOrAddPaletteIndex(textureSetId));
	}
	
	private byte getOrAddPaletteIndex(short textureSetId)
	{
		// linear search is fine, palettes hold the few dozen
		// distinct block appearances of a single 64x64 section
		// and most lookups hit the first few entries
		// TODO: Still see if we can improve this
		int paletteSize = this.texturePalette.size();
		for (int i = 0; i < paletteSize; i++)
		{
			if (this.texturePalette.getShort(i) == textureSetId)
			{
				return (byte) i;
			}
		}
		
		if (paletteSize >= MAX_PALETTE_SIZE)
		{
			// sections with too many distinct blocks
			// gracefully lose textures so they can't take up every possible ID
			return BlockTextureRegistry.FLAT_TILE_ID;
		}
		
		this.texturePalette.add(textureSetId);
		return (byte) paletteSize;
	}
	
	public void populateColumnView(ColumnRenderView view, int posX, int posZ) throws IllegalArgumentException
	{
		int offset = posX * WIDTH * this.maxVerticalSliceCount + posZ * this.maxVerticalSliceCount;
		
		// don't allow returning views that are outside this render source's bounds
		if (offset >= this.renderDataContainer.size())
		{
			throw new IllegalArgumentException("Column View offset ["+offset+"] greater than parent render data container ["+DhSectionPos.toString(this.pos)+"] size ["+this.renderDataContainer.size()+"].");
		}
		else if (posX < 0 || posX >= WIDTH
				|| posZ < 0 || posZ >= WIDTH)
		{
			throw new IllegalArgumentException("Column View pos outside valid range ["+posX+","+posZ+"].");
		}
		
		view.populate(
			this.renderDataContainer, this.maxVerticalSliceCount,
			offset, this.maxVerticalSliceCount);
	}
	
	//endregion
	
	
	
	//=====================//
	// data helper methods //
	//=====================//
	//region
	
	public Long getPos() { return this.pos; }
	public Long getKey() { return this.pos; }
	
	public byte getDataDetailLevel() { return (byte) (DhSectionPos.getDetailLevel(this.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL); }
	
	public boolean isEmpty() { return this.isEmpty; }
	public void markNotEmpty() { this.isEmpty = false; }
	
	/** can be used when debugging */
	public boolean hasNonVoidDataPoints()
	{
		if (this.isEmpty)
		{
			return false;
		}
		
		try (ColumnRenderView columnView = ColumnRenderView.getPooled())
		{
			for (int x = 0; x < WIDTH; x++)
			{
				for (int z = 0; z < WIDTH; z++)
				{
					this.populateColumnView(columnView, x, z);
					for (int i = 0; i < columnView.size; i++)
					{
						long dataPoint = columnView.get(i);
						if (!RenderDataPointUtil.hasZeroHeight(dataPoint))
						{
							return true;
						}
					}
				}
			}
		}
		
		return false;
	}
	
	//endregion
	
	
	
	//==============//
	// base methods //
	//==============//
	//region
	
	@Override
	public String toString()
	{
		String LINE_DELIMITER = "\n";
		String DATA_DELIMITER = " ";
		String SUBDATA_DELIMITER = ",";
		StringBuilder stringBuilder = new StringBuilder();
		
		stringBuilder.append(DhSectionPos.toString(this.pos));
		stringBuilder.append(LINE_DELIMITER);
		
		int size = 1;
		for (int z = 0; z < size; z++)
		{
			for (int x = 0; x < size; x++)
			{
				for (int y = 0; y < this.maxVerticalSliceCount; y++)
				{
					//Converting the dataToHex
					stringBuilder.append(Long.toHexString(this.getDataPoint(x, z, y)));
					if (y != this.maxVerticalSliceCount - 1)
						stringBuilder.append(SUBDATA_DELIMITER);
				}
				
				if (x != size - 1)
					stringBuilder.append(DATA_DELIMITER);
			}
			
			if (z != size - 1)
				stringBuilder.append(LINE_DELIMITER);
		}
		return stringBuilder.toString();
	}
	
	//endregion
	
	
	
}
