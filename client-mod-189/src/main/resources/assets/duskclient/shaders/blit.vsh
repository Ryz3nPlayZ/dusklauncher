#version 120

varying vec2 texCoord;

void main() {
    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);
    texCoord = gl_Vertex.xy * 0.5 + 0.5;
}
