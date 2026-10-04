package dev.romoslayer.stormcell.mixin;

import dev.romoslayer.stormcell.Stormcell;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends a player the weather where they are as soon as their client starts a level (join, respawn, dimension change). */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
	@Inject(method = "sendLevelInfo", at = @At("TAIL"))
	private void stormcell$sendLocalWeather(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
		Stormcell.onLevelInfo(player, level);
	}
}
