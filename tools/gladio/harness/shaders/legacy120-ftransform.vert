#version 120
varying vec4 tc;
void main() { tc = gl_MultiTexCoord0; gl_Position = ftransform(); }
