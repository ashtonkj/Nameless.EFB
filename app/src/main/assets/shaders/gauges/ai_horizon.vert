#version 300 es
// ai_horizon.vert — Attitude Indicator sky/ground disc
//
// Outputs the vertex position relative to the pitch-offset, bank-rotated
// horizon for use by ai_horizon.frag to decide sky vs. ground colour.

in vec2 a_position;    // unit disc vertex (range -1..1 in both axes)

uniform float u_pitch_deg;   // pitch in degrees (positive = nose up)
uniform float u_bank_rad;    // bank in radians (positive = right wing down)

out vec2 v_horizon_pos;      // rotated disc pos for sky/ground split

void main() {
    // Pitch shifts the horizon line downward when nose is up.
    // Scale factor: 0.02 NDC units per degree gives ~±50° usable pitch range.
    const float DEG_TO_NDC = 0.02;
    float horizon_y = -u_pitch_deg * DEG_TO_NDC;

    // Bank rotates the entire horizon about the instrument centre.
    float cosB = cos(u_bank_rad);
    float sinB = sin(u_bank_rad);
    mat2  rot  = mat2(cosB, -sinB, sinB, cosB);

    // Rotated disc position relative to the pitch-offset horizon.
    v_horizon_pos = rot * (a_position - vec2(0.0, horizon_y));

    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
