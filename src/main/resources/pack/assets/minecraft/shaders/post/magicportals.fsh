#version 330
#extension GL_ARB_separate_shader_objects : require

// MagicPortals: draws the destination world into the portal opening.
// Camera and projection come from the marker's data row (entity_outline buffer),
// the scene itself is a baked voxel texture in the resource pack.
// Coordinates ("lokal"): world axes, origin at the lower west corner of the opening on the portal plane.
// The opening spans x 0..3, y 0..3 at z = 0; the destination world lies at z < 0.

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

const float OEFFNUNG = 3.0;
const float ZWISCHENRAUM = 3.0;   // camera counts as "inside" up to this depth behind the opening

float lies(int i) {
    return dp_entpacken(texelFetch(DataSampler, ivec2(i, 0), 0));
}

int b8(float v) { return int(round(v * 255.0)); }

// ---------- scene data ----------

ivec4 voxel(ivec3 zelle, int szene) {
    int i = zelle.x - DP_GX0;
    int j = zelle.y - DP_GY0;
    int k = -zelle.z - 1 + DP_DF;   // rows 0..DF-1 lie in front of the plane (the area behind the destination portal)
    if (i < 0 || j < 0 || k < 0 || i >= DP_W || j >= DP_H || k >= DP_DT) return ivec4(-1);
    ivec2 at = ivec2(i + (j % 8) * DP_W, k + (j / 8) * DP_DT + szene * DP_DT * DP_HB);
    vec4 t = texelFetch(VoxelSampler, at, 0);
    return ivec4(b8(t.r) | (b8(t.g) << 8), b8(t.b), 0, 0);
}

vec4 palette(int eintrag, int spalte) {
    return texelFetch(PaletteSampler, ivec2(spalte, eintrag), 0);
}

int kachel(int eintrag, int flaeche) {
    vec4 t = palette(eintrag, 5 + flaeche);
    return b8(t.r) * 256 + b8(t.g);
}

vec4 atlas(int k, vec2 uv) {
    ivec2 at = ivec2((k % DP_ATLAS_SPALTEN) * 16, (k / DP_ATLAS_SPALTEN) * 16)
             + clamp(ivec2(floor(uv * 16.0)), ivec2(0), ivec2(15));
    return texelFetch(AtlasSampler, at, 0);
}

// faces: 0 up, 1 down, 2 north(-z), 3 south(+z), 4 east(+x), 5 west(-x)
vec2 flaechenUV(int f, vec3 p) {
    if (f == 0) return vec2(p.x, p.z);
    if (f == 1) return vec2(p.x, 1.0 - p.z);
    if (f == 2) return vec2(1.0 - p.x, 1.0 - p.y);
    if (f == 3) return vec2(p.x, 1.0 - p.y);
    if (f == 4) return vec2(1.0 - p.z, 1.0 - p.y);
    return vec2(p.z, 1.0 - p.y);
}

float flaechenSchatten(int f) {
    if (f == 0) return 1.0;
    if (f == 1) return 0.5;
    if (f <= 3) return 0.8;
    return 0.6;
}

float licht(int stufe) {
    return mix(0.18, 1.0, pow(0.82, float(15 - stufe)));
}

vec3 himmel(vec3 d) {
    return mix(DP_HORIZONT, DP_ZENIT, smoothstep(-0.05, 0.55, d.y));
}

// Fog towards the horizon. Backwards (+z) the scene only reaches DP_DF blocks,
// so the fog starts earlier there to hide the edge.
float nebel(float weg, vec3 d) {
    float ende = d.z > 0.0 ? mix(float(DP_D) * 0.97, float(DP_DF) * 0.95, smoothstep(0.0, 0.5, d.z)) : float(DP_D) * 0.97;
    return smoothstep(ende * 0.5, ende, weg);
}

bool inOeffnung(vec2 p) {
    return p.x >= 0.0 && p.x <= OEFFNUNG && p.y >= 0.0 && p.y <= OEFFNUNG;
}

