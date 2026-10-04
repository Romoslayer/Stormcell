package dev.romoslayer.stormcell.config;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.config.ConfigBinder.Comment;
import dev.romoslayer.stormcell.config.ConfigBinder.Range;
import dev.romoslayer.stormcell.persist.SafeFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/stormcell.toml. Options missing from the file keep their defaults, and the file is rewritten after every
 * successful load so options added by an update appear in it (the original is kept as stormcell.toml.bak if anything in
 * it had to be ignored or corrected). A file that cannot be parsed is left alone and the current settings stay in use.
 */
public final class StormcellConfig {
	private static final String FILE_NAME = "stormcell.toml";
	private static final String HEADER = """
			Stormcell - regional, moving weather for Minecraft servers.

			Weather is simulated in large square cells. Storm systems form where the air is humid and unsettled, drift
			with the wind, grow, mature and fade away, so one place can be in a thunderstorm while another stays clear.
			Players do not need the mod: each one is simply sent the weather of the place they are standing in.

			Intensity scale used throughout this file (0 = clear sky, 1 = the most violent storm):
			  0.20 cloudy / unstable, 0.30 drizzle, 0.50 light rain, 0.70 heavy rain, 0.85 thunderstorm, 1.00 severe.
			The exact steps are set in [thresholds].

			Reload with /stormcell reload. Changing the cell size or the dimensions list is best done while the server is
			stopped.""";

	private static volatile StormcellConfig instance = new StormcellConfig();
	private static Path file;

	@Comment("Basic switches and the size of the simulation.")
	public General general = new General();
	@Comment("How often new weather forms.")
	public Generation generation = new Generation();
	@Comment("How storm systems grow, travel and fade.")
	public StormBehavior stormBehavior = new StormBehavior();
	@Comment("""
			The three kinds of storm system. Radii are in blocks; durations are how long a storm stays at full strength
			before it starts to weaken. length and width stretch a storm along and across its direction of travel
			(a long, narrow band crossing the land is a weather front).""")
	public StormTypes stormTypes = new StormTypes();
	@Comment("""
			Wind steers storms. It is purely part of the weather simulation: it never pushes players, mobs, boats,
			projectiles or anything else.""")
	public Wind wind = new Wind();
	@Comment("The temperature, humidity and pressure the simulation tracks for each cell.")
	public Atmosphere atmosphere = new Atmosphere();
	@Comment("How the climate of each biome shapes its weather.")
	public Biomes biomes = new Biomes();
	@Comment("""
			Per-biome adjustments. The key is a biome id ("minecraft:desert") or a biome tag ("#minecraft:is_ocean").
			An exact id beats a tag; among tags the first match wins.
			  rainMultiplier      how often rain forms over the biome
			  stormMultiplier     how often thunderstorms form over the biome
			  humidityMultiplier  how moist the air is (also decides how much rain reaches the ground)
			  temperatureOffset   added to the biome temperature used by the simulation (vanilla scale: 0.15 is freezing)
			To neutralise one of the defaults, set its multipliers to 1.0 and its offset to 0.0.""")
	public Map<String, BiomeOverride> biomeOverrides = defaultBiomeOverrides();
	@Comment("""
			Height above sea level. Vanilla clients already draw snow instead of rain high up in cool biomes, using the
			game's own height cooling; Stormcell follows the same rule by default so what players see matches what
			happens (snow piling up, cauldrons filling, mobs getting wet).""")
	public Elevation elevation = new Elevation();
	@Comment("Lightning only strikes under thunderstorms, more often the stronger the storm.")
	public Lightning lightning = new Lightning();
	@Comment("""
			Weather in hot, dry biomes, where vanilla clients never draw rain. Deserts get dry thunderstorms (a dark sky,
			thunder and lightning, no rain) and dust storms (a gloomy sky and the sound of wind). Rain storms
			drifting in from greener land still rain on the edges and stop at the sand.""")
	public DryWeather dryWeather = new DryWeather();
	@Comment("Where on the intensity scale each kind of weather begins.")
	public Thresholds thresholds = new Thresholds();
	@Comment("""
			What unmodified clients are shown. A client can only display one rain strength and one thunder strength, so
			each player is sent the values for the spot they are standing in, eased in gradually.""")
	public Client client = new Client();
	@Comment("Things in the world that react to the local weather instead of a world-wide flag.")
	public Gameplay gameplay = new Gameplay();
	@Comment("Trade simulation detail for server performance.")
	public Performance performance = new Performance();
	@Comment("Diagnostics for server owners and testing.")
	public Debug debug = new Debug();
	@Comment("""
			Optional link with a seasons mod such as Seasonfall. When both are installed the seasons shape the climate
			(temperature, humidity, how much it rains, how often storms form); Stormcell still makes all of the weather.
			Each mod works fine on its own.""")
	public Seasons seasons = new Seasons();

