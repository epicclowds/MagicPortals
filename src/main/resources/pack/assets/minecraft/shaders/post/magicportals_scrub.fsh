#version 330
#extension GL_ARB_separate_shader_objects : require

// Removes the MagicPortals marker data row from the outline buffer,
// so no glow outline is drawn there.

#include <minecraft:magicportals_marker.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 color = texture(InSampler, texCoord);
    if (at.y <= 1 && at.x < DP_PIXEL + 1) {
        color = vec4(0.0);
    }
    fragColor = color;
}
