package com.islesplusplus;

import com.islesplus.IslesPlusConfig;
import com.islesplus.logging.IslesLog;
import com.islesplusplus.healthborder.HealthBorder;
import com.islesplusplus.itempickup.ItemPickupLog;
import com.islesplusplus.map.IslesMap;
import com.islesplusplus.map.MapPois;
import com.islesplusplus.map.MapScreen;
import com.islesplusplus.map.Party;
import com.islesplusplus.map.WaypointRenderer;
import com.islesplusplus.map.WaypointsScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static com.islesplus.IslesClient.sendInfoMessage;

public class IslesPlusPlus implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("islesplusplus", "islesplusplus"));
    public static final KeyBinding HEALTH_BORDER_KEY = key("health_border", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyBinding MAP_KEY = key("map", GLFW.GLFW_KEY_M);
    public static final KeyBinding WAYPOINTS_KEY = key("waypoints", GLFW.GLFW_KEY_UNKNOWN);

    private static int partyTimer;

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.islesplusplus." + name, InputUtil.Type.KEYSYM, code, CATEGORY));
    }

    @Override
    public void onInitializeClient() {
        moveOldMapFolder();   // our settings load with the Isles+ ones (IslesPlusConfigMixin)

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ItemPickupLog.reset();
            MapPois.forgetIcons();
            IslesMap.ensureLoaded();   // load now so the first M press doesn't hitch
            MapPois.all();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            IslesMap.save();
            IslesMap.waypoint = null;
            IslesMap.forgetChunks();
            ItemPickupLog.reset();
            Party.members = Set.of();
        }));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(IslesPlusPlus::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(WaypointRenderer::render);
        HudRenderCallback.EVENT.register((context, tickDelta) -> HealthBorder.render(context, MinecraftClient.getInstance()));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> IslesMap.forgetChunk(chunk.getPos()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> IslesMap.save(true));
    }

    private static void tick(MinecraftClient client) {
        while (HEALTH_BORDER_KEY.wasPressed()) {
            HealthBorder.enabled ^= true;
            IslesPlusConfig.save();
            sendInfoMessage(client, "Health Border " + (HealthBorder.enabled ? "on." : "off."));
        }
        while (MAP_KEY.wasPressed()) {
            if (IslesMap.available()) client.setScreen(new MapScreen());
            else sendInfoMessage(client, "The map is only on the Isles.");
        }
        while (WAYPOINTS_KEY.wasPressed()) client.setScreen(new WaypointsScreen(null));
        guarded("map", () -> IslesMap.tick(client));
        guarded("map_record", () -> MapPois.recordTick(client));
        if (++partyTimer >= 20) {
            partyTimer = 0;
            guarded("party", () -> Party.tick(client));
        }
    }

    // so one broken feature doesn't kill the rest
    private static void guarded(String feature, Runnable tick) {
        try {
            tick.run();
        } catch (Exception e) {
            IslesLog.runtimeInfo("[Isles++] " + feature + " tick failed: " + e);
        }
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // /waypoint x z | x y z | clear
        dispatcher.register(ClientCommandManager.literal("waypoint")
            .then(ClientCommandManager.literal("clear").executes(context -> {
                IslesMap.clearWaypoint();
                return 1;
            }))
            // "x z" or "x y z", split by spaces and/or commas ("100, 0, 200" as copied from F3 or chat)
            .then(ClientCommandManager.argument("coords", StringArgumentType.greedyString())
                .executes(context -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    int[] xyz = IslesMap.parseCoords(
                        StringArgumentType.getString(context, "coords"),
                        c.player != null ? c.player.getBlockY() : 64);
                    if (xyz == null) {
                        sendInfoMessage(c, "Use /waypoint x z or /waypoint x y z, like 100, 0, 200.");
                        return 0;
                    }
                    IslesMap.setWaypoint(xyz[0], xyz[1], xyz[2]);
                    return 1;
                }))
        );
        dispatcher.register(ClientCommandManager.literal("waypoints").executes(context -> {
            MinecraftClient c = MinecraftClient.getInstance();
            c.execute(() -> c.setScreen(new WaypointsScreen(null)));
            return 1;
        }));
        // /poi stuff, edits config/islesplusplus/map/pois.json
        LiteralArgumentBuilder<FabricClientCommandSource> poiAdd = ClientCommandManager.literal("add");
        for (MapPois.Group group : MapPois.Group.values()) {
            if (group == MapPois.Group.PLAYERS || group == MapPois.Group.WAYPOINTS) continue;
            poiAdd.then(ClientCommandManager.literal(group.name().toLowerCase(Locale.ROOT))
                .then(ClientCommandManager.argument("type", StringArgumentType.greedyString())
                    .executes(context -> {
                        MinecraftClient c = MinecraftClient.getInstance();
                        if (c.player == null) return 0;
                        String type = StringArgumentType.getString(context, "type");
                        MapPois.add(new MapPois.Poi(group, type,
                            c.player.getBlockX(), c.player.getBlockY(), c.player.getBlockZ()));
                        MapPois.save();
                        sendInfoMessage(c, "Map point added: " + MapPois.title(type.toLowerCase(Locale.ROOT)) + " (" + group.label + ").");
                        return 1;
                    })));
        }
        dispatcher.register(ClientCommandManager.literal("poi")
            .then(poiAdd)
            .then(ClientCommandManager.literal("remove").executes(context -> {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player == null) return 0;
                var removed = MapPois.removeNearest(c.player.getX(), c.player.getZ(), 8);
                MapPois.save();
                sendInfoMessage(c, removed == null ? "No map point within 8 blocks." : "Map point removed: " + removed.name() + ".");
                return 1;
            }))
            .then(ClientCommandManager.literal("labels").executes(context -> {
                MinecraftClient c = MinecraftClient.getInstance();
                for (String line : MapPois.describeLabels(c, 24)) sendInfoMessage(c, line);
                return 1;
            }))
            .then(ClientCommandManager.literal("record").executes(context -> {
                MinecraftClient c = MinecraftClient.getInstance();
                MapPois.recording ^= true;
                if (!MapPois.recording) MapPois.save();
                sendInfoMessage(c, MapPois.recording
                    ? "Recording map points: waystones, nodes and stations in range are saved as you go."
                    : "Stopped recording map points (saved).");
                return 1;
            }))
            .then(ClientCommandManager.literal("reload").executes(context -> {
                int n = MapPois.reload();
                sendInfoMessage(MinecraftClient.getInstance(), "Map points reloaded: " + n + ".");
                return 1;
            })));
    }

    // map used to live in config/islesplus/map back when this was part of Isles+, move it over once
    private static void moveOldMapFolder() {
        Path config = FabricLoader.getInstance().getConfigDir();
        Path from = config.resolve("islesplus").resolve("map"), to = config.resolve("islesplusplus").resolve("map");
        if (!Files.isDirectory(from) || Files.exists(to)) return;
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (Exception e) {
            IslesLog.runtimeInfo("[Isles++] could not move " + from + " to " + to + ": " + e);
        }
    }
}
