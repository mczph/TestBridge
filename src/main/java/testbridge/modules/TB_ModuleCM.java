package testbridge.modules;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import logisticspipes.interfaces.*;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.modules.LogisticsModule;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeItemsSatelliteLogistics;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.computers.objects.CCSinkResponder;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

import network.rs485.logisticspipes.module.Gui;
import network.rs485.logisticspipes.module.PipeServiceProviderUtilKt;
import network.rs485.logisticspipes.property.*;

import testbridge.network.guis.pipe.CMGuiProvider;
import testbridge.network.packets.pipe.CMPipeUpdatePacket;
import testbridge.pipes.PipeCraftingManager;
import testbridge.pipes.ResultPipe;

public class TB_ModuleCM extends LogisticsModule implements Gui {

  public final UUIDProperty satelliteUUID = new UUIDProperty(null, "satelliteUUID");
  public final UUIDProperty resultUUID = new UUIDProperty(null, "resultUUID");
  public final BooleanProperty bufferModeIsExclude = new BooleanProperty(true, "bufferModeIsExclude");

  private final List<Property<?>> properties;

  public ClientSideSatResultNames clientSideSatResultNames = new ClientSideSatResultNames();

  @Nullable
  private IRequestItems _invRequester;

  private UpdateSatResultFromIDs updateSatResultFromIDs = null;

  // Logistics Chassis
  private final PipeCraftingManager parentPipe;
  private final SlottedModuleListProperty modules;

  public TB_ModuleCM(int moduleCount, PipeCraftingManager parentPipe) {
    modules = new SlottedModuleListProperty(moduleCount, "modules");
    this.parentPipe = parentPipe;
    registerPosition(ModulePositionType.IN_PIPE, 0);
    properties = ImmutableList.<Property<?>>builder()
        .addAll(Collections.singletonList(modules))
        .add(satelliteUUID)
        .add(resultUUID)
        .add(bufferModeIsExclude)
        .build();
  }

  public static String getName() {
    return "crafting_manager";
  }

  @Nonnull
  @Override
  public String getLPName() {
    return getName();
  }

  @Nonnull
  @Override
  public List<Property<?>> getProperties() {
    return properties;
  }

  public void installModule(int slot, LogisticsModule module) {
    modules.set(slot, module);
  }

  public void removeModule(int slot) {
    modules.clear(slot);
  }

  @Nullable
  public LogisticsModule getModule(int slot) {
    return modules.get(slot).getModule();
  }

  public boolean hasModule(int slot) {
    return !modules.get(slot).isEmpty();
  }

  public Stream<LogisticsModule> getModules() {
    return modules.stream()
        .filter(slottedModule -> !slottedModule.isEmpty())
        .map(SlottedModule::getModule);
  }

  public Stream<SlottedModule> slottedModules() {
    return modules.stream();
  }

  @Override
  public SinkReply sinksItem(@Nonnull ItemStack stack, ItemIdentifier item, int bestPriority, int bestCustomPriority,
                             boolean allowDefault, boolean includeInTransit, boolean forcePassive) {
    SinkReply bestresult = null;
    for (SlottedModule slottedModule : modules) {
      final LogisticsModule module = slottedModule.getModule();
      if (module != null) {
        if (!forcePassive || module.recievePassive()) {
          SinkReply result = module
              .sinksItem(stack, item, bestPriority, bestCustomPriority, allowDefault, includeInTransit,
                  forcePassive);
          if (result != null && result.maxNumberOfItems >= 0) {
            bestresult = result;
            bestPriority = result.fixedPriority.ordinal();
            bestCustomPriority = result.customPriority;
          }
        }
      }
    }

    if (bestresult == null) {
      return null;
    }
    //Always deny items when we can't put the item anywhere
    final ISlotUpgradeManager upgradeManager = parentPipe.getUpgradeManager(ModulePositionType.SLOT,
        ((PipeCraftingManager.CMTargetInformation) bestresult.addInfo).getModuleSlot());
    IInventoryUtil invUtil = PipeServiceProviderUtilKt.availableSneakyInventories(parentPipe, upgradeManager)
        .stream().findFirst().orElse(null);
    if (invUtil == null) {
      return null;
    }
    int roomForItem;
    if (includeInTransit) {
      int onRoute = parentPipe.countOnRoute(item);
      final ItemStack copy = stack.copy();
      copy.setCount(onRoute + item.getMaxStackSize());
      roomForItem = invUtil.roomForItem(copy);
      roomForItem -= onRoute;
    } else {
      roomForItem = invUtil.roomForItem(stack);
    }
    if (roomForItem < 1) {
      return null;
    }

    if (bestresult.maxNumberOfItems == 0) {
      return new SinkReply(bestresult, roomForItem);
    }
    return new SinkReply(bestresult, Math.min(bestresult.maxNumberOfItems, roomForItem));
  }



  @Override
  public void registerHandler(IWorldProvider world, IPipeServiceProvider service) {
    super.registerHandler(world, service);
    _invRequester = (IRequestItems) service;
  }

  private UUID getUUIDForSatelliteName(String name) {
    for (PipeItemsSatelliteLogistics pipe : PipeItemsSatelliteLogistics.AllSatellites) {
      if (pipe.getSatellitePipeName().equals(name)) {
        return pipe.getRouter().getId();
      }
    }
    return null;
  }

  private UUID getUUIDForResultName(String name) {
    for (ResultPipe pipe : ResultPipe.AllResults) {
      if (pipe.getSatellitePipeName().equals(name)) {
        return pipe.getRouter().getId();
      }
    }
    return null;
  }

