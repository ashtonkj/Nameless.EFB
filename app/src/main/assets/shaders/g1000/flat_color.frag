#version 300 es
// flat_color.frag — outputs a single uniform colour (no texture)

uniform vec4 u_color;

out vec4 frag_color;

void main() {
    frag_color = u_color;
}
