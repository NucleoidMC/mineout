package xyz.nucleoid.mineout.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import xyz.nucleoid.mineout.game.map.MineoutMapConfig;
import xyz.nucleoid.plasmid.game.config.PlayerConfig;

public final class MineoutConfig {
    public static final Codec<MineoutConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                MineoutMapConfig.CODEC.fieldOf("map").forGetter(config -> config.map),
                PlayerConfig.CODEC.fieldOf("players").forGetter(config -> config.players),
                Codec.INT.optionalFieldOf("decay_seconds", 4).forGetter(config -> config.decaySeconds),
                Codec.INT.optionalFieldOf("time_limit_seconds", 150).forGetter(config -> config.timeLimitSeconds)
        ).apply(instance, MineoutConfig::new);
    });

    public final MineoutMapConfig map;
    public final PlayerConfig players;
    public final int decaySeconds;
    public final int timeLimitSeconds;

    private MineoutConfig(MineoutMapConfig map, PlayerConfig players, int decaySeconds, int timeLimitSeconds) {
        this.map = map;
        this.players = players;
        this.decaySeconds = decaySeconds;
        this.timeLimitSeconds = timeLimitSeconds;
    }
}
