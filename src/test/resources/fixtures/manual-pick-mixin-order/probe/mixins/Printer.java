package probe.mixins;
import org.spongepowered.asm.mixin.*; import org.spongepowered.asm.mixin.injection.*; import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="game.Target",priority=PRINTER_PRIORITY,remap=false) public class Printer {
 @Inject(method="pick",at=@At("HEAD"),order=PRINTER_ORDER,cancellable=true) private static void printer(CallbackInfoReturnable<Integer> c){if (Boolean.getBoolean("probe.printerCancels")) c.setReturnValue(1);}
}