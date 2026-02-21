#version 300 es
// attitude.frag — sky/ground gradient for G1000 PFD attitude indicator.
// Pitch and roll are applied in the fragment shader per G1000 CRG Rev. R.

uniform float u_pitch_deg;   // aircraft pitch in degrees
uniform float u_roll_rad;    // aircraft roll  in radians

in  vec2 v_pos;              // clip-space position from vertex shader
out vec4 frag_color;

// CRG colour palette (P/N 190-00498-00)
const vec3 SKY_TOP    = vec3(0.0,   0.122, 0.302);  // #001F4D
const vec3 SKY_BOT    = vec3(0.0,   0.384, 0.671);  // #0062AB
const vec3 GND_TOP    = vec3(0.361, 0.239, 0.039);  // #5C3D0A
const vec3 GND_BOT    = vec3(0.165, 0.094, 0.0);    // #2A1800
const vec3 HORIZON    = vec3(0.90,  0.80,  0.0);    // yellow horizon line (CRG)

void main() {
    // Rotate fragment position by bank angle.
    float cosR = cos(u_roll_rad);
    float sinR = sin(u_roll_rad);
    vec2 rotated = vec2( cosR * v_pos.x + sinR * v_pos.y,
                        -sinR * v_pos.x + cosR * v_pos.y);

    // Pitch offset: 24px per degree, normalised to clip space (800px half-height).
    float pixPerDeg = 24.0 / 800.0;
    float horizon   = u_pitch_deg * pixPerDeg;

    vec3 color;
    // Yellow horizon line: ~2 px at nominal 800px height (CRG).
    float horizonHalfWidth = 0.003;
    if (abs(rotated.y - horizon) < horizonHalfWidth) {
        color = HORIZON;
    } else if (rotated.y > horizon) {
        // Sky — gradient from bottom (brighter) to top (darker).
        float t = clamp((rotated.y - horizon) / 0.5, 0.0, 1.0);
        color = mix(SKY_BOT, SKY_TOP, t);
    } else {
        // Ground — gradient from top (brighter) to bottom (darker).
        float t = clamp((horizon - rotated.y) / 0.5, 0.0, 1.0);
        color = mix(GND_TOP, GND_BOT, t);
    }

    // Theme-dependent brightness (applies to sky, ground, and horizon line). and tint.
    // u_theme: 0.0 = day, 1.0 = night, 2.0 = red-cockpit (from common_uniforms.glsl)
    if (u_theme >= 1.5) {
        // Red cockpit — desaturate to luminance then tint red at 70%.
        float lum = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(vec3(lum), vec3(lum * 0.9, 0.0, 0.0), 0.7);
    } else if (u_theme >= 0.5) {
        // Night — dim to 50%.
        color *= 0.5;
    }

    frag_color = vec4(color, 1.0);
}
