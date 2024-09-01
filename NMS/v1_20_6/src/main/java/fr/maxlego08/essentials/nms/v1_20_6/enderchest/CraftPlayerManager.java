package fr.maxlego08.essentials.nms.v1_20_6.enderchest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Dynamic;
import fr.maxlego08.essentials.api.nms.PlayerManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.logging.Logger;

public class CraftPlayerManager implements PlayerManager {

    private static boolean paper;

    static {
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            paper = true;
        } catch (ClassNotFoundException ignored) {
            paper = false;
        }
    }

    private final @NotNull Logger logger;
    private @Nullable Field bukkitEntity;

    public CraftPlayerManager(@NotNull Logger logger) {
        this.logger = logger;
        try {
            bukkitEntity = Entity.class.getDeclaredField("bukkitEntity");
        } catch (NoSuchFieldException e) {
            logger.warning("Unable to obtain field to inject custom save process - certain player data may be lost when saving!");
            logger.log(java.util.logging.Level.WARNING, e.getMessage(), e);
            bukkitEntity = null;
        }
    }

    public static @NotNull ServerPlayer getHandle(final Player player) {
        if (player instanceof CraftPlayer) {
            return ((CraftPlayer) player).getHandle();
        }

        Server server = player.getServer();
        ServerPlayer nmsPlayer = null;

        if (server instanceof CraftServer) {
            nmsPlayer = ((CraftServer) server).getHandle().getPlayer(player.getUniqueId());
        }

        if (nmsPlayer == null) {
            // Could use reflection to examine fields, but it's honestly not worth the bother.
            throw new RuntimeException("Unable to fetch EntityPlayer from Player implementation " + player.getClass().getName());
        }

        return nmsPlayer;
    }

    @Override
    public @Nullable Player loadPlayer(@NotNull final OfflinePlayer offline) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel worldServer = server.getLevel(Level.OVERWORLD);

        if (worldServer == null) {
            return null;
        }

        // Create a new ServerPlayer.
        ServerPlayer entity = createNewPlayer(server, worldServer, offline);

        // Stop listening for advancement progression - if this is not cleaned up, loading causes a memory leak.
        entity.getAdvancements().stopListening();

        // Try to load the player's data.
        if (loadData(entity)) {
            // If data is loaded successfully, return the Bukkit entity.
            return entity.getBukkitEntity();
        }

        return null;
    }

    private @NotNull ServerPlayer createNewPlayer(
            @NotNull MinecraftServer server,
            @NotNull ServerLevel worldServer,
            @NotNull final OfflinePlayer offline) {
        // See net.minecraft.server.players.PlayerList#canPlayerLogin(ServerLoginPacketListenerImpl, GameProfile)
        // See net.minecraft.server.network.ServerLoginPacketListenerImpl#handleHello(ServerboundHelloPacket)
        GameProfile profile = new GameProfile(offline.getUniqueId(),
                offline.getName() != null ? offline.getName() : offline.getUniqueId().toString());

        ClientInformation dummyInfo = new ClientInformation(
                "en_us",
                1, // Reduce distance just in case.
                ChatVisiblity.HIDDEN, // Don't accept chat.
                false,
                ServerPlayer.DEFAULT_MODEL_CUSTOMIZATION,
                ServerPlayer.DEFAULT_MAIN_HAND,
                true,
                false // Don't list in player list (not that this player is in the list anyway).
        );

        ServerPlayer entity = new ServerPlayer(server, worldServer, profile, dummyInfo);

        try {
            injectPlayer(entity);
        } catch (IllegalAccessException e) {
            logger.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Unable to inject ServerPlayer, certain player data may be lost when saving!");
        }

        return entity;
    }

    boolean loadData(@NotNull ServerPlayer player) {
        // See CraftPlayer#loadData
        CompoundTag loadedData = player.server.getPlayerList().playerIo.load(player).orElse(null);

        if (loadedData == null) {
            // Exceptions with loading are logged by Mojang.
            return false;
        }

        // Read basic data into the player.
        player.load(loadedData);
        // Also read "extra" data.
        player.readAdditionalSaveData(loadedData);
        // Game type settings are also loaded separately.
        player.loadGameTypes(loadedData);

        if (paper) {
            // Paper: world is not loaded by ServerPlayer#load(CompoundTag).
            parseWorld(player, loadedData);
        }

        return true;
    }

    private void parseWorld(@NotNull ServerPlayer player, @NotNull CompoundTag loadedData) {
        // See PlayerList#placeNewPlayer
        World bukkitWorld;
        if (loadedData.contains("WorldUUIDMost") && loadedData.contains("WorldUUIDLeast")) {
            // Modern Bukkit world.
            bukkitWorld = Bukkit.getServer().getWorld(new UUID(loadedData.getLong("WorldUUIDMost"), loadedData.getLong("WorldUUIDLeast")));
        } else if (loadedData.contains("world", net.minecraft.nbt.Tag.TAG_STRING)) {
            // Legacy Bukkit world.
            bukkitWorld = Bukkit.getServer().getWorld(loadedData.getString("world"));
        } else {
            // Vanilla player data.
            DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, loadedData.get("Dimension")))
                    .resultOrPartial(logger::warning)
                    .map(player.server::getLevel)
                    // If ServerLevel exists, set, otherwise move to spawn.
                    .ifPresentOrElse(player::setServerLevel, () -> player.spawnIn(null));
            return;
        }
        if (bukkitWorld == null) {
            player.spawnIn(null);
            return;
        }
        player.setServerLevel(((CraftWorld) bukkitWorld).getHandle());
    }

    private void injectPlayer(ServerPlayer player) throws IllegalAccessException {
        if (bukkitEntity == null) {
            return;
        }

        bukkitEntity.setAccessible(true);

        bukkitEntity.set(player, new OpenPlayer(player.server.server, player, this));
    }

    @Override
    public @NotNull Player inject(@NotNull Player player) {
        try {
            ServerPlayer nmsPlayer = getHandle(player);
            if (nmsPlayer.getBukkitEntity() instanceof OpenPlayer openPlayer) {
                return openPlayer;
            }
            injectPlayer(nmsPlayer);
            return nmsPlayer.getBukkitEntity();
        } catch (IllegalAccessException e) {
            logger.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Unable to inject ServerPlayer, certain player data may be lost when saving!");
            return player;
        }
    }

}