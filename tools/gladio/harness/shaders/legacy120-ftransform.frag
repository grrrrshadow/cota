#version 120
uniform sampler3D t3;
varying vec4 tc;
void main() {
gl_FragColor = texture3DLod(t3, tc.xyz, 0.0);
}
