package com.ozonehis.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates the .env: merges the compose project's vars.yml, the distro's
 * vars.yml (overrides), the model's `deployment:` section and the runtime
 * values, then leaves ${VAR} references between values raw.
 *
 * Merge order (later wins): code vars.yml -> distro vars.yml -> deployment
 * section -> runtime values.
 *
 * The `sso:` sub-blocks are merged only when SSO is enabled, per the
 * vars.yml header contract. Owner blocks under `apps:` / `integrations:` /
 * `addons:` are merged only for the owners that run (the plan's seed owners:
 * selected apps, activated integrations, enabled addons) plus the transitive
 * closure of `vars-from` edges declared in the vars files themselves - at any
 * distro level, since the distro's resolved vars.yml carries the merged chain.
 * Every other section (databases, operations, restic, ...) and any
 * section-level common/paths is infrastructure and always merged.
 */
final class EnvGen {

    private final boolean ssoEnabled;
    /** seed owners to merge, section-qualified, e.g. "apps.openmrs" (see Plan.owners) */
    private final Set<String> owners;

    /**
     * `paths:` values of the distro's vars.yml are distro-relative and are
     * emitted as ${DISTRO_PATH}/... references (see mergeSubBlock); nothing
     * is resolved against the distro dir at generation time, keeping the
     * .env portable across machines.
     */
    EnvGen(boolean ssoEnabled, Set<String> owners) {
        this.ssoEnabled = ssoEnabled;
        this.owners = owners;
    }

    Map<String, String> generate(File codeVars, File distroVars, Map<String, Object> deployment,
            Map<String, String> runtime) throws IOException {

        Map<String, String> env = new LinkedHashMap<String, String>();

        // Runtime roots (HOST_NAME, SERVER_SCHEME, ...) must come FIRST:
        // compose interpolates env-file values in file order, single pass,
        // so every ${HOST_NAME} reference below them resolves. They are
        // put again at the end so their values also win the merge.
        env.putAll(runtime);
        Map<String, Object> code = Yamls.load(codeVars);
        Map<String, Object> distro = distroVars.isFile()
                ? Yamls.load(distroVars) : new LinkedHashMap<String, Object>();
        Set<String> included = includedOwners(code, distro);
        mergeOwnerBlocks(env, code, false, included);
        mergeOwnerBlocks(env, distro, true, included);
        mergeDeploymentVars(env, deployment);
        env.putAll(runtime);
        // ${VAR} references between values are left as-is on purpose: compose
        // resolves them at runtime, so a deployment can override HOST_NAME
        // (edit the line or export it in the shell) without regenerating,
        // and every derived hostname follows. Resolution for display happens
        // in substitute(), on a copy.
        return env;
    }

    /**
     * The owners whose blocks are merged: the plan's seed owners plus the
     * transitive closure of the `vars-from` edges declared in the vars files
     * (code-level and distro-level combined). An unknown target or a cycle
     * fails loudly - no guessing.
     */
    private Set<String> includedOwners(Map<String, Object> code, Map<String, Object> distro) throws IOException {
        Set<String> included = new LinkedHashSet<String>();
        Set<String> visiting = new LinkedHashSet<String>();
        for (String seed : owners) {
            addOwner(code, distro, included, visiting, seed);
        }
        return included;
    }

    private void addOwner(Map<String, Object> code, Map<String, Object> distro, Set<String> included,
            Set<String> visiting, String owner) throws IOException {
        if (!visiting.add(owner)) {
            throw new IOException("Vars error: 'vars-from' cycle detected at '" + owner + "'");
        }
        if (included.add(owner)) {
            for (String from : varsFromOf(code, distro, owner)) {
                if (blockIn(code, from) == null && blockIn(distro, from) == null) {
                    throw new IOException("Vars error: 'vars-from' target '" + from + "' (declared by '"
                            + owner + "') has no block in any vars source");
                }
                addOwner(code, distro, included, visiting, from);
            }
        }
        visiting.remove(owner);
    }

    /** The `vars-from` edges of a block, from both vars sources (lists append across levels). */
    private static List<String> varsFromOf(Map<String, Object> code, Map<String, Object> distro, String owner) {
        List<String> edges = new ArrayList<String>();
        collectVarsFrom(code, owner, edges);
        collectVarsFrom(distro, owner, edges);
        return edges;
    }

    private static void collectVarsFrom(Map<String, Object> source, String owner, List<String> edges) {
        Map<String, Object> block = blockIn(source, owner);
        if (block != null) {
            edges.addAll(Yamls.stringList(block.get("vars-from")));
        }
    }

    /** The block named by a section-qualified owner ("apps.openmrs"), or null. */
    private static Map<String, Object> blockIn(Map<String, Object> source, String owner) {
        int dot = owner.indexOf('.');
        if (dot < 0) {
            return null;
        }
        Object block = Yamls.map(source.get(owner.substring(0, dot))).get(owner.substring(dot + 1));
        return block instanceof Map ? Yamls.map(block) : null;
    }

