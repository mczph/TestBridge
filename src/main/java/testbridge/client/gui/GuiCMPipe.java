package testbridge.client.gui;

import java.io.IOException;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import logisticspipes.LPItems;
import logisticspipes.modules.LogisticsModule;
import logisticspipes.modules.ModuleCrafter;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.packets.gui.GuiClosePacket;
import logisticspipes.network.packets.module.ModulePropertiesUpdate;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.gui.extention.GuiExtention;

import network.rs485.logisticspipes.property.PropertyLayer;
import network.rs485.logisticspipes.property.EnumProperty;
import network.rs485.logisticspipes.util.TextUtil;

import testbridge.core.TB_ItemHandlers;
import testbridge.client.popup.GuiSelectResultPopup;
import testbridge.client.popup.GuiSelectSatellitePopup;
import testbridge.modules.TB_ModuleCM;
import testbridge.modules.TB_ModuleCM.BlockingMode;
import testbridge.network.guis.pipe.CMGuiProvider;
import testbridge.network.packets.pipe.CMPipeSetSatResultPacket;
import testbridge.network.packets.cmpipe.CMGui;
import testbridge.pipes.PipeCraftingManager;
import testbridge.pipes.upgrades.ModuleUpgradeManager;
import testbridge.helpers.DummyContainer;
import testbridge.helpers.CrafterSlot;

public class GuiCMPipe extends LogisticsBaseGuiScreen {

  private static final String PREFIX = "gui.crafting_manager.";

  private final boolean hasBufferUpgrade;
  private final boolean hasContainer;
  private final PipeCraftingManager pipeCM;
  private final TB_ModuleCM cmModule;
  private final IInventory _moduleInventory;
  private final Slot[] upgradeslot;
  private final PropertyLayer propertyLayer;
  private final PropertyLayer.ValuePropertyOverlay<BlockingMode, EnumProperty<BlockingMode>> blockingModeOverlay;
  private GuiButton blockingButton;

  public GuiCMPipe(EntityPlayer _player, PipeCraftingManager pipeCM, TB_ModuleCM module, boolean flag, boolean container) {
    super(null);
    hasBufferUpgrade = flag;
    hasContainer = container;
    cmModule = module;
    this.pipeCM = pipeCM;
    _moduleInventory = pipeCM.getModuleInventory();

    propertyLayer = new PropertyLayer(cmModule.getProperties());

    // Create dummy container
    DummyContainer dummy = new DummyContainer(_player.inventory, _moduleInventory);
    dummy.addNormalSlotsForPlayerInventory(8, 16 + 18 * 3 + 15);
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 9; j++)
        dummy.addCMModuleSlot(9*i+j, _moduleInventory, 8 + 18*j, 16 + 18*i, this.pipeCM);

    // Create upgrade slot
    upgradeslot = new Slot[2 * pipeCM.getChassisSize()];
    for (int i = 0; i < pipeCM.getChassisSize(); i++) {
      final int fI = i;
      ModuleUpgradeManager upgradeManager = this.pipeCM.getModuleUpgradeManager(i);
      upgradeslot[i * 2]  = dummy.addUpgradeSlot(0, upgradeManager, 0, - (i - pipeCM.getChassisSize()) * 18, 9 + i * 20, itemStack -> CMGuiProvider.checkStack(itemStack, this.pipeCM, fI));
      upgradeslot[i * 2 + 1] = dummy.addUpgradeSlot(1, upgradeManager, 1, - (i - pipeCM.getChassisSize()) * 18, 9 + i * 20, itemStack -> CMGuiProvider.checkStack(itemStack, this.pipeCM, fI));
    }

    inventorySlots = dummy;

