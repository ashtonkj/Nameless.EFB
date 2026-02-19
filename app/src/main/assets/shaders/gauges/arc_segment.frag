#version 300 es
// arc_segment.frag — solid colour arc (green/yellow/red range bands)

uniform vec4 u_color;   // RGBA range-band colour

out vec4 frag_color;

void main() {
    frag_color = u_color;
}