	public static final class General {
		@Comment("Turns the whole mod off. Vanilla weather takes over again while this is false.")
		public boolean enabled = true;
		@Comment("Ticks between simulation steps. Lower is smoother storm movement, higher is cheaper.")
		@Range(min = 20, max = 1200)
		public int simulationIntervalTicks = 100;
		@Comment("Width of one weather cell in chunks (16 chunks = 256 blocks).")
		@Range(min = 4, max = 64)
		public int weatherCellSizeChunks = 16;
		@Comment("""
				Weather samples per cell along each side (4 means a 4x4 grid, one sample every 64 blocks with
				16-chunk cells). More samples give finer storm edges.""")
		@Range(min = 1, max = 8)
		public int cellResolution = 4;
		@Comment("Upper limit on weather cells kept in memory per dimension.")
		@Range(min = 64, max = 65536)
		public int maxActiveWeatherCells = 4096;
		@Comment("""
				Dimensions Stormcell simulates. Any dimension not listed keeps vanilla weather. Dimensions without a sky
				(the Nether, the End) cannot show weather on a vanilla client and are skipped even when listed.""")
		public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
		@Comment("Ticks between saves of the weather state (it is also saved when the server stops).")
		@Range(min = 1200, max = 72000)
		public int autosaveIntervalTicks = 6000;
	}

	public static final class Generation {
		@Comment("Multiplies how often rain showers and rain systems form.")
		@Range(min = 0, max = 10)
		public double rainChanceMultiplier = 1.0;
		@Comment("Multiplies how often convective storms (the kind that can become thunderstorms) form.")
		@Range(min = 0, max = 10)
		public double stormChanceMultiplier = 1.0;
		@Comment("Multiplies the share of convective storms that grow strong enough to produce thunder and lightning.")
		@Range(min = 0, max = 10)
		public double thunderChanceMultiplier = 1.0;
		@Comment("""
				Base chance per cell per minute that new weather forms there, before humidity, pressure, biome and season
				are taken into account. The default gives rain over plains about 15% of the time, as in vanilla, with
				forests and oceans wetter, jungles and swamps wettest, and deserts all but dry.""")
		@Range(min = 0, max = 1)
		public double formationChancePerMinute = 0.002;
		@Comment("Weakest peak strength a new storm system may be given.")
		@Range(min = 0.05, max = 1)
		public double minimumStormIntensity = 0.3;
		@Comment("Strongest peak strength a new storm system may be given.")
		@Range(min = 0.05, max = 1)
		public double maximumStormIntensity = 1.0;
		@Comment("Most storm systems allowed to form per online player in a single simulation step.")
		@Range(min = 1, max = 32)
		public int maximumFormationsPerStep = 2;
	}

