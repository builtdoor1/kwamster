package kwamster.mixin;

import kwamster.capture.CombatCapture;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A landed melee hit (§6.2). Vanilla reaches {@code MultiPlayerGameMode.attack}
 * (client/multiplayer/MultiPlayerGameMode.java:427) only when the pick found an entity in range, and calls
 * {@code player.attack(entity)} there unless the local game mode is spectator (430), then resets the
 * attack-strength ticker (432). So at HEAD the charge is intact, and reproducing the spectator guard
 * gives exactly "vanilla will call {@code player.attack}".
 *
 * <p>HEAD with {@code require = 0} rather than the {@code Player.attack} INVOKE: another client mod may
 * redirect that call, and a required injector would then crash the game at startup. A missing injector
 * only degrades accuracy (CAPTURE logs "Landed-hit hook active" the first time this runs).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
			at = @At("HEAD"), require = 0)
	private void kwamster$landed(Player player, Entity target, CallbackInfo ci) {
		if (!((MultiPlayerGameMode) (Object) this).isSpectator()) {
			CombatCapture.onGameModeAttack(player, target);
		}
	}
}
