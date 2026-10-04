package dev.romoslayer.stormcell.mixin;

import dev.romoslayer.stormcell.sync.ClientWeatherView;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every packet sent to a player, so ClientWeatherView knows exactly what weather each client is showing, whoever
 * sent it (Stormcell or vanilla). Only weather, join and respawn packets are looked at.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
	private void stormcell$observe(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game) {
			ClientWeatherView.observe(game.player, packet);
		}
	}
}
