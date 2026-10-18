#version 120
uniform sampler2D tex;
varying vec2 uv;
varying vec3 n;
void main()
{
    gl_FragColor = texture2D(tex, gl_TexCoord[0].xy) * gl_Color;
}
