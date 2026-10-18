#version 120
#extension GL_ARB_shader_texture_lod : enable
uniform sampler2DShadow shadowMap;
uniform sampler2D tex;
uniform sampler1D ramp;
uniform samplerCube env;
varying vec4 shadowCoord;
void main()
{
    float s = shadow2DProj(shadowMap, shadowCoord).r;
    float s2 = shadow2D(shadowMap, shadowCoord.xyz).r;
    vec4 c = texture2DLodARB(tex, gl_TexCoord[0].xy, 1.0) + texture2DProj(tex, gl_TexCoord[0]);
    vec4 r = texture1D(ramp, c.r) + textureCube(env, gl_TexCoord[1].xyz) + textureCubeLodARB(env, gl_TexCoord[1].xyz, 0.0);
    gl_FragColor = c * r * s * s2 * gl_Color;
}
