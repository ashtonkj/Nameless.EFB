// common_uniforms.glsl
// Injected after the #version directive of every shader by ShaderManager.
// Do NOT add a #version line here.

precision highp float;

uniform mat4  u_mvp;        // model-view-projection matrix
uniform float u_theme;      // 0.0 = day, 1.0 = night, 2.0 = red-cockpit
uniform float u_time_sec;   // sim time in seconds (for animations: beacon flash, etc.)
