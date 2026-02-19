#version 300 es
// tile.frag — sample a raster map tile, apply night-mode desaturation

uniform sampler2D u_texture;

in  vec2 v_texcoord;
out vec4 frag_color;

void main() {
    vec4 tile = texture(u_texture, v_texcoord);

    // Night mode: desaturate + darken according to u_theme.
    float grey   = dot(tile.rgb, vec3(0.299, 0.587, 0.114));
    float factor = 1.0 - clamp(u_theme, 0.0, 1.0) * 0.6;
    vec3  color  = mix(tile.rgb, vec3(grey), clamp(u_theme, 0.0, 1.0) * 0.5) * factor;

    frag_color = vec4(color, tile.a);
}
