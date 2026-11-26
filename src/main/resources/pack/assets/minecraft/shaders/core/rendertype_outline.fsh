#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:magicportals_marker.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 6) flat in int dpMarker;
layout(location = 7) flat in vec4 dpD0;
layout(location = 8) flat in vec4 dpD1;
layout(location = 9) flat in vec4 dpD2;
layout(location = 10) flat in vec4 dpD3;
layout(location = 11) flat in vec4 dpD4;
layout(location = 12) flat in vec4 dpD5;
layout(location = 13) flat in vec4 dpD6;
layout(location = 14) flat in vec4 dpD7;

layout(location = 0) out vec4 fragColor;

float dp_wert(int i) {
    vec4 v = i < 4 ? dpD0 : i < 8 ? dpD1 : i < 12 ? dpD2 : i < 16 ? dpD3
           : i < 20 ? dpD4 : i < 24 ? dpD5 : i < 28 ? dpD6 : dpD7;
    return v[i & 3];
}

void main() {
    if (dpMarker == 1) {
        // Every pixel of the data row carries exactly one float, bit-exact in RGBA.
        int i = clamp(int(gl_FragCoord.x), 0, DP_PIXEL - 1);
        fragColor = dp_packen(dp_wert(i));
        return;
    }
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
}
