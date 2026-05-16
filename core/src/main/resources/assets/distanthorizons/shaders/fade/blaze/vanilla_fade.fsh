#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D uMcDepthTexture;
uniform sampler2D uCombinedMcDhColorTexture;

uniform sampler2D uDhDepthTexture;
uniform sampler2D uDhColorTexture;

layout (std140) uniform fragUniformBlock
{
    bool uOnlyRenderLods;
    
    float uStartFadeBlockDistance;
    float uEndFadeBlockDistance;
    float uMaxLevelHeight;
    
    // inverted model view matrix and projection matrix
    mat4 uDhInvMvmProj;
    mat4 uMcInvMvmProj;
};



vec3 calcViewPosition(float fragmentDepth, mat4 invMvmProj) 
{
    // normalized device coordinates
    vec4 ndc = vec4(
        TexCoord.x, // UV [0,1]
        TexCoord.y,
        fragmentDepth, // depth [0,1]
        1.0
    );
    // AKA: remap the [0,1] UV coordinates and depth value
    // into the [-1,1] positions used by
    // the NDC cube rendering stage
    ndc.xyz = ndc.xyz * 2.0 - 1.0;
    
    vec4 eyeCoord = invMvmProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

// TODO vulkan
vec3 calcReversedZViewPosition(float fragmentDepth, mat4 invMvmProj)
{
    // Vulkan NDC: xy in [-1,+1], z already in [0,1] — don't remap z
    vec4 ndc = vec4(
        TexCoord.x * 2.0 - 1.0, // UV [0,1] -> NDC [-1,+1]
        TexCoord.y * 2.0 - 1.0,
        fragmentDepth, // no remapping needed, this depth is already in the [0,1] range
        1.0 // w=1 placeholder for matrix multiplication
    );
    
    vec4 eyeCoord = invMvmProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

/**
 * Used to fade out vanilla chunks so the transition
 * between DH and vanilla is smoother.
 */
void main() 
{
    // includes both the vanilla chunks as well as DH
    vec4 combinedMcDhColor = texture(uCombinedMcDhColorTexture, TexCoord);
    // just the DH render pass
    vec4 dhColor = texture(uDhColorTexture, TexCoord);
    
    // completely remove the MC render pass to only show LODs
    // useful for debugging/troubleshooting, but doesn't improve performance since MC is still rendering
    if (uOnlyRenderLods)
    {
        fragColor = dhColor;
        return;
    }
    
    
    // ignore anything that DH hasn't drawn to
    if (dhColor.a == 0.0f)
    {
        // if not done vanilla clouds will render incorrectly at night
        dhColor = combinedMcDhColor;
    }
    
    float mcFragmentDepth = texture(uMcDepthTexture, TexCoord).r;
    float dhFragmentDepth = texture(uDhDepthTexture, TexCoord).r;
    vec3 dhVertexWorldPos = calcViewPosition(dhFragmentDepth, uDhInvMvmProj);
    
	// this is a work around to prevent MC clouds rendering behind DH clouds
    if (dhVertexWorldPos.y > uMaxLevelHeight)
    {
        fragColor = vec4(combinedMcDhColor.rgb, 0.0);
    }
//    // a fragment depth of "1" means the fragment wasn't drawn to,
//    // we only want to fade vanilla rendered objects, not to the sky or LODs
//    else if (mcFragmentDepth < 1.0)
    else if (mcFragmentDepth > 0)
    {
        // fade based on distance from the camera
        vec3 mcVertexWorldPos = calcReversedZViewPosition(mcFragmentDepth, uMcInvMvmProj);
        float mcFragmentDistance = length(mcVertexWorldPos.xzy);
        
        
        // Smoothly transition between combinedMcDhColor and uDhColorTexture
        // as the depth increases from the camera
        float fadeStep = smoothstep(uStartFadeBlockDistance, uEndFadeBlockDistance, mcFragmentDistance);
        fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
        fragColor.a = 1.0;
    }
    else
    {
        fragColor = vec4(combinedMcDhColor.rgb, 0.0);
    }
}

