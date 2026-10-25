/* SPDX-License-Identifier: GPL-3.0-or-later
 * Read the system Vulkan driver's actual features. No device/model table and
 * no Vulkan device or presentation surface is created by this query. */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdlib.h>

static jstring capability_error(JNIEnv *env, const char *status) {
    return (*env)->NewStringUTF(env, status);
}

EXPORT jstring Java_org_factorio_local_launcher_GraphicsCapabilities_query(JNIEnv *env, jclass type) {
    (void)type;
    void *library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return capability_error(env, "{\"status\":\"loader-unavailable\"}");
    PFN_vkGetInstanceProcAddr get = (void *)dlsym(library, "vkGetInstanceProcAddr");
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice *physical = NULL;
    VkExtensionProperties *extensions = NULL;
    const char *error = "{\"status\":\"query-failed\"}";
    jstring answer = NULL;
    if (!get) goto done;
    PFN_vkCreateInstance create = (void *)get(VK_NULL_HANDLE, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion instance_version = (void *)get(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    uint32_t version = VK_API_VERSION_1_0;
    if (!instance_version || instance_version(&version) != VK_SUCCESS || version < VK_API_VERSION_1_1) {
        error = "{\"status\":\"properties2-unavailable\"}"; goto done;
    }
    VkApplicationInfo application = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Graphics capability query", .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &application};
    if (!create || create(&info, NULL, &instance) != VK_SUCCESS) goto done;
    PFN_vkEnumeratePhysicalDevices enumerate = (void *)get(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties properties = (void *)get(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkEnumerateDeviceExtensionProperties device_extensions = (void *)get(instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkGetPhysicalDeviceFeatures2 features = (void *)get(instance, "vkGetPhysicalDeviceFeatures2");
    if (!enumerate || !properties || !device_extensions || !features) goto done;
    uint32_t count = 0;
    if (enumerate(instance, &count, NULL) != VK_SUCCESS || !count || count > 64) goto done;
    physical = calloc(count, sizeof(*physical));
    if (!physical || enumerate(instance, &count, physical) != VK_SUCCESS) goto done;
    VkPhysicalDevice selected = physical[0];
    VkPhysicalDeviceProperties props;
    for (uint32_t i = 0; i < count; i++) {
        properties(physical[i], &props);
        if (props.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {selected = physical[i]; break;}
    }
    properties(selected, &props);
    count = 0;
    if (device_extensions(selected, NULL, &count, NULL) != VK_SUCCESS || count > 4096) goto done;
    extensions = calloc(count ? count : 1, sizeof(*extensions));
    if (!extensions || device_extensions(selected, NULL, &count, extensions) != VK_SUCCESS) goto done;
    int dynamic_extension = 0, robustness_extension = 0;
    for (uint32_t i = 0; i < count; i++) {
        if (!strcmp(extensions[i].extensionName, "VK_KHR_dynamic_rendering")) dynamic_extension = 1;
        if (!strcmp(extensions[i].extensionName, "VK_EXT_robustness2")) robustness_extension = 1;
    }
    VkPhysicalDeviceDynamicRenderingFeatures dynamic = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES};
    VkPhysicalDeviceRobustness2FeaturesEXT robustness = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 values = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (dynamic_extension || props.apiVersion >= VK_API_VERSION_1_3) {dynamic.pNext = values.pNext; values.pNext = &dynamic;}
    if (robustness_extension) {robustness.pNext = values.pNext; values.pNext = &robustness;}
    features(selected, &values);
    /* Only numeric values and fixed field names are written to this JSON. */
    char json[320];
    snprintf(json, sizeof(json), "{\"status\":\"ok\",\"apiVersion\":%u,\"vendorId\":%u,\"deviceId\":%u,\"dynamicRendering\":%s,\"nullDescriptor\":%s}",
        props.apiVersion, props.vendorID, props.deviceID, dynamic.dynamicRendering ? "true" : "false", robustness.nullDescriptor ? "true" : "false");
    answer = (*env)->NewStringUTF(env, json);
 done:
    free(extensions); free(physical);
    if (instance && get) {PFN_vkDestroyInstance destroy = (void *)get(instance, "vkDestroyInstance"); if (destroy) destroy(instance, NULL);}
    dlclose(library);
    return answer ? answer : capability_error(env, error);
}
