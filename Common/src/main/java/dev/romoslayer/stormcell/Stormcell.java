package dev.romoslayer.stormcell;

import com.mojang.brigadier.CommandDispatcher;
import dev.romoslayer.stormcell.command.StormcellCommand;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.platform.Platform;
import dev.romoslayer.stormcell.sim.WeatherManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Everything the loader entrypoints call into. Each loader turns its own events into these calls, so the weather
 * logic itself is written once.
 */
public final class Stormcell {
	public static final String MOD_ID = "stormcell";
	public static final Logger LOGGER = LoggerFactory.getLogger("Stormcell");

	private static @Nullable Platform platform;
	private static @Nullable WeatherManager manager;

	private Stormcell() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static void init(Platform loaderPlatform) {
		platform = loaderPlatform;
		StormcellConfig.load(loaderPlatform.configDir());
	}

	public static Platform platform() {
		if (platform == null) {
			throw new IllegalStateException("Stormcell was used before its loader entrypoint ran");
		}
		return platform;
	}

	/** The running server's weather, or null while no server is running. */
	public static @Nullable WeatherManager manager() {
		return manager;
	}

	public static void onServerStarted(MinecraftServer server) {
		manager = new WeatherManager(server);
		manager.start();
		if (Boolean.getBoolean("stormcell.mixinAudit")) {
			// Development aid: apply every mixin now, so a broken one fails at startup instead of when its class loads
			MixinEnvironment.getCurrentEnvironment().audit();
			LOGGER.info("Mixin audit finished");
		}
	}

	public static void onServerStopping(MinecraftServer server) {
		if (manager != null) {
			manager.stop();
			manager = null;
		}
	}

	public static void onServerTickEnd(MinecraftServer server) {
		if (manager != null) {
			manager.tick();
		}
	}

	/** Vanilla just sent a player the basics of a level (on joining, respawning or changing dimension). */
	public static void onLevelInfo(ServerPlayer player, ServerLevel level) {
		if (manager != null) {
			manager.onLevelInfo(player, level);
		}
	}

	/** The players of a level slept through the night (before they are woken). */
	public static void onNightSkipped(ServerLevel level) {
		if (manager != null) {
			manager.onNightSkipped(level);
		}
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		StormcellCommand.register(dispatcher);
	}
}
