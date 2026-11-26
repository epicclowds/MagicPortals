#version 330
#extension GL_ARB_separate_shader_objects : require

// MagicPortals: draws the destination world into the portal opening.
// Camera and projection come from the marker's data row (entity_outline buffer),
// the scene itself is a baked voxel texture in the resource pack.
// Coordinates ("local"): world axes, origin at the lower west corner of the opening on the portal plane.
// The opening spans x 0..3, y 0..3 at z = 0; the destination world readData at z < 0.

#include <minecraft:magicportals_marker.glsl>
#include <minecraft:magicportals_scene.glsl>

uniform sampler2D InSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D DataSampler;
uniform sampler2D VoxelSampler;
uniform sampler2D PaletteSampler;
uniform sampler2D AtlasSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Opening size and options of the portal being drawn, decoded from the marker in main().
float OPENING_W = 3.0, OPENING_H = 3.0;
int gridX0 = DP_RX0 + 1;
const float INSIDE_DEPTH = 3.0;   // camera counts as "inside" up to this depth behind the opening

float readData(int i) {
    return dp_unpack(texelFetch(DataSampler, ivec2(i, 0), 0));
}

int byte8(float v) { return int(round(v * 255.0)); }

// ---------- scene data ----------

ivec4 voxel(ivec3 cell, int scene) {
    int i = cell.x - gridX0;
    int j = cell.y - DP_GY0;
    int k = -cell.z - 1 + DP_DB;   // rows 0..DF-1 lie in front of the plane (the area behind the destination portal)
    if (i < 0 || j < 0 || k < 0 || i >= DP_W || j >= DP_H || k >= DP_DT) return ivec4(-1);
    ivec2 at = ivec2(i + (j % 8) * DP_W, k + (j / 8) * DP_DT + scene * DP_DT * DP_HB);
    vec4 t = texelFetch(VoxelSampler, at, 0);
    return ivec4(byte8(t.r) | (byte8(t.g) << 8), byte8(t.b), 0, 0);
}

vec4 palette(int entry, int column) {
    return texelFetch(PaletteSampler, ivec2(column, entry), 0);
}

int tileIndex(int entry, int face) {
    vec4 t = palette(entry, 5 + face);
    return byte8(t.r) * 256 + byte8(t.g);
}

vec4 atlas(int k, vec2 uv) {
    ivec2 at = ivec2((k % DP_ATLAS_COLUMNS) * 16, (k / DP_ATLAS_COLUMNS) * 16)
             + clamp(ivec2(floor(uv * 16.0)), ivec2(0), ivec2(15));
    return texelFetch(AtlasSampler, at, 0);
}

// faces: 0 up, 1 down, 2 north(-z), 3 south(+z), 4 east(+x), 5 west(-x)
vec2 faceUV(int f, vec3 p) {
    if (f == 0) return vec2(p.x, p.z);
    if (f == 1) return vec2(p.x, 1.0 - p.z);
    if (f == 2) return vec2(1.0 - p.x, 1.0 - p.y);
    if (f == 3) return vec2(p.x, 1.0 - p.y);
    if (f == 4) return vec2(1.0 - p.z, 1.0 - p.y);
    return vec2(p.z, 1.0 - p.y);
}

float faceShade(int f) {
    if (f == 0) return 1.0;
    if (f == 1) return 0.5;
    if (f <= 3) return 0.8;
    return 0.6;
}

float lightFactor(int level) {
    return mix(0.18, 1.0, pow(0.82, float(15 - level)));
}

vec3 sky(vec3 d) {
    return mix(DP_HORIZON, DP_ZENITH, smoothstep(-0.05, 0.55, d.y));
}

// Fog towards the horizon. Backwards (+z) the scene only reaches DP_DB blocks,
// so the fog starts earlier there to hide the edge.
float fog(float dist, vec3 d) {
    float fogEnd = d.z > 0.0 ? mix(float(DP_D) * 0.97, float(DP_DB) * 0.95, smoothstep(0.0, 0.5, d.z)) : float(DP_D) * 0.97;
    return smoothstep(fogEnd * 0.5, fogEnd, dist);
}

bool inOpening(vec2 p) {
    return p.x >= 0.0 && p.x <= OPENING_W && p.y >= 0.0 && p.y <= OPENING_H;
}

// Ray vs. box inside one cell. Returns t and face, t < 0 if missed.
vec2 boxHit(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / d;
    vec3 t0 = (lo - o) * inv, t1 = (hi - o) * inv;
    vec3 tn = min(t0, t1), tf = max(t0, t1);
    float eye = max(max(tn.x, tn.y), tn.z), far = min(min(tf.x, tf.y), tf.z);
    if (eye > far || far < 0.0) return vec2(-1.0);
    float f;
    if (eye == tn.x) f = d.x > 0.0 ? 5.0 : 4.0;
    else if (eye == tn.y) f = d.y > 0.0 ? 1.0 : 0.0;
    else f = d.z > 0.0 ? 2.0 : 3.0;
    return vec2(max(eye, 0.0), f);
}

