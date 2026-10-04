package com.islesplusplus.map;

import com.islesplus.IslesPlusConfig;
import com.islesplus.ui.Draw;
import com.islesplus.ui.Flow;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Metrics;
import com.islesplus.ui.Theme;
import com.islesplus.ui.Widget;
import com.islesplus.ui.widgets.Button;
import com.islesplus.ui.widgets.Label;
import com.islesplus.ui.widgets.SmallToggle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

// full map. drag = pan, scroll = zoom, middle click = temp waypoint, right click = add saved waypoint
// clicking a point puts the waypoint on it (on a cluster: the node nearest the middle)
public final class MapScreen extends Screen {
    /** Space around the map panel. The game shows through the scrim there, same as behind the mod's other screens. */
    private static final int MARGIN = 12;
    private static final float MIN_PPB = 0.125f, MAX_PPB = 8f;
    // px
    private static final int PICK = 5;
    private static final int SHOW_PANEL_W = 128;
    private static final int SHOW_PANEL_GAP = 8;
    private static final int SHOW_PANEL_PAD = 6;
    private int showPanelW = SHOW_PANEL_W;
    private static final int TAB_W = 12, TAB_H = 30;
    private static boolean panelOpen = false;
    private int tabX, tabY;
    private static float ppb = 1f;   // TODO remember the pan too?
    private double cx, cz;
    private boolean centred;
    private Widget showContent;
    private int boxX, boxY, boxH;
    private int showScroll;
    private static final int SCROLL_STEP = 12;
    private List<MapPois.Shown> drawn = List.of();
    private final Button waypointsButton = new Button("Waypoints", Button.Kind.SECONDARY,
        () -> MinecraftClient.getInstance().setScreen(new WaypointsScreen(this)));
    private boolean dragOnPanel;

    public MapScreen() {
        super(Text.literal("Map"));
    }

    @Override
    protected void init() {
        // centred on you when it opens, not again when you come back from the Waypoints screen
        if (!centred && client != null && client.player != null) {
            cx = client.player.getX();
            cz = client.player.getZ();
            centred = true;
        }
        Fonts.islesUi(() -> {
            Flow.Column col = new Flow.Column(Metrics.DRAWER_GAP).add(new Label("Show", Theme.TEXT_TITLE, Fonts.BODY));
            // the panel grows to fit its labels, up to a third of the map (at a big GUI scale the map is only a
            // few hundred px wide); past that a label is cut short with "..." rather than wrapping
            int maxRow = Math.max(SHOW_PANEL_W, (width - 2 * MARGIN) / 3) - 2 * (SHOW_PANEL_PAD + 1);
            int widest = 0;
            for (MapPois.Group g : MapPois.Group.values()) {
                // a profession (or Miscellaneous) opens up into a toggle per resource
                List<String> types = g.expands() ? MapPois.resources(g) : List.of();
                Widget icon = icon(g, null);
                int others = MapPois.ICON + ROW_GAP + (types.isEmpty() ? 0 : ROW_GAP + Expander.W);
                SmallToggle toggle = fitToggle(g.label, g, null, maxRow - others);
                Flow.WrapRow row = new Flow.WrapRow(ROW_GAP, ROW_GAP).add(icon).add(toggle);
                col.add(row);
                if (types.isEmpty()) {
                    widest = Math.max(widest, rowWidth(icon, toggle));
                    continue;
                }
                Flow.Column sub = new Flow.Column(Metrics.DRAWER_GAP);
                for (String t : types) {
                    Widget[] sp = {new Gap(INDENT), icon(g, t),
                        fitToggle(MapPois.title(t), g, t, maxRow - INDENT - MapPois.ICON - 2 * ROW_GAP)};
                    sub.add(new Flow.WrapRow(ROW_GAP, ROW_GAP).add(sp[0]).add(sp[1]).add(sp[2]));
                    widest = Math.max(widest, rowWidth(sp));
                }
                sub.visible = expanded.contains(g);
                Expander expander = new Expander(() -> sub.visible, () -> {
                    sub.visible = !sub.visible;
                    if (sub.visible) expanded.add(g); else expanded.remove(g);
                });
                row.add(expander);
                widest = Math.max(widest, rowWidth(icon, toggle, expander));
                col.add(sub);
            }
            // wide enough that no row wraps onto a second line, with a pixel to spare each side
            showPanelW = Math.max(SHOW_PANEL_W, widest + 2 * (SHOW_PANEL_PAD + 1));
            showContent = col;
        });
    }

