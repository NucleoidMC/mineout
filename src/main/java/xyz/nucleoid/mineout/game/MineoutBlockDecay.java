package xyz.nucleoid.mineout.game;

import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;

public final class MineoutBlockDecay {
    private final int decaySteps;
    private final int stepLength;

    private static final LongSet EMPTY = new LongArraySet(0);

    private final LongSet[] queues;
    private LongSet swap = new LongOpenHashSet();

    public MineoutBlockDecay(int decaySteps, int stepLength) {
        this.decaySteps = decaySteps;
        this.stepLength = stepLength;

        this.queues = new LongSet[decaySteps];
        for (int i = 0; i < decaySteps; i++) {
            this.queues[i] = new LongOpenHashSet();
        }
    }

    public void enqueue(BlockPos pos) {
        this.queues[0].add(pos.asLong());
    }

    public LongSet tick(long time) {
        if (time % this.stepLength != 0) {
            return EMPTY;
        }

        int steps = this.decaySteps;

        // take the last queue before we push it off the end
        LongSet last = this.queues[steps - 1];

        // push all queues down
        System.arraycopy(this.queues, 0, this.queues, 1, steps - 1);

        // initialize the first queue to empty
        this.swap.clear();
        this.queues[0] = this.swap;

        // reuse this queue for next tick
        this.swap = last;

        return last;
    }
}
