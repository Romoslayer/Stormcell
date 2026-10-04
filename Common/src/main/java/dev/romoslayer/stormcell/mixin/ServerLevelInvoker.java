package dev.romoslayer.stormcell.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's lightning targeting (lightning rods, then exposed mobs), reused for Stormcell's own strikes. */
@Mixin(ServerLevel.class)
public interface ServerLevelInvoker {
	@Invoker("findLightningTargetAround")
	BlockPos stormcell$findLightningTargetAround(BlockPos pos);
}
