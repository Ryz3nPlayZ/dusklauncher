#version 120

// The 1.21.11 frame_blending shader (natural-motionblur) in GLSL 1.20: a
// weighted average of recent frames in linear light.

uniform sampler2D Sample0;
uniform sampler2D Sample1;
uniform sampler2D Sample2;
uniform sampler2D Sample3;
uniform sampler2D Sample4;
uniform sampler2D Sample5;
uniform sampler2D Sample6;
uniform sampler2D Sample7;
uniform sampler2D Sample8;
uniform sampler2D Sample9;
uniform sampler2D Sample10;
uniform sampler2D Sample11;
uniform float weights[12];
uniform float invTotalWeight;

varying vec2 texCoord;

vec3 lin(vec3 c) { return c * c; }

void main() {
    vec3 accum = vec3(0.0);
    accum += lin(texture2D(Sample0, texCoord).rgb) * weights[0];
    accum += lin(texture2D(Sample1, texCoord).rgb) * weights[1];
    accum += lin(texture2D(Sample2, texCoord).rgb) * weights[2];
    accum += lin(texture2D(Sample3, texCoord).rgb) * weights[3];
    accum += lin(texture2D(Sample4, texCoord).rgb) * weights[4];
    accum += lin(texture2D(Sample5, texCoord).rgb) * weights[5];
    accum += lin(texture2D(Sample6, texCoord).rgb) * weights[6];
    accum += lin(texture2D(Sample7, texCoord).rgb) * weights[7];
    accum += lin(texture2D(Sample8, texCoord).rgb) * weights[8];
    accum += lin(texture2D(Sample9, texCoord).rgb) * weights[9];
    accum += lin(texture2D(Sample10, texCoord).rgb) * weights[10];
    accum += lin(texture2D(Sample11, texCoord).rgb) * weights[11];
    gl_FragColor = vec4(sqrt(max(accum * invTotalWeight, vec3(0.0))), 1.0);
}
