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

const float OPENING = 3.0;
const float INSIDE_DEPTH = 3.0;   // camera counts as "inside" up to this depth behind the opening

float readData(int i) {
    return dp_unpack(texelFetch(DataSampler, ivec2(i, 0), 0));
}

int byte8(float v) { return int(round(v * 255.0)); }

// ---------- scene data ----------

ivec4 voxel(ivec3 cell, int scene) {
    int i = cell.x - DP_GX0;
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
    return p.x >= 0.0 && p.x <= OPENING && p.y >= 0.0 && p.y <= OPENING;
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

const vec2 RIFT_CENTER = vec2(1.5, 2.05);
const float RIFT_BOTTOM = -0.25;    // lower tip (just below the floor)
const float RIFT_HEIGHT = 4.6;      // up to the upper tip
const float RIFT_HALF_WIDTH = 0.8;     // half width at the widest point

// noise with hard kinks (linear instead of smooth interpolation)
float kinkNoise(float x) { float i = floor(x); return mix(hash1(i), hash1(i + 1.0), fract(x)); }

// centre line of the rift: zig-zag like a lightning crack
float riftCenterLine(float t) {
    return 1.5 + 0.5 * (kinkNoise(t * 4.0 + 2.0) - 0.5) + 0.12 * (kinkNoise(t * 11.0 + 5.0) - 0.5);
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

void main() {
    vec4 base = texture(InSampler, texCoord);
    fragColor = base;
    if (readData(29) != DP_MAGIC) return;

    mat4 proj = mat4(vec4(readData(0), readData(1), readData(2), readData(3)), vec4(readData(4), readData(5), readData(6), readData(7)),
                     vec4(readData(8), readData(9), readData(10), readData(11)), vec4(readData(12), readData(13), readData(14), readData(15)));
    mat3 rotation = mat3(vec3(readData(16), readData(17), readData(18)), vec3(readData(20), readData(21), readData(22)), vec3(readData(24), readData(25), readData(26)));
    vec3 camera = vec3(readData(19), readData(23), readData(27));   // origin of view space, local
    int scene = int(readData(28) + 0.5);
    int style = int(readData(30) + 0.5);                 // 0 = gate in a wall, 1 = rift
    float time = readData(31) * 1200.0;                 // seconds within one game day
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

    // Detect the depth range. Minecraft's near plane is 0.05; proj[3][2]/proj[2][2] is about
    // 2*0.05 for OpenGL (-1..1), 0.05 for 0..1 and very large for reversed depth.
    float ratio = proj[3][2] / proj[2][2];
    bool openglDepth = ratio > 0.075 && ratio < 1.0;
    float rawDepth = texture(MainDepthSampler, texCoord).r;
    vec4 s = invProj * vec4(ndc, openglDepth ? rawDepth * 2.0 - 1.0 : rawDepth, 1.0);
    float sceneDist = length(invRotation * (s.xyz / s.w) + camera - eye);

    if (eye.z > 0.0 && style == 0) {
        // gate in a wall, seen from the front: only pixels whose ray hits the opening
        if (d.z >= -1e-4) return;
        float tPlane = -eye.z / d.z;
        vec3 hit = eye + d * tPlane;
        if (!inOpening(hit.xy)) return;
        if (sceneDist < tPlane - 0.03) return;
        vec3 color = march(hit + d * 1e-3, d, scene);
        float edge = min(min(hit.x, OPENING - hit.x), min(hit.y, OPENING - hit.y));
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
    } else if (eye.z > -INSIDE_DEPTH && eye.z <= 0.0 && inOpening(eye.xy)) {
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
    }
}
