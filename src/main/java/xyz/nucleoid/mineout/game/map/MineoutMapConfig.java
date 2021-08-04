package xyz.nucleoid.mineout.game.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.List;

public record MineoutMapConfig(
        Identifier template,
        List<CheckpointConfig> checkpoints
) {
    public static final Codec<MineoutMapConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                Identifier.CODEC.fieldOf("template").forGetter(c -> c.template),
                CheckpointConfig.CODEC.listOf().fieldOf("checkpoints").forGetter(c -> c.checkpoints)
        ).apply(instance, MineoutMapConfig::new);
    });
}