	public static final class StormBehavior {
		@Comment("Multiplies how fast storms travel.")
		@Range(min = 0, max = 10)
		public double stormMovementSpeedMultiplier = 1.0;
		@Comment("Intensity a forming storm gains per minute in humid air (less in drier air).")
		@Range(min = 0.005, max = 1)
		public double stormGrowthRate = 0.08;
		@Comment("Intensity a weakening storm loses per minute.")
		@Range(min = 0.005, max = 1)
		public double stormDecayRate = 0.06;
		@Comment("Extra intensity lost per minute while a storm sits over very dry air (deserts, badlands).")
		@Range(min = 0, max = 2)
		public double dryAirDecayRate = 0.2;
		@Comment("""
				How much rain dries out the air below it: the share of its moisture taken per minute of full-strength
				rain (at most 60% in total). Drier air makes the next storm less likely and weaker.""")
		@Range(min = 0, max = 1)
		public double moistureConsumption = 0.02;
		@Comment("Minutes for rained-out air to get most of its moisture back.")
		@Range(min = 1, max = 600)
		public double moistureRecoveryMinutes = 40.0;
		@Comment("Storms that run into each other combine into one larger storm.")
		public boolean allowStormMerging = true;
		@Comment("Large mature storms can occasionally break into two smaller ones that drift apart.")
		public boolean allowStormSplitting = false;
		@Comment("Chance per minute that a large mature storm splits (when splitting is allowed).")
		@Range(min = 0, max = 1)
		public double splitChancePerMinute = 0.02;
		@Comment("Storms start to weaken after this long no matter how good the conditions are.")
		@Range(min = 5, max = 1440)
		public double maximumStormLifetimeMinutes = 90.0;
	}

	public static final class StormTypes {
		@Comment("Small, short-lived patches of rain.")
		public StormType shower = new StormType(1.0, 128, 256, 0.35, 0.65, 3, 8, 1.0, 1.0, 1.0);
		@Comment("Wide, long-lasting areas of steady rain (or snow), often stretched into a band.")
		public StormType rainSystem = new StormType(0.6, 384, 768, 0.5, 0.8, 10, 25, 0.8, 1.0, 1.8);
		@Comment("""
				Compact, violent convective storms. They need warm, humid air, and only the ones that reach the
				thunderstorm threshold produce thunder and lightning.""")
		public StormType thunderstorm = new StormType(1.0, 224, 400, 0.86, 1.0, 5, 12, 1.15, 1.0, 1.0);
		@Comment("""
				Dust storms over dry, sandy biomes (see dryWeather): a gloomy sky and the sound of wind. Stretched along
				the wind, fast moving, and gone quickly once it reaches moist, green land. Intensity is how thick the dust is.""")
		public StormType dustStorm = new StormType(1.0, 256, 512, 0.5, 0.9, 4, 10, 1.4, 1.5, 1.0);
	}

	public static final class StormType {
		@Comment("Relative likelihood of this kind of storm forming.")
		@Range(min = 0, max = 100)
		public double weight;
		@Range(min = 16, max = 4096)
		public int minRadius;
		@Range(min = 16, max = 4096)
		public int maxRadius;
		@Comment("Lowest and highest peak intensity (further limited by generation.minimum/maximumStormIntensity).")
		@Range(min = 0.05, max = 1)
		public double minIntensity;
		@Range(min = 0.05, max = 1)
		public double maxIntensity;
		@Comment("Minutes the storm holds its peak strength.")
		@Range(min = 0, max = 600)
		public double minMatureMinutes;
		@Range(min = 0, max = 600)
		public double maxMatureMinutes;
		@Comment("Multiplies how fast the wind carries this kind of storm.")
		@Range(min = 0, max = 10)
		public double speedFactor;
		@Comment("Stretch along the direction of travel (1 = round).")
		@Range(min = 0.2, max = 5)
		public double length;
		@Comment("Stretch across the direction of travel (1 = round).")
		@Range(min = 0.2, max = 5)
		public double width;

		public StormType() {
		}

		StormType(double weight, int minRadius, int maxRadius, double minIntensity, double maxIntensity, double minMatureMinutes,
				double maxMatureMinutes, double speedFactor, double length, double width) {
			this.weight = weight;
			this.minRadius = minRadius;
			this.maxRadius = maxRadius;
			this.minIntensity = minIntensity;
			this.maxIntensity = maxIntensity;
			this.minMatureMinutes = minMatureMinutes;
			this.maxMatureMinutes = maxMatureMinutes;
			this.speedFactor = speedFactor;
			this.length = length;
			this.width = width;
		}
	}

