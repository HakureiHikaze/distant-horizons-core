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

package com.seibel.distanthorizons.core.config;

/**
 * Stage 3 (SA-3-4/3-9): user-facing LOD render distance level (2-8) mapped to
 * the chunk-radius data source {@code Config.Client.Advanced.Graphics.Quality.
 * lodChunkRenderDistanceRadius}.
 *
 * <p>Each level doubles the radius: radius = 32 * 2^(level-1), so level 4 is
 * the default 256-chunk radius and level 8 is the 4096-chunk maximum.
 * The inverse mapping picks the nearest level for display when the radius is
 * changed directly in the config file.
 */
public final class LodRenderDistanceLevel
{
	public static final int MIN_LEVEL = 2;
	public static final int MAX_LEVEL = 8;
	public static final int DEFAULT_LEVEL = 4;
	
	/** minimum lodChunkRenderDistanceRadius (see Config) */
	private static final int BASE_RADIUS = 32;
	
	
	
	private LodRenderDistanceLevel() { }
	
	
	
	/** level (clamped to 2-8) -> chunk radius */
	public static int levelToRadius(int level)
	{
		level = clampLevel(level);
		return BASE_RADIUS << (level - 1);
	}
	
	/** chunk radius -> nearest level (clamped to 2-8) */
	public static int radiusToLevel(int radius)
	{
		if (radius <= BASE_RADIUS)
		{
			return MIN_LEVEL;
		}
		
		double log2 = Math.log(radius / (double) BASE_RADIUS) / Math.log(2.0);
		return clampLevel((int) Math.round(log2) + 1);
	}
	
	public static boolean isValidLevel(int level)
	{
		return level >= MIN_LEVEL && level <= MAX_LEVEL;
	}
	
	public static int clampLevel(int level)
	{
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
	}
	
	
	
}
