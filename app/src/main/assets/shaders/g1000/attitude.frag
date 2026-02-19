#version 300 es
// attitude.frag — sky/ground gradient for G1000 attitude sphere

in  vec2 v_texcoord;
out vec4 frag_color;

// G1000 CRG sky: #00B3FF  ground: #B06010
const vec3 SKY_COLOR    = vec3(0.0,  0.702, 1.0);
const vec3 GROUND_COLOR = vec3(0.69, 0.376, 0.063);

void main() {
    // v_texcoord.y > 0.5 = sky, < 0.5 = ground (set by CPU-side transform)
    vec3 color = mix(GROUND_COLOR, SKY_COLOR, step(0.5, v_texcoord.y));
    frag_color = vec4(color, 1.0);
}
