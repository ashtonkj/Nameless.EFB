#version 300 es
// attitude.vert — G1000 PFD attitude indicator (sky/ground sphere)
// Passes clip-space position directly; pitch/roll transform is in the fragment shader.

in vec2 a_position;   // clip-space quad (-1..1)

out vec2 v_pos;

void main() {
    v_pos       = a_position;
    gl_Position = vec4(a_position, 0.0, 1.0);
}