    private static final int ROW_GAP = 4;
    private static final int INDENT = 8;

    /** A Show panel toggle no wider than {@code maxW}, its label cut short with "..." if it has to be. */
    private static SmallToggle fitToggle(String label, MapPois.Group g, String type, int maxW) {
        SmallToggle t = toggle(label, g, type);
        int over = t.prefWidth() - maxW;
        return over <= 0 ? t : toggle(Fonts.ellipsize(label, Fonts.width(label) - over), g, type);
    }

    private static int rowWidth(Widget... parts) {
        int w = ROW_GAP * (parts.length - 1);
        for (Widget p : parts) w += p.prefWidth();
        return w;
    }

    private static final java.util.Set<MapPois.Group> expanded = java.util.EnumSet.noneOf(MapPois.Group.class);

    private static SmallToggle toggle(String label, MapPois.Group g, String type) {
        return new SmallToggle(label, () -> type == null ? MapPois.shown(g) : MapPois.shown(g, type), () -> {
            MapPois.toggle(g, type);
            IslesPlusConfig.save();
        }).mini();
    }

    private static Widget icon(MapPois.Group g, String type) {
        return new Widget() {
            @Override public int prefWidth() { return MapPois.ICON; }
            @Override public int layout(int x, int y, int width) { this.x = x; this.y = y; w = MapPois.ICON; h = MapPois.ICON; return h; }
            @Override public void render(DrawContext ctx, int mouseX, int mouseY) {
                MapPois.drawIcon(ctx, g, type, x + MapPois.ICON / 2, y + MapPois.ICON / 2);
            }
        };
    }

    private static final class Gap extends Widget {
        private final int size;
        Gap(int size) { this.size = size; }
        @Override public int prefWidth() { return size; }
        @Override public int layout(int x, int y, int width) { this.x = x; this.y = y; w = size; h = 1; return h; }
        @Override public void render(DrawContext ctx, int mouseX, int mouseY) {}
    }

    private static final class Expander extends Widget {
        private final java.util.function.BooleanSupplier open;
        private final Runnable onClick;
        static final int W = 10;
        Expander(java.util.function.BooleanSupplier open, Runnable onClick) { this.open = open; this.onClick = onClick; }
        @Override public int prefWidth() { return W; }
        @Override public int layout(int x, int y, int width) { this.x = x; this.y = y; w = W; h = W; return h; }
        @Override public void render(DrawContext ctx, int mouseX, int mouseY) {
            Draw.caret(ctx, x + 5, y + 5, open.getAsBoolean() ? Draw.Dir.DOWN : Draw.Dir.RIGHT,
                contains(mouseX, mouseY) ? Theme.TEXT_STRONG : Theme.TEXT_TITLE);
        }
        @Override public boolean mouseClicked(double mx, double my, int button) {
            if (!contains(mx, my)) return false;
            onClick.run();
            return true;
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, Theme.SCRIM);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int m = MARGIN, b = IslesMap.BORDER, mw = width - 2 * m, mh = height - 2 * m;
        IslesMap.drawFrame(ctx, m - b, m - b, width - 2 * (m - b), height - 2 * (m - b), 1f);
        if (!IslesMap.hasData()) {
            String s = "No map data yet.";
            Fonts.draw(ctx, s, (width - Fonts.width(s)) / 2, height / 2, Theme.HUD_TEXT);
        }
        IslesMap.drawMap(ctx, m, m, mw, mh, cx, cz, ppb);
        drawn = MapPois.draw(ctx, m, m, mw, mh, cx, cz, ppb, false);
        IslesMap.drawWaypoint(ctx, m, m, mw, mh, cx, cz, ppb);
        if (client != null && client.player != null) {
            int px = (int) Math.round(width / 2.0 + (client.player.getX() - cx) * ppb);
            int py = (int) Math.round(height / 2.0 + (client.player.getZ() - cz) * ppb);
            if (px >= m && px < width - m && py >= m && py < height - m) IslesMap.drawPlayer(ctx, px, py, client.player.getYaw());
        }
        IslesMap.drawCompass(ctx, m, m, mw, mh);

