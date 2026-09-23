#version 150

// DuskClient motion blur, 1.21–1.21.1: the legacy post chain only resolves
// program names in the minecraft namespace, hence the file's home and prefix.
uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;
uniform float blendFactor;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 curr = texture(DiffuseSampler, texCoord).rgb;
    vec3 prev = texture(PrevSampler, texCoord).rgb;
    // blend in (approximately) linear light so bright trails don't dull
    vec3 mixed = mix(curr * curr, prev * prev, blendFactor);
    fragColor = vec4(sqrt(mixed), 1.0);
}
