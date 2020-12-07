package xyz.nucleoid.mineout.game;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.BubbleWorldConfig;
import xyz.nucleoid.mineout.game.map.MineoutMap;
import xyz.nucleoid.mineout.game.map.MineoutMapGenerator;
import xyz.nucleoid.plasmid.game.*;
import xyz.nucleoid.plasmid.game.event.PlayerAddListener;
import xyz.nucleoid.plasmid.game.event.PlayerDamageListener;
import xyz.nucleoid.plasmid.game.event.PlayerDeathListener;
import xyz.nucleoid.plasmid.game.event.RequestStartListener;

public final class MineoutWaiting {
    private final GameSpace gameSpace;
    private final MineoutMap map;
    private final MineoutConfig config;

    private MineoutWaiting(GameSpace gameSpace, MineoutMap map, MineoutConfig config) {
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
    }

    public static GameOpenProcedure open(GameOpenContext<MineoutConfig> context) {
        MineoutConfig config = context.getConfig();
        MineoutMapGenerator generator = new MineoutMapGenerator(config.map);
        MineoutMap map = generator.build();

        BubbleWorldConfig worldConfig = new BubbleWorldConfig()
                .setGenerator(map.asGenerator(context.getServer()))
                .setDefaultGameMode(GameMode.ADVENTURE);

        return context.createOpenProcedure(worldConfig, game -> {
            GameWaitingLobby.applyTo(game, config.players);

            MineoutWaiting waiting = new MineoutWaiting(game.getSpace(), map, config);

            game.on(RequestStartListener.EVENT, waiting::requestStart);

            game.on(PlayerAddListener.EVENT, waiting::addPlayer);
            game.on(PlayerDamageListener.EVENT, waiting::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, waiting::onPlayerDeath);
        });
    }

    private StartResult requestStart() {
        MineoutActive.open(this.gameSpace, this.map, this.config);
        return StartResult.OK;
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.spawnPlayer(player);
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        return ActionResult.FAIL;
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        this.spawnPlayer(player);
        return ActionResult.FAIL;
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.NIGHT_VISION,
                Integer.MAX_VALUE,
                1,
                true,
                false
        ));

        ServerWorld world = this.gameSpace.getWorld();

        BlockPos pos = this.map.getSpawn();

        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.map.getRotation(), 0.0F);
    }
}
