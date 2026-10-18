// Vulkan layer that shows the guest what Vortek shows "mesa zink" (vulkan_helper.c in vortekrenderer): device
// apiVersion capped at 1.3.128 (the highest the Vortek dialog offers) and only the fixed extension list, minus the
// ones Vortek disables for zink, plus the ones it implements itself. Used by vortek-filter-check.sh; lists.h is
// generated from the vortekrenderer sources. VORTEK_SIM_ADD=VK_KHR_x,... also exposes those host extensions.
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include "lists.h"

#define N(a) (sizeof(a) / sizeof((a)[0]))
#define EXPORT __attribute__((visibility("default")))

static PFN_vkGetInstanceProcAddr next_gipa;
static PFN_vkGetDeviceProcAddr next_gdpa;
static VkInstance g_instance;
static PFN_vkEnumerateDeviceExtensionProperties next_edep;
static PFN_vkGetPhysicalDeviceProperties next_gpdp;
static PFN_vkGetPhysicalDeviceProperties2 next_gpdp2;
static const uint32_t MAX_VERSION = VK_MAKE_API_VERSION(0, 1, 3, 128);

static int in_list(const char *name, const char **list, size_t n) {
    for (size_t i = 0; i < n; i++) if (strcmp(name, list[i]) == 0) return 1;
    return 0;
}

static int in_env_list(const char *name) {
    const char *env = getenv("VORTEK_SIM_ADD");
    if (!env) return 0;
    size_t len = strlen(name);
    for (const char *p = env; (p = strstr(p, name)); p += len)
        if ((p == env || p[-1] == ',') && (p[len] == ',' || p[len] == 0)) return 1;
    return 0;
}

static VKAPI_ATTR VkResult VKAPI_CALL sim_EnumerateDeviceExtensionProperties(VkPhysicalDevice pd, const char *layer, uint32_t *count, VkExtensionProperties *props) {
    if (layer && strcmp(layer, "VK_LAYER_TEST_vortek_sim") == 0) { *count = 0; return VK_SUCCESS; }
    if (layer) return next_edep(pd, layer, count, props);
    uint32_t n = 0;
    next_edep(pd, NULL, &n, NULL);
    VkExtensionProperties *host = calloc(n ? n : 1, sizeof(*host));
    next_edep(pd, NULL, &n, host);
    VkExtensionProperties *out = calloc(n ? n : 1, sizeof(*out));
    uint32_t m = 0;
    for (uint32_t i = 0; i < n; i++) {
        const char *e = host[i].extensionName;
        int expose = (in_list(e, GLOBAL, N(GLOBAL)) && !in_list(e, DISABLED, N(DISABLED))) ||
                     in_list(e, IMPLEMENTED, N(IMPLEMENTED)) || in_env_list(e);
        if (expose) out[m++] = host[i];
    }
    VkResult res = VK_SUCCESS;
    if (!props) *count = m;
    else {
        if (*count < m) res = VK_INCOMPLETE; else *count = m;
        memcpy(props, out, *count * sizeof(*out));
    }
    free(host);
    free(out);
    return res;
}

static VKAPI_ATTR void VKAPI_CALL sim_GetPhysicalDeviceProperties(VkPhysicalDevice pd, VkPhysicalDeviceProperties *p) {
    next_gpdp(pd, p);
    if (p->apiVersion > MAX_VERSION) p->apiVersion = MAX_VERSION;
}

static VKAPI_ATTR void VKAPI_CALL sim_GetPhysicalDeviceProperties2(VkPhysicalDevice pd, VkPhysicalDeviceProperties2 *p) {
    next_gpdp2(pd, p);
    if (p->properties.apiVersion > MAX_VERSION) p->properties.apiVersion = MAX_VERSION;
}

static VKAPI_ATTR VkResult VKAPI_CALL sim_CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *inst) {
    VkLayerInstanceCreateInfo *li = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (li && !(li->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && li->function == VK_LAYER_LINK_INFO))
        li = (VkLayerInstanceCreateInfo *)li->pNext;
    if (!li) return VK_ERROR_INITIALIZATION_FAILED;
    next_gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)next_gipa(NULL, "vkCreateInstance");
    VkResult res = create(ci, a, inst);
    if (res != VK_SUCCESS) return res;
    g_instance = *inst;
    next_edep = (PFN_vkEnumerateDeviceExtensionProperties)next_gipa(*inst, "vkEnumerateDeviceExtensionProperties");
    next_gpdp = (PFN_vkGetPhysicalDeviceProperties)next_gipa(*inst, "vkGetPhysicalDeviceProperties");
    next_gpdp2 = (PFN_vkGetPhysicalDeviceProperties2)next_gipa(*inst, "vkGetPhysicalDeviceProperties2");
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL sim_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci, const VkAllocationCallbacks *a, VkDevice *dev) {
    VkLayerDeviceCreateInfo *li = (VkLayerDeviceCreateInfo *)ci->pNext;
    while (li && !(li->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && li->function == VK_LAYER_LINK_INFO))
        li = (VkLayerDeviceCreateInfo *)li->pNext;
    if (!li) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    next_gdpa = li->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    li->u.pLayerInfo = li->u.pLayerInfo->pNext;
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)gipa(g_instance, "vkCreateDevice");
    return create(pd, ci, a, dev);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice dev, const char *name) {
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    return next_gdpa(dev, name);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance inst, const char *name) {
    if (strcmp(name, "vkGetInstanceProcAddr") == 0) return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    if (strcmp(name, "vkCreateInstance") == 0) return (PFN_vkVoidFunction)sim_CreateInstance;
    if (strcmp(name, "vkCreateDevice") == 0) return (PFN_vkVoidFunction)sim_CreateDevice;
    if (strcmp(name, "vkEnumerateDeviceExtensionProperties") == 0) return (PFN_vkVoidFunction)sim_EnumerateDeviceExtensionProperties;
    if (strcmp(name, "vkGetPhysicalDeviceProperties") == 0) return (PFN_vkVoidFunction)sim_GetPhysicalDeviceProperties;
    if (strcmp(name, "vkGetPhysicalDeviceProperties2") == 0 || strcmp(name, "vkGetPhysicalDeviceProperties2KHR") == 0)
        return (PFN_vkVoidFunction)sim_GetPhysicalDeviceProperties2;
    return next_gipa ? next_gipa(inst, name) : NULL;
}