/* Marches a ray from start (local, z < 0) through the voxel scene (DDA). The scene also extends in
 * front of the plane, so the in-between space shows the destination world all around. */
vec3 march(vec3 start, vec3 d, int scene) {
    vec3 o0 = start;
    ivec3 cell = ivec3(floor(o0));
    cell.z = min(cell.z, -1);
    ivec3 stepDir = ivec3(sign(d));
    vec3 tDelta = abs(1.0 / d);
    vec3 bound = vec3(cell) + max(vec3(stepDir), vec3(0.0));
    vec3 tMax = (bound - o0) / d;

    vec3 color = vec3(0.0);
    float rest = 1.0;
    int prevLight = 15;
    bool prevFluid = false;
    float tRay = 0.0;

    for (int n = 0; n < 340; n++) {
        ivec4 v = voxel(cell, scene);
        if (v.x < 0) break;
        int entry = v.x;
        int lightLevel = v.y & 15;
        bool fluidHere = false;
        vec3 local = o0 + d * tRay - vec3(cell);

        if (entry != 0) {
            vec4 p0 = palette(entry, 0);
            int shape = byte8(p0.r);
            bool fluid = (byte8(p0.g) & 1) != 0;
            fluidHere = fluid;
            vec3 tint = palette(entry, 3).rgb;
            int tintMask = byte8(palette(entry, 4).r);

            if (shape == 1 && !(fluid && prevFluid)) {
                vec3 lo = palette(entry, 1).rgb * 255.0 / 16.0;
                vec3 hi = palette(entry, 2).rgb * 255.0 / 16.0;
                if (fluid && (v.y & 16) != 0) hi.y = 1.0;
                vec2 q = boxHit(local, d, lo, hi);
                if (q.x >= 0.0) {
                    int f = int(q.y);
                    vec3 p = local + d * q.x;
                    vec4 tex = atlas(tileIndex(entry, f), faceUV(f, clamp(p, vec3(0.0), vec3(0.9999))));
                    if (tex.a > 0.5) {
                        vec3 c = tex.rgb;
                        if (((tintMask >> f) & 1) != 0) c *= tint;
                        c *= faceShade(f) * lightFactor(f == 0 || fluid ? max(lightLevel, prevLight) : prevLight);
                        float haze = fog(tRay + q.x, d);
                        c = mix(c, sky(d), haze);
                        if (fluid) {
                            color += rest * 0.62 * c;
                            rest *= 0.38;
                        } else {
                            return color + rest * c;
                        }
                    }
                }
            } else if (shape == 2) {
                // plants: two vertical diagonal planes
                float bestT = 1e9; vec3 bestC = vec3(0.0);
                for (int e = 0; e < 2; e++) {
                    vec3 planeN = e == 0 ? vec3(0.7071, 0.0, -0.7071) : vec3(0.7071, 0.0, 0.7071);
                    float planeD = e == 0 ? 0.0 : 0.7071;
                    float dn = dot(d, planeN);
                    if (abs(dn) < 1e-5) continue;
                    float t = (planeD - dot(local, planeN)) / dn;
                    if (t < 0.0 || t >= bestT) continue;
                    vec3 p = local + d * t;
                    if (any(lessThan(p, vec3(0.0))) || any(greaterThan(p, vec3(1.0)))) continue;
                    float u = e == 0 ? p.x : 1.0 - p.x;
                    vec4 tex = atlas(tileIndex(entry, 2), vec2(u, 1.0 - p.y));
                    if (tex.a < 0.5) continue;
                    vec3 c = tex.rgb;
                    if ((tintMask & 1) != 0) c *= tint;
                    bestT = t; bestC = c * 0.85 * lightFactor(lightLevel);
                }
                if (bestT < 1e8) {
                    float haze = fog(tRay + bestT, d);
                    return color + rest * mix(bestC, sky(d), haze);
                }
            }
        }
        prevFluid = fluidHere;
        prevLight = entry == 0 || fluidHere ? lightLevel : prevLight;

        if (tMax.x < tMax.y && tMax.x < tMax.z) { tRay = tMax.x; tMax.x += tDelta.x; cell.x += stepDir.x; }
        else if (tMax.y < tMax.z) { tRay = tMax.y; tMax.y += tDelta.y; cell.y += stepDir.y; }
        else { tRay = tMax.z; tMax.z += tDelta.z; cell.z += stepDir.z; }
    }
    return color + rest * sky(d);
}

// ---------- style "rift": torn dimensional crack with an electric edge ----------

float hash1(float n) { return fract(sin(n * 127.1) * 43758.5453); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise1(float x) { float i = floor(x), f = fract(x); return mix(hash1(i), hash1(i + 1.0), f * f * (3.0 - 2.0 * f)); }
float noise2(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), f.x), mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), f.x), f.y);
}