// Ray vs. box inside one cell. Returns t and face, t < 0 if missed.
vec2 quaderTreffer(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / d;
    vec3 t0 = (lo - o) * inv, t1 = (hi - o) * inv;
    vec3 tn = min(t0, t1), tf = max(t0, t1);
    float nah = max(max(tn.x, tn.y), tn.z), fern = min(min(tf.x, tf.y), tf.z);
    if (nah > fern || fern < 0.0) return vec2(-1.0);
    float f;
    if (nah == tn.x) f = d.x > 0.0 ? 5.0 : 4.0;
    else if (nah == tn.y) f = d.y > 0.0 ? 1.0 : 0.0;
    else f = d.z > 0.0 ? 2.0 : 3.0;
    return vec2(max(nah, 0.0), f);
}

/* Marches a ray from start (local, z < 0) through the voxel scene (DDA). The scene also extends in
 * front of the plane, so the in-between space shows the destination world all around. */
vec3 marsch(vec3 start, vec3 d, int szene) {
    vec3 o0 = start;
    ivec3 zelle = ivec3(floor(o0));
    zelle.z = min(zelle.z, -1);
    ivec3 schritt = ivec3(sign(d));
    vec3 tDelta = abs(1.0 / d);
    vec3 grenze = vec3(zelle) + max(vec3(schritt), vec3(0.0));
    vec3 tMax = (grenze - o0) / d;

    vec3 farbe = vec3(0.0);
    float rest = 1.0;
    int vorherLicht = 15;
    bool vorherFluessig = false;
    float tStrahl = 0.0;

    for (int n = 0; n < 340; n++) {
        ivec4 v = voxel(zelle, szene);
        if (v.x < 0) break;
        int eintrag = v.x;
        int lichtStufe = v.y & 15;
        bool fluessigHier = false;
        vec3 lokal = o0 + d * tStrahl - vec3(zelle);

        if (eintrag != 0) {
            vec4 p0 = palette(eintrag, 0);
            int form = b8(p0.r);
            bool fluessig = (b8(p0.g) & 1) != 0;
            fluessigHier = fluessig;
            vec3 tint = palette(eintrag, 3).rgb;
            int tintMaske = b8(palette(eintrag, 4).r);

            if (form == 1 && !(fluessig && vorherFluessig)) {
                vec3 lo = palette(eintrag, 1).rgb * 255.0 / 16.0;
                vec3 hi = palette(eintrag, 2).rgb * 255.0 / 16.0;
                if (fluessig && (v.y & 16) != 0) hi.y = 1.0;
                vec2 q = quaderTreffer(lokal, d, lo, hi);
                if (q.x >= 0.0) {
                    int f = int(q.y);
                    vec3 p = lokal + d * q.x;
                    vec4 tex = atlas(kachel(eintrag, f), flaechenUV(f, clamp(p, vec3(0.0), vec3(0.9999))));
                    if (tex.a > 0.5) {
                        vec3 c = tex.rgb;
                        if (((tintMaske >> f) & 1) != 0) c *= tint;
                        c *= flaechenSchatten(f) * licht(f == 0 || fluessig ? max(lichtStufe, vorherLicht) : vorherLicht);
                        float dunst = nebel(tStrahl + q.x, d);
                        c = mix(c, himmel(d), dunst);
                        if (fluessig) {
                            farbe += rest * 0.62 * c;
                            rest *= 0.38;
                        } else {
                            return farbe + rest * c;
                        }
                    }
                }
            } else if (form == 2) {
                // plants: two vertical diagonal planes
                float bestT = 1e9; vec3 bestC = vec3(0.0);
                for (int e = 0; e < 2; e++) {
                    vec3 nrm = e == 0 ? vec3(0.7071, 0.0, -0.7071) : vec3(0.7071, 0.0, 0.7071);
                    float abstandE = e == 0 ? 0.0 : 0.7071;
                    float dn = dot(d, nrm);
                    if (abs(dn) < 1e-5) continue;
                    float t = (abstandE - dot(lokal, nrm)) / dn;
                    if (t < 0.0 || t >= bestT) continue;
                    vec3 p = lokal + d * t;
                    if (any(lessThan(p, vec3(0.0))) || any(greaterThan(p, vec3(1.0)))) continue;
                    float u = e == 0 ? p.x : 1.0 - p.x;
                    vec4 tex = atlas(kachel(eintrag, 2), vec2(u, 1.0 - p.y));
                    if (tex.a < 0.5) continue;
                    vec3 c = tex.rgb;
                    if ((tintMaske & 1) != 0) c *= tint;
                    bestT = t; bestC = c * 0.85 * licht(lichtStufe);
                }
                if (bestT < 1e8) {
                    float dunst = nebel(tStrahl + bestT, d);
                    return farbe + rest * mix(bestC, himmel(d), dunst);
                }
            }
        }
        vorherFluessig = fluessigHier;
        vorherLicht = eintrag == 0 || fluessigHier ? lichtStufe : vorherLicht;

        if (tMax.x < tMax.y && tMax.x < tMax.z) { tStrahl = tMax.x; tMax.x += tDelta.x; zelle.x += schritt.x; }
        else if (tMax.y < tMax.z) { tStrahl = tMax.y; tMax.y += tDelta.y; zelle.y += schritt.y; }
        else { tStrahl = tMax.z; tMax.z += tDelta.z; zelle.z += schritt.z; }
    }
    return farbe + rest * himmel(d);
}

