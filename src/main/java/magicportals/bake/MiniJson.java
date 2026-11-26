package magicportals.bake;

import java.util.*;

/** Minimal JSON reader for Minecraft's blockstate and model files (objects, arrays, strings, numbers, booleans). */
final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) { this.s = s; }

    static Object parse(String text) { return new MiniJson(text).value(); }

    private Object value() {
        skipWhitespace();
        char c = s.charAt(p);
        if (c == '{') {
            p++;
            var map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (s.charAt(p) == '}') { p++; return map; }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                p++;   // ':'
                map.put(key, value());
                skipWhitespace();
                if (s.charAt(p++) == '}') return map;
            }
        }
        if (c == '[') {
            p++;
            var list = new ArrayList<Object>();
            skipWhitespace();
            if (s.charAt(p) == ']') { p++; return list; }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (s.charAt(p++) == ']') return list;
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", p)) { p += 4; return true; }
        if (s.startsWith("false", p)) { p += 5; return false; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        return Double.parseDouble(s.substring(start, p));
    }

    private String string() {
        p++;
        var b = new StringBuilder();
        while (s.charAt(p) != '"') {
            char c = s.charAt(p++);
            if (c == '\\') {
                char e = s.charAt(p++);
                b.append(e == 'n' ? '\n' : e == 'u' ? (char) Integer.parseInt(s.substring(p, p += 4), 16) : e);
            } else b.append(c);
        }
        p++;
        return b.toString();
    }

    private void skipWhitespace() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
}
