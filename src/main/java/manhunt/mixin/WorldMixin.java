package manhunt.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public class WorldMixin {

    // Thanks to https://github.com/Walksy/ShieldFixes for the mixin

    /**
     * Fixes Minecraft Bug MC-98271 -- https://bugs.mojang.com/browse/MC-105068
     * --Walksy
     */

    @Unique
    private boolean playedBreakSound = false;

    @Inject(at = @At("HEAD"), method = "playSound(Lnet/minecraft/entity/player/PlayerEntity;DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FF)V")
    private void onSoundPlay(PlayerEntity except, double x, double y, double z, SoundEvent sound, SoundCategory category, float volume, float pitch, CallbackInfo ci) {
        //Checks if the 'shield.block' sound effect has been played because for some reason it shows it's been played, but no sound is outputted
        //if it has then we know the player is blocking with a shield
        if (sound.getId().toString().toLowerCase().contains("shield.break")) {
            except.getWorld().playSound(x, y, z, SoundEvents.ITEM_SHIELD_BREAK, category, 1, 0.8F + except.getWorld().random.nextFloat() * 0.4F, false);
            playedBreakSound = true;
        } else if (sound.getId().toString().toLowerCase().contains("shield.block")) {
            if (!playedBreakSound) {
                except.getWorld().playSound(x, y, z, SoundEvents.ITEM_SHIELD_BLOCK, category, 1, 0.8F + except.getWorld().random.nextFloat() * 0.4F, false);
            } else {
                playedBreakSound = false;
            }
        }
    }
}
