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

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateFaceTextureProvider;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns each block state face a global atlas tile id
 * and holds the tile pixel data until it's uploaded to the GPU. <br><br>
 *
 * TODO: Improve this ratio explanation across the codebase, i yap too much, simplify it and just provide the exact detail of what we do.
 * Tile pixels are stored as color ratios relative to the tile's average color
 * (128 = 1.0, IE no change) instead of absolute colors.
 * This way the tile can be multiplied with the LOD's existing per-position
 * vertex color, leaving the current tinting/shading pipeline
 * and the average color seen at a distance unchanged. <br><br>
 *
 * Tile id {@link BlockTextureRegistry#FLAT_TILE_ID} represents "no texture"
 * and renders identically to a flat-colored LOD.
 */
public class BlockTextureRegistry
{
	private static final IBlockStateFaceTextureProvider TEXTURE_PROVIDER = SingletonInjector.INSTANCE.get(IBlockStateFaceTextureProvider.class);
	
	
	public static final BlockTextureRegistry INSTANCE = new BlockTextureRegistry();
	
	/** renders as a constant 1.0 color multiplier (flat colors) */
	public static final short FLAT_TILE_ID = 0;
	
	public static final int TILE_WIDTH = 16;
	/** RGBA */
	public static final int TILE_BYTE_COUNT = TILE_WIDTH * TILE_WIDTH * 4;
	
	/**
	 * tile ids are stored in 16 vertex bits,
	 * if we somehow run out of ids additional faces just render flat
	 */
	public static final int MAX_TILE_COUNT = 65_536;
	
	
	
	/** renders every face flat, also used when set registration overflows */
	public static final short UNTEXTURED_ID = 0;
	
	
	
	/** indexed by tile id, holds the ratio-encoded RGBA pixels ready for GPU upload */
	private final ArrayList<byte[]> tilePixelsById = new ArrayList<>();
	/** used to dedupe tiles so block faces sharing a texture share a tile */
	private final HashMap<TileKey, Short> tileIdByContent = new HashMap<>();
	/** tiles that haven't been uploaded to the GPU yet, drained by the render thread */
	private int firstTileIdPendingUpload = 0;
	
	/** indexed by set id, holds the 6 face tile ids for one block state */
	private final ArrayList<short[]> faceTileIdsById = new ArrayList<>();
	private final ConcurrentHashMap<IBlockStateWrapper, Short> idByBlockStateWrapper = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlockTextureRegistry()
	{
		// reserve the flat tile so id 0 always renders as a 1.0 multiplier
		byte[] flatPixels = new byte[TILE_BYTE_COUNT];
		
		// fill everything with a gray pixel
		for (int i = 0; i < TILE_BYTE_COUNT; i += 4)
		{
			flatPixels[i] = (byte) 128; // half of an unsigned byte, ie gray
			flatPixels[i + 1] = (byte) 128;
			flatPixels[i + 2] = (byte) 128;
			flatPixels[i + 3] = (byte) 0xFF; // max opacity (256)
		}
		this.tilePixelsById.add(flatPixels);
		
		// reserve the all-flat face set so set id 0 always renders flat
		this.faceTileIdsById.add(new short[6]);
	}
	
	//endregion
	
	
	
	//==============//
	// tile getters //
	//==============//
	//region
	
	/**
	 * Returns the id of the given block state's face tile set,
	 * baking and registering the face textures if necessary. <br>
	 * Thread safe, although baking may block briefly.
	 *
	 * @return {@link BlockTextureRegistry#UNTEXTURED_ID} if no textures are available
	 */
	public short getOrRegisterBlockStateSetId(IBlockStateWrapper blockState)
	{
		Short id = this.idByBlockStateWrapper.get(blockState);
		if (id == null)
		{
			id = this.registerBlockState(blockState);
		}
		return id;
	}
	
	/**
	 * Returns the tile id for each face of the given set,
	 * indexed by {@link EDhDirection#faceIndex}. <br>
	 * The returned array must not be modified.
	 *
	 * @return null if the set id is invalid or the flat set
	 */
	public synchronized short @Nullable [] getFaceTileIds(int setId)
	{
		if (setId <= UNTEXTURED_ID || setId >= this.faceTileIdsById.size())
		{
			return null;
		}
		return this.faceTileIdsById.get(setId);
	}
	
	private short registerBlockState(IBlockStateWrapper blockState)
	{
		short[] faceTileIds = new short[6]; // 6 faces on a cube
		boolean anyFaceTextured = false;
		
		for (EDhDirection direction : EDhDirection.ALL)
		{
			BlockFaceTexture faceTexture = TEXTURE_PROVIDER.getFaceTexture(blockState, direction);
			short tileId = (faceTexture != null)
					? this.getOrCreateTileId(faceTexture)
					: FLAT_TILE_ID;
			faceTileIds[direction.faceIndex] = tileId;
			anyFaceTextured |= (tileId != FLAT_TILE_ID);
		}
		
		short textureId;
		if (!anyFaceTextured)
		{
			// blocks whose every face is flat (IE uniformly colored textures)
			// share the reserved flat set
			textureId = UNTEXTURED_ID;
		}
		else
		{
			// synchronized to prevent concurrent array modifications
			synchronized (this)
			{
				if (this.faceTileIdsById.size() >= MAX_TILE_COUNT)
				{
					textureId = UNTEXTURED_ID;
				}
				else
				{
					textureId = (short) this.faceTileIdsById.size();
					this.faceTileIdsById.add(faceTileIds);
				}
			}
		}
		
		Short existingSetId = this.idByBlockStateWrapper.putIfAbsent(blockState, textureId);
		return (existingSetId != null) ? existingSetId : textureId;
	}
	
	//endregion
	
	
	
	//===================//
	// tile registration //
	//===================//
	//region
	
	private synchronized short getOrCreateTileId(BlockFaceTexture faceTexture)
	{
		byte[] ratioPixels = convertColorsToDifferenceRatios(faceTexture);
		if (ratioPixels == null)
		{
			return FLAT_TILE_ID;
		}
		
		TileKey key = new TileKey(ratioPixels);
		Short existingId = this.tileIdByContent.get(key);
		if (existingId != null)
		{
			return existingId;
		}
		
		if (this.tilePixelsById.size() >= MAX_TILE_COUNT)
		{
			return FLAT_TILE_ID;
		}
		
		short newId = (short) this.tilePixelsById.size();
		this.tilePixelsById.add(ratioPixels);
		this.tileIdByContent.put(key, newId);
		return newId;
	}
	
	/**
	 * Converts the texture's absolute colors into ratios
	 * relative to the texture's average color.
	 * 128 = 1.0, 255 = 2.0, ratios above 2.0 are clamped.
	 *
	 * @return null if the texture is a single uniform color, IE flat shading is identical
	 */
	private static byte[] convertColorsToDifferenceRatios(BlockFaceTexture faceTexture)
	{
		int[] argbPixels = faceTexture.argbPixels;
		
		// average only the visible pixels, otherwise
		// cutout textures (IE fences) would have overly dark averages
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int visibleCount = 0;
		for (int i = 0; i < argbPixels.length; i++)
		{
			if (ColorUtil.getAlpha(argbPixels[i]) != 0)
			{
				redSum += ColorUtil.getRed(argbPixels[i]);
				greenSum += ColorUtil.getGreen(argbPixels[i]);
				blueSum += ColorUtil.getBlue(argbPixels[i]);
				visibleCount++;
			}
		}
		if (visibleCount == 0)
		{
			return null;
		}
		
		float averageRed = Math.max(redSum / (float) visibleCount, 1.0f);
		float averageGreen = Math.max(greenSum / (float) visibleCount, 1.0f);
		float averageBlue = Math.max(blueSum / (float) visibleCount, 1.0f);
		
		byte[] uploadPixels = new byte[TILE_BYTE_COUNT];
		boolean anyPixelDiffersFromAverage = false;
		for (int v = 0; v < TILE_WIDTH; v++)
		{
			for (int u = 0; u < TILE_WIDTH; u++)
			{
				// 1x1 fallback tiles repeat their single pixel
				int sourceIndex = ((v * faceTexture.height / TILE_WIDTH) * faceTexture.width)
						+ (u * faceTexture.width / TILE_WIDTH);
				int argb = argbPixels[sourceIndex];
				
				int outIndex = ((v * TILE_WIDTH) + u) * 4;
				
				if (faceTexture.uploadAsColorRatio)
				{
					// upload as a ratio so the texture modifies the base DH defined color
					uploadPixels[outIndex] = encodeRatio(ColorUtil.getRed(argb), averageRed);
					uploadPixels[outIndex + 1] = encodeRatio(ColorUtil.getGreen(argb), averageGreen);
					uploadPixels[outIndex + 2] = encodeRatio(ColorUtil.getBlue(argb), averageBlue);
					uploadPixels[outIndex + 3] = (byte)ColorUtil.getAlpha(argb);
				}
				else
				{
					// upload as an absolute color
					// (due to how rendering is done this will only partially work,
					// but is helpful for the error texture to appear correctly)
					uploadPixels[outIndex] = (byte) ColorUtil.getRed(argb);
					uploadPixels[outIndex + 1] = (byte) ColorUtil.getGreen(argb);
					uploadPixels[outIndex + 2] = (byte) ColorUtil.getBlue(argb);
					uploadPixels[outIndex + 3] = (byte) ColorUtil.getAlpha(argb);
				}
				
				anyPixelDiffersFromAverage |=
						uploadPixels[outIndex] != (byte) 128
						|| uploadPixels[outIndex + 1] != (byte) 128
						|| uploadPixels[outIndex + 2] != (byte) 128
						|| uploadPixels[outIndex + 3] != (byte) 0xFF;
			}
		}
		
		return anyPixelDiffersFromAverage ? uploadPixels : null;
	}
	private static byte encodeRatio(int channel, float average)
	{
		int encoded = Math.round((channel / average) * 127.5f);
		return (byte) Math.min(encoded, 255);
	}
	
	//endregion
	
	
	
	//============//
	// GPU upload //
	//============//
	//region
	
	/**
	 * Returns the tiles registered since the last call so they can
	 * be uploaded to the GPU, must be called from the render thread.
	 */
	public synchronized PendingTiles getAndClearPendingUploadTiles()
	{
		int firstId = this.firstTileIdPendingUpload;
		int tileCount = this.tilePixelsById.size() - firstId;
		if (tileCount <= 0)
		{
			return null;
		}
		
		byte[][] pixelArrays = new byte[tileCount][];
		for (int i = 0; i < tileCount; i++)
		{
			pixelArrays[i] = this.tilePixelsById.get(firstId + i);
		}
		this.firstTileIdPendingUpload = this.tilePixelsById.size();
		return new PendingTiles(firstId, pixelArrays);
	}
	
	public synchronized int getTileCount() { return this.tilePixelsById.size(); }
	
	/** Makes every tile pending again, used when the GPU atlas is re-allocated. */
	public synchronized void resetPendingUploads() { this.firstTileIdPendingUpload = 0; }
	
	/** Should be called whenever MC's textures change, IE when resource packs are swapped. */
	public synchronized void clear()
	{
		this.idByBlockStateWrapper.clear();
		short[] flatSet = this.faceTileIdsById.get(UNTEXTURED_ID);
		this.faceTileIdsById.clear();
		this.faceTileIdsById.add(flatSet);
		
		this.tileIdByContent.clear();
		byte[] flatTile = this.tilePixelsById.get(FLAT_TILE_ID);
		this.tilePixelsById.clear();
		this.tilePixelsById.add(flatTile);
		this.firstTileIdPendingUpload = 0;
		
		IBlockStateFaceTextureProvider textureProvider = SingletonInjector.INSTANCE.get(IBlockStateFaceTextureProvider.class);
		if (textureProvider != null)
		{
			textureProvider.clear();
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	/** wraps tile pixels so they can be used as a hash map key for deduplication */
	private static class TileKey
	{
		private final byte[] pixels;
		private final int hash;
		
		public TileKey(byte[] pixels)
		{
			this.pixels = pixels;
			this.hash = Arrays.hashCode(pixels);
		}
		
		@Override
		public int hashCode() { return this.hash; }
		
		@Override
		public boolean equals(Object obj)
		{
			if (this == obj) 
			{
				return true; 
			}
			
			if (!(obj instanceof TileKey)) 
			{
				return false; 
			}
			
			return Arrays.equals(this.pixels, ((TileKey) obj).pixels);
		}
		
	}
	
	public static class PendingTiles
	{
		public final int firstTileId;
		public final byte[][] tilePixels;
		
		public PendingTiles(int firstTileId, byte[][] tilePixels)
		{
			this.firstTileId = firstTileId;
			this.tilePixels = tilePixels;
		}
	}
	
	//endregion
	
	
	
}
