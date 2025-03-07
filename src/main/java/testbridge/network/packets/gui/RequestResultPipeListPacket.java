package testbridge.network.packets.gui;

import java.util.*;
import java.util.stream.Collectors;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.network.abstractpackets.BooleanCoordinatesPacket;
import logisticspipes.utils.StaticResolve;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.ExitRoute;
import logisticspipes.utils.tuples.Pair;

import testbridge.pipes.ResultPipe;

@StaticResolve
public class RequestResultPipeListPacket extends BooleanCoordinatesPacket {

  public RequestResultPipeListPacket(int id) {
    super(id);
  }

  @Override
  public void processPacket(EntityPlayer player) {
    LogisticsTileGenericPipe pipe = this.getPipe(player.getEntityWorld(), LTGPCompletionCheck.PIPE);
    if (pipe == null || !(pipe.pipe instanceof CoreRoutedPipe)) {
      return;
    }
    CoreRoutedPipe rPipe = (CoreRoutedPipe) pipe.pipe;
    List<Pair<String, UUID>> list;
    if (rPipe.getRouter() == null || rPipe.getRouter().getRouteTable() == null) {
      return;
    }
    list = ResultPipe.AllResults.stream()
        .filter(Objects::nonNull)
        .filter(it -> it.getRouter() != null)
        .filter(it -> {
          List<List<ExitRoute>> routingTable = rPipe.getRouter().getRouteTable();
          return routingTable.size() > it.getRouterId() && routingTable.get(it.getRouterId()) != null && !routingTable.get(it.getRouterId()).isEmpty();
        })
        .sorted(Comparator.comparingDouble(it -> rPipe.getRouter().getRouteTable().get(it.getRouterId()).stream().map(it1 -> it1.distanceToDestination).min(Double::compare).get()))
        .map(it -> new Pair<>(it.getSatellitePipeName(), it.getRouter().getId()))
        .collect(Collectors.toList());
    MainProxy.sendPacketToPlayer(PacketHandler.getPacket(ProvideResultPipeListPacket.class).setList(list), player);
  }

  @Override
  public ModernPacket template() {
    return new RequestResultPipeListPacket(getId());
  }
}
