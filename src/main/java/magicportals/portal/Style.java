package magicportals.portal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The look of a portal. The index is sent to the shader through the marker colour. */
public enum Style {
    /** Opening in a thick stone wall. */
    GATE("gate", "tor"),
    /** Floating, jagged dimensional crack. */
    RIFT("rift", "riss"),
    /** Burning arch with rising flames and embers – for the Nether. */
    INFERNO("inferno", "fire", "feuer", "nether"),
    /** Crackling oval with lightning arcs and strikes from the sky – for a boss chamber. */
    STORM("storm", "sturm", "gewitter", "lightning", "blitz"),
    /** Round swirling vortex that twists the view – for the End or a void world. */
    VOID("void", "vortex", "leere", "ender"),
    /** Oval overgrown with ice crystals, cold mist and glitter. */
    FROST("frost", "ice", "eis");

    public final String id;
    private final List<String> aliases;

    Style(String id, String... aliases) { this.id = id; this.aliases = List.of(aliases); }

    public int index() { return ordinal(); }

    /** The style for a name or alias (English or German), or null. */
    public static Style parse(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (Style s : values()) if (s.id.equals(t) || s.aliases.contains(t)) return s;
        return null;
    }

    public static List<String> ids() { return Arrays.stream(values()).map(s -> s.id).toList(); }
}
