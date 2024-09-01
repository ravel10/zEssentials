package fr.maxlego08.essentials.nms.v1_20_4.enderchest;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.craftbukkit.v1_20_R3.CraftServer;
import org.bukkit.craftbukkit.v1_20_R3.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class OpenPlayer extends CraftPlayer {

    private static final Set<String> RESET_TAGS = Set.of(
            // net.minecraft.world.Entity#saveWithoutId(CompoundTag)
            "CustomName", "CustomNameVisible", "Silent", "NoGravity", "Glowing", "TicksFrozen", "HasVisualFire", "Tags", "Passengers",
            // net.minecraft.server.level.ServerPlayer#addAdditionalSaveData(CompoundTag)
            // Intentional omissions to prevent mount loss: Attach, Entity, and RootVehicle
            "warden_spawn_tracker", "enteredNetherPosition", "SpawnX", "SpawnY", "SpawnZ", "SpawnForced", "SpawnAngle", "SpawnDimension",
            // net.minecraft.world.entity.player.Player#addAdditionalSaveData(CompoundTag)
            "ShoulderEntityLeft", "ShoulderEntityRight", "LastDeathLocation",
            // net.minecraft.world.entity.LivingEntity#addAdditionalSaveData(CompoundTag)
            "ActiveEffects", // Backwards compat: Renamed from 1.19
            "active_effects", "SleepingX", "SleepingY", "SleepingZ", "Brain");

    private final CraftPlayerManager manager;

    OpenPlayer(CraftServer server, ServerPlayer entity, CraftPlayerManager manager) {
        super(server, entity);
        this.manager = manager;
    }

    @Override
    public void loadData() {
        manager.loadData(getHandle());
    }

    @Override
    public void saveData() {

        ServerPlayer player = this.getHandle();
        // See net.minecraft.world.level.storage.PlayerDataStorage#save(EntityHuman)
        try {
            PlayerDataStorage worldNBTStorage = player.server.getPlayerList().playerIo;

            CompoundTag oldData = isOnline() ? null : worldNBTStorage.getPlayerData(player.getStringUUID());
            CompoundTag playerData = getWritableTag(oldData);
            playerData = player.saveWithoutId(playerData);
            setExtraData(playerData);

            if (oldData != null) {
                // Revert certain special data values when offline.
                revertSpecialValues(playerData, oldData);
            }

            Path playerDataDir = worldNBTStorage.getPlayerDir().toPath();
            Path file = Files.createTempFile(playerDataDir, player.getStringUUID() + "-", ".dat");
            NbtIo.writeCompressed(playerData, file);
            Path dataFile = playerDataDir.resolve(player.getStringUUID() + ".dat");
            Path backupFile = playerDataDir.resolve(player.getStringUUID() + ".dat_old");
            Util.safeReplaceFile(dataFile, file, backupFile);
        } catch (Exception e) {
            LogUtils.getLogger().warn("Failed to save player data for {}: {}", player.getScoreboardName(), e);
        }
    }

    @Contract("null -> new")
    private @NotNull CompoundTag getWritableTag(@Nullable CompoundTag oldData) {
        if (oldData == null) {
            return new CompoundTag();
        }

        // Copy old data. This is a deep clone, so operating on it should be safe.
        oldData = oldData.copy();

        // Remove vanilla/server data that is not written every time.
        oldData.getAllKeys().removeIf(key -> RESET_TAGS.contains(key) || key.startsWith("Bukkit"));

        return oldData;
    }

    private void revertSpecialValues(@NotNull CompoundTag newData, @NotNull CompoundTag oldData) {
        // Revert automatic updates to play timestamps.
        copyValue(oldData, newData, "bukkit", "lastPlayed", NumericTag.class);
        copyValue(oldData, newData, "Paper", "LastSeen", NumericTag.class);
        copyValue(oldData, newData, "Paper", "LastLogin", NumericTag.class);
    }

    private <T extends Tag> void copyValue(@NotNull CompoundTag source, @NotNull CompoundTag target, @NotNull String container, @NotNull String key, @NotNull Class<T> tagType) {
        CompoundTag oldContainer = getTag(source, container, CompoundTag.class);
        CompoundTag newContainer = getTag(target, container, CompoundTag.class);

        // New container being null means the server implementation doesn't store this data.
        if (newContainer == null) {
            return;
        }

        // If old tag exists, copy it to new location, removing otherwise.
        setTag(newContainer, key, getTag(oldContainer, key, tagType));
    }

    private <T extends Tag> @Nullable T getTag(@Nullable CompoundTag container, @NotNull String key, @NotNull Class<T> dataType) {
        if (container == null) {
            return null;
        }
        Tag value = container.get(key);
        if (value == null || !dataType.isAssignableFrom(value.getClass())) {
            return null;
        }
        return dataType.cast(value);
    }

    private <T extends Tag> void setTag(@NotNull CompoundTag container, @NotNull String key, @Nullable T data) {
        if (data == null) {
            container.remove(key);
        } else {
            container.put(key, data);
        }
    }

}