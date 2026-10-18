#version 330
precision highp float;
uniform sampler2DArray a; uniform isampler2D i; uniform samplerCubeShadow cs;
in vec3 tc;
out vec4 o;
void main(){ o = texture(a, tc) + vec4(texture(i, tc.xy)) + vec4(texture(cs, vec4(tc,1.0))); }
