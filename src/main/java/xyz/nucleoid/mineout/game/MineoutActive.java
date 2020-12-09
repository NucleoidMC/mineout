package xyz.nucleoid.mineout.game;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.apache.commons.lang3.RandomStringUtils;
import xyz.nucleoid.mineout.game.map.MineoutCheckpoint;
import xyz.nucleoid.mineout.game.map.MineoutMap;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.event.*;
import xyz.nucleoid.plasmid.game.player.JoinResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRule;
import xyz.nucleoid.plasmid.game.rule.RuleResult;
import xyz.nucleoid.plasmid.util.BlockBounds;
import xyz.nucleoid.plasmid.widget.GlobalWidgets;
import xyz.nucleoid.plasmid.widget.SidebarWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MineoutActive {
    private static final long CLOSE_TICKS = 20 * 5;

    private final GameSpace gameSpace;
    private final MineoutMap map;
    private final MineoutConfig config;

    private final SidebarWidget sidebar;

    private long startTime;
    private long closeTime = -1;

    private final Object2IntMap<UUID> playerStates = new Object2IntOpenHashMap<>();
    private final List<FinishRecord> finishRecords = new ArrayList<>();

    private final Team team;

    private MineoutActive(GameSpace gameSpace, MineoutMap map, MineoutConfig config, GlobalWidgets widgets) {
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;

        this.sidebar = widgets.addSidebar(new LiteralText("Mineout!").formatted(Formatting.RED, Formatting.BOLD));

        this.playerStates.defaultReturnValue(0);

        ServerScoreboard scoreboard = gameSpace.getServer().getScoreboard();
        String teamKey = RandomStringUtils.randomAlphanumeric(16);
        this.team = scoreboard.addTeam(teamKey);

        this.team.setDisplayName(new LiteralText("Mineout"));
        this.team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        this.team.setFriendlyFireAllowed(false);
    }

    public static void open(GameSpace gameSpace, MineoutMap map, MineoutConfig config) {
        gameSpace.openGame(game -> {
            GlobalWidgets widgets = new GlobalWidgets(game);

            MineoutActive active = new MineoutActive(gameSpace, map, config, widgets);

            game.setRule(GameRule.CRAFTING, RuleResult.DENY);
            game.setRule(GameRule.PVP, RuleResult.DENY);
            game.setRule(GameRule.BLOCK_DROPS, RuleResult.DENY);
            game.setRule(GameRule.FALL_DAMAGE, RuleResult.DENY);
            game.setRule(GameRule.HUNGER, RuleResult.DENY);
            game.setRule(GameRule.THROW_ITEMS, RuleResult.DENY);

            game.on(GameOpenListener.EVENT, active::onOpen);
            game.on(GameCloseListener.EVENT, active::onClose);

            game.on(OfferPlayerListener.EVENT, player -> JoinResult.ok());
            game.on(PlayerAddListener.EVENT, active::addPlayer);

            game.on(GameTickListener.EVENT, active::tick);

            game.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, active::onPlayerDeath);

            game.on(PlaceBlockListener.EVENT, active::onPlaceBlock);
        });
    }

    private void onOpen() {
        this.startTime = this.gameSpace.getWorld().getTime();

        MineoutCheckpoint startCheckpoint = this.map.getCheckpoint(0);
        if (startCheckpoint == null) {
            throw new GameOpenException(new LiteralText("No start checkpoint!"));
        }

        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            this.playerStates.put(player.getUuid(), 0);

            player.setGameMode(GameMode.ADVENTURE);
            startCheckpoint.spawnPlayer(player, this.map.getRotation());
            startCheckpoint.sendTaskTo(player);
        }

        this.updateSidebar();
    }

    private void onClose() {
        ServerScoreboard scoreboard = this.gameSpace.getServer().getScoreboard();
        scoreboard.removeTeam(this.team);
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.spawnPlayer(player);

        ServerScoreboard scoreboard = this.gameSpace.getServer().getScoreboard();
        scoreboard.addPlayerToTeam(player.getEntityName(), this.team);
    }

    private ActionResult onPlaceBlock(ServerPlayerEntity player, BlockPos pos, BlockState state, ItemUsageContext context) {
        if (this.playerStates.containsKey(player.getUuid())) {
            if (this.map.canBuildAt(pos)) {
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.FAIL;
    }

    private void tick() {
        ServerWorld world = this.gameSpace.getWorld();
        long time = world.getTime();

        if (this.closeTime > 0) {
            this.tickClosing(time);
            return;
        }

        BlockBounds mapBounds = this.map.getBounds();

        PlayerSet players = this.gameSpace.getPlayers();
        for (UUID id : this.playerStates.keySet()) {
            ServerPlayerEntity player = players.getEntity(id);
            if (player != null && !mapBounds.contains(player.getBlockPos())) {
                this.spawnPlayer(player);
            }
        }

        List<ServerPlayerEntity> completedPlayers = this.tickCheckpoints();
        if (!completedPlayers.isEmpty()) {
            for (ServerPlayerEntity player : completedPlayers) {
                this.onPlayerComplete(time, player);
            }

            if (this.playerStates.isEmpty()) {
                this.closeTime = time + CLOSE_TICKS;
                this.broadcastFinish();
            }
        }
    }

    private List<ServerPlayerEntity> tickCheckpoints() {
        PlayerSet players = this.gameSpace.getPlayers();

        List<ServerPlayerEntity> completedPlayers = new ArrayList<>();

        for (Object2IntMap.Entry<UUID> entry : Object2IntMaps.fastIterable(this.playerStates)) {
            ServerPlayerEntity player = players.getEntity(entry.getKey());
            if (player == null) {
                continue;
            }

            int checkpointIndex = entry.getIntValue();
            int nextCheckpointIndex = checkpointIndex + 1;
            MineoutCheckpoint nextCheckpoint = this.map.getCheckpoint(nextCheckpointIndex);

            if (nextCheckpoint != null && nextCheckpoint.contains(player)) {
                entry.setValue(nextCheckpointIndex);
                this.movePlayerToNextCheckpoint(player, nextCheckpoint);

                if (!this.map.containsCheckpoint(nextCheckpointIndex + 1)) {
                    completedPlayers.add(player);
                }
            }
        }

        return completedPlayers;
    }

    private void onPlayerComplete(long time, ServerPlayerEntity player) {
        this.spawnSpectator(player);

        this.playerStates.removeInt(player.getUuid());

        int finishSeconds = (int) (time - this.startTime) / 20;
        this.finishRecords.add(new FinishRecord(player.getGameProfile(), finishSeconds));

        this.updateSidebar();

        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("")
                        .append(player.getDisplayName())
                        .append(" finished with a time of ")
                        .append(new LiteralText(finishSeconds + "s").formatted(Formatting.AQUA))
                        .append("!")
                        .formatted(Formatting.GOLD)
        );
    }

    private void movePlayerToNextCheckpoint(ServerPlayerEntity player, MineoutCheckpoint checkpoint) {
        player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);

        checkpoint.applyTo(player);
        checkpoint.sendTaskTo(player);
    }

    private void tickClosing(long time) {
        if (time >= this.closeTime) {
            this.gameSpace.close(GameCloseReason.FINISHED);
        }
    }

    private void broadcastFinish() {
        Text message;
        if (!this.finishRecords.isEmpty()) {
            FinishRecord winningRecord = this.finishRecords.get(0);

            message = new LiteralText("")
                    .append(new LiteralText(winningRecord.player.getName()).formatted(Formatting.AQUA))
                    .append(" won the game!")
                    .formatted(Formatting.GOLD);
        } else {
            message = new LiteralText("Nobody finished the game!").formatted(Formatting.GOLD);
        }

        PlayerSet players = this.gameSpace.getPlayers();
        players.sendMessage(message);
        players.sendSound(SoundEvents.ENTITY_VILLAGER_YES);
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        if (player.isSpectator()) {
            return ActionResult.FAIL;
        }

        if (source == DamageSource.FLY_INTO_WALL) {
            return ActionResult.PASS;
        }

        if (this.shouldRespawnFromDamage(source)) {
            this.spawnPlayer(player);
        }

        return ActionResult.FAIL;
    }

    private boolean shouldRespawnFromDamage(DamageSource source) {
        return source == DamageSource.LAVA || source == DamageSource.OUT_OF_WORLD;
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        if (!player.isSpectator()) {
            this.spawnPlayer(player);
        }
        return ActionResult.FAIL;
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        player.setHealth(20.0F);
        player.setFireTicks(0);
        player.stopFallFlying();

        int checkpointIndex = this.playerStates.getOrDefault(player.getUuid(), -1);
        MineoutCheckpoint checkpoint = this.map.getCheckpoint(checkpointIndex);

        if (checkpoint == null) {
            this.spawnSpectator(player);
            return;
        }

        player.setGameMode(GameMode.ADVENTURE);
        checkpoint.spawnPlayer(player, this.map.getRotation());
    }

    private void spawnSpectator(ServerPlayerEntity player) {
        player.setGameMode(GameMode.SPECTATOR);

        BlockPos spawn = this.map.getSpawn();
        float rotation = this.map.getRotation();
        player.teleport(this.gameSpace.getWorld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, rotation, 0.0F);
    }

    private void updateSidebar() {
        this.sidebar.set(content -> {
            content.writeLine(Formatting.GREEN + "Race to the finish line!");
            content.writeLine("");

            for (FinishRecord record : this.finishRecords) {
                content.writeLine(Formatting.AQUA + record.player.getName() + ": " + Formatting.GOLD + record.seconds + "s");
            }
        });
    }

    private static final class FinishRecord {
        private final GameProfile player;
        private final int seconds;

        FinishRecord(GameProfile player, int seconds) {
            this.player = player;
            this.seconds = seconds;
        }
    }
}
