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

void main() {
    // Rotate fragment position by bank angle.
    float cosR = cos(u_roll_rad);
    float sinR = sin(u_roll_rad);
    vec2 rotated = vec2( cosR * v_pos.x + sinR * v_pos.y,
                        -sinR * v_pos.x + cosR * v_pos.y);

    // Pitch offset: 24px per degree, normalised to clip space (800px half-height).
    float pixPerDeg = 24.0 / 800.0;
    float horizon   = u_pitch_deg * pixPerDeg;

    if (rotated.y > horizon) {
        // Sky — gradient from bottom (brighter) to top (darker).
        float t = clamp((rotated.y - horizon) / 0.5, 0.0, 1.0);
        frag_color = vec4(mix(SKY_BOT, SKY_TOP, t), 1.0);
    } else {
        // Ground — gradient from top (brighter) to bottom (darker).
        float t = clamp((horizon - rotated.y) / 0.5, 0.0, 1.0);
        frag_color = vec4(mix(GND_TOP, GND_BOT, t), 1.0);
    }
}
