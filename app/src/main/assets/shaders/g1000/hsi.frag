#version 300 es
// hsi.frag — compass rose texture sample

uniform sampler2D u_texture;

in  vec2 v_texcoord;
out vec4 frag_color;

void main() {
    frag_color = texture(u_texture, v_texcoord);
}
