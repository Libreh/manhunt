package manhunt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class PingCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ping")
                .executes(context -> pingLatency(context.getSource(), context.getSource().getPlayer()))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> pingLatency(context.getSource(), EntityArgumentType.getPlayer(context, "player"))))
        );
    }

    private static int pingLatency(ServerCommandSource source, ServerPlayerEntity player) {
        source.sendFeedback(() -> Text.translatable("manhunt.ping.ms", Text.literal(String.valueOf(player.networkHandler.getLatency()))), false);

        return Command.SINGLE_SUCCESS;
    }
}
