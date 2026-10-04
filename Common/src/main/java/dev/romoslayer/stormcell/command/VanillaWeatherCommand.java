package dev.romoslayer.stormcell.command;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.sim.StormSystem;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What vanilla's /weather does in a dimension Stormcell runs. Called from the WeatherCommand mixin; a null result
 * means "not ours, let vanilla handle it".
 *
 * <ul>
 * <li>{@code clear}: every storm in the dimension ends and no new ones form for the duration (10 minutes if none).
 * <li>{@code rain} / {@code thunder}: a storm starts over whoever ran the command (the world spawn for the console)
 * and stays there at full strength for the duration, or vanilla's usual length for that weather if none is given.
 * Then it drifts off and fades over a few minutes, like any other storm. At the storm limit the weakest storm
 * makes way, so the command always does something.
 * </ul>
 */
public final class VanillaWeatherCommand {
	private static final long DEFAULT_CLEAR_TICKS = 12000L;

	private VanillaWeatherCommand() {
	}

	private static @Nullable LevelWeather weather(CommandSourceStack source) {
		return StormcellConfig.get().gameplay.vanillaWeatherCommand ? RegionalWeather.weather(source.getLevel()) : null;
	}

	public static @Nullable Integer clear(CommandSourceStack source, int duration) {
		LevelWeather weather = weather(source);
		if (weather == null) {
			return null;
		}
		weather.clearStorms(0.0, 0.0, -1.0);
		weather.suppressFormation(duration > 0 ? duration : DEFAULT_CLEAR_TICKS);
		source.sendSuccess(() -> Component.translatable("commands.weather.set.clear"), true);
		return Math.max(duration, 1);
	}

	public static @Nullable Integer rain(CommandSourceStack source, int duration) {
		// Somewhere between light and heavy rain, so it visibly rains
		StormcellConfig.Thresholds t = StormcellConfig.get().thresholds;
		double intensity = t.lightRain + (t.heavyRain - t.lightRain) * source.getLevel().getRandom().nextDouble();
		return start(source, duration, StormSystem.Kind.RAIN_SYSTEM, Math.max(intensity, t.drizzle + 0.1), ServerLevel.RAIN_DURATION,
				"commands.weather.set.rain");
	}

	public static @Nullable Integer thunder(CommandSourceStack source, int duration) {
		double intensity = Math.min(1.0, Math.max(0.9, StormcellConfig.get().thresholds.thunderstorm + 0.05));
		return start(source, duration, StormSystem.Kind.THUNDERSTORM, intensity, ServerLevel.THUNDER_DURATION, "commands.weather.set.thunder");
	}

	private static @Nullable Integer start(CommandSourceStack source, int duration, StormSystem.Kind kind, double intensity, IntProvider usualLength,
			String message) {
		LevelWeather weather = weather(source);
		if (weather == null) {
			return null;
		}
		int length = duration > 0 ? duration : usualLength.sample(source.getLevel().getRandom());
		Vec3 pos = source.getPosition();
		weather.spawnStorm(kind, pos.x, pos.z, intensity, length, LevelWeather.Overflow.REPLACE_WEAKEST);
		source.sendSuccess(() -> Component.translatable(message), true);
		return length;
	}
}
