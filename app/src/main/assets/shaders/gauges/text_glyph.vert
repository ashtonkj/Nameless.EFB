#version 300 es
// text_glyph.vert — positions a single glyph quad from the font atlas

in vec2 a_position;
in vec2 a_texcoord;

out vec2 v_texcoord;

void main() {
    v_texcoord  = a_texcoord;
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
