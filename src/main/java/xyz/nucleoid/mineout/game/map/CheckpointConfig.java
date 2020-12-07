package xyz.nucleoid.mineout.game.map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.map.template.MapTemplateMetadata;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CheckpointConfig {
    private static final Codec<EquipmentSlot> EQUIPMENT_SLOT_CODEC = Codec.STRING.xmap(EquipmentSlot::byName, EquipmentSlot::getName);

    public static final Codec<CheckpointConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                Codec.STRING.fieldOf("region").forGetter(c -> c.region),
                Codec.STRING.optionalFieldOf("task").forGetter(c -> Optional.ofNullable(c.task)),
                ItemStack.CODEC.listOf().optionalFieldOf("give", ImmutableList.of()).forGetter(c -> c.give),
                Codec.unboundedMap(EQUIPMENT_SLOT_CODEC, ItemStack.CODEC).optionalFieldOf("equip", ImmutableMap.of()).forGetter(c -> c.equip)
        ).apply(instance, CheckpointConfig::new);
    });

    private final String region;
    private final String task;

    private final List<ItemStack> give;
    private final Map<EquipmentSlot, ItemStack> equip;

    private CheckpointConfig(String region, Optional<String> task, List<ItemStack> give, Map<EquipmentSlot, ItemStack> equip) {
        this.region = region;
        this.task = task.orElse(null);
        this.give = give;
        this.equip = equip;
    }

    public MineoutCheckpoint create(MapTemplateMetadata metadata) {
        BlockBounds bounds = metadata.getFirstRegionBounds(this.region);
        if (bounds == null) {
            throw new GameOpenException(new LiteralText("Missing checkpoint region: " + this.region));
        }

        return new MineoutCheckpoint(bounds, this.task, this.give, this.equip);
    }
}
