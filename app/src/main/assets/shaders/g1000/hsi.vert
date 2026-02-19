#version 300 es
// hsi.vert — G1000 HSI compass rose

in vec2 a_position;
in vec2 a_texcoord;

uniform float u_heading_deg;  // current magnetic heading (rotates the rose)

out vec2 v_texcoord;

void main() {
    v_texcoord  = a_texcoord;
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
