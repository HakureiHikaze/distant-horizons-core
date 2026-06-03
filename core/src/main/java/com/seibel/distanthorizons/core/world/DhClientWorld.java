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

package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DhClientWorld extends AbstractDhWorld implements IDhClientWorld
{
	public final ClientOnlySaveStructure saveStructure;
	public final ClientNetworkState networkState = new ClientNetworkState();
	
	
	private final ConcurrentHashMap<String, DhClientLevel> clientLevelByDhId;
	/**
	 * Having a set of level wrappers is done to handle an issue where the client 
	 * level would get unloaded when jumping back and forth between dimensions. <br><br>
	 *
	 * We might have more than one {@link ILevelWrapper} pointing to the same {@link IDhLevel}
	 * since they're not immediately unloaded, and we don't want to unload the {@link IDhLevel}
	 * until all the {@link ILevelWrapper} for that {@link IDhLevel} have been unloaded. 
	 * Any stale {@link IDhLevel} references should disappear on their own after about 
	 * 30 seconds or so thanks to the automatic cleanup.
	 */
	private final Map<String, Set<IClientLevelWrapper>> clientLevelWrapperSetByDhId = new ConcurrentHashMap<>();
	
	private final Timer clientTickTimer = TimerUtil.CreateTimer("ClientTickTimer");
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhClientWorld()
	{
		super(EWorldEnvironment.CLIENT_ONLY);
		
		this.saveStructure = new ClientOnlySaveStructure();
		this.clientLevelByDhId = new ConcurrentHashMap<>();
		
		LOGGER.info("Started DhWorld of type " + this.environment);
		
		this.clientTickTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				DhClientWorld.this.clientLevelByDhId.values().forEach(DhClientLevel::clientTick);
			}
		}, 0, IDhClientWorld.TICK_RATE_IN_MS);
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhClientLevel getOrLoadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}
		
		IClientLevelWrapper clientLevelWrapper = (IClientLevelWrapper) wrapper;
		clientLevelWrapper.markAccessed();
		DhClientLevel storedLevel = this.clientLevelByDhId.computeIfAbsent(wrapper.getDhIdentifier(),
			(key) -> createClientLevel(clientLevelWrapper)
		);
		
		if (storedLevel != null 
			&& storedLevel.getClientLevelWrapper() != wrapper) 
		{
			unloadLevel(storedLevel.getLevelWrapper());
			storedLevel = createClientLevel(clientLevelWrapper);
			if (storedLevel != null)
			{
				this.clientLevelByDhId.put(wrapper.getDhIdentifier(), storedLevel);
			}
		}
		return storedLevel;
	}
	private DhClientLevel createClientLevel(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		try
		{
			if (!ClientApi.INSTANCE.canLoadClientLevel(clientLevelWrapper))
			{
				return null;
			}
			
			DhClientLevel level = new DhClientLevel(this.saveStructure, clientLevelWrapper, this.networkState);
			clientLevelWrapperSetByDhId.computeIfAbsent(clientLevelWrapper.getDhIdentifier(), (dhId) -> Collections.synchronizedSet(new HashSet<>())).add(clientLevelWrapper);
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(clientLevelWrapper));
			ClientApi.INSTANCE.loadWaitingChunksForLevel(clientLevelWrapper);
			
			return level;
		}
		catch (Exception e)
		{
			LOGGER.fatal("Failed to load client level, error: ["+e.getMessage()+"].", e);
			
			String r = MinecraftTextFormat.RED;
			String y = MinecraftTextFormat.YELLOW;
			String cf = MinecraftTextFormat.CLEAR_FORMATTING;
			
			ClientApi.INSTANCE.showChatMessageNextFrame(
				r + "Distant Horizons: Client level loading failed." + cf + "\n" +
				"Unable to load level ["+y+clientLevelWrapper.getDhIdentifier()+cf+"], LODs may not appear. See log for more information.");
			
			return null;
		}
	}
	
	@Override
	public DhClientLevel getLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}
		
		return this.clientLevelByDhId.get(wrapper.getDhIdentifier());
	}
	
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.clientLevelByDhId.values(); }
	@Override
	public int getLoadedLevelCount() { return this.clientLevelByDhId.size(); }
	
	@Override
	public boolean unloadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return false;
		}
		
		if (this.clientLevelByDhId.containsKey(wrapper.getDhIdentifier()))
		{
			LOGGER.info("Unloading level [" + this.clientLevelByDhId.get(wrapper.getDhIdentifier()) + "].");
			wrapper.onUnload();
			Set<IClientLevelWrapper> wrapperSet = this.clientLevelWrapperSetByDhId.get(wrapper.getDhIdentifier());
			wrapperSet.remove(wrapper);
			if (wrapperSet.isEmpty()) 
			{
				this.clientLevelByDhId.remove(wrapper.getDhIdentifier()).close();
			}
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper));
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		super.addDebugMenuStringsToList(messageList);
		this.networkState.addDebugMenuStringsToList(messageList);
	}
	
	@Override
	public void close()
	{
		this.networkState.close();
		
		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		for (DhClientLevel dhClientLevel : this.clientLevelByDhId.values())
		{
			// level wrapper shouldn't be null, but just in case
			IClientLevelWrapper clientLevelWrapper = dhClientLevel.getClientLevelWrapper();
			if (clientLevelWrapper != null)
			{
				clientLevelWrapper.onUnload();
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(clientLevelWrapper));
			}
			
			
			// close levels asynchronously to speed up
			// shutdown on servers with a lot of levels
			CompletableFuture<Void> closeFuture = new CompletableFuture<>();
			Thread closeThread = new Thread(() ->
			{
				dhClientLevel.close();
				closeFuture.complete(null);
			}, "level shutdown");
			closeThread.start();
			closeFutures.add(closeFuture);
		}
		
		// wait for all the levels to finish closing
		for (CompletableFuture<Void> future : closeFutures)
		{
			future.join();
		}
		
		this.clientLevelByDhId.clear();
		this.clientLevelWrapperSetByDhId.clear();
		this.clientTickTimer.cancel();
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}
	
}