    /** Resolves ${VAR} references between values, e.g. hostnames referencing ${HOST_NAME}. Display only. */
    static Map<String, String> substitute(Map<String, String> env) {
        Map<String, String> resolved = new LinkedHashMap<String, String>(env);
        substituteInPlace(resolved);
        return resolved;
    }

    private static void substituteInPlace(Map<String, String> env) {
        for (int pass = 0; pass < 10; pass++) {
            boolean changed = false;
            for (Map.Entry<String, String> var : env.entrySet()) {
                String value = var.getValue();
                if (value == null || !value.contains("${")) {
                    continue;
                }
                String resolved = resolve(value, env);
                if (!resolved.equals(value)) {
                    var.setValue(resolved);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /**
     * vars.yml top-level sections: apps / integrations / addons / databases /
     * operations / restic / ... Each section holds named blocks (e.g.
     * apps.openmrs), each block with common / paths (always merged) and sso
     * (merged only when SSO is enabled). A section may also carry common /
     * paths directly, e.g. the shared databases paths.
     *
     * Blocks of the selectable owner sections (apps / integrations / addons)
     * are merged only when `included` contains the owner; all other sections
     * are infrastructure and always merged.
     */
    private void mergeOwnerBlocks(Map<String, String> env, Map<String, Object> vars, boolean override,
            Set<String> included) {
        for (Map.Entry<String, Object> section : vars.entrySet()) {
            if (!(section.getValue() instanceof Map)) {
                continue;
            }
            String sectionName = section.getKey();
            Map<String, Object> blocks = Yamls.map(section.getValue());
            mergeSubBlock(env, blocks.get("common"), override, false);
            mergeSubBlock(env, blocks.get("paths"), override, true);

            boolean filtered = isSelectableSection(sectionName);
            for (Map.Entry<String, Object> block : blocks.entrySet()) {
                if (!(block.getValue() instanceof Map)) {
                    continue;
                }
                if (filtered && !included.contains(sectionName + "." + block.getKey())) {
                    continue; // owner not running: its block is not merged
                }
                mergeBlock(env, Yamls.map(block.getValue()), override, sectionName + "." + block.getKey());
            }
        }
    }

    /** The sections the selection filters; every other section is always merged. */
    private static boolean isSelectableSection(String section) {
        return "apps".equals(section) || "integrations".equals(section) || "addons".equals(section);
    }

    private void mergeBlock(Map<String, String> env, Map<String, Object> fields, boolean override, String path) {
        mergeSubBlock(env, fields.get("common"), override, false);
        mergeSubBlock(env, fields.get("paths"), override, true);
        if (ssoEnabled) {
            mergeSubBlock(env, fields.get("sso"), override, false);
        }
    }

    private void mergeSubBlock(Map<String, String> env, Object subBlock, boolean override, boolean resolvePaths) {
        for (Map.Entry<String, Object> var : Yamls.map(subBlock).entrySet()) {
            String value = var.getValue() == null ? "" : String.valueOf(var.getValue());
            if (resolvePaths && !value.isEmpty() && !value.startsWith("/") && !value.startsWith("$")) {
                // Distro-relative paths stay as ${DISTRO_PATH} references: the
                // .env must be portable across machines, and DISTRO_PATH is a
                // runtime root written at the top of the file (and overridable
                // by the deployment through the shell), exactly like HOST_NAME.
                value = "${DISTRO_PATH}/" + value;
            }
            if (override || !env.containsKey(var.getKey())) {
                env.put(var.getKey(), value);
            }
        }
    }

    /**
     * deployment section: services.&lt;name&gt;.{hostnames,datadirs,common}
     * and the extra block - all flattened, always overriding.
     */
    private void mergeDeploymentVars(Map<String, String> env, Map<String, Object> deploymentVars) {
        for (Map.Entry<String, Object> service : Yamls.map(deploymentVars.get("services")).entrySet()) {
            Map<String, Object> block = Yamls.map(service.getValue());
            mergeSubBlock(env, block.get("hostnames"), true, false);
            mergeSubBlock(env, block.get("datadirs"), true, false);
            mergeSubBlock(env, block.get("common"), true, false);
        }
        mergeSubBlock(env, deploymentVars.get("extra"), true, false);
    }

    private static String resolve(String value, Map<String, String> env) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                out.append(value, i, value.length());
                break;
            }
            int end = value.indexOf('}', start);
            if (end < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, start);
            String name = value.substring(start + 2, end);
            String replacement = env.get(name);
            out.append(replacement == null ? "${" + name + "}" : replacement);
            i = end + 1;
        }
        return out.toString();
    }

    static List<String> toFileLines(Map<String, String> env) {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, String> var : env.entrySet()) {
            lines.add(var.getKey() + "=" + (var.getValue() == null ? "" : var.getValue()));
        }
        return lines;
    }
}
