import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/* Kompiliert die Dorfportal-Shader vorab mit shaderc (wie der Minecraft-Client), damit
 * Fehler nicht erst im Spiel auffallen.
 * Aufruf: java ShaderPruefen <shader-paket> <include-Ordner ...> */
public final class ShaderPruefen {
    static List<Path> includeOrdner = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        Path paket = Path.of(a[0]).resolve("assets/minecraft/shaders");
        includeOrdner.add(paket.resolve("include"));
        for (int i = 1; i < a.length; i++) includeOrdner.add(Path.of(a[i]));
        long compiler = shaderc_compiler_initialize();
        long optionen = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(optionen, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_auto_bind_uniforms(optionen, true);
        shaderc_compile_options_set_auto_map_locations(optionen, true);
        int fehler = 0;
        try (var dateien = Files.walk(paket)) {
            for (Path p : dateien.filter(f -> f.toString().endsWith(".vsh") || f.toString().endsWith(".fsh")).toList()) {
                String quelle = vorverarbeiten(Files.readString(p), new HashSet<>());
                quelle = quelle.replaceFirst("#version 330", "#version 450");
                int art = p.toString().endsWith(".vsh") ? shaderc_vertex_shader : shaderc_fragment_shader;
                String src = quelle;
                long ergebnis = shaderc_compile_into_spv(compiler, src, art, paket.relativize(p).toString(), "main", optionen);
                int status = shaderc_result_get_compilation_status(ergebnis);
                String meldung = shaderc_result_get_error_message(ergebnis);
                if (status != shaderc_compilation_status_success) { fehler++; System.out.println("FEHLER " + paket.relativize(p) + ":\n" + meldung); }
                else System.out.println("ok     " + paket.relativize(p) + (meldung != null && !meldung.isBlank() ? "  (" + meldung.trim() + ")" : ""));
                shaderc_result_release(ergebnis);

            }
        }
        shaderc_compile_options_release(optionen);
        shaderc_compiler_release(compiler);
        System.exit(fehler == 0 ? 0 : 1);
    }

    static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s*<(?:minecraft:)?([^>]+)>\\s*$");

    static String vorverarbeiten(String text, Set<String> gesehen) throws Exception {
        Matcher m = INCLUDE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String ersatz = "";
            if (gesehen.add(name)) {
                Path gefunden = null;
                for (Path o : includeOrdner) if (Files.exists(o.resolve(name))) { gefunden = o.resolve(name); break; }
                if (gefunden == null) throw new IllegalStateException("Include nicht gefunden: " + name);
                ersatz = vorverarbeiten(Files.readString(gefunden), gesehen);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(ersatz));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
