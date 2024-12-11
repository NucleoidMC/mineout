package xyz.nucleoid.mineout.game.map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.map_templates.MapTemplateMetadata;
import xyz.nucleoid.map_templates.MapTemplateSerializer;
import xyz.nucleoid.plasmid.api.game.GameOpenException;

import java.io.IOException;

public final class MineoutMapGenerator {
    private final MineoutMapConfig config;

    public MineoutMapGenerator(MineoutMapConfig config) {
        this.config = config;
    }

    public MineoutMap build(MinecraftServer server) {
        if (this.config.checkpoints().size() < 2) {
            throw new GameOpenException(Text.literal("Not enough checkpoints!"));
        }

        MapTemplate template;
        try {
            template = MapTemplateSerializer.loadFromResource(server, this.config.template());
        } catch (IOException e) {
            throw new GameOpenException(Text.literal("Failed to load map template"), e);
        }

        MapTemplateMetadata metadata = template.getMetadata();

        MineoutMap map = new MineoutMap(template);
        for (CheckpointConfig checkpointConfig : this.config.checkpoints()) {
            MineoutCheckpoint checkpoint = checkpointConfig.create(metadata);
            map.addCheckpoint(checkpoint);
        }

        float rotation = metadata.getData().getFloat("rotation");
        map.setRotation(rotation);

        metadata.getRegionBounds("buildable").forEach(map::addBuildable);

        return map;
    }
}
