#version 330
#extension GL_ARB_separate_shader_objects : require

// MagicPortals: draws the destination world into the portal opening.
// Camera and projection come from the marker's data row (entity_outline buffer),
// the scene itself is a baked voxel texture in the resource pack.
// Coordinates ("local"): the portal's own frame, origin at the lower corner of the opening on the portal plane
// (the front face of the opening, flush with the frame).
// The opening spans x 0..3, y 0..3 at z = 0; the destination world readData at z < 0.

#include <minecraft:magicportals_marker.glsl>
#include <minecraft:magicportals_scene.glsl>

uniform sampler2D InSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D DataSampler;
uniform sampler2D VoxelSampler;
uniform sampler2D PaletteSampler;
uniform sampler2D AtlasSampler;
uniform sampler2D FarColorSampler;
uniform sampler2D FarHeightSampler;
uniform sampler2D FarCeilingSampler;
uniform sampler2D FarCeilingColorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Opening size and options of the portal being drawn, decoded from the marker in main().
float OPENING_W = 3.0, OPENING_H = 3.0;
int gridX0 = DP_RX0 + 1;
const float INSIDE_DEPTH = 3.0;   // camera counts as "inside" up to this depth behind the opening

int DATA_ROW = 0;          // data row (= scene) of the portal being drawn
float PORTAL_DIST = 0.0;   // distance from the eye to that portal's plane, the nearest portal wins

