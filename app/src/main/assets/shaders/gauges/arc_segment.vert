#version 300 es
// arc_segment.vert — coloured arc band (no texture)

in vec2 a_position;   // pre-built triangle-strip vertices from buildArcStrip()

void main() {
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
