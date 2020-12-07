package xyz.nucleoid.mineout.game.map;

import net.minecraft.text.LiteralText;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.map.template.MapTemplate;
import xyz.nucleoid.plasmid.map.template.MapTemplateMetadata;
import xyz.nucleoid.plasmid.map.template.MapTemplateSerializer;

import java.io.IOException;

public final class MineoutMapGenerator {
    private final MineoutMapConfig config;

    public MineoutMapGenerator(MineoutMapConfig config) {
        this.config = config;
    }

    public MineoutMap build() {
        if (this.config.checkpoints.size() < 2) {
            throw new GameOpenException(new LiteralText("Not enough checkpoints!"));
        }

        MapTemplate template;
        try {
            template = MapTemplateSerializer.INSTANCE.loadFromResource(this.config.template);
        } catch (IOException e) {
            throw new GameOpenException(new LiteralText("Failed to load map template"), e);
        }

        MapTemplateMetadata metadata = template.getMetadata();

        MineoutMap map = new MineoutMap(template);
        for (CheckpointConfig checkpointConfig : this.config.checkpoints) {
            MineoutCheckpoint checkpoint = checkpointConfig.create(metadata);
            map.addCheckpoint(checkpoint);
        }

        float rotation = metadata.getData().getFloat("rotation");
        map.setRotation(rotation);

        metadata.getRegionBounds("buildable").forEach(map::addBuildable);

        return map;
    }
}
