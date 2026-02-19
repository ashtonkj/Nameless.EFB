#version 300 es
// route_line.frag — solid magenta route line (matches G1000 CRG colour)

uniform vec4 u_color;   // default: magenta (1.0, 0.0, 1.0, 1.0)

out vec4 frag_color;

void main() {
    frag_color = u_color;
}
