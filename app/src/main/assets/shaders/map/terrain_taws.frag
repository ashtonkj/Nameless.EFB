#version 300 es
// terrain_taws.frag — TAWS terrain colour overlay
// Blended on top of the map tile layer using source-alpha blending.

uniform sampler2D u_elevation_tex;   // float16 terrain elevation tile
uniform float     u_aircraft_elev_m; // current aircraft elevation in metres
uniform float     u_agl_caution_m;   // caution threshold AGL (default 300 m)
uniform float     u_agl_warning_m;   // warning threshold AGL (default 150 m)

in  vec2 v_texcoord;
out vec4 frag_color;

void main() {
    float terrain_m = texture(u_elevation_tex, v_texcoord).r;
    float agl       = u_aircraft_elev_m - terrain_m;

    vec4 color;
    if (agl < u_agl_warning_m) {
        // Red — immediate terrain threat
        color = vec4(1.0, 0.0, 0.0, 0.55);
    } else if (agl < u_agl_caution_m) {
        // Yellow — terrain caution
        color = vec4(1.0, 0.85, 0.0, 0.45);
    } else {
        // No threat — transparent
        color = vec4(0.0, 0.0, 0.0, 0.0);
    }

    frag_color = color;
}
