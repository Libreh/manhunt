package manhunt.game;

import com.mojang.brigadier.CommandDispatcher;
import eu.pb4.playerdata.api.PlayerDataApi;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import manhunt.command.*;
import manhunt.mixin.MinecraftServerAccessInterface;
import manhunt.mixin.ServerWorldInterface;
import manhunt.world.ManhuntWorldModule;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.SpawnLocating;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.world.*;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

import static manhunt.ManhuntMod.LOGGER;
import static manhunt.ManhuntMod.MOD_ID;
import static manhunt.config.ManhuntConfig.*;
import static manhunt.game.ManhuntState.*;

public class ManhuntGame {
    public static final Identifier LOBBY_WORLD_ID = new Identifier(MOD_ID, "lobby");
    public static final Identifier OVERWORLD_ID = new Identifier("manhunt", "overworld");
    public static final Identifier THE_NETHER_ID = new Identifier("manhunt", "the_nether");
    public static final Identifier THE_END_ID = new Identifier("manhunt", "the_end");
    public static RegistryKey<World> lobbyRegistryKey = RegistryKey.of(RegistryKeys.WORLD, LOBBY_WORLD_ID);
    public static RegistryKey<World> overworldRegistryKey = RegistryKey.of(RegistryKeys.WORLD, OVERWORLD_ID);
    public static RegistryKey<World> theNetherRegistryKey = RegistryKey.of(RegistryKeys.WORLD, THE_NETHER_ID);
    public static RegistryKey<World> theEndRegistryKey = RegistryKey.of(RegistryKeys.WORLD, THE_END_ID);
    public static List<ServerPlayerEntity> allRunners;
    public static String previousDuration;
    public static String previousSeed;
    public static HashMap<UUID, Boolean> isReady = new HashMap<>();
    public static HashMap<ServerPlayerEntity, Integer> votedRunners = new HashMap<>();
    public static HashMap<ServerPlayerEntity, Integer> topVoted = new HashMap<>();
    public static ArrayList<Identifier> personalPreferences = new ArrayList<>();
    public static ArrayList<Identifier> runnerPreferences = new ArrayList<>();
    public static HashMap<Setting, Integer> settingIndex = new HashMap<>();
    public static HashMap<Identifier, Integer> personalPreferencesIndex = new HashMap<>();
    public static HashMap<Identifier, Integer> runnerPreferencesIndex = new HashMap<>();
    public static int settingSlot = 0;
    public static int personalPreferencesSlot = 0;
    public static int runnerPreferencesSlot = 0;
    private static boolean paused;
    public static boolean isPaused() {
        return paused;
    }
    public static void setPaused(boolean paused) {
        ManhuntGame.paused = paused;
    }
    public final MinecraftServerAccessInterface serverAccessMixin;
    public static BlockPos worldSpawnPos;
    public static boolean hasPreloaded;
    public static void setHasPreloaded(boolean hasPreloaded) {
        ManhuntGame.hasPreloaded = hasPreloaded;
    }
    public static Identifier isRunner = new Identifier(MOD_ID, "is_runner");
    public static Identifier runnerVotes = new Identifier(MOD_ID, "runner_votes");
    public static Identifier votesList = new Identifier(MOD_ID, "votes_list");
    public static Identifier votesLeft = new Identifier(MOD_ID, "votes_left");
    public static Identifier playerSpawnX = new Identifier(MOD_ID, "spawn_x");
    public static Identifier playerSpawnY = new Identifier(MOD_ID, "spawn_y");
    public static Identifier playerSpawnZ = new Identifier(MOD_ID, "spawn_z");
    public static Identifier runsLeft = new Identifier(MOD_ID, "runs_left");
    public static Identifier showWinnerTitle = new Identifier(MOD_ID, "show_winner_title");
    public static Identifier manhuntSoundsVolume = new Identifier(MOD_ID, "manhunt_sounds_volume");
    public static Identifier showSettingsAtStart = new Identifier(MOD_ID, "show_settings_at_start");
    public static Identifier showDurationOnWin = new Identifier(MOD_ID, "show_duration_on_win");
    public static Identifier showSeedOnWin = new Identifier(MOD_ID, "show_seed_on_win");
    public static Identifier allowBedExplosions = new Identifier(MOD_ID, "allow_bed_explosions");
    public static Identifier allowLavaPvpInNether = new Identifier(MOD_ID, "allow_lava_pvp_in_the_nether");

    public ManhuntGame(MinecraftServerAccessInterface serverAccessMixin) {
        this.serverAccessMixin = serverAccessMixin;
    }

    public static void commandRegister(CommandDispatcher<ServerCommandSource> dispatcher) {
        PingCommand.register(dispatcher);
        HunterCommand.register(dispatcher);
        RunnerCommand.register(dispatcher);
        OneRunnerCommand.register(dispatcher);
        StartCommand.register(dispatcher);
        SettingsCommand.register(dispatcher);
        DurationCommand.register(dispatcher);
        TogglePauseCommand.register(dispatcher);
        SendTeamCoordsCommand.register(dispatcher);
        ShowTeamCoordsCommand.register(dispatcher);
        CancelCommand.register(dispatcher);
        ResetCommand.register(dispatcher);
        VoteCommand.register(dispatcher);
    }

    // Thanks to https://gitlab.com/horrific-tweaks/bingo for the spawnStructure method

