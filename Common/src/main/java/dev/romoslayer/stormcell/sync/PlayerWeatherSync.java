package dev.romoslayer.stormcell.sync;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.world.RegionalWeather;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Shows every player the weather where they are standing. A vanilla client has one rain strength and one thunder
 * strength for its whole world, set by the same packets vanilla uses for its global weather, so the server simply
 * sends each player different values. They are eased a little every tick, the way vanilla fades rain in and out.
 *
 * <p>The display uses the same blended samples, and the same drizzle threshold, as the server's own "is it raining
 * here" answer, so a player sees rain exactly where the game treats them as being in the rain.
 */
public final class PlayerWeatherSync {
	private static final float SEND_STEP = 0.01F;

	private final Map<UUID, State> states = new HashMap<>();
	private long tick;

	/** One player's display. Kept free of game classes so the easing can be tested on its own. */
	public static final class State {
		/** Moving further than this between two ticks (a teleport) jumps straight to the new weather. */
		static final double JUMP_DISTANCE_SQR = 96.0 * 96.0;

		boolean managed;
		ResourceKey<Level> dimension;
		public float rain;
		public float thunder;
		float targetRain;
		float targetThunder;
		float dust;
		final DustWind.Gusts gusts = new DustWind.Gusts();
		float sentRain = -1.0F;
		float sentThunder = -1.0F;
		/** Where the player was on the previous tick, for spotting teleports. */
		double previousX;
		double previousZ;
		boolean hasPrevious;

		/**
		 * Records this tick's position and says whether the player jumped since the last tick. Movement only counts
		 * between consecutive ticks, so ordinary fast travel never looks like a teleport.
		 */
		public boolean moveTo(double x, double z) {
			boolean jumped = false;
			if (this.hasPrevious) {
				double dx = x - this.previousX;
				double dz = z - this.previousZ;
				jumped = dx * dx + dz * dz > JUMP_DISTANCE_SQR;
			}
			this.previousX = x;
			this.previousZ = z;
			this.hasPrevious = true;
			return jumped;
		}

		/** Sets what the display should move towards. */
		public void target(float rain, float thunder) {
			this.targetRain = rain;
			this.targetThunder = thunder;
		}

		/** Jumps straight to the target. */
		public void snap() {
			this.rain = this.targetRain;
			this.thunder = this.targetThunder;
		}

		/** Moves the display one tick towards the target. */
		public void ease(float step) {
			this.rain = approach(this.rain, this.targetRain, step);
			this.thunder = approach(this.thunder, this.targetThunder, step);
		}
	}

