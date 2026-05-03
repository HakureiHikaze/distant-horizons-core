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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java representation of one or more OpenGL buffers for rendering.
 *
 * @see ColumnRenderBufferBuilder
 */
public class LodBufferContainer implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	/** the position closest to minimum X/Z infinity and the level's lowest Y */
	public final DhBlockPos minCornerBlockPos;
	public final long pos;
	
	public boolean buffersUploaded = false;
	
	public IVertexBufferWrapper[] vboOpaqueWrappers;
	public IVertexBufferWrapper[] vboTransparentWrappers;
	
	public ILodContainerUniformBufferWrapper uniformContainer = WRAPPER_FACTORY.createLodContainerUniformWrapper();
	
	private final AtomicReference<CompletableFuture<LodBufferContainer>> uploadFutureRef = new AtomicReference<>(null);
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public LodBufferContainer(long pos, DhBlockPos minCornerBlockPos)
	{
		this.pos = pos;
		this.minCornerBlockPos = minCornerBlockPos;
		this.vboOpaqueWrappers = new IVertexBufferWrapper[0];
		this.vboTransparentWrappers = new IVertexBufferWrapper[0];
		
		this.uniformContainer.createUniformData(this);
	}
	
	//endregion
	
	
	
	//==================//
	// buffer uploading //
	//==================//
	//region
	
	/** Should be run on a DH thread. */
	public synchronized CompletableFuture<LodBufferContainer> tryMakeAndUploadBuffersAsync(LodQuadBuilder builder)
	{
		//================//
		// handle futures //
		//================//
		//region
		
		// separate variable to prevent race condition when checking null
		CompletableFuture<LodBufferContainer> oldFuture = this.uploadFutureRef.get();
		if (oldFuture != null)
		{
			// upload already in process
			return oldFuture;
		}
		
		// new upload needed
		CompletableFuture<LodBufferContainer> future = new CompletableFuture<>();
		future.handle((lodBufferContainer, throwable) -> 
		{
			if (!this.uploadFutureRef.compareAndSet(future, null))
			{
				LOGGER.warn("upload future ref changed for pos ["+DhSectionPos.toString(this.pos)+"].");
			}
			
			return null;
		});
		
		if (!this.uploadFutureRef.compareAndSet(null, future))
		{
			oldFuture = this.uploadFutureRef.get();
			LodUtil.assertTrue(oldFuture != null, "Concurrency error");
			return oldFuture;
		}
		
		//endregion
		
		
		
		//================//
		// create buffers //
		//================//
		//region
		
		ArrayList<ByteBuffer> opaqueBuffers = builder.makeOpaqueVertexBuffers();
		ArrayList<ByteBuffer> transparentBuffers = builder.makeTransparentVertexBuffers();
		
		this.vboOpaqueWrappers = resizeWrapperArray(this.vboOpaqueWrappers, opaqueBuffers.size());
		this.vboTransparentWrappers = resizeWrapperArray(this.vboTransparentWrappers, transparentBuffers.size());
		
		// mac requires separate IBO objects for each VBO when using OpenGL,
		// all other OS's can share a single IBO for quicker loading times
		boolean useSingleIbo = RENDER_DEF.useSingleIbo();
		@Nullable ArrayList<ByteBuffer> opaqueIndexBuffers = useSingleIbo ? null : this.createIndexBuffers(opaqueBuffers);
		@Nullable ArrayList<ByteBuffer> transparentIndexBuffers = useSingleIbo ? null : this.createIndexBuffers(transparentBuffers);
		
		//endregion
		
		
		
		//================//
		// upload buffers //
		//================//
		//region
		
		try
		{
			//=============//
			// create VBOs //
			//=============//
			
			CompletableFuture<Void> createOpaqueFuture = createBufferWrappersAsync(future, this.vboOpaqueWrappers, opaqueBuffers);
			CompletableFuture<Void> createTransparentFuture = createBufferWrappersAsync(future, this.vboTransparentWrappers, transparentBuffers);
			
			CompletableFuture<Void> createFuture = CompletableFuture.allOf(createOpaqueFuture, createTransparentFuture);
			createFuture.exceptionally((Throwable e) ->
			{
				// create VBOs failed //
				
				if (!ExceptionUtil.isShutdownException(e))
				{
					LOGGER.error("Unexpected issue creating buffer [" + this.minCornerBlockPos + "], error: [" + e.getMessage() + "].", e);
				}
				future.completeExceptionally(e);
				return null;
			});
			createFuture.thenRun(() ->
			{
				//=============//
				// upload VBOs //
				//=============//
				
				CompletableFuture<Void> opaqueFuture = uploadBuffersAsync(future, this.vboOpaqueWrappers, opaqueBuffers, opaqueIndexBuffers);
				CompletableFuture<Void> transparentFuture = uploadBuffersAsync(future, this.vboTransparentWrappers, transparentBuffers, transparentIndexBuffers);
				
				CompletableFuture<Void> uploadFuture = CompletableFuture.allOf(opaqueFuture, transparentFuture);
				uploadFuture.exceptionally((Throwable e) ->
				{
					// upload failed //
					
					if (!ExceptionUtil.isShutdownException(e))
					{
						LOGGER.error("Unexpected issue uploading buffer [" + this.minCornerBlockPos + "], error: [" + e.getMessage() + "].", e);
					}
					future.completeExceptionally(e);
					return null;
				});
				uploadFuture.thenRun(() ->
				{
					// upload success /
					
					this.buffersUploaded = true;
					future.complete(this);
				});
			});
		}
		catch (Exception e)
		{
			if (!ExceptionUtil.isShutdownException(e))
			{
				LOGGER.error("Unexpected issue prepping buffer uploading [" + this.minCornerBlockPos + "], error: [" + e.getMessage() + "].", e);
			}
			future.completeExceptionally(e);
		}
		
		
		//================//
		// buffer cleanup //
		//================//
		
		future.whenComplete((LodBufferContainer lodBufferContainer, Throwable throwable) -> 
		{
			// all the buffers must be manually freed to prevent memory leaks
			
			tryFreeByteBufferList(opaqueBuffers);
			tryFreeByteBufferList(transparentBuffers);
			
			tryFreeByteBufferList(opaqueIndexBuffers);
			tryFreeByteBufferList(transparentIndexBuffers);
			
		});
		
		//endregion
		
		
		
		return future;
	}
	private static void tryFreeByteBufferList(@Nullable ArrayList<ByteBuffer> list)
	{
		if (list != null) 
		{
			for (ByteBuffer buffer : list) 
			{
				MemoryUtil.memFree(buffer);
			} 
		}
	}
	
	
	private ArrayList<ByteBuffer> createIndexBuffers(ArrayList<ByteBuffer> vertexBuffers)
	{
		ArrayList<ByteBuffer> indexBuffers = new ArrayList<>();
		
		for (int i = 0; i < vertexBuffers.size(); i++)
		{
			ByteBuffer buffer = vertexBuffers.get(i);
			int size = buffer.limit() - buffer.position();
			int maxVertexCount = size / LodQuadBuilder.BYTES_PER_VERTEX;
			int quadCount = (maxVertexCount / 4);
			ByteBuffer indexBuffer = IndexBufferBuilder.createBuffer(quadCount);
			indexBuffers.add(indexBuffer);
		}
		
		return indexBuffers;
	}
	
	private static IVertexBufferWrapper[] resizeWrapperArray(IVertexBufferWrapper[] vbos, int newSize)
	{
		if (vbos.length == newSize)
		{
			return vbos;
		}
		
		IVertexBufferWrapper[] newVbos = new IVertexBufferWrapper[newSize];
		System.arraycopy(vbos, 0, newVbos, 0, Math.min(vbos.length, newSize));
		if (newSize < vbos.length)
		{
			for (int i = newSize; i < vbos.length; i++)
			{
				if (vbos[i] != null)
				{
					vbos[i].close();
				}
			}
		}
		return newVbos;
	}
	
	private static CompletableFuture<Void> createBufferWrappersAsync(
		CompletableFuture<LodBufferContainer> parentFuture, 
		IVertexBufferWrapper[] vboWrappers, ArrayList<ByteBuffer> vertexBuffers)
	{
		ArrayList<CompletableFuture<Void>> createVboFutureList = new ArrayList<>();
		for (int i = 0; i < vertexBuffers.size(); i++)
		{
			if (i >= vboWrappers.length)
			{
				throw new RuntimeException("Too many vertex buffers!!");
			}
			
			if (vboWrappers[i] == null)
			{
				final int finalVboIndex = i;
				
				CompletableFuture<Void> future = new CompletableFuture<>();
				createVboFutureList.add(future);
				
				RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("LodBufferContainer Setup", () ->
				{
					try
					{
						// skip this event if requested
						if (Thread.interrupted()
							|| parentFuture.isCancelled())
						{
							throw new InterruptedException();
						}
						
						
						vboWrappers[finalVboIndex] = WRAPPER_FACTORY.createVboWrapper("distantHorizons:McLodRenderer");
						future.complete(null);
					}
					catch (Exception e)
					{
						future.completeExceptionally(e);
					}
				});
			}
		}
		
		if (createVboFutureList.size() == 0)
		{
			return CompletableFuture.completedFuture(null);
		}
		
		CompletableFuture<?>[] futureArray = new CompletableFuture[createVboFutureList.size()];
		for (int i = 0; i < createVboFutureList.size(); i++)
		{
			futureArray[i] = createVboFutureList.get(i);
		}
		return CompletableFuture.allOf(futureArray);
	}
	
	/** Index buffers should be null if {@link AbstractDhRenderApiDefinition#useSingleIbo()} returns true. */
	private static CompletableFuture<Void> uploadBuffersAsync(
		CompletableFuture<LodBufferContainer> parentFuture,
		IVertexBufferWrapper[] vboWrappers, 
		ArrayList<ByteBuffer> vertexBuffers, @Nullable ArrayList<ByteBuffer> indexBuffers
		)
	{
		ArrayList<CompletableFuture<Void>> uploadFutureList = new ArrayList<>();
		int vboIndex = 0;
		for (int i = 0; i < vertexBuffers.size(); i++)
		{
			if (vboIndex >= vboWrappers.length)
			{
				throw new RuntimeException("Too many vertex buffers!!");
			}
			
			
			
			// final variables for use in lambdas //
			
			final int finalVboIndex = vboIndex;
			
			final IVertexBufferWrapper finalVboWrapper = vboWrappers[vboIndex];
			
			final ByteBuffer finalVertexBuffer = vertexBuffers.get(vboIndex);
			// index buffers are optional
			@Nullable final ByteBuffer finalIndexBuffer = (indexBuffers != null) ? indexBuffers.get(vboIndex) : null;
			
			final int finalVertexCount = vertexByteBufferToVertexCount(finalVertexBuffer);
			
			
			
			//===============//
			// vertex upload //
			//===============//
			//region
			
			CompletableFuture<Void> vertexUploadFuture = new CompletableFuture<>();
			uploadFutureList.add(vertexUploadFuture);
			
			RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("LodBufferContainer VBO Upload", () ->
			{
				try
				{
					// skip this event if requested
					if (Thread.interrupted()
						|| parentFuture.isCancelled())
					{
						throw new InterruptedException();
					}
					
					
					try
					{
						finalVboWrapper.uploadVertexBuffer(finalVertexBuffer, finalVertexCount);
						vertexUploadFuture.complete(null);
					}
					catch (Exception e)
					{
						vboWrappers[finalVboIndex] = null;
						finalVboWrapper.close();
						LOGGER.error("Failed to upload buffer. Error: [" + e.getMessage() + "].", e);
					}
				}
				catch (Exception e)
				{
					vertexUploadFuture.completeExceptionally(e);
				}
			});
			
			//endregion
			
			
			
			//==============//
			// index upload //
			//==============//
			//region
			
			if (finalIndexBuffer != null)
			{
				CompletableFuture<Void> indexUploadFuture = new CompletableFuture<>();
				uploadFutureList.add(indexUploadFuture);
				
				RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("LodBufferContainer IBO Upload", () ->
				{
					try
					{
						// skip this event if requested
						if (Thread.interrupted()
							|| parentFuture.isCancelled())
						{
							throw new InterruptedException();
						}
						
						finalVboWrapper.uploadIndexBuffer(finalIndexBuffer, finalVertexCount);
						indexUploadFuture.complete(null);
					}
					catch (Exception e)
					{
						indexUploadFuture.completeExceptionally(e);
					}
				});
			}
			//endregion
			
			
			
			vboIndex++;
		}
		
		if (vboIndex < vboWrappers.length)
		{
			throw new RuntimeException("Too few vertex buffers!!");
		}
		
		
		
		// merge futures //
		
		CompletableFuture<?>[] futureArray = new CompletableFuture[uploadFutureList.size()];
		for (int i = 0; i < uploadFutureList.size(); i++)
		{
			futureArray[i] = uploadFutureList.get(i);
		}
		return CompletableFuture.allOf(futureArray);
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static int vertexByteBufferToVertexCount(ByteBuffer buffer)
	{
		int size = buffer.limit() - buffer.position();
		int vertexCount = size / LodQuadBuilder.BYTES_PER_VERTEX;
		return vertexCount;
	}
	
	/** can be used when debugging */
	public boolean hasNonNullVbos() { return this.vboOpaqueWrappers != null || this.vboTransparentWrappers != null; }
	
	/** can be used when debugging */
	public int vboBufferCount() 
	{
		int count = 0;
		
		if (this.vboOpaqueWrappers != null)
		{
			count += this.vboOpaqueWrappers.length;
		}
		
		if (this.vboTransparentWrappers != null)
		{
			count += this.vboTransparentWrappers.length;
		}
		
		return count;
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	/**
	 * This method is called when object is no longer in use.
	 * Called either after uploadBuffers() returned false (On buffer Upload
	 * thread), or by others when the object is not being used. (not in build,
	 * upload, or render state). 
	 */
	@Override
	public void close()
	{
		this.buffersUploaded = false;
		
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("LodBufferContainer Close", () -> 
		{
			tryCloseBufferWrapperArray(this.vboOpaqueWrappers);
			tryCloseBufferWrapperArray(this.vboTransparentWrappers);
			
			this.uniformContainer.close();
		});
	}
	
	private static void tryCloseBufferWrapperArray(@Nullable IVertexBufferWrapper[] bufferWrappers)
	{
		if (bufferWrappers != null)
		{
			for (int i = 0; i < bufferWrappers.length; i++)
			{
				IVertexBufferWrapper buffer = bufferWrappers[i];
				bufferWrappers[i] = null;
				if (buffer != null)
				{
					buffer.close();
				}
			}
		}
	}
	
	//endregion
	
	
	
}
