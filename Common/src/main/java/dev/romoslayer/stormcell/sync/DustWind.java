package dev.romoslayer.stormcell.sync;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The sound of a dust storm: an unbroken rush of wind for players caught in one, made from vanilla's elytra flying
 * sound played lower and slower. One gust plays at a time; the next starts shortly before it ends, so the wind never
 * stops while the dust lasts, and it is cut off as soon as the player is out of the dust. The grey, gloomy sky comes
 * from the rain strength PlayerWeatherSync sends. None of this touches gameplay.
 */
final class DustWind {
	/** Length of the vanilla elytra flying sound in seconds (it plays longer at a lower pitch). */
	private static final double CLIP_SECONDS = 10.22;
	/** How long each gust overlaps the next, in seconds. */
	private static final double OVERLAP_SECONDS = 2.0;
	private static final float MIN_PITCH = 0.55F;
	private static final float MAX_PITCH = 0.65F;

	private DustWind() {
	}

	/** Per-player wind: when the next gust is due, and whether one is playing. */
	static final class Gusts {
		long nextGustTick;
		boolean blowing;
	}

	static void tick(ServerPlayer player, Gusts gusts, float dust, long tick) {
		StormcellConfig.DryWeather config = StormcellConfig.get().dryWeather;
		double threshold = config.dustThreshold;
		boolean inDust = config.dustStorms && config.dustSound && config.dustSoundVolume > 0.0 && dust >= threshold;
		if (!inDust) {
			stop(player, gusts);
			return;
		}
		if (gusts.blowing && tick < gusts.nextGustTick) {
			return;
		}
		double strength = Mth.clamp((dust - threshold) / Math.max(0.01, 1.0 - threshold), 0.0, 1.0);
		RandomSource random = player.getRandom();
		float volume = (float) (config.dustSoundVolume * (0.4 + 0.6 * strength));
		float pitch = MIN_PITCH + random.nextFloat() * (MAX_PITCH - MIN_PITCH);
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ELYTRA_FLYING), SoundSource.WEATHER,
				player.getX(), player.getEyeY(), player.getZ(), volume, pitch, random.nextLong()));
		gusts.blowing = true;
		gusts.nextGustTick = tick + gustTicks(pitch);
		ClientWeatherView.noteWindSound(player);
	}

	/** Cuts the wind off (left the dust, changed dimension, or the storm ended). */
	static void stop(ServerPlayer player, Gusts gusts) {
		if (gusts.blowing) {
			gusts.blowing = false;
			// Only Stormcell plays this sound in the weather category (the game's own elytra sound is a player sound)
			player.connection.send(Versioned.stopDustWind());
		}
	}

	/** Ticks until the next gust should start: the clip's length at this pitch, less the overlap. */
	static long gustTicks(float pitch) {
		return Math.max(20L, Math.round((CLIP_SECONDS / pitch - OVERLAP_SECONDS) * 20.0));
	}
}
