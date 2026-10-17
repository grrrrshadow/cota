package com.winlator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.winlator.container.GraphicsDrivers;
import com.winlator.core.KeyValueSet;
import com.winlator.xenvironment.components.VortekRendererComponent;

import org.junit.Test;

public class VortekExtensionsTest {
    @Test
    public void customSelectionUsesTheDialogSeparator() {
        // VortekConfigDialog saves a custom selection joined with ':'; this used to be split on '|' and matched nothing.
        String config = "exposedDeviceExtensions=VK_KHR_swapchain:VK_KHR_maintenance1:VK_EXT_robustness2,vkMaxVersion=1.3|";
        KeyValueSet vulkanConfig = GraphicsDrivers.parseConfigs("vortek,zink", config)[0];
        assertArrayEquals(new String[]{"VK_KHR_swapchain", "VK_KHR_maintenance1", "VK_EXT_robustness2"},
            VortekRendererComponent.Options.parseExposedDeviceExtensions(vulkanConfig.get("exposedDeviceExtensions", "all")));
        assertArrayEquals(new String[]{"VK_KHR_swapchain"}, VortekRendererComponent.Options.parseExposedDeviceExtensions("VK_KHR_swapchain"));
        assertNull(VortekRendererComponent.Options.parseExposedDeviceExtensions("all"));
        assertNull(VortekRendererComponent.Options.parseExposedDeviceExtensions(""));
        assertNull(VortekRendererComponent.Options.parseExposedDeviceExtensions("VK_KHR_swapchain|VK_KHR_maintenance1"));
    }
}