vec2 RIFT_CENTER = vec2(1.5, 2.05);
const float RIFT_BOTTOM = -0.25;    // lower tip (just below the floor)
float RIFT_HEIGHT = 4.6;            // up to the upper tip
float RIFT_HALF_WIDTH = 0.8;        // half width at the widest point

// noise with hard kinks (linear instead of smooth interpolation)
float kinkNoise(float x) { float i = floor(x); return mix(hash1(i), hash1(i + 1.0), fract(x)); }

// centre line of the rift: zig-zag like a lightning crack
float riftCenterLine(float t) {
    float scale = RIFT_HALF_WIDTH / 0.8;
    return RIFT_CENTER.x + scale * (0.5 * (kinkNoise(t * 4.0 + 2.0) - 0.5) + 0.12 * (kinkNoise(t * 11.0 + 5.0) - 0.5));
}

// Distance to the rift edge in blocks (negative = inside). Base shape is a tall, narrow slit,
// pointed at both ends, widest in the middle. Both edges are torn independently.
float riftDistance(vec2 p, float time, out float angle) {
    vec2 q = p - RIFT_CENTER;
    angle = atan(q.y, q.x);
    float t = (p.y - RIFT_BOTTOM) / RIFT_HEIGHT;
    float tc = clamp(t, 0.0, 1.0);
    float centerX = riftCenterLine(tc);
    float sideSeed = p.x < centerX ? 11.0 : 23.0;
    float halfWidth = RIFT_HALF_WIDTH * pow(sin(3.14159 * tc), 0.95);
    halfWidth *= 0.65 + 0.7 * kinkNoise(tc * 6.0 + sideSeed);
    halfWidth += 0.2 * pow(max(0.0, kinkNoise(tc * 19.0 + sideSeed * 0.7) - 0.55) * 2.2, 2.0) * sin(3.14159 * tc);   // spikes
    halfWidth += 0.05 * (kinkNoise(tc * 47.0 + sideSeed) - 0.5);                                                     // fine fraying
    halfWidth += 0.02 * sin(time * 1.7 + tc * 12.0);                                             // slight breathing
    halfWidth = max(halfWidth, 0.0);
    float edgeDist = (abs(p.x - centerX) - halfWidth) * 0.9;
    if (t < 0.0 || t > 1.0) {
        // beyond the tips: distance to the tip
        float tipY = t < 0.0 ? RIFT_BOTTOM : RIFT_BOTTOM + RIFT_HEIGHT;
        edgeDist = max(edgeDist, length(vec2(p.x - centerX, p.y - tipY)));
    }
    return edgeDist;
}

vec3 riftColor(float angle, float time) {
    vec3 cyan = vec3(0.25, 0.85, 1.0), magenta = vec3(1.0, 0.3, 0.95), gold = vec3(1.0, 0.82, 0.35);
    vec3 c = mix(cyan, magenta, 0.5 + 0.5 * sin(angle * 3.0 + time * 0.8));
    return mix(c, gold, smoothstep(0.62, 0.95, noise1(angle * 4.0 - time * 0.6 + 20.0)));
}

// Dark jagged hem just outside the edge, like torn material.
float riftHem(float edgeDist, float angle) {
    float hemWidth = 0.04 + 0.13 * noise1((angle + 3.14159) * 23.0) * noise1((angle + 3.14159) * 7.0 + 4.0);
    return edgeDist > 0.0 && edgeDist < hemWidth ? 0.25 : 1.0;
}

// Glow around the edge: bright line, tight haze, lightning arcs and sparkles.
vec3 riftGlow(vec2 p, float edgeDist, float angle, float time) {
    vec3 color = riftColor(angle, time);
    float flicker = 0.7 + 0.3 * noise1(time * 14.0 + angle * 5.0);
    float core = exp(-abs(edgeDist) * 26.0) * 1.8;
    float haze = exp(-max(edgeDist, 0.0) * 7.0) * 0.22 + exp(-max(-edgeDist, 0.0) * 12.0) * 0.18;
    vec3 glow = color * (core + haze) * flicker;
    // lightning arcs: eight roots on the edge, each flickering at its own rate and zig-zagging outwards
    float radius = length(p - RIFT_CENTER);
    for (int k = 0; k < 8; k++) {
        float fk = float(k);
        float beat = floor(time * (3.0 + hash1(fk) * 3.0) + fk * 0.37);
        if (hash1(fk * 7.1 + beat * 1.3) < 0.45) continue;
        float arcLength = 0.35 + 0.75 * hash1(fk * 3.7 + beat);
        if (edgeDist <= 0.0 || edgeDist > arcLength) continue;
        float start = fk * 0.785 + (hash1(fk * 5.3 + beat * 2.1) - 0.5) * 0.7 - 3.14159;
        float jag = (noise1(edgeDist * 9.0 + fk * 13.0 + beat * 4.0) - 0.5) * 0.9
                     + (noise1(edgeDist * 31.0 + fk * 5.0 + beat) - 0.5) * 0.25;
        float arcAngle = start + jag * (0.2 + edgeDist / arcLength) / max(radius, 0.5);
        float deviation = abs(mod(angle - arcAngle + 3.14159, 6.28318) - 3.14159) * radius;
        float thickness = 0.028 * (1.0 - edgeDist / arcLength) + 0.004;
        float line = smoothstep(thickness, 0.0, deviation);
        glow += mix(vec3(1.0), riftColor(start, time), 0.35) * line * 1.6 + riftColor(start, time) * smoothstep(thickness * 6.0, 0.0, deviation) * 0.25;
    }
    glow *= smoothstep(1.2, 0.85, edgeDist);
    if (edgeDist > -0.3 && edgeDist < 0.9) {
        vec2 cell = floor(p * 18.0);
        float sparkle = step(0.975, hash2(cell)) * (0.5 + 0.5 * sin(time * 6.0 + hash2(cell + 3.0) * 30.0));
        vec2 inner = fract(p * 18.0) - 0.5;
        glow += vec3(1.0, 0.95, 1.0) * sparkle * smoothstep(0.25, 0.0, length(inner)) * exp(-abs(edgeDist) * 2.5);
    }
    return glow;
}

