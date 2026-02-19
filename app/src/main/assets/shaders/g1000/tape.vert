#version 300 es
// tape.vert — scrolling airspeed/altitude tape

in vec2  a_position;
in vec2  a_texcoord;

uniform float u_scroll;   // normalised scroll offset (0–1) driven by indicated value

out vec2 v_texcoord;

void main() {
    // Shift V coordinate by scroll amount so the tape texture moves continuously.
    v_texcoord  = vec2(a_texcoord.x, a_texcoord.y + u_scroll);
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
