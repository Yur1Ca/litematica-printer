package probe.mixins;
import org.spongepowered.asm.mixin.*; import org.spongepowered.asm.mixin.injection.*; import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="game.Target",priority=1000,remap=false) public class TakeItOut {
 @Inject(method="pick",at=@At("HEAD"),cancellable=true) private static void takeitout(CallbackInfoReturnable<Integer> c){if (Boolean.getBoolean("probe.foreignCancels")) c.setReturnValue(2);}
}