// Back side of the rift: deep starry nebula instead of the destination world.
vec3 riftNebula(vec2 p, float time) {
    float n = noise2(p * 1.6 + vec2(time * 0.05, 0.0)) * 0.6 + noise2(p * 4.0 - time * 0.08) * 0.4;
    vec3 c = mix(vec3(0.03, 0.02, 0.12), vec3(0.35, 0.12, 0.55), n * n);
    c = mix(c, vec3(0.1, 0.45, 0.7), smoothstep(0.55, 0.9, noise2(p * 2.3 + 5.0)) * 0.5);
    vec2 cell = floor(p * 30.0);
    c += vec3(1.0) * step(0.985, hash2(cell)) * (0.6 + 0.4 * sin(time * 3.0 + hash2(cell) * 20.0));
    return c;
}

// ---------- further styles: 2 inferno, 3 storm, 4 void, 5 frost ----------

float ellipseDistance(vec2 p, vec2 c, vec2 r) { return (length((p - c) / r) - 1.0) * min(r.x, r.y); }
float boxDistance(vec2 p, vec2 lo, vec2 hi) {
    vec2 c = (lo + hi) * 0.5, d = abs(p - c) - (hi - lo) * 0.5;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}
vec2 openingCenter() { return vec2(OPENING_W, OPENING_H) * 0.5; }

vec3 fireRamp(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c = mix(vec3(0.0), vec3(0.6, 0.05, 0.0), smoothstep(0.0, 0.3, t));
    c = mix(c, vec3(1.0, 0.35, 0.02), smoothstep(0.25, 0.6, t));
    c = mix(c, vec3(1.0, 0.8, 0.25), smoothstep(0.55, 0.85, t));
    return mix(c, vec3(1.0, 0.97, 0.8), smoothstep(0.85, 1.0, t));
}

// Frost: an oval whose rim is overgrown with ice crystals reaching into the opening.
float frostSpikes(float angle) {
    float s = max(0.0, kinkNoise(angle * 9.0 + 3.0) - 0.35) * 1.6;
    return (0.05 + 0.3 * s * s) * min(OPENING_W, OPENING_H) / 3.0;
}

// Storm: one big lightning strike every ~0.8 s (not every beat); returns the flash strength.
float STORM_FLASH = 0.0;
float stormBeat(float time) { return floor(time * 1.25); }
float stormFlash(float time) {
    float beat = stormBeat(time), phase = fract(time * 1.25);
    if (hash1(beat * 3.7) < 0.35) return 0.0;
    return exp(-phase * 9.0) * (0.6 + 0.4 * step(0.5, fract(phase * 14.0)));
}

// Signed distance to the opening edge in blocks (negative = inside) for styles 2..5.
float styleDistance(int style, vec2 p, float time, out float angle) {
    vec2 c = openingCenter(), q = p - c;
    angle = atan(q.y, q.x);
    if (style == 2) {
        // pointed arch, the edge licking like a flame
        float r = min(OPENING_W * 0.5, OPENING_H * 0.45);
        float d = min(boxDistance(p, vec2(0.0), vec2(OPENING_W, OPENING_H - r)),
                      ellipseDistance(p, vec2(c.x, OPENING_H - r), vec2(OPENING_W * 0.5, r)));
        return d + 0.07 * (noise2(vec2(angle * 4.0, time * 3.0)) - 0.5);
    }
    if (style == 3) {
        // oval, its edge crackling with electricity
        float d = ellipseDistance(p, c, vec2(OPENING_W * 0.5, OPENING_H * 0.5 + 0.1));
        return d + 0.05 * (kinkNoise(angle * 12.0 + floor(time * 12.0) * 3.1) - 0.5);
    }
    if (style == 4) {
        // round vortex, slowly wobbling
        return ellipseDistance(p, c, vec2(OPENING_W * 0.5, OPENING_H * 0.5)) + 0.035 * sin(angle * 5.0 + time * 2.0);
    }
    // frost
    return ellipseDistance(p, c, vec2(OPENING_W * 0.5, OPENING_H * 0.5 + 0.05)) + frostSpikes(angle);
}

