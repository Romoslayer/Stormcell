package dev.romoslayer.stormcell.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.mc.Versioned;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ClientWeatherViewTest {
	static {
		// Dimension keys need the built-in registries before 26.x
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final ResourceKey<Level> OVERWORLD = Versioned.dimension("minecraft:overworld");
	private static final ResourceKey<Level> NETHER = Versioned.dimension("minecraft:the_nether");

	@Test
	void appliesWeatherPacketsLikeTheVanillaClient() {
		ClientWeatherView.View view = ClientWeatherView.detached();
		view.newWorld(OVERWORLD, "join");
		assertEquals(0.0F, view.rain());

		assertTrue(view.apply(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.6F));
		assertTrue(view.apply(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.5F));
		assertEquals(0.6F, view.rain(), 1.0E-6F);
		assertEquals(0.3F, view.shownThunder(), 1.0E-6F, "the client draws thunder times rain");

		// Vanilla's own pair: START sets 0, STOP sets 1 (each followed by a level change in practice)
		view.apply(ClientboundGameEventPacket.START_RAINING, 0.0F);
		assertEquals(0.0F, view.rain());
		view.apply(ClientboundGameEventPacket.STOP_RAINING, 0.0F);
		assertEquals(1.0F, view.rain());

		view.apply(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 5.0F);
		assertEquals(1.0F, view.rain(), "levels are clamped like the client clamps them");
		assertFalse(view.apply(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F), "other game events are ignored");
		assertEquals(5, view.packets());
	}

	@Test
	void aNewClientWorldHasNoWeather() {
		ClientWeatherView.View view = ClientWeatherView.detached();
		view.newWorld(OVERWORLD, "join");
		view.apply(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1.0F);
		view.apply(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 1.0F);
		view.newWorld(NETHER, "dimension change");
		assertEquals(0.0F, view.rain());
		assertEquals(0.0F, view.thunder());
		assertEquals(NETHER, view.dimension());
	}
}