	public static final class Wind {
		@Comment("Without wind, storms drift slowly in a fixed, gently wandering direction (see calmDriftSpeed).")
		public boolean windEnabled = true;
		@Comment("Slowest prevailing wind, in blocks per second.")
		@Range(min = 0, max = 50)
		public double minimumWindSpeed = 0.3;
		@Comment("Fastest prevailing wind, in blocks per second.")
		@Range(min = 0, max = 50)
		public double maximumWindSpeed = 1.6;
		@Comment("Typical change in the prevailing wind direction per minute, in degrees.")
		@Range(min = 0, max = 180)
		public double windDirectionChangeRate = 4.0;
		@Comment("Typical change in the prevailing wind speed per minute, in blocks per second.")
		@Range(min = 0, max = 10)
		public double windSpeedChangeRate = 0.15;
		@Comment("How much the wind differs from place to place (0 = the same everywhere).")
		@Range(min = 0, max = 1)
		public double localWindVariation = 0.35;
		@Comment("Storms are carried by the wind. When false they drift at calmDriftSpeed instead.")
		public boolean windAffectsStormMovement = true;
		@Comment("Drift speed in blocks per second used when the wind does not move storms.")
		@Range(min = 0, max = 50)
		public double calmDriftSpeed = 0.6;
	}

	public static final class Atmosphere {
		@Comment("Track temperature. Warm air breeds thunderstorms, cold air turns them into snowstorms.")
		public boolean temperatureEnabled = true;
		@Comment("Track humidity. Humid air forms and feeds storms, dry air starves them.")
		public boolean humidityEnabled = true;
		@Comment("Track pressure. Slow-moving high and low pressure regions bring settled and unsettled spells.")
		public boolean pressureEnabled = true;
		@Comment("How strongly temperature affects storm formation and type.")
		@Range(min = 0, max = 5)
		public double temperatureInfluence = 1.0;
		@Comment("How strongly humidity affects storm formation and strength.")
		@Range(min = 0, max = 5)
		public double humidityInfluence = 1.0;
		@Comment("How strongly pressure affects storm formation.")
		@Range(min = 0, max = 5)
		public double pressureInfluence = 1.0;
		@Comment("Typical width of a high or low pressure region, in blocks.")
		@Range(min = 500, max = 100000)
		public double pressureSystemSizeBlocks = 6000.0;
		@Comment("Minutes for the pressure pattern to change completely (besides drifting with the wind).")
		@Range(min = 5, max = 10000)
		public double pressureSystemLifetimeMinutes = 150.0;
		@Comment("How far regional moisture swings above and below each biome's normal humidity.")
		@Range(min = 0, max = 1)
		public double moistureVariation = 0.2;
		@Comment("Temperature swing between night and mid-afternoon (vanilla temperature scale).")
		@Range(min = 0, max = 1)
		public double dayNightTemperatureSwing = 0.1;
		@Comment("Biome temperature from which convective storms become likely (plains are 0.8, deserts 2.0).")
		@Range(min = -1, max = 3)
		public double convectionTemperature = 0.5;
	}

	public static final class Biomes {
		@Comment("Biome climate shapes the weather. When false every biome behaves like a temperate plain.")
		public boolean biomeWeatherInfluence = true;
		@Comment("Use each biome's temperature.")
		public boolean biomeTemperatureInfluence = true;
		@Comment("Use each biome's rainfall (its 'downfall' value) as its humidity.")
		public boolean biomeHumidityInfluence = true;
		@Comment("""
				Humidity given to biomes that never get rain or snow in vanilla (deserts, savannas, badlands), before the
				biome overrides below. Their own rainfall value is 0, which would rule out weather there entirely.""")
		@Range(min = 0, max = 1)
		public double dryBiomeHumidity = 0.25;
		@Comment("Extra humidity over oceans and rivers, which feed passing storms.")
		@Range(min = 0, max = 1)
		public double waterHumidityBonus = 0.15;
		@Comment("""
				Below this humidity hardly any rain reaches the ground (it evaporates on the way down), so a storm
				passing over a desert stays mostly dry.""")
		@Range(min = 0, max = 1)
		public double dryGroundHumidity = 0.12;
	}

