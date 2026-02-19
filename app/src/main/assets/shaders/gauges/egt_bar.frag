#version 300 es
// egt_bar.frag — green → yellow → red temperature colour ramp for EGT/CHT.
//
// u_is_peak flags the cylinder at peak EGT during lean-assist mode with
// a white highlight so the pilot can identify the hottest cylinder.

in float v_fill_frac;

uniform float u_is_peak;   // 1.0 if this cylinder is at peak EGT; 0.0 otherwise

out vec4 frag_color;

void main() {
    // Lower half of bar: green → yellow; upper half: yellow → red.
    vec3 col;
    if (v_fill_frac < 0.5) {
        col = mix(vec3(0.0, 0.8, 0.0), vec3(1.0, 1.0, 0.0), v_fill_frac * 2.0);
    } else {
        col = mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), (v_fill_frac - 0.5) * 2.0);
    }

    // Blend toward white for the peak-EGT cylinder (lean-assist highlight).
    if (u_is_peak > 0.5) {
        col = mix(col, vec3(1.0, 1.0, 1.0), 0.25);
    }

    frag_color = vec4(col, 1.0);
}
