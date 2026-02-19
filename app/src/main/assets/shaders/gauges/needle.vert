#version 300 es
// needle.vert — rotates a needle sprite around the dial centre

in  vec2  a_position;    // local-space vertex (unit quad)
in  vec2  a_texcoord;

uniform float u_needle_angle; // current value mapped to degrees (via CPU)
uniform vec2  u_pivot;        // pivot point in NDC

out vec2 v_texcoord;

void main() {
    float rad  = radians(u_needle_angle);
    float cosA = cos(rad);
    float sinA = sin(rad);

    // Rotate around pivot
    vec2 rotated = vec2(
        a_position.x * cosA - a_position.y * sinA,
        a_position.x * sinA + a_position.y * cosA
    ) + u_pivot;

    v_texcoord  = a_texcoord;
    gl_Position = u_mvp * vec4(rotated, 0.0, 1.0);
}
