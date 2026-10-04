package dev.romoslayer.stormcell.neoforge;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.platform.Platform;
import dev.romoslayer.stormcell.world.BedWeather;
import java.nio.file.Path;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entry point: installs the platform, then wires the shared logic to NeoForge's events. */
@Mod(Stormcell.MOD_ID)
public final class StormcellNeoForge {
	public StormcellNeoForge(IEventBus modBus) {
		Stormcell.init(new NeoForgePlatform());
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> Stormcell.onServerStarted(event.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> Stormcell.onServerStopping(event.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> Stormcell.onServerTickEnd(event.getServer()));
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> Stormcell.registerCommands(event.getDispatcher()));
		// NeoForge reports sleep problems through this event (Fabric uses a mixin on ServerPlayer instead)
		NeoForge.EVENT_BUS.addListener((CanPlayerSleepEvent event) -> {
			if (StormcellConfig.get().gameplay.regionalMobBehaviour) {
				event.setProblem(BedWeather.stormSleepProblem(event.getEntity(), event.getPos(), event.getProblem()));
			}
		});
	}

	private static final class NeoForgePlatform implements Platform {
		@Override
		public Path configDir() {
			return FMLPaths.CONFIGDIR.get();
		}

		@Override
		public boolean isModLoaded(String modId) {
			return ModList.get().isLoaded(modId);
		}
	}
}
