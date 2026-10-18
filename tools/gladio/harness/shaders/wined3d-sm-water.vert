#version 150
#extension GL_ARB_explicit_attrib_location : enable
#extension GL_ARB_shader_bit_encoding : enable
invariant gl_Position;
uniform vec4 vs_c[256];
uniform vec4 pos_fixup;
layout(location = 0) in vec4 vs_in0;
layout(location = 1) in vec4 vs_in1;
layout(location = 2) in vec4 vs_in2;
out shader_in_out { vec4 reg[2]; } shader_out;
vec4 vs_out[2];
vec4 R0;
void main()
{
R0.xyzw = (vs_in0.xyzw);
gl_Position.x = (dot(R0.xyzw, vs_c[0].xyzw));
gl_Position.y = (dot(R0.xyzw, vs_c[1].xyzw));
gl_Position.z = (dot(R0.xyzw, vs_c[2].xyzw));
gl_Position.w = (dot(R0.xyzw, vs_c[3].xyzw));
vs_out[0].xyzw = (vs_in1.xyzw);
vs_out[1].xy = (vs_in2.xy);
shader_out.reg[0] = vs_out[0];
shader_out.reg[1] = vs_out[1];
gl_Position.y = gl_Position.y * pos_fixup.y;
gl_Position.xy += pos_fixup.zw * gl_Position.ww;
gl_Position.z = gl_Position.z * 2.0 - gl_Position.w;
}
