#version 150
#extension GL_ARB_explicit_attrib_location : enable
#extension GL_ARB_fragment_coord_conventions : enable
#extension GL_ARB_shader_texture_lod : enable
uniform struct
{
    vec4 color;
    float density;
    float end;
    float scale;
} ffp_fog;
uniform float alpha_test_ref;
vec4 ps_out[8];
layout(location = 0) out vec4 color_out0;
uniform sampler2D ps_sampler0;
uniform samplerCube ps_sampler1;
uniform sampler2DShadow ps_sampler2;
uniform vec4 ps_c[224];
in shader_in_out { vec4 reg[2]; } shader_in;
vec4 R0;
vec4 R1;
void main()
{
R0.xyzw = (texture(ps_sampler0, shader_in.reg[1].xy).xyzw);
R1.xyzw = (texture(ps_sampler1, shader_in.reg[1].xyz).xyzw);
R1.w = texture(ps_sampler2, vec3(shader_in.reg[1].xy, 0.5));
ps_out[0].xyzw = (R0.xyzw * shader_in.reg[0].xyzw * R1.wwww);
float fog = clamp(ffp_fog.scale * (ffp_fog.end - gl_FragCoord.z / gl_FragCoord.w), 0.0, 1.0);
ps_out[0].xyz = mix(ffp_fog.color.xyz, ps_out[0].xyz, fog);
if (!(ps_out[0].a >= alpha_test_ref)) discard;
color_out0 = ps_out[0];
}
