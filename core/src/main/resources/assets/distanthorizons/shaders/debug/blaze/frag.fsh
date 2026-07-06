#version 330 core

out vec4 fragColor;

layout (std140) uniform uniformBlock
{
    mat4 uTransform;
    vec4 uColor;
};

void main()
{
    fragColor = uColor;
}