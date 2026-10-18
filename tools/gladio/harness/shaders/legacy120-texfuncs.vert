#version 120
uniform sampler2D heightmap;
uniform sampler1D ramp;
varying vec4 shadowCoord;
void main()
{
    vec4 h = texture2DLod(heightmap, gl_MultiTexCoord0.xy, 0.0) + texture2D(heightmap, gl_MultiTexCoord0.xy) + texture1DLod(ramp, 0.5, 0.0);
    shadowCoord = gl_TextureMatrix[1] * gl_Vertex;
    gl_TexCoord[0] = gl_MultiTexCoord0;
    gl_TexCoord[1] = vec4(gl_Normal, 0.0);
    gl_FrontColor = gl_Color;
    gl_Position = gl_ModelViewProjectionMatrix * (gl_Vertex + vec4(0.0, h.r, 0.0, 0.0));
}
