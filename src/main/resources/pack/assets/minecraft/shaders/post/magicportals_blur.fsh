#version 330
#extension GL_ARB_separate_shader_objects : require

// MagicPortals depth of field: blurs the destination seen through a portal by distance.
// In  = image from the portal pass; on portal pixels the alpha holds the blur amount (0..1).
// Main = the image before the portal pass – pixels where both are equal are not part of a portal.

uniform sampler2D InSampler;
uniform sampler2D MainSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool portalPixel(vec2 uv, out vec4 c) {
    c = texture(InSampler, uv);
    vec3 m = texture(MainSampler, uv).rgb;
    return any(greaterThan(abs(c.rgb - m), vec3(0.0015)));
}

void main() {
    vec4 original = texture(MainSampler, texCoord);
    vec4 c;
    if (!portalPixel(texCoord, c) || c.a < 0.02) {
        fragColor = vec4(c.rgb, original.a);
        return;
    }
    vec2 size = vec2(textureSize(InSampler, 0));
    float radius = c.a * size.y * 0.011;    // up to ~12 px at 1080p
    vec3 sum = c.rgb;
    float total = 1.0;
    // golden-angle spiral; only portal pixels count, weighted by their own blur so sharp near
    // things do not smear into the background
    for (int i = 1; i < 28; i++) {
        float r = sqrt(float(i) / 27.0) * radius;
        float angle = float(i) * 2.39996;
        vec4 s;
        if (!portalPixel(texCoord + vec2(cos(angle), sin(angle)) * r / size, s)) continue;
        float w = smoothstep(0.0, 0.6, s.a);
        sum += s.rgb * w;
        total += w;
    }
    fragColor = vec4(sum / total, original.a);
}
