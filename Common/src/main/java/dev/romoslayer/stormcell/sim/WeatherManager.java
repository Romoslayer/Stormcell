package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.climate.BiomeClimate;
import dev.romoslayer.stormcell.compat.SeasonfallBridge;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.persist.WeatherState;
import dev.romoslayer.stormcell.sync.ClientWeatherView;
import dev.romoslayer.stormcell.sync.PlayerWeatherSync;
import dev.romoslayer.stormcell.world.StormcellLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/** Runs the weather of every managed dimension on one server, and keeps each player's display in step with it. */
public final class WeatherManager {
	private final MinecraftServer server;
	private final Path saveDirectory;
	private final Map<ResourceKey<Level>, Managed> levels = new LinkedHashMap<>();
	/** States that could not be written when their dimension was handed back; retried at every save. */
	private final Map<ResourceKey<Level>, WeatherState> pendingSaves = new LinkedHashMap<>();
	private final PlayerWeatherSync sync = new PlayerWeatherSync();
	private final Set<String> warnedDimensions = new HashSet<>();
	private int ticksSinceSave;

	private record Managed(ServerLevel level, LevelWeather weather) {
	}

	public WeatherManager(MinecraftServer server) {
		this.server = server;
		this.saveDirectory = server.getWorldPath(LevelResource.ROOT).resolve(Stormcell.MOD_ID);
	}

	public void start() {
		BiomeClimate.clearCache();
		SeasonfallBridge.reset();
		this.refreshManagedLevels();
	}

	public void stop() {
		this.saveAll(true);
		for (Managed managed : this.levels.values()) {
			((StormcellLevel) managed.level()).stormcell$setWeather(null);
		}
		this.levels.clear();
		BiomeClimate.clearCache();
		ClientWeatherView.clear();
		SeasonfallBridge.reset();
	}

	public void tick() {
		SeasonfallBridge.tick();
		// Like vanilla's weather cycle: paused while the game is frozen or advance_weather is off
		boolean running = this.server.tickRateManager().runsNormally();
		for (Managed managed : this.levels.values()) {
			boolean advance = running && managed.level().getGameRules().get(GameRules.ADVANCE_WEATHER);
			managed.weather().tick(advance);
		}
		this.sync.tick(this.server);
		if (++this.ticksSinceSave >= StormcellConfig.get().general.autosaveIntervalTicks) {
			this.ticksSinceSave = 0;
			this.saveAll(false);
		}
	}

	public void configReloaded() {
		BiomeClimate.clearCache();
		SeasonfallBridge.reset();
		this.warnedDimensions.clear();
		this.refreshManagedLevels();
		for (Managed managed : this.levels.values()) {
			managed.weather().configReloaded();
		}
	}

	/** Takes over (or hands back to vanilla) each dimension according to the config. */
	private void refreshManagedLevels() {
		StormcellConfig config = StormcellConfig.get();
		for (ServerLevel level : this.server.getAllLevels()) {
			ResourceKey<Level> key = level.dimension();
			boolean wanted = this.shouldManage(level);
			Managed current = this.levels.get(key);
			if (wanted && current == null) {
				WeatherState saved = this.pendingSaves.remove(key);
				if (saved == null) {
					saved = WeatherState.load(this.file(key), config.performance.maximumStormSystems, config.general.maxActiveWeatherCells);
				}
				LevelWeather weather = new LevelWeather(new LevelEnvironment(level), saved);
				this.levels.put(key, new Managed(level, weather));
				((StormcellLevel) level).stormcell$setWeather(weather);
				// Vanilla's world-wide rain stops here; each player is shown their local weather instead
				level.setRainLevel(0.0F);
				level.setThunderLevel(0.0F);
				Stormcell.LOGGER.info("Stormcell is running the weather in {}", key.identifier());
			} else if (!wanted && current != null) {
				WeatherState state = current.weather().snapshotState();
				if (!state.save(this.file(key))) {
					this.pendingSaves.put(key, state);
				}
				((StormcellLevel) level).stormcell$setWeather(null);
				this.levels.remove(key);
				Stormcell.LOGGER.info("Stormcell handed the weather in {} back to vanilla", key.identifier());
			}
		}
	}

	private boolean shouldManage(ServerLevel level) {
		StormcellConfig config = StormcellConfig.get();
		String id = level.dimension().identifier().toString();
		if (!config.general.enabled || !config.general.dimensions.contains(id)) {
			return false;
		}
		if (!level.canHaveWeather()) {
			if (this.warnedDimensions.add(id)) {
				Stormcell.LOGGER.warn("{} is listed in general.dimensions but has no sky to show weather in; leaving it alone", id);
			}
			return false;
		}
		return true;
	}

	/** Saves every dimension with unsaved changes (all of them when forced), and retries earlier failed saves. */
	private void saveAll(boolean force) {
		Iterator<Map.Entry<ResourceKey<Level>, WeatherState>> pending = this.pendingSaves.entrySet().iterator();
		while (pending.hasNext()) {
			Map.Entry<ResourceKey<Level>, WeatherState> entry = pending.next();
			if (entry.getValue().save(this.file(entry.getKey()))) {
				pending.remove();
			}
		}
		for (Map.Entry<ResourceKey<Level>, Managed> entry : this.levels.entrySet()) {
			LevelWeather weather = entry.getValue().weather();
			if (force || weather.isDirty()) {
				WeatherState state = weather.snapshotState();
				if (state.save(this.file(entry.getKey()))) {
					weather.markSaved(state);
				}
			}
		}
		if (force && !this.pendingSaves.isEmpty()) {
			Stormcell.LOGGER.error("Could not save the weather of {}; it will start fresh next time", this.pendingSaves.keySet());
		}
	}

	private Path file(ResourceKey<Level> dimension) {
		Identifier id = dimension.identifier();
		return this.saveDirectory.resolve(id.getNamespace()).resolve(id.getPath() + ".json");
	}

	public @Nullable LevelWeather weather(ResourceKey<Level> dimension) {
		Managed managed = this.levels.get(dimension);
		return managed == null ? null : managed.weather();
	}

	public void onLevelInfo(ServerPlayer player, ServerLevel level) {
		this.sync.onLevelInfo(player, level);
	}

	/**
	 * The players of a level slept through the night. Vanilla ends its world-wide rain then (if advance_weather is on);
	 * here the storms over the sleepers end and weather elsewhere carries on.
	 */
	public void onNightSkipped(ServerLevel level) {
		Managed managed = this.levels.get(level.dimension());
		if (managed == null || !level.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
			return;
		}
		List<WeatherEnvironment.Observer> sleepers = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (player.isSleeping()) {
				sleepers.add(new WeatherEnvironment.Observer(player.getX(), player.getZ()));
			}
		}
		if (!sleepers.isEmpty()) {
			int removed = managed.weather().clearStormsOver(sleepers);
			if (removed > 0) {
				Stormcell.LOGGER.debug("Sleeping cleared {} storm(s) in {}", removed, level.dimension().identifier());
			}
		}
	}
}
