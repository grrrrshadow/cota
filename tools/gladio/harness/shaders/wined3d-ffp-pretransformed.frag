#version 150
vec4 ps_out[1];
out vec4 color_out0;
vec4 tmp0, tmp1;
vec4 ret;
vec4 temp_reg = vec4(0.0);
vec4 arg0, arg1, arg2;
uniform sampler2D ps_sampler0;
vec4 tex0;
uniform vec4 color_key[2];
uniform vec4 specular_enable;
uniform struct
{
    vec4 color;
    float density;
    float end;
    float scale;
} ffp_fog;
uniform float alpha_test_ref;
flat in vec4 ffp_varying_diffuse;
flat in vec4 ffp_varying_specular;
in vec4 ffp_varying_texcoord[8];
vec4 ffp_texcoord[8];
in float ffp_varying_fogcoord;
void main()
{
ffp_texcoord[0] = vec4(gl_PointCoord.xy, 0.0, 0.0);
tex0 = texture(ps_sampler0, ffp_texcoord[0].xy);
tex0.xyz = tex0.zyx;
if (all(greaterThanEqual(tex0, color_key[0])) && all(lessThan(tex0, color_key[1])))
    discard;
ret = ffp_varying_diffuse;
temp_reg.xyz = tex0.xyz;
ret.w = tex0.w * ret.w;
ret.xyz = mix(ret.xyz, temp_reg.xyz, tex0.w);
ps_out[0] = ffp_varying_specular * specular_enable + ret;
float fog = exp(-ffp_fog.density * ffp_fog.density * ffp_varying_fogcoord * ffp_varying_fogcoord);
ps_out[0].xyz = mix(ffp_fog.color.xyz, ps_out[0].xyz, clamp(fog, 0.0, 1.0));
if (!(ps_out[0].a > alpha_test_ref))
    discard;
color_out0 = ps_out[0];
}
