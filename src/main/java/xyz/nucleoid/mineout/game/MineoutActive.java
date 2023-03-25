package xyz.nucleoid.mineout.game;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.mineout.Mineout;
import xyz.nucleoid.mineout.game.map.MineoutCheckpoint;
import xyz.nucleoid.mineout.game.map.MineoutMap;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.game.common.team.GameTeamConfig;
import xyz.nucleoid.plasmid.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.game.common.team.TeamManager;
import xyz.nucleoid.plasmid.game.common.widget.BossBarWidget;
import xyz.nucleoid.plasmid.game.common.widget.SidebarWidget;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.stimuli.event.block.BlockPlaceEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MineoutActive {
    private static final long CLOSE_TICKS = 20 * 5;

    private static final GameTeam TEAM = new GameTeam(
            new GameTeamKey(Mineout.ID),
            GameTeamConfig.builder()
                    .setName(Text.literal("Mineout"))
                    .setCollision(AbstractTeam.CollisionRule.NEVER)
                    .build()
    );

    private final ServerWorld world;
    private final GameSpace gameSpace;
    private final MineoutMap map;
    private final MineoutConfig config;

    private final SidebarWidget sidebar;
    private final BossBarWidget timerBar;
    private final TeamManager teams;

    private final Object2IntMap<UUID> playerStates = new Object2IntOpenHashMap<>();
    private final List<FinishRecord> finishRecords = new ArrayList<>();
    private final MineoutBlockDecay blockDecay;

    private long startTime;
    private long maximumTime;
    private long closeTime = -1;

    private MineoutActive(ServerWorld world, GameSpace gameSpace, MineoutMap map, MineoutConfig config, GlobalWidgets widgets, TeamManager teams) {
        this.world = world;
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;

        this.sidebar = widgets.addSidebar(Text.literal("Mineout!").formatted(Formatting.RED, Formatting.BOLD));
        this.timerBar = widgets.addBossBar(Text.literal("Time left"));

        this.playerStates.defaultReturnValue(0);

        int decayStepLength = 10;
        int decaySteps = config.decaySeconds() * 20 / decayStepLength;
        this.blockDecay = new MineoutBlockDecay(decaySteps, decayStepLength);

        this.teams = teams;
        teams.addTeam(TEAM);
    }

    public static void open(ServerWorld world, GameSpace gameSpace, MineoutMap map, MineoutConfig config) {
        gameSpace.setActivity(activity -> {
            GlobalWidgets widgets = GlobalWidgets.addTo(activity);
            TeamManager teams = TeamManager.addTo(activity);

            MineoutActive active = new MineoutActive(world, gameSpace, map, config, widgets, teams);

            activity.deny(GameRuleType.CRAFTING);
            activity.deny(GameRuleType.PVP);
            activity.deny(GameRuleType.BLOCK_DROPS);
            activity.deny(GameRuleType.FALL_DAMAGE);
            activity.deny(GameRuleType.HUNGER);
            activity.deny(GameRuleType.THROW_ITEMS);
            activity.allow(GameRuleType.PVP);

            activity.listen(GameActivityEvents.ENABLE, active::onOpen);

            activity.listen(GamePlayerEvents.OFFER, active::offerPlayer);
            activity.listen(GamePlayerEvents.ADD, active::addPlayer);
            activity.listen(GamePlayerEvents.REMOVE, active::removePlayer);

            activity.listen(GameActivityEvents.TICK, active::tick);

            activity.listen(PlayerDamageEvent.EVENT, active::onPlayerDamage);
            activity.listen(PlayerDeathEvent.EVENT, active::onPlayerDeath);

            activity.listen(BlockPlaceEvent.BEFORE, active::onPlaceBlock);
        });
    }

    private static String ordinal(int i) {
        String[] suffixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
        return switch (i % 100) {
            case 11, 12, 13 -> i + "th";
            default -> i + suffixes[i % 10];
        };
    }

    private void onOpen() {
        this.startTime = this.gameSpace.getTime();
        this.maximumTime = this.startTime + this.config.timeLimitSeconds() * 20L;

        MineoutCheckpoint startCheckpoint = this.map.getCheckpoint(0);
        if (startCheckpoint == null) {
            throw new GameOpenException(Text.literal("No start checkpoint!"));
        }

        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            this.playerStates.put(player.getUuid(), 0);

            player.changeGameMode(GameMode.ADVENTURE);
            startCheckpoint.spawnPlayer(player, this.map.getRotation());
            startCheckpoint.sendTaskTo(player);
        }

        this.updateSidebar();
    }

    private PlayerOfferResult offerPlayer(PlayerOffer offer) {
        var player = offer.player();
        return offer.accept(this.world, Vec3d.ofBottomCenter(this.map.getSpawn()))
                .and(() -> this.spawnPlayer(player));
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.spawnPlayer(player);
        this.teams.addPlayerTo(player, TEAM.key());
    }

    private void removePlayer(ServerPlayerEntity player) {
        this.playerStates.removeInt(player.getUuid());
    }

    private ActionResult onPlaceBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state, ItemUsageContext context) {
        if (this.playerStates.containsKey(player.getUuid())) {
            if (this.map.canBuildAt(pos)) {
                this.blockDecay.enqueue(pos);
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.FAIL;
    }

    private void tick() {
        long time = this.gameSpace.getTime();

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

            this.timerBar.setProgress((float) ticksRemaining / (this.config.timeLimitSeconds() * 20));
            this.timerBar.setTitle(Text.literal(String.format("Time remaining: %02d:%02d", minutes, seconds)));
        }
    }

    private void applyDecay(LongSet blocks) {
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        LongIterator iterator = blocks.iterator();
        while (iterator.hasNext()) {
            mutablePos.set(iterator.nextLong());
            this.world.breakBlock(mutablePos, false);
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

        Text title = Text.literal("Well Done!").formatted(Formatting.GREEN);
        Text subtitle = Text.literal("You completed the course in ")
                .append(Text.literal(finishSeconds + "s").formatted(Formatting.AQUA))
                .formatted(Formatting.GOLD);

        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 10));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));

        for (ServerPlayerEntity otherPlayer : players) {
            if (!otherPlayer.getUuid().equals(player.getUuid())) {
                otherPlayer.playSound(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 1.0F, 1.0F);
                otherPlayer.sendMessage(
                        Text.empty().append(player.getDisplayName())
                                .append(" finished with a time of ")
                                .append(Text.literal(finishSeconds + "s").formatted(Formatting.AQUA))
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

            Text message = Text.empty().append(winningRecord.player.getName()).append(" was 1st!").formatted(Formatting.GOLD);
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
                        subtitle = Text.literal("You finished in ").append(ordinal(finishIndex + 1)).append(" place!").formatted(Formatting.BLUE);
                    } else {
                        subtitle = Text.literal("You didn't finish the course.").formatted(Formatting.RED);
                    }

                    player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 10));
                    player.networkHandler.sendPacket(new TitleS2CPacket(message));
                    player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
                }
            }
        } else {
            Text message = Text.literal("You didn't finish the course.").formatted(Formatting.RED);
            Text subtitle = Text.literal("Don't worry! No one else finished the course either!").formatted(Formatting.AQUA);
            players.sendPacket(new TitleFadeS2CPacket(10, 60, 10));
            players.sendPacket(new TitleS2CPacket(message));
            players.sendPacket(new SubtitleS2CPacket(subtitle));
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

        if (source.isOf(DamageTypes.FLY_INTO_WALL)) {
            return ActionResult.PASS;
        }

        if (this.shouldRespawnFromDamage(source)) {
            this.spawnPlayer(player);
        }

        return ActionResult.FAIL;
    }

    private boolean shouldRespawnFromDamage(DamageSource source) {
        return source.isOf(DamageTypes.LAVA) || source.isOf(DamageTypes.OUT_OF_WORLD);
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
        var checkpoint = this.map.getCheckpoint(checkpointIndex);

        if (checkpoint == null) {
            this.spawnSpectator(player);
            return;
        }

        player.changeGameMode(GameMode.ADVENTURE);
        checkpoint.spawnPlayer(player, this.map.getRotation());
    }

    private void spawnSpectator(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SPECTATOR);

        var spawn = this.map.getSpawn();
        float rotation = this.map.getRotation();
        player.teleport(this.world, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, rotation, 0.0F);
    }

    private void updateSidebar() {
        this.sidebar.set(content -> {
            content.add(Text.literal("Race to the finish line!").formatted(Formatting.GREEN));
            if (this.finishRecords.isEmpty()) {
                return;
            }

            content.add(ScreenTexts.EMPTY);
            for (var record : this.finishRecords) {
                var name = Text.literal(record.player.getName() + ": ").formatted(Formatting.AQUA);
                var time = Text.literal(record.seconds + "s").formatted(Formatting.GOLD);
                content.add(name.append(time));
            }
        });
    }

    private record FinishRecord(GameProfile player, int seconds) {
    }
}