// How far outside the edge a style still draws something.
float styleReach(int style) { return style == 3 ? 6.0 : style == 2 ? 2.2 : 1.3; }

// Lightning arcs leaving the edge (like the rift's, with parameters).
vec3 edgeArcs(vec2 p, float e, float angle, float time, int count, float rate, float minLength, float maxLength, vec3 tint, float seed) {
    vec3 glow = vec3(0.0);
    float radius = length(p - openingCenter());
    for (int k = 0; k < 12; k++) {
        if (k >= count) break;
        float fk = float(k) + seed;
        float beat = floor(time * (rate + hash1(fk) * rate) + fk * 0.37);
        if (hash1(fk * 7.1 + beat * 1.3) < 0.4) continue;
        float arcLength = minLength + (maxLength - minLength) * hash1(fk * 3.7 + beat);
        if (e <= 0.0 || e > arcLength) continue;
        float start = fk * 6.28318 / float(count) + (hash1(fk * 5.3 + beat * 2.1) - 0.5) * 0.7 - 3.14159;
        float jag = (noise1(e * 7.0 + fk * 13.0 + beat * 4.0) - 0.5) * 1.1 + (noise1(e * 29.0 + fk * 5.0 + beat) - 0.5) * 0.3;
        float arcAngle = start + jag * (0.2 + e / arcLength) / max(radius, 0.5);
        float deviation = abs(mod(angle - arcAngle + 3.14159, 6.28318) - 3.14159) * radius;
        float thickness = 0.035 * (1.0 - e / arcLength) + 0.005;
        glow += mix(vec3(1.0), tint, 0.3) * smoothstep(thickness, 0.0, deviation) * 1.7 + tint * smoothstep(thickness * 7.0, 0.0, deviation) * 0.3;
    }
    return glow;
}

// The big strike of the storm style: a jagged bolt from the sky through the portal to the ground.
vec3 stormBolt(vec2 p, float time) {
    float beat = stormBeat(time), phase = fract(time * 1.25);
    if (hash1(beat * 3.7) < 0.35 || phase > 0.3 || p.y < -0.3 || p.y > OPENING_H + 6.0) return vec3(0.0);
    float x = OPENING_W * (0.2 + 0.6 * hash1(beat * 1.9)) + 0.7 * (kinkNoise(p.y * 2.2 + beat * 7.0) - 0.5) + 0.15 * (kinkNoise(p.y * 9.0 + beat) - 0.5);
    float dist = abs(p.x - x);
    float fade = 1.0 - phase / 0.3;
    vec3 c = vec3(1.0) * smoothstep(0.06, 0.0, dist) * 2.0 + vec3(0.55, 0.5, 1.0) * exp(-dist * 7.0) * 0.6;
    // a side branch
    float by = OPENING_H * (0.6 + 0.5 * hash1(beat * 4.3));
    if (p.y < by && p.y > by - 1.6) {
        float bx = x + (by - p.y) * (hash1(beat * 2.2) < 0.5 ? -0.6 : 0.6) + 0.2 * (kinkNoise(p.y * 7.0 + beat * 3.0) - 0.5);
        float bd = abs(p.x - bx);
        c += vec3(1.0) * smoothstep(0.035, 0.0, bd) * 1.4 + vec3(0.55, 0.5, 1.0) * exp(-bd * 9.0) * 0.35;
    }
    return c * fade;
}

