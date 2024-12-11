package xyz.nucleoid.mineout.game;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.mineout.game.map.MineoutMap;
import xyz.nucleoid.mineout.game.map.MineoutMapGenerator;
import xyz.nucleoid.plasmid.api.game.GameOpenContext;
import xyz.nucleoid.plasmid.api.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.api.game.GameResult;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GameWaitingLobby;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

import java.util.Set;

public final class MineoutWaiting {
    private final ServerWorld world;
    private final GameSpace gameSpace;
    private final MineoutMap map;
    private final MineoutConfig config;

    private MineoutWaiting(ServerWorld world, GameSpace gameSpace, MineoutMap map, MineoutConfig config) {
        this.world = world;
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
    }

    public static GameOpenProcedure open(GameOpenContext<MineoutConfig> context) {
        MineoutConfig config = context.config();
        MineoutMapGenerator generator = new MineoutMapGenerator(config.map());
        MineoutMap map = generator.build(context.server());

        RuntimeWorldConfig worldConfig = new RuntimeWorldConfig()
                .setGenerator(map.asGenerator(context.server()));

        return context.openWithWorld(worldConfig, (activity, world) -> {
            GameWaitingLobby.addTo(activity, config.players());

            MineoutWaiting waiting = new MineoutWaiting(world, activity.getGameSpace(), map, config);

            activity.listen(GameActivityEvents.REQUEST_START, waiting::requestStart);
            activity.listen(GameActivityEvents.TICK, waiting::tick);

            activity.listen(GamePlayerEvents.ACCEPT, waiting::onAcceptPlayers);
            activity.listen(GamePlayerEvents.OFFER, JoinOffer::accept);
            activity.listen(PlayerDamageEvent.EVENT, (player, source, amount) -> EventResult.DENY);
            activity.listen(PlayerDeathEvent.EVENT, (player, source) -> EventResult.DENY);
        });
    }

    private GameResult requestStart() {
        MineoutActive.open(this.world, this.gameSpace, this.map, this.config);
        return GameResult.ok();
    }

    private void tick() {
        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            if (!this.map.getBounds().contains(player.getBlockPos())) {
                Vec3d spawn = Vec3d.ofBottomCenter(this.map.getSpawn());
                player.teleport(this.world, spawn.getX(), spawn.getY(), spawn.getZ(), Set.of(), this.map.getRotation(), 0, true);
            }
        }
    }

    private JoinAcceptorResult onAcceptPlayers(JoinAcceptor acceptor) {
        return acceptor.teleport(this.world, Vec3d.ofBottomCenter(this.map.getSpawn()), this.map.getRotation(), 0)
                .thenRunForEach(player -> {
                    player.changeGameMode(GameMode.ADVENTURE);

                    player.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.NIGHT_VISION,
                            StatusEffectInstance.INFINITE,
                            1,
                            true,
                            false
                    ));
                });
    }
}
