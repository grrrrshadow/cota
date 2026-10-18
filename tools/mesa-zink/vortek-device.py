#!/usr/bin/env python3
# Describes the Vulkan device zink sees on the phone (Mali through Vortek) for vortek-sim.c.
#   trim GPUINFO.json VK.XML > mali-*.json   shrinks a vulkan.gpuinfo.org profile export (api/v3/getprofile.php)
#                                            to what the simulation uses
#   header PROFILE.json VK.XML VULKAN_CORE.H VORTEKRENDERER_DIR > device.h
#                                            C code for vortek-sim.c: Mali's extensions, features, limits and
#                                            formats, what Vortek fakes (checkDeviceFeatures) and which pNext
#                                            structs Vortek's serializer (and so the closed guest ICD) carries
#   lists VORTEKRENDERER_DIR > lists.h       the extension lists Vortek uses for "mesa zink" (vulkan_helper.h/.c)
# pNext structs: zeroed when Vortek does not carry them or Mali lacks their extension, masked with Mali's values
# when the report has them, left as lavapipe filled them otherwise (most property structs: lavapipe must honour them).
# Run with python3 -I; reads only the files it is given.
import json
import re
import sys
import xml.etree.ElementTree as ET


def registry(vkxml):
    root = ET.parse(vkxml).getroot()
    structs, aliases, providers = {}, {}, {}
    for t in root.iter("type"):
        if t.get("category") != "struct":
            continue
        name = t.get("name")
        if t.get("alias"):
            aliases[name] = t.get("alias")
            continue
        stype, members = None, []
        for m in t.findall("member"):
            if m.get("api") == "vulkansc":
                continue
            mname = m.find("name").text
            mtype = m.find("type").text
            if mname == "sType":
                stype = m.get("values")
            members.append((mname, mtype, "[" in "".join(m.itertext())))
        structs[name] = {"stype": stype, "members": members, "extends": (t.get("structextends") or "").split(",")}
    for f in root.findall("feature"):
        if "vulkan" not in f.get("api", "vulkan").split(","):
            continue
        version = tuple(int(x) for x in f.get("number").split("."))
        for t in f.iter("type"):
            providers.setdefault(t.get("name"), set()).add(("core", version))
    for e in root.find("extensions"):
        for t in e.iter("type"):
            providers.setdefault(t.get("name"), set()).add(("ext", e.get("name")))
    return structs, aliases, providers


def canonical(name, aliases):
    while name in aliases:
        name = aliases[name]
    return name


def flags(names, prefix):
    out = []
    for n in names:
        assert n.startswith(prefix) and n.endswith("_BIT"), n
        out.append(n[len(prefix):-4])
    return out


def trim(gpuinfo, vkxml):
    _, aliases, _ = registry(vkxml)
    dev = json.load(open(gpuinfo))["capabilities"]["device"]
    label = list(json.load(open(gpuinfo))["profiles"].values())[0]["label"]
    props = dev["properties"]
    core = props["VkPhysicalDeviceProperties"]
    vk12 = props["VkPhysicalDeviceVulkan12Properties"]
    limits = {}
    for k, v in core["limits"].items():
        limits[k] = flags(v, "VK_SAMPLE_COUNT_") if isinstance(v, list) and v and isinstance(v[0], str) else v
    features = {}
    for k, v in dev["features"].items():
        features.setdefault(canonical(k, aliases), {}).update(v)
    formats = {}
    for k, v in sorted(dev["formats"].items()):
        p = v["VkFormatProperties"]
        formats[k[len("VK_FORMAT_"):]] = [flags(p[t], "VK_FORMAT_FEATURE_") for t in
                                          ("linearTilingFeatures", "optimalTilingFeatures", "bufferFeatures")]
    out = {
        "source": "vulkan.gpuinfo.org profile export: " + label,
        "apiVersion": core["apiVersion"],
        "vendorID": core["vendorID"],
        "deviceName": vk12["driverName"],
        "driverID": vk12["driverID"],
        "driverInfo": vk12["driverInfo"],
        "extensions": sorted(dev["extensions"]),
        "features": features,
        "limits": limits,
        "formats": formats,
    }
    json.dump(out, sys.stdout, indent=0, sort_keys=False)
    sys.stdout.write("\n")


def c_switch_stypes(serializer, function):
    body = re.search(r"static inline void %s\(.*?\n}\n" % function, serializer, re.S).group(0)
    return set("VK_STRUCTURE_TYPE_" + s for s in re.findall(r"case VK_STRUCTURE_TYPE_([A-Z0-9_]+):", body))


