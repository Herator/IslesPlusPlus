package com.islesplusplus.map;

import com.islesplus.hud.HudAnchor;
import com.islesplus.hud.HudElement;
import com.islesplus.hud.HudPlacement;
import com.islesplus.logging.IslesLog;
import com.islesplus.sync.FeatureFlags;
import com.islesplus.ui.Draw;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Theme;
import com.islesplus.world.PlayerWorld;
import com.islesplus.world.WorldIdentification;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The Isles map. It's a top-down picture, one pixel per block, cut into {@value #TILE}-block square
 * PNG tiles named {@code <tileX>_<tileZ>.png}. Players start with the tiles shipped in the jar
 * ({@code assets/islesplusplus/map/}).
 *
 * <p>While {@link #generating} is on (it is by default, there's a setting for it), every loaded column's
 * top block is drawn in its vanilla map colour, so the map fills in as you explore. Only the blocks
 * you explored are saved, into tiles under {@code config/islesplusplus/map/} that are see-through
 * everywhere else; they're drawn over the shipped tiles, so a newer shipped map still shows wherever
 * you haven't been. Copy those tiles into the assets folder to ship them.
 *
 * <p>Each explored block's height goes in a matching tile under {@code heights/} (16 bits a pixel in red and
 * green, see {@link #encodeHeight}), so a waypoint clicked anywhere on the map can stand on the ground there.
 * Those ship the same way, from {@code assets/islesplusplus/map/heights/}.
 */
public final class IslesMap {
    public static final String KILL_KEY = "map";

    public static boolean minimapEnabled = true;
    // px per block
    public static float zoom = 1f;
    public static final float MIN_ZOOM = 0.25f, MAX_ZOOM = 4f;
    public static boolean generating = true;

    static final int TILE = 512;
    // method not a constant, FabricLoader isn't there in tests
    private static Path localDir() { return FabricLoader.getInstance().getConfigDir().resolve("islesplusplus").resolve("map"); }
    private static Path heightsDir() { return localDir().resolve("heights"); }
    static final int NO_HEIGHT = Short.MIN_VALUE;
    // TODO: scale CHUNKS_PER_TICK with render distance?
    private static final int CHUNKS_PER_TICK = 8, SAVE_EVERY_TICKS = 20 * 30;

    private static final class Tile {
        final int tx, tz;
        final Identifier id;
        final NativeImageBackedTexture tex;
        // changed since last upload / last save
        boolean stale, unsaved;
        // pixels you explored (z * TILE + x), only these get saved
        final BitSet explored = new BitSet(TILE * TILE);
        // top block y per pixel, NO_HEIGHT if unknown. made lazily
        short[] heights;

        int height(int i) { return heights == null ? NO_HEIGHT : heights[i]; }

        void setHeight(int i, int y) {
            if (heights == null) {
                heights = new short[TILE * TILE];
                java.util.Arrays.fill(heights, (short) NO_HEIGHT);
            }
            heights[i] = (short) y;
        }

        Tile(int tx, int tz, NativeImage img) {
            this.tx = tx;
            this.tz = tz;
            this.id = Identifier.of("islesplusplus", "map_tile/" + tx + "_" + tz);
            this.tex = new NativeImageBackedTexture(id::toString, img);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        }
    }

    private static final Map<Long, Tile> tiles = new HashMap<>();
    private static boolean loaded;
    private static final LongOpenHashSet scanned = new LongOpenHashSet();
    private static int saveTimer;

    private IslesMap() {}

    static int tileOf(int block) { return Math.floorDiv(block, TILE); }

    private static long key(int tx, int tz) { return ChunkPos.toLong(tx, tz); }

    // "3_-2" -> {3, -2}
    static int[] parseTileName(String name) {
        String[] p = name.split("_");
        if (p.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A height as a height tile's pixel: opaque, the height + 32768 in red (high byte) and green (low byte). */
    static int encodeHeight(int y) {
        int v = y + 32768;
        return 0xFF000000 | (v >> 8 & 0xFF) << 16 | (v & 0xFF) << 8;
    }

    static int decodeHeight(int argb) {
        if (argb >>> 24 == 0) return NO_HEIGHT;
        return ((argb >> 16 & 0xFF) << 8 | (argb >> 8 & 0xFF)) - 32768;
    }

    // vanilla map shading
    static MapColor.Brightness shade(int y, int northY) {
        return y > northY ? MapColor.Brightness.HIGH : y < northY ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }

    // isles only (not rifts or the hub)
    public static boolean available() {
        return WorldIdentification.world == PlayerWorld.ISLE && !FeatureFlags.isKilled(KILL_KEY);
    }

    public static boolean hasData() {
        ensureLoaded();
        return !tiles.isEmpty();
    }

    // also called on join so opening the map the first time doesn't freeze
    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Map<String, NativeImage> shipped = new HashMap<>(), local = new HashMap<>();
        Map<String, NativeImage> shippedHeights = new HashMap<>(), localHeights = new HashMap<>();
        // recursive, picks up heights/ too
        MinecraftClient.getInstance().getResourceManager()
            .findResources("map", id -> id.getNamespace().equals("islesplusplus") && id.getPath().endsWith(".png"))
            .forEach((id, res) -> {
                try (InputStream in = res.getInputStream()) {
                    put(id.getPath().contains("/heights/") ? shippedHeights : shipped, fileName(id.getPath()), NativeImage.read(in));
                } catch (IOException e) {
                    IslesLog.runtimeInfo("[Isles++] map tile " + id + " unreadable: " + e);
                }
            });
        readDir(localDir(), local);
        readDir(heightsDir(), localHeights);
        shipped.forEach((name, img) -> {
            int[] t = parseTileName(name);
            if (t == null) { img.close(); return; }
            tiles.put(key(t[0], t[1]), new Tile(t[0], t[1], img));
        });
        // your explored pixels go over the shipped tile; see-through ones leave it showing
        local.forEach((name, img) -> {
            int[] t = parseTileName(name);
            if (t != null) {
                Tile tile = tileFor(t[0], t[1]);
                NativeImage base = tile.tex.getImage();
                for (int z = 0; z < TILE; z++) {
                    for (int x = 0; x < TILE; x++) {
                        int argb = img.getColorArgb(x, z);
                        if (argb >>> 24 == 0) continue;
                        base.setColorArgb(x, z, argb);
                        tile.explored.set(z * TILE + x);
                    }
                }
                tile.tex.upload();
            }
            img.close();
        });
        // shipped first, ours on top
        readHeights(shippedHeights);
        readHeights(localHeights);
    }

    private static void readDir(Path dir, Map<String, NativeImage> images) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".png")).forEach(f -> {
                try (InputStream in = Files.newInputStream(f)) {
                    put(images, fileName(f.getFileName().toString()), NativeImage.read(in));
                } catch (IOException e) {
                    IslesLog.runtimeInfo("[Isles++] map tile " + f + " unreadable: " + e);
                }
            });
        } catch (IOException e) {
            IslesLog.runtimeInfo("[Isles++] map folder " + dir + " unreadable: " + e);
        }
    }

    // heights without a map tile under them are dropped
    private static void readHeights(Map<String, NativeImage> images) {
        images.forEach((name, img) -> {
            int[] t = parseTileName(name);
            Tile tile = t == null ? null : tiles.get(key(t[0], t[1]));
            if (tile != null) {
                for (int i = 0; i < TILE * TILE; i++) {
                    int y = decodeHeight(img.getColorArgb(i % TILE, i / TILE));
                    if (y != NO_HEIGHT) tile.setHeight(i, y);
                }
            }
            img.close();
        });
    }

    private static void put(Map<String, NativeImage> images, String name, NativeImage img) {
        if (img.getWidth() != TILE || img.getHeight() != TILE) { img.close(); return; }
        NativeImage old = images.put(name, img);
        if (old != null) old.close();
    }

    private static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".png".length());
    }

    // one thread so two saves of the same tile can't overlap
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Isles+ map saver");
        t.setDaemon(true);
        return t;
    });

    public static void save() { save(false); }

    // copy on the game thread (fast), write the pngs in the background (slow)
    // wait = block until it's on disk, used when the game closes
    public static void save(boolean wait) {
        saveTimer = 0;
        MapPois.save();
        for (Tile t : tiles.values()) {
            if (!t.unsaved) continue;
            t.unsaved = false;
            NativeImage colours = exploredImage(t, i -> t.tex.getImage().getColorArgb(i % TILE, i / TILE));
            NativeImage heights = t.heights == null ? null
                : exploredImage(t, i -> t.height(i) == NO_HEIGHT ? 0 : encodeHeight(t.height(i)));
            String file = t.tx + "_" + t.tz + ".png";
            WRITER.execute(() -> {
                try (colours; heights) {
                    Files.createDirectories(localDir());
                    colours.writeTo(localDir().resolve(file));
                    if (heights != null) {
                        Files.createDirectories(heightsDir());
                        heights.writeTo(heightsDir().resolve(file));
                    }
                } catch (IOException | RuntimeException e) {
                    IslesLog.runtimeInfo("[Isles++] could not save map tile " + file + ": " + e);
                    MinecraftClient.getInstance().execute(() -> t.unsaved = true);   // try again next save
                }
            });
        }
        if (wait) {
            try {
                WRITER.submit(() -> {}).get();
            } catch (InterruptedException | ExecutionException e) {
                IslesLog.runtimeInfo("[Isles++] map save interrupted: " + e);
            }
        }
    }

    private static NativeImage exploredImage(Tile t, java.util.function.IntUnaryOperator pixel) {
        NativeImage out = new NativeImage(TILE, TILE, true);
        out.fillRect(0, 0, TILE, TILE, 0);
        for (int i = t.explored.nextSetBit(0); i >= 0; i = t.explored.nextSetBit(i + 1)) {
            out.setColorArgb(i % TILE, i / TILE, pixel.applyAsInt(i));
        }
        return out;
    }

    // might have been built on, redraw it next time it loads
    public static void forgetChunk(ChunkPos pos) {
        scanned.remove(pos.toLong());
    }

    public static void forgetChunks() {
        scanned.clear();
    }

    public static void setGenerating(boolean on) {
        generating = on;
        scanned.clear();
        if (!on) save();
        com.islesplus.IslesPlusConfig.save();
    }

    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (!generating || world == null || client.player == null || !available()) return;
        ensureLoaded();
        int r = client.options.getClampedViewDistance();
        ChunkPos c = client.player.getChunkPos();
        int budget = CHUNKS_PER_TICK;
        for (int dx = -r; dx <= r && budget > 0; dx++) {
            for (int dz = -r; dz <= r && budget > 0; dz++) {
                long k = ChunkPos.toLong(c.x + dx, c.z + dz);
                if (scanned.contains(k)) continue;
                WorldChunk chunk = loadedChunk(world, c.x + dx, c.z + dz);
                if (chunk == null) continue;
                scan(world, chunk);
                scanned.add(k);
                budget--;
            }
        }
        for (Tile t : tiles.values()) {
            if (t.stale) { t.tex.upload(); t.stale = false; }
        }
        if (++saveTimer >= SAVE_EVERY_TICKS) save();
    }

    private static WorldChunk loadedChunk(ClientWorld world, int cx, int cz) {
        return world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
    }

    private static void scan(ClientWorld world, WorldChunk chunk) {
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        Tile tile = tileFor(tileOf(bx), tileOf(bz));
        NativeImage img = tile.tex.getImage();
        int px = Math.floorMod(bx, TILE), pz = Math.floorMod(bz, TILE);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        WorldChunk north = loadedChunk(world, chunk.getPos().x, chunk.getPos().z - 1);
        int[] northY = new int[16];
        for (int x = 0; x < 16; x++) northY[x] = north != null ? surfaceY(world, north, x, 15, pos) : Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = surfaceY(world, chunk, x, z, pos);
                int argb = 0;
                if (y != Integer.MIN_VALUE) {
                    pos.set(bx + x, y, bz + z);
                    MapColor color = chunk.getBlockState(pos).getMapColor(world, pos);
                    argb = color.getRenderColor(shade(y, northY[x] == Integer.MIN_VALUE ? y : northY[x]));
                }
                northY[x] = y;
                img.setColorArgb(px + x, pz + z, argb);
                int i = (pz + z) * TILE + px + x;
                tile.explored.set(i);
                tile.setHeight(i, y == Integer.MIN_VALUE ? NO_HEIGHT : y);
            }
        }
        tile.stale = tile.unsaved = true;
    }

    /** One above the top block the map shows at block (bx, bz), so a waypoint there stands on it: the block
     * itself when its chunk is loaded, else the height the map saved there. {@code fallback} if neither is
     * known (never explored since heights were added) or it's void. */
    public static int groundY(int bx, int bz, int fallback) {
        ClientWorld world = MinecraftClient.getInstance().world;
        WorldChunk chunk = world == null ? null : loadedChunk(world, bx >> 4, bz >> 4);
        if (chunk != null) {
            int y = surfaceY(world, chunk, bx & 15, bz & 15, new BlockPos.Mutable());
            return y == Integer.MIN_VALUE ? fallback : y + 1;
        }
        ensureLoaded();
        Tile t = tiles.get(key(tileOf(bx), tileOf(bz)));
        int y = t == null ? NO_HEIGHT : t.height(Math.floorMod(bz, TILE) * TILE + Math.floorMod(bx, TILE));
        return y == NO_HEIGHT ? fallback : y + 1;
    }

    // skips see-through stuff like barriers. MIN_VALUE over the void
    private static int surfaceY(ClientWorld world, WorldChunk chunk, int x, int z, BlockPos.Mutable pos) {
        int bottom = world.getBottomY();
        int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) + 1;
        int bx = chunk.getPos().getStartX() + x, bz = chunk.getPos().getStartZ() + z;
        BlockState state;
        do {
            y--;
            pos.set(bx, y, bz);
            state = chunk.getBlockState(pos);
        } while (y > bottom && state.getMapColor(world, pos) == MapColor.CLEAR);
        return y > bottom ? y : Integer.MIN_VALUE;
    }

    private static Tile tileFor(int tx, int tz) {
        return tiles.computeIfAbsent(key(tx, tz), k -> {
            NativeImage img = new NativeImage(TILE, TILE, true);
            img.fillRect(0, 0, TILE, TILE, 0);
            return new Tile(tx, tz, img);
        });
    }

    static void drawMap(DrawContext ctx, int x, int y, int w, int h, double cx, double cz, float ppb) {
        ensureLoaded();
        double left = cx - w / 2.0 / ppb, top = cz - h / 2.0 / ppb;
        int tx0 = tileOf((int) Math.floor(left)), tx1 = tileOf((int) Math.floor(left + w / ppb));
        int tz0 = tileOf((int) Math.floor(top)), tz1 = tileOf((int) Math.floor(top + h / ppb));
        ctx.enableScissor(x, y, x + w, y + h);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                Tile t = tiles.get(key(tx, tz));
                if (t == null) continue;
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate((float) (x + ((double) tx * TILE - left) * ppb), (float) (y + ((double) tz * TILE - top) * ppb));
                ctx.getMatrices().scale(ppb, ppb);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id, 0, 0, 0, 0, TILE, TILE, TILE, TILE);
                ctx.getMatrices().popMatrix();
            }
        }
        ctx.disableScissor();
    }

    // --- waypoint ---

    public record Waypoint(int x, int y, int z) {}

    // the temporary one (middle click / /waypoint). null if none
    public static Waypoint waypoint;

    public static void setWaypoint(int x, int y, int z) {
        waypoint = new Waypoint(x, y, z);
        com.islesplus.IslesClient.sendInfoMessage(MinecraftClient.getInstance(), "Waypoint set at " + x + ", " + y + ", " + z + ".");
    }

    public static int[] parseCoords(String text, int defaultY) {
        String[] parts = text.replaceAll("^[,\\s]+|[,\\s]+$", "").split("[,\\s]+");
        if (parts.length < 2 || parts.length > 3) return null;
        try {
            int[] n = new int[parts.length];
            for (int i = 0; i < parts.length; i++) n[i] = Integer.parseInt(parts[i]);
            return n.length == 2 ? new int[]{n[0], defaultY, n[1]} : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void clearWaypoint() {
        waypoint = null;
        com.islesplus.IslesClient.sendInfoMessage(MinecraftClient.getInstance(), "Waypoint removed.");
    }

    static boolean near(double sx, double sy, double mx, double my, int px) {
        return Math.abs(sx - mx) <= px && Math.abs(sy - my) <= px;
    }

    /** Where a marker {@code (dx, dy)} from a box's centre gets drawn. Right there if it's within
     * {@code halfW} x {@code halfH}, otherwise where the line out to it crosses the box's edge. */
    static int[] edgePoint(double dx, double dy, double halfW, double halfH) {
        if (Math.abs(dx) <= halfW && Math.abs(dy) <= halfH) return new int[]{(int) Math.round(dx), (int) Math.round(dy), 1};
        double t = Math.min(dx == 0 ? Double.MAX_VALUE : halfW / Math.abs(dx), dy == 0 ? Double.MAX_VALUE : halfH / Math.abs(dy));
        return new int[]{(int) Math.round(dx * t), (int) Math.round(dy * t), 0};
    }

    static void drawWaypoint(DrawContext ctx, int x, int y, int w, int h, double cx, double cz, float ppb) {
        Waypoint wp = waypoint;
        if (wp == null) return;
        int pad = 4;
        int[] p = edgePoint((wp.x() + 0.5 - cx) * ppb, (wp.z() + 0.5 - cz) * ppb, w / 2.0 - pad, h / 2.0 - pad);
        int mx = x + w / 2 + p[0], my = y + h / 2 + p[1];
        if (p[2] == 1) {
            diamond(ctx, mx, my, Theme.HUD_RED);
        } else {
            ctx.fill(mx - 2, my - 2, mx + 3, my + 3, Theme.HUD_SHADOW);
            ctx.fill(mx - 1, my - 1, mx + 2, my + 2, Theme.HUD_RED);
        }
    }

    static void diamond(DrawContext ctx, int cx, int cy, int argb) {
        for (int pass = 0; pass < 2; pass++) {
            int grow = pass == 0 ? 1 : 0, colour = pass == 0 ? Theme.HUD_SHADOW : argb;
            for (int i = -3; i <= 3; i++) {
                int half = 3 - Math.abs(i) + grow;
                ctx.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, colour);
            }
            ctx.fill(cx, cy - 3 - grow, cx + 1, cy + 4 + grow, colour);
        }
    }

    // {y, half width, half notch} per row, notch = the cut in the tail
    private static final int[][] ARROW = {
        {-5, 1, 0}, {-4, 1, 0}, {-3, 2, 0}, {-2, 2, 0}, {-1, 3, 0}, {0, 3, 0}, {1, 4, 0}, {2, 4, 2}, {3, 4, 3}};

    static void drawPlayer(DrawContext ctx, int px, int py, float yaw) {
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(px, py);
        // yaw 180 = north = up. y is down so + is clockwise
        ctx.getMatrices().rotate((float) Math.toRadians(yaw + 180f));
        for (int pass = 0; pass < 2; pass++) {
            int grow = pass == 0 ? 1 : 0, colour = pass == 0 ? Theme.HUD_SHADOW : Theme.HUD_GOLD;
            for (int[] r : ARROW) {
                int y = r[0], half = r[1], notch = r[2];
                if (notch == 0) {
                    ctx.fill(-half - grow, y - grow, half + grow, y + 1 + grow, colour);
                } else {
                    ctx.fill(-half - grow, y - grow, -notch + grow, y + 1 + grow, colour);
                    ctx.fill(notch - grow, y - grow, half + grow, y + 1 + grow, colour);
                }
            }
        }
        ctx.getMatrices().popMatrix();
    }

    static void drawCompass(DrawContext ctx, int x, int y, int w, int h) {
        int th = Fonts.height(Fonts.SMALL), pad = 3;
        Fonts.drawHud(ctx, "N", x + (w - Fonts.hudWidth("N", Fonts.SMALL)) / 2, y + pad, Theme.HUD_GOLD, Fonts.SMALL);
        Fonts.drawHud(ctx, "S", x + (w - Fonts.hudWidth("S", Fonts.SMALL)) / 2, y + h - th - pad, Theme.HUD_TEXT, Fonts.SMALL);
        Fonts.drawHud(ctx, "W", x + pad, y + (h - th) / 2, Theme.HUD_TEXT, Fonts.SMALL);
        Fonts.drawHud(ctx, "E", x + w - Fonts.hudWidth("E", Fonts.SMALL) - pad, y + (h - th) / 2, Theme.HUD_TEXT, Fonts.SMALL);
    }

    // the World Bosses panel colours (Isles+ BossTimerHud), so the maps match the other HUD panels
    private static final int PANEL_BG = 0x9E1C1815, PANEL_RING = 0xCC15100A;
    private static final int PANEL_SHADE = 0xB31A1714, PANEL_LIT_SIDE = 0xB33A342D, PANEL_SHADE_SIDE = 0xB31F1B17;

    static void panel(DrawContext ctx, int x, int y, int w, int h, float bg) {
        Draw.ring(ctx, x, y, w, h, fade(PANEL_RING, bg));
        ctx.fill(x, y, x + w, y + h, fade(PANEL_BG, bg));
        ctx.fill(x, y + h - 1, x + w, y + h, fade(PANEL_SHADE, bg));
        ctx.fill(x, y, x + 1, y + h, fade(PANEL_LIT_SIDE, bg));
        ctx.fill(x + w - 1, y, x + w, y + h, fade(PANEL_SHADE_SIDE, bg));
    }

    private static int fade(int argb, float f) {
        if (f >= 1f) return argb;
        return Math.round((argb >>> 24) * Math.max(0f, f)) << 24 | (argb & 0xFFFFFF);
    }

    static final int BORDER = 4;

    // parchment border + dark panel inside it, map goes inside BORDER
    static void drawFrame(DrawContext ctx, int x, int y, int w, int h, float bg) {
        int ix = x + BORDER, iy = y + BORDER, iw = w - 2 * BORDER, ih = h - 2 * BORDER;
        if (bg > 0f) IslesMap.panel(ctx, ix, iy, iw, ih, bg);
        // only the border strips, so the inside stays see-through when the background is faded
        int x2 = x + w, y2 = y + h;
        ctx.fill(x + 1, y + 1, x2 - 1, iy - 1, Theme.SURFACE);
        ctx.fill(x + 1, iy + ih + 1, x2 - 1, y2 - 1, Theme.SURFACE);
        ctx.fill(x + 1, iy - 1, ix - 1, iy + ih + 1, Theme.SURFACE);
        ctx.fill(ix + iw + 1, iy - 1, x2 - 1, iy + ih + 1, Theme.SURFACE);
        ctx.fill(x + 1, y + 1, x2 - 1, y + 2, Theme.SURFACE_LIT);
        ctx.fill(x + 1, y + 2, x + 2, y2 - 1, Theme.SURFACE_LIT_SIDE);
        ctx.fill(x + 1, y2 - 2, x2 - 1, y2 - 1, Theme.SURFACE_SHADE);
        ctx.fill(x2 - 2, y + 2, x2 - 1, y2 - 2, Theme.SURFACE_SHADE_SIDE);
        Draw.insetRing(ctx, x, y, w, h, Theme.SURFACE_RING);
        Draw.ring(ctx, ix, iy, iw, ih, Theme.SURFACE_RING);
    }

    private static final int MINIMAP_SIZE = 100;

    public static final HudElement ELEMENT = new HudElement("minimap", "Minimap",
        new HudPlacement(HudAnchor.START, HudAnchor.START, 4, 4)) {
        @Override public boolean enabled() { return minimapEnabled; }
        @Override public boolean active(MinecraftClient client) {
            return minimapEnabled && client.player != null && !client.options.hudHidden && available();
        }
        @Override public Size measure(boolean preview) { return new Size(MINIMAP_SIZE, MINIMAP_SIZE); }
        @Override public boolean hasBackgroundOpacity() { return true; }
        @Override public void draw(DrawContext ctx, Frame f) {
            var player = MinecraftClient.getInstance().player;
            int s = MINIMAP_SIZE;
            int b = BORDER, in = s - 2 * BORDER;
            drawFrame(ctx, 0, 0, s, s, f.backgroundOpacity());
            if (player != null) {
                drawMap(ctx, b, b, in, in, player.getX(), player.getZ(), zoom);
                MapPois.draw(ctx, b, b, in, in, player.getX(), player.getZ(), zoom, true);
                drawWaypoint(ctx, b, b, in, in, player.getX(), player.getZ(), zoom);
                drawPlayer(ctx, s / 2, s / 2, player.getYaw());
            }
            drawCompass(ctx, b, b, in, in);
        }
    };
}
