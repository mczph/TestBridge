package testbridge.pipes;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import lombok.Getter;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.client.FMLClientHandler;

import logisticspipes.LPItems;
import logisticspipes.LogisticsPipes;
import logisticspipes.config.Configs;
import logisticspipes.interfaces.*;
import logisticspipes.interfaces.routing.*;
import logisticspipes.items.ItemModule;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.ItemModuleInformationManager;
import logisticspipes.logisticspipes.TransportLayer;
import logisticspipes.modules.LogisticsModule;
import logisticspipes.modules.LogisticsModule.ModulePositionType;
import logisticspipes.modules.ModuleCrafter;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.pipe.SendQueueContent;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.computers.interfaces.CCCommand;
import logisticspipes.proxy.computers.interfaces.CCType;
import logisticspipes.request.*;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.routing.order.LogisticsOrder;
import logisticspipes.security.SecuritySettings;
import logisticspipes.ticks.HudUpdateTick;
import logisticspipes.utils.EnumFacingUtil;
import logisticspipes.utils.ISimpleInventoryEventHandler;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierInventory;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.utils.tuples.Pair;

import network.rs485.logisticspipes.connection.*;
import network.rs485.logisticspipes.pipes.IChassisPipe;
import network.rs485.logisticspipes.property.SlottedModule;

import testbridge.client.gui.GuiCMPipe;
import testbridge.helpers.CMTransportLayer;
import testbridge.modules.TB_ModuleCM;
import testbridge.modules.TB_ModuleCrafter;
import testbridge.network.packets.pipe.CMOrientationPacket;
import testbridge.network.packets.pipe.CMPipeModuleContent;
import testbridge.network.packets.pipe.RequestCMOrientationPacket;
import testbridge.pipes.upgrades.BufferCMUpgrade;
import testbridge.pipes.upgrades.ModuleUpgradeManager;
import testbridge.client.TB_Textures;

