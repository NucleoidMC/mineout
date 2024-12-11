package xyz.nucleoid.mineout.game.map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.plasmid.api.game.world.generator.TemplateChunkGenerator;

import java.util.ArrayList;
import java.util.List;

public final class MineoutMap {
    private final MapTemplate template;
    private final List<MineoutCheckpoint> checkpoints = new ArrayList<>();
    private final List<BlockBounds> buildableBounds = new ArrayList<>();

    private final BlockBounds bounds;

    private float rotation;

    public MineoutMap(MapTemplate template) {
        this.template = template;
        this.bounds = template.getBounds();
    }

    public void addCheckpoint(MineoutCheckpoint checkpoint) {
        this.checkpoints.add(checkpoint);
    }

    public void setRotation(float rotation) {
        this.rotation = rotation;
    }

    public BlockPos getSpawn() {
        MineoutCheckpoint checkpoint = this.checkpoints.get(0);
        return checkpoint.getSpawn();
    }

    public float getRotation() {
        return this.rotation;
    }

    public void addBuildable(BlockBounds bounds) {
        this.buildableBounds.add(bounds);
    }

    public boolean canBuildAt(BlockPos pos) {
        for (BlockBounds bounds : this.buildableBounds) {
            if (bounds.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public ChunkGenerator asGenerator(MinecraftServer server) {
        return new TemplateChunkGenerator(server, this.template);
    }

    @Nullable
    public MineoutCheckpoint getCheckpoint(int index) {
        if (!this.containsCheckpoint(index)) {
            return null;
        }
        return this.checkpoints.get(index);
    }

    public boolean containsCheckpoint(int index) {
        return index >= 0 && index < this.checkpoints.size();
    }

    public BlockBounds getBounds() {
        return this.bounds;
    }
}
