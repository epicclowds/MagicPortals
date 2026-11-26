#version 330
#extension GL_ARB_separate_shader_objects : require

// MagicPortals: vanilla outline shader plus the portal marker.
// The lines marked //@ULTIMATEUI_... are replaced by MagicPortals when Ultimate UI is installed,
// so its HUD keeps working (Ultimate UI overrides this shader too).

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

//@ULTIMATEUI_INCLUDE
#include <minecraft:magicportals_marker.glsl>

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 6) flat out int dpMarker;
layout(location = 7) flat out vec4 dpD0;
layout(location = 8) flat out vec4 dpD1;
layout(location = 9) flat out vec4 dpD2;
layout(location = 10) flat out vec4 dpD3;
layout(location = 11) flat out vec4 dpD4;
layout(location = 12) flat out vec4 dpD5;
layout(location = 13) flat out vec4 dpD6;
layout(location = 14) flat out vec4 dpD7;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
    dpMarker = 0;
    dpD0 = dpD1 = dpD2 = dpD3 = dpD4 = dpD5 = dpD6 = dpD7 = vec4(0.0);

    //@ULTIMATEUI_HUD

    // The portal marker is a tiny glowing block display at the corner of the portal opening.
    // Instead of drawing it, its vertices are moved to a 32x1 pixel strip at the screen edge and
    // write camera position, rotation, projection, scene, style and game time into the outline buffer.
    int scene = dp_marker_scene(Color);
    if (scene >= 0) {
        mat3 rotation = mat3(ModelViewMat);
        vec3 relative = Position + inverse(rotation) * ModelViewMat[3].xyz;   // portal corner relative to the camera
        vec3 camera = -relative;                                             // camera relative to the portal corner
        dpD0 = ProjMat[0];
        dpD1 = ProjMat[1];
        dpD2 = ProjMat[2];
        dpD3 = ProjMat[3];
        dpD4 = vec4(rotation[0], camera.x);
        dpD5 = vec4(rotation[1], camera.y);
        dpD6 = vec4(rotation[2], camera.z);
        dpD7 = vec4(float(scene), DP_MAGIC, float(dp_marker_style(Color)), GameTime);
        dpMarker = 1;

        int corner = gl_VertexIndex % 4;
        int quad = gl_VertexIndex / 4;
        vec2 offset = vec2((corner == 1 || corner == 2) ? 1.0 : 0.0, corner >= 2 ? 1.0 : 0.0);
        if ((quad & 1) == 1) {
            offset = offset.yx;   // other winding, so the quad is never culled
        }
        vec2 size = vec2(float(DP_PIXEL), 1.0) * 2.0 / ScreenSize;
        gl_Position = vec4(vec2(-1.0) + offset * size, 0.001, 1.0);
    }
}