// ---------- style "rift": torn dimensional crack with an electric edge ----------

float zufall(float n) { return fract(sin(n * 127.1) * 43758.5453); }
float zufall2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float rauschen(float x) { float i = floor(x), f = fract(x); return mix(zufall(i), zufall(i + 1.0), f * f * (3.0 - 2.0 * f)); }
float rauschen2(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(zufall2(i), zufall2(i + vec2(1, 0)), f.x), mix(zufall2(i + vec2(0, 1)), zufall2(i + vec2(1, 1)), f.x), f.y);
}

const vec2 RISS_MITTE = vec2(1.5, 2.05);
const float RISS_UNTEN = -0.25;    // lower tip (just below the floor)
const float RISS_HOEHE = 4.6;      // up to the upper tip
const float RISS_BREITE = 0.8;     // half width at the widest point

// noise with hard kinks (linear instead of smooth interpolation)
float knick(float x) { float i = floor(x); return mix(zufall(i), zufall(i + 1.0), fract(x)); }

// centre line of the rift: zig-zag like a lightning crack
float rissMittellinie(float t) {
    return 1.5 + 0.5 * (knick(t * 4.0 + 2.0) - 0.5) + 0.12 * (knick(t * 11.0 + 5.0) - 0.5);
}

// Distance to the rift edge in blocks (negative = inside). Base shape is a tall, narrow slit,
// pointed at both ends, widest in the middle. Both edges are torn independently.
float rissAbstand(vec2 p, float zeit, out float winkel) {
    vec2 q = p - RISS_MITTE;
    winkel = atan(q.y, q.x);
    float t = (p.y - RISS_UNTEN) / RISS_HOEHE;
    float tc = clamp(t, 0.0, 1.0);
    float mitte = rissMittellinie(tc);
    float seite = p.x < mitte ? 11.0 : 23.0;
    float halb = RISS_BREITE * pow(sin(3.14159 * tc), 0.95);
    halb *= 0.65 + 0.7 * knick(tc * 6.0 + seite);
    halb += 0.2 * pow(max(0.0, knick(tc * 19.0 + seite * 0.7) - 0.55) * 2.2, 2.0) * sin(3.14159 * tc);   // spikes
    halb += 0.05 * (knick(tc * 47.0 + seite) - 0.5);                                                     // fine fraying
    halb += 0.02 * sin(zeit * 1.7 + tc * 12.0);                                             // slight breathing
    halb = max(halb, 0.0);
    float abstand = (abs(p.x - mitte) - halb) * 0.9;
    if (t < 0.0 || t > 1.0) {
        // beyond the tips: distance to the tip
        float spitzeY = t < 0.0 ? RISS_UNTEN : RISS_UNTEN + RISS_HOEHE;
        abstand = max(abstand, length(vec2(p.x - mitte, p.y - spitzeY)));
    }
    return abstand;
}

vec3 rissFarbe(float winkel, float zeit) {
    vec3 cyan = vec3(0.25, 0.85, 1.0), magenta = vec3(1.0, 0.3, 0.95), gold = vec3(1.0, 0.82, 0.35);
    vec3 c = mix(cyan, magenta, 0.5 + 0.5 * sin(winkel * 3.0 + zeit * 0.8));
    return mix(c, gold, smoothstep(0.62, 0.95, rauschen(winkel * 4.0 - zeit * 0.6 + 20.0)));
}

// Dark jagged hem just outside the edge, like torn material.
float rissSaum(float abstand, float winkel) {
    float breite = 0.04 + 0.13 * rauschen((winkel + 3.14159) * 23.0) * rauschen((winkel + 3.14159) * 7.0 + 4.0);
    return abstand > 0.0 && abstand < breite ? 0.25 : 1.0;
}