	public static final class BiomeOverride {
		@Range(min = 0, max = 20)
		public double rainMultiplier = 1.0;
		@Range(min = 0, max = 20)
		public double stormMultiplier = 1.0;
		@Range(min = 0, max = 20)
		public double humidityMultiplier = 1.0;
		@Range(min = -3, max = 3)
		public double temperatureOffset = 0.0;

		public BiomeOverride() {
		}

		BiomeOverride(double rainMultiplier, double stormMultiplier, double humidityMultiplier) {
			this.rainMultiplier = rainMultiplier;
			this.stormMultiplier = stormMultiplier;
			this.humidityMultiplier = humidityMultiplier;
		}
	}

	public static final class Elevation {
		@Comment("Higher ground is colder in the simulation, so mountain storms bring snow and fewer thunderstorms.")
		public boolean elevationTemperatureEnabled = true;
		@Comment("""
				Extra cooling per block above sea level, on top of vanilla's own, used when deciding between rain and
				snow on the server (snow piling up, cauldrons, mobs getting wet). Vanilla clients cannot be told about
				this: values above 0 make snow settle at heights where clients still draw rain. Leave at 0 to keep the
				two in step.""")
		@Range(min = 0, max = 0.05)
		public double extraTemperatureLossPerBlock = 0.0;
		@Comment("Temperature below which the simulation treats a storm as a snowstorm (vanilla uses 0.15).")
		@Range(min = -2, max = 2)
		public double snowTemperatureThreshold = 0.15;
		@Comment("Look up the terrain height of each cell (from the world generator, without loading chunks).")
		public boolean terrainSampling = true;
	}

	public static final class Lightning {
		@Comment("""
				Thunderstorms produce lightning. When false there is no natural lightning at all in the dimensions
				Stormcell runs (vanilla's own lightning needs its world-wide thunderstorms, which Stormcell replaces).
				As in vanilla, a strike is drawn to a lightning rod (or an exposed mob) up to 128 blocks from where it
				would have landed, so a rod near the edge of a thunderstorm can be struck.""")
		public boolean localizedLightning = true;
		@Comment("Multiplies how often lightning strikes.")
		@Range(min = 0, max = 100)
		public double lightningFrequencyMultiplier = 1.0;
		@Comment("Storm intensity needed for any lightning.")
		@Range(min = 0, max = 1)
		public double minimumLightningStormIntensity = 0.85;
		@Comment("""
				Lightning rate at the minimum intensity, as a multiple of a vanilla thunderstorm's rate
				(roughly one strike per chunk every 83 minutes).""")
		@Range(min = 0, max = 100)
		public double lightningRateAtMinimum = 0.5;
		@Comment("Lightning rate at intensity 1.0, as a multiple of a vanilla thunderstorm's rate.")
		@Range(min = 0, max = 100)
		public double lightningRateAtMaximum = 3.0;
		@Comment("Allow vanilla skeleton horse traps from lightning.")
		public boolean skeletonTraps = true;
	}

	public static final class DryWeather {
		@Comment("Heat can build thunderstorms over dry biomes: dark sky, thunder and lightning, but no rain.")
		public boolean dryThunderstorms = true;
		@Comment("Multiplies how often dry thunderstorms form.")
		@Range(min = 0, max = 10)
		public double dryThunderstormChanceMultiplier = 1.0;
		@Comment("Biome temperature from which dry thunderstorms can form (deserts, savannas and badlands are 2.0).")
		@Range(min = 0, max = 3)
		public double hotTemperature = 1.2;
		@Comment("""
				Lightning from a storm with no rain where it lands can start fires and hurt mobs, like normal lightning.
				Off by default: dry lightning then only flashes and thunders, so savannas do not burn down.""")
		public boolean dryLightningStartsFires = false;
		@Comment("Dust storms form over the biomes listed in dustStormBiomes.")
		public boolean dustStorms = true;
		@Comment("Multiplies how often dust storms form (strong wind makes them more likely).")
		@Range(min = 0, max = 10)
		public double dustStormChanceMultiplier = 1.0;
		@Comment("Biome ids or tags (starting with #) where dust storms form.")
		public List<String> dustStormBiomes = new ArrayList<>(List.of("minecraft:desert", "#minecraft:is_badlands"));
		@Comment("Players in a dust storm hear the wind (the elytra flying sound, played lower and slower).")
		public boolean dustSound = true;
		@Range(min = 0, max = 2)
		public double dustSoundVolume = 0.6;
		@Comment("""
				How dark the sky gets (as a vanilla rain strength, 0 to 1) under a full-strength storm in a biome where
				clients draw no rain. Nothing falls; only the sky and light change.""")
		@Range(min = 0, max = 1)
		public double drySkyDarkness = 0.75;
		@Comment("How dark the sky gets under the thickest dust (as a vanilla rain strength, 0 to 1).")
		@Range(min = 0, max = 1)
		public double dustSkyDarkness = 0.55;
		@Comment("Dust thinner than this is not shown at all.")
		@Range(min = 0, max = 1)
		public double dustThreshold = 0.2;
	}

