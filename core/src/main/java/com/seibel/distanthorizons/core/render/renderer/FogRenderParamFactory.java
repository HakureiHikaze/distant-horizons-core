package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogColorMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

import java.awt.*;

/**
 * @see DhApiFogRenderParam
 * @see DhApiBeforeFogRenderEvent.EventParam
 */
public class FogRenderParamFactory
{
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	
	
	public static DhApiBeforeFogRenderEvent.EventParam createRenderParam(RenderParams renderParams)
	{
		Color fogColor = getFogColor(renderParams.partialTicks);
		
		// far fog
		EDhApiFogFalloff farFogFalloff = Config.Client.Advanced.Graphics.Fog.farFogFalloff.get();
		float farFogStart = Config.Client.Advanced.Graphics.Fog.farFogStart.get();
		float farFogEnd = Config.Client.Advanced.Graphics.Fog.farFogEnd.get();
		float farFogMin = Config.Client.Advanced.Graphics.Fog.farFogMin.get();
		float farFogMax = Config.Client.Advanced.Graphics.Fog.farFogMax.get();
		float farFogDensity = Config.Client.Advanced.Graphics.Fog.farFogDensity.get();
		
		
		// height fog
		EDhApiFogFalloff heightFogFalloff = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogFalloff.get();
		EDhApiHeightFogMixMode heightFogMixingMode = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMixMode.get();
		EDhApiHeightFogDirection heightFogCameraDirection = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDirection.get();
		
		float heightFogBaseHeight = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogBaseHeight.get();
		float heightFogStart = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogStart.get();
		float heightFogEnd = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogEnd.get();
		float heightFogMin = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMin.get();
		float heightFogMax = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMax.get();
		float heightFogDensity = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDensity.get();
		
		// override fog if underwater
		if (MC_RENDER.isFogStateSpecial())
		{
			// hide everything behind fog
			farFogStart = 0.0f;
			farFogEnd = 0.0f;
		}
		
		DhApiFogRenderParam fogRenderParam = new DhApiFogRenderParam(
			fogColor,
			
			// far fog
			farFogFalloff,
			farFogStart, farFogEnd,
			farFogMin, farFogMax,
			farFogDensity,
			
			// height fog
			heightFogFalloff,
			heightFogMixingMode, heightFogCameraDirection,
			heightFogBaseHeight,
			heightFogStart, heightFogEnd,
			heightFogMin, heightFogMax,
			heightFogDensity
		);
		
		DhApiBeforeFogRenderEvent.EventParam fogRenderEventParam = new DhApiBeforeFogRenderEvent.EventParam(renderParams, fogRenderParam);
		return fogRenderEventParam;
	}
	
	private static Color getFogColor(float partialTicks)
	{
		Color fogColor;
		
		if (Config.Client.Advanced.Graphics.Fog.colorMode.get() == EDhApiFogColorMode.USE_SKY_COLOR)
		{
			fogColor = MC_RENDER.getSkyColor();
		}
		else
		{
			fogColor = MC_RENDER.getFogColor(partialTicks);
		}
		
		return fogColor;
	}
	
	
	
}
