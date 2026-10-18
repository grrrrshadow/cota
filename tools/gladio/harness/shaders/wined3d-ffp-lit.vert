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
    vec4 emissive;
    vec4 ambient;
    vec4 diffuse;
    vec4 specular;
    float shininess;
} ffp_material;
uniform vec3 ffp_light_ambient;
uniform struct
{
    vec4 diffuse;
    vec4 specular;
    vec4 ambient;
    vec4 position;
    vec3 direction;
    float range;
    float falloff;
    float c_att;
    float l_att;
    float q_att;
    float cos_htheta;
    float cos_hphi;
} ffp_light[8];
out vec4 ffp_varying_diffuse;
out vec4 ffp_varying_specular;
out vec4 ffp_varying_texcoord[8];
out float ffp_varying_fogcoord;

vec3 ffp_normalize(vec3 n)
{
    float lensq = dot(n, n);
    return lensq == 0.0 ? n : (n * inversesqrt(lensq));
}

void main()
{
float m;
vec3 r;
vec4 ffp_attrib_position = vs_in0;
vec4 ffp_attrib_blendweight = vs_in1;
vec3 ffp_attrib_normal = vs_in3;
float ffp_attrib_psize = vs_in4;
vec4 ffp_attrib_diffuse = vs_in5.zyxw;
vec4 ffp_attrib_specular = vs_in6.zyxw;
vec4 ffp_attrib_texcoord0 = vs_in7;
vec4 ffp_attrib_texcoord1 = vs_in8;
ffp_attrib_blendweight[0] = 1.0;
vec4 ec_pos = vec4(0.0);
ec_pos += ffp_attrib_blendweight[0] * (ffp_modelview_matrix[0] * vec4(ffp_attrib_position.xyz, 1.0));
gl_Position = ffp_projection_matrix * ec_pos;
ec_pos /= ec_pos.w;
vec3 normal = vec3(0.0);
normal = mat3(ffp_normal_matrix) * ffp_attrib_normal;
normal = ffp_normalize(normal);
vec3 ambient = ffp_light_ambient;
vec3 diffuse = vec3(0.0);
vec3 dir, dst;
float att, t;
dir = ffp_light[0].position.xyz - ec_pos.xyz;
dst.z = dot(dir, dir);
dst.y = sqrt(dst.z);
dst.x = 1.0;
if (dst.y <= ffp_light[0].range)
{
att = dot(dst.xyz, vec3(ffp_light[0].c_att, ffp_light[0].l_att, ffp_light[0].q_att));
att = 1.0 / att;
ambient += ffp_light[0].ambient.xyz * att;
dir = ffp_normalize(dir);
diffuse += clamp(dot(dir, normal), 0.0, 1.0) * ffp_light[0].diffuse.xyz * att;
t = dot(normal, ffp_normalize(dir + vec3(0.0, 0.0, -1.0)));
}
ambient += ffp_light[1].ambient.xyz;
att = 1.0;
dir = ffp_normalize(ffp_light[1].direction.xyz);
diffuse += clamp(dot(dir, normal), 0.0, 1.0) * ffp_light[1].diffuse.xyz * att;
t = dot(normal, ffp_normalize(dir + vec3(0.0, 0.0, -1.0)));
ffp_varying_diffuse.xyz = ffp_attrib_diffuse.xyz * ambient + ffp_attrib_diffuse.xyz * diffuse + ffp_material.emissive.xyz;
ffp_varying_diffuse.w = ffp_attrib_diffuse.w;
ffp_varying_specular = ffp_attrib_specular;
ffp_varying_diffuse = clamp(ffp_varying_diffuse, 0.0, 1.0);
ffp_varying_specular = clamp(ffp_varying_specular, 0.0, 1.0);
ffp_varying_texcoord[0] = ffp_texture_matrix[0] * ffp_attrib_texcoord0;
ffp_varying_texcoord[1] = ffp_texture_matrix[1] * ffp_attrib_texcoord1;
ffp_varying_texcoord[2] = ffp_texture_matrix[2] * vec4(reflect(ffp_normalize(ec_pos.xyz), normal), 1.0);
r = reflect(ffp_normalize(ec_pos.xyz), normal);
m = 2.0 * length(vec3(r.x, r.y, r.z + 1.0));
ffp_varying_texcoord[3] = ffp_texture_matrix[3] * vec4(r.x / m + 0.5, r.y / m + 0.5, 0.0, 1.0);
ffp_varying_fogcoord = abs(ec_pos.z);
}
