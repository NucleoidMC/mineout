package xyz.nucleoid.mineout.game.map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplateMetadata;
import xyz.nucleoid.plasmid.api.game.GameOpenException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final record CheckpointConfig(
        String region,
        String task,
        List<ItemStack> give,
        Map<EquipmentSlot, ItemStack> equip,
        boolean pvp
) {
    private static final Codec<EquipmentSlot> EQUIPMENT_SLOT_CODEC = Codec.STRING.xmap(EquipmentSlot::byName, EquipmentSlot::getName);

    public static final Codec<CheckpointConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                Codec.STRING.fieldOf("region").forGetter(CheckpointConfig::region),
                Codec.STRING.optionalFieldOf("task").forGetter(c -> Optional.ofNullable(c.task())),
                ItemStack.CODEC.listOf().optionalFieldOf("give", ImmutableList.of()).forGetter(CheckpointConfig::give),
                Codec.unboundedMap(EQUIPMENT_SLOT_CODEC, ItemStack.CODEC).optionalFieldOf("equip", ImmutableMap.of()).forGetter(CheckpointConfig::equip),
                Codec.BOOL.optionalFieldOf("pvp", false).forGetter(c -> c.pvp)
        ).apply(instance, CheckpointConfig::new);
    });

    private CheckpointConfig(String region, Optional<String> task, List<ItemStack> give, Map<EquipmentSlot, ItemStack> equip, boolean pvp) {
        this(region, task.orElse(null), give, equip, pvp);
    }

    public MineoutCheckpoint create(MapTemplateMetadata metadata) {
        BlockBounds bounds = metadata.getFirstRegionBounds(this.region);
        if (bounds == null) {
            throw new GameOpenException(Text.literal("Missing checkpoint region: " + this.region));
        }

        return new MineoutCheckpoint(bounds, this.task, this.give, this.equip, this.pvp);
    }
}
