package xyz.nucleoid.mineout.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import xyz.nucleoid.mineout.game.map.MineoutMapConfig;
import xyz.nucleoid.plasmid.api.game.common.config.WaitingLobbyConfig;

public record MineoutConfig(
        MineoutMapConfig map,
        WaitingLobbyConfig players,
        int decaySeconds,
        int timeLimitSeconds
) {
    public static final MapCodec<MineoutConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> {
        return instance.group(
                MineoutMapConfig.CODEC.fieldOf("map").forGetter(MineoutConfig::map),
                WaitingLobbyConfig.CODEC.fieldOf("players").forGetter(MineoutConfig::players),
                Codec.INT.optionalFieldOf("decay_seconds", 4).forGetter(MineoutConfig::decaySeconds),
                Codec.INT.optionalFieldOf("time_limit_seconds", 150).forGetter(MineoutConfig::timeLimitSeconds)
        ).apply(instance, MineoutConfig::new);
    });
}
