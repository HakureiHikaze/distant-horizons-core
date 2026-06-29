package com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ByteBufferCheckoutWrapper
{
	public ByteBuffer buffer = null;
	/** in bytes */
	public int size = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	/** waits to create a buffer until requested */
	public ByteBufferCheckoutWrapper() { }
	public ByteBufferCheckoutWrapper(int byteBufferSize)
	{
		this.clearAndSetSize(byteBufferSize);
	}
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	public void clearAndSetSize(int size)
	{
		if (this.size < size)
		{
			// the old buffer will automatically be garbage collected when no longer in use
			// (hopefully at a relatively quick time to prevent too much native memory floating around)
			this.buffer = ByteBuffer.allocateDirect(size);
			this.buffer.order(ByteOrder.nativeOrder()); // we need native ordering for GL/Vulkan
			
			this.size = size;
		}
		
		buffer.rewind();
		buffer.limit(this.size);
	}
	
	//endregion
	
	
	
}
