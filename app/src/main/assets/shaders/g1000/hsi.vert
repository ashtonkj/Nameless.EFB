#version 300 es
// hsi.vert — G1000 HSI compass rose with heading rotation

layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_texcoord;

uniform float u_heading_deg;  // current magnetic heading (rotates the rose)

out vec2 v_texcoord;

void main() {
    // Rotate texture coordinates around centre (0.5, 0.5) by heading.
    float rad = radians(-u_heading_deg);
    float c = cos(rad);
    float s = sin(rad);
    vec2 centred = a_texcoord - 0.5;
    v_texcoord = vec2(c * centred.x - s * centred.y,
                      s * centred.x + c * centred.y) + 0.5;
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
