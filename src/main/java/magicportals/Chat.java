package magicportals;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The look of all MagicPortals chat messages: a gradient prefix, grey text with highlighted values,
 * red errors and framed boxes for longer output (setup, list, settings, help).
 */
final class Chat {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Gradient of the plugin: violet → magenta → cyan, like the rift. */
    static final String GRADIENT = "#a855f7:#e879f9:#38bdf8";
    static final TextColor TEXT = TextColor.color(0xB9B4C7);
    static final TextColor VALUE = TextColor.color(0xF0D9FF);
    static final TextColor ACCENT = TextColor.color(0x38BDF8);
    static final TextColor ERROR = TextColor.color(0xFF6B81);
    static final TextColor ERROR_VALUE = TextColor.color(0xFFE0E6);
    static final TextColor SUCCESS = TextColor.color(0x6EE7A8);

    private Chat() { }

    static Component gradient(String text, boolean bold) {
        String escaped = MM.escapeTags(text);
        return MM.deserialize("<gradient:" + GRADIENT + ">" + (bold ? "<bold>" + escaped + "</bold>" : escaped) + "</gradient>");
    }

    /** "✦ MagicPortals » " */
    static final Component PREFIX = Component.text("✦ ", TextColor.color(0xE879F9))
            .append(gradient("MagicPortals", true))
            .append(Component.text(" » ", NamedTextColor.DARK_GRAY));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** A message template with {0}, {1}, … where the values are shown in a highlight colour. */
    static Component format(String template, Object[] values, TextColor base, TextColor highlight) {
        Component out = Component.empty();
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out = out.append(Component.text(template.substring(last, m.start()), base));
            int i = Integer.parseInt(m.group(1));
            String value = i < values.length ? String.valueOf(values[i]) : m.group();
            out = out.append(Component.text(value, highlight));
            last = m.end();
        }
        if (last < template.length()) out = out.append(Component.text(template.substring(last), base));
        return out;
    }

    static Component info(String template, Object... values) { return PREFIX.append(format(template, values, TEXT, VALUE)); }
    static Component success(String template, Object... values) { return PREFIX.append(format(template, values, SUCCESS, VALUE)); }
    static Component error(String template, Object... values) { return PREFIX.append(format(template, values, ERROR, ERROR_VALUE)); }

    /** A line in the plugin gradient, struck through: the frame of boxes. */
    static final Component RULE = MM.deserialize("<gradient:#2e1065:#7e22ce:#0e7490:#2e1065><st>" + " ".repeat(80) + "</st></gradient>");

    /**
     * Sends a framed box:
     *  ─────────────────────
     *                    MagicPortals TITLE
     *     subtitle
     *   lines …
     *  ─────────────────────
     */
    static void box(Audience to, String title, Component subtitle, List<Component> lines) {
        to.sendMessage(RULE);
        to.sendMessage(Component.empty());
        to.sendMessage(Component.text(" ".repeat(20)).append(gradient(title, true)));
        to.sendMessage(Component.empty());
        if (subtitle != null) {
            to.sendMessage(Component.text("   ").append(subtitle));
            to.sendMessage(Component.empty());
        }
        for (Component line : lines) to.sendMessage(Component.text(" ").append(line));
        to.sendMessage(Component.empty());
        to.sendMessage(RULE);
    }

    static Component bold(String text, TextColor color) { return Component.text(text, color, TextDecoration.BOLD); }
}