// Glow around the edge: bright line, tight haze, lightning arcs and sparkles.
vec3 rissLeuchten(vec2 p, float abstand, float winkel, float zeit) {
    vec3 farbe = rissFarbe(winkel, zeit);
    float flackern = 0.7 + 0.3 * rauschen(zeit * 14.0 + winkel * 5.0);
    float kern = exp(-abs(abstand) * 26.0) * 1.8;
    float dunst = exp(-max(abstand, 0.0) * 7.0) * 0.22 + exp(-max(-abstand, 0.0) * 12.0) * 0.18;
    vec3 licht = farbe * (kern + dunst) * flackern;
    // lightning arcs: eight roots on the edge, each flickering at its own rate and zig-zagging outwards
    float radius = length(p - RISS_MITTE);
    for (int k = 0; k < 8; k++) {
        float fk = float(k);
        float takt = floor(zeit * (3.0 + zufall(fk) * 3.0) + fk * 0.37);
        if (zufall(fk * 7.1 + takt * 1.3) < 0.45) continue;
        float laenge = 0.35 + 0.75 * zufall(fk * 3.7 + takt);
        if (abstand <= 0.0 || abstand > laenge) continue;
        float start = fk * 0.785 + (zufall(fk * 5.3 + takt * 2.1) - 0.5) * 0.7 - 3.14159;
        float zacken = (rauschen(abstand * 9.0 + fk * 13.0 + takt * 4.0) - 0.5) * 0.9
                     + (rauschen(abstand * 31.0 + fk * 5.0 + takt) - 0.5) * 0.25;
        float bogenWinkel = start + zacken * (0.2 + abstand / laenge) / max(radius, 0.5);
        float abweichung = abs(mod(winkel - bogenWinkel + 3.14159, 6.28318) - 3.14159) * radius;
        float dicke = 0.028 * (1.0 - abstand / laenge) + 0.004;
        float linie = smoothstep(dicke, 0.0, abweichung);
        licht += mix(vec3(1.0), rissFarbe(start, zeit), 0.35) * linie * 1.6 + rissFarbe(start, zeit) * smoothstep(dicke * 6.0, 0.0, abweichung) * 0.25;
    }
    licht *= smoothstep(1.2, 0.85, abstand);
    if (abstand > -0.3 && abstand < 0.9) {
        vec2 zelle = floor(p * 18.0);
        float stern = step(0.975, zufall2(zelle)) * (0.5 + 0.5 * sin(zeit * 6.0 + zufall2(zelle + 3.0) * 30.0));
        vec2 innen = fract(p * 18.0) - 0.5;
        licht += vec3(1.0, 0.95, 1.0) * stern * smoothstep(0.25, 0.0, length(innen)) * exp(-abs(abstand) * 2.5);
    }
    return licht;
}

// Back side of the rift: deep starry nebula instead of the destination world.
vec3 rissNebel(vec2 p, float zeit) {
    float n = rauschen2(p * 1.6 + vec2(zeit * 0.05, 0.0)) * 0.6 + rauschen2(p * 4.0 - zeit * 0.08) * 0.4;
    vec3 c = mix(vec3(0.03, 0.02, 0.12), vec3(0.35, 0.12, 0.55), n * n);
    c = mix(c, vec3(0.1, 0.45, 0.7), smoothstep(0.55, 0.9, rauschen2(p * 2.3 + 5.0)) * 0.5);
    vec2 zelle = floor(p * 30.0);
    c += vec3(1.0) * step(0.985, zufall2(zelle)) * (0.6 + 0.4 * sin(zeit * 3.0 + zufall2(zelle) * 20.0));
    return c;
}

