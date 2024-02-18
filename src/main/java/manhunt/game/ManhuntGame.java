package manhunt.game;

import com.mojang.brigadier.CommandDispatcher;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import manhunt.Manhunt;
import manhunt.command.*;
import manhunt.config.Configs;
import manhunt.config.model.ConfigModel;
import manhunt.mixin.MinecraftServerAccessInterface;
import manhunt.util.MessageUtil;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
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
import java.util.function.Predicate;

import static manhunt.Manhunt.CONFIG_PATH;
import static manhunt.Manhunt.MOD_ID;

public class ManhuntGame {
    public static final Identifier LOBBY_WORLD_ID = new Identifier(MOD_ID, "lobby");
    public static final Identifier OVERWORLD_ID = new Identifier("manhunt", "overworld");
    public static final Identifier THE_NETHER_ID = new Identifier("manhunt", "the_nether");
    public static final Identifier THE_END_ID = new Identifier("manhunt", "the_end");
    public static RegistryKey<World> lobbyRegistryKey = RegistryKey.of(RegistryKeys.WORLD, LOBBY_WORLD_ID);
    public static RegistryKey<World> overworldRegistryKey = RegistryKey.of(RegistryKeys.WORLD, OVERWORLD_ID);
    public static RegistryKey<World> theNetherRegistryKey = RegistryKey.of(RegistryKeys.WORLD, THE_NETHER_ID);
    public static RegistryKey<World> theEndRegistryKey = RegistryKey.of(RegistryKeys.WORLD, THE_END_ID);
    public static final ConfigModel.Settings settings = Configs.configHandler.model().settings;
    public static List<ServerPlayerEntity> allPlayers;
    public static List<ServerPlayerEntity> allRunners;
    public static HashMap<UUID, Boolean> isReady = new HashMap<>();
    public static HashMap<UUID, String> currentRole = new HashMap<>();
    private static boolean paused;
    public static boolean isPaused() {
        return paused;
    }
    public static void setPaused(boolean paused) {
        ManhuntGame.paused = paused;
    }
    public final MinecraftServerAccessInterface serverAccessMixin;
    public static BlockPos worldSpawnPos;

    public ManhuntGame(MinecraftServerAccessInterface serverAccessMixin) {
        this.serverAccessMixin = serverAccessMixin;
    }

    public static void commandRegister(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
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
        ResetCommand.register(dispatcher);
    }

    // Thanks to https://gitlab.com/horrific-tweaks/bingo for the spawnStructure method

    private static void spawnStructure(MinecraftServer server) throws IOException {
        var lobbyIcebergNbt = NbtIo.readCompressed(ManhuntGame.class.getResourceAsStream("/manhunt/lobby/iceberg.nbt"), NbtSizeTracker.ofUnlimitedBytes());

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
        try {
            FileUtils.deleteDirectory(CONFIG_PATH.resolve("lang").toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        MessageUtil.copyLanguageFiles();

        Configs.configHandler.model().settings.worldSeed = RandomSeed.getSeed();
        Configs.configHandler.saveToDisk();

        new ManhuntWorldModule().loadWorlds(server);

        setPaused(false);

        manhuntState(ManhuntState.PREGAME, server);

        var difficulty = switch (settings.worldDifficulty) {
            case 2 -> Difficulty.NORMAL;
            case 3 -> Difficulty.HARD;
            default -> Difficulty.EASY;
        };

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

        scoreboard.addTeam("players");

        scoreboard.getTeam("players").setCollisionRule(AbstractTeam.CollisionRule.NEVER);

        scoreboard.addTeam("hunters");
        scoreboard.addTeam("runners");

        if (settings.teamColor) {
            scoreboard.getTeam("hunters").setColor(Formatting.RED);
            scoreboard.getTeam("runners").setColor(Formatting.GREEN);
        }

        try {
            spawnStructure(server);
        } catch (IOException e) {
            Manhunt.LOGGER.fatal("Failed to spawn Manhunt mod lobby");
        }
    }

    public static void serverTick(MinecraftServer server) {
        if (gameState == ManhuntState.PREGAME) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (hasItem(Items.RED_CONCRETE, player, "NotReady") && hasItem(Items.LIME_CONCRETE, player, "Ready") && settings.setRoles == 1) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("NotReady", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Unready\",\"italic\": false,\"color\": \"red\"}");

                    ItemStack itemStack = new ItemStack(Items.RED_CONCRETE);
                    itemStack.setNbt(nbt);

                    player.getInventory().setStack(0, itemStack);
                }

                if (hasItem(Items.RECOVERY_COMPASS, player, "Hunter") && settings.setRoles == 1) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Hunter", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Hunter\",\"italic\": false,\"color\": \"aqua\"}");

                    ItemStack itemStack = new ItemStack(Items.RECOVERY_COMPASS);
                    itemStack.setNbt(nbt);

                    player.getInventory().setStack(3, itemStack);
                }

