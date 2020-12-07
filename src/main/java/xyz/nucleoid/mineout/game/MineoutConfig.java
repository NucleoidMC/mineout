package xyz.nucleoid.mineout.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import xyz.nucleoid.mineout.game.map.MineoutMapConfig;
import xyz.nucleoid.plasmid.game.config.PlayerConfig;

public final class MineoutConfig {
    public static final Codec<MineoutConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                MineoutMapConfig.CODEC.fieldOf("map").forGetter(config -> config.map),
                PlayerConfig.CODEC.fieldOf("players").forGetter(config -> config.players)
        ).apply(instance, MineoutConfig::new);
    });

    public final MineoutMapConfig map;
    public final PlayerConfig players;

    private MineoutConfig(MineoutMapConfig map, PlayerConfig players) {
        this.map = map;
        this.players = players;
    }
}
