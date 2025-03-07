package testbridge.mixins.logisticspipes.compat;

import java.util.function.Function;
import javax.annotation.Nonnull;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.ProbeMode;

import logisticspipes.kotlin.jvm.internal.Intrinsics;
import logisticspipes.kotlin.text.StringsKt;
import logisticspipes.pipes.basic.CoreUnroutedPipe;
import logisticspipes.pipes.basic.LogisticsBlockGenericPipe;

import network.rs485.logisticspipes.compat.TheOneProbeIntegration;

import testbridge.pipes.ResultPipe;

@Mixin(targets = "network.rs485.logisticspipes.compat.TheOneProbeIntegration$PipeInfoProvider")
public abstract class TB_MixinTOPAddon implements Function<ITheOneProbe, Void> {
  @Final
  @Unique
  String tb$prefix = "top.testbridge.";

  @Inject(
    method = "addProbeInfo",
    at = @At(
      value = "INVOKE",
      target = "Llogisticspipes/kotlin/jvm/internal/Intrinsics;checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V",
      ordinal = 1,
      shift = At.Shift.AFTER),
    cancellable = true)
  private void preAddProbeInfo(
      ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, IBlockState blockState, IProbeHitData data, CallbackInfo ci) {
    if (probeInfo != null && blockState != null && data != null) {
      if (blockState.getBlock() instanceof LogisticsBlockGenericPipe) {
        CoreUnroutedPipe pipe = LogisticsBlockGenericPipe.getPipe(world, data.getPos());
        if (pipe instanceof ResultPipe) {
          Intrinsics.checkNotNullExpressionValue(pipe, "pipe");
          this.addResultPipeInfo((ResultPipe) pipe, probeInfo);
          ci.cancel();
        }
      }
    }
  }

  @Shadow(aliases = "TheOneProbeIntegration")
  private TheOneProbeIntegration this$0;

  @Unique
  private void addResultPipeInfo(@Nonnull ResultPipe pipe, IProbeInfo probeInfo) {
    String resultPipeName = pipe.getSatellitePipeName();
    if (!StringsKt.isBlank(resultPipeName)) {
      TheOneProbeIntegration.LPText var4 = this$0.new LPText(tb$prefix + "pipe.result.name");
      var4.getArguments().add(resultPipeName);
      probeInfo.element(var4);
    } else {
      probeInfo.element(this$0.new LPText(tb$prefix + "pipe.result.no_name"));
    }
  }
}
