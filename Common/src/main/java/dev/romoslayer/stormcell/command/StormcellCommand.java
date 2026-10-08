package dev.romoslayer.stormcell.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.api.LocalWeather;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.sim.StormSystem;
import dev.romoslayer.stormcell.sim.WeatherManager;
import dev.romoslayer.stormcell.sync.ClientWeatherView;
import dev.romoslayer.stormcell.sync.PlayerWeatherSync;
import dev.romoslayer.stormcell.world.RegionalWeather;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * /stormcell, for server operators: see the simulated conditions where you stand (or what a player's client is
 * showing), start or clear storms, and reload
 * the config. There is deliberately no forecast.
 */
public final class StormcellCommand {
	private static final SimpleCommandExceptionType NOT_MANAGED = new SimpleCommandExceptionType(
			Component.literal("Stormcell is not running the weather in this dimension"));
	private static final DynamicCommandExceptionType UNKNOWN_KIND = new DynamicCommandExceptionType(
			kind -> Component.literal("Unknown storm kind '" + kind + "' (use shower, rain, thunderstorm or dust)"));
	private static final DynamicCommandExceptionType STORM_LIMIT = new DynamicCommandExceptionType(
			limit -> Component.literal("The storm limit (" + limit + ") is reached; clear some storms or raise performance.maximumStormSystems"));
	private static final List<String> KINDS = List.of("shower", "rain", "thunderstorm", "dust");

