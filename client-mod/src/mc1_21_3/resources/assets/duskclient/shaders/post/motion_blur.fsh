#version 150

// Accumulation blur: keep blendFactor of the previous blended frame, mixed in
// (approximately) linear light so bright trails don't go muddy.
// 1.21.2–1.21.3 flavour: GLSL 150 program declared by shaders/post/motion_blur.json.
uniform sampler2D MainSampler;
uniform sampler2D PrevSampler;

uniform float blendFactor;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 curr = texture(MainSampler, texCoord).rgb;
    vec3 prev = texture(PrevSampler, texCoord).rgb;
    vec3 blended = mix(curr * curr, prev * prev, blendFactor);
    fragColor = vec4(sqrt(blended), 1.0);
}