	public static final class Thresholds {
		@Range(min = 0, max = 1)
		public double cloudy = 0.2;
		@Comment("Precipitation reaches the ground from here up (the server treats the spot as raining or snowing).")
		@Range(min = 0, max = 1)
		public double drizzle = 0.3;
		@Range(min = 0, max = 1)
		public double lightRain = 0.5;
		@Range(min = 0, max = 1)
		public double heavyRain = 0.7;
		@Range(min = 0, max = 1)
		public double thunderstorm = 0.85;
	}

	public static final class Client {
		@Comment("""
				Rain strength (0 to 1) shown at each step of the intensity scale; values in between are blended. Below
				the drizzle threshold nothing is shown, matching where the server stops treating a spot as rainy.""")
		@Range(min = 0, max = 1)
		public double rainLevelAtDrizzle = 0.2;
		@Range(min = 0, max = 1)
		public double rainLevelAtLightRain = 0.45;
		@Range(min = 0, max = 1)
		public double rainLevelAtHeavyRain = 0.8;
		@Range(min = 0, max = 1)
		public double rainLevelAtThunderstorm = 1.0;
		@Comment("""
				Thunder strength (darker sky) shown at the thunderstorm threshold; it climbs to 1.0 at intensity 1.0
				and fades in from the heavy rain threshold.""")
		@Range(min = 0, max = 1)
		public double thunderLevelAtThunderstorm = 0.6;
		@Comment("Largest change in a player's rain or thunder strength per tick (0.005 = 10 seconds from clear to full).")
		@Range(min = 0.0005, max = 1)
		public double transitionPerTick = 0.005;
		@Comment("Ticks between working out what each player should see. The display still eases every tick.")
		@Range(min = 1, max = 100)
		public int targetRefreshTicks = 10;
	}

	public static final class Gameplay {
		@Comment("""
				Bees head home, foxes seek shelter, pandas get scared, monsters spawn in the gloom and players may sleep
				during the day based on the weather where they are, not on a world-wide flag.""")
		public boolean regionalMobBehaviour = true;
		@Comment("Heavier precipitation piles up snow and fills cauldrons faster than a drizzle does.")
		public boolean precipitationScalesBlockEffects = true;
		@Comment("""
				Make /weather work with regional weather. 'clear' removes every storm and keeps new ones away for the
				given duration (10 minutes if none). 'rain' and 'thunder' start a storm over whoever runs the command: it
				stays put at full strength for the duration (vanilla's usual length if none), then drifts off and fades
				over a few minutes. At the storm limit the weakest storm makes way.""")
		public boolean vanillaWeatherCommand = true;
	}

