package dev.romoslayer.stormcell.forge;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.platform.Platform;
import dev.romoslayer.stormcell.world.BedWeather;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point (Forge 52, Minecraft 1.21.x): installs the platform, then wires the shared logic to Forge's events. */
@Mod(Stormcell.MOD_ID)
public final class StormcellForge {
	public StormcellForge(FMLJavaModLoadingContext context) {
		Stormcell.init(new ForgePlatform());
		MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> Stormcell.onServerStarted(event.getServer()));
		MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> Stormcell.onServerStopping(event.getServer()));
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				Stormcell.onServerTickEnd(event.getServer());
			}
		});
		MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> Stormcell.registerCommands(event.getDispatcher()));
		// Forge asks this event, rather than checking for daylight itself, both when a player goes to bed and every tick
		// while they sleep, so it covers what the Fabric build does with mixins. Vanilla's monster check still follows.
		MinecraftForge.EVENT_BUS.addListener(StormcellForge::onSleepingTimeCheck);
	}

	private static void onSleepingTimeCheck(SleepingTimeCheckEvent event) {
		BlockPos pos = event.getSleepingLocation().orElseGet(() -> event.getEntity().blockPosition());
		if (BedWeather.stormLetsSleep(event.getEntity().level(), pos)) {
			event.setResult(Event.Result.ALLOW);
		}
	}

	private static final class ForgePlatform implements Platform {
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