float readData(int i) {
    return dp_unpack(texelFetch(DataSampler, ivec2(i, DATA_ROW), 0));
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

// ---------- view settings of the portal (time, fog, tint, weather), from DP_VIEW ----------

int VIEW_TIME = 0;                    // 0 day, 1 sunset, 2 night
float VIEW_FOG = 1.0;                 // fog density, 0 = none
bool VIEW_FOG_CUSTOM = false;
bool CAVE = false;                    // world with a ceiling (nether): rock ceiling and haze instead of sky
bool END = false;                     // the End: dark purple sky with streaks, void below
float VIEW_BLUR = 1.0;                // depth-of-field strength, 0 = everything sharp
vec3 VIEW_FOG_COLOR = vec3(0.0);
vec3 VIEW_TINT = vec3(1.0);
float VIEW_TINT_STRENGTH = 0.0;
int VIEW_WEATHER = 0;                 // 0 none, 1 rain, 2 snow, 3 ash, 4 embers
float SKY_SCALE = 1.0;                // how much sky light there is
vec3 SKY_LIGHT_COLOR = vec3(1.0);
vec3 SKY_HORIZON = DP_HORIZON, SKY_ZENITH = DP_ZENITH;

void setView(int scene) {
    // defaults first: several portals are drawn one after another
    SKY_HORIZON = DP_HORIZON; SKY_ZENITH = DP_ZENITH; SKY_SCALE = 1.0; SKY_LIGHT_COLOR = vec3(1.0);
    vec4 a = DP_VIEW[scene * 4], b = DP_VIEW[scene * 4 + 1], c = DP_VIEW[scene * 4 + 2], e = DP_VIEW[scene * 4 + 3];
    VIEW_BLUR = e.x;
    VIEW_TIME = int(a.x + 0.5);
    VIEW_FOG = a.y;
    VIEW_WEATHER = int(a.z + 0.5);
    VIEW_TINT_STRENGTH = a.w;
    VIEW_TINT = b.rgb;
    VIEW_FOG_CUSTOM = b.a > 0.5;
    VIEW_FOG_COLOR = c.rgb;
    if (VIEW_TIME == 1) {
        SKY_HORIZON = vec3(1.0, 0.55, 0.3); SKY_ZENITH = vec3(0.32, 0.33, 0.62);
        SKY_SCALE = 0.8; SKY_LIGHT_COLOR = vec3(1.0, 0.78, 0.6);
    } else if (VIEW_TIME == 2) {
        SKY_HORIZON = vec3(0.05, 0.07, 0.15); SKY_ZENITH = vec3(0.01, 0.015, 0.045);
        SKY_SCALE = 0.3; SKY_LIGHT_COLOR = vec3(0.55, 0.65, 1.0);
    }
    CAVE = c.a > 0.5 && c.a < 1.5;
    END = c.a > 1.5;
    if (END) {
        VIEW_TIME = 0;
        SKY_HORIZON = vec3(0.1, 0.06, 0.14); SKY_ZENITH = vec3(0.035, 0.02, 0.06);
        SKY_SCALE = 0.8; SKY_LIGHT_COLOR = vec3(0.82, 0.78, 1.0);
    }
    if (CAVE) {
        // nether: no sky, a warm glowing haze; light comes from lava and fire, not from the sun
        VIEW_TIME = 0;
        SKY_HORIZON = vec3(0.32, 0.09, 0.05); SKY_ZENITH = vec3(0.14, 0.035, 0.025);
        SKY_SCALE = 0.62; SKY_LIGHT_COLOR = vec3(1.0, 0.74, 0.58);
        if (!VIEW_FOG_CUSTOM) { VIEW_FOG_CUSTOM = true; VIEW_FOG_COLOR = vec3(0.3, 0.085, 0.05); }
    }
}

float lightFactor(float level) {
    return mix(0.18, 1.0, pow(0.82, 15.0 - level));
}

// Light of a voxel: sky light (bits 0-3, scaled by the time of day) or block light (bits 5-7) of torches etc.
vec3 lightOf(int raw) {
    float skyLevel = float(raw & 15) * SKY_SCALE;
    int b = (raw >> 5) & 7;
    float blockLevel = b > 0 ? float(b) * 2.0 + 1.0 : 0.0;
    float level = max(skyLevel, blockLevel);
    vec3 color = mix(SKY_LIGHT_COLOR, vec3(1.0, 0.86, 0.66), clamp((blockLevel - skyLevel) / 4.0, 0.0, 1.0));
    return color * lightFactor(level);
}

// Smooth value noise for the End sky (the other noise helpers are defined further down).
float endHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float endNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(endHash(i), endHash(i + vec2(1.0, 0.0)), f.x), mix(endHash(i + vec2(0.0, 1.0)), endHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Colour of fog and of the distance. In caves (nether) it is a dark version of the fog colour, so open
// spaces fade into darkness like in the real nether instead of glowing.
vec3 fogColor(vec3 d) {
    if (CAVE) return VIEW_FOG_COLOR * 0.32;
    if (END && !VIEW_FOG_CUSTOM) return vec3(0.04, 0.022, 0.065);
    return VIEW_FOG_CUSTOM ? VIEW_FOG_COLOR * (VIEW_TIME == 2 ? 0.45 : 1.0) : mix(SKY_HORIZON, SKY_ZENITH, smoothstep(-0.05, 0.55, d.y));
}


vec3 sky(vec3 d) {
    vec3 c = mix(SKY_HORIZON, SKY_ZENITH, smoothstep(-0.05, 0.55, d.y));
    if (VIEW_TIME == 1) c += vec3(1.0, 0.45, 0.15) * pow(max(0.0, 1.0 - abs(d.y - 0.03) * 5.0), 3.0) * 0.4;   // glowing horizon
    if (VIEW_TIME == 2 && d.y > 0.0) {
        // stars
        vec3 n = normalize(d) * 180.0;
        vec3 cell = floor(n);
        float h = fract(sin(dot(cell, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
        c += vec3(0.9, 0.92, 1.0) * step(0.985, h) * smoothstep(0.5, 0.1, length(fract(n) - 0.5)) * smoothstep(0.0, 0.2, d.y);
    }
    if (CAVE) return fogColor(d);   // no sky in caves: dark distance
    if (END) {
        // End sky: dark purple with faint swirling streaks, fading to black void below the horizon
        vec3 n = normalize(d);
        vec2 q = n.xz / (abs(n.y) + 0.35) * 2.5;
        float streak = endNoise(q + vec2(0.0, 7.0)) * 0.6 + endNoise(q * 2.7 + 3.0) * 0.4;
        vec3 c2 = mix(SKY_HORIZON, SKY_ZENITH, smoothstep(-0.05, 0.6, n.y)) + vec3(0.09, 0.05, 0.13) * smoothstep(0.45, 0.9, streak);
        c2 *= smoothstep(-0.55, 0.05, n.y) * 0.85 + 0.15;
        return VIEW_FOG_CUSTOM ? mix(c2, VIEW_FOG_COLOR * 0.4, 0.4) : c2;
    }
    if (VIEW_FOG_CUSTOM) c = mix(c, VIEW_FOG_COLOR, clamp(0.35 + 0.15 * VIEW_FOG, 0.0, 1.0));
    return c;
}


// Fog. By default (fog <= 1) the view stays clear: the far terrain continues behind the blocks and only
// gets a light aerial haze. fog > 1 lets everything fade out earlier. Backwards (+z, only seen from inside)
// the scene ends after DP_DB blocks, so that edge always fades out.
float fog(float dist, vec3 d) {
    float back = 0.0;   // behind the portal the far terrain continues, no fade needed
    if (CAVE && VIEW_FOG <= 1.0) return max(back, smoothstep(40.0, 320.0, dist) * 0.6 * VIEW_FOG);   // light nether haze, none with fog 0
    if (VIEW_FOG <= 1.0) return back;
    float end = 300.0 / VIEW_FOG;
    return max(back, smoothstep(end * 0.1, end, dist));
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

float MARCH_T = 1e4;   // distance of the last hit along the ray (for weather)

// ---------- far terrain: a soft heightfield beyond the block scene (no fog, slightly blurred) ----------

float farTexel(int c, int r, int scene) {
    if (c < 0 || r < 0 || c >= DP_FAR_COLUMNS || r >= DP_FAR_ROWS) return -1e4;
    vec4 t = texelFetch(FarHeightSampler, ivec2(c, r + scene * DP_FAR_ROWS), 0);
    if ((byte8(t.b) & 1) == 0) return -1e4;
    return float(byte8(t.r) * 256 + byte8(t.g)) - 2048.0;
}

// Continuous cell coordinate of a local point; cell centres are whole numbers.
vec2 farCell(vec3 p) {
    float a = float((int(OPENING_W) - 1) / 2);
    return vec2((p.x - a - 0.5) / DP_FAR_STEP + float(DP_FAR_COLUMNS / 2), (-p.z - 0.5) / DP_FAR_STEP + float(DP_FAR_BACK));
}

// Ceiling of cave worlds (nether); +1e4 where there is none.
float farCeilingTexel(int c, int r, int scene) {
    if (c < 0 || r < 0 || c >= DP_FAR_COLUMNS || r >= DP_FAR_ROWS) return 1e4;
    vec4 t = texelFetch(FarCeilingSampler, ivec2(c, r + scene * DP_FAR_ROWS), 0);
    if ((byte8(t.b) & 1) == 0) return 1e4;
    return float(byte8(t.r) * 256 + byte8(t.g)) - 2048.0;
}

// Shading of a far column face: 0/1 = side facing x/z, 2 = top, 3 = underside (ceiling).
vec3 farShade(vec3 color, int face, bool ceiling) {
    bool lava = !ceiling && color.r > 0.7 && color.g > 0.2 && color.b < 0.2;   // glows by itself
    if (lava) return color * 1.25;
    float shade = face == 2 ? 1.0 : face == 3 ? 0.5 : face == 0 ? 0.8 : 0.65;
    return color * shade * SKY_LIGHT_COLOR * lightFactor(15.0 * (ceiling ? SKY_SCALE * 0.7 : SKY_SCALE));
}

// The far terrain as blocky 2x2 columns (floor and, in caves, ceiling), traversed cell by cell.
// Up close it looks like Minecraft blocks; the depth-of-field pass blurs it in the distance.
vec3 farView(vec3 p0, vec3 d, int scene, float tBefore) {
    vec2 u0 = farCell(p0) + 0.5;                       // cell space: cell (c, r) spans [c, c+1)
    vec2 du = vec2(d.x, -d.z) / DP_FAR_STEP;
    ivec2 cell = ivec2(floor(u0));
    ivec2 stepCell = ivec2(sign(du));
    vec2 tDelta = abs(1.0 / du);
    vec2 tMax = ((vec2(cell) + max(vec2(stepCell), vec2(0.0))) - u0) / du;
    float tEnter = 0.0;
    int axis = 0;
    for (int n = 0; n < 420; n++) {
        if (cell.x < 0 || cell.y < 0 || cell.x >= DP_FAR_COLUMNS || cell.y >= DP_FAR_ROWS || tBefore + tEnter > 700.0) break;
        float tExit = min(tMax.x, tMax.y);
        float y0 = p0.y + d.y * tEnter, y1 = p0.y + d.y * tExit;
        float h = farTexel(cell.x, cell.y, scene);
        float c = CAVE ? farCeilingTexel(cell.x, cell.y, scene) : 1e4;
        bool hitFloor = h > -1e3 && min(y0, y1) < h;
        bool hitRoof = c < 1e3 && max(y0, y1) > c;
        if (hitFloor || hitRoof) {
            float tFloor = !hitFloor ? 1e9 : y0 < h ? tEnter : (h - p0.y) / d.y;
            float tRoof = !hitRoof ? 1e9 : y0 > c ? tEnter : (c - p0.y) / d.y;
            bool roof = tRoof < tFloor;
            float tHit = roof ? tRoof : tFloor;
            int face = roof ? (y0 > c ? axis : 3) : (y0 < h ? axis : 2);
            ivec2 at = ivec2(cell.x, cell.y + scene * DP_FAR_ROWS);
            vec3 color = roof ? texelFetch(FarCeilingColorSampler, at, 0).rgb : texelFetch(FarColorSampler, at, 0).rgb;
            color = farShade(color, face, roof);
            float dist = tBefore + tHit;
            MARCH_T = dist;
            // aerial perspective: distant land takes on a little of the sky colour
            if (!CAVE) color = mix(color, sky(d), smoothstep(40.0, 340.0, dist) * 0.5 * min(VIEW_FOG, 1.0));
            return mix(color, fogColor(d), fog(dist, d));
        }
        if (tMax.x < tMax.y) { tEnter = tMax.x; tMax.x += tDelta.x; cell.x += stepCell.x; axis = 0; }
        else { tEnter = tMax.y; tMax.y += tDelta.y; cell.y += stepCell.y; axis = 1; }
    }
    return CAVE ? fogColor(d) : sky(d);
}


vec3 march(vec3 start, vec3 d, int scene) {
    MARCH_T = 1e4;
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
        int lightLevel = v.y;   // sky light, water flag and block light, see lightOf
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
                        c *= faceShade(f) * (f == 0 || fluid ? max(lightOf(lightLevel), lightOf(prevLight)) : lightOf(prevLight));
                        float haze = fog(tRay + q.x, d);
                        c = mix(c, fogColor(d), haze);
                        if (fluid) {
                            color += rest * 0.62 * c;
                            rest *= 0.38;
                        } else {
                            MARCH_T = tRay + q.x;
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
                    bestT = t; bestC = c * 0.85 * lightOf(lightLevel);
                }
                if (bestT < 1e8) {
                    float haze = fog(tRay + bestT, d);
                    MARCH_T = tRay + bestT;
                    return color + rest * mix(bestC, fogColor(d), haze);
                }
            }
        }
        prevFluid = fluidHere;
        prevLight = entry == 0 || fluidHere ? lightLevel : prevLight;

        if (tMax.x < tMax.y && tMax.x < tMax.z) { tRay = tMax.x; tMax.x += tDelta.x; cell.x += stepDir.x; }
        else if (tMax.y < tMax.z) { tRay = tMax.y; tMax.y += tDelta.y; cell.y += stepDir.y; }
        else { tRay = tMax.z; tMax.z += tDelta.z; cell.z += stepDir.z; }
    }
    return color + rest * farView(o0 + d * tRay, d, scene, tRay);
}

float weatherHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

// Snow, rain, ash or embers in the destination world: particles on a few layers behind the opening
// (true parallax), hidden behind blocks that are closer than the layer.
vec3 weather(vec3 c, vec3 start, vec3 d, float time) {
    if (VIEW_WEATHER == 0 || d.z > -0.05) return c;
    float night = VIEW_TIME == 2 ? 0.45 : 1.0;
    for (int layer = 0; layer < 6; layer++) {
        float fl = float(layer);
        float depth = 1.5 + fl * 2.3;
        float t = (-depth - start.z) / d.z;
        if (t < 0.0 || t > MARCH_T) continue;
        vec2 uv = (start + d * t).xy;
        float fade = 1.0 - fl * 0.11;
        if (VIEW_WEATHER == 1) {
            // rain: thin fast streaks
            vec2 q = vec2(uv.x * 3.0 + fl * 7.3, uv.y * 0.7 + time * 6.5 + fl * 3.1);
            vec2 cell = floor(q), f = fract(q);
            float h = weatherHash(cell);
            if (h < 0.45) continue;
            float x = 0.15 + 0.7 * fract(h * 13.7);
            float streak = smoothstep(0.045, 0.0, abs(f.x - x)) * smoothstep(0.0, 0.2, f.y) * smoothstep(0.7, 0.45, f.y);
            c = mix(c, vec3(0.72, 0.77, 0.88) * night, streak * 0.45 * fade);
            continue;
        }
        bool embers = VIEW_WEATHER == 4, ash = VIEW_WEATHER == 3;
        float speed = embers ? -0.7 : ash ? 0.45 : 1.1;
        float sway = (ash ? 0.5 : 0.3) * sin(time * (ash ? 0.5 : 0.8) + uv.y * 0.6 + fl * 1.7);
        vec2 q = vec2(uv.x + sway, uv.y + time * speed) * 2.2 + vec2(fl * 17.3, fl * 5.9);
        vec2 cell = floor(q), f = fract(q);
        float h = weatherHash(cell);
        if (h < (embers ? 0.72 : 0.38)) continue;
        vec2 centre = 0.2 + 0.6 * vec2(fract(h * 7.31), fract(h * 3.17));
        float radius = (embers ? 0.05 : 0.07) + 0.05 * fract(h * 11.1);
        float flake = smoothstep(radius, radius * 0.35, length(f - centre)) * fade;
        if (embers) c += vec3(1.0, 0.45, 0.08) * flake * (0.7 + 0.5 * sin(time * 6.0 + h * 40.0)) * 1.5;
        else if (ash) c = mix(c, vec3(0.42, 0.4, 0.4) * mix(1.0, 1.5, fract(h * 5.0)) * night, flake * 0.85);
        else c = mix(c, vec3(0.95, 0.97, 1.0) * night, flake * 0.9);
    }
    return c;
}

// Blur amount of the current pixel (0 = sharp), written to the alpha channel for magicportals_blur.
float BLUR = 0.0;

// The destination as seen through this portal: scene, weather, colour filter.
vec3 viewMarch(vec3 start, vec3 d, int scene, float time) {
    vec3 c = march(start, d, scene);
    // depth of field: things far away are drawn out of focus by the blur pass (magicportals_blur)
    BLUR = clamp(VIEW_BLUR * smoothstep(8.0, 70.0, MARCH_T), 0.0, 1.0);
    c = weather(c, start, d, time);
    if (VIEW_WEATHER == 1) c *= 0.85;   // overcast
    if (VIEW_TINT_STRENGTH > 0.0) {
        float luminance = dot(c, vec3(0.3, 0.59, 0.11));
        vec3 tinted = luminance * VIEW_TINT / max(dot(VIEW_TINT, vec3(0.3, 0.59, 0.11)), 0.1);
        c = mix(c, min(tinted, vec3(1.5)), VIEW_TINT_STRENGTH);
    }
    return c;
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
const float RIFT_LIFT = 0.6;        // the rift floats this far above the floor
const float RIFT_BOTTOM = -0.25 + RIFT_LIFT;   // lower tip
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

// ----- shapes -----

float triangleDistance(vec2 p, vec2 p0, vec2 p1, vec2 p2) {
    vec2 e0 = p1 - p0, e1 = p2 - p1, e2 = p0 - p2;
    vec2 v0 = p - p0, v1 = p - p1, v2 = p - p2;
    vec2 q0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
    vec2 q1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
    vec2 q2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
    float s = sign(e0.x * e2.y - e0.y * e2.x);
    vec2 d = min(min(vec2(dot(q0, q0), s * (v0.x * e0.y - v0.y * e0.x)),
                     vec2(dot(q1, q1), s * (v1.x * e1.y - v1.y * e1.x))),
                     vec2(dot(q2, q2), s * (v2.x * e2.y - v2.y * e2.x)));
    return -sqrt(d.x) * sign(d.y);
}

float pentagonDistance(vec2 p, vec2 a, vec2 b, vec2 c, vec2 d, vec2 e) {
    vec2 v[5] = vec2[5](a, b, c, d, e);
    float dist = dot(p - v[0], p - v[0]), s = 1.0;
    for (int i = 0, j = 4; i < 5; j = i, i++) {
        vec2 ed = v[j] - v[i], w = p - v[i];
        vec2 q = w - ed * clamp(dot(w, ed) / dot(ed, ed), 0.0, 1.0);
        dist = min(dist, dot(q, q));
        bvec3 c3 = bvec3(p.y >= v[i].y, p.y < v[j].y, ed.x * w.y > ed.y * w.x);
        if (all(c3) || all(not(c3))) s = -s;
    }
    return s * sqrt(dist);
}

// Inferno: a giant fire – a big flame with two smaller tongues at its sides, all swaying and flickering.
float FLAME_HEIGHT = 4.6;
float smoothMin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }
float flameTongue(vec2 p, float cx0, float height, float halfWidth, float phase, float time) {
    float t = (p.y + 0.1) / height, tc = clamp(t, 0.0, 1.0);
    float cx = cx0 + OPENING_W * 0.11 * pow(tc, 1.6) * sin(time * 2.1 + tc * 4.0 + phase)
                   + OPENING_W * 0.05 * pow(tc, 2.0) * sin(time * 5.3 + tc * 9.0 + phase * 2.0);
    float profile = tc < 0.3 ? 0.78 + 0.22 * sqrt(max(0.0, 1.0 - pow((0.3 - tc) / 0.3, 2.0))) : pow(1.0 - (tc - 0.3) / 0.7, 1.4);
    float flicker = 1.0 + 0.14 * (noise1(tc * 6.0 - time * 3.5 + phase) - 0.5) + 0.1 * (noise1(tc * 17.0 - time * 7.0 + phase) - 0.5);
    float e = (abs(p.x - cx) - halfWidth * profile * flicker) * 0.85;
    if (t < 0.0) e = max(e, -0.1 - p.y);
    if (t > 1.0) e = max(e, length(vec2(p.x - cx, p.y - (height - 0.1))));
    return e;
}
float flameDistance(vec2 p, float time) {
    float main = flameTongue(p, OPENING_W * 0.5, FLAME_HEIGHT, OPENING_W * 0.5, 0.0, time);
    float left = flameTongue(p, OPENING_W * 0.2, FLAME_HEIGHT * 0.68, OPENING_W * 0.3, 1.7, time);
    float right = flameTongue(p, OPENING_W * 0.8, FLAME_HEIGHT * 0.76, OPENING_W * 0.3, 3.9, time);
    return smoothMin(smoothMin(main, left, 0.25), right, 0.25);
}

// Frost: a big ice crystal rising from the ground (you look through it), with smaller shards at its foot.
float ICE_HEIGHT = 4.8;
float iceDistance(vec2 p) {
    float w = OPENING_W * 0.5 * 1.08, cx = OPENING_W * 0.5;
    return pentagonDistance(p, vec2(cx - w, -0.2), vec2(cx + w, -0.2), vec2(cx + w * 0.9, ICE_HEIGHT * 0.62),
                            vec2(cx, ICE_HEIGHT), vec2(cx - w * 0.9, ICE_HEIGHT * 0.62));
}
// Solid shards around the big crystal: x = distance, y = facet shade.
vec2 iceShards(vec2 p) {
    float w = OPENING_W * 0.5 * 1.08, cx = OPENING_W * 0.5, h = ICE_HEIGHT;
    vec2 best = vec2(1e3, 0.0);
    vec2 tips[4] = vec2[4](vec2(cx - w - 0.95, h * 0.5), vec2(cx + w + 0.75, h * 0.42), vec2(cx - w - 1.7, h * 0.22), vec2(cx + w + 1.45, h * 0.18));
    vec2 feet[4] = vec2[4](vec2(cx - w + 0.35, cx - w - 0.45), vec2(cx + w - 0.35, cx + w + 0.4), vec2(cx - w - 0.7, cx - w - 1.35), vec2(cx + w + 0.55, cx + w + 1.15));
    for (int i = 0; i < 4; i++) {
        vec2 a = vec2(feet[i].x, -0.2), b = vec2(feet[i].y, -0.2), tip = tips[i];
        float d = triangleDistance(p, a, b, tip);
        if (d < best.x) {
            vec2 mid = (a + b) * 0.5, axis = tip - mid;
            float side = axis.x * (p.y - mid.y) - axis.y * (p.x - mid.x);
            best = vec2(d, side > 0.0 ? 1.0 : 0.0);
        }
    }
    return best;
}

// Storm: the eye of a storm – a round opening inside a ring of swirling thunderclouds.
float stormRadius() { return min(OPENING_W, OPENING_H) * 0.5; }
float stormRing() { return 0.55 + 0.25 * stormRadius(); }

// Storm: one big lightning strike roughly every 0.8 s; returns the flash strength.
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
    if (style == 2) return flameDistance(p, time);
    if (style == 3) return length(q) - stormRadius() + 0.04 * (kinkNoise(angle * 12.0 + floor(time * 12.0) * 3.1) - 0.5);
    if (style == 4) return ellipseDistance(p, c, vec2(OPENING_W * 0.5, OPENING_H * 0.5)) + 0.035 * sin(angle * 5.0 + time * 2.0);
    return iceDistance(p);
}

// How far outside the edge a style still draws something.
float styleReach(int style) { return style == 3 ? stormRing() + 0.4 : style == 2 ? 1.6 : style == 5 ? 2.2 : 1.3; }

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

// Thundercloud density of the storm ring (0..1) and how much it is lit by lightning inside.
vec2 stormCloud(vec2 p, float e, float angle, float time) {
    float ring = stormRing();
    if (e < -0.05 || e > ring) return vec2(0.0);
    float swirl = angle + time * 0.35 + e * 1.2;
    vec2 polar = vec2(swirl * 2.0, e * 2.2 - time * 0.15);
    float n = noise2(polar * vec2(1.6, 1.0)) * 0.55 + noise2(polar * vec2(4.0, 2.6) + 7.0) * 0.3 + noise2(polar * vec2(9.0, 6.0) + 3.0) * 0.15;
    float density = smoothstep(0.25, 0.75, n) * smoothstep(ring, ring * 0.35, e) * smoothstep(-0.05, 0.12, e);
    density = max(density, smoothstep(ring * 0.55, 0.0, e) * smoothstep(-0.05, 0.08, e) * 0.85);
    float sector = floor((swirl / 6.28318 + 0.5) * 9.0);
    float lit = step(0.78, hash1(sector * 3.3 + floor(time * 7.0 + hash1(sector) * 3.0))) * (0.5 + 0.5 * n);
    return vec2(density, max(lit, STORM_FLASH * 0.8));
}

// A big strike of the storm: a jagged bolt through the cloud ring and the opening down to the ground.
vec3 stormBolt(vec2 p, float time) {
    float beat = stormBeat(time), phase = fract(time * 1.25);
    float top = OPENING_H * 0.5 + stormRadius() + stormRing() * 0.8;
    if (hash1(beat * 3.7) < 0.35 || phase > 0.3 || p.y < -0.2 || p.y > top) return vec3(0.0);
    float x = OPENING_W * (0.3 + 0.4 * hash1(beat * 1.9)) + 0.5 * (kinkNoise(p.y * 2.4 + beat * 7.0) - 0.5) + 0.12 * (kinkNoise(p.y * 9.0 + beat) - 0.5);
    float dist = abs(p.x - x), fade = (1.0 - phase / 0.3) * smoothstep(top, top - 0.4, p.y);
    vec3 c = vec3(1.0) * smoothstep(0.05, 0.0, dist) * 2.0 + vec3(0.55, 0.5, 1.0) * exp(-dist * 9.0) * 0.5;
    float by = OPENING_H * (0.45 + 0.4 * hash1(beat * 4.3));
    if (p.y < by && p.y > by - 1.2) {
        float bx = x + (by - p.y) * (hash1(beat * 2.2) < 0.5 ? -0.6 : 0.6) + 0.15 * (kinkNoise(p.y * 7.0 + beat * 3.0) - 0.5);
        float bd = abs(p.x - bx);
        c += vec3(1.0) * smoothstep(0.03, 0.0, bd) * 1.3 + vec3(0.55, 0.5, 1.0) * exp(-bd * 11.0) * 0.3;
    }
    return c * fade;
}

// Glow, flames, arcs and sparkles around the edge.
vec3 styleGlow(int style, vec2 p, float e, float angle, float time) {
    if (style == 2) {
        // fire licking off the flame's outline, white-hot rim, embers rising
        float up = clamp(p.y / FLAME_HEIGHT + 0.3, 0.2, 1.3);
        float n = noise2(vec2(p.x * 2.4, p.y * 1.7 - time * 2.8)) * 0.6 + noise2(vec2(p.x * 5.5, p.y * 3.8 - time * 4.8)) * 0.4;
        float reach = (0.12 + 0.7 * up) * (0.4 + n);
        float heat = e > 0.0 ? clamp(1.0 - e / reach, 0.0, 1.0) : exp(e * 6.0);
        heat = pow(heat, 1.3);
        vec3 c = fireRamp(heat * (0.75 + 0.35 * n)) * heat * 1.6;
        c += vec3(1.0, 0.85, 0.5) * exp(-abs(e) * 34.0) * 1.2;
        vec2 q = p * vec2(9.0, 6.0) + vec2(0.0, -time * 3.2);
        float ember = step(0.94, hash2(floor(q))) * smoothstep(0.3, 0.0, length(fract(q) - 0.5));
        c += vec3(1.0, 0.5, 0.1) * ember * exp(-max(e, 0.0) * 1.4) * step(-0.1, e);
        return c;
    }
    if (style == 3) {
        vec3 violet = vec3(0.6, 0.45, 1.0);
        float flicker = 0.6 + 0.4 * noise1(time * 25.0 + angle * 6.0);
        vec3 c = mix(violet, vec3(1.0), 0.5) * exp(-abs(e) * 30.0) * 1.8 * flicker;
        c += violet * exp(-max(-e, 0.0) * 10.0) * 0.2 * flicker * step(e, 0.0);
        c += edgeArcs(p, e, angle, time, 8, 5.0, 0.25, stormRing() * 0.9, violet, 0.0);
        c += stormBolt(p, time);
        return c;
    }
    if (style == 4) {
        vec2 q = (p - openingCenter()) / vec2(OPENING_W * 0.5, OPENING_H * 0.5);
        float r = length(q), a = atan(q.y, q.x);
        float swirl = 0.5 + 0.5 * sin(a * 3.0 + r * 9.0 - time * 2.5);
        float swirl2 = noise2(vec2(a * 2.0 + time * 0.7 - r * 3.0, r * 4.0));
        vec3 c = mix(vec3(0.55, 0.15, 0.95), vec3(0.95, 0.35, 0.85), swirl2) * exp(-max(e, 0.0) * 3.0) * step(0.0, e) * (0.3 + 0.7 * swirl) * 1.2;
        c += vec3(0.85, 0.6, 1.0) * exp(-abs(e) * 28.0) * 1.4;
        vec2 polar = vec2(a * 4.0 / 3.14159 + time * 0.4, log(max(r, 0.05)) * 5.0 + time * 1.6);
        float speck = step(0.93, hash2(floor(polar))) * smoothstep(0.35, 0.0, length(fract(polar) - 0.5));
        c += vec3(0.9, 0.7, 1.0) * speck * step(0.0, e) * exp(-e * 1.8);
        return c;
    }
    // frost: bright crystal edges, cold haze, glitter on the ice
    vec3 c = vec3(0.7, 0.93, 1.0) * exp(-abs(e) * 26.0) * 1.2 + vec3(0.5, 0.8, 1.0) * exp(-max(e, 0.0) * 5.0) * 0.18;
    float shard = iceShards(p).x;
    c += vec3(0.75, 0.95, 1.0) * exp(-abs(shard) * 30.0) * 0.8;
    // a sharp highlight running down the middle ridge of the crystal
    float ridge = abs(p.x - OPENING_W * 0.5) / max(0.02, 0.05 * (1.0 - p.y / ICE_HEIGHT) + 0.01);
    c += vec3(0.85, 0.97, 1.0) * smoothstep(1.0, 0.0, ridge) * step(e, 0.0) * smoothstep(ICE_HEIGHT * 0.3, ICE_HEIGHT, p.y) * 0.35;
    vec2 cell = floor(p * 14.0);
    float twinkle = step(0.95, hash2(cell)) * max(0.0, sin(time * 3.0 + hash2(cell + 7.0) * 40.0));
    c += vec3(1.0) * twinkle * smoothstep(0.3, 0.0, length(fract(p * 14.0) - 0.5)) * (exp(-abs(e) * 2.0) + step(shard, 0.0));
    return c;
}

// What the area right outside the edge looks like (base = the normal image there).
vec3 styleOutside(int style, vec2 p, float e, float angle, float time, vec3 base) {
    if (style == 4) return base * mix(1.0, 0.12, exp(-e * 2.2));   // dark halo around the vortex
    if (style == 2) return base * mix(1.0, 0.6, exp(-e * 3.5));    // scorched surroundings
    if (style == 3) {
        vec2 cloud = stormCloud(p, e, angle, time);
        vec3 grey = mix(vec3(0.07, 0.07, 0.11), vec3(0.3, 0.3, 0.4), noise2(p * 3.0 + time * 0.2));
        vec3 lit = mix(grey, vec3(0.8, 0.75, 1.0), cloud.y);
        return mix(base, lit, cloud.x);
    }
    vec2 shard = iceShards(p);
    if (shard.x < 0.0) {
        float depth = clamp(-shard.x * 4.0, 0.0, 1.0);
        vec3 ice = mix(vec3(0.5, 0.75, 0.92), vec3(0.88, 0.97, 1.0), 0.3 + 0.45 * shard.y + 0.25 * depth);
        return mix(base, ice, 0.85) * (0.9 + 0.1 * sin(time * 1.5 + p.y * 6.0));
    }
    return base * mix(1.0, 1.2, exp(-max(e, 0.0) * 4.0));   // frosty brightening
}

// Inside, seen from the front: tint over the destination world near the edge.
vec3 styleInside(int style, vec3 scene, vec2 p, float e, float time) {
    if (style == 2) return mix(scene, scene * vec3(1.25, 0.75, 0.45), exp(e * 4.0));
    if (style == 3) {
        float angle = atan(p.y - OPENING_H * 0.5, p.x - OPENING_W * 0.5);
        vec2 cloud = stormCloud(p, e, angle, time);
        vec3 lit = mix(vec3(0.12, 0.12, 0.17), vec3(0.8, 0.75, 1.0), cloud.y);
        return mix(scene * mix(1.0, 0.7, exp(e * 6.0)), lit, cloud.x * 0.8) + vec3(0.75, 0.75, 1.0) * STORM_FLASH * 0.15;
    }
    if (style == 4) {
        vec2 q = (p - openingCenter()) / vec2(OPENING_W * 0.5, OPENING_H * 0.5);
        float swirl = 0.5 + 0.5 * sin(atan(q.y, q.x) * 3.0 + length(q) * 9.0 - time * 2.5);
        return scene * mix(1.0, 0.15 + 0.35 * swirl, exp(e * 3.5)) + vec3(0.3, 0.05, 0.45) * exp(e * 4.0) * 0.5;
    }
    // looking through ice: blue tint, frosted towards the edges
    return mix(scene * vec3(0.85, 0.95, 1.08), vec3(0.85, 0.94, 1.0), exp(e * 3.5) * 0.6);
}

// Seen from behind: no destination, but a style-specific surface.
vec3 styleBack(int style, vec2 p, float time) {
    if (style == 2) {
        float t = p.y / FLAME_HEIGHT;
        float n = noise2(vec2(p.x * 2.0, p.y * 1.4 - time * 2.0));
        return fireRamp(clamp(1.05 - t * 0.7 + (n - 0.5) * 0.4, 0.0, 1.0)) * 1.1;
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
    float n = noise2(p * 2.0);
    float crack = 1.0 - smoothstep(0.0, 0.05, abs(noise2(p * 3.5 + 11.0) - 0.5));
    float facet = step(OPENING_W * 0.5, p.x);
    return mix(vec3(0.55, 0.78, 0.92), vec3(0.85, 0.95, 1.0), n * 0.7 + facet * 0.3) - vec3(0.25, 0.15, 0.05) * crack;
}

// Draws one portal (data row DATA_ROW) into outColor; false if this pixel shows nothing of it.
bool renderPortal(vec4 base, out vec4 outColor) {
    outColor = base;
    if (readData(29) != DP_MAGIC) return false;

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
    RIFT_CENTER = vec2(OPENING_W * 0.5, OPENING_H * 0.683 + RIFT_LIFT);
    RIFT_HEIGHT = OPENING_H * 1.533;
    RIFT_HALF_WIDTH = 0.8 * OPENING_W / 3.0;
    float time = readData(31) * 1200.0;                 // seconds within one game day
    STORM_FLASH = style == 3 ? stormFlash(time) : 0.0;
    FLAME_HEIGHT = OPENING_H * 1.55;
    ICE_HEIGHT = OPENING_H * 1.6;
    if (scene < 0 || scene >= DP_SCENES) return false;
    setView(scene);

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
        if (d.z >= -1e-4) return false;
        float tPlane = -eye.z / d.z;
        PORTAL_DIST = tPlane;
        vec3 hit = eye + d * tPlane;
        if (!inOpening(hit.xy)) return false;
        if (sceneDist < tPlane - 0.03) return false;   // something in front of the portal plane (the front face of the opening) hides it
        vec3 color = viewMarch(hit + d * 1e-3, d, scene, time);
        float edge = min(min(hit.x, OPENING_W - hit.x), min(hit.y, OPENING_H - hit.y));
        color += vec3(0.35, 0.85, 1.0) * exp(-edge * 22.0) * 0.55;
        outColor = vec4(color, BLUR); return true;
    } else if (eye.z > 0.0 && style == 1) {
        // rift seen from the front: destination world inside, glowing edge around it
        if (d.z >= -1e-4) return false;
        float tPlane = -eye.z / d.z;
        PORTAL_DIST = tPlane;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return false;   // something in front of the portal plane (the front face of the opening) hides it
        float angle;
        float edgeDist = riftDistance(hit.xy, time, angle);
        if (edgeDist > 1.2) return false;
        vec3 color = edgeDist < 0.0 ? viewMarch(hit + d * 1e-3, d, scene, time) : base.rgb * riftHem(edgeDist, angle);
        if (edgeDist < 0.0) color *= mix(1.0, 0.55, exp(edgeDist * 9.0));          // darker rim inside
        outColor = vec4(color + riftGlow(hit.xy, edgeDist, angle, time), BLUR); return true;
    } else if (eye.z > 0.0 && style >= 2) {
        // inferno, storm, void, frost seen from the front
        if (d.z >= -1e-4) return false;
        float tPlane = -eye.z / d.z;
        PORTAL_DIST = tPlane;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return false;   // something in front of the portal plane (the front face of the opening) hides it
        float angle;
        float e = styleDistance(style, hit.xy, time, angle);
        if (e > styleReach(style)) return false;
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
            } else if (style == 5) {
                // the ice crystal refracts the view a little towards its edges
                start.xy += vec2(hit.x - OPENING_W * 0.5, 0.0) * 0.12 * exp(e * 2.0);
            }
            color = styleInside(style, viewMarch(start + d * 1e-3, d, scene, time), hit.xy, e, time);
        } else color = styleOutside(style, hit.xy, e, angle, time, base.rgb);
        outColor = vec4(color + styleGlow(style, hit.xy, e, angle, time), BLUR); return true;
    } else if (insideView && eye.z > -INSIDE_DEPTH && eye.z <= 0.0 && inOpening(eye.xy)) {
        // The camera flew through the opening: the whole screen shows the destination world in all
        // directions, without the portal, as if you had really walked through. In game there are
        // barriers there, so normal players never get here.
        outColor = vec4(viewMarch(eye, d, scene, time), BLUR); return true;
    } else if (eye.z <= 0.0 && style == 1) {
        // rift seen from behind: glowing edge around a dark starry nebula
        if (d.z <= 1e-4) return false;
        float tPlane = -eye.z / d.z;
        PORTAL_DIST = tPlane;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return false;
        float angle;
        float edgeDist = riftDistance(hit.xy, time, angle);
        if (edgeDist > 1.2) return false;
        vec3 color = edgeDist < 0.0 ? riftNebula(hit.xy, time) : base.rgb * riftHem(edgeDist, angle);
        outColor = vec4(color + riftGlow(hit.xy, edgeDist, angle, time), 1.0); return true;
    } else if (eye.z <= 0.0 && style >= 2) {
        // inferno, storm, void, frost seen from behind: their own surface instead of the destination
        if (d.z <= 1e-4) return false;
        float tPlane = -eye.z / d.z;
        PORTAL_DIST = tPlane;
        vec3 hit = eye + d * tPlane;
        if (sceneDist < tPlane - 0.03) return false;
        float angle;
        float e = styleDistance(style, hit.xy, time, angle);
        if (e > styleReach(style)) return false;
        vec3 color = e < 0.0 ? styleBack(style, hit.xy, time) : styleOutside(style, hit.xy, e, angle, time, base.rgb);
        outColor = vec4(color + styleGlow(style, hit.xy, e, angle, time), 1.0); return true;
    }
    return false;
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    fragColor = base;
    // every portal on screen has its own data row; draw them all, the nearest one wins per pixel
    float nearest = 1e9;
    for (int row = 0; row < 16; row++) {
        DATA_ROW = row;
        if (readData(29) != DP_MAGIC) continue;
        BLUR = 0.0;
        PORTAL_DIST = 0.0;
        vec4 color;
        if (renderPortal(base, color) && PORTAL_DIST < nearest) {
            nearest = PORTAL_DIST;
            fragColor = color;
        }
    }
}
