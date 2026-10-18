#version 330
in vec4 p; out vec3 tc;
void main(){ tc = p.xyz; gl_Position = p; }