  @Override
  public void tick() {
    final IPipeServiceProvider service = _service;
    if (service == null) return;
//    enabledUpdateEntity();
    if (updateSatResultFromIDs != null && service.isNthTick(100)) {
      if (updateSatResultFromIDs.satelliteId != -1) {
        UUID uuid = getUUIDForSatelliteName(Integer.toString(updateSatResultFromIDs.satelliteId));
        if (uuid != null) {
          updateSatResultFromIDs.satelliteId = -1;
          satelliteUUID.setValue(uuid);
        }
      }
      if (updateSatResultFromIDs.resultId != -1) {
        UUID uuid = getUUIDForResultName(Integer.toString(updateSatResultFromIDs.resultId));
        if (uuid != null) {
          updateSatResultFromIDs.resultId = -1;
          resultUUID.setValue(uuid);
        }
      }
      if (updateSatResultFromIDs.satelliteId == -1
          && updateSatResultFromIDs.resultId == -1) {
        updateSatResultFromIDs = null;
      }
    }
    for (SlottedModule slottedModule : modules) {
      final LogisticsModule module = slottedModule.getModule();
      if (module == null) {
        continue;
      }
      module.tick();
    }
  }

  @Override
  public void finishInit() {
    super.finishInit();
    getModules().forEach(LogisticsModule::finishInit);
  }

  @Override
  public boolean hasGenericInterests() {
    return false;
  }

  @Override
  public boolean interestedInAttachedInventory() {
    return false;
    // when we are default we are interested in everything anyway, otherwise we're only interested in our filter.
  }

  @Override
  public boolean interestedInUndamagedID() {
    return false;
  }

  @Override
  public boolean recievePassive() {
    return false;
  }

  @Override
  public List<CCSinkResponder> queueCCSinkEvent(ItemIdentifierStack item) {
    List<CCSinkResponder> list = new ArrayList<>();
    for (SlottedModule slottedModule : modules) {
      final LogisticsModule module = slottedModule.getModule();
      if (module != null) {
        list.addAll(module.queueCCSinkEvent(item));
      }
    }
    return list;
  }

  @Nonnull
  @Override
  public ModuleCoordinatesGuiProvider getPipeGuiProvider() {
    return NewGuiHandler.getGui(CMGuiProvider.class)
        .setFlag(parentPipe.getUpgradeManager().hasUpgradeModuleUpgrade());
  }

  @Nonnull
  @Override
  public ModuleInHandGuiProvider getInHandGuiProvider() {
    throw new UnsupportedOperationException("Crafting Manager GUI can never be opened in hand");
  }

  @Override
	public void readFromNBT(@Nonnull NBTTagCompound tag) {
		super.readFromNBT(tag);
    // Stream crafter modules
    modules.stream()
        .filter(slottedModule -> !slottedModule.isEmpty() && tag.hasKey("slot" + slottedModule.getSlot()))
        .forEach(slottedModule -> Objects.requireNonNull(slottedModule.getModule())
            .readFromNBT(tag.getCompoundTag("slot" + slottedModule.getSlot())));
    // Showing update sat and result info
    if (tag.hasKey("satelliteid") || tag.hasKey("resultid")) {
      updateSatResultFromIDs = new UpdateSatResultFromIDs();
      updateSatResultFromIDs.satelliteId = tag.getInteger("satelliteid");
      updateSatResultFromIDs.resultId = tag.getInteger("resultid");
    }
	}

  public ModernPacket getCMPipePacket() {
    return PacketHandler.getPacket(CMPipeUpdatePacket.class)
        .setSatelliteName(getSatResultNameForUUID(satelliteUUID.getValue()))
        .setResultName(getSatResultNameForUUID(resultUUID.getValue()))
        .setModulePos(this);
  }

  private String getSatResultNameForUUID(UUID uuid) {
    if (UUIDPropertyKt.isZero(uuid)) {
      return "";
    }
    int simpleId = SimpleServiceLocator.routerManager.getIDforUUID(uuid);
    IRouter router = SimpleServiceLocator.routerManager.getRouter(simpleId);
    if (router != null) {
      CoreRoutedPipe pipe = router.getPipe();
      if (pipe instanceof PipeItemsSatelliteLogistics) {
        return ((PipeItemsSatelliteLogistics) pipe).getSatellitePipeName();
      } else if (pipe instanceof ResultPipe) {
        return ((ResultPipe) pipe).getSatellitePipeName();
      }
    }
    return "UNKNOWN NAME";
  }

  public void handleCMUpdatePacket(CMPipeUpdatePacket packet) {
    if (MainProxy.isClient(getWorld())) {
      clientSideSatResultNames.satelliteName = packet.getSatelliteName();
      clientSideSatResultNames.resultName = packet.getResultName();
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private void updateSatResultsOnClient() {
    MainProxy.sendToPlayerList(getCMPipePacket(), parentPipe.localModeWatchers);
  }

  public void setSatelliteUUID(@Nullable UUID pipeID) {
    if (pipeID == null) {
      satelliteUUID.zero();
    } else {
      satelliteUUID.setValue(pipeID);
    }
    updateSatResultsOnClient();
    updateSatResultFromIDs = null;
  }

  public void setResultUUID(@Nullable UUID pipeID) {
    if (pipeID == null) {
      resultUUID.zero();
    } else {
      resultUUID.setValue(pipeID);
    }
    updateSatResultsOnClient();
    updateSatResultFromIDs = null;
  }

  private static class UpdateSatResultFromIDs {
    public int satelliteId;
    public int resultId;
  }

  public static class ClientSideSatResultNames {
    public @Nonnull
    String satelliteName = "";
    public @Nonnull
    String resultName = "";
  }

}