	public static final class Performance {
		@Comment("Weather is simulated (and new storms form) within this many chunks of a player.")
		@Range(min = 16, max = 1024)
		public int activeRadiusChunks = 128;
		@Comment("""
				Cells outside every player's view (and not asked about by chunk loaders since the last step) are
				refreshed only every this many steps. 1 refreshes everything every step.""")
		@Range(min = 1, max = 64)
		public int inactiveCellUpdateRate = 4;
		@Comment("""
				Most storm systems per online player in a dimension. Scaling with players keeps each player's weather the
				same however many others are online and wherever they are.""")
		@Range(min = 1, max = 256)
		public int stormSystemsPerPlayer = 32;
		@Comment("Hard limit on storm systems in one dimension, whatever the number of players (commands included).")
		@Range(min = 1, max = 4096)
		public int maximumStormSystems = 512;
		@Comment("Storms further than this many chunks from every player are dropped.")
		@Range(min = 32, max = 4096)
		public int despawnDistanceChunks = 224;
		@Comment("""
				Most weather cells created per tick, for all players and chunk loaders together. Creating a cell looks up
				its biomes and terrain height, the most expensive thing Stormcell does; spots whose cell is not ready yet
				are answered straight from the storms overhead.""")
		@Range(min = 1, max = 256)
		public int maxNewCellsPerTick = 4;
		@Comment("Seconds a cell kept alive only by chunk loaders (no player near) survives without being asked about.")
		@Range(min = 5, max = 3600)
		public int passiveCellTimeoutSeconds = 60;
	}

	public static final class Debug {
		@Comment("""
				Write every weather packet sent to a player to the server log, with the rain and thunder levels the
				player's client is showing as a result (worked out from the packets themselves). Busy while weather is
				changing; leave off on a live server unless you are looking into a problem. /stormcell client <player>
				shows the same information at any time.""")
		public boolean logClientWeather = false;
	}

	public static final class Seasons {
		@Comment("Let season mods (Seasonfall, or any mod using the Stormcell API) shape the climate.")
		public boolean integrationEnabled = true;
		@Comment("""
				Read the seasonal climate from Seasonfall when it is installed. Seasonfall decides its own seasonal
				values (see its config); this only switches the link on or off.""")
		public boolean seasonfallBridge = true;
		@Comment("""
				Scales how strongly the seasons move the climate away from normal: 0 ignores them, 1 uses them as they
				are, 2 doubles their effect.""")
		@Range(min = 0, max = 3)
		public double seasonalStrength = 1.0;
	}

	private static Map<String, BiomeOverride> defaultBiomeOverrides() {
		Map<String, BiomeOverride> overrides = new LinkedHashMap<>();
		overrides.put("minecraft:desert", new BiomeOverride(0.05, 0.1, 0.2));
		overrides.put("#minecraft:is_badlands", new BiomeOverride(0.15, 0.4, 0.4));
		overrides.put("#minecraft:is_savanna", new BiomeOverride(0.5, 1.6, 0.8));
		overrides.put("#minecraft:is_jungle", new BiomeOverride(1.5, 1.5, 1.1));
		overrides.put("minecraft:swamp", new BiomeOverride(1.3, 1.1, 1.1));
		overrides.put("minecraft:mangrove_swamp", new BiomeOverride(1.4, 1.3, 1.1));
		return overrides;
	}

	// ---- Loading

	public static StormcellConfig get() {
		return instance;
	}

	public static void load(Path configDir) {
		file = configDir.resolve(FILE_NAME);
		reload();
	}

	/**
	 * The outcome of reading config text.
	 *
	 * @param config   the settings to use, or null if the text could not be used at all
	 * @param problems everything worth telling the server owner
	 */
	public record Parsed(StormcellConfig config, List<String> problems) {
	}

	/** Reads config text into a complete, checked config. Never throws; never touches the live settings. */
	public static Parsed parse(String text) {
		List<String> problems = new ArrayList<>();
		StormcellConfig config = new StormcellConfig();
		try {
			problems.addAll(ConfigBinder.read(Toml.parse(text), config));
		} catch (Toml.ParseException e) {
			problems.add("Could not read " + FILE_NAME + " (" + e.getMessage() + ")");
			return new Parsed(null, problems);
		} catch (RuntimeException e) {
			Stormcell.LOGGER.error("Unexpected error reading {}", FILE_NAME, e);
			problems.add("Could not read " + FILE_NAME + " (" + e + ")");
			return new Parsed(null, problems);
		}
		config.validate(problems);
		return new Parsed(config, problems);
	}