    private static void spawnStructure(MinecraftServer server) throws IOException {
        var lobbyIcebergNbt = NbtIo.readCompressed(ManhuntGame.class.getResourceAsStream("/data/manhunt/iceberg.nbt"), NbtSizeTracker.ofUnlimitedBytes());

        var lobbyWorld = server.getWorld(lobbyRegistryKey);

        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(0, 0), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(-15, 0), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(0, -15), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(-15, -15), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(16, 16), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(16, 0), 16, Unit.INSTANCE);
        lobbyWorld.getChunkManager().addTicket(ChunkTicketType.START, new ChunkPos(0, 16), 16, Unit.INSTANCE);
        placeStructure(lobbyWorld, new BlockPos(-8, 41, -8), lobbyIcebergNbt);
    }

    // Thanks to https://gitlab.com/horrific-tweaks/bingo for the placeStructure method

    private static void placeStructure(ServerWorld world, BlockPos pos, NbtCompound nbt) {
        StructureTemplate template = world.getStructureTemplateManager().createTemplate(nbt);

        template.place(
                world,
                pos,
                pos,
                new StructurePlacementData(),
                StructureBlockBlockEntity.createRandom(world.getSeed()),
                2
        );
    }

    public static void serverStart(MinecraftServer server) {
        WORLD_SEED.set(RandomSeed.getSeed());

        new ManhuntWorldModule().loadWorlds(server);

        setHasPreloaded(true);

        setPaused(false);

        manhuntState(PREGAME, server);

        personalPreferences.add(showWinnerTitle);
        personalPreferences.add(manhuntSoundsVolume);
        personalPreferences.add(showSettingsAtStart);
        personalPreferences.add(showDurationOnWin);
        personalPreferences.add(showSeedOnWin);

        runnerPreferences.add(allowBedExplosions);
        runnerPreferences.add(allowLavaPvpInNether);

        Difficulty difficulty = Difficulty.EASY;

        if (WORLD_DIFFICULTY.get().equals("Normal")) {
            difficulty = Difficulty.NORMAL;
        } else if (WORLD_DIFFICULTY.get().equals("Hard")) {
            difficulty = Difficulty.HARD;
        }

        server.setDifficulty(difficulty, true);

        var world = server.getWorld(lobbyRegistryKey);

        world.getGameRules().get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, server);
        world.getGameRules().get(GameRules.DO_FIRE_TICK).set(false, server);
        world.getGameRules().get(GameRules.DO_INSOMNIA).set(false, server);
        world.getGameRules().get(GameRules.DO_MOB_LOOT).set(false, server);
        world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, server);
        world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
        world.getGameRules().get(GameRules.FALL_DAMAGE).set(false, server);
        world.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(0, server);
        world.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(false, server);
        world.getGameRules().get(GameRules.SPAWN_RADIUS).set(0, server);
        world.getGameRules().get(GameRules.FALL_DAMAGE).set(false, server);

        server.setPvpEnabled(false);

        Scoreboard scoreboard = server.getScoreboard();

        for (Team team : scoreboard.getTeams()) {
            String name = team.getName();

            if (name.equals("players") || name.equals("hunters") || name.equals("runners")) {
                scoreboard.removeTeam(scoreboard.getTeam(name));
            }
        }

        scoreboard.addTeam("players");
        scoreboard.addTeam("hunters");
        scoreboard.addTeam("runners");

        scoreboard.getTeam("players").setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        scoreboard.getTeam("hunters").setColor(Formatting.RED);
        scoreboard.getTeam("runners").setColor(Formatting.GREEN);

        try {
            spawnStructure(server);
        } catch (IOException e) {
            LOGGER.fatal("Failed to spawn Manhunt mod lobby");
        }
    }

    public static void serverTick(MinecraftServer server) {
        if (gameState == PREGAME) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (doesNotHaveItem(player, Items.RED_CONCRETE, "NotReady") && doesNotHaveItem(player, Items.GREEN_CONCRETE, "Ready") && SET_ROLES.get().equals("Free Select")) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("NotReady", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"manhunt.item.notready\",\"italic\": false,\"color\": \"red\"}");

                    ItemStack notReady = new ItemStack(Items.RED_CONCRETE);
                    notReady.setNbt(nbt);

                    player.getInventory().setStack(0, notReady);
                }

                if (doesNotHaveItem(player, Items.RECOVERY_COMPASS, "Hunter") && SET_ROLES.get().equals("Free Select")) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Hunter", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"manhunt.item.hunter\",\"italic\": false,\"color\": \"aqua\"}");

                    ItemStack chooseHunter = new ItemStack(Items.RECOVERY_COMPASS);
                    chooseHunter.setNbt(nbt);

                    player.getInventory().setStack(3, chooseHunter);
                }

                if (doesNotHaveItem(player, Items.CLOCK, "Runner") && SET_ROLES.get().equals("Free Select")) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Runner", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"manhunt.item.runner\",\"italic\": false,\"color\": \"gold\"}");

                    ItemStack chooseRunner = new ItemStack(Items.CLOCK);
                    chooseRunner.setNbt(nbt);

                    player.getInventory().setStack(5, chooseRunner);
                }

                if (player.hasPermissionLevel(1) || player.hasPermissionLevel(2) || player.hasPermissionLevel(3) || player.hasPermissionLevel(4)) {
                    if (doesNotHaveItem(player, Items.COMPARATOR, "Settings")) {
                        NbtCompound nbt = new NbtCompound();
                        nbt.putBoolean("Remove", true);
                        nbt.putBoolean("Settings", true);
                        nbt.putInt("HideFlags", 1);
                        nbt.put("display", new NbtCompound());
                        nbt.getCompound("display").putString("Name", "{\"translate\": \"manhunt.settings\",\"italic\": false,\"color\": \"white\"}");

                        ItemStack showSettings = new ItemStack(Items.COMPARATOR);
                        showSettings.setNbt(nbt);

                        player.getInventory().setStack(7, showSettings);
                    }
                }

                if (doesNotHaveItem(player, Items.REPEATER, "Preferences")) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Preferences", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Preferences\",\"italic\": false,\"color\": \"white\"}");

                    ItemStack showPreferences = new ItemStack(Items.REPEATER);
                    showPreferences.setNbt(nbt);

                    player.getInventory().setStack(8, showPreferences);
                }
            }
        }

        if (gameState == PLAYING) {
            allRunners = new LinkedList<>();

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player != null) {
                    if (player.isTeamPlayer(player.getScoreboard().getTeam("runners"))) {
                        allRunners.add(player);
                    }
                }
            }
            if (Integer.parseInt(TIME_LIMIT_MINUTES.get()) != 0) {
                if (server.getWorld(overworldRegistryKey).getTime() % (20 * 60 * 60) / (20 * 60) >= Integer.parseInt(TIME_LIMIT_MINUTES.get())) {
                    manhuntState(POSTGAME, server);
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        if (PlayerDataApi.getGlobalDataFor(player, showWinnerTitle) == NbtByte.ONE) {
                            player.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable("manhunt.title.hunterswon").formatted(Formatting.RED)));
                            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("manhunt.title.timelimit").formatted(Formatting.DARK_RED)));
                            if (!PlayerDataApi.getGlobalDataFor(player, manhuntSoundsVolume).equals(NbtInt.of(0))) {
                                float volume = (float) Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, manhuntSoundsVolume))) / 100;
                                if (volume >= 0.2f) {
                                    player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, volume / 2, 2f);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void playerJoin(ServerPlayNetworkHandler handler, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();

        if (gameState == PREGAME) {
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            player.teleport(server.getWorld(lobbyRegistryKey), 0, 63, 5.5, PositionFlag.ROT, 0, 0);
            player.getInventory().clear();
            updateGameMode(player);
            player.setFireTicks(0);
            player.setOnFire(false);
            player.setFireTicks(0);
            player.setHealth(20);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5);
            player.getHungerManager().setExhaustion(0);
            player.setExperienceLevel(0);
            player.setExperiencePoints(0);
            player.clearStatusEffects();
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, StatusEffectInstance.INFINITE, 255, false, false, false));
            player.getScoreboard().clearTeam(player.getName().getString());
            player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));

            for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                for (String criteria : progress.getObtainedCriteria()) {
                    player.getAdvancementTracker().revokeCriterion(advancement, criteria);
                }
            }

            if (SET_ROLES.get().equals("All Runners")) {
                player.getScoreboard().clearTeam(player.getName().getString());
                player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("runners"));
            }

            if (server.getWorld(overworldRegistryKey) == null) return;
            setPlayerSpawnXYZ(server.getWorld(overworldRegistryKey), player);
        }

        if (gameState == PLAYING || gameState == POSTGAME) {
            updateGameMode(player);

            int playerX = Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnX)));
            int playerY = Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnY)));
            int playerZ = Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnZ)));
            player.setSpawnPoint(overworldRegistryKey, new BlockPos(playerX, playerY, playerZ), 0, true, false);

            if (player.isTeamPlayer(player.getScoreboard().getTeam("players"))) {
                player.getScoreboard().removeScoreHolderFromTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
            }

            if (player.hasStatusEffect(StatusEffects.SATURATION)) {
                player.removeStatusEffect(StatusEffects.SATURATION);
            }
        }

        if (!player.isTeamPlayer(player.getScoreboard().getTeam("hunters")) && !player.isTeamPlayer(player.getScoreboard().getTeam("runners"))) {
            player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("hunters"));
        }

        if (PlayerDataApi.getGlobalDataFor(player, isRunner) == null) {
            PlayerDataApi.setGlobalDataFor(player, isRunner, NbtByte.ZERO);
        }
        if (PlayerDataApi.getGlobalDataFor(player, runnerVotes) == null) {
            PlayerDataApi.setGlobalDataFor(player, runnerVotes, NbtInt.of(0));
        }
        if (PlayerDataApi.getGlobalDataFor(player, votesList) == null) {
            PlayerDataApi.setGlobalDataFor(player, votesList, new NbtList());
        }
        if (PlayerDataApi.getGlobalDataFor(player, votesLeft) == null) {
            PlayerDataApi.setGlobalDataFor(player, votesLeft, NbtInt.of(0));
        }
        if (PlayerDataApi.getGlobalDataFor(player, runsLeft) == null) {
            PlayerDataApi.setGlobalDataFor(player, runsLeft, NbtInt.of(0));
        }
        if (PlayerDataApi.getGlobalDataFor(player, showWinnerTitle) == null) {
            if (Boolean.parseBoolean(SHOW_WINNER_TITLE.get())) {
                PlayerDataApi.setGlobalDataFor(player, showWinnerTitle, NbtByte.ONE);
            } else {
                PlayerDataApi.setGlobalDataFor(player, showWinnerTitle, NbtByte.ZERO);
            }
        }
        if (PlayerDataApi.getGlobalDataFor(player, manhuntSoundsVolume) == null) {
            PlayerDataApi.setGlobalDataFor(player, manhuntSoundsVolume, NbtInt.of(Integer.parseInt(MANHUNT_SOUNDS_VOLUME.get())));
        }
        if (PlayerDataApi.getGlobalDataFor(player, showSettingsAtStart) == null) {
            if (Boolean.parseBoolean(SHOW_SETTINGS_AT_START.get())) {
                PlayerDataApi.setGlobalDataFor(player, showSettingsAtStart, NbtByte.ONE);
            } else {
                PlayerDataApi.setGlobalDataFor(player, showSettingsAtStart, NbtByte.ZERO);
            }
        }
        if (PlayerDataApi.getGlobalDataFor(player, showDurationOnWin) == null) {
            if (Boolean.parseBoolean(SHOW_DURATION_ON_WIN.get())) {
                PlayerDataApi.setGlobalDataFor(player, showDurationOnWin, NbtByte.ONE);
            } else {
                PlayerDataApi.setGlobalDataFor(player, showDurationOnWin, NbtByte.ZERO);
            }
        }
        if (PlayerDataApi.getGlobalDataFor(player, showSeedOnWin) == null) {
            if (Boolean.parseBoolean(SHOW_SEED_ON_WIN.get())) {
                PlayerDataApi.setGlobalDataFor(player, showSeedOnWin, NbtByte.ONE);
            } else {
                PlayerDataApi.setGlobalDataFor(player, showSeedOnWin, NbtByte.ZERO);
            }
        }
        if (PlayerDataApi.getGlobalDataFor(player, allowBedExplosions) == null) {
            PlayerDataApi.setGlobalDataFor(player, allowBedExplosions, NbtByte.ONE);
        }
        if (PlayerDataApi.getGlobalDataFor(player, allowLavaPvpInNether) == null) {
            PlayerDataApi.setGlobalDataFor(player, allowLavaPvpInNether, NbtByte.ONE);
        }
    }

    public static void playerDisconnect(ServerPlayNetworkHandler handler) {
        PlayerDataApi.setGlobalDataFor(handler.getPlayer(), isRunner, NbtByte.ZERO);
    }

    public static TypedActionResult<ItemStack> useItem(PlayerEntity player, Hand hand) {
        var itemStack = player.getStackInHand(hand);

        if (gameState == PREGAME) {
            MinecraftServer server = player.getServer();

            if (!player.getItemCooldownManager().isCoolingDown(itemStack.getItem())) {
                if (itemStack.getItem() == Items.RED_CONCRETE && itemStack.getNbt().getBoolean("NotReady")) {
                    isReady.put(player.getUuid(), true);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Ready", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"manhunt.item.ready\",\"italic\": false,\"color\": \"green\"}");

                    ItemStack ready = new ItemStack(Items.LIME_CONCRETE);
                    ready.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, ready);

                    player.getItemCooldownManager().set(ready.getItem(), 20);

                    player.playSound(SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5f, 1.5f);

                    if (isReady.size() == server.getPlayerManager().getPlayerList().size()) {
                        if (player.getScoreboard().getTeam("runners").getPlayerList().isEmpty()) {
                            server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.minimum").formatted(Formatting.RED), false);
                        } else {
                            if (!player.getScoreboard().getTeam("runners").getPlayerList().isEmpty()) {
                                startGame(server);
                            }
                        }
                    }

                    server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.ready", Text.literal(player.getName().getString()).formatted(Formatting.WHITE), Text.literal(String.valueOf(isReady.size())).formatted(Formatting.WHITE), Text.literal(String.valueOf(server.getPlayerManager().getPlayerList().size())).formatted(Formatting.WHITE)).formatted(Formatting.GREEN), false);
                }

                if (itemStack.getItem() == Items.GREEN_CONCRETE && itemStack.getNbt().getBoolean("Ready")) {
                    isReady.put(player.getUuid(), false);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("NotReady", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Unready\",\"italic\": false,\"color\": \"red\"}");

                    ItemStack notReady = new ItemStack(Items.RED_CONCRETE);
                    notReady.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, notReady);

                    player.playSound(SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5f, 0.5f);

                    server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.unready", player.getName().getString(), isReady.size(), server.getPlayerManager().getPlayerList().size()).formatted(Formatting.GREEN), false);
                }

                if (itemStack.getItem() == Items.RECOVERY_COMPASS && itemStack.getNbt().getBoolean("Hunter")) {
                    player.getItemCooldownManager().set(itemStack.getItem(), 20);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Runner", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Runner\",\"italic\": false,\"color\": \"green\"}");

                    ItemStack chooseRunner = new ItemStack(Items.CLOCK);
                    chooseRunner.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, chooseRunner);

                    itemStack.addEnchantment(Enchantments.VANISHING_CURSE, 1);

                    player.playSound(SoundEvents.ITEM_LODESTONE_COMPASS_LOCK, SoundCategory.PLAYERS, 0.5f, 1f);

                    player.getScoreboard().clearTeam(player.getName().getString());
                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("hunters"));

                    server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.hunter", Text.literal(player.getName().getString()).formatted(Formatting.WHITE)).formatted(Formatting.RED), false);
                }

                if (itemStack.getItem() == Items.CLOCK && itemStack.getNbt().getBoolean("Runner")) {
                    player.getItemCooldownManager().set(itemStack.getItem(), 20);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Hunter", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Hunter\",\"italic\": false,\"color\": \"aqua\"}");

                    ItemStack chooseHunter = new ItemStack(Items.RECOVERY_COMPASS);
                    chooseHunter.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, chooseHunter);

                    itemStack.addEnchantment(Enchantments.VANISHING_CURSE, 1);

                    player.playSound(SoundEvents.ENTITY_ENDER_EYE_LAUNCH, SoundCategory.PLAYERS, 0.5f, 1f);

                    player.getScoreboard().clearTeam(player.getName().getString());
                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("runners"));

                    server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.runner", Text.literal(player.getName().getString()).formatted(Formatting.WHITE)).formatted(Formatting.GREEN), false);

                    if (PlayerDataApi.getGlobalDataFor((ServerPlayerEntity) player, allowBedExplosions) == NbtByte.ZERO) {
                        ALLOW_BED_EXPLOSIONS.set(false);
                    } else {
                        ALLOW_BED_EXPLOSIONS.set(true);
                    }

                    if (PlayerDataApi.getGlobalDataFor((ServerPlayerEntity) player, allowLavaPvpInNether) == NbtByte.ZERO) {
                        ALLOW_LAVA_PVP_IN_THE_NETHER.set(false);
                    } else {
                        ALLOW_LAVA_PVP_IN_THE_NETHER.set(true);
                    }

                    server.getPlayerManager().broadcast(Text.translatable("manhunt.chat.preference.runner", Text.literal(player.getName().getString()).formatted(Formatting.WHITE)).formatted(Formatting.GREEN), false);
                }

                if (itemStack.getItem() == Items.COMPARATOR && itemStack.getNbt().getBoolean("Settings")) {
                    settingsGui((ServerPlayerEntity) player);
                }

                if (itemStack.getItem() == Items.REPEATER && itemStack.getNbt().getBoolean("Preferences")) {
                    if (Boolean.parseBoolean(CHANGEABLE_PREFERENCES.get())) {
                        preferencesGui((ServerPlayerEntity) player);
                    } else {
                        player.sendMessage(Text.translatable("manhunt.chat.notallowed").formatted(Formatting.RED));
                    }
                }
            }
        }

        if (gameState == PLAYING) {
            if (Boolean.parseBoolean(TRACKER_UPDATE_METHOD.get()) && itemStack.getNbt() != null && itemStack.getNbt().getBoolean("Tracker") && !player.isSpectator() && player.isTeamPlayer(player.getScoreboard().getTeam("hunters")) && !player.getItemCooldownManager().isCoolingDown(itemStack.getItem())) {
                player.getItemCooldownManager().set(itemStack.getItem(), 20);
                if (!itemStack.getNbt().contains("Info")) {
                    itemStack.getNbt().put("Info", new NbtCompound());
                }

                NbtCompound info = itemStack.getNbt().getCompound("Info");

                if (!info.contains("Name", NbtElement.STRING_TYPE) && !allRunners.isEmpty()) {
                    info.putString("Name", allRunners.get(0).getName().getString());
                }

                ServerPlayerEntity trackedPlayer = player.getServer().getPlayerManager().getPlayer(info.getString("Name"));

                if (trackedPlayer != null) {
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.1f, 1f);
                    updateCompass((ServerPlayerEntity) player, itemStack.getNbt(), trackedPlayer);
                }
            }

            if (!Boolean.parseBoolean(ALLOW_BED_EXPLOSIONS.get())) {
                if ((player.getWorld().getRegistryKey() == theNetherRegistryKey || player.getWorld().getRegistryKey() == theEndRegistryKey) && itemStack.getItem().getName().getString().contains("bed")) {
                    if (player.isTeamPlayer(player.getScoreboard().getTeam("hunters"))) {
                        for (String playerName : player.getScoreboard().getTeam("runners").getPlayerList()) {
                            if (player.distanceTo(player.getServer().getPlayerManager().getPlayer(playerName)) <= 9) {
                                player.sendMessage(Text.translatable("manhunt.chat.allowbedexplosions").formatted(Formatting.RED), false);
                                return TypedActionResult.fail(itemStack);
                            }
                        }
                    } else {
                        for (String playerName : player.getScoreboard().getTeam("hunters").getPlayerList()) {
                            if (player.distanceTo(player.getServer().getPlayerManager().getPlayer(playerName)) <= 9) {
                                player.sendMessage(Text.translatable("manhunt.chat.allowlavapvpinthenether").formatted(Formatting.RED), false);
                                return TypedActionResult.fail(itemStack);
                            }
                        }
                    }

                }
            }
            if (!Boolean.parseBoolean(ALLOW_LAVA_PVP_IN_THE_NETHER.get())) {
                if (player.getWorld().getRegistryKey() == theNetherRegistryKey && itemStack.getItem() == Items.LAVA_BUCKET) {
                    if (player.isTeamPlayer(player.getScoreboard().getTeam("hunters"))) {
                        for (String playerName : player.getScoreboard().getTeam("runners").getPlayerList()) {
                            if (player.distanceTo(player.getServer().getPlayerManager().getPlayer(playerName)) <= 9) {
                                return TypedActionResult.fail(itemStack);
                            }
                        }
                    } else {
                        for (String playerName : player.getScoreboard().getTeam("hunters").getPlayerList()) {
                            if (player.distanceTo(player.getServer().getPlayerManager().getPlayer(playerName)) <= 9) {
                                return TypedActionResult.fail(itemStack);
                            }
                        }
                    }
                    player.sendMessage(Text.translatable("manhunt.chat.allowlavapvpinthenether").formatted(Formatting.RED), false);
                }
            }
        }

        return TypedActionResult.pass(itemStack);
    }

    public static void playerRespawn(ServerPlayerEntity newPlayer) {
        Scoreboard scoreboard = newPlayer.getScoreboard();
        if (newPlayer.isTeamPlayer(scoreboard.getTeam("runners"))) {
            PlayerDataApi.setGlobalDataFor(newPlayer, isRunner, NbtByte.ONE);
        }
        if (!newPlayer.isTeamPlayer(scoreboard.getTeam("hunters"))) {
            scoreboard.clearTeam(newPlayer.getName().getString());
            scoreboard.addScoreHolderToTeam(newPlayer.getName().getString(), scoreboard.getTeam("hunters"));
        }
        updateGameMode(newPlayer);
    }

    private static boolean doesNotHaveItem(PlayerEntity player, Item item, String nbtBoolean) {
        boolean bool = false;
        for (ItemStack itemStack : player.getInventory().main) {
            if (itemStack.getItem() == item && itemStack.getNbt() != null && itemStack.getNbt().getBoolean("Remove") && itemStack.getNbt().getBoolean(nbtBoolean)) {
                bool = true;
                break;
            }
        }

        if (player.playerScreenHandler.getCursorStack().getNbt() != null && player.playerScreenHandler.getCursorStack().getNbt().getBoolean(nbtBoolean)) {
            bool = true;
        } else if (player.getOffHandStack().hasNbt() && player.getOffHandStack().getNbt().getBoolean("Remove") && player.getOffHandStack().getNbt().getBoolean(nbtBoolean)) {
            bool = true;
        }
        return !bool;
    }

    private static void settingsGui(ServerPlayerEntity player) {
        if (settingIndex.isEmpty()) {
            settingSlot = 0;

            for (Setting setting : ConfigBuilder.settings) {
                settingIndex.put(setting, settingSlot);
                settingSlot++;
            }
        }

        SimpleGui settingsGui;
        if (settingSlot <= 8) {
            settingsGui = new SimpleGui(ScreenHandlerType.GENERIC_9X1, player, false);
        } else if (settingSlot <= 17) {
            settingsGui = new SimpleGui(ScreenHandlerType.GENERIC_9X2, player, false);
        } else {
            settingsGui = new SimpleGui(ScreenHandlerType.GENERIC_9X3, player, false);
        }

        settingsGui.setTitle(Text.translatable("manhunt.settings"));

        changeSetting(player, settingsGui, PRELOADING, Items.GRASS_BLOCK, SoundEvents.BLOCK_GRASS_PLACE);
        changeSetting(player, settingsGui, SET_MOTD, Items.DIRT, SoundEvents.BLOCK_GRAVEL_PLACE);
        changeSetting(player, settingsGui, RUNNER_VOTING, Items.BELL, SoundEvents.BLOCK_BELL_USE);
        changeSetting(player, settingsGui, VOTES_PER_PLAYER, Items.PAPER, SoundEvents.ITEM_BOOK_PUT);
        changeSetting(player, settingsGui, TOP_VOTED_RUNS, Items.FEATHER, SoundEvents.ENTITY_GENERIC_WIND_BURST);
        changeSetting(player, settingsGui, VOTE_PLACES, Items.GOLD_BLOCK, SoundEvents.ITEM_ARMOR_EQUIP_GOLD);
        changeSetting(player, settingsGui, RESET_SECONDS, Items.BARRIER, SoundEvents.BLOCK_BARREL_CLOSE);
        changeSetting(player, settingsGui, AUTO_RESET, Items.CHEST, SoundEvents.BLOCK_CHEST_CLOSE);
        changeSetting(player, settingsGui, AUTO_START, Items.ENDER_CHEST, SoundEvents.BLOCK_ENDER_CHEST_OPEN);
        changeSetting(player, settingsGui, SET_ROLES, Items.FLETCHING_TABLE, SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER);
        changeSetting(player, settingsGui, HUNTER_FREEZE_SECONDS, Items.ICE, SoundEvents.BLOCK_GLASS_BREAK);
        changeSetting(player, settingsGui, TIME_LIMIT_MINUTES, Items.CLOCK, SoundEvents.ENTITY_FISHING_BOBBER_THROW);
        changeSetting(player, settingsGui, TRACKER_UPDATE_METHOD, Items.COMPASS, SoundEvents.ITEM_LODESTONE_COMPASS_LOCK);
        changeSetting(player, settingsGui, SHOW_TEAM_COLOR, Items.LEATHER_CHESTPLATE, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER);
        changeSetting(player, settingsGui, WORLD_DIFFICULTY, Items.CREEPER_HEAD, SoundEvents.ENTITY_CREEPER_HURT);
        changeSetting(player, settingsGui, WORLD_BORDER_BLOCKS, Items.STRUCTURE_VOID, SoundEvents.BLOCK_DEEPSLATE_BREAK);
        changeSetting(player, settingsGui, ALLOW_BED_EXPLOSIONS, Items.RED_BED, SoundEvents.ENTITY_GENERIC_EXPLODE);
        changeSetting(player, settingsGui, ALLOW_LAVA_PVP_IN_THE_NETHER, Items.LAVA_BUCKET, SoundEvents.ITEM_BUCKET_FILL_LAVA);
        changeSetting(player, settingsGui, CHANGEABLE_PREFERENCES, Items.BONE, SoundEvents.BLOCK_BONE_BLOCK_BREAK);
        changeSetting(player, settingsGui, SHOW_WINNER_TITLE, Items.OAK_SIGN, SoundEvents.BLOCK_WOOD_PLACE);
        changeSetting(player, settingsGui, MANHUNT_SOUNDS_VOLUME, Items.PLAYER_HEAD, SoundEvents.ENTITY_PLAYER_BURP);
        changeSetting(player, settingsGui, SHOW_SETTINGS_AT_START, Items.IRON_BLOCK, SoundEvents.BLOCK_METAL_PLACE);
        changeSetting(player, settingsGui, SHOW_DURATION_ON_WIN, Items.GRAY_WOOL, SoundEvents.BLOCK_WOOL_BREAK);
        changeSetting(player, settingsGui, SHOW_SEED_ON_WIN, Items.WHEAT_SEEDS, SoundEvents.BLOCK_GRASS_BREAK);
        settingsGui.open();
    }

    private static void preferencesGui(ServerPlayerEntity player) {
        SimpleGui preferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X3, player, false);
        preferencesGui.setTitle(Text.of("Preferences"));
        List<Text> personalLore = new ArrayList<>();
        personalLore.add(Text.translatable("manhunt.lore.personalpreferences").formatted(Formatting.GRAY));
        preferencesGui.setSlot(11, new GuiElementBuilder(Items.PAPER)
                .setName(Text.translatable("manhunt.personalpreferences"))
                .setLore(personalLore)
                .setCallback(() -> {
                    personalPreferencesGui(player);
                    player.playSound(SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.MASTER, 1f, 1f);
                })
        );
        List<Text> runnerLore = new ArrayList<>();
        runnerLore.add(Text.translatable("manhunt.lore.runnerpreferences").formatted(Formatting.GRAY));
        preferencesGui.setSlot(15, new GuiElementBuilder(Items.FEATHER)
                .setName(Text.translatable("manhunt.runnerpreferences"))
                .setLore(runnerLore)
                .setCallback(() -> {
                    runnerPreferencesGui(player);
                    player.playSound(SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.MASTER, 0.2f, 1f);
                })
        );
        preferencesGui.open();
    }

    private static void personalPreferencesGui(ServerPlayerEntity player) {
        if (personalPreferencesIndex.isEmpty()) {
            personalPreferencesSlot = 0;

            for (Identifier preference : personalPreferences) {
                personalPreferencesIndex.put(preference, personalPreferencesSlot);
                personalPreferencesSlot++;
            }
        }

        SimpleGui personalPreferencesGui;
        if (personalPreferencesSlot <= 8) {
            personalPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X1, player, false);
        } else if (personalPreferencesSlot <= 17) {
            personalPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X2, player, false);
        } else {
            personalPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X3, player, false);
        }

        personalPreferencesGui.setTitle(Text.translatable("manhunt.personalpreferences"));

        changePreference(player, personalPreferencesGui, showWinnerTitle, Items.OAK_SIGN, SoundEvents.BLOCK_WOOD_PLACE);
        changePreference(player, personalPreferencesGui, manhuntSoundsVolume, Items.PLAYER_HEAD, SoundEvents.ENTITY_PLAYER_BURP);
        changePreference(player, personalPreferencesGui, showSettingsAtStart, Items.IRON_BLOCK, SoundEvents.BLOCK_METAL_PLACE);
        changePreference(player, personalPreferencesGui, showDurationOnWin, Items.GRAY_WOOL, SoundEvents.BLOCK_WOOL_BREAK);
        changePreference(player, personalPreferencesGui, showSeedOnWin, Items.WHEAT_SEEDS, SoundEvents.BLOCK_GRASS_BREAK);
        personalPreferencesGui.open();
    }

    private static void runnerPreferencesGui(ServerPlayerEntity player) {
        if (runnerPreferencesIndex.isEmpty()) {
            runnerPreferencesSlot = 0;

            for (Identifier preference : runnerPreferences) {
                runnerPreferencesIndex.put(preference, runnerPreferencesSlot);
                runnerPreferencesSlot++;
            }
        }

        SimpleGui runnerPreferencesGui;
        if (runnerPreferencesSlot <= 8) {
            runnerPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X1, player, false);
        } else if (runnerPreferencesSlot <= 17) {
            runnerPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X2, player, false);
        } else {
            runnerPreferencesGui = new SimpleGui(ScreenHandlerType.GENERIC_9X3, player, false);
        }

        runnerPreferencesGui.setTitle(Text.translatable("manhunt.runnerpreferences"));

        changePreference(player, runnerPreferencesGui, allowBedExplosions, Items.RED_BED, SoundEvents.ENTITY_GENERIC_EXPLODE);
        changePreference(player, runnerPreferencesGui, allowLavaPvpInNether, Items.LAVA_BUCKET, SoundEvents.ITEM_BUCKET_FILL_LAVA);
        runnerPreferencesGui.open();
    }

    private static void changeSetting(ServerPlayerEntity player, SimpleGui gui, Setting setting, Item item, SoundEvent sound) {
        List<ServerPlayerEntity> playerList = player.getServer().getPlayerManager().getPlayerList();

        String name = setting.getName().toLowerCase();

        List<Text> loreList = new ArrayList<>();
        loreList.add(Text.translatable("manhunt.lore." + name).formatted(Formatting.GRAY));

        int slot = settingIndex.get(setting) - 1;

        if (setting.getDefaultValue() instanceof Boolean) {
            if (Boolean.parseBoolean(setting.get())) {
                loreList.add(Text.translatable("manhunt.enabled").formatted(Formatting.GREEN));
            } else {
                loreList.add(Text.translatable("manhunt.disabled").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(Text.translatable("manhunt." + name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (!Boolean.parseBoolean(setting.get())) {
                            setting.set(true);
                            player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)).formatted(Formatting.GRAY), false);
                            if (name.equals("allowbedexplosions")) {
                                player.playSound(sound, SoundCategory.MASTER, 0.2F, 1);
                            } else {
                                player.playSound(sound, SoundCategory.MASTER, 1, 1);
                            }
                        } else {
                            setting.set(false);
                            player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.translatable("manhunt.disabled").formatted(Formatting.RED)).formatted(Formatting.GRAY), false);
                            if (name.equals("allowbedexplosions")) {
                                player.playSound(sound, SoundCategory.MASTER, 0.2F, 0.5F);
                            } else {
                                player.playSound(sound, SoundCategory.MASTER, 1, 0.5F);
                            }

                            if (name.equals("runnervoting")) {
                                AUTO_START.set(false);
                                votedRunners.clear();
                                topVoted.clear();

                                for (ServerPlayerEntity serverPlayer : player.getServer().getPlayerManager().getPlayerList()) {
                                    PlayerDataApi.setGlobalDataFor(serverPlayer, runnerVotes, NbtInt.of(0));
                                    PlayerDataApi.setGlobalDataFor(serverPlayer, votesList, new NbtList());
                                    PlayerDataApi.setGlobalDataFor(serverPlayer, votesLeft, NbtInt.of(Integer.parseInt(VOTES_PER_PLAYER.get())));
                                }
                            }
                        }
                        changeSetting(player, gui, setting, item, sound);
                    })
            );
        }
        if (setting.getDefaultValue() instanceof String) {
            String value1 = "";
            String value2 = "";
            String value3= "";

            if (name.equals("setroles")) {
                value1 = "Free Select";
                value2 = "All Hunters";
                value3 = "All Runners";
            }

            if (name.equals("trackerupdatemethod")) {
                value1 = "Manual Click";
                value2 = "Basic Update";
                value3 = "Smart Rate";
            }

            if (name.equals("worlddifficulty")) {
                value1 = "Easy";
                value2 = "Normal";
                value3 = "Hard";
            }

            if (setting.get().equals(value1)) {
                loreList.add(Text.translatable("manhunt." + name + "." + value1.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.GREEN));
            } else if (setting.get().equals(value2)) {
                loreList.add(Text.translatable("manhunt." + name + "." + value2.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.YELLOW));
            } else {
                loreList.add(Text.translatable("manhunt." + name + "." + value3.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.RED));
            }

            String finalValue1 = value1;
            String finalValue2 = value2;
            String finalValue3 = value3;
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(Text.translatable("manhunt." + name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (setting.get().equals(finalValue1)) {
                            setting.set(finalValue2);
                            player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.translatable("manhunt." + name + "." + finalValue2.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.YELLOW)).formatted(Formatting.GRAY), false);
                            player.playSound(sound, SoundCategory.MASTER, 1, 1);
                            if (name.equals("setroles")) {
                                for (ServerPlayerEntity serverPlayer : playerList) {
                                    player.getScoreboard().clearTeam(player.getName().getString());
                                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
                                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("hunters"));
                                    serverPlayer.getInventory().clear();
                                }
                            }
                        } else if (setting.get().equals(finalValue2)) {
                            setting.set(finalValue3);
                            player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.translatable("manhunt." + name + "." + finalValue3.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.RED)).formatted(Formatting.GRAY), false);
                            player.playSound(sound, SoundCategory.MASTER, 1, 1.5F);
                            if (name.equals("setroles")) {
                                for (ServerPlayerEntity serverPlayer : playerList) {
                                    player.getScoreboard().clearTeam(player.getName().getString());
                                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
                                    player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("runners"));
                                    serverPlayer.getInventory().clear();
                                }
                            }
                        } else {
                            setting.set(finalValue1);
                            player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.translatable("manhunt." + name + "." + finalValue1.toLowerCase().replaceAll("\\s+","")).formatted(Formatting.GREEN)).formatted(Formatting.GRAY), false);
                            player.playSound(sound, SoundCategory.MASTER, 1, 0.5F);
                        }
                        changeSetting(player, gui, setting, item, sound);
                    })
            );
        }
        if (setting.getDefaultValue() instanceof Integer) {
            String dataType = switch (name) {
                case "hunterfreezeseconds" -> "seconds";
                case "timelimitminutes" -> "minutes";
                case "votesperplayer" -> "votes";
                case "topvotedruns" -> "runs";
                case "voteplaces" -> "places";
                case "manhuntsoundsvolume" -> "percent";
                default -> "blocks";
            };

            if (Integer.parseInt(setting.get()) == 0) {
                loreList.add(Text.translatable("manhunt.lore.setting", setting.get(), dataType, Text.literal("(disabled)")).formatted(Formatting.RED));
            } else if (Integer.parseInt(setting.get()) == 59999968 && name.equals("worldborderblocks")) {
                loreList.add(Text.translatable("manhunt.lore.setting", setting.get(), dataType, Text.literal("(default)")).formatted(Formatting.RED));
            } else if (dataType.equals("votes") || dataType.equals("runs") || dataType.equals("places")) {
                loreList.add(Text.translatable("manhunt.lore.setting", setting.get(), dataType, "").formatted(Formatting.GREEN));
            } else {
                loreList.add(Text.translatable("manhunt.lore.setting", setting.get(), dataType, "").formatted(Formatting.GREEN));
            }

            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(Text.translatable("manhunt." + name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        AnvilInputGui inputGui = new AnvilInputGui(player, false) {
                            @Override
                            public void onInput(String input) {
                                this.setSlot(2, new GuiElementBuilder(Items.PAPER)
                                        .setName(Text.literal(input).formatted(Formatting.ITALIC))
                                        .setCallback(() -> {
                                            try {
                                                int value = Integer.parseInt(input);

                                                setting.set(value);
                                                if (Integer.parseInt(setting.get()) == 0) {
                                                    player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.literal(setting.get() + " " + dataType + " (disabled)").formatted(Formatting.RED)).formatted(Formatting.GRAY), false);
                                                    player.playSound(sound, SoundCategory.MASTER, 1, 0.5F);
                                                } else {
                                                    player.getServer().getPlayerManager().broadcast(Text.translatable("manhunt.chat.setting.set", Text.translatable("manhunt." + name).formatted(Formatting.WHITE), Text.literal(setting.get() + " " + dataType).formatted(Formatting.GREEN)).formatted(Formatting.GRAY), false);
                                                    player.playSound(sound, SoundCategory.MASTER, 1, 1);
                                                }
                                            } catch (NumberFormatException e) {
                                                player.sendMessage(Text.translatable("manhunt.chat.invalid").formatted(Formatting.RED));
                                            }
                                            settingsGui(player);
                                        })
                                );
                            }
                        };
                        inputGui.setTitle(Text.translatable("manhunt.entervalue"));
                        inputGui.setSlot(0, new GuiElementBuilder(Items.PAPER));
                        inputGui.setDefaultInputValue("");
                        inputGui.open();
                    })
            );
        }
    }

    private static void changePreference(ServerPlayerEntity player, SimpleGui gui, Identifier preference, Item item, SoundEvent sound) {
        int slot = 0;

        for (Identifier identifier : personalPreferences) {
            if (preference == identifier) {
                slot = personalPreferencesIndex.get(preference);
                break;
            }
        }
        for (Identifier identifier : runnerPreferences) {
            if (preference == identifier) {
                slot = runnerPreferencesIndex.get(preference);
                break;
            }
        }

        String name = preference.getPath().replaceAll("_","");

        List<Text> loreList = new ArrayList<>();
        loreList.add(Text.translatable("manhunt.lore." + name).formatted(Formatting.GRAY));
        if (PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ONE) {
            loreList.add(Text.translatable("manhunt.enabled").formatted(Formatting.GREEN));
        } else if (PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ZERO) {
            loreList.add(Text.translatable("manhunt.disabled").formatted(Formatting.RED));
        } else {
            int value = Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, preference)));
            if (value == 0) {
                loreList.add(Text.literal("0% (muted)").formatted(Formatting.RED));
            } else {
                loreList.add(Text.literal(PlayerDataApi.getGlobalDataFor(player, preference) + "%").formatted(Formatting.GREEN));
            }
        }
        if (PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ONE || PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ZERO) {
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(Text.translatable("manhunt." + name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ZERO) {
                            PlayerDataApi.setGlobalDataFor(player, preference, NbtByte.ONE);
                            if (preference == allowBedExplosions) {
                                ALLOW_BED_EXPLOSIONS.set(true);
                                player.playSound(sound, SoundCategory.MASTER, 0.2F, 1);
                            } else {
                                player.playSound(sound, SoundCategory.MASTER, 1, 1);
                                if (preference == allowLavaPvpInNether) {
                                    ALLOW_LAVA_PVP_IN_THE_NETHER.set(true);
                                }
                            }
                        } else if (PlayerDataApi.getGlobalDataFor(player, preference) == NbtByte.ONE) {
                            PlayerDataApi.setGlobalDataFor(player, preference, NbtByte.ZERO);
                            if (preference == allowBedExplosions) {
                                ALLOW_BED_EXPLOSIONS.set(false);
                                player.playSound(sound, SoundCategory.MASTER, 0.2F, 0.5F);
                            } else {
                                player.playSound(sound, SoundCategory.MASTER, 1, 0.5F);
                                if (preference == allowLavaPvpInNether) {
                                    ALLOW_LAVA_PVP_IN_THE_NETHER.set(false);
                                }
                            }
                        }
                        changePreference(player, gui, preference, item, sound);
                    })
            );
        } else {
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(Text.translatable("manhunt." + name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        AnvilInputGui inputGui = new AnvilInputGui(player, false) {
                            @Override
                            public void onInput(String input) {
                                this.setSlot(2, new GuiElementBuilder(Items.PAPER)
                                        .setName(Text.literal(input).formatted(Formatting.ITALIC))
                                        .setCallback(() -> {
                                            try {
                                                int value = Integer.parseInt(input);
                                                if (value == 0) {
                                                    player.playSound(sound, SoundCategory.MASTER, 0.2f, 0.5f);
                                                } else if (value >= 100) {
                                                    player.playSound(sound, SoundCategory.MASTER, 0.2f, 2f);
                                                    value = 100;
                                                } else {
                                                    player.playSound(sound, SoundCategory.MASTER, 0.2f, 1f);
                                                }
                                                PlayerDataApi.setGlobalDataFor(player, preference, NbtInt.of(value));
                                            } catch (NumberFormatException e) {
                                                player.sendMessage(Text.translatable("manhunt.chat.invalidinput").formatted(Formatting.RED));
                                            }
                                            personalPreferencesGui(player);
                                        })
                                );
                            }
                        };
                        inputGui.setTitle(Text.translatable("manhunt.entervalue"));
                        inputGui.setSlot(0, new GuiElementBuilder(Items.PAPER));
                        inputGui.setDefaultInputValue("");
                        inputGui.open();
                    })
            );
        }
    }

    public static ManhuntState gameState;

    public static void manhuntState(ManhuntState newState, MinecraftServer server) {
        if (Boolean.parseBoolean(SET_MOTD.get())) {
            server.setMotd(newState.getColor() + "[" + newState.getMotd() + "]§f Minecraft MANHUNT");
        }
        gameState = newState;
    }

    public static void startGame(MinecraftServer server) {
        server.setFlightEnabled(true);

        manhuntState(PLAYING, server);

        var world = server.getWorld(overworldRegistryKey);

        for (ServerWorld serverWorld : server.getWorlds()) {
            ((ServerWorldInterface) serverWorld).getWorldProperties().setTime(0);
            serverWorld.setTimeOfDay(0);
            serverWorld.resetWeather();
        }

        world.getGameRules().get(GameRules.ANNOUNCE_ADVANCEMENTS).set(true, server);
        world.getGameRules().get(GameRules.DO_FIRE_TICK).set(true, server);
        world.getGameRules().get(GameRules.DO_INSOMNIA).set(true, server);
        world.getGameRules().get(GameRules.DO_MOB_LOOT).set(true, server);
        world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(true, server);
        world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
        world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(true, server);
        world.getGameRules().get(GameRules.FALL_DAMAGE).set(true, server);
        world.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(3, server);
        world.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(true, server);
        world.getGameRules().get(GameRules.SPAWN_RADIUS).set(5, server);
        world.getGameRules().get(GameRules.FALL_DAMAGE).set(true, server);

        Difficulty difficulty = Difficulty.EASY;

        if (WORLD_DIFFICULTY.get().equals("Normal")) {
            difficulty = Difficulty.NORMAL;
        } else if (WORLD_DIFFICULTY.get().equals("Hard")) {
            difficulty = Difficulty.HARD;
        }

        server.setDifficulty(difficulty, true);

        server.setPvpEnabled(true);

        if (Boolean.parseBoolean(SHOW_TEAM_COLOR.get())) {
            server.getScoreboard().getTeam("hunters").setColor(Formatting.RESET);
            server.getScoreboard().getTeam("runners").setColor(Formatting.RESET);
        }

        if (Boolean.parseBoolean(SHOW_TEAM_COLOR.get())) {
            server.getScoreboard().getTeam("hunters").setColor(Formatting.RED);
            server.getScoreboard().getTeam("runners").setColor(Formatting.GREEN);
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            setPlayerSpawnXYZ(server.getWorld(overworldRegistryKey), player);

            Stats.MINED.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.CRAFTED.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.USED.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.BROKEN.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.PICKED_UP.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.DROPPED.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.KILLED.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.KILLED_BY.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));
            Stats.CUSTOM.forEach(stat -> player.resetStat(stat.getType().getOrCreateStat(stat.getValue())));

            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            double playerX = Double.parseDouble(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnX)));
            double playerY = Double.parseDouble(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnY)));
            double playerZ = Double.parseDouble(String.valueOf(PlayerDataApi.getGlobalDataFor(player, playerSpawnZ)));
            player.teleport(world, playerX, playerY, playerZ, 0, 0);
            player.setSpawnPoint(overworldRegistryKey, new BlockPos((int) playerX, (int) playerY, (int) playerZ), 0, true, false);
            player.clearStatusEffects();
            player.getInventory().clear();
            player.setFireTicks(0);
            player.setOnFire(false);
            player.setHealth(20);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5);
            player.getHungerManager().setExhaustion(0);

            updateGameMode(player);

            if (player.isTeamPlayer(player.getScoreboard().getTeam("players"))) {
                player.getScoreboard().removeScoreHolderFromTeam(player.getName().getString(), player.getScoreboard().getTeam("players"));
            }

            if (!(PlayerDataApi.getGlobalDataFor(player, manhuntSoundsVolume) == NbtInt.of(0))) {
                float volume = (float) Integer.parseInt(String.valueOf(PlayerDataApi.getGlobalDataFor(player, manhuntSoundsVolume))) / 100;
                if (volume >= 0.2f) {
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.BLOCKS, volume / 2, 1.5f);
                }
            }

            if (Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) != 0) {
                if (player.isTeamPlayer(player.getScoreboard().getTeam("hunters"))) {
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) * 20, 255, false, true));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) * 20, 248, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, (Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) - 1) * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) * 20, 255, false, false));
                }
            }

            if (PlayerDataApi.getGlobalDataFor(player, showWinnerTitle) == NbtByte.ONE) {
                player.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable("manhunt.title.name")));
                player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("manhunt.title.start").formatted(Formatting.GRAY)));
            }

            if (PlayerDataApi.getGlobalDataFor(player, showSettingsAtStart) == NbtByte.ONE) {
                showSettings(player);
            }

            if (player.isTeamPlayer(player.getScoreboard().getTeam("hunters")) && PlayerDataApi.getGlobalDataFor(player, isRunner) == NbtByte.ONE) {
                PlayerDataApi.setGlobalDataFor(player, isRunner, NbtByte.ZERO);
            }
        }
    }

    public static void updateGameMode(ServerPlayerEntity player) {
        if (gameState == PREGAME) {
            player.changeGameMode(GameMode.ADVENTURE);
        } else if (gameState == PLAYING) {
            player.changeGameMode(GameMode.SURVIVAL);
        } else {
            player.changeGameMode(GameMode.SPECTATOR);
        }
    }

    // Thanks to https://github.com/Ivan-Khar/manhunt-fabricated for the updateCompass method

    public static void updateCompass(ServerPlayerEntity player, NbtCompound nbt, ServerPlayerEntity trackedPlayer) {
        nbt.remove("LodestonePos");
        nbt.remove("LodestoneDimension");

        nbt.put("Info", new NbtCompound());
        if (trackedPlayer.getScoreboardTeam() != null && trackedPlayer.isTeamPlayer(trackedPlayer.getScoreboard().getTeam("runners"))) {
            NbtCompound playerTag = trackedPlayer.writeNbt(new NbtCompound());
            NbtList positions = playerTag.getList("Positions", 10);
            int i;
            for (i = 0; i < positions.size(); ++i) {
                NbtCompound compound = positions.getCompound(i);
                if (Objects.equals(compound.getString("LodestoneDimension"), player.writeNbt(new NbtCompound()).getString("Dimension"))) {
                    nbt.copyFrom(compound);
                    break;
                }
            }

            NbtCompound info = nbt.getCompound("Info");
            info.putLong("LastUpdateTime", player.getWorld().getTime());
            info.putString("Name", trackedPlayer.getName().getString());
            info.putString("Dimension", playerTag.getString("Dimension"));
        }
    }

    public static void resetGame(MinecraftServer server) {
        manhuntState(PREGAME, server);

        if (!allRunners.isEmpty()) {
            for (ServerPlayerEntity player : allRunners) {
                PlayerDataApi.setGlobalDataFor(player, isRunner, NbtByte.ONE);
            }
        }

        new ManhuntWorldModule().resetWorlds(server);
    }

    public static void resetGameIfAuto(MinecraftServer server) {
        if (Boolean.parseBoolean(AUTO_RESET.get())) {
            resetGame(server);
        }
    }

    public static void unloadWorld(MinecraftServer server, ServerWorld world) {
        new ManhuntWorldModule().onWorldUnload(server, world);
    }

    public static void setPlayerSpawnXYZ(ServerWorld world, ServerPlayerEntity player) {
        if (worldSpawnPos.equals(new BlockPos(0, 0, 0))) {
            worldSpawnPos = setupSpawn(world);
        }
        BlockPos blockPos = worldSpawnPos;
        long l;
        long m;
        int i = Math.max(0, world.getServer().getSpawnRadius(world));
        int j = MathHelper.floor(world.getWorldBorder().getDistanceInsideBorder(blockPos.getX(), blockPos.getZ()));
        if (j < i) {
            i = j;
        }
        if (j <= 1) {
            i = 1;
        }
        int k = (m = (l = i * 2L + 1) * l) > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)m;
        int n = k <= 16 ? k - 1 : 17;
        int o = Random.create().nextInt(k);
        for (int p = 0; p < k; ++p) {
            int q = (o + n * p) % k;
            int r = q % (i * 2 + 1);
            int s = q / (i * 2 + 1);
            BlockPos blockPos2 = findOverworldSpawn(world, blockPos.getX() + r - i, blockPos.getZ() + s - i);
            if (blockPos2 == null) continue;
            PlayerDataApi.setGlobalDataFor(player, playerSpawnX, NbtInt.of(blockPos2.getX()));
            PlayerDataApi.setGlobalDataFor(player, playerSpawnY, NbtInt.of(blockPos2.getY()));
            PlayerDataApi.setGlobalDataFor(player, playerSpawnZ, NbtInt.of(blockPos2.getZ()));
            break;
        }
    }

    @Nullable
    private static BlockPos findOverworldSpawn(ServerWorld world, int x, int z) {
        int i;
        boolean bl = world.getDimension().hasCeiling();
        WorldChunk worldChunk = world.getChunk(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z));
        i = bl ? world.getChunkManager().getChunkGenerator().getSpawnHeight(world) : worldChunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x & 0xF, z & 0xF);
        if (i < world.getBottomY()) {
            return null;
        }
        int j = worldChunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 0xF, z & 0xF);
        if (j <= i && j > worldChunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR, x & 0xF, z & 0xF)) {
            return null;
        }
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int k = i + 1; k >= world.getBottomY(); --k) {
            mutable.set(x, k, z);
            BlockState blockState = world.getBlockState(mutable);
            if (!blockState.getFluidState().isEmpty()) break;
            if (!Block.isFaceFullSquare(blockState.getCollisionShape(world, mutable), Direction.UP)) continue;
            return mutable.up().toImmutable();
        }
        return null;
    }

    public static void showSettings(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();

        if (Boolean.parseBoolean(RUNNER_VOTING.get())) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.runnervoting").formatted(Formatting.AQUA), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.runnervoting").formatted(Formatting.AQUA), Text.translatable("manhunt.disabled").formatted(Formatting.RED)));
        }

        if (SET_ROLES.get().equals("Free Select")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.setroles").formatted(Formatting.AQUA), Text.translatable("manhunt.setroles.freeselect").formatted(Formatting.GREEN)));
        } else if (SET_ROLES.get().equals("All Hunters")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.setroles").formatted(Formatting.AQUA), Text.translatable("manhunt.setroles.allhunters").formatted(Formatting.YELLOW)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.setroles").formatted(Formatting.AQUA), Text.translatable("manhunt.setroles.allrunners").formatted(Formatting.RED)));
        }

        if (Integer.parseInt(HUNTER_FREEZE_SECONDS.get()) == 0) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.hunterfreezeseconds").formatted(Formatting.AQUA), Text.literal("0 seconds (disabled)").formatted(Formatting.RED)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.hunterfreezeseconds").formatted(Formatting.AQUA), Text.literal(HUNTER_FREEZE_SECONDS.get() + " seconds").formatted(Formatting.GREEN)));
        }

        if (Integer.parseInt(TIME_LIMIT_MINUTES.get()) == 0) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.timelimitminutes").formatted(Formatting.AQUA), Text.literal("0 minutes (disabled)").formatted(Formatting.RED)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.timelimitminutes").formatted(Formatting.AQUA), Text.literal(TIME_LIMIT_MINUTES.get() + " minutes").formatted(Formatting.RED)));
        }

        if (Boolean.parseBoolean(TRACKER_UPDATE_METHOD.get())) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.trackerupdatemethod").formatted(Formatting.AQUA), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.trackerupdatemethod").formatted(Formatting.AQUA), Text.translatable("manhunt.disabled").formatted(Formatting.RED)));
        }
        if (TRACKER_UPDATE_METHOD.get().equals("Manual Click")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.trackerupdatemethod").formatted(Formatting.AQUA), Text.translatable("manhunt.trackerupdatemethod.manualclick").formatted(Formatting.GREEN)));
        } else if (TRACKER_UPDATE_METHOD.get().equals("Basic Update")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.trackerupdatemethod").formatted(Formatting.AQUA), Text.translatable("manhunt.trackerupdatemethod.basicupdate").formatted(Formatting.YELLOW)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.trackerupdatemethod").formatted(Formatting.AQUA), Text.translatable("manhunt.trackerupdatemethod.smartrate").formatted(Formatting.RED)));
        }

        if (Boolean.parseBoolean(SHOW_TEAM_COLOR.get())) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.showteamcolor").formatted(Formatting.AQUA), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.showteamcolor").formatted(Formatting.AQUA), Text.translatable("manhunt.disabled").formatted(Formatting.RED)));
        }

        if (WORLD_DIFFICULTY.get().equals("Easy")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.worlddifficulty").formatted(Formatting.AQUA), Text.translatable("manhunt.worlddifficulty.easy").formatted(Formatting.GREEN)));
        } else if (WORLD_DIFFICULTY.get().equals("Normal")) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.worlddifficulty").formatted(Formatting.AQUA), Text.translatable("manhunt.worlddifficulty.normal").formatted(Formatting.YELLOW)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.worlddifficulty").formatted(Formatting.AQUA), Text.translatable("manhunt.worlddifficulty.hard").formatted(Formatting.RED)));
        }

        if (Integer.parseInt(WORLD_BORDER_BLOCKS.get()) == 59999968) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.worldborderblocks").formatted(Formatting.AQUA), Text.literal("59999968 blocks (maximum)").formatted(Formatting.RED)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.worldborderblocks").formatted(Formatting.AQUA), Text.literal(WORLD_BORDER_BLOCKS.get() + " blocks").formatted(Formatting.RED)));
        }

        if (Boolean.parseBoolean(ALLOW_BED_EXPLOSIONS.get())) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.allowbedexplosions").formatted(Formatting.AQUA), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.allowbedexplosions").formatted(Formatting.AQUA), Text.translatable("manhunt.disabled").formatted(Formatting.RED)));
        }

        if (Boolean.parseBoolean(ALLOW_LAVA_PVP_IN_THE_NETHER.get())) {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.allowlavapvpinthenether").formatted(Formatting.AQUA), Text.translatable("manhunt.enabled").formatted(Formatting.GREEN)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.setting.is", Text.translatable("manhunt.allowlavapvpinthenether").formatted(Formatting.AQUA), Text.translatable("manhunt.disabled").formatted(Formatting.RED)));
        }

        int viewDistance = server.getPlayerManager().getViewDistance();

        if (viewDistance >= 10 && viewDistance <= 12) {
            player.sendMessage(Text.translatable("manhunt.chat.property.is", Text.literal("Render Distance").formatted(Formatting.AQUA), Text.literal(String.valueOf(viewDistance)).formatted(Formatting.GREEN)));
        } else if (viewDistance >= 13 && viewDistance <= 18) {
            player.sendMessage(Text.translatable("manhunt.chat.property.is", Text.literal("Render Distance").formatted(Formatting.AQUA), Text.literal(String.valueOf(viewDistance)).formatted(Formatting.YELLOW)));
        } else {
            player.sendMessage(Text.translatable("manhunt.chat.property.is", Text.literal("Render Distance").formatted(Formatting.AQUA), Text.literal(String.valueOf(viewDistance)).formatted(Formatting.RED)));
        }
    }

    public static BlockPos setupSpawn(ServerWorld world) {
        ServerChunkManager serverChunkManager = world.getChunkManager();
        ChunkPos chunkPos = new ChunkPos(serverChunkManager.getNoiseConfig().getMultiNoiseSampler().findBestSpawnPosition());
        int i = serverChunkManager.getChunkGenerator().getSpawnHeight(world);
        if (i < world.getBottomY()) {
            BlockPos blockPos = chunkPos.getStartPos();
            world.getTopY(Heightmap.Type.WORLD_SURFACE, blockPos.getX() + 8, blockPos.getZ() + 8);
        }
        BlockPos blockPos = chunkPos.getStartPos().add(8, i, 8);
        int j = 0;
        int k = 0;
        int l = 0;
        int m = -1;
        for (int o = 0; o < MathHelper.square(11); ++o) {
            if (j >= -5 && j <= 5 && k >= -5 && k <= 5 && (blockPos = SpawnLocating.findServerSpawnPoint(world, new ChunkPos(chunkPos.x + j, chunkPos.z + k))) != null) {
                break;
            }
            if (j == k || j < 0 && j == -k || j > 0 && j == 1 - k) {
                int p = l;
                l = -m;
                m = p;
            }
            j += l;
            k += m;
        }
        return blockPos;
    }

    public static void serverStop(MinecraftServer server) {
        RUNNER_VOTING.set(false);

        try {
            FileUtils.deleteDirectory(FabricLoader.getInstance().getConfigDir().resolve("chunky/tasks").toFile());
        } catch (IOException ignored) {
        }
    }
}
