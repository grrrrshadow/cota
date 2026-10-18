#version 120
uniform sampler2D tex;
float scale = 0.5;
float bias = scale * 0.25;
vec4 accum = vec4(0.0);
int counter = 0;
void main()
{
    accum += texture2D(tex, gl_TexCoord[0].xy) * scale + bias;
    counter++;
    accum.rgb *= float(counter);
    gl_FragColor = accum;
}
