package xyz.nucleoid.mineout.game;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
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
import xyz.nucleoid.plasmid.widget.BossBarWidget;
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
    private final BossBarWidget timerBar;
    private final Object2IntMap<UUID> playerStates = new Object2IntOpenHashMap<>();
    private final List<FinishRecord> finishRecords = new ArrayList<>();
    private final Team team;
    private final MineoutBlockDecay blockDecay;
    private long startTime;
    private long maximumTime;
    private long closeTime = -1;

    private MineoutActive(GameSpace gameSpace, MineoutMap map, MineoutConfig config, GlobalWidgets widgets) {
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;

        this.sidebar = widgets.addSidebar(new LiteralText("Mineout!").formatted(Formatting.RED, Formatting.BOLD));
        this.timerBar = widgets.addBossBar(new LiteralText("Time left"));

        this.playerStates.defaultReturnValue(0);

        int decayStepLength = 10;
        int decaySteps = config.decaySeconds * 20 / decayStepLength;
        this.blockDecay = new MineoutBlockDecay(decaySteps, decayStepLength);

        ServerScoreboard scoreboard = gameSpace.getServer().getScoreboard();
        String teamKey = RandomStringUtils.randomAlphanumeric(16);
        this.team = scoreboard.addTeam(teamKey);

        this.team.setDisplayName(new LiteralText("Mineout"));
        this.team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
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
            game.setRule(GameRule.PVP, RuleResult.ALLOW);

            game.on(GameOpenListener.EVENT, active::onOpen);
            game.on(GameCloseListener.EVENT, active::onClose);

            game.on(OfferPlayerListener.EVENT, player -> JoinResult.ok());
            game.on(PlayerAddListener.EVENT, active::addPlayer);
            game.on(PlayerRemoveListener.EVENT, active::removePlayer);

            game.on(GameTickListener.EVENT, active::tick);

            game.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, active::onPlayerDeath);

            game.on(PlaceBlockListener.EVENT, active::onPlaceBlock);
        });
    }

    private static String ordinal(int i) {
        String[] suffixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
        switch (i % 100) {
            case 11:
            case 12:
            case 13:
                return i + "th";
            default:
                return i + suffixes[i % 10];
        }
    }

    private void onOpen() {
        this.startTime = this.gameSpace.getWorld().getTime();
        this.maximumTime = this.startTime + this.config.timeLimitSeconds * 20L;

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

    private void removePlayer(ServerPlayerEntity player) {
        ServerScoreboard scoreboard = this.gameSpace.getServer().getScoreboard();
        scoreboard.removePlayerFromTeam(player.getEntityName(), this.team);

        this.playerStates.removeInt(player.getUuid());
    }

    private ActionResult onPlaceBlock(ServerPlayerEntity player, BlockPos pos, BlockState state, ItemUsageContext context) {
        if (this.playerStates.containsKey(player.getUuid())) {
            if (this.map.canBuildAt(pos)) {
                this.blockDecay.enqueue(pos);
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
        }

        if (this.playerStates.isEmpty() || time >= this.maximumTime) {
            this.closeTime = time + CLOSE_TICKS;
            this.timerBar.close();
            this.broadcastFinish();
            return;
        }

        LongSet decayBlocks = this.blockDecay.tick(time);
        if (!decayBlocks.isEmpty()) {
            this.applyDecay(decayBlocks);
        }

        if (time % 20 == 0) {
            long ticksRemaining = this.maximumTime - time;
            long secondsRemaining = ticksRemaining / 20;

            long minutes = secondsRemaining / 60;
            long seconds = secondsRemaining % 60;

            this.timerBar.setProgress((float) ticksRemaining / (this.config.timeLimitSeconds * 20));
            this.timerBar.setTitle(new LiteralText(String.format("Time remaining: %02d:%02d", minutes, seconds)));
        }
    }

    private void applyDecay(LongSet blocks) {
        ServerWorld world = this.gameSpace.getWorld();

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        LongIterator iterator = blocks.iterator();
        while (iterator.hasNext()) {
            mutablePos.set(iterator.nextLong());
            world.breakBlock(mutablePos, false);
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
        PlayerSet players = this.gameSpace.getPlayers();
        this.spawnSpectator(player);

        this.playerStates.removeInt(player.getUuid());

        int finishSeconds = (int) (time - this.startTime) / 20;
        this.finishRecords.add(new FinishRecord(player.getGameProfile(), finishSeconds));

        this.updateSidebar();

        Text title = new LiteralText("Well Done!").formatted(Formatting.GREEN);
        Text subtitle = new LiteralText("You completed the course in ")
                .append(new LiteralText(finishSeconds + "s").formatted(Formatting.AQUA))
                .formatted(Formatting.GOLD);

        player.networkHandler.sendPacket(new TitleS2CPacket(10, 60, 10));
        player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE, title));
        player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, subtitle));

        for (ServerPlayerEntity otherPlayer : players) {
            if (!otherPlayer.getUuid().equals(player.getUuid())) {
                otherPlayer.playSound(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 1.0F, 1.0F);
                otherPlayer.sendMessage(
                        new LiteralText("").append(player.getDisplayName())
                                .append(" finished with a time of ")
                                .append(new LiteralText(finishSeconds + "s").formatted(Formatting.AQUA))
                                .append("!")
                                .formatted(Formatting.GOLD),
                        false
                );
            }
        }
    }

    private void movePlayerToNextCheckpoint(ServerPlayerEntity player, MineoutCheckpoint checkpoint) {
        checkpoint.applyTo(player);
        checkpoint.sendTaskTo(player);
    }

    private void tickClosing(long time) {
        if (time >= this.closeTime) {
            this.gameSpace.close(GameCloseReason.FINISHED);
        }
    }

    private void broadcastFinish() {
        PlayerSet players = this.gameSpace.getPlayers();
        if (!this.finishRecords.isEmpty()) {
            FinishRecord winningRecord = this.finishRecords.get(0);

            Text message = new LiteralText("").append(winningRecord.player.getName()).append(" was 1st!").formatted(Formatting.GOLD);
            if (players.size() != 1) {
                for (ServerPlayerEntity player : players) {
                    Text subtitle;

                    int finishIndex = -1;
                    for (int i = 0; i < this.finishRecords.size(); i++) {
                        FinishRecord record = this.finishRecords.get(i);
                        if (record.player.getId().equals(player.getUuid())) {
                            finishIndex = i;
                            break;
                        }
                    }

                    if (finishIndex != -1) {
                        subtitle = new LiteralText("You finished in ").append(ordinal(finishIndex + 1)).append(" place!").formatted(Formatting.BLUE);
                    } else {
                        subtitle = new LiteralText("You didn't finish the course.").formatted(Formatting.RED);
                    }

                    player.networkHandler.sendPacket(new TitleS2CPacket(10, 60, 10));
                    player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE, message));
                    player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, subtitle));
                }
            }
        } else {
            Text message = new LiteralText("You didn't finish the course.").formatted(Formatting.RED);
            Text subtitle = new LiteralText("Don't worry! No one else finished the course either!").formatted(Formatting.AQUA);
            players.sendPacket(new TitleS2CPacket(10, 60, 10));
            players.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE, message));
            players.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, subtitle));
        }
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        if (player.isSpectator()) {
            return ActionResult.FAIL;
        }

        if (source.getSource() instanceof ServerPlayerEntity) {
            int checkpointIndex = this.playerStates.getOrDefault(player.getUuid(), -1);
            MineoutCheckpoint checkpoint = this.map.getCheckpoint(checkpointIndex);
            if (checkpoint != null && checkpoint.isPvpEnabled()) {
                return ActionResult.PASS;
            }
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
            if (this.finishRecords.isEmpty()) {
                return;
            }

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