    xSize = 177;
    ySize = 167;
    blockingModeOverlay = propertyLayer.overlay(cmModule.blockingMode);
  }

  @Override
  protected void mouseClicked(int X, int Y, int mouseButton) throws IOException {
    Slot currentSlot = getSlotUnderMouse();
    // TODO: Make those module upgradable
    if (mouseButton == 1 && currentSlot instanceof CrafterSlot) {
      int slotID = currentSlot.getSlotIndex();
      LogisticsModule module = pipeCM.getSubModule(slotID);
      if ( module instanceof ModuleCrafter) {
        ModernPacket packet = PacketHandler.getPacket(CMGui.class).setId(slotID).setPosX(pipeCM.getX()).setPosY(pipeCM.getY()).setPosZ(pipeCM.getZ());
        MainProxy.sendPacketToServer(packet);
      }
    } else super.mouseClicked(X, Y, mouseButton);
  }

  @Override
  public void initGui() {
    super.initGui();
    buttonList.clear();
    extentionControllerLeft.clear();

    CMExtention extention = new CMExtention("gui.satellite.SatelliteName", new ItemStack(LPItems.pipeSatellite), 0);
    extention.registerButton(extentionControllerLeft.registerControlledButton(addButton(new SmallGuiButton(1, guiLeft - 40 / 2 - 18, guiTop + 25, 37, 10, TextUtil.translate(PREFIX + "Select")))));
    extentionControllerLeft.addExtention(extention);
    extention = new CMExtention("gui.result.ResultName" , new ItemStack(TB_ItemHandlers.pipeResult), 1);
    extention.registerButton(extentionControllerLeft.registerControlledButton(addButton(new SmallGuiButton(2, guiLeft - 40 / 2 - 18, guiTop + 25, 37, 10, TextUtil.translate(PREFIX + "Select")))));
    extentionControllerLeft.addExtention(extention);

    if (hasBufferUpgrade) {
      BufferExtention buffered = new BufferExtention(PREFIX + "blocking", new ItemStack(TB_ItemHandlers.upgradeBuffer));
      buffered.registerButton(extentionControllerLeft.registerControlledButton(addButton(blockingButton = new GuiButton(4, guiLeft - 143, guiTop + 23, 140, 14, getModeText()))));
      extentionControllerLeft.addExtention(buffered);
    }
  }

  @Override
  protected void actionPerformed(GuiButton guibutton) throws IOException {
    switch (guibutton.id) {
      case 1:
        openSubGuiForSatResultSelection(1);
        break;
      case 2:
        openSubGuiForSatResultSelection(2);
        break;
      case 4:
        if (hasBufferUpgrade) {
          final BlockingMode newMode = blockingModeOverlay.write(EnumProperty::next);
          blockingButton.displayString = TextUtil.translate(PREFIX + "blocking." + newMode.toString().toLowerCase());
        }
        break;
      default:
        super.actionPerformed(guibutton);
    }
  }

  @Override
  protected void drawGuiContainerForegroundLayer(int par1, int par2) {
    super.drawGuiContainerForegroundLayer(par1, par2);
    drawCenteredString(TextUtil.translate(GuiCMPipe.PREFIX + "CMName"), xSize / 2, 5, 0x404040);
    mc.fontRenderer.drawString(TextUtil.translate("key.categories.inventory"), 7, ySize - 93, 0x404040);
  }

  @Override
  protected void drawGuiContainerBackgroundLayer(float f, int x, int y) {
    GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 9; j++)
        GuiGraphics.drawSlotBackground(mc, guiLeft + 7 + 18 * j, guiTop + 15 + 18 * i);
    GuiGraphics.drawPlayerInventoryBackground(mc, guiLeft + 8, guiTop + ySize - 82);

    super.renderExtentions();
  }

  private void openSubGuiForSatResultSelection(int id) {
    if (pipeCM.getModules().getSlot().isInWorld()) {
      if (id == 1) {
        this.setSubGui(new GuiSelectSatellitePopup(pipeCM.getModules().getBlockPos(), uuid ->
            MainProxy.sendPacketToServer(PacketHandler.getPacket(CMPipeSetSatResultPacket.class).setPipeID(uuid).setInteger(id).setModulePos(pipeCM.getModules()))));
      } else {
        this.setSubGui(new GuiSelectResultPopup(pipeCM.getModules().getBlockPos(), uuid ->
            MainProxy.sendPacketToServer(PacketHandler.getPacket(CMPipeSetSatResultPacket.class).setPipeID(uuid).setInteger(id).setModulePos(pipeCM.getModules()))));
      }
    }
  }

  @Override
  public void onGuiClosed() {
    MainProxy.sendPacketToServer(PacketHandler.getPacket(GuiClosePacket.class).setTilePos(pipeCM.container));
    super.onGuiClosed();
    propertyLayer.unregister();
    if (this.mc.player != null && !propertyLayer.getProperties().isEmpty()) {
      // send update to server, when there are changed properties
      MainProxy.sendPacketToServer(ModulePropertiesUpdate.fromPropertyHolder(propertyLayer).setModulePos(pipeCM.getModules()));
    }
  }

  private String getModeText() {
    return TextUtil.translate(PREFIX + "blocking." + blockingModeOverlay.get().toString().toLowerCase());
  }

  private final class CMExtention extends GuiExtention {
    private final ItemStack showItem;
    private final String translationKey;
    private final int guiButton;

    public CMExtention(String translationKey, ItemStack showItem, int guiButton) {
      this.translationKey = translationKey;
      this.showItem = showItem;
      this.guiButton = guiButton;
    }

    @Override
    public int getFinalWidth() {
      return 120;
    }

    @Override
    public int getFinalHeight() {
      return 40;
    }

    @Override
    public void renderForground(int left, int top) {
      if (!isFullyExtended()) {
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240 / 1.0F, 240 / 1.0F);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.renderItemAndEffectIntoGUI(showItem, left + 5, top + 5);
        itemRender.renderItemOverlayIntoGUI(fontRenderer, showItem, left + 5, top + 5, "");
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        itemRender.zLevel = 0.0F;
      } else {
        mc.fontRenderer.drawString(TextUtil.translate(translationKey), left + 9, top + 8, 0x404040);
        String pipeID = guiButton == 0 ? pipeCM.getModules().clientSideSatResultNames.satelliteName : pipeCM.getModules().clientSideSatResultNames.resultName;
        int maxWidth = 70;
        if (pipeID.isEmpty()) {
          drawCenteredString(TextUtil.translate("gui.crafting.Off"), left + maxWidth / 2 + 7, top + 23, 0x404040);
        } else {
          String name = TextUtil.getTrimmedString(pipeID, maxWidth, mc.fontRenderer, "...");
          drawCenteredString(name, left + maxWidth / 2 + 7, top + 23, 0x404040);
        }
      }
    }
  }

  private final class BufferExtention extends GuiExtention {
    private final ItemStack showItem;
    private final String translationKey;

    public BufferExtention(String translationKey, ItemStack showItem) {
      this.translationKey = translationKey;
      this.showItem = showItem;
    }

    @Override
    public int getFinalWidth() {
      return 150;
    }

    @Override
    public int getFinalHeight() {
      return 40;
    }

    @Override
    public void renderForground(int left, int top) {
      if (!isFullyExtended()) {
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240 / 1.0F, 240 / 1.0F);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.renderItemAndEffectIntoGUI(showItem, left + 5, top + 5);
        itemRender.renderItemOverlayIntoGUI(fontRenderer, showItem, left + 5, top + 5, "");
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        itemRender.zLevel = 0.0F;
      } else {
        mc.fontRenderer.drawString(TextUtil.translate(translationKey), left + 9, top + 8, 0x404040);
        if (hasContainer) {
          blockingButton.displayString = TextUtil.translate(PREFIX + "NoContainer");
          blockingButton.enabled = false;
        } else {
          blockingButton.enabled = true;
        }
      }
    }
  }
}