        // the Show panel sits centred on the map's left edge with its tab on its right (or just the tab on
        // the edge while closed).
        int midY = m + mh / 2;
        if (panelOpen) {
            Fonts.islesUi(() -> {
                int pad = SHOW_PANEL_PAD, innerW = showPanelW - 2 * pad;
                int contentH = showContent.layout(0, 0, innerW) + 2 * pad;
                // no taller than the map less a gap at the top and bottom; what doesn't fit scrolls
                // (a big GUI scale with a list open)
                boxH = Math.min(contentH, mh - 2 * SHOW_PANEL_GAP);
                showScroll = Math.max(0, Math.min(showScroll, contentH - boxH));
                boxX = m + SHOW_PANEL_GAP;
                boxY = Math.max(m + SHOW_PANEL_GAP, midY - boxH / 2);
                Draw.bevel(ctx, boxX, boxY, showPanelW, boxH, Theme.SURFACE, Theme.SURFACE_LIT, Theme.SURFACE_SHADE, Theme.SURFACE_RING);
                showContent.layout(boxX + pad, boxY + pad - showScroll, innerW);
                ctx.enableScissor(boxX + 1, boxY + 1, boxX + showPanelW - 1, boxY + boxH - 1);
                showContent.render(ctx, mouseX, mouseY);
                ctx.disableScissor();
                if (contentH > boxH) {
                    // scrollbar
                    int thumb = Math.max(8, boxH * boxH / contentH);
                    int ty = boxY + showScroll * (boxH - thumb) / (contentH - boxH);
                    ctx.fill(boxX + showPanelW - 4, ty, boxX + showPanelW - 2, ty + thumb, Theme.TEXT_META);
                }
            });
        }
        tabX = panelOpen ? boxX + showPanelW : m;
        tabY = midY - TAB_H / 2;
        Draw.bevel4(ctx, tabX, tabY, TAB_W, TAB_H, Theme.SURFACE, Theme.SURFACE_LIT, Theme.SURFACE_SHADE,
            Theme.SURFACE_LIT_SIDE, Theme.SURFACE_SHADE_SIDE, Theme.SURFACE_RING);
        Draw.caret(ctx, tabX + TAB_W / 2, midY, panelOpen ? Draw.Dir.LEFT : Draw.Dir.RIGHT,
            onTab(mouseX, mouseY) ? Theme.TEXT_STRONG : Theme.TEXT_TITLE);

        // coords under cursor
        int[] under = blockAt(mouseX, mouseY);
        String text = under[0] + ", " + under[1];
        int pw = Fonts.width(text) + 14, ph = Fonts.height(Fonts.BODY) + 10;
        int plx = (width - pw) / 2, ply = height - m - ph - 6;
        IslesMap.panel(ctx, plx, ply, pw, ph, 1f);
        Fonts.draw(ctx, text, plx + 7, ply + 5, Theme.HUD_TEXT);

        Fonts.islesUi(() -> {
            waypointsButton.layout(width - m - waypointsButton.prefWidth() - 6, m + 6, waypointsButton.prefWidth());
            waypointsButton.render(ctx, mouseX, mouseY);
        });

