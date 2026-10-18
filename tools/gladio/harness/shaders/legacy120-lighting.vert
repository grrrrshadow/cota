#version 120
uniform sampler2D heightmap;
varying vec2 uv;
varying vec3 n;
void main()
{
    uv = gl_MultiTexCoord0.xy;
    n = gl_NormalMatrix * gl_Normal;
    vec4 p = gl_Vertex;
    p.y += texture2DLod(heightmap, uv, 0.0).r;
    gl_FrontColor = gl_Color;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_Position = gl_ModelViewProjectionMatrix * p;
}
