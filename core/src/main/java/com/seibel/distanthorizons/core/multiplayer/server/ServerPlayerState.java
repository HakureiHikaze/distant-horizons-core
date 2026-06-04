package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayloadSender;
import com.seibel.distanthorizons.core.multiplayer.fullData.SharedBandwidthLimit;
import com.seibel.distanthorizons.core.network.event.internal.IncompatibleMessageInternalEvent;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.base.LevelInitMessage;
import com.seibel.distanthorizons.core.network.messages.base.RequestLevelInitMessage;
import com.seibel.distanthorizons.core.network.messages.base.SessionConfigMessage;
import com.seibel.distanthorizons.core.network.event.internal.CloseInternalEvent;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class ServerPlayerState implements Closeable
{
	private final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	private final ConfigChangeListener<String> levelKeyPrefixChangeListener
			= new ConfigChangeListener<>(Config.Server.levelKeyPrefix, this::onLevelKeyPrefixConfigChanged);
	private final SessionConfig.AnyChangeListener configAnyChangeListener = new SessionConfig.AnyChangeListener(this::sendConfigMessage);
	
	
	private final String serverKeyWithoutId = Config.Server.serverKey.get();
	private final String serverKey = (this.serverKeyWithoutId.isEmpty() ? "" : Config.Server.serverId.get() + "_" + this.serverKeyWithoutId.trim())
			.replaceAll("[^" + LevelInitMessage.ALLOWED_CHARS_REGEX + " ]", "")
			.replaceAll(" ", "_");
	private String lastLevelKey = "";
	
	
	public final NetworkSession networkSession;
	public IServerPlayerWrapper getServerPlayer() { return this.networkSession.serverPlayer; }
	
	@NotNull
	public final SessionConfig sessionConfig = new SessionConfig();
	public boolean isReady() { return this.sessionConfig.constrainingConfig != null; }
	
	public final FullDataPayloadSender fullDataPayloadSender;
	
	private final ConcurrentHashMap<AbstractDhServerLevel, RateLimiterSet> rateLimiterSets = new ConcurrentHashMap<>();
	public RateLimiterSet getRateLimiterSet(AbstractDhServerLevel level) { return this.rateLimiterSets.computeIfAbsent(level, ignored -> new RateLimiterSet()); }
	public void clearRateLimiterSets() { this.rateLimiterSets.clear(); }
	
	
	//==============//
	// constructors //
	//==============//
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer, SharedBandwidthLimit sharedBandwidthLimit)
	{
		this.networkSession = new NetworkSession(serverPlayer);
		this.fullDataPayloadSender = new FullDataPayloadSender(this.networkSession, this.sessionConfig::getPlayerBandwidthLimit, sharedBandwidthLimit);
		
		this.networkSession.registerHandler(SessionConfigMessage.class, (sessionConfigMessage) ->
		{
			this.sessionConfig.constrainingConfig = sessionConfigMessage.config;
			this.sendConfigMessage();
		});
		
		this.networkSession.registerHandler(RequestLevelInitMessage.class, (requestLevelInitMessage) ->
		{
			sendLevelKey(requestLevelInitMessage.dimensionResourceLocation);
		});
		
		
		this.networkSession.registerHandler(CloseInternalEvent.class, event -> {
			// No-op. prevents "Unhandled message" log entries
		});
		
		this.networkSession.registerHandler(IncompatibleMessageInternalEvent.class, event ->
		{
			// Client won't understand this message, but it's still enough to display incompatible protocol error
			this.networkSession.sendMessage(new CloseReasonMessage("Incompatible protocol version"));
			this.close();
		});
	}
	
	
	
	//=================//
	// client updating //
	//=================//
	
	private void onLevelKeyPrefixConfigChanged(String newLevelKey) { this.sendLevelKey(); }
	
	private void sendLevelKey(String dimensionResourceLocation)
	{
		sendLevelKey(() ->
		{
			IServerLevelWrapper serverLevelWrapper = MC_SHARED.getWrappedServerLevelWithDimensionResourceLocation(dimensionResourceLocation);
			if (serverLevelWrapper == null)
			{
				LodUtil.assertNotReach("Unable to get server level from");
			}
			
			return serverLevelWrapper.getKeyedLevelDimensionName();
		});
	}
	private void sendLevelKey()
	{
		sendLevelKey(() ->
			this.getServerPlayer()
				.getLevel()
				.getKeyedLevelDimensionName());
	}
	private void sendLevelKey(Supplier<String> levelKeySupplier)
	{
		if (Config.Server.sendLevelKeys.get())
		{
			String levelKey = levelKeySupplier.get();
			// let the client's know about the change
			if (!levelKey.equals(this.lastLevelKey))
			{
				this.lastLevelKey = levelKey;
				this.networkSession.sendMessage(new LevelInitMessage(this.serverKey, levelKey));
			}
		}
	}
	
	private void sendConfigMessage()
	{
		double coordinateScale = this.getServerPlayer().getLevel().getDimensionType().getCoordinateScale();
		this.sessionConfig.constrainValue(Config.Common.WorldGenerator.generationCenterChunkX, (int) (Config.Common.WorldGenerator.generationCenterChunkX.get() / coordinateScale));
		this.sessionConfig.constrainValue(Config.Common.WorldGenerator.generationCenterChunkZ, (int) (Config.Common.WorldGenerator.generationCenterChunkZ.get() / coordinateScale));
		
		this.networkSession.sendMessage(new SessionConfigMessage(this.sessionConfig));
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		this.fullDataPayloadSender.close();
		this.levelKeyPrefixChangeListener.close();
		this.configAnyChangeListener.close();
		this.networkSession.close();
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	public class RateLimiterSet
	{
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> generationRequestRateLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> Config.Server.generationRequestRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Full data request rate limit: " + ServerPlayerState.this.sessionConfig.getGenerationRequestRateLimit()));
				}
		);
		
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> syncOnLoginRateLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> Config.Server.syncOnLoadRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Sync on login rate limit: " + ServerPlayerState.this.sessionConfig.getSyncOnLoginRateLimit()));
				}
		);
		
	}
	
}