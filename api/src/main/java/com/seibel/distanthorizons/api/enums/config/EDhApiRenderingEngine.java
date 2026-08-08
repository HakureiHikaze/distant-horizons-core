package com.seibel.distanthorizons.api.enums.config;

/**
 * AUTO, <br>
 * OPEN_GL, <br>
 * BLAZE_3D, <br>
 * STUB (temporary no-op engine used while the renderpearl port is in progress), <br>
 * RENDERPEARL (MC 26.3 GPU abstraction layer; selectable while the port is in progress), <br><br>
 *
 * @see EDhApiRenderingApi
 * 
 * @since API 7.0.0
 * @version 2026-8-9
 */
public enum EDhApiRenderingEngine
{
	AUTO,
	OPEN_GL,
	BLAZE_3D,
	STUB,
	RENDERPEARL;
	
}
