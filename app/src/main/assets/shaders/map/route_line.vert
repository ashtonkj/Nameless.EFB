#version 300 es
// route_line.vert — flight-plan route polyline

in vec2 a_position;   // world-space vertex of the route segment

void main() {
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
