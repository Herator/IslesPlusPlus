package com.islesplusplus.map;

import com.islesplus.screen.islesscreen.rows.GlowColorDrawer;
import com.islesplus.ui.ColorMath;
import com.islesplus.ui.Draw;
import com.islesplus.ui.Flow;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Metrics;
import com.islesplus.ui.OverlayHost;
import com.islesplus.ui.Theme;
import com.islesplus.ui.Widget;
import com.islesplus.ui.widgets.Button;
import com.islesplus.ui.widgets.ConfirmDialog;
import com.islesplus.ui.widgets.Dialog;
import com.islesplus.ui.widgets.EmptyBox;
import com.islesplus.ui.widgets.Label;
import com.islesplus.ui.widgets.Panel;
import com.islesplus.ui.widgets.SmallToggle;
import com.islesplus.ui.widgets.TextField;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

// list of saved waypoints. adding() opens just the add dialog (right click on the full map)
public final class WaypointsScreen extends Screen implements OverlayHost {
    private static final int LIST_W = 300, ROW_GAP = 4;

    private final Screen parent;
    // null = show the list
    private final int[] addAt;
    // list at the bottom, add/edit/delete dialogs over it
    private final List<Widget> overlays = new ArrayList<>();
    private final Flow.Column list = new Flow.Column(ROW_GAP);

    public WaypointsScreen(Screen parent) {
        this(parent, null);
    }

    private WaypointsScreen(Screen parent, int[] addAt) {
        super(Text.literal("Waypoints"));
        this.parent = parent;
        this.addAt = addAt;
    }

    public static WaypointsScreen adding(Screen parent, int x, int y, int z) {
        return new WaypointsScreen(parent, new int[]{x, y, z});
    }

