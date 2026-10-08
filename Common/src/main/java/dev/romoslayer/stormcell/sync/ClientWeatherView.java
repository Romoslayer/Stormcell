package dev.romoslayer.stormcell.sync;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What each player's client is showing for weather, worked out on the server from the packets actually sent to it
 * (by Stormcell or by vanilla), using the same rules the vanilla client applies:
 * <ul>
 * <li>joining, or respawning into a different dimension, starts a fresh client world with no rain or thunder;
 * <li>RAIN_LEVEL_CHANGE and THUNDER_LEVEL_CHANGE set the levels directly;
 * <li>START_RAINING sets rain to 0 and STOP_RAINING sets it to 1 (vanilla always follows them with a level change).
 * </ul>
 * The client draws thunder as thunder level times rain level. With debug.logClientWeather on, every weather packet is
 * written to the server log; /stormcell client shows the current view.
 */
public final class ClientWeatherView {
	private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

	private ClientWeatherView() {
	}

	/** One client's weather as it stands after the packets sent so far. */
	public static final class View {
		@Nullable ResourceKey<Level> dimension;
		float rain;
		float thunder;
		long packets;
		@Nullable String lastEvent;
		long lastChangeMillis;
		/** Dust storm wind sounds Stormcell has sent. */
		long windSounds;
		/** Stop-sound packets that cut the dust storm wind off. */
		long windStops;

		public float rain() {
			return this.rain;
		}

		public float thunder() {
			return this.thunder;
		}

		/** Thunder as the client draws it (vanilla multiplies it by the rain level). */
		public float shownThunder() {
			return this.thunder * this.rain;
		}

		public long packets() {
			return this.packets;
		}

		public @Nullable String lastEvent() {
			return this.lastEvent;
		}

		public long lastChangeMillis() {
			return this.lastChangeMillis;
		}

		public long windSounds() {
			return this.windSounds;
		}

		public long windStops() {
			return this.windStops;
		}

		public @Nullable ResourceKey<Level> dimension() {
			return this.dimension;
		}

		/** A fresh client world: no rain, no thunder. */
		synchronized void newWorld(ResourceKey<Level> dimension, String reason) {
			this.dimension = dimension;
			this.rain = 0.0F;
			this.thunder = 0.0F;
			// Not counted as a weather packet: it is the join or respawn packet that resets the weather
			this.lastEvent = reason;
			this.lastChangeMillis = System.currentTimeMillis();
		}

		/** Applies one game event packet exactly as the vanilla client does. Returns false if it is not about weather. */
		synchronized boolean apply(ClientboundGameEventPacket.Type event, float value) {
			if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
				this.rain = Mth.clamp(value, 0.0F, 1.0F);
				this.changed("RAIN_LEVEL_CHANGE");
			} else if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
				this.thunder = Mth.clamp(value, 0.0F, 1.0F);
				this.changed("THUNDER_LEVEL_CHANGE");
			} else if (event == ClientboundGameEventPacket.START_RAINING) {
				this.rain = 0.0F;
				this.changed("START_RAINING");
			} else if (event == ClientboundGameEventPacket.STOP_RAINING) {
				this.rain = 1.0F;
				this.changed("STOP_RAINING");
			} else {
				return false;
			}
			return true;
		}

		private void changed(String event) {
			this.packets++;
			this.lastEvent = event;
			this.lastChangeMillis = System.currentTimeMillis();
		}

		synchronized String describe() {
			return String.format(Locale.ROOT, "rain %.3f, thunder %.3f (drawn %.3f) in %s", this.rain, this.thunder, this.shownThunder(),
					this.dimension == null ? "?" : Versioned.id(this.dimension));
		}
	}

	/** Called for every packet sent to a player in the game phase. Cheap for packets that are not about weather. */
	public static void observe(ServerPlayer player, Packet<?> packet) {
		if (packet instanceof ClientboundGameEventPacket event) {
			View view = view(player);
			if (view.apply(event.getEvent(), event.getParam())) {
				log(player, view, view.lastEvent);
			}
		} else if (packet instanceof ClientboundLoginPacket login) {
			View view = view(player);
			view.newWorld(Versioned.dimension(login), "join");
			log(player, view, "join (new client world)");
		} else if (packet instanceof ClientboundRespawnPacket respawn) {
			View view = view(player);
			ResourceKey<Level> dimension = Versioned.dimension(respawn);
			// The client only starts a new world (and loses its weather) when the dimension changes
			if (dimension != view.dimension) {
				view.newWorld(dimension, "dimension change");
				log(player, view, "dimension change (new client world)");
			}
		} else if (packet instanceof ClientboundStopSoundPacket stop && Versioned.isDustWindStop(stop)) {
			// Only DustWind stops this sound in the weather category
			View view = view(player);
			synchronized (view) {
				view.windStops++;
			}
			log(player, view, "dust storm wind stopped");
		} else if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> inner : bundle.subPackets()) {
				observe(player, inner);
			}
		}
	}

	private static View view(ServerPlayer player) {
		return VIEWS.computeIfAbsent(player.getUUID(), uuid -> new View());
	}

	private static void log(ServerPlayer player, View view, @Nullable String cause) {
		if (StormcellConfig.get().debug.logClientWeather) {
			Stormcell.LOGGER.info("[client weather] {} at {}, {}: {} after {}", player.getScoreboardName(), player.getBlockX(), player.getBlockZ(),
					view.describe(), cause);
		}
	}

	/** Counts a wind sound sent to a player in a dust storm. */
	static void noteWindSound(ServerPlayer player) {
		View view = view(player);
		synchronized (view) {
			view.windSounds++;
		}
		if (StormcellConfig.get().debug.logClientWeather) {
			Stormcell.LOGGER.info("[client weather] {} at {}, {}: dust storm wind sound ({} so far)", player.getScoreboardName(), player.getBlockX(),
					player.getBlockZ(), view.windSounds);
		}
	}

	/** The current view of a player's client, or null if nothing has been sent to it yet. */
	public static @Nullable View of(ServerPlayer player) {
		return VIEWS.get(player.getUUID());
	}

	/** Forgets players who are no longer online. */
	public static void retain(Set<UUID> online) {
		VIEWS.keySet().retainAll(online);
	}

	/** Forgets everything (server stopping). */
	public static void clear() {
		VIEWS.clear();
	}

	/** For tests: a view not tied to a player. */
	static View detached() {
		return new View();
	}
}
