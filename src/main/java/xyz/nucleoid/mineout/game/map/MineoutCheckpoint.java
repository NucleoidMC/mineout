package xyz.nucleoid.mineout.game.map;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.util.List;
import java.util.Map;

public final class MineoutCheckpoint {
    private final BlockBounds bounds;
    private final String task;
    private final List<ItemStack> give;
    private final Map<EquipmentSlot, ItemStack> equip;
    private final boolean pvp;

    private final BlockPos spawn;

    MineoutCheckpoint(BlockBounds bounds, String task, List<ItemStack> give, Map<EquipmentSlot, ItemStack> equip, boolean pvp) {
        this.bounds = bounds;
        this.task = task;
        this.give = give;
        this.equip = equip;
        this.pvp = pvp;

        this.spawn = new BlockPos(bounds.getCenter());
    }

    public BlockPos getSpawn() {
        return this.spawn;
    }

    public boolean isPvpEnabled() {
        return this.pvp;
    }

    public boolean contains(ServerPlayerEntity player) {
        return this.bounds.contains(player.getBlockPos());
    }

    public void spawnPlayer(ServerPlayerEntity player, float rotation) {
        player.teleport(player.getServerWorld(), this.spawn.getX() + 0.5, this.spawn.getY(), this.spawn.getZ() + 0.5, rotation, 0.0F);
        player.networkHandler.syncWithPlayerPosition();

        this.applyTo(player);
    }

    public void sendTaskTo(ServerPlayerEntity player) {
        if (this.task != null) {
            player.sendMessage(new LiteralText(this.task).formatted(Formatting.GREEN), false);
        }
    }

    public void applyTo(ServerPlayerEntity player) {
        player.inventory.clear();
        player.setHealth(20.0F);

        for (Map.Entry<EquipmentSlot, ItemStack> entry : this.equip.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            ItemStack stack = entry.getValue();
            player.equipStack(slot, stack.copy());
        }

        for (ItemStack stack : this.give) {
            player.inventory.offerOrDrop(player.world, stack.copy());
        }
    }
}
