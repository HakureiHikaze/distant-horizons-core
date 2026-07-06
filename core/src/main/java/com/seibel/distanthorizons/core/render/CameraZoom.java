package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.core.util.RenderUtil;

/**
 * Describes how far the camera is currently zoomed in
 * and which area of the world is visible through it.
 */
public class CameraZoom
{
	public static final CameraZoom NOT_ZOOMED = CameraZoom.createNotZoomed();
	
	
	
	/** how many times larger objects appear on screen compared to the player's FOV setting */
	public double magnification;
	
	/**
	 * The tangent of half the zoom cone's horizontal angle. <br>
	 * The cone is slightly wider than the zoomed camera's FOV so LODs
	 * just off screen can start loading before the camera pans over them.
	 */
	public double coneTanHalfAngle;
	
	/** the camera's look direction projected onto the XZ plane, normalized */
	public double lookDirectionX;
	/** the camera's look direction projected onto the XZ plane, normalized */
	public double lookDirectionZ;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static CameraZoom createNotZoomed() { return new CameraZoom(RenderUtil.NOT_ZOOMED_MAGNIFICATION, 0.0, 0.0, 0.0); }
	
	public CameraZoom(double magnification, double coneTanHalfAngle, double lookDirectionX, double lookDirectionZ)
	{
		this.magnification = magnification;
		this.coneTanHalfAngle = coneTanHalfAngle;
		this.lookDirectionX = lookDirectionX;
		this.lookDirectionZ = lookDirectionZ;
	}
	
	//endregion
	
	
	
	//==========//
	// updating //
	//==========//
	//region
	
	public void set(CameraZoom that) { this.set(that.magnification, that.coneTanHalfAngle, that.lookDirectionX, that.lookDirectionZ); }
	public void set(double magnification, double coneTanHalfAngle, double lookDirectionX, double lookDirectionZ)
	{
		this.magnification = magnification;
		this.coneTanHalfAngle = coneTanHalfAngle;
		this.lookDirectionX = lookDirectionX;
		this.lookDirectionZ = lookDirectionZ;
	}
	
	//endregion
	
	
	
	//==============//
	// intersection //
	//==============//
	//region
	
	/**
	 * Returns true if any part of the given circle is visible through the zoomed camera. <br>
	 * The check is done in 2D on the XZ plane.
	 */
	public boolean coneIntersectsCircle(
		double coneOriginX, double coneOriginZ, 
		double circleCenterX, double circleCenterZ, double circleRadius)
	{
		double offsetX = circleCenterX - coneOriginX;
		double offsetZ = circleCenterZ - coneOriginZ;
		
		double distanceAlongLook = (offsetX * this.lookDirectionX) + (offsetZ * this.lookDirectionZ);
		if (distanceAlongLook < -circleRadius)
		{
			// entirely behind the camera
			return false;
		}
		
		// 2D cross product, how far the circle's center is from the camera's look line
		double distanceAcrossLook = Math.abs((offsetX * this.lookDirectionZ) - (offsetZ * this.lookDirectionX));
		return distanceAcrossLook <= (distanceAlongLook * this.coneTanHalfAngle) + circleRadius;
	}
	
	//endregion
	
	
	
}
