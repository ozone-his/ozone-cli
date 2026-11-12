package com.ozonehis.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The distro's selection (the 'selection:' section of the distro's ozone.yml):
 * what WILL run - the selected apps and the enabled addons. Integrations are
 * never listed here; they activate automatically when their required apps are
 * all selected.
 */
final class Selection {

    final List<String> apps = new ArrayList<String>();
    final Map<String, Boolean> addons = new LinkedHashMap<String, Boolean>();

    static Selection load(File ozoneYml) throws IOException {
        Map<String, Object> root = Yamls.load(ozoneYml);
        if (!(root.get("selection") instanceof Map)) {
            throw new IOException("No 'selection:' section in " + ozoneYml);
        }
        Map<String, Object> section = Yamls.map(root.get("selection"));
        Selection selection = new Selection();
        selection.apps.addAll(Yamls.stringList(section.get("apps")));
        for (Map.Entry<String, Object> e : Yamls.map(section.get("addons")).entrySet()) {
            boolean enabled = "true".equalsIgnoreCase(String.valueOf(e.getValue()));
            selection.addons.put(e.getKey(), enabled);
        }
        return selection;
    }

    boolean addonEnabled(String addon) {
        Boolean value = addons.get(addon);
        return value != null && value;
    }
}
