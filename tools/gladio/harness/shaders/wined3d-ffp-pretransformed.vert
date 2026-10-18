#version 150
invariant gl_Position;
in vec4 vs_in0;
in vec4 vs_in1;
in float vs_in2;
in vec3 vs_in3;
in float vs_in4;
in vec4 vs_in5;
in vec4 vs_in6;
in vec4 vs_in7;
in vec4 vs_in8;
in vec4 vs_in9;
in vec4 vs_in10;
in vec4 vs_in11;
in vec4 vs_in12;
in vec4 vs_in13;
in vec4 vs_in14;

uniform mat4 ffp_modelview_matrix[4];
uniform mat4 ffp_projection_matrix;
uniform mat4 ffp_normal_matrix;
uniform mat4 ffp_texture_matrix[8];
uniform struct
{
    float size;
    float size_min;
    float size_max;
    float c_att;
    float l_att;
    float q_att;
} ffp_point;
flat out vec4 ffp_varying_diffuse;
flat out vec4 ffp_varying_specular;
out vec4 ffp_varying_texcoord[8];
out float ffp_varying_fogcoord;

void main()
{
vec4 ffp_attrib_position = vs_in0;
vec4 ffp_attrib_blendweight = vs_in1;
vec4 ffp_attrib_diffuse = vs_in5.zyxw;
vec4 ffp_attrib_specular = vs_in6.zyxw;
vec4 ffp_attrib_texcoord0 = vs_in7;
ffp_attrib_blendweight[0] = 1.0;
vec4 ec_pos = vec4(ffp_attrib_position.xyz, 1.0);
gl_Position = ffp_projection_matrix * ec_pos;
if (ffp_attrib_position.w != 0.0) gl_Position /= ffp_attrib_position.w;
vec3 normal = vec3(0.0);
ffp_varying_diffuse = ffp_attrib_diffuse;
ffp_varying_specular = ffp_attrib_specular;
ffp_varying_diffuse = clamp(ffp_varying_diffuse, 0.0, 1.0);
ffp_varying_specular = clamp(ffp_varying_specular, 0.0, 1.0);
ffp_varying_texcoord[0] = ffp_attrib_texcoord0;
ffp_varying_texcoord[1] = vec4(0.0);
ffp_varying_fogcoord = ffp_attrib_specular.w;
gl_PointSize = ffp_point.size / sqrt(ffp_point.c_att + ffp_point.l_att * length(ec_pos.xyz) + ffp_point.q_att * dot(ec_pos.xyz, ec_pos.xyz));
gl_PointSize = clamp(gl_PointSize, ffp_point.size_min, ffp_point.size_max);
}
