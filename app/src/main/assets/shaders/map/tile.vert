#version 300 es
// tile.vert — OSM raster map tile

in vec2 a_position;   // tile corner in world/tile space
in vec2 a_texcoord;

out vec2 v_texcoord;

void main() {
    v_texcoord  = a_texcoord;
    gl_Position = u_mvp * vec4(a_position, 0.0, 1.0);
}
