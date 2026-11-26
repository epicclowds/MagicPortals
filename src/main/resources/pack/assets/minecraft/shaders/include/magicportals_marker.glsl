#ifndef MAGICPORTALS_MARKER_GLSL
#define MAGICPORTALS_MARKER_GLSL

// Shared definitions between the marker (outline shader) and the post shader.
// Marker glow colour: R=254, B=160+scene+16*facing (scene 0..15; facing 0 south, 1 west, 2 north, 3 east),
// G = 1 + style (0 gate, 1 rift) + 2*(no inside view) + 4*(width-1) + 32*(height-1), width/height 1..7.
#define DP_PIXEL 32
#define DP_MAGIC 1234.5

// Returns scene + 16 * facing, or -1 if the colour is not a portal marker.
int dp_marker_scene(vec4 color) {
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r == 254 && c.g >= 1 && c.g <= 224 && c.b >= 160 && c.b < 224) {
        return c.b - 160;
    }
    return -1;
}

int dp_marker_code(vec4 color) {
    return int(round(color.g * 255.0));
}

vec4 dp_pack(float value) {
    uint b = floatBitsToUint(value);
    return vec4(float((b >> 24) & 255u), float((b >> 16) & 255u), float((b >> 8) & 255u), float(b & 255u)) / 255.0;
}

float dp_unpack(vec4 t) {
    uvec4 b = uvec4(round(clamp(t, 0.0, 1.0) * 255.0));
    return uintBitsToFloat((b.r << 24) | (b.g << 16) | (b.b << 8) | b.a);
}

#endif