def c_function_cases(source, function):
    body = re.search(r"\n[a-zA-Z]+ %s\(.*?\n}\n" % function, source, re.S).group(0)
    return re.findall(r"case (VK_FORMAT_[A-Z0-9_]+):", body)


# What a stricter value of a limit is.
def limit_rule(name):
    if name.endswith("SampleCounts"):
        return "and"
    if name in ("pointSizeRange", "lineWidthRange", "viewportBoundsRange"):
        return "range"
    if name in ("minTexelOffset", "minTexelGatherOffset", "minInterpolationOffset"):
        return "max"
    if name.startswith("min") or name.endswith("Granularity") or name in ("nonCoherentAtomSize",) \
            or name.startswith("optimalBufferCopy"):
        return "max"
    if name in ("strictLines", "standardSampleLocations", "timestampComputeAndGraphics"):
        return "and"
    if name == "timestampPeriod":
        return None
    return "min"


def header(profile_path, vkxml, core_h, vortek):
    profile = json.load(open(profile_path))
    structs, aliases, providers = registry(vkxml)
    core_text = open(core_h).read()
    # sTypes the header defines outside "#ifdef VK_ENABLE_BETA_EXTENSIONS".
    usable, beta = set(), False
    for line in core_text.splitlines():
        m = re.match(r"\s+(VK_STRUCTURE_TYPE_\w+) = ", line)
        if m and not beta:
            usable.add(m.group(1))
        beta = line.strip() == "#ifdef VK_ENABLE_BETA_EXTENSIONS"
    serializer = open(vortek + "/include/vortek_serializer.h").read()
    helper = open(vortek + "/src/vulkan_helper.c").read()
    decoder = open(vortek + "/src/texture_decoder.c").read()
    inspector = open(vortek + "/src/shader_inspector.c").read()
    carried_feats = c_switch_stypes(serializer, "vt_unserialize_VkPhysicalDeviceFeatures2")
    carried_props = c_switch_stypes(serializer, "vt_unserialize_VkPhysicalDeviceProperties2")
    mali_exts = set(profile["extensions"])
    o = []
    w = o.append
    w("// Generated by vortek-device.py from %s" % profile_path.split("/")[-1])
    w("// %s" % profile["source"])
    w("#define MALI_DEVICE_NAME \"Vortek (%s)\"" % profile["deviceName"])
    w("#define MALI_VENDOR_ID 0x%x" % profile["vendorID"])
    w("#define MALI_DRIVER_ID %s" % profile["driverID"])
    w("#define MALI_DRIVER_INFO \"%s\"" % profile["driverInfo"])
    w("static const char *MALI_EXTENSIONS[] = {%s};" % ", ".join('"%s"' % e for e in sorted(mali_exts)))

    # Core features: Mali's own, then what Vortek's checkDeviceFeatures sets to VK_TRUE on top.
    core_feats = profile["features"]["VkPhysicalDeviceFeatures"]
    w("static const VkPhysicalDeviceFeatures MALI_CORE_FEATURES = {%s};" %
      ", ".join(".%s = VK_TRUE" % k for k, v in sorted(core_feats.items()) if v))
    fake_body = re.search(r"void checkDeviceFeatures\(.*?\n}\n", helper, re.S).group(0)
    fakes = re.findall(r"features->(\w+) = VK_TRUE;", fake_body)
    w("static void vortek_fake_core_features(VkPhysicalDeviceFeatures *f) {")
    for f in fakes:
        w("    f->%s = VK_TRUE;%s" % (f, "" if core_feats.get(f) else " // faked: Mali has no " + f))
    w("}")
    w("static const char *CORE_FEATURE_NAMES[] = {%s};" %
      ", ".join('"%s"' % m for m, _, _ in structs["VkPhysicalDeviceFeatures"]["members"]))

    def filled_by_mali(name):
        # A 1.0-1.3 core struct, or one of an extension Mali has.
        for kind, what in providers.get(name, ()):
            if kind == "core" and what <= (1, 3):
                return True
            if kind == "ext" and what in mali_exts:
                return True
        return False

    def emit_chain(function, extends, carried, profile_structs):
        w("static void %s(VkBaseOutStructure *s) {" % function)
        w("    switch ((int)s->sType) {")
        seen = set()
        for name, st in sorted(structs.items()):
            if extends not in st["extends"] or not st["stype"] or st["stype"] in seen:
                continue
            if st["stype"] not in usable or not re.search(r"\}\s*%s;" % name, core_text):
                continue  # not in the header zink is built against, or a beta struct
            seen.add(st["stype"])
            hdr = "memset((char *)s + sizeof(VkBaseOutStructure), 0, sizeof(%s) - sizeof(VkBaseOutStructure));" % name
            if st["stype"] not in carried:
                w("    case %s: // not carried by Vortek: stays as the caller set it" % st["stype"])
                w("        %s break;" % hdr)
            elif not filled_by_mali(name):
                w("    case %s: // Mali lacks the extension" % st["stype"])
                w("        %s break;" % hdr)
            elif profile_structs is not None and name in profile_structs:
                vals = profile_structs[name]
                w("    case %s: {" % st["stype"])
                w("        %s t = *(%s *)s; %s" % (name, name, hdr))
                for m, mt, arr in st["members"]:
                    if mt == "VkBool32" and vals.get(m):
                        w("        ((%s *)s)->%s = t.%s;" % (name, m, m))
                w("        break; }")
        w("    default: break;")
        w("    }")
        w("}")

    emit_chain("mali_features", "VkPhysicalDeviceFeatures2", carried_feats, profile["features"])
    emit_chain("mali_properties2", "VkPhysicalDeviceProperties2", carried_props, None)

    w("static void mali_limits(VkPhysicalDeviceLimits *l) {")
    w("    (void)l;")
    for k, v in profile["limits"].items():
        rule = limit_rule(k)
        if rule is None:
            continue
        if rule == "and" and isinstance(v, list):
            w("    l->%s &= %s;" % (k, " | ".join("VK_SAMPLE_COUNT_%s_BIT" % b for b in v) or "0"))
        elif rule == "and":
            w("    l->%s = l->%s && %d;" % (k, k, 1 if v else 0))
        elif rule == "range":
            w("    l->%s[0] = MAX2(l->%s[0], %r); l->%s[1] = MIN2(l->%s[1], %r);" % (k, k, v[0], k, k, v[1]))
        elif isinstance(v, list):
            for i, x in enumerate(v):
                w("    l->%s[%d] = %s(l->%s[%d], %r);" % (k, i, rule.upper() + "2", k, i, x))
        else:
            w("    l->%s = %s(l->%s, %r);" % (k, rule.upper() + "2", k, v))
    w("}")

    w("static const struct { VkFormat format; VkFormatFeatureFlags linear, optimal, buffer; } MALI_FORMATS[] = {")
    for f, (lin, opt, buf) in profile["formats"].items():
        fl = lambda bits: " | ".join("VK_FORMAT_FEATURE_%s_BIT" % b for b in bits) or "0"
        w("    {VK_FORMAT_%s, %s, %s, %s}," % (f, fl(lin), fl(opt), fl(buf)))
    w("};")
    w("static int vortek_compressed(VkFormat f) {")
    w("    switch ((int)f) {%s return 1; default: return 0; }" %
      "".join(" case %s:" % c for c in c_function_cases(decoder, "isCompressedFormat")))
    w("}")
    w("static int vortek_scaled(VkFormat f) {")
    w("    switch ((int)f) {%s return 1; default: return 0; }" %
      "".join(" case %s:" % c for c in c_function_cases(inspector, "isFormatScaled")))
    w("}")
    print("\n".join(o))


def lists(vortek):
    header = open(vortek + "/include/vulkan_helper.h").read()
    source = open(vortek + "/src/vulkan_helper.c").read()
    exposed = re.search(r'globalExposedDeviceExtensions\[\] = \{([^}]*)\}', header).group(1)
    implemented = re.search(r'globalImplementedDeviceExtensions\[\] = \{([^}]*)\}', header).group(1)
    disabled = re.search(r'"mesa zink"\) == 0\) \{\s*const char\* disabledDeviceExtensions\[\] = \{([^}]*)\}',
                         source).group(1)
    print("static const char *GLOBAL[] = {%s};" % exposed)
    print("static const char *IMPLEMENTED[] = {%s};" % implemented)
    print("static const char *DISABLED[] = {%s};" % disabled)


if __name__ == "__main__":
    if sys.argv[1] == "trim":
        trim(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "header":
        header(*sys.argv[2:6])
    elif sys.argv[1] == "lists":
        lists(sys.argv[2])
    else:
        sys.exit("usage: vortek-device.py trim|header|lists ...")
