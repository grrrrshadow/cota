#version 330
in vec4 p;
out vec3 tc;
void main(){ tc = vec3(float(gl_VertexID), float(gl_InstanceID), 0.0); gl_Position = p; }