    @Override
    protected void init() {
        if (!overlays.isEmpty()) return;   // a window resize runs init again; keep what's open
        if (addAt != null) {
            edit(null);
            return;
        }
        rebuild();
        Widget body = new Flow.Column(Metrics.DRAWER_GAP)
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Button("Add waypoint", Button.Kind.PRIMARY, () -> edit(null)).plus()))
            .add(new Scroll(list));
        openOverlay(new Dialog(this, "Waypoints", body, null).width(LIST_W));
    }

    private void rebuild() {
        list.clear();
        if (SavedWaypoints.all().isEmpty()) list.add(new EmptyBox("No waypoints yet. Add one, or right click the map."));
        for (SavedWaypoints.Waypoint w : SavedWaypoints.all()) list.add(row(w));
    }

    private Widget row(SavedWaypoints.Waypoint w) {
        Widget body = new Flow.WrapRow(ROW_GAP, ROW_GAP)
            .add(new Swatch(() -> w.color))
            .add(new NameLines(w))
            .add(new Button("Hide", Button.Kind.SECONDARY, () -> {
                w.inWorld = !w.inWorld;
                SavedWaypoints.save();
            }).small().label(() -> w.inWorld ? "Hide" : "Show"))
            .add(new Button("Edit", Button.Kind.SECONDARY, () -> edit(w)).small())
            .add(new Button("Delete", Button.Kind.QUIET, () -> ConfirmDialog.open(this, "Delete waypoint",
                "Delete " + w.name + "?", "Delete", () -> {
                    SavedWaypoints.remove(w);
                    rebuild();
                })).small());
        return new Panel(body, 5, Theme.SURFACE, Theme.RAISED, Theme.SECONDARY, Theme.SURFACE_RING);
    }

    // works on a copy, nothing changes until Save
    private void edit(SavedWaypoints.Waypoint existing) {
        SavedWaypoints.Waypoint draft;
        if (existing != null) {
            draft = existing.copy();
        } else {
            var p = client != null ? client.player : null;
            int[] at = addAt != null ? addAt
                : p != null ? new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()} : new int[]{0, 64, 0};
            draft = new SavedWaypoints.Waypoint(SavedWaypoints.nextName(), at[0], at[1], at[2]);
        }
        String[] xyz = {String.valueOf(draft.x), String.valueOf(draft.y), String.valueOf(draft.z)};
        float[] hsl = ColorMath.rgbToHsl(draft.color);
        String[] error = {""};
        Widget[] dialogRef = new Widget[1];

        int labelW = Fonts.labelColumn(Fonts.SMALL, "Name");
        Label errorLabel = new Label(() -> error[0], Theme.OXBLOOD, Fonts.SMALL);
        errorLabel.visible = false;
        Widget body = new Flow.Column(7)
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Label("Name", Theme.TEXT_LABEL, Fonts.SMALL).fixed(labelW))
                .add(new TextField(() -> draft.name, v -> draft.name = v, "Name", 32)))
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Label("X", Theme.TEXT_LABEL, Fonts.SMALL)).add(numberField(xyz, 0))
                .add(new Label("Y", Theme.TEXT_LABEL, Fonts.SMALL)).add(numberField(xyz, 1))
                .add(new Label("Z", Theme.TEXT_LABEL, Fonts.SMALL)).add(numberField(xyz, 2))
                .add(new Button("Here", Button.Kind.QUIET, () -> {
                    if (client == null || client.player == null) return;
                    xyz[0] = String.valueOf(client.player.getBlockX());
                    xyz[1] = String.valueOf(client.player.getBlockY());
                    xyz[2] = String.valueOf(client.player.getBlockZ());
                }).small()))
            .add(GlowColorDrawer.of(() -> hsl[0], v -> hsl[0] = v, () -> hsl[1], v -> hsl[1] = v,
                () -> hsl[2], v -> hsl[2] = v))   // a draft: its colour saves with the waypoint
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new SmallToggle("Show in world", () -> draft.inWorld, () -> draft.inWorld = !draft.inWorld))
                .add(new SmallToggle("Beacon beam", () -> draft.beam, () -> draft.beam = !draft.beam)))
            .add(errorLabel)
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Button("Cancel", Button.Kind.QUIET, () -> closeOverlay(dialogRef[0])).fill())
                .add(new Button("Save", Button.Kind.PRIMARY, () -> {
                    int[] at = parse(xyz);
                    error[0] = draft.name.isBlank() ? "Give it a name." : at == null ? "X, Y and Z must be whole numbers." : "";
                    errorLabel.visible = !error[0].isEmpty();
                    if (errorLabel.visible) return;
                    draft.name = draft.name.trim();
                    draft.x = at[0]; draft.y = at[1]; draft.z = at[2];
                    draft.color = ColorMath.hslToRgb(hsl[0], hsl[1], hsl[2]) & 0xFFFFFF;
                    if (existing != null) {
                        existing.copyFrom(draft);
                        SavedWaypoints.save();
                    } else {
                        SavedWaypoints.add(draft);
                        rebuild();
                    }
                    closeOverlay(dialogRef[0]);
                }).fill()));

        Dialog dialog = new Dialog(this, existing != null ? "Edit waypoint" : "Add waypoint", body, null).width(LIST_W);
        dialogRef[0] = dialog;
        openOverlay(dialog);
    }

    static int[] parse(String[] xyz) {
        try {
            return new int[]{Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TextField numberField(String[] xyz, int i) {
        return new TextField(() -> xyz[i], v -> xyz[i] = v, "0", 9) {
            @Override protected boolean acceptChar(char c) { return c == '-' || c >= '0' && c <= '9'; }
        };
    }

    private static final class Swatch extends Widget {
        private static final int SIZE = 10;
        private final IntSupplier rgb;
        Swatch(IntSupplier rgb) { this.rgb = rgb; }
        @Override public int prefWidth() { return SIZE; }
        @Override public int layout(int x, int y, int width) { this.x = x; this.y = y; w = SIZE; h = SIZE; return h; }
        @Override public void render(DrawContext ctx, int mouseX, int mouseY) {
            ctx.fill(x, y, x + w, y + h, 0xFF000000 | rgb.getAsInt());
            Draw.ring(ctx, x, y, w, h, Theme.INK_DEEP);
        }
    }

    private static final class NameLines extends Widget {
        private final SavedWaypoints.Waypoint wp;
        NameLines(SavedWaypoints.Waypoint wp) { this.wp = wp; }
        @Override public int layout(int x, int y, int width) {
            this.x = x; this.y = y; w = width;
            h = Fonts.height(Fonts.BODY) + 2 + Fonts.height(Fonts.SMALL);
            return h;
        }
        @Override public void render(DrawContext ctx, int mouseX, int mouseY) {
            Fonts.draw(ctx, Fonts.ellipsize(wp.name, w), x, y, wp.inWorld ? Theme.TEXT_STRONG : Theme.TEXT_META);
            String at = wp.x + ", " + wp.y + ", " + wp.z + (wp.inWorld ? "" : "  · hidden in world");
            Fonts.draw(ctx, Fonts.ellipsize(at, w, Fonts.SMALL), x, y + Fonts.height(Fonts.BODY) + 2, Theme.TEXT_META, Fonts.SMALL);
        }
    }

    // scrolls when it's too long
    private final class Scroll extends Widget {
        private static final int STEP = 12;
        private final Widget child;
        private int scroll, contentH;
        Scroll(Widget child) { this.child = child; }

        @Override public int layout(int x, int y, int width) {
            this.x = x; this.y = y; this.w = width;
            contentH = child.layout(x, 0, width);
            h = Math.min(contentH, Math.max(40, height - 90));
            scroll = Math.max(0, Math.min(scroll, contentH - h));
            child.layout(x, y - scroll, width);
            return h;
        }

        @Override public void render(DrawContext ctx, int mouseX, int mouseY) {
            boolean in = contains(mouseX, mouseY);
            ctx.enableScissor(x - 1, y, x + w + 1, y + h);
            child.render(ctx, in ? mouseX : -1, in ? mouseY : -1);
            ctx.disableScissor();
            if (contentH > h) {
                int thumb = Math.max(8, h * h / contentH);
                int ty = y + scroll * (h - thumb) / (contentH - h);
                ctx.fill(x + w + 2, ty, x + w + 4, ty + thumb, Theme.TEXT_META);
            }
        }

        @Override public boolean mouseClicked(double mx, double my, int button) {
            if (!contains(mx, my)) { child.unfocus(); return false; }
            return child.mouseClicked(mx, my, button);
        }
        @Override public boolean mouseScrolled(double mx, double my, double amount) {
            if (contentH <= h) return child.mouseScrolled(mx, my, amount);
            scroll -= (int) Math.signum(amount) * STEP;
            return true;
        }
        @Override public boolean mouseDragged(double mx, double my) { return child.mouseDragged(mx, my); }
        @Override public void mouseReleased() { child.mouseReleased(); }
        @Override public boolean keyPressed(KeyInput in) { return child.keyPressed(in); }
        @Override public boolean charTyped(CharInput in) { return child.charTyped(in); }
        @Override public void unfocus() { child.unfocus(); }
    }

    // ==============================
    // Overlay host, input
    // ==============================

    @Override public void openOverlay(Widget overlay) { overlays.add(overlay); }

    // closing the bottom one closes the screen
    @Override public void closeOverlay(Widget overlay) {
        int i = overlays.indexOf(overlay);
        if (i < 0) return;
        while (overlays.size() > i) overlays.remove(overlays.size() - 1).unfocus();
        if (overlays.isEmpty()) close();
    }

    @Override public int screenWidth() { return width; }
    @Override public int screenHeight() { return height; }
    @Override public void closeScreen() { close(); }

    @Override
    public void close() {
        for (Widget o : new ArrayList<>(overlays)) o.unfocus();
        overlays.clear();
        if (client != null) client.setScreen(parent);
    }

    private Widget top() { return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1); }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}   // the dialogs bring their scrim

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Fonts.islesUi(() -> {
            for (Widget o : new ArrayList<>(overlays)) {
                ctx.createNewRootLayer();
                boolean isTop = o == top();
                o.layout(0, 0, width);
                o.render(ctx, isTop ? mouseX : -1, isTop ? mouseY : -1);
            }
        });
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        Widget t = top();
        if (t != null) Fonts.islesUi(() -> t.mouseClicked(click.x(), click.y(), click.button()));
        return true;
    }

    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        Widget t = top();
        if (t != null) Fonts.islesUi(() -> t.mouseDragged(click.x(), click.y()));
        return true;
    }

    @Override public boolean mouseReleased(Click click) {
        Widget t = top();
        if (t != null) Fonts.islesUi(t::mouseReleased);
        return true;
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        Widget t = top();
        if (t != null) Fonts.islesUi(() -> t.mouseScrolled(mx, my, vertical));
        return true;
    }

    @Override public boolean keyPressed(KeyInput input) {
        Widget t = top();
        if (t == null) return super.keyPressed(input);
        Fonts.islesUi(() -> {
            if (!t.keyPressed(input) && input.key() == GLFW.GLFW_KEY_ESCAPE) closeOverlay(t);
        });
        return true;
    }

    @Override public boolean charTyped(CharInput input) {
        Widget t = top();
        if (t != null) Fonts.islesUi(() -> t.charTyped(input));
        return true;
    }

    @Override public boolean shouldPause() { return false; }
}
