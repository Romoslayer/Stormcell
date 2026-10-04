package dev.romoslayer.stormcell.world;

import dev.romoslayer.stormcell.sim.LevelWeather;
import org.jspecify.annotations.Nullable;

/** Added to every ServerLevel so the weather of a dimension is one field read away from the hot code paths. */
public interface StormcellLevel {
	@Nullable LevelWeather stormcell$weather();

	void stormcell$setWeather(@Nullable LevelWeather weather);
}
