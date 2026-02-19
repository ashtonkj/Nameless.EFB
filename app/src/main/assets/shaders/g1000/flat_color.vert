#version 300 es
// flat_color.vert — G1000 flat-colour primitive (bars, bugs, markers, borders)

in vec2 a_position;

void main() {
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
