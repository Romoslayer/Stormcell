package dev.romoslayer.stormcell.world;

import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Where a loot roll happens, if the loot context knows. */
public final class LootOrigin {
	private LootOrigin() {
	}

	public static @Nullable Vec3 of(LootContext context) {
		return context.getParamOrNull(LootContextParams.ORIGIN);
	}
}
