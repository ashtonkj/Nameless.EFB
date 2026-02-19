#version 300 es
// text_glyph.frag — renders a glyph from the font atlas with tint colour

uniform sampler2D u_font_atlas;
uniform vec4      u_color;       // tint (typically white or theme-driven)

in  vec2 v_texcoord;
out vec4 frag_color;

void main() {
    float alpha = texture(u_font_atlas, v_texcoord).r;
    frag_color  = vec4(u_color.rgb, u_color.a * alpha);
}