void main() {
    vec4 grund = texture(InSampler, texCoord);
    fragColor = grund;
    if (lies(29) != DP_MAGIC) return;

    mat4 proj = mat4(vec4(lies(0), lies(1), lies(2), lies(3)), vec4(lies(4), lies(5), lies(6), lies(7)),
                     vec4(lies(8), lies(9), lies(10), lies(11)), vec4(lies(12), lies(13), lies(14), lies(15)));
    mat3 drehung = mat3(vec3(lies(16), lies(17), lies(18)), vec3(lies(20), lies(21), lies(22)), vec3(lies(24), lies(25), lies(26)));
    vec3 kamera = vec3(lies(19), lies(23), lies(27));   // origin of view space, local
    int szene = int(lies(28) + 0.5);
    int stil = int(lies(30) + 0.5);                 // 0 = gate in a wall, 1 = rift
    float zeit = lies(31) * 1200.0;                 // seconds within one game day
    if (szene < 0 || szene >= DP_SZENEN) return;

    // The eye is the point the projection maps to w = 0. View bobbing lives in the projection
    // and shifts the eye; this way rays stay correct whatever depth range
    // the renderer uses.
    mat4 invProj = inverse(proj);
    mat3 invDrehung = inverse(drehung);
    vec4 augeH = invProj * vec4(0.0, 0.0, 1.0, 0.0);
    vec3 augeSicht = augeH.xyz / augeH.w;
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 b = invProj * vec4(ndc, 0.5, 1.0);
    vec3 nah = invDrehung * augeSicht + kamera;
    vec3 d = normalize(invDrehung * (b.xyz / b.w) + kamera - nah);

    // Detect the depth range. Minecraft's near plane is 0.05; proj[3][2]/proj[2][2] is about
    // 2*0.05 for OpenGL (-1..1), 0.05 for 0..1 and very large for reversed depth.
    float verhaeltnis = proj[3][2] / proj[2][2];
    bool openglTiefe = verhaeltnis > 0.075 && verhaeltnis < 1.0;
    float roh = texture(MainDepthSampler, texCoord).r;
    vec4 s = invProj * vec4(ndc, openglTiefe ? roh * 2.0 - 1.0 : roh, 1.0);
    float bisSzene = length(invDrehung * (s.xyz / s.w) + kamera - nah);

    if (nah.z > 0.0 && stil == 0) {
        // gate in a wall, seen from the front: only pixels whose ray hits the opening
        if (d.z >= -1e-4) return;
        float tEbene = -nah.z / d.z;
        vec3 treffer = nah + d * tEbene;
        if (!inOeffnung(treffer.xy)) return;
        if (bisSzene < tEbene - 0.03) return;
        vec3 farbe = marsch(treffer + d * 1e-3, d, szene);
        float rand = min(min(treffer.x, OEFFNUNG - treffer.x), min(treffer.y, OEFFNUNG - treffer.y));
        farbe += vec3(0.35, 0.85, 1.0) * exp(-rand * 22.0) * 0.55;
        fragColor = vec4(farbe, 1.0);
    } else if (nah.z > 0.0 && stil == 1) {
        // rift seen from the front: destination world inside, glowing edge around it
        if (d.z >= -1e-4) return;
        float tEbene = -nah.z / d.z;
        vec3 treffer = nah + d * tEbene;
        if (bisSzene < tEbene - 0.03) return;
        float winkel;
        float abstand = rissAbstand(treffer.xy, zeit, winkel);
        if (abstand > 1.2) return;
        vec3 farbe = abstand < 0.0 ? marsch(treffer + d * 1e-3, d, szene) : grund.rgb * rissSaum(abstand, winkel);
        if (abstand < 0.0) farbe *= mix(1.0, 0.55, exp(abstand * 9.0));          // darker rim inside
        fragColor = vec4(farbe + rissLeuchten(treffer.xy, abstand, winkel, zeit), 1.0);
    } else if (nah.z > -ZWISCHENRAUM && nah.z <= 0.0 && inOeffnung(nah.xy)) {
        // The camera flew through the opening: the whole screen shows the destination world in all
        // directions, without the portal, as if you had really walked through. In game there are
        // barriers there, so normal players never get here.
        fragColor = vec4(marsch(nah, d, szene), 1.0);
    } else if (nah.z <= 0.0 && stil == 1) {
        // rift seen from behind: glowing edge around a dark starry nebula
        if (d.z <= 1e-4) return;
        float tEbene = -nah.z / d.z;
        vec3 treffer = nah + d * tEbene;
        if (bisSzene < tEbene - 0.03) return;
        float winkel;
        float abstand = rissAbstand(treffer.xy, zeit, winkel);
        if (abstand > 1.2) return;
        vec3 farbe = abstand < 0.0 ? rissNebel(treffer.xy, zeit) : grund.rgb * rissSaum(abstand, winkel);
        fragColor = vec4(farbe + rissLeuchten(treffer.xy, abstand, winkel, zeit), 1.0);
    }
}
