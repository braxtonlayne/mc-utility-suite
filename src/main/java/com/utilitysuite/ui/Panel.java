package com.utilitysuite.ui;

import com.utilitysuite.ui.kit.BoxNode;
import com.utilitysuite.ui.kit.CardNode;
import com.utilitysuite.ui.kit.CollapsibleSection;
import com.utilitysuite.ui.kit.CycleSelector;
import com.utilitysuite.ui.kit.LabelNode;
import com.utilitysuite.ui.kit.ListRowNode;
import com.utilitysuite.ui.kit.ModernSlider;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.StyledTextField;
import com.utilitysuite.ui.kit.ToggleSwitch;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builder for the scrolling content canvas of the menu. Tab builders append cards top to bottom; inside a card they
 * add text lines and rows of custom widgets ({@link PillButton}, {@link ToggleSwitch}, {@link ModernSlider},
 * {@link CycleSelector}, {@link StyledTextField}, list rows, collapsible sections). Every node gets a fixed layout box
 * in content coordinates when it is added; the screen only offsets by the eased scroll while drawing, so steady-state
 * frames allocate nothing. {@link #width()} is the inner width of the open card (or the canvas outside cards), so
 * {@code dx}/{@code w} arithmetic is relative to the card's content edge.
 * <p>
 * Also the extension surface for {@link ModulePanels}: a custom module panel receives this object while its module's
 * card is open.
 */
public final class Panel {

    /** Row pitch of widget rows. */
    public static final int ROW = 22;
    /** Pitch of text lines. */
    public static final int LINE = 12;
    /** Height of text fields; pills, selectors and sliders are 14-16 px and centered in the row. */
    public static final int WIDGET_H = StyledTextField.H;

    public static final int WHITE = UiTheme.TEXT_SECONDARY;
    public static final int GRAY = UiTheme.TEXT_MUTED;
    public static final int DIM = UiTheme.TEXT_DIM;
    public static final int HEADING = UiTheme.TEXT;
    public static final int RED = UiTheme.DANGER;
    public static final int GREEN = UiTheme.RUNNING;
    public static final int YELLOW = UiTheme.WARNING;
    public static final int ACCENT = UiTheme.ACCENT;

    private static final int BODY_PAD = 8;

    private final SuiteMenuScreen screen;
    private final Font font;
    private final int contentW;
    private final List<UiNode> nodes = new ArrayList<>();
    private final List<StyledTextField> fields = new ArrayList<>();
    private final List<CollapsibleSection> sections = new ArrayList<>();
    private int y;
    private boolean lastWasSection;

    // open card
    private CardNode card;
    private int cardTop;
    private boolean bodyEmpty;
    private int originX;
    private int innerW;
    // open collapsible block
    private BoxNode inset;
    private int insetTop;

    Panel(SuiteMenuScreen screen, Font font, int contentW) {
        this.screen = screen;
        this.font = font;
        this.contentW = contentW;
        this.innerW = contentW;
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    public Font font() {
        return font;
    }

    /** Usable width: the open card's inner width, or the canvas width outside cards. */
    public int width() {
        return card != null ? innerW : contentW;
    }

    /** Width of the whole canvas (cards span it). */
    public int canvasWidth() {
        return contentW;
    }

    /** Logical height of the content so far. */
    public int contentHeight() {
        return y;
    }

    /** Current logical y (top of the next row). */
    public int y() {
        return y;
    }

    // ---- structure --------------------------------------------------------------------------------------------------

    /** A muted label between cards ("Job modules"). Closes the open card. */
    public void section(String label) {
        endCard();
        if (y > 0) {
            y += 12;
        }
        LabelNode l = new LabelNode(font, label, UiTheme.TEXT_DIM, contentW);
        add(l, 2, y);
        y += 14;
        lastWasSection = true;
    }

    /** Opens a card with a title (closes the previous one). */
    public CardNode card(String title, String subtitle) {
        return card(title, subtitle, 0, CardNode.Tone.NORMAL);
    }

    /** Opens a card; {@code reserveRight} keeps the header text clear of header controls. */
    public CardNode card(String title, String subtitle, int reserveRight, CardNode.Tone tone) {
        endCard();
        if (y > 0 && !lastWasSection) {
            y += UiTheme.CARD_GAP;
        }
        lastWasSection = false;
        card = new CardNode(font, title, subtitle, contentW, reserveRight, tone);
        add(card, 0, y);
        cardTop = y;
        originX = UiTheme.CARD_PAD;
        innerW = contentW - 2 * UiTheme.CARD_PAD;
        bodyEmpty = true;
        y += card.hasHeader() ? CardNode.HEADER_H : UiTheme.CARD_PAD - BODY_PAD;
        return card;
    }

    /** The legacy section heading: a new card with this title. */
    public void heading(String text) {
        card(text, "");
    }

    /** A switch at the right end of the open card's header row. */
    public ToggleSwitch cardToggle(boolean value, String animKey, Consumer<Boolean> onChange) {
        ToggleSwitch t = new ToggleSwitch(value, onChange).animKey(animKey);
        add(t, contentW - UiTheme.CARD_PAD - ToggleSwitch.W, cardTop + (CardNode.HEADER_H - ToggleSwitch.H) / 2);
        return t;
    }

    /** A node placed in the open card's header row, right-aligned before {@code rightInset} px. */
    public <T extends UiNode> T headerNode(T node, int rightInset) {
        add(node, contentW - UiTheme.CARD_PAD - rightInset - node.w, cardTop + (CardNode.HEADER_H - node.h) / 2);
        return node;
    }

    /** Closes the open card (sets its height). Called automatically by the next card or section. */
    public void endCard() {
        if (card == null) {
            return;
        }
        endCollapsible();
        if (bodyEmpty) {
            if (!card.hasHeader()) {
                y += BODY_PAD;
            }
        } else {
            y += BODY_PAD;
            if (card.hasHeader()) {
                card.divider(CardNode.HEADER_H);
            }
        }
        card.h = Math.max(card.hasHeader() ? CardNode.HEADER_H : 2 * UiTheme.CARD_PAD, y - cardTop);
        y = cardTop + card.h;
        card = null;
        originX = 0;
        innerW = contentW;
    }

    private void body() {
        if (card != null && bodyEmpty) {
            bodyEmpty = false;
            y += BODY_PAD;
        }
    }

    /** A node outside cards spanning the canvas (e.g. the goal hero card); returns its top y. */
    public int block(UiNode node) {
        endCard();
        if (y > 0 && !lastWasSection) {
            y += UiTheme.CARD_GAP;
        }
        lastWasSection = false;
        int top = y;
        node.w = contentW;
        add(node, 0, y);
        y += node.h;
        return top;
    }

    /** Places a node at absolute content coordinates (children of a {@link #block}). */
    public <T extends UiNode> T placeAbs(T node, int x, int yAbs) {
        add(node, x, yAbs);
        return node;
    }

    // ---- content ----------------------------------------------------------------------------------------------------

    public void text(String text) {
        text(text, WHITE);
    }

    public void text(String text, int color) {
        textIndented(text, color, 0);
    }

    public void textIndented(String text, int color, int indent) {
        body();
        add(new LabelNode(font, text, color, width() - indent), originX + indent, y + 2);
        y += LINE;
    }

    /** Word-wrapped text over as many lines as it needs. */
    public void paragraph(String text, int color) {
        body();
        for (FormattedText line : font.getSplitter().splitLines(text == null ? "" : text, width(), Style.EMPTY)) {
            add(new LabelNode(font, line.getString(), color, width()), originX, y + 2);
            y += LINE;
        }
        y += 1;
    }

    /** A line whose text is re-read every few ticks (e.g. "Now: Mining at Y=-54"). */
    public LabelNode dynamic(Supplier<String> source, int color) {
        body();
        LabelNode l = new LabelNode(font, null, source, color, width());
        add(l, originX, y + 2);
        y += LINE;
        return l;
    }

    /** A small muted group heading inside a card. */
    public void subheading(String text) {
        body();
        y += 6;
        add(new LabelNode(font, text, UiTheme.ACCENT, width()), originX, y + 2);
        int tw = font.width(text) + 8;
        if (tw < width() - 10) {
            add(new BoxNode(width() - tw, 1, 0, 0xFF262B36, 0, UiRenderUtils.ALL), originX + tw, y + 6);
        }
        y += LINE + 2;
    }

    public void gap(int pixels) {
        y += pixels;
    }

    /** Hairline across the open card. */
    public void divider() {
        body();
        y += 4;
        add(new BoxNode(width(), 1, 0, 0xFF22262F, 0, UiRenderUtils.ALL), originX, y);
        y += 5;
    }

    /** Places a node on the current row at {@code dx}, vertically centered; rows advance with {@link #nextRow()}. */
    public <T extends UiNode> T place(T node, int dx) {
        body();
        add(node, originX + dx, y + (ROW - node.h) / 2);
        return node;
    }

    /** A label at the current row's text line (left of a widget). */
    public LabelNode rowLabel(String text, int dx, int maxWidth, int color) {
        body();
        LabelNode l = new LabelNode(font, text, color, Math.max(0, maxWidth));
        add(l, originX + dx, y + (ROW - 8) / 2);
        return l;
    }

    public void nextRow() {
        y += ROW;
    }

    /** Background of a list row on the current row (hover highlight, separator); {@code onPress} may be null. */
    public ListRowNode listRow(Consumer<ListRowNode> onPress) {
        body();
        ListRowNode r = new ListRowNode(width() + 8, ROW - 2, onPress);
        add(r, originX - 4, y + 1);
        return r;
    }

    // ---- widget helpers ---------------------------------------------------------------------------------------------

    public PillButton button(String label, int dx, int w, Consumer<PillButton> onPress) {
        return button(label, null, dx, w, PillButton.Style.SECONDARY, onPress);
    }

    public PillButton button(String label, String tooltip, int dx, int w, Consumer<PillButton> onPress) {
        return button(label, tooltip, dx, w, PillButton.Style.SECONDARY, onPress);
    }

    public PillButton button(String label, String tooltip, int dx, int w, PillButton.Style style,
                             Consumer<PillButton> onPress) {
        PillButton b = new PillButton(font, label, w, style, onPress);
        if (tooltip != null) {
            b.tooltip(tooltip);
        }
        return place(b, dx);
    }

    /** A label with a switch at the right end of the {@code w}-wide box starting at {@code dx}. */
    public ToggleSwitch toggle(String label, boolean value, int dx, int w, Consumer<Boolean> onChange) {
        if (label != null && !label.isEmpty()) {
            rowLabel(label, dx, w - ToggleSwitch.W - 6, WHITE);
        }
        return place(new ToggleSwitch(value, onChange), dx + w - ToggleSwitch.W);
    }

    /**
     * A styled edit box whose text survives rebuilds and reopening the menu (stored in the screen's state under
     * {@code stateKey}); focus is restored after a rebuild.
     */
    public StyledTextField textField(String stateKey, String hint, int dx, int w) {
        StyledTextField f = field(stateKey, hint, dx, w);
        f.box().setValue(screen.state(stateKey, ""));
        f.box().setResponder(v -> screen.setState(stateKey, v));
        return f;
    }

    /** A styled edit box without stored state; {@code focusKey} restores focus across rebuilds. */
    public StyledTextField field(String focusKey, String hint, int dx, int w) {
        StyledTextField f = new StyledTextField(font, focusKey, hint, w, StyledTextField.H);
        f.box().setMaxLength(256);
        fields.add(f);
        return place(f, dx);
    }

    public <E> CycleSelector<E> cycle(List<E> values, E current, Function<E, String> label, int dx, int w,
                                      Consumer<E> onChange) {
        return place(new CycleSelector<>(font, values, current, label, w, onChange), dx);
    }

    public ModernSlider slider(ModernSlider.Model model, int dx, int w) {
        return place(new ModernSlider(font, model, w), dx);
    }

    /**
     * A collapsible block header ("Advanced settings · n"), collapsed by default; its state is kept under
     * {@code key}. Returns whether it is open: the caller then adds the block's rows, closed by
     * {@link #endCollapsible()} (or the end of the card).
     */
    public boolean collapsible(String key, String title, int count) {
        body();
        endCollapsible();
        boolean open = flag(key);
        y += 2;
        CollapsibleSection s = new CollapsibleSection(font, title, count, open, width() + 8, () -> {
            setFlag(key, !open);
            rebuild();
        });
        add(s, originX - 4, y);
        sections.add(s);
        y += CollapsibleSection.H;
        if (open) {
            inset = new BoxNode(width() + 8, 0, UiTheme.RADIUS_SMALL, UiTheme.INSET, 0,
                    UiRenderUtils.BL | UiRenderUtils.BR);
            add(inset, originX - 4, y);
            insetTop = y;
            y += 2;
        }
        return open;
    }

    public void endCollapsible() {
        if (inset != null) {
            y += 4;
            inset.h = y - insetTop;
            inset = null;
        }
    }

    // ---- screen state -----------------------------------------------------------------------------------------------

    public String state(String key, String def) {
        return screen.state(key, def);
    }

    public void setState(String key, String value) {
        screen.setState(key, value);
    }

    public boolean flag(String key) {
        return "1".equals(screen.state(key, ""));
    }

    public void setFlag(String key, boolean value) {
        screen.setState(key, value ? "1" : "");
    }

    /** Rebuilds the screen's content on the next tick (after a structural change). */
    public void rebuild() {
        screen.requestRebuild();
    }

    /** Shows a short status message at the bottom of the menu. */
    public void toast(String message) {
        screen.setStatus(message);
    }

    // ---- results ----------------------------------------------------------------------------------------------------

    private void add(UiNode n, int x, int yy) {
        n.x = x;
        n.y = yy;
        nodes.add(n);
    }

    /** Finishes the layout (closes the open card) and returns the content height. */
    int finish() {
        endCard();
        return y;
    }

    List<UiNode> nodes() {
        return nodes;
    }

    List<StyledTextField> fields() {
        return fields;
    }

    List<CollapsibleSection> sections() {
        return sections;
    }
}
