package eaangrino.mixin;

import eaangrino.item.ExcavatorItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void mineExcavators$modifyDestroySpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
		Player player = (Player) (Object) this;
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof ExcavatorItem)) {
			return;
		}

		float modifiedSpeed = ExcavatorItem.getModifiedDestroySpeed(stack, player.level(), player, state, cir.getReturnValueF());
		if (modifiedSpeed != cir.getReturnValueF()) {
			cir.setReturnValue(modifiedSpeed);
		}
	}
}
