#version 300 es
// ai_horizon.frag — sky / ground colour for the Attitude Indicator.
//
// v_horizon_pos.y > 0 → above horizon (sky), < 0 → below (ground).
// A thin white band at y≈0 draws the physical horizon line.
//
// Colour scheme adjusts with u_theme (injected by ShaderManager):
//   0 = day, 1 = night, 2 = red-cockpit (treated same as night here).

in vec2 v_horizon_pos;

out vec4 frag_color;

void main() {
    // Day palette
    vec3 skyDay    = vec3(0.176, 0.533, 0.796);   // aviation blue
    vec3 groundDay = vec3(0.451, 0.314, 0.157);   // earth brown

    // Night palette
    vec3 skyNight    = vec3(0.043, 0.118, 0.247);
    vec3 groundNight = vec3(0.149, 0.098, 0.039);

    float nightBlend = clamp(u_theme, 0.0, 1.0);
    vec3 sky    = mix(skyDay,    skyNight,    nightBlend);
    vec3 ground = mix(groundDay, groundNight, nightBlend);

    // Thin white horizon line (±1.2% of disc radius).
    const float LINE_HALF = 0.012;
    if (abs(v_horizon_pos.y) < LINE_HALF) {
        frag_color = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }

    frag_color = vec4(v_horizon_pos.y >= 0.0 ? sky : ground, 1.0);
}
