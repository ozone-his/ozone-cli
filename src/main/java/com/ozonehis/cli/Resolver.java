package com.ozonehis.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a selection against the model and resolves the plan: the
 * ordered compose chain, the db-init list, the restore invocation and the
 * ad-hoc backup chain. Every rule fails loudly; nothing is guessed.
 */
final class Resolver {

    private final Model model;
    private final Selection selection;
    private final File composeRoot;
    private final File distroDir;

    Resolver(Model model, Selection selection, File composeRoot, File distroDir) {
        this.model = model;
        this.selection = selection;
        this.composeRoot = composeRoot;
        this.distroDir = distroDir;
    }

    Plan resolve() throws IOException {
        validateSelection();
        Plan plan = new Plan();

        plan.apps.addAll(selection.apps);
        plan.addons.putAll(selection.addons);

        // Integrations activate automatically; model order keeps the chain
        // deterministic.
        for (Model.Entry integration : model.integrations.values()) {
            if (selection.apps.containsAll(integration.requires)) {
                plan.integrations.add(integration.name);
            }
        }

        LinkedHashSet<String> engines = new LinkedHashSet<String>();
        for (String appName : selection.apps) {
            engines.addAll(model.apps.get(appName).db);
        }
        for (String integrationName : plan.integrations) {
            engines.addAll(model.integrations.get(integrationName).db);
        }
        for (Model.Entry addon : model.addons.values()) {
            if (enabledAddonsOnly().contains(addon.name)) {
                engines.addAll(addon.db);
            }
        }

        List<String> chain = plan.chain;
        chain.addAll(model.baseCompose);
        for (String engine : model.databases.keySet()) {
            if (engines.contains(engine)) {
                chain.add(model.databases.get(engine));
            }
        }
        for (String appName : selection.apps) {
            chain.addAll(model.apps.get(appName).compose);
        }
        for (String integrationName : plan.integrations) {
            chain.addAll(model.integrations.get(integrationName).compose);
        }
        for (Model.Entry addon : model.addons.values()) {
            if (enabledAddonsOnly().contains(addon.name)) {
                chain.addAll(addon.compose);
            }
        }
        if (plan.ssoEnabled()) {
            for (String appName : selection.apps) {
                chain.addAll(model.apps.get(appName).sso);
            }
            for (String integrationName : plan.integrations) {
                chain.addAll(model.integrations.get(integrationName).sso);
            }
            for (Model.Entry addon : model.addons.values()) {
                if (enabledAddonsOnly().contains(addon.name)) {
                    chain.addAll(addon.sso);
                }
            }
        }
        dedupe(chain);
        checkFilesExist(chain);

        for (String appName : selection.apps) {
            addAll(plan.dbInit, model.apps.get(appName).dbInit);
        }
        for (String integrationName : plan.integrations) {
            addAll(plan.dbInit, model.integrations.get(integrationName).dbInit);
        }
        for (Model.Entry addon : model.addons.values()) {
            if (enabledAddonsOnly().contains(addon.name)) {
                addAll(plan.dbInit, addon.dbInit);
            }
        }
        checkDbInitDirs(plan.dbInit);

        // The pre-computed restore invocation: the restore operation's files,
        // plus every selected app's restore files and gate profiles (the
        // keycloak app carries the SSO restore files).
        Model.Entry restoreOp = model.operations.get("restore");
        if (restoreOp != null) {
            plan.restoreCompose.addAll(restoreOp.compose);
        }
        for (String appName : selection.apps) {
            Model.Entry app = model.apps.get(appName);
            addAll(plan.restoreCompose, app.restoreCompose);
            addAll(plan.restoreProfiles, app.restoreProfiles);
        }
        dedupe(plan.restoreCompose);
        checkFilesExist(plan.restoreCompose);

        // The pre-computed ad-hoc chain for the `backup` command:
        // base + the backup operation's databases + its files + its sso
        // override when SSO is enabled in the selection.
        Model.Entry backupOp = model.operations.get("backup");
        if (backupOp != null) {
            plan.backupChain.addAll(model.baseCompose);
            for (String engine : model.databases.keySet()) {
                if (backupOp.db.contains(engine)) {
                    plan.backupChain.add(model.databases.get(engine));
                }
            }
            plan.backupChain.addAll(backupOp.compose);
            if (plan.ssoEnabled()) {
                plan.backupChain.addAll(backupOp.sso);
            }
            dedupe(plan.backupChain);
            checkFilesExist(plan.backupChain);
        }

        return plan;
    }

    private Set<String> enabledAddonsOnly() {
        Set<String> enabled = new LinkedHashSet<String>();
        for (Model.Entry addon : model.addons.values()) {
            if (selection.addonEnabled(addon.name)) {
                enabled.add(addon.name);
            }
        }
        return enabled;
    }

    private void validateSelection() throws IOException {
        for (String appName : selection.apps) {
            if (model.integrations.containsKey(appName)) {
                throw new IOException("Selection error: '" + appName + "' is an integration; integrations "
                        + "activate automatically when their required apps are selected and are never listed "
                        + "in the selection");
            }
            if (!model.apps.containsKey(appName)) {
                throw new IOException("Selection error: unknown app '" + appName + "'. Valid apps: "
                        + model.apps.keySet());
            }
        }
        for (String addonName : selection.addons.keySet()) {
            if (!model.addons.containsKey(addonName)) {
                throw new IOException("Selection error: unknown addon '" + addonName + "'. Valid addons: "
                        + model.addons.keySet());
            }
        }
        for (String[] pair : model.incompatibilities) {
            if (selection.apps.contains(pair[0]) && selection.apps.contains(pair[1])) {
                throw new IOException("Selection error: incompatible apps '" + pair[0] + "' and '" + pair[1]
                        + "' cannot be selected together");
            }
        }
    }

    private void checkFilesExist(List<String> files) throws IOException {
        for (String file : files) {
            File resolved = ComposePaths.resolve(composeRoot, distroDir, file,
                    model.composeRootFiles, model.distroDirFiles);
            if (!resolved.isFile()) {
                throw new IOException("Model error: declared compose file does not exist: " + file
                        + " (expected at " + resolved + ")");
            }
        }
    }

    private void checkDbInitDirs(List<String> dirs) throws IOException {
        for (String dir : dirs) {
            boolean inMysql = new File(distroDir, "data/mysql/" + dir).isDirectory();
            boolean inPostgresql = new File(distroDir, "data/postgresql/" + dir).isDirectory();
            if (!inMysql && !inPostgresql) {
                throw new IOException("Selection error: db-init directory '" + dir
                        + "' not found in the distro's data tree (data/mysql/ or data/postgresql/)");
            }
        }
    }

    private static void addAll(List<String> target, List<String> source) {
        for (String item : source) {
            if (!target.contains(item)) {
                target.add(item);
            }
        }
    }

    private static void dedupe(List<String> list) {
        List<String> seen = new ArrayList<String>();
        for (String item : list) {
            if (!seen.contains(item)) {
                seen.add(item);
            }
        }
        list.clear();
        list.addAll(seen);
    }
}