	private StormcellCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(Stormcell.MOD_ID)
				.requires(Versioned.operators())
				.then(Commands.literal("info").executes(StormcellCommand::info))
				.then(Commands.literal("spawn")
						.then(Commands.argument("kind", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(KINDS, builder))
								.executes(context -> spawn(context, Double.NaN))
								.then(Commands.argument("intensity", DoubleArgumentType.doubleArg(0.05, 1.0))
										.executes(context -> spawn(context, DoubleArgumentType.getDouble(context, "intensity"))))))
				.then(Commands.literal("clear")
						.executes(context -> clear(context, -1.0))
						.then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0))
								.executes(context -> clear(context, DoubleArgumentType.getDouble(context, "radius")))))
				.then(Commands.literal("client")
						.then(Commands.argument("player", EntityArgument.player()).executes(StormcellCommand::client)))
				.then(Commands.literal("stats").executes(StormcellCommand::stats))
				.then(Commands.literal("reload").executes(StormcellCommand::reload));
		if (DevCommand.ENABLED) {
			root.then(DevCommand.node());
		}
		dispatcher.register(root);
	}

	private static LevelWeather weather(CommandSourceStack source) throws CommandSyntaxException {
		LevelWeather weather = RegionalWeather.weather(source.getLevel());
		if (weather == null) {
			throw NOT_MANAGED.create();
		}
		return weather;
	}

	private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		LevelWeather weather = weather(source);
		ServerLevel level = source.getLevel();
		Vec3 pos = source.getPosition();
		int x = (int) Math.floor(pos.x);
		int z = (int) Math.floor(pos.z);
		LocalWeather local = weather.localWeather(pos.x, pos.z);
		StormcellConfig config = StormcellConfig.get();
		source.sendSuccess(() -> Component.literal("Stormcell weather at " + x + ", " + z + " in " + Versioned.id(level.dimension()))
				.withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal(condition(config, local.precipitationIntensity(), local.stormIntensity(), local.dustIntensity()))
				.withStyle(ChatFormatting.WHITE)
				.append(Component.literal(String.format(Locale.ROOT, "  (storm %.2f, precipitation %.2f, dust %.2f)", local.stormIntensity(),
						local.precipitationIntensity(), local.dustIntensity())).withStyle(ChatFormatting.GRAY)), false);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Temperature %.2f, humidity %.2f, pressure %.0f hPa",
				local.temperature(), local.humidity(), local.pressure())).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Wind %.1f blocks/s towards %s (prevailing %.1f towards %s)",
				local.windSpeed(), compass(local.windDirectionDegrees()), weather.windSpeed(), compass(weather.windAngleDegrees())))
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(weather.storms().size() + " storm systems and " + weather.cellCount()
				+ " weather cells active in this dimension").withStyle(ChatFormatting.DARK_GRAY), false);
		return 1;
	}

	private static int spawn(CommandContext<CommandSourceStack> context, double intensity) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		LevelWeather weather = weather(source);
		String name = StringArgumentType.getString(context, "kind").toLowerCase(Locale.ROOT);
		StormSystem.Kind kind = switch (name) {
			case "shower" -> StormSystem.Kind.SHOWER;
			case "rain" -> StormSystem.Kind.RAIN_SYSTEM;
			case "thunderstorm" -> StormSystem.Kind.THUNDERSTORM;
			case "dust" -> StormSystem.Kind.DUST_STORM;
			default -> throw UNKNOWN_KIND.create(name);
		};
		Vec3 pos = source.getPosition();
		StormSystem storm = weather.spawnStorm(kind, pos.x, pos.z, intensity, -1L, LevelWeather.Overflow.REJECT);
		if (storm == null) {
			throw STORM_LIMIT.create(StormcellConfig.get().performance.maximumStormSystems);
		}
		source.sendSuccess(() -> Component.literal("Started " + storm.describe()), true);
		return storm.id;
	}

	private static int clear(CommandContext<CommandSourceStack> context, double radius) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		LevelWeather weather = weather(source);
		Vec3 pos = source.getPosition();
		int removed = weather.clearStorms(pos.x, pos.z, radius);
		source.sendSuccess(() -> Component.literal("Cleared " + removed + (removed == 1 ? " storm system" : " storm systems")
				+ (radius < 0.0 ? " in this dimension" : " within " + (int) radius + " blocks")), true);
		return removed;
	}

	/**
	 * What a player's client is showing (worked out from the weather packets actually sent to it) next to the weather
	 * the server simulates where the player stands. The two should agree: rain on the client exactly where the server
	 * says it is raining.
	 */
	private static int client(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = EntityArgument.getPlayer(context, "player");
		ClientWeatherView.View view = ClientWeatherView.of(player);
		String name = player.getScoreboardName();
		if (view == null) {
			source.sendSuccess(() -> Component.literal(name + ": no weather packets sent yet"), false);
			return 0;
		}
		long ago = Math.max(0L, System.currentTimeMillis() - view.lastChangeMillis());
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"%s's client: rain %.3f, thunder %.3f (drawn %.3f) in %s; %d weather packets, last %s %.1f s ago", name, view.rain(), view.thunder(),
				view.shownThunder(), view.dimension() == null ? "?" : Versioned.id(view.dimension()), view.packets(), view.lastEvent(), ago / 1000.0)), false);
		LevelWeather weather = RegionalWeather.weather(player.level());
		if (weather != null) {
			StormcellConfig config = StormcellConfig.get();
			float precipitation = weather.precipitationAt(player.getX(), player.getZ());
			float storm = weather.stormAt(player.getX(), player.getZ());
			PlayerWeatherSync.Targets targets = PlayerWeatherSync.targets(config, weather, Versioned.level(player), player.getX(), player.getY(), player.getZ());
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
					"Simulated at %d, %d: %s (storm %.3f, precipitation %.3f, dust %.3f); target rain %.3f, thunder %.3f", player.getBlockX(),
					player.getBlockZ(), condition(config, precipitation, storm, targets.dust()), storm, precipitation, targets.dust(), targets.rain(), targets.thunder()))
					.withStyle(ChatFormatting.GRAY), false);
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Dust storm wind sounds sent: %d, stopped: %d", view.windSounds(), view.windStops())).withStyle(ChatFormatting.GRAY), false);
		} else {
			source.sendSuccess(() -> Component.literal("Not a Stormcell dimension: vanilla weather").withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	private static int stats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		LevelWeather weather = weather(source);
		LevelWeather.Stats stats = weather.stats();
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Steps: %d, last %.2f ms, average %.2f ms, slowest %.2f ms", stats.steps, stats.lastStepNanos / 1.0E6, stats.averageStepNanos / 1.0E6,
				stats.maxStepNanos / 1.0E6)).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Cells: %d (remembered dry regions %d); created %d, terrain samples %d, evicted %d, answered from storms while not ready %d",
				weather.cellCount(), weather.dormantCount(), stats.creations, stats.terrainSamples, stats.evictions, stats.fallbacks))
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal(weather.storms().size() + " storm systems").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<String> problems = StormcellConfig.reload();
		WeatherManager manager = Stormcell.manager();
		if (manager != null) {
			manager.configReloaded();
		}
		if (problems.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Reloaded the Stormcell config"), true);
		} else {
			source.sendSuccess(() -> Component.literal("Reloaded the Stormcell config with " + problems.size() + " problem(s):")
					.withStyle(ChatFormatting.YELLOW), true);
			for (String problem : problems) {
				source.sendSuccess(() -> Component.literal(" - " + problem).withStyle(ChatFormatting.YELLOW), false);
			}
		}
		return problems.isEmpty() ? 1 : 0;
	}

	/** A plain-language name for the weather at a given intensity. */
	public static String condition(StormcellConfig config, float precipitation, float storm, float dust) {
		String weather = condition(config, precipitation, storm);
		if (dust < config.dryWeather.dustThreshold) {
			return weather;
		}
		return weather.equals("Clear") || weather.equals("Cloudy") ? "Dust storm" : weather + " with blowing dust";
	}

	public static String condition(StormcellConfig config, float precipitation, float storm) {
		StormcellConfig.Thresholds t = config.thresholds;
		if (storm >= t.thunderstorm) {
			String name = storm >= 0.97F ? "Severe thunderstorm" : "Thunderstorm";
			// Thunder with no rain reaching the ground: a dry thunderstorm
			return precipitation < t.drizzle ? "Dry " + name.toLowerCase(Locale.ROOT) : name;
		}
		if (precipitation >= t.heavyRain) {
			return "Heavy rain";
		}
		if (precipitation >= t.lightRain) {
			return "Rain";
		}
		if (precipitation >= t.drizzle) {
			return "Light rain";
		}
		if (storm >= t.drizzle) {
			return "Overcast, dry";
		}
		if (storm >= t.cloudy || precipitation >= t.cloudy) {
			return "Cloudy";
		}
		return "Clear";
	}

	private static String compass(double yawDegrees) {
		// Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		int index = (int) Math.floorMod(Math.round(yawDegrees / 45.0), 8L);
		return names[index];
	}
}
