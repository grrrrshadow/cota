#version 150
vec4 ps_out[1];
out vec4 color_out0;
vec4 tmp0, tmp1;
vec4 ret;
vec4 arg0, arg1, arg2;
uniform sampler2D ps_sampler0;
vec4 tex0;
uniform sampler2D ps_sampler1;
vec4 tex1;
uniform samplerCube ps_sampler2;
vec4 tex2;
uniform sampler3D ps_sampler3;
vec4 tex3;
uniform vec4 tex_factor;
uniform vec4 specular_enable;
uniform struct
{
    vec4 color;
    float density;
    float end;
    float scale;
} ffp_fog;
uniform float alpha_test_ref;
in vec4 ffp_varying_diffuse;
in vec4 ffp_varying_specular;
in vec4 ffp_varying_texcoord[8];
vec4 ffp_texcoord[8];
in float ffp_varying_fogcoord;
void main()
{
ffp_texcoord[0] = ffp_varying_texcoord[0];
ffp_texcoord[1] = ffp_varying_texcoord[1];
ffp_texcoord[2] = ffp_varying_texcoord[2];
ffp_texcoord[3] = ffp_varying_texcoord[3];
tex0 = texture(ps_sampler0, ffp_texcoord[0].xy);
tex1 = textureProj(ps_sampler1, ffp_texcoord[1].xyw);
tex2 = texture(ps_sampler2, ffp_texcoord[2].xyz);
tex3 = texture(ps_sampler3, ffp_texcoord[3].xyz);
ret = ffp_varying_diffuse;
ret.xyz = tex0.xyz * ret.xyz;
ret.w = tex0.w * ret.w;
ret.xyz = clamp(tex1.xyz * ret.xyz * 2.0, 0.0, 1.0);
ret.xyz = mix(ret.xyz, tex2.xyz, tex_factor.w);
arg1 = vec4(1.0) - tex3;
ret.xyz = clamp(ret.xyz + (arg1 - vec4(0.5)).xyz, 0.0, 1.0);
ps_out[0] = ffp_varying_specular * specular_enable + ret;
float fog = (ffp_fog.end - ffp_varying_fogcoord) * ffp_fog.scale;
ps_out[0].xyz = mix(ffp_fog.color.xyz, ps_out[0].xyz, clamp(fog, 0.0, 1.0));
if (!(ps_out[0].a >= alpha_test_ref))
    discard;
color_out0 = ps_out[0];
}