// Glow, flames, arcs and sparkles around the edge.
vec3 styleGlow(int style, vec2 p, float e, float angle, float time) {
    if (style == 2) {
        // flames rising from the edge, taller towards the top, with embers
        float up = clamp((p.y - OPENING_H * 0.5) / OPENING_H + 0.6, 0.15, 1.3);
        float n = noise2(vec2(p.x * 2.2, p.y * 1.6 - time * 2.6)) * 0.6 + noise2(vec2(p.x * 5.0, p.y * 3.5 - time * 4.5)) * 0.4;
        float reach = (0.2 + 0.95 * up) * (0.45 + n);
        float heat = e > 0.0 ? clamp(1.0 - e / reach, 0.0, 1.0) : exp(e * 9.0);
        heat = pow(heat, 1.4);
        vec3 c = fireRamp(heat * (0.7 + 0.4 * n)) * heat * 1.7;
        c += vec3(1.0, 0.6, 0.15) * exp(-abs(e) * 30.0) * 1.1;
        vec2 q = p * vec2(9.0, 6.0) + vec2(0.0, -time * 3.0);
        float ember = step(0.94, hash2(floor(q))) * smoothstep(0.3, 0.0, length(fract(q) - 0.5));
        c += vec3(1.0, 0.5, 0.1) * ember * exp(-max(e, 0.0) * 1.2) * step(-0.15, e);
        return c;
    }
    if (style == 3) {
        vec3 violet = vec3(0.6, 0.45, 1.0);
        float flicker = 0.6 + 0.4 * noise1(time * 25.0 + angle * 6.0);
        vec3 c = mix(violet, vec3(1.0), 0.5) * exp(-abs(e) * 30.0) * 2.0 * flicker;
        c += violet * (exp(-max(e, 0.0) * 4.0) * 0.25 + exp(-max(-e, 0.0) * 10.0) * 0.2) * flicker;
        c += edgeArcs(p, e, angle, time, 10, 5.0, 0.4, 1.9, violet, 0.0);
        c += stormBolt(p, time);
        c += vec3(0.75, 0.75, 1.0) * STORM_FLASH * 0.35 * exp(-max(e, 0.0) * 0.5);
        return c;
    }
    if (style == 4) {
        vec2 q = (p - openingCenter()) / vec2(OPENING_W * 0.5, OPENING_H * 0.5);
        float r = length(q), a = atan(q.y, q.x);
        float swirl = 0.5 + 0.5 * sin(a * 3.0 + r * 9.0 - time * 2.5);
        float swirl2 = noise2(vec2(a * 2.0 + time * 0.7 - r * 3.0, r * 4.0));
        vec3 c = mix(vec3(0.55, 0.15, 0.95), vec3(0.95, 0.35, 0.85), swirl2) * exp(-max(e, 0.0) * 3.0) * step(0.0, e) * (0.3 + 0.7 * swirl) * 1.2;
        c += vec3(0.85, 0.6, 1.0) * exp(-abs(e) * 28.0) * 1.4;
        // specks spiralling inwards
        vec2 polar = vec2(a * 4.0 / 3.14159 + time * 0.4, log(max(r, 0.05)) * 5.0 + time * 1.6);
        float speck = step(0.93, hash2(floor(polar))) * smoothstep(0.35, 0.0, length(fract(polar) - 0.5));
        c += vec3(0.9, 0.7, 1.0) * speck * step(0.0, e) * exp(-e * 1.8);
        return c;
    }
    // frost: pale rim, cold haze and twinkling ice glitter
    vec3 c = vec3(0.6, 0.9, 1.0) * exp(-abs(e) * 22.0) * 1.1 + vec3(0.5, 0.8, 1.0) * exp(-max(e, 0.0) * 5.0) * 0.22;
    vec2 cell = floor(p * 14.0);
    float twinkle = step(0.96, hash2(cell)) * max(0.0, sin(time * 3.0 + hash2(cell + 7.0) * 40.0));
    c += vec3(1.0) * twinkle * smoothstep(0.3, 0.0, length(fract(p * 14.0) - 0.5)) * exp(-abs(e) * 2.0);
    return c;
}

// What the area right outside the edge looks like (base = the normal image there).
vec3 styleOutside(int style, vec2 p, float e, float angle, float time, vec3 base) {
    if (style == 4) return base * mix(1.0, 0.12, exp(-e * 2.2));   // dark halo around the vortex
    if (style == 2) return base * mix(1.0, 0.55, exp(-e * 3.0));   // scorched surroundings
    if (style == 5) {
        float plain = ellipseDistance(p, openingCenter(), vec2(OPENING_W * 0.5, OPENING_H * 0.5 + 0.05));
        if (plain < 0.0) {
            // ice crystal growing into the opening: faceted, translucent blue-white
            float facet = step(0.5, fract(angle * 9.0 / 6.28318 * 6.0 + 0.5));
            float depth = clamp(-plain / max(frostSpikes(angle), 0.01), 0.0, 1.0);
            vec3 ice = mix(vec3(0.55, 0.8, 0.95), vec3(0.9, 0.97, 1.0), 0.35 + 0.4 * facet + 0.25 * depth);
            return ice * (0.85 + 0.15 * sin(time * 1.5 + angle * 20.0));
        }
        return base * mix(1.0, 1.25, exp(-plain * 4.0));   // frosty brightening
    }
    return base;
}

// Inside, seen from the front: tint over the destination world near the edge.
vec3 styleInside(int style, vec3 scene, vec2 p, float e, float time) {
    if (style == 2) return mix(scene, scene * vec3(1.2, 0.7, 0.45), exp(e * 5.0));
    if (style == 3) return scene * mix(1.0, 0.6, exp(e * 8.0)) + vec3(0.75, 0.75, 1.0) * STORM_FLASH * 0.25;
    if (style == 4) {
        vec2 q = (p - openingCenter()) / vec2(OPENING_W * 0.5, OPENING_H * 0.5);
        float swirl = 0.5 + 0.5 * sin(atan(q.y, q.x) * 3.0 + length(q) * 9.0 - time * 2.5);
        return scene * mix(1.0, 0.15 + 0.35 * swirl, exp(e * 3.5)) + vec3(0.3, 0.05, 0.45) * exp(e * 4.0) * 0.5;
    }
    return mix(scene, vec3(0.85, 0.93, 1.0), exp(e * 6.0) * 0.55);   // frost mist at the rim
}