@CCType(name = "TestBridge:CraftingManager")
public class PipeCraftingManager extends CoreRoutedPipe
    implements ICraftItems, ISimpleInventoryEventHandler, ISendRoutedItem, IChassisPipe, IChangeListener, ISendQueueContentRecieiver {

  private final TB_ModuleCM moduleCM;
  private final ItemIdentifierInventory _moduleInventory;
  private final NonNullList<ModuleUpgradeManager> slotUpgradeManagers = NonNullList.create();
  private boolean init = false;
  public final PlayerCollectionList localModeWatchers = new PlayerCollectionList();

  @Nullable
  private SingleAdjacent pointedAdjacent = null;

  @CCCommand(description = "Returns the size of this container pipe")
  public int getChassisSize() {
    return 27;
  }

  public PipeCraftingManager(Item item) {
    super(item);
    _moduleInventory = new ItemIdentifierInventory(getChassisSize(), "Crafting Manager", 1);
    _moduleInventory.addListener(this);
    assert slotUpgradeManagers.size() == 0; // starting at index 0
    for (int i = 0; i < getChassisSize(); i++) {
      addModuleUpgradeManager();
    }
    moduleCM = new TB_ModuleCM(getChassisSize(), this);
    moduleCM.registerHandler(this, this);
  }

  /**
   * Returns the pointed adjacent EnumFacing or null, if this pipe does not have an attached inventory.
   */
  @Nullable
  @Override
  public EnumFacing getPointedOrientation() {
    if (pointedAdjacent == null) return null;
    return pointedAdjacent.getDir();
  }

  @Nonnull
  protected Adjacent getPointedAdjacentOrNoAdjacent() {
    // for public access, use getAvailableAdjacent()
    if (pointedAdjacent == null) {
      return NoAdjacent.INSTANCE;
    } else {
      return pointedAdjacent;
    }
  }

  /**
   * Returns just the adjacent this pipe points at or no adjacent.
   */
  @Nonnull
  @Override
  public Adjacent getAvailableAdjacent() {
    return getPointedAdjacentOrNoAdjacent();
  }

  /**
   * Updates pointedAdjacent on {@link CoreRoutedPipe}.
   */
  @Override
  protected void updateAdjacentCache() {
    super.updateAdjacentCache();
    final Adjacent adjacent = getAdjacent();
    if (adjacent instanceof SingleAdjacent) {
      pointedAdjacent = ((SingleAdjacent) adjacent);
    } else {
      final SingleAdjacent oldPointedAdjacent = pointedAdjacent;
      SingleAdjacent newPointedAdjacent = null;
      if (oldPointedAdjacent != null) {
        // update pointed adjacent with connection type or reset it
        newPointedAdjacent = adjacent.optionalGet(oldPointedAdjacent.getDir()).map(connectionType -> new SingleAdjacent(this, oldPointedAdjacent.getDir(), connectionType)).orElse(null);
      }
      if (newPointedAdjacent == null) {
        newPointedAdjacent = adjacent.neighbors().entrySet().stream().findAny().map(connectedNeighbor -> new SingleAdjacent(this, connectedNeighbor.getKey().getDirection(), connectedNeighbor.getValue())).orElse(null);
      }
      pointedAdjacent = newPointedAdjacent;
    }
  }

  @Nullable
  private Pair<NeighborTileEntity<TileEntity>, ConnectionType> nextPointedOrientation(@Nullable EnumFacing previousDirection) {
    final Map<NeighborTileEntity<TileEntity>, ConnectionType> neighbors = getAdjacent().neighbors();
    final Stream<NeighborTileEntity<TileEntity>> sortedNeighborsStream = neighbors.keySet().stream()
        .sorted(Comparator.comparingInt(n -> n.getDirection().ordinal()));
    if (previousDirection == null) {
      return sortedNeighborsStream.findFirst().map(neighbor -> new Pair<>(neighbor, neighbors.get(neighbor))).orElse(null);
    } else {
      final List<NeighborTileEntity<TileEntity>> sortedNeighbors = sortedNeighborsStream.collect(Collectors.toList());
      if (sortedNeighbors.size() == 0) return null;
      final Optional<NeighborTileEntity<TileEntity>> nextNeighbor = sortedNeighbors.stream()
          .filter(neighbor -> neighbor.getDirection().ordinal() > previousDirection.ordinal())
          .findFirst();
      return nextNeighbor.map(neighbor -> new Pair<>(neighbor, neighbors.get(neighbor)))
          .orElse(new Pair<>(sortedNeighbors.get(0), neighbors.get(sortedNeighbors.get(0))));
    }
  }

  @Override
  public void nextOrientation() {
    final SingleAdjacent pointedAdjacent = this.pointedAdjacent;
    Pair<NeighborTileEntity<TileEntity>, ConnectionType> newNeighbor;
    if (pointedAdjacent == null) {
      newNeighbor = nextPointedOrientation(null);
    } else {
      newNeighbor = nextPointedOrientation(pointedAdjacent.getDir());
    }
    final CMOrientationPacket packet = PacketHandler.getPacket(CMOrientationPacket.class);
    if (newNeighbor == null) {
      this.pointedAdjacent = null;
      packet.setDir(null);
    } else {
      this.pointedAdjacent = new SingleAdjacent(
          this, newNeighbor.getValue1().getDirection(), newNeighbor.getValue2());
      packet.setDir(newNeighbor.getValue1().getDirection());
    }
    MainProxy.sendPacketToAllWatchingChunk(moduleCM, packet.setTilePos(container));
    refreshRender(true);
  }

  @Override
  public void setPointedOrientation(@Nullable EnumFacing dir) {
    if (dir == null) {
      pointedAdjacent = null;
    } else {
      pointedAdjacent = new SingleAdjacent(this, dir, ConnectionType.UNDEFINED);
    }
  }

  private void updateModuleInventory() {
    moduleCM.slottedModules().forEach(slottedModule -> {
      if (slottedModule.isEmpty()) {
        _moduleInventory.clearInventorySlotContents(slottedModule.getSlot());
        return;
      }
      final LogisticsModule module = Objects.requireNonNull(slottedModule.getModule());
      final ItemIdentifierStack idStack = _moduleInventory.getIDStackInSlot(slottedModule.getSlot());
      ItemStack moduleStack;
      if (idStack != null) {
        moduleStack = idStack.getItem().makeNormalStack(1);
      } else {
        ResourceLocation resourceLocation = LPItems.modules.get(module.getLPName());
        Item item = Item.REGISTRY.getObject(resourceLocation);
        if (item == null) return;
        moduleStack = new ItemStack(item);
      }
      ItemModuleInformationManager.saveInformation(moduleStack, module);
      _moduleInventory.setInventorySlotContents(slottedModule.getSlot(), moduleStack);
    });
  }

  @Override
  @Nonnull
  public IInventory getModuleInventory() {
    updateModuleInventory();
    return _moduleInventory;
  }

  public ModuleUpgradeManager getModuleUpgradeManager(int slot) {
    return slotUpgradeManagers.get(slot);
  }

  @Override
  public TextureType getCenterTexture() {
    return TB_Textures.TESTBRIDGE_CMPIPE_TEXTURE;
  }

  @Override
  public TextureType getRoutedTexture(EnumFacing connection) {
    if (getRouter().isSubPoweredExit(connection)) {
      return TB_Textures.LOGISTICSPIPE_SUBPOWER_TEXTURE;
    }
    return TB_Textures.LOGISTICSPIPE_CHASSI_ROUTED_TEXTURE;
  }

  @Override
  public TextureType getNonRoutedTexture(EnumFacing connection) {
    if (pointedAdjacent != null && connection.equals(pointedAdjacent.getDir())) {
      return TB_Textures.LOGISTICSPIPE_CHASSI_DIRECTION_TEXTURE;
    }
    if (isPowerProvider(connection)) {
      return TB_Textures.LOGISTICSPIPE_POWERED_TEXTURE;
    }
    return TB_Textures.LOGISTICSPIPE_CHASSI_NOTROUTED_TEXTURE;
  }

  @Override
  public void readFromNBT(@Nonnull NBTTagCompound nbttagcompound) {
    super.readFromNBT(nbttagcompound);
    _moduleInventory.readFromNBT(nbttagcompound, "craftingmanager");
    moduleCM.readFromNBT(nbttagcompound);
    int tmp = nbttagcompound.getInteger("Orientation");
    if (tmp != -1) {
      setPointedOrientation(EnumFacingUtil.getOrientation(tmp % 6));
    }
    for (int i = 0; i < getChassisSize(); i++) {
      // TODO: remove after 1.12.2 update, backwards compatibility
      final ItemIdentifierStack idStack = _moduleInventory.getIDStackInSlot(i);
      if (idStack != null && !moduleCM.hasModule(i)) {
        final Item stackItem = idStack.getItem().item;
        if (stackItem instanceof ItemModule) {
          final ItemModule moduleItem = (ItemModule) stackItem;
          LogisticsModule module = moduleItem.getModule(null, this, this);
          if (module != null) {
            moduleCM.installModule(i, module);
          }
        }
      }

      if (i >= slotUpgradeManagers.size()) {
        addModuleUpgradeManager();
      }
      slotUpgradeManagers.get(i).readFromNBT(nbttagcompound, Integer.toString(i));
    }
    // register slotted modules
    moduleCM.slottedModules()
           .filter(slottedModule -> !slottedModule.isEmpty())
           .forEach(slottedModule -> {
             LogisticsModule logisticsModule = Objects.requireNonNull(slottedModule.getModule());
             // FIXME: rely on getModuleForItem instead
             logisticsModule.registerHandler(this, this);
             slottedModule.registerPosition();
           });
  }

  private void addModuleUpgradeManager() {
    slotUpgradeManagers.add(new ModuleUpgradeManager(this, upgradeManager));
  }

  @Override
  public void writeToNBT(@Nonnull NBTTagCompound nbttagcompound) {
    super.writeToNBT(nbttagcompound);
    updateModuleInventory();
    _moduleInventory.writeToNBT(nbttagcompound, "craftingmanager");
    moduleCM.writeToNBT(nbttagcompound);
    nbttagcompound.setInteger("Orientation", pointedAdjacent == null ? -1 : pointedAdjacent.getDir().ordinal());
    for (int i = 0; i < getChassisSize(); i++) {
      slotUpgradeManagers.get(i).writeToNBT(nbttagcompound, Integer.toString(i));
    }
  }

  @Override
  public void onAllowedRemoval() {
    _moduleInventory.removeListener(this);
    if (MainProxy.isServer(getWorld())) {
      for (int i = 0; i < getChassisSize(); i++) {
        LogisticsModule x = getSubModule(i);
        if (x instanceof ILegacyActiveModule) {
          ILegacyActiveModule y = (ILegacyActiveModule) x;
          y.onBlockRemoval();
        }
      }
      updateModuleInventory();
      _moduleInventory.dropContents(getWorld(), getX(), getY(), getZ());

      for (int i = 0; i < getChassisSize(); i++) {
        getModuleUpgradeManager(i).dropUpgrades();
      }
    }
  }

  @Override
  public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
    if (MainProxy.isServer(getWorld())) {
      if (info instanceof PipeCraftingManager.CMTargetInformation) {
        PipeCraftingManager.CMTargetInformation target = (PipeCraftingManager.CMTargetInformation) info;
        LogisticsModule module = getSubModule(target.moduleSlot);
        if (module instanceof IRequireReliableTransport) {
          ((IRequireReliableTransport) module).itemArrived(item, info);
        }
      } else {
        if (LogisticsPipes.isDEBUG() && info != null) {
          System.out.println(item);
          new RuntimeException("[ItemArrived] Information weren't ment for a crafting manager pipe").printStackTrace();
        }
      }
    }
  }

  @Override
  public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
    if (MainProxy.isServer(getWorld())) {
      if (info instanceof PipeCraftingManager.CMTargetInformation) {
        PipeCraftingManager.CMTargetInformation target = (PipeCraftingManager.CMTargetInformation) info;
        LogisticsModule module = getSubModule(target.moduleSlot);
        if (module instanceof IRequireReliableTransport) {
          ((IRequireReliableTransport) module).itemLost(item, info);
        }
      } else {
        if (LogisticsPipes.isDEBUG()) {
          System.out.println(item);
          new RuntimeException("[ItemLost] Information weren't ment for a crafting manager pipe").printStackTrace();
        }
      }
    }
  }

  @Override
  public IRoutedItem sendStack(@Nonnull ItemStack stack, int destRouterId, @Nonnull SinkReply sinkReply, @Nonnull ItemSendMode itemSendMode, EnumFacing direction) {
    return super.sendStack(stack, destRouterId, sinkReply, itemSendMode, direction);
  }

  @Override
  public void InventoryChanged(IInventory inventory) {
    boolean reInitGui = false;
    for (int i = 0; i < inventory.getSizeInventory(); i++) {
      ItemStack stack = inventory.getStackInSlot(i);
      if (stack.isEmpty()) {
        if (moduleCM.hasModule(i)) {
          moduleCM.removeModule(i);
          reInitGui = true;
        }
        continue;
      }

      final Item stackItem = stack.getItem();
      if (stackItem instanceof ItemModule) {
        LogisticsModule current = moduleCM.getModule(i);
        LogisticsModule next = getModuleForItem(stack, moduleCM.getModule(i), this, this);
        Objects.requireNonNull(next, "getModuleForItem returned null for " + stack);
        next.registerPosition(ModulePositionType.SLOT, i);
        if (current != next) {
          moduleCM.installModule(i, next);
          if (!MainProxy.isClient(getWorld())) {
            ItemModuleInformationManager.readInformation(stack, next);
          }
          next.finishInit();
        }
        inventory.setInventorySlotContents(i, stack);
      }
    }
    if (reInitGui) {
      if (MainProxy.isClient(getWorld())) {
        if (FMLClientHandler.instance().getClient().currentScreen instanceof GuiCMPipe) {
          FMLClientHandler.instance().getClient().currentScreen.initGui();
        }
      }
    }
    if (MainProxy.isServer(getWorld())) {
      if (!localModeWatchers.isEmpty()) {
        MainProxy.sendToPlayerList(PacketHandler.getPacket(CMPipeModuleContent.class)
                .setIdentList(ItemIdentifierStack.getListFromInventory(_moduleInventory))
                .setPosX(getX()).setPosY(getY()).setPosZ(getZ()),
            localModeWatchers);
      }
    }
  }

  @Override
  public void ignoreDisableUpdateEntity() {
    if (!init) {
      init = true;
      if (MainProxy.isClient(getWorld())) {
        MainProxy.sendPacketToServer(PacketHandler.getPacket(RequestCMOrientationPacket.class).setPosX(getX()).setPosY(getY()).setPosZ(getZ()));
      }
      InventoryChanged(_moduleInventory);
    }
  }

  @Override
  public final @Nullable LogisticsModule getLogisticsModule() {
    return moduleCM;
  }

  @Nonnull
  @Override
  public TransportLayer getTransportLayer() {
    if (_transportLayer == null) {
      _transportLayer = new CMTransportLayer(this);
    }
    return _transportLayer;
  }

  private boolean tryInsertingModule(EntityPlayer entityplayer) {
    if (!isCraftingModule(entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND))) return false;
    updateModuleInventory();
    for (int i = 0; i < _moduleInventory.getSizeInventory(); i++) {
      if (_moduleInventory.getIDStackInSlot(i) == null) {
        _moduleInventory.setInventorySlotContents(i, entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND).splitStack(1));
        InventoryChanged(_moduleInventory);
        return true;
      }
    }
    return false;
  }

  public static boolean isCraftingModule(ItemStack itemStack){
    return itemStack.getItem() == Item.REGISTRY.getObject(LPItems.modules.get(ModuleCrafter.getName()));
  }

  @Override
  public boolean handleClick(EntityPlayer entityplayer, SecuritySettings settings) {
    if (entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND).isEmpty()) {
      return false;
    }

    if (entityplayer.isSneaking() && SimpleServiceLocator.configToolHandler.canWrench(entityplayer, entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND), container)) {
      if (MainProxy.isServer(getWorld())) {
        if (settings == null || settings.openGui) {
          ((PipeCraftingManager) container.pipe).nextOrientation();
        } else {
          entityplayer.sendMessage(new TextComponentTranslation("lp.chat.permissiondenied"));
        }
      }
      SimpleServiceLocator.configToolHandler.wrenchUsed(entityplayer, entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND), container);
      return true;
    }

    if (!entityplayer.isSneaking() && entityplayer.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND).getItem() instanceof ItemModule) {
      if (MainProxy.isServer(getWorld())) {
        if (settings == null || settings.openGui) {
          return tryInsertingModule(entityplayer);
        } else {
          entityplayer.sendMessage(new TextComponentTranslation("lp.chat.permissiondenied"));
        }
      }
      return true;
    }

    return false;
  }

  @Override
  public void canProvide(RequestTreeNode tree, RequestTree root, List<IFilter> filters) {}

  @Override
  public LogisticsOrder fullFill(LogisticsPromise promise, IRequestItems destination, IAdditionalTargetInformation info) {
    return null;
  }

  @Override
  public void getAllItems(Map<ItemIdentifier, Integer> list, List<IFilter> filter) {}

  @Override
  public ItemSendMode getItemSendMode() {
    return ItemSendMode.Normal;
  }

  @Nonnull
  public LogisticsModule getModuleForItem(
      @Nullable LogisticsModule currentModule,
      @Nullable IWorldProvider world,
      @Nullable IPipeServiceProvider service
  ) {
    if (currentModule != null) {
      if (TB_ModuleCrafter.class.equals(currentModule.getClass())) {
        return currentModule;
      }
    }
    LogisticsModule newModule = new TB_ModuleCrafter();
    newModule.registerHandler(world, service);
    return newModule;
  }

  @Nullable
  public LogisticsModule getModuleForItem(
      @Nonnull ItemStack itemStack,
      @Nullable LogisticsModule currentModule,
      @Nullable IWorldProvider world,
      @Nullable IPipeServiceProvider service
  ) {
    if (itemStack.isEmpty()) {
      return null;
    }

    if (!isCraftingModule(itemStack)) {
      return null;
    }

    return getModuleForItem(currentModule, world, service);
  }

  @Override
  public void playerStartWatching(EntityPlayer player, int mode) {}

  @Override
  public void playerStopWatching(EntityPlayer player, int mode) {}

  @Override
  public void listenedChanged() {}

  @Override
  public void finishInit() {
    super.finishInit();
    moduleCM.finishInit();
  }

  public void handleModuleItemIdentifierList(Collection<ItemIdentifierStack> _allItems) {
    _moduleInventory.handleItemIdentifierList(_allItems);
  }

  @Override
  public int sendQueueChanged(boolean force) {
    if (MainProxy.isServer(getWorld())) {
      if (Configs.MULTI_THREAD_NUMBER > 0 && !force) {
        HudUpdateTick.add(getRouter());
      } else {
        if (localModeWatchers.size() > 0) {
          LinkedList<ItemIdentifierStack> items = ItemIdentifierStack.getListSendQueue(_sendQueue);
          MainProxy.sendToPlayerList(PacketHandler.getPacket(SendQueueContent.class).setIdentList(items).setPosX(getX()).setPosY(getY()).setPosZ(getZ()), localModeWatchers);
          return items.size();
        }
      }
    }
    return 0;
  }

  @Override
  public void handleSendQueueItemIdentifierList(Collection<ItemIdentifierStack> _allItems) {}

  public TB_ModuleCM getModules() {
    return moduleCM;
  }

  @Override
  public void setTile(TileEntity tile) {
    super.setTile(tile);
    moduleCM.slottedModules().forEach(SlottedModule::registerPosition);
  }

  @Override
  public int getSourceID() {
    return getRouterId();
  }

  @Override
  public void collectSpecificInterests(@Nonnull Collection<ItemIdentifier> itemidCollection) {
    // if we don't have a pointed inventory while buffer upgrade is on
    // we can't be interested in anything
    if (hasBufferUpgrade() && getPointedAdjacentOrNoAdjacent().inventories().isEmpty()) {
      return;
    }

    for (int i = 0; i < getChassisSize(); i++) {
      LogisticsModule module = getSubModule(i);
      if (module != null) {
        module.collectSpecificInterests(itemidCollection);
      }
    }
  }

  @Override
  public boolean hasGenericInterests() {
    return false;
  }

  /** ICraftItems */
  public final LinkedList<LogisticsOrder> _extras = new LinkedList<>();

  @Override
  public void registerExtras(IPromise promise) {
    if (!(promise instanceof LogisticsPromise)) {
      throw new UnsupportedOperationException("Extra has to be an item for a chassis pipe");
    }
    ItemIdentifierStack stack = new ItemIdentifierStack(((LogisticsPromise) promise).item, ((LogisticsPromise) promise).numberOfItems);
    _extras.add(new LogisticsItemOrder(new DictResource(stack, null), null, IOrderInfoProvider.ResourceType.EXTRA, null));
  }

  @Override
  public ICraftingTemplate addCrafting(IResource toCraft) {
    for (int i = 0; i < getChassisSize(); i++) {
      LogisticsModule x = getSubModule(i);

      if (x instanceof ICraftItems) {
        if (((ICraftItems) x).canCraft(toCraft)) {
          return ((ICraftItems) x).addCrafting(toCraft);
        }
      }
    }
    return null;

    // trixy code goes here to ensure the right crafter answers the right request
  }

  @Override
  public List<ItemIdentifierStack> getCraftedItems() {
    List<ItemIdentifierStack> craftables = null;
    for (int i = 0; i < getChassisSize(); i++) {
      LogisticsModule x = getSubModule(i);

      if (x instanceof ICraftItems) {
        if (craftables == null) {
          craftables = new LinkedList<>();
        }
        craftables.addAll(((ICraftItems) x).getCraftedItems());
      }
    }
    return craftables;
  }

  @Override
  public boolean canCraft(IResource toCraft) {
    for (int i = 0; i < getChassisSize(); i++) {
      LogisticsModule x = getSubModule(i);

      if (x instanceof ICraftItems) {
        if (((ICraftItems) x).canCraft(toCraft)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nonnull
  @Override
  public ISlotUpgradeManager getUpgradeManager(ModulePositionType slot, int positionInt) {
    if (slot != ModulePositionType.SLOT || positionInt >= slotUpgradeManagers.size()) {
      if (LogisticsPipes.isDEBUG()) {
        new UnsupportedOperationException("Position info aren't for a crafting manager pipe. (" + slot + "/" + positionInt + ")").printStackTrace();
      }
      return super.getUpgradeManager(slot, positionInt);
    }
    return slotUpgradeManagers.get(positionInt);
  }

  @Override
  public int getTodo() {
    // TODO Auto-generated method stub
    // probably not needed, the chasi order manager handles the count, would need to store origin to specifically know this.
    return 0;
  }

  @Nullable
  public LogisticsModule getSubModule(int slot) {
    return moduleCM.getModule(slot);
  }

  public static class CMTargetInformation implements IAdditionalTargetInformation {

    @Getter
    private final int moduleSlot;

    public CMTargetInformation(int slot) {
      moduleSlot = slot;
    }
  }

  @Override
  public void setCCType(Object type) {
    super.setCCType(type);
  }

  @Override
  public Object getCCType() {
    return super.getCCType();
  }

  public IRouter getSatelliteRouterByUUID(UUID id) {
    if (id == null) return null;
    int satelliteRouterId = SimpleServiceLocator.routerManager.getIDforUUID(id);
    return SimpleServiceLocator.routerManager.getRouter(satelliteRouterId);
  }

  public IRouter getCMSatelliteRouter() {
    final UUID satelliteUUID = getModules().getSatelliteUUID().getValue();
    final int satelliteRouterId = SimpleServiceLocator.routerManager.getIDforUUID(satelliteUUID);
    return SimpleServiceLocator.routerManager.getRouter(satelliteRouterId);
  }

  public IRouter getCMResultRouter() {
    final UUID resultUUID = getModules().getResultUUID().getValue();
    final int resultRouterId = SimpleServiceLocator.routerManager.getIDforUUID(resultUUID);
    return SimpleServiceLocator.routerManager.getRouter(resultRouterId);
  }

  public boolean hasBufferUpgrade() {
    for (int i = 0 ; i < 9 ; i++) {
      if (upgradeManager.getUpgrade(i) instanceof BufferCMUpgrade) {
        return true;
      }
    }
    return false;
  }

  public int getBlockingMode() {
    return moduleCM != null ? moduleCM.getBlockingMode().getValue().ordinal() : 0;
  }
}
