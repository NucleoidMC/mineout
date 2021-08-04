package xyz.nucleoid.mineout.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import xyz.nucleoid.mineout.game.map.MineoutMapConfig;
import xyz.nucleoid.plasmid.game.common.config.PlayerConfig;

public record MineoutConfig(
        MineoutMapConfig map,
        PlayerConfig players,
        int decaySeconds,
        int timeLimitSeconds
) {
    public static final Codec<MineoutConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                MineoutMapConfig.CODEC.fieldOf("map").forGetter(MineoutConfig::map),
                PlayerConfig.CODEC.fieldOf("players").forGetter(MineoutConfig::players),
                Codec.INT.optionalFieldOf("decay_seconds", 4).forGetter(MineoutConfig::decaySeconds),
                Codec.INT.optionalFieldOf("time_limit_seconds", 150).forGetter(MineoutConfig::timeLimitSeconds)
        ).apply(instance, MineoutConfig::new);
    });
}
