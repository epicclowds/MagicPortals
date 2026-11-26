#ifndef MAGICPORTALS_MARKER_GLSL
#define MAGICPORTALS_MARKER_GLSL

// Shared definitions between the marker (outline shader) and the post shader.
// Marker glow colour: R=254, G=1+style (1 gate, 2 rift), B=160+scene (scene 0..15).
#define DP_PIXEL 32
#define DP_MAGIC 1234.5

int dp_marker_szene(vec4 farbe) {
    ivec3 c = ivec3(round(farbe.rgb * 255.0));
    if (c.r == 254 && c.g >= 1 && c.g <= 2 && c.b >= 160 && c.b < 176) {
        return c.b - 160;
    }
    return -1;
}

int dp_marker_stil(vec4 farbe) {
    return int(round(farbe.g * 255.0)) - 1;
}

vec4 dp_packen(float wert) {
    uint b = floatBitsToUint(wert);
    return vec4(float((b >> 24) & 255u), float((b >> 16) & 255u), float((b >> 8) & 255u), float(b & 255u)) / 255.0;
}

float dp_entpacken(vec4 t) {
    uvec4 b = uvec4(round(clamp(t, 0.0, 1.0) * 255.0));
    return uintBitsToFloat((b.r << 24) | (b.g << 16) | (b.b << 8) | b.a);
}

#endif
