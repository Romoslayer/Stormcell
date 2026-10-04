package dev.romoslayer.stormcell.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Lets another mod (a seasons mod, typically) shape the broad climate Stormcell's weather forms in. Stormcell asks
 * every registered provider about each weather sample point (16 per cell by default) whenever it refreshes a cell, on
 * the server thread, so answers should be cheap. Stormcell itself still decides where storms form, how they move and
 * how strong they get.
 */
@FunctionalInterface
public interface ClimateModifierProvider {
	/**
	 * The modifiers for one sample point.
	 *
	 * @param pos   the sample point (its height is only a rough guide)
	 * @param biome the biome there, as the world generator places it
	 * @return the modifiers to apply, or {@link ClimateModifiers#NONE} for no change
	 */
	ClimateModifiers getModifiers(ServerLevel level, BlockPos pos, Holder<Biome> biome);
}
