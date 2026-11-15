#version 120

uniform sampler2D MainSampler;
uniform sampler2D PrevSampler;
uniform float blendFactor;

varying vec2 texCoord;

void main() {
    vec3 curr = texture2D(MainSampler, texCoord).rgb;
    vec3 prev = texture2D(PrevSampler, texCoord).rgb;
    gl_FragColor = vec4(sqrt(mix(curr * curr, prev * prev, blendFactor)), 1.0);
}
