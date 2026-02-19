#version 300 es
// attitude.vert — G1000 attitude indicator sky/ground sphere

in vec2 a_position;
in vec2 a_texcoord;

uniform float u_pitch_deg;   // aircraft pitch in degrees
uniform float u_roll_deg;    // aircraft roll  in degrees

out vec2 v_texcoord;

void main() {
    // The pitch/roll transforms are applied in the MVP; shader just passes through.
    v_texcoord  = a_texcoord;
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