	/**
	 * Reads the file again and returns the problems found, so a command can show them. A file that cannot be used
	 * leaves the current settings in place (the defaults, on first start) and is not touched. Otherwise the settings
	 * are switched over in one go and the file is rewritten in the standard layout, so new options appear in it; if
	 * anything in it had to be ignored or corrected, the original is first kept as stormcell.toml.bak.
	 */
	public static List<String> reload() {
		if (file == null || !Files.isRegularFile(file)) {
			StormcellConfig defaults = new StormcellConfig();
			instance = defaults;
			if (file != null) {
				save(defaults);
			}
			return List.of();
		}
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			String problem = "Could not read " + FILE_NAME + " (" + e.getMessage() + "); keeping the current settings";
			Stormcell.LOGGER.warn("[config] {}", problem);
			return List.of(problem);
		}
		Parsed parsed = parse(text);
		List<String> problems = new ArrayList<>(parsed.problems());
		if (parsed.config() == null) {
			problems.add("The file was left as it is; keeping the current settings until it is fixed");
		}
		for (String problem : problems) {
			Stormcell.LOGGER.warn("[config] {}", problem);
		}
		if (parsed.config() != null) {
			instance = parsed.config();
			if (!problems.isEmpty()) {
				backup(text);
			}
			save(parsed.config());
		}
		return problems;
	}

	private static void backup(String text) {
		try {
			SafeFiles.replace(file.resolveSibling(FILE_NAME + ".bak"), text);
		} catch (IOException e) {
			Stormcell.LOGGER.error("Could not keep a backup of {}", file, e);
		}
	}

	private static void save(StormcellConfig config) {
		try {
			SafeFiles.replace(file, ConfigBinder.write(config, HEADER));
		} catch (IOException e) {
			Stormcell.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Fixes up options that only make sense together. */
	private void validate(List<String> problems) {
		if (this.generation.minimumStormIntensity > this.generation.maximumStormIntensity) {
			problems.add("generation.minimumStormIntensity is above maximumStormIntensity; swapping them");
			double low = this.generation.maximumStormIntensity;
			this.generation.maximumStormIntensity = this.generation.minimumStormIntensity;
			this.generation.minimumStormIntensity = low;
		}
		if (this.wind.minimumWindSpeed > this.wind.maximumWindSpeed) {
			problems.add("wind.minimumWindSpeed is above maximumWindSpeed; swapping them");
			double low = this.wind.maximumWindSpeed;
			this.wind.maximumWindSpeed = this.wind.minimumWindSpeed;
			this.wind.minimumWindSpeed = low;
		}
		for (StormType type : List.of(this.stormTypes.shower, this.stormTypes.rainSystem, this.stormTypes.thunderstorm, this.stormTypes.dustStorm)) {
			if (type.minRadius > type.maxRadius) {
				int low = type.maxRadius;
				type.maxRadius = type.minRadius;
				type.minRadius = low;
			}
			if (type.minIntensity > type.maxIntensity) {
				double low = type.maxIntensity;
				type.maxIntensity = type.minIntensity;
				type.minIntensity = low;
			}
			if (type.minMatureMinutes > type.maxMatureMinutes) {
				double low = type.maxMatureMinutes;
				type.maxMatureMinutes = type.minMatureMinutes;
				type.minMatureMinutes = low;
			}
		}
		Thresholds t = this.thresholds;
		if (!(t.cloudy <= t.drizzle && t.drizzle <= t.lightRain && t.lightRain <= t.heavyRain && t.heavyRain <= t.thunderstorm)) {
			problems.add("[thresholds] must increase from cloudy to thunderstorm; using the defaults");
			this.thresholds = new Thresholds();
		}
		if (this.biomeOverrides == null) {
			this.biomeOverrides = new LinkedHashMap<>();
		}
		if (this.general.dimensions == null) {
			this.general.dimensions = new ArrayList<>();
		}
		if (this.dryWeather.dustStormBiomes == null) {
			this.dryWeather.dustStormBiomes = new ArrayList<>();
		}
	}

	public int cellSizeBlocks() {
		return this.general.weatherCellSizeChunks * 16;
	}
}