// Seen from behind: no destination, but a style-specific surface.
vec3 styleBack(int style, vec2 p, float time) {
    if (style == 2) {
        float n = noise2(p * 1.5 + vec2(0.0, time * 0.2));
        float crack = 1.0 - smoothstep(0.0, 0.07, abs(noise2(p * 2.5 + time * 0.1) - 0.5));
        return mix(vec3(0.08, 0.02, 0.01), vec3(0.35, 0.05, 0.0), n) + fireRamp(0.9) * crack * (0.6 + 0.4 * sin(time * 2.0 + n * 6.0));
    }
    if (style == 3) {
        float n = noise2(p * 1.2 + vec2(time * 0.3, 0.0)) * 0.6 + noise2(p * 3.0 - time * 0.4) * 0.4;
        return mix(vec3(0.04, 0.04, 0.08), vec3(0.3, 0.3, 0.42), n * n) + vec3(0.7, 0.7, 1.0) * STORM_FLASH * n;
    }
    if (style == 4) {
        vec2 q = (p - openingCenter()) / vec2(OPENING_W * 0.5, OPENING_H * 0.5);
        float r = length(q), a = atan(q.y, q.x);
        float arms = 0.5 + 0.5 * sin(a * 2.0 - r * 10.0 + time * 1.8);
        return vec3(0.25, 0.05, 0.4) * arms * smoothstep(0.1, 1.0, r) * 0.8;
    }
    vec2 cell = floor(p * 2.2);
    float n = noise2(p * 2.0);
    float crack = 1.0 - smoothstep(0.0, 0.05, abs(noise2(p * 3.5 + 11.0) - 0.5));
    return mix(vec3(0.55, 0.75, 0.9), vec3(0.85, 0.95, 1.0), n) - vec3(0.25, 0.15, 0.05) * crack + 0.05 * hash2(cell);
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    fragColor = base;
    if (readData(29) != DP_MAGIC) return;

    mat4 proj = mat4(vec4(readData(0), readData(1), readData(2), readData(3)), vec4(readData(4), readData(5), readData(6), readData(7)),
                     vec4(readData(8), readData(9), readData(10), readData(11)), vec4(readData(12), readData(13), readData(14), readData(15)));
    mat3 rotation = mat3(vec3(readData(16), readData(17), readData(18)), vec3(readData(20), readData(21), readData(22)), vec3(readData(24), readData(25), readData(26)));
    vec3 camera = vec3(readData(19), readData(23), readData(27));   // origin of view space, local
    int sceneData = int(readData(28) + 0.5);            // scene + 16*facing + 64*(style>>1)
    int scene = sceneData & 15;
    int facing = (sceneData >> 4) & 3;                  // 0 south, 1 west, 2 north, 3 east
    // marker code = 1 + (style&1) + 2*(no inside view) + 4*(width-1) + 32*(height-1)
    int code = int(readData(30) + 0.5) - 1;
    int style = (sceneData >> 6) * 2 + (code & 1);      // 0 gate, 1 rift, 2 inferno, 3 storm, 4 void, 5 frost
    bool insideView = ((code >> 1) & 1) == 0;
    OPENING_W = float(((code >> 2) & 7) + 1);
    OPENING_H = float(((code >> 5) & 7) + 1);
    gridX0 = DP_RX0 + (int(OPENING_W) - 1) / 2;
    RIFT_CENTER = vec2(OPENING_W * 0.5, OPENING_H * 0.683);
    RIFT_HEIGHT = OPENING_H * 1.533;
    RIFT_HALF_WIDTH = 0.8 * OPENING_W / 3.0;
    float time = readData(31) * 1200.0;                 // seconds within one game day
    STORM_FLASH = style == 3 ? stormFlash(time) : 0.0;
    if (scene < 0 || scene >= DP_SCENES) return;

    // The eye is the point the projection maps to w = 0. View bobbing lives in the projection
    // and shifts the eye; this way rays stay correct whatever depth range
    // the renderer uses.
    mat4 invProj = inverse(proj);
    mat3 invRotation = inverse(rotation);
    vec4 eyeH = invProj * vec4(0.0, 0.0, 1.0, 0.0);
    vec3 eyeView = eyeH.xyz / eyeH.w;
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 b = invProj * vec4(ndc, 0.5, 1.0);
    vec3 eye = invRotation * eyeView + camera;
    vec3 d = normalize(invRotation * (b.xyz / b.w) + camera - eye);
    // world axes → the portal's local frame (x along the opening, z towards the viewer side)
    vec2 axisX = facing == 0 ? vec2(1.0, 0.0) : facing == 1 ? vec2(0.0, 1.0) : facing == 2 ? vec2(-1.0, 0.0) : vec2(0.0, -1.0);
    vec2 axisZ = vec2(-axisX.y, axisX.x);
    vec3 eyeWorld = eye;
    eye =vec3(dot(eye.xz, axisX), eye.y, dot(eye.xz, axisZ));
    d = vec3(dot(d.xz, axisX), d.y, dot(d.xz, axisZ));

    // Detect the depth range. Minecraft's near plane is 0.05; proj[3][2]/proj[2][2] is about
    // 2*0.05 for OpenGL (-1..1), 0.05 for 0..1 and very large for reversed depth.
    float ratio = proj[3][2] / proj[2][2];
    bool openglDepth = ratio > 0.075 && ratio < 1.0;
    float rawDepth = texture(MainDepthSampler, texCoord).r;
    vec4 s = invProj * vec4(ndc, openglDepth ? rawDepth * 2.0 - 1.0 : rawDepth, 1.0);
    float sceneDist = length(invRotation * (s.xyz / s.w) + camera - eyeWorld);

    if (eye.z > 0.0 && style == 0) {
        // gate in a wall, seen from the front: only pixels whose ray hits the opening
        if (d.z >= -1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (!inOpening(hit.xy)) return;
        if (sceneDist < tPlane - 0.03) return;
        vec3 color = march(hit + d * 1e-3, d, scene);
        float edge = min(min(hit.x, OPENING_W - hit.x), min(hit.y, OPENING_H - hit.y));
        color += vec3(0.35, 0.85, 1.0) * exp(-edge * 22.0) * 0.55;
        fragColor = vec4(color, 1.0);
    } else if (eye.z > 0.0 && style == 1) {
        // rift seen from the front: destination world inside, glowing edge around it
        if (d.z >= -1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return;
        float angle;
        float edgeDist = riftDistance(hit.xy, time, angle);
        if (edgeDist > 1.2) return;
        vec3 color = edgeDist < 0.0 ? march(hit + d * 1e-3, d, scene) : base.rgb * riftHem(edgeDist, angle);
        if (edgeDist < 0.0) color *= mix(1.0, 0.55, exp(edgeDist * 9.0));          // darker rim inside
        fragColor = vec4(color + riftGlow(hit.xy, edgeDist, angle, time), 1.0);
    } else if (eye.z > 0.0 && style >= 2) {
        // inferno, storm, void, frost seen from the front
        if (d.z >= -1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return;
        float angle;
        float e = styleDistance(style, hit.xy, time, angle);
        if (e > styleReach(style)) return;
        vec3 color;
        if (e < 0.0) {
            vec3 start = hit;
            if (style == 2) {
                // heat shimmer near the edge
                float s = exp(e * 3.0) * 0.08;
                start.xy += s * vec2(noise2(hit.xy * 5.0 + vec2(0.0, -time * 4.0)) - 0.5, noise2(hit.xy * 5.0 + vec2(5.0, -time * 4.0)) - 0.5);
            } else if (style == 4) {
                // the vortex twists the view towards its rim
                float twist = 0.6 * exp(e * 2.0) * (0.8 + 0.2 * sin(time));
                vec2 c = openingCenter(), q = hit.xy - c;
                start.xy = c + mat2(cos(twist), sin(twist), -sin(twist), cos(twist)) * q;
            }
            color = styleInside(style, march(start + d * 1e-3, d, scene), hit.xy, e, time);
        } else color = styleOutside(style, hit.xy, e, angle, time, base.rgb);
        fragColor = vec4(color + styleGlow(style, hit.xy, e, angle, time), 1.0);
    } else if (insideView && eye.z > -INSIDE_DEPTH && eye.z <= 0.0 && inOpening(eye.xy)) {
        // The camera flew through the opening: the whole screen shows the destination world in all
        // directions, without the portal, as if you had really walked through. In game there are
        // barriers there, so normal players never get here.
        fragColor = vec4(march(eye, d, scene), 1.0);
    } else if (eye.z <= 0.0 && style == 1) {
        // rift seen from behind: glowing edge around a dark starry nebula
        if (d.z <= 1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return;
        float angle;
        float edgeDist = riftDistance(hit.xy, time, angle);
        if (edgeDist > 1.2) return;
        vec3 color = edgeDist < 0.0 ? riftNebula(hit.xy, time) : base.rgb * riftHem(edgeDist, angle);
        fragColor = vec4(color + riftGlow(hit.xy, edgeDist, angle, time), 1.0);
    } else if (eye.z <= 0.0 && style >= 2) {
        // inferno, storm, void, frost seen from behind: their own surface instead of the destination
        if (d.z <= 1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return;
        float angle;
        float e = styleDistance(style, hit.xy, time, angle);
        if (e > styleReach(style)) return;
        vec3 color = e < 0.0 ? styleBack(style, hit.xy, time) : styleOutside(style, hit.xy, e, angle, time, base.rgb);
        fragColor = vec4(color + styleGlow(style, hit.xy, e, angle, time), 1.0);
    }
}