	public void tick(MinecraftServer server) {
		this.tick++;
		StormcellConfig.Client config = StormcellConfig.get().client;
		float step = (float) config.transitionPerTick;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			State state = this.states.computeIfAbsent(player.getUUID(), uuid -> new State());
			boolean jumped = state.moveTo(player.getX(), player.getZ());
			ServerLevel level = player.level();
			LevelWeather weather = RegionalWeather.weather(level);
			if (weather == null) {
				if (state.managed) {
					// The dimension stopped being managed (config reload): hand the display back to vanilla
					state.managed = false;
					DustWind.stop(player, state.gusts);
					send(player, ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0F));
					send(player, ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0F));
				}
				continue;
			}
			if (!state.managed || state.dimension != level.dimension()) {
				this.snap(player, level, weather, state);
				continue;
			}
			if (jumped || (this.tick + player.getId()) % config.targetRefreshTicks == 0) {
				this.updateTarget(player, weather, state);
			}
			if (jumped) {
				state.snap();
			} else {
				state.ease(step);
			}
			this.sendChanges(player, state);
			DustWind.tick(player, state.gusts, state.dust, this.tick);
		}
		if (this.tick % 200 == 0) {
			Set<UUID> online = new HashSet<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				online.add(player.getUUID());
			}
			this.states.keySet().retainAll(online);
			ClientWeatherView.retain(online);
		}
	}

	/**
	 * A player joined, respawned or changed dimension, and the client has just started a fresh, rainless world. Send
	 * the local weather straight away (vanilla only sends weather when its world-wide flag says it is raining).
	 */
	public void onLevelInfo(ServerPlayer player, ServerLevel level) {
		State state = this.states.computeIfAbsent(player.getUUID(), uuid -> new State());
		LevelWeather weather = RegionalWeather.weather(level);
		if (weather == null) {
			state.managed = false;
			return;
		}
		this.snap(player, level, weather, state);
	}

	private void snap(ServerPlayer player, ServerLevel level, LevelWeather weather, State state) {
		// A new client world: stop any wind and let it restart if the player is still in dust
		DustWind.stop(player, state.gusts);
		state.managed = true;
		state.dimension = level.dimension();
		state.moveTo(player.getX(), player.getZ());
		this.updateTarget(player, weather, state);
		state.snap();
		state.sentRain = state.rain;
		state.sentThunder = state.thunder;
		send(player, ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, state.rain);
		send(player, ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, state.thunder);
	}

	private void updateTarget(ServerPlayer player, LevelWeather weather, State state) {
		Targets targets = targets(StormcellConfig.get(), weather, player.level(), player.getX(), player.getY(), player.getZ());
		state.target(targets.rain(), targets.thunder());
		state.dust = targets.dust();
	}

	/**
	 * What a client at a spot should be shown.
	 *
	 * @param rain    rain strength to send (in biomes where clients draw no rain this only darkens the sky)
	 * @param thunder thunder strength to send
	 * @param dust    dust thickness, for the dust storm effects
	 */
	public record Targets(float rain, float thunder, float dust) {
	}

	public static Targets targets(StormcellConfig config, LevelWeather weather, ServerLevel level, double x, double y, double z) {
		boolean drawsRain = level.getBiome(BlockPos.containing(x, y, z)).value().hasPrecipitation();
		return targets(config, weather.precipitationAt(x, z), weather.stormAt(x, z), weather.dustAt(x, z), drawsRain);
	}

	/**
	 * Rain follows the precipitation reaching the ground. Where the client draws no rain at all (deserts, savannas,
	 * badlands) the storm overhead still darkens the sky, and so does dust; nothing is drawn falling there, so the
	 * display still matches the ground staying dry.
	 */
	public static Targets targets(StormcellConfig config, float precipitation, float storm, float dust, boolean drawsRain) {
		// Where the client draws no rain, only the sky shows the storm, at the configured darkness, whatever the simulation
		// says about rain reaching the ground (nothing gets wet there anyway)
		float rain = drawsRain ? clientRain(config, precipitation) : drySky(config, storm);
		rain = Math.max(rain, dustSky(config, dust));
		return new Targets(rain, clientThunder(config, storm), dust);
	}

	/** Sky darkness (as a rain strength) under a storm whose rain the client does not draw. */
	static float drySky(StormcellConfig config, float storm) {
		StormcellConfig.Thresholds t = config.thresholds;
		if (storm < t.drizzle) {
			return 0.0F;
		}
		return (float) Mth.clamp(Math.min(1.0, blend(storm, t.drizzle, t.thunderstorm, 0.15, 1.0)) * config.dryWeather.drySkyDarkness, 0.0, 1.0);
	}

	/** Sky darkness (as a rain strength) in a dust storm. */
	static float dustSky(StormcellConfig config, float dust) {
		double threshold = config.dryWeather.dustThreshold;
		if (!config.dryWeather.dustStorms || dust < threshold) {
			return 0.0F;
		}
		return (float) Mth.clamp(blend(dust, threshold, 1.0, 0.2, 1.0) * config.dryWeather.dustSkyDarkness, 0.0, 1.0);
	}

	private void sendChanges(ServerPlayer player, State state) {
		if (Math.abs(state.rain - state.sentRain) >= SEND_STEP || state.rain == state.targetRain && state.rain != state.sentRain) {
			state.sentRain = state.rain;
			send(player, ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, state.rain);
		}
		if (Math.abs(state.thunder - state.sentThunder) >= SEND_STEP || state.thunder == state.targetThunder && state.thunder != state.sentThunder) {
			state.sentThunder = state.thunder;
			send(player, ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, state.thunder);
		}
	}

	private static void send(ServerPlayer player, ClientboundGameEventPacket.Type type, float value) {
		player.connection.send(new ClientboundGameEventPacket(type, value));
	}

	private static float approach(float current, float target, float step) {
		if (current < target) {
			return Math.min(target, current + step);
		}
		return Math.max(target, current - step);
	}

	/**
	 * The rain strength a client shows for a precipitation intensity: none below the drizzle threshold (where the
	 * server also stops treating the spot as rainy), then blended between the configured steps.
	 */
	public static float clientRain(StormcellConfig config, float precipitation) {
		StormcellConfig.Thresholds t = config.thresholds;
		StormcellConfig.Client c = config.client;
		if (precipitation < t.drizzle) {
			return 0.0F;
		}
		double level;
		if (precipitation <= t.lightRain) {
			level = blend(precipitation, t.drizzle, t.lightRain, c.rainLevelAtDrizzle, c.rainLevelAtLightRain);
		} else if (precipitation <= t.heavyRain) {
			level = blend(precipitation, t.lightRain, t.heavyRain, c.rainLevelAtLightRain, c.rainLevelAtHeavyRain);
		} else if (precipitation <= t.thunderstorm) {
			level = blend(precipitation, t.heavyRain, t.thunderstorm, c.rainLevelAtHeavyRain, c.rainLevelAtThunderstorm);
		} else {
			level = c.rainLevelAtThunderstorm;
		}
		return (float) Mth.clamp(level, 0.0, 1.0);
	}

	/** The thunder strength (stormy sky) a client shows for a storm intensity. */
	public static float clientThunder(StormcellConfig config, float storm) {
		StormcellConfig.Thresholds t = config.thresholds;
		double atStorm = config.client.thunderLevelAtThunderstorm;
		if (storm <= t.heavyRain) {
			return 0.0F;
		}
		if (storm <= t.thunderstorm) {
			return (float) blend(storm, t.heavyRain, t.thunderstorm, 0.0, atStorm);
		}
		return (float) Mth.clamp(blend(storm, t.thunderstorm, 1.0, atStorm, 1.0), 0.0, 1.0);
	}

	private static double blend(double value, double from, double to, double atFrom, double atTo) {
		double span = to - from;
		return span <= 0.0 ? atTo : Mth.lerp((value - from) / span, atFrom, atTo);
	}
}