        // hovered point's tooltip, drawn the same way the game draws its own
        MapPois.Shown hover = hovered(mouseX, mouseY);
        if (hover != null) ctx.drawTooltip(textRenderer, hover.tooltip().get(), mouseX, mouseY);
    }

    private boolean onTab(double mx, double my) {
        return mx >= tabX && mx < tabX + TAB_W && my >= tabY && my < tabY + TAB_H;
    }

    private boolean onPanel(double mx, double my) {
        return onTab(mx, my) || waypointsButton.contains(mx, my) || panelOpen && mx >= boxX && mx < boxX + showPanelW && my >= boxY && my < boxY + boxH;
    }

    // nearest within PICK px
    private MapPois.Shown hovered(double mx, double my) {
        if (onPanel(mx, my)) return null;
        MapPois.Shown best = null;
        double bestD = PICK * PICK;
        for (MapPois.Shown s : drawn) {
            double d = (s.sx() - mx) * (s.sx() - mx) + (s.sy() - my) * (s.sy() - my);
            if (d <= bestD) { bestD = d; best = s; }
        }
        return best;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && Fonts.islesUi(() -> waypointsButton.mouseClicked(click.x(), click.y(), 0))) {
            dragOnPanel = true;   // so it doesn't pan
            return true;
        }
        dragOnPanel = onPanel(click.x(), click.y());
        if (dragOnPanel) {
            if (onTab(click.x(), click.y())) panelOpen = !panelOpen;
            else Fonts.islesUi(() -> showContent.mouseClicked(click.x(), click.y(), click.button()));
            return true;
        }
        if (client == null || client.player == null) return super.mouseClicked(click, doubled);
        boolean left = click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            int[] b = blockAt(click.x(), click.y());
            client.setScreen(WaypointsScreen.adding(this, b[0], IslesMap.groundY(b[0], b[1], client.player.getBlockY()), b[1]));
            return true;
        }
        if (!left && click.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return super.mouseClicked(click, doubled);
        if (left) {
            if (onWaypoint(click.x(), click.y())) {
                IslesMap.clearWaypoint();
                return true;
            }
            MapPois.Shown s = hovered(click.x(), click.y());
            if (s != null && !SavedWaypoints.inWorldAt(s.x(), s.y(), s.z())) IslesMap.setWaypoint(s.x(), s.y(), s.z());
            return true;
        }
        // middle click puts a waypoint standing on that block (at your height if it's too far away to know)
        int[] b = blockAt(click.x(), click.y());
        IslesMap.setWaypoint(b[0], IslesMap.groundY(b[0], b[1], client.player.getBlockY()), b[1]);
        return true;
    }

    private int[] blockAt(double mx, double my) {
        return new int[]{(int) Math.floor(cx + (mx - width / 2.0) / ppb), (int) Math.floor(cz + (my - height / 2.0) / ppb)};
    }

    private boolean onWaypoint(double mx, double my) {
        IslesMap.Waypoint wp = IslesMap.waypoint;
        if (wp == null) return false;
        return IslesMap.near(width / 2.0 + (wp.x() + 0.5 - cx) * ppb, height / 2.0 + (wp.z() + 0.5 - cz) * ppb, mx, my, PICK);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragOnPanel) return true;
        cx -= dx / ppb;
        cz -= dy / ppb;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (onPanel(mouseX, mouseY)) {
            // scrolls the Show panel (kept in range when it's drawn), not the map
            showScroll -= (int) Math.signum(vertical) * SCROLL_STEP;
            return true;
        }
        float next = Math.max(MIN_PPB, Math.min(MAX_PPB, ppb * (vertical > 0 ? 1.25f : 0.8f)));
        // zoom towards the cursor
        double ox = mouseX - width / 2.0, oy = mouseY - height / 2.0;
        cx += ox / ppb - ox / next;
        cz += oy / ppb - oy / next;
        ppb = next;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (com.islesplusplus.IslesPlusPlus.MAP_KEY.matchesKey(input)) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() { return false; }
}