                if (hasItem(Items.CLOCK, player, "Runner") && settings.setRoles == 1) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Runner", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Runner\",\"italic\": false,\"color\": \"gold\"}");

                    ItemStack itemStack = new ItemStack(Items.CLOCK);
                    itemStack.setNbt(nbt);

                    player.getInventory().setStack(5, itemStack);
                }

                if (hasItem(Items.COMPARATOR, player, "Settings")) {
                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Settings", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Settings\",\"italic\": false,\"color\": \"white\"}");

                    ItemStack itemStack = new ItemStack(Items.COMPARATOR);
                    itemStack.setNbt(nbt);

                    player.getInventory().setStack(8, itemStack);
                }

                if (player.getWorld() == server.getOverworld()) {
                    player.teleport(server.getWorld(lobbyRegistryKey), 0, 63, 5.5, PositionFlag.ROT, 0, 0);
                    player.clearStatusEffects();
                    player.getInventory().clear();
                    player.setFireTicks(0);
                    player.setOnFire(false);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.getHungerManager().setSaturationLevel(5);
                    player.getHungerManager().setExhaustion(0);
                    player.setExperienceLevel(0);
                    player.setExperiencePoints(0);
                    player.clearStatusEffects();
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, StatusEffectInstance.INFINITE, 255, false, false, false));

                    for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
                        AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                        for (String criteria : progress.getObtainedCriteria()) {
                            player.getAdvancementTracker().revokeCriterion(advancement, criteria);
                        }
                    }

                    updateGameMode(player);

                    if (!player.isTeamPlayer(server.getScoreboard().getTeam("players"))) {
                        player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), server.getScoreboard().getTeam("players"));
                    }

                    if (settings.setRoles == 3) {
                        currentRole.put(player.getUuid(), "runner");
                    }
                }
            }
        }

        if (gameState == ManhuntState.PLAYING) {
            if (settings.timeLimit != 0) {
                if (server.getWorld(overworldRegistryKey).getTime() % (20 * 60 * 60) / (20 * 60) >= settings.timeLimit) {
                    manhuntState(ManhuntState.POSTGAME, server);
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        MessageUtil.showTitle(player, "manhunt.title.hunters", "manhunt.title.timelimit");
                        player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.1f, 1f);
                    }
                }
            }
        }
    }

    public static void worldTick(ServerWorld world) {
        if (gameState == ManhuntState.PLAYING) {
            allPlayers = Manhunt.SERVER.getPlayerManager().getPlayerList();
            allRunners = new LinkedList<>();
            
            MinecraftServer server = Manhunt.SERVER;

            for (ServerPlayerEntity player : allPlayers) {
                if (player != null) {
                    if (player.isTeamPlayer(server.getScoreboard().getTeam("runners"))) {
                        allRunners.add(player);
                    }
                    if (!player.isTeamPlayer(server.getScoreboard().getTeam("hunters")) && !player.isTeamPlayer(server.getScoreboard().getTeam("runners"))) {
                        if (currentRole.get(player.getUuid()).equals("hunter")) {
                            server.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("hunters"));
                        } else {
                            server.getScoreboard().addScoreHolderToTeam(player.getName().getString(), player.getScoreboard().getTeam("runners"));
                        }
                    }
                }
            }
        }
    }

    public static void playerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();

        currentRole.put(player.getUuid(), "hunter");

        if (gameState == ManhuntState.PREGAME) {
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            player.teleport(server.getWorld(lobbyRegistryKey), 0, 63, 5.5, PositionFlag.ROT, 0, 0);
            player.clearStatusEffects();
            player.getInventory().clear();
            player.setFireTicks(0);
            player.setOnFire(false);
            player.setHealth(20);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5);
            player.getHungerManager().setExhaustion(0);
            player.setExperienceLevel(0);
            player.setExperiencePoints(0);
            player.clearStatusEffects();
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, StatusEffectInstance.INFINITE, 255, false, false, false));

            for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                for (String criteria : progress.getObtainedCriteria()) {
                    player.getAdvancementTracker().revokeCriterion(advancement, criteria);
                }
            }

            //player.resetStat(Stats.CUSTOM.getOrCreateStat(Stats.BOAT_ONE_CM));

            updateGameMode(player);

            if (!player.isTeamPlayer(server.getScoreboard().getTeam("players"))) {
                player.getScoreboard().addScoreHolderToTeam(player.getName().getString(), server.getScoreboard().getTeam("players"));
            }

            if (settings.setRoles == 3) {
                currentRole.put(player.getUuid(), "runner");
            }
        }

        if (gameState == ManhuntState.PLAYING) {
            if (player.getWorld() == server.getWorld(lobbyRegistryKey) || player.getWorld() == server.getOverworld()) {
                player.getInventory().clear();
                updateGameMode(player);
                moveToSpawn(server.getWorld(overworldRegistryKey), player);
                player.removeStatusEffect(StatusEffects.SATURATION);
            }
        }

        if (gameState == ManhuntState.POSTGAME) {
            player.getInventory().clear();
            updateGameMode(player);
            moveToSpawn(server.getWorld(overworldRegistryKey), player);
            player.removeStatusEffect(StatusEffects.SATURATION);
        }
    }

    public static void playerDisconnect(ServerPlayNetworkHandler handler, MinecraftServer server) {
        allRunners.removeIf(Predicate.isEqual(handler.getPlayer()));
    }

    public static TypedActionResult<ItemStack> useItem(PlayerEntity player, World world, Hand hand) {
        var itemStack = player.getStackInHand(hand);

        if (gameState == ManhuntState.PREGAME) {
            MinecraftServer server = Manhunt.SERVER;

            if (!player.getItemCooldownManager().isCoolingDown(itemStack.getItem())) {
                if (itemStack.getItem() == Items.RED_CONCRETE && itemStack.getNbt().getBoolean("NotReady")) {
                    isReady.put(player.getUuid(), true);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Ready", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Ready\",\"italic\": false,\"color\": \"green\"}");

                    ItemStack item = new ItemStack(Items.LIME_CONCRETE);
                    item.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, item);

                    player.getItemCooldownManager().set(item.getItem(), 10);

                    player.playSound(SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5f, 1.5f);

                    if (isReady.size() == server.getPlayerManager().getPlayerList().size()) {
                        if (Collections.frequency(currentRole.values(), "runner") == 0) {
                            MessageUtil.sendBroadcast("manhunt.chat.minimum");
                        } else {
                            if (Collections.frequency(currentRole.values(), "runner") >= 1) {
                                startGame(server);
                            }
                        }
                    }

                    MessageUtil.sendBroadcast("manhunt.chat.ready", player.getName().getString(), isReady.size(), player.getWorld().getPlayers().size());
                }

                if (itemStack.getItem() == Items.LIME_CONCRETE && itemStack.getNbt().getBoolean("Ready")) {
                    isReady.put(player.getUuid(), false);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("NotReady", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Unready\",\"italic\": false,\"color\": \"red\"}");

                    ItemStack item = new ItemStack(Items.RED_CONCRETE);
                    item.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, item);

                    player.playSound(SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5f, 0.5f);

                    MessageUtil.sendBroadcast("manhunt.chat.unready", player.getName().getString(), isReady.size(), player.getWorld().getPlayers().size());
                }

                if (itemStack.getItem() == Items.RECOVERY_COMPASS && itemStack.getNbt().getBoolean("Hunter") && !player.getItemCooldownManager().isCoolingDown(Items.CLOCK)) {
                    player.getItemCooldownManager().set(itemStack.getItem(), 10);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Runner", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Runner\",\"italic\": false,\"color\": \"green\"}");

                    ItemStack item = new ItemStack(Items.CLOCK);
                    item.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, item);

                    itemStack.addEnchantment(Enchantments.VANISHING_CURSE, 1);

                    player.playSound(SoundEvents.ITEM_LODESTONE_COMPASS_LOCK, SoundCategory.PLAYERS, 0.5f, 1f);

                    currentRole.put(player.getUuid(), "hunter");

                    MessageUtil.sendBroadcast("manhunt.chat.hunter", player.getName().getString());
                }

                if (itemStack.getItem() == Items.CLOCK && itemStack.getNbt().getBoolean("Runner") && !player.getItemCooldownManager().isCoolingDown(Items.RECOVERY_COMPASS)) {
                    player.getItemCooldownManager().set(itemStack.getItem(), 10);

                    NbtCompound nbt = new NbtCompound();
                    nbt.putBoolean("Remove", true);
                    nbt.putBoolean("Hunter", true);
                    nbt.putInt("HideFlags", 1);
                    nbt.put("display", new NbtCompound());
                    nbt.getCompound("display").putString("Name", "{\"translate\": \"Hunter\",\"italic\": false,\"color\": \"aqua\"}");

                    ItemStack item = new ItemStack(Items.RECOVERY_COMPASS);
                    item.setNbt(nbt);

                    int slotNumber = player.getInventory().getSlotWithStack(itemStack);

                    player.getInventory().setStack(slotNumber, item);

                    itemStack.addEnchantment(Enchantments.VANISHING_CURSE, 1);

                    player.playSound(SoundEvents.ENTITY_ENDER_EYE_LAUNCH, SoundCategory.PLAYERS, 0.5f, 1f);

                    currentRole.put(player.getUuid(), "runner");

                    MessageUtil.sendBroadcast("manhunt.chat.runner", player.getName().getString());
                }

                if (itemStack.getItem() == Items.COMPARATOR && itemStack.getNbt().getBoolean("Settings")) {
                    if (itemStack.getNbt().getBoolean("Settings")) {
                        settings((ServerPlayerEntity) player);
                    }
                }
            }
        }

        if (gameState == ManhuntState.PLAYING) {
            if (!settings.compassUpdate && itemStack.getNbt() != null && itemStack.getNbt().getBoolean("Tracker") && !player.isSpectator() && player.isTeamPlayer(Manhunt.SERVER.getScoreboard().getTeam("hunters")) && !player.getItemCooldownManager().isCoolingDown(itemStack.getItem())) {
                player.getItemCooldownManager().set(itemStack.getItem(), 10);
                if (!itemStack.getNbt().contains("Info")) {
                    itemStack.getNbt().put("Info", new NbtCompound());
                }

                NbtCompound info = itemStack.getNbt().getCompound("Info");

                if (!info.contains("Name", NbtElement.STRING_TYPE) && !allRunners.isEmpty()) {
                    info.putString("Name", allRunners.get(0).getName().getString());
                }

                ServerPlayerEntity trackedPlayer = Manhunt.SERVER.getPlayerManager().getPlayer(info.getString("Name"));

                if (trackedPlayer != null) {
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.1f, 1f);
                    updateCompass((ServerPlayerEntity) player, itemStack.getNbt(), trackedPlayer);
                }
            }
        }

        return TypedActionResult.pass(itemStack);
    }

    public static void playerRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        MinecraftServer server = Manhunt.SERVER;
        Scoreboard scoreboard = server.getScoreboard();
        if (!newPlayer.isTeamPlayer(scoreboard.getTeam("hunters"))) {
            scoreboard.clearTeam(newPlayer.getName().getString());
            scoreboard.addScoreHolderToTeam(newPlayer.getName().getString(), scoreboard.getTeam("hunters"));
        }
    }

    private static boolean hasItem(Item item, PlayerEntity player, String nbtBoolean) {
        boolean bool = false;
        for (ItemStack itemStack : player.getInventory().main) {
            if (itemStack.getItem().equals(item) && itemStack.getNbt() != null && itemStack.getNbt().getBoolean("Remove") && itemStack.getNbt().getBoolean(nbtBoolean)) {
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

    private static void settings(ServerPlayerEntity player) {
        if (player.hasPermissionLevel(1) || player.hasPermissionLevel(2) || player.hasPermissionLevel(3) || player.hasPermissionLevel(4)) {
            SimpleGui settings = new SimpleGui(ScreenHandlerType.GENERIC_9X2, player, false);
            settings.setTitle(MessageUtil.ofVomponent(player, "manhunt.item.settings"));
            changeSetting(player, settings, "setRoles", "manhunt.item.setroles", "manhunt.lore.setroles", Items.FLETCHING_TABLE, 0, SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER);
            changeSetting(player, settings, "hunterFreeze", "manhunt.item.hunterfreeze", "manhunt.lore.hunterfreeze", Items.ICE, 1, SoundEvents.BLOCK_GLASS_BREAK);
            changeSetting(player, settings, "timeLimit", "manhunt.item.timelimit", "manhunt.lore.timelimit", Items.CLOCK, 2, SoundEvents.ENTITY_FISHING_BOBBER_THROW);
            changeSetting(player, settings, "compassUpdate", "manhunt.item.compassupdate", "manhunt.lore.compassupdate", Items.COMPASS, 3, SoundEvents.ITEM_LODESTONE_COMPASS_LOCK);
            changeSetting(player, settings, "showTeamColor", "manhunt.item.showteamcolor", "manhunt.lore.showteamcolor", Items.LEATHER_CHESTPLATE, 4, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER);
            changeSetting(player, settings, "worldDifficulty", "manhunt.item.worlddifficulty", "manhunt.lore.worlddifficulty", Items.CREEPER_HEAD, 5, SoundEvents.ENTITY_CREEPER_HURT);
            changeSetting(player, settings, "borderSize", "manhunt.item.bordersize", "manhunt.lore.bordersize", Items.STRUCTURE_VOID, 6, SoundEvents.BLOCK_DEEPSLATE_BREAK);
            changeSetting(player, settings, "winnerTitle", "manhunt.item.winnertitle", "manhunt.lore.winnertitle", Items.OAK_SIGN, 7, SoundEvents.BLOCK_WOOD_BREAK);
            settings.open();
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.leader");
        }
    }

    private static void changeSetting(ServerPlayerEntity player, SimpleGui gui, String setting, String name, String lore, Item item, int slot, SoundEvent sound) {
        MinecraftServer server = Manhunt.SERVER;

        List<Text> loreList = new ArrayList<>();
        loreList.add(MessageUtil.ofVomponent(player, lore));
        if (setting.equals("setRoles")) {
            switch (settings.setRoles) {
                case 1 -> loreList.add(Text.literal("Free Select").formatted(Formatting.GREEN));
                case 2 -> loreList.add(Text.literal("All Hunters").formatted(Formatting.YELLOW));
                default -> loreList.add(Text.literal("All Runners").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        switch (settings.setRoles) {
                            case 1 -> {
                                Configs.configHandler.model().settings.setRoles = 2;
                                Configs.configHandler.saveToDisk();
                                MessageUtil.sendBroadcast("manhunt.chat.set.yellow", "Preset Mode", "All Hunters");
                                player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                                for (ServerPlayerEntity serverPlayer : server.getPlayerManager().getPlayerList()) {
                                    currentRole.put(serverPlayer.getUuid(), "hunter");
                                    serverPlayer.getInventory().clear();
                                }
                            }
                            case 2 -> {
                                Configs.configHandler.model().settings.setRoles = 3;
                                Configs.configHandler.saveToDisk();
                                MessageUtil.sendBroadcast("manhunt.chat.set.red", "Preset Mode", "All Runners");
                                player.playSound(sound, SoundCategory.MASTER, 1f, 1.5f);
                                for (ServerPlayerEntity serverPlayer : server.getPlayerManager().getPlayerList()) {
                                    currentRole.put(serverPlayer.getUuid(), "runner");
                                    serverPlayer.getInventory().clear();
                                }
                            }
                            default -> {
                                Configs.configHandler.model().settings.setRoles = 1;
                                Configs.configHandler.saveToDisk();
                                MessageUtil.sendBroadcast("manhunt.chat.set.green", "Preset Mode", "Free Select");
                                player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                            }
                        }
                        changeSetting(player, gui, setting, name, lore, item, slot, sound);
                    })
            );
        }
        if (setting.equals("hunterFreeze")) {
            if (settings.hunterFreeze == 0) {
                loreList.add(Text.literal(settings.hunterFreeze + " seconds (disabled)").formatted(Formatting.RED));
            } else {
                loreList.add(Text.literal(settings.hunterFreeze + " seconds").formatted(Formatting.GREEN));
            }
        }
        if (setting.equals("timeLimit")) {
            if (settings.timeLimit == 0) {
                loreList.add(Text.literal(settings.timeLimit + " minutes (disabled)").formatted(Formatting.RED));
            } else {
                loreList.add(Text.literal(settings.timeLimit + " minutes").formatted(Formatting.GREEN));
            }
        }
        if (setting.equals("borderSize")) {
            if (settings.borderSize == 59999968) {
                loreList.add(Text.literal(settings.borderSize + " blocks (maximum)").formatted(Formatting.RED));
            } else {
                loreList.add(Text.literal(settings.borderSize + " blocks").formatted(Formatting.GREEN));
            }
        }
        if (setting.equals("hunterFreeze") || setting.equals("timeLimit") || setting.equals("borderSize")) {
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
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
                                                if (setting.equals("hunterFreeze")) {
                                                    Configs.configHandler.model().settings.hunterFreeze = value;
                                                    Configs.configHandler.saveToDisk();
                                                    if (settings.hunterFreeze == 0) {
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.red", "Hunter Freeze", settings.hunterFreeze + " seconds (disabled)");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                                                    } else {
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.green", "Hunter Freeze", settings.hunterFreeze + " seconds");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                                                    }
                                                }
                                                if (setting.equals("timeLimit")) {
                                                    Configs.configHandler.model().settings.timeLimit = value;
                                                    Configs.configHandler.saveToDisk();
                                                    if (settings.timeLimit == 0) {
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.red", "Time Limit", settings.timeLimit + " minutes (disabled)");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                                                    } else {
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.green", "Time Limit", settings.timeLimit + " minutes");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                                                    }
                                                }
                                                if (setting.equals("borderSize")) {
                                                    Configs.configHandler.model().settings.borderSize = value;
                                                    Configs.configHandler.saveToDisk();
                                                    if (settings.borderSize == 0 || settings.borderSize >= 59999968) {
                                                        settings.borderSize = 59999968;
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.red", "Border Size", settings.borderSize + " blocks (maximum)");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                                                    } else {
                                                        MessageUtil.sendBroadcast("manhunt.chat.set.green", "Border Size", settings.borderSize + " blocks");
                                                        player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                                                    }
                                                }
                                            } catch (NumberFormatException e) {
                                                MessageUtil.sendMessage(player, "manhunt.chat.invalid");
                                            }
                                            settings(player);
                                        })
                                );
                            }
                        };
                        inputGui.setTitle(MessageUtil.ofVomponent(player, "manhunt.lore.value"));
                        inputGui.setSlot(0, new GuiElementBuilder(Items.PAPER));
                        inputGui.setDefaultInputValue("");
                        inputGui.open();
                        Configs.configHandler.saveToDisk();
                    })
            );
        }
        if (setting.equals("compassUpdate")) {
            if (settings.compassUpdate) {
                loreList.add(Text.literal("Automatic").formatted(Formatting.GREEN));
            } else {
                loreList.add(Text.literal("Manual").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (settings.compassUpdate) {
                            Configs.configHandler.model().settings.compassUpdate = false;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.red", "Compass Update", "Manual");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                        } else {
                            Configs.configHandler.model().settings.compassUpdate = true;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.green", "Compass Update", "Automatic");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                        }
                        changeSetting(player, gui, setting, name, lore, item, slot, sound);
                        Configs.configHandler.saveToDisk();
                    })
            );
        }

        if (setting.equals("showTeamColor")) {
            if (settings.teamColor) {
                loreList.add(Text.literal("Show").formatted(Formatting.GREEN));
            } else {
                loreList.add(Text.literal("Hide").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (settings.teamColor) {
                            Configs.configHandler.model().settings.teamColor = false;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.red", "Team Color", "Hide");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                        } else {
                            Configs.configHandler.model().settings.teamColor = true;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.green", "Team Color", "Show");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                        }
                        changeSetting(player, gui, setting, name, lore, item, slot, sound);
                        Configs.configHandler.saveToDisk();
                    })
            );
        }
        if (setting.equals("worldDifficulty")) {
            if (settings.worldDifficulty == 1) {
                loreList.add(Text.literal("Easy").formatted(Formatting.GREEN));
            } else if (settings.worldDifficulty == 2) {
                loreList.add(Text.literal("Normal").formatted(Formatting.YELLOW));
            } else {
                loreList.add(Text.literal("Hard").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (settings.worldDifficulty == 1) {
                            Configs.configHandler.model().settings.worldDifficulty = 2;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.yellow", "World Difficulty", "Normal");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                        } else if (settings.worldDifficulty == 2) {
                            Configs.configHandler.model().settings.worldDifficulty = 3;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.red", "World Difficulty", "Hard");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 0.8f);
                        } else {
                            Configs.configHandler.model().settings.worldDifficulty = 1;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.green", "World Difficulty", "Easy");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 1.2f);
                        }
                        changeSetting(player, gui, setting, name, lore, item, slot, sound);
                        Configs.configHandler.saveToDisk();
                    })
            );
        }
        if (setting.equals("winnerTitle")) {
            if (settings.winnerTitle) {
                loreList.add(Text.literal("Show").formatted(Formatting.GREEN));
            } else {
                loreList.add(Text.literal("Hide").formatted(Formatting.RED));
            }
            gui.setSlot(slot, new GuiElementBuilder(item)
                    .hideFlags()
                    .setName(MessageUtil.ofVomponent(player, name))
                    .setLore(loreList)
                    .setCallback(() -> {
                        if (settings.winnerTitle) {
                            Configs.configHandler.model().settings.winnerTitle = false;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.red", "Winner Title", "Hide");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 0.5f);
                        } else {
                            Configs.configHandler.model().settings.winnerTitle = true;
                            Configs.configHandler.saveToDisk();
                            MessageUtil.sendBroadcast("manhunt.chat.set.green", "Winner Title", "Show");
                            player.playSound(sound, SoundCategory.MASTER, 1f, 1f);
                        }
                        changeSetting(player, gui, setting, name, lore, item, slot, sound);
                        Configs.configHandler.saveToDisk();
                })
            );
        }
    }

    public static ManhuntState gameState;

    public static void manhuntState(ManhuntState gameState, MinecraftServer server) {
        server.setMotd(gameState.getColor() + "[" + gameState.getMotd() + "]§f Minecraft MANHUNT");
        ManhuntGame.gameState = gameState;
    }

    public static void startGame(MinecraftServer server) {
        server.setFlightEnabled(true);

        manhuntState(ManhuntState.PLAYING, server);

        var world = server.getWorld(overworldRegistryKey);

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
        world.getGameRules().get(GameRules.SPAWN_RADIUS).set(10, server);
        world.getGameRules().get(GameRules.FALL_DAMAGE).set(true, server);

        var difficulty = switch (ManhuntGame.settings.worldDifficulty) {
            case 2 -> Difficulty.NORMAL;
            case 3 -> Difficulty.HARD;
            default -> Difficulty.EASY;
        };

        server.setDifficulty(difficulty, true);

        server.getOverworld().setTimeOfDay(0);
        server.getOverworld().resetWeather();

        server.setPvpEnabled(true);

        worldSpawnPos = setupSpawn(world);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            moveToSpawn(world, player);
            player.clearStatusEffects();
            player.getInventory().clear();
            player.setFireTicks(0);
            player.setOnFire(false);
            player.setHealth(20);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5);
            player.getHungerManager().setExhaustion(0);
            player.setExperienceLevel(0);
            player.setExperiencePoints(0);

            for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                for (String criteria : progress.getObtainedCriteria()) {
                    player.getAdvancementTracker().revokeCriterion(advancement, criteria);
                }
            }

            player.resetStat(Stats.CUSTOM.getOrCreateStat(Stats.BOAT_ONE_CM));

            updateGameMode(player);

            player.networkHandler.sendPacket(new PlaySoundS2CPacket(SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.BLOCKS, player.getX(), player.getY(), player.getZ(), 0.1f, 1.5f, 0));

            if (settings.hunterFreeze != 0) {
                if (player.isTeamPlayer(server.getScoreboard().getTeam("hunters"))) {
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, settings.hunterFreeze * 20, 255, false, true));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, settings.hunterFreeze * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, settings.hunterFreeze * 20, 248, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, (settings.hunterFreeze - 1) * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, settings.hunterFreeze * 20, 255, false, false));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, settings.hunterFreeze * 20, 255, false, false));
                }
            }

            if (settings.winnerTitle) {
                MessageUtil.showTitle(player, "manhunt.title.gamemode", "manhunt.title.start");
            }
        }

        showSettingsToEveryone();
    }

    public static void updateGameMode(ServerPlayerEntity player) {
        if (gameState == ManhuntState.PREGAME) {
            player.changeGameMode(GameMode.ADVENTURE);
        } else if (gameState == ManhuntState.PLAYING) {
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
        if (trackedPlayer.getScoreboardTeam() != null && Objects.equals(trackedPlayer.getScoreboardTeam().getName(), "runners")) {
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

    public static void resetGame(ServerCommandSource source) {
        manhuntState(ManhuntState.PREGAME, Manhunt.SERVER);

        new ManhuntWorldModule().resetWorlds(Manhunt.SERVER);
    }

    public static void unloadWorld(MinecraftServer server, ServerWorld world) {
        new ManhuntWorldModule().onWorldUnload(server, world);
    }

    private static void moveToSpawn(ServerWorld world, ServerPlayerEntity player) {
        BlockPos blockPos = worldSpawnPos;
        long l;
        long m;
        int i = Math.max(0, Manhunt.SERVER.getSpawnRadius(world));
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
            player.teleport(world, blockPos2.getX(), blockPos2.getY(), blockPos2.getZ(), 0.0F, 0.0F);
            player.setSpawnPoint(world.getRegistryKey(), worldSpawnPos, 0.0F, true, false);
            if (!world.isSpaceEmpty(player)) {
                continue;
            }
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

    public static void showSettings(ServerPlayerEntity player) {
        MinecraftServer server = Manhunt.SERVER;

        if (settings.setRoles == 1) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Set Roles", "Free Select");
        } else if (settings.setRoles == 2) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.yellow", "Set Roles", "All Hunters");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Set Roles", "All Runners");
        }

        if (settings.hunterFreeze == 0) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Hunter Freeze", "0 seconds (disabled)");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Hunter Freeze", settings.hunterFreeze + " seconds");
        }

        if (settings.timeLimit == 0) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Time Limit", "0 minutes (disabled)");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Time Limit", settings.timeLimit + " minutes");
        }

        if (settings.compassUpdate) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Compass Update", "Automatic");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Compass Update", "Manual");
        }

        if (settings.teamColor) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Team Color", "Show");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Team Color", "Hide");
        }

        if (settings.worldDifficulty == 1) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "World Difficulty", "Easy");
        } else if (settings.worldDifficulty == 2) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.yellow", "World Difficulty", "Normal");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "World Difficulty", "Hard");
        }

        if (settings.borderSize == 59999968) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Border Size", "59999968 blocks (maximum)");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Border Size", settings.borderSize + " blocks");
        }

        if (settings.winnerTitle) {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.green", "Winner Title", "Show");
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.setting.red", "Winner Title", "Hide");
        }

        int viewDistance = server.getPlayerManager().getViewDistance();

        if (viewDistance >= 10 && viewDistance <= 12) {
            MessageUtil.sendMessage(player, "manhunt.chat.property.green", "View Distance", viewDistance);
        } else if (viewDistance >= 13 && viewDistance <= 18) {
            MessageUtil.sendMessage(player, "manhunt.chat.property.yellow", "View Distance", viewDistance);
        } else {
            MessageUtil.sendMessage(player, "manhunt.chat.property.red", "View Distance", viewDistance);
        }
    }

    public static void showSettingsToEveryone() {
        MinecraftServer server = Manhunt.SERVER;

        if (settings.setRoles == 1) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Set Roles", "Free Select");
        } else if (settings.setRoles == 2) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.yellow", "Set Roles", "All Hunters");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Set Roles", "All Runners");
        }

        if (settings.hunterFreeze == 0) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Hunter Freeze", "0 seconds (disabled)");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Hunter Freeze", settings.hunterFreeze + " seconds");
        }

        if (settings.timeLimit == 0) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Time Limit", "0 minutes (disabled)");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Time Limit", settings.timeLimit + " minutes");
        }

        if (settings.compassUpdate) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Compass Update", "Automatic");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Compass Update", "Manual");
        }

        if (settings.teamColor) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Team Color", "Show");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Team Color", "Hide");
        }

        if (settings.worldDifficulty == 1) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "World Difficulty", "Easy");
        } else if (settings.worldDifficulty == 2) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.yellow", "World Difficulty", "Normal");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "World Difficulty", "Hard");
        }

        if (settings.borderSize == 59999968) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Border Size", "59999968 blocks (maximum)");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Border Size", settings.borderSize + " blocks");
        }

        if (settings.winnerTitle) {
            MessageUtil.sendBroadcast("manhunt.chat.setting.green", "Winner Title", "Show");
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.setting.red", "Winner Title", "Hide");
        }

        int viewDistance = server.getPlayerManager().getViewDistance();

        if (viewDistance >= 10 && viewDistance <= 12) {
            MessageUtil.sendBroadcast("manhunt.chat.property.green", "View Distance", viewDistance);
        } else if (viewDistance >= 13 && viewDistance <= 18) {
            MessageUtil.sendBroadcast("manhunt.chat.property.yellow", "View Distance", viewDistance);
        } else {
            MessageUtil.sendBroadcast("manhunt.chat.property.red", "View Distance", viewDistance);
        }
    }
}
