import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/* Compiles the MagicPortals shaders offline with shaderc (the same compiler the Minecraft client uses),
 * so errors show up before a client rejects the resource pack.
 *
 * Usage (classpath: lwjgl + lwjgl-shaderc jars incl. natives, e.g. from your launcher's libraries folder):
 *   java -cp <lwjgl jars>;. ShaderCheck <pack folder with assets/> <include folder> [<include folder> ...]
 * Include folders are searched in order, e.g. the vanilla shaders/include of the client jar
 * and, if used, Ultimate UI's include folder (for hud.glsl). */
public final class ShaderCheck {
    private static final List<Path> includeFolders = new ArrayList<>();
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s*<(?:minecraft:)?([^>]+)>\\s*$");

    public static void main(String[] args) throws Exception {
        Path shaders = Path.of(args[0]).resolve("assets/minecraft/shaders");
        includeFolders.add(shaders.resolve("include"));
        for (int i = 1; i < args.length; i++) includeFolders.add(Path.of(args[i]));
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_auto_bind_uniforms(options, true);
        shaderc_compile_options_set_auto_map_locations(options, true);
        int errors = 0;
        try (var files = Files.walk(shaders)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".vsh") || f.toString().endsWith(".fsh")).toList()) {
                String source = expandIncludes(Files.readString(p), new HashSet<>()).replaceFirst("#version 330", "#version 450");
                int kind = p.toString().endsWith(".vsh") ? shaderc_vertex_shader : shaderc_fragment_shader;
                long result = shaderc_compile_into_spv(compiler, source, kind, shaders.relativize(p).toString(), "main", options);
                String message = shaderc_result_get_error_message(result);
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    errors++;
                    System.out.println("ERROR  " + shaders.relativize(p) + ":\n" + message);
                } else System.out.println("ok     " + shaders.relativize(p) + (message != null && !message.isBlank() ? "  (" + message.trim() + ")" : ""));
                shaderc_result_release(result);
            }
        }
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
        System.exit(errors == 0 ? 0 : 1);
    }

    private static String expandIncludes(String text, Set<String> seen) throws Exception {
        Matcher m = INCLUDE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1), replacement = "";
            if (seen.add(name)) {
                Path found = null;
                for (Path folder : includeFolders) if (Files.exists(folder.resolve(name))) { found = folder.resolve(name); break; }
                if (found == null) throw new IllegalStateException("Include not found: " + name);
                replacement = expandIncludes(Files.readString(found), seen);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
