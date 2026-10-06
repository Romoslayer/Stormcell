package dev.romoslayer.stormcell.forge;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.platform.Platform;
import dev.romoslayer.stormcell.world.BedWeather;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point: installs the platform, then wires the shared logic to Forge's events. */
@Mod(Stormcell.MOD_ID)
public final class StormcellForge {
	public StormcellForge(FMLJavaModLoadingContext context) {
		Stormcell.init(new ForgePlatform());
		// Forge 26.x events carry their own bus; listeners are added rather than annotated
		ServerStartedEvent.BUS.addListener(event -> Stormcell.onServerStarted(event.getServer()));
		ServerStoppingEvent.BUS.addListener(event -> Stormcell.onServerStopping(event.getServer()));
		TickEvent.ServerTickEvent.Post.BUS.addListener(event -> Stormcell.onServerTickEnd(event.server()));
		RegisterCommandsEvent.BUS.addListener(event -> Stormcell.registerCommands(event.getDispatcher()));
		// Forge asks this event, rather than the bed rule, whether it is time to sleep, both when a player goes to bed and
		// every tick while they sleep, so it covers what the Fabric and NeoForge builds do with mixins. Vanilla's monster
		// check still runs afterwards.
		SleepingTimeCheckEvent.BUS.addListener(StormcellForge::onSleepingTimeCheck);
	}

	private static void onSleepingTimeCheck(SleepingTimeCheckEvent event) {
		if (event.getEntity().level() instanceof ServerLevel level) {
			BlockPos pos = event.getSleepingLocation().orElseGet(() -> event.getEntity().blockPosition());
			BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
			if (BedWeather.stormLetsSleep(rule, level, pos)) {
				event.setResult(Result.ALLOW);
			}
		}
	}

	private static final class ForgePlatform implements Platform {
		@Override
		public Path configDir() {
			return FMLPaths.CONFIGDIR.get();
		}

		@Override
		public boolean isModLoaded(String modId) {
			return ModList.isLoaded(modId);
		}
	}
}
