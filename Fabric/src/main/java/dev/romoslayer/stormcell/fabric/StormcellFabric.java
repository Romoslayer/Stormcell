package dev.romoslayer.stormcell.fabric;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.platform.Platform;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entry point: installs the platform, then wires the shared logic to Fabric's events. */
public final class StormcellFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Stormcell.init(new FabricPlatform());
		ServerLifecycleEvents.SERVER_STARTED.register(Stormcell::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(Stormcell::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(Stormcell::onServerTickEnd);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> Stormcell.registerCommands(dispatcher));
	}

	private static final class FabricPlatform implements Platform {
		@Override
		public Path configDir() {
			return FabricLoader.getInstance().getConfigDir();
		}

		@Override
		public boolean isModLoaded(String modId) {
			return FabricLoader.getInstance().isModLoaded(modId);
		}
	}
}
