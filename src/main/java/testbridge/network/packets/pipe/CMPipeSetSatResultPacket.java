package testbridge.network.packets.pipe;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.network.abstractpackets.IntegerModuleCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.utils.StaticResolve;

import network.rs485.logisticspipes.util.LPDataInput;
import network.rs485.logisticspipes.util.LPDataOutput;

import testbridge.modules.TB_ModuleCM;

@StaticResolve
public class CMPipeSetSatResultPacket extends IntegerModuleCoordinatesPacket {

  @Getter
  @Setter
  private UUID pipeID;

  public CMPipeSetSatResultPacket(int id) {
    super(id);
  }

  @Override
  public void readData(LPDataInput input) {
    super.readData(input);
    pipeID = input.readUUID();
  }

  @Override
  public void writeData(LPDataOutput output) {
    super.writeData(output);
    output.writeUUID(pipeID);
  }

  @Override
  public void processPacket(EntityPlayer player) {
    TB_ModuleCM module = this.getLogisticsModule(player, TB_ModuleCM.class);
    if (module == null) {
      return;
    }
    if (getInteger() == 1) {
      module.setSatelliteUUID(getPipeID());
    } else if (getInteger() == 2) {
      module.setResultUUID(getPipeID());
    }
  }

  @Override
  public ModernPacket template() {
    return new CMPipeSetSatResultPacket(getId());
  }
}
