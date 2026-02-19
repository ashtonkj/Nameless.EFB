#version 300 es
// egt_bar.vert — single EGT/CHT bar (renderer calls this once per cylinder).
//
// The renderer submits a unit quad (±0.5 in both axes) with the bar's
// origin, size, and fill fraction encoded as uniforms.  The bar grows
// upward from its bottom edge.

in vec2 a_position;    // unit quad, centre at origin (−0.5..+0.5 in y)

uniform float u_fill_fraction;   // normalised bar height in [0, 1]
uniform vec2  u_bar_origin;      // bottom-centre in NDC space
uniform vec2  u_bar_size;        // (width, max_height) in NDC

out float v_fill_frac;   // interpolated fill fraction for colour ramp

void main() {
    // Scale x to bar width; shift y so the bar grows upward from origin.
    float x = u_bar_origin.x + a_position.x * u_bar_size.x;
    float y = u_bar_origin.y + (a_position.y + 0.5) * u_bar_size.y * u_fill_fraction;

    // v_fill_frac encodes how far up within the filled region we are (0=bottom, 1=top).
    v_fill_frac = (a_position.y + 0.5) * u_fill_fraction;

    gl_Position = u_mvp * vec4(x, y, 0.0, 1.0);
}
