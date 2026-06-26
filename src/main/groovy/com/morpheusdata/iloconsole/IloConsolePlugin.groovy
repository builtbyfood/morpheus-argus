/*
 * Copyright 2026 builtbyfood
 * Licensed under the Apache License, Version 2.0
 */
package com.morpheusdata.iloconsole

import com.morpheusdata.core.Plugin
import com.morpheusdata.iloconsole.services.AuditLogger
import com.morpheusdata.iloconsole.services.IloConfigStore
import com.morpheusdata.iloconsole.services.IloDetectionService
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * 0.1.19: matches the canonical pattern from builtbyfood/morpheus-vm-folders,
 * a plugin known-working on Morpheus 8.1.x and 8.8.x with plugin-api 1.3.0.
 *
 * Critical change: explicitly set this.renderer = new HandlebarsRenderer()
 * AND override hasCustomRenderer() to return true. Without these, Morpheus's
 * default rendering path bypasses the provider's renderTemplate() method
 * entirely — which is exactly what we observed in 0.1.4-0.1.6 (the tab
 * rendered with an empty model and no log lines fired from our provider).
 */
@Slf4j
class IloConsolePlugin extends Plugin {

    public static final String VERSION = "0.1.35"

    IloConfigStore configStore
    IloDetectionService detectionService
    AuditLogger auditLogger

    @Override String getCode() { return 'ilo-console-plugin' }

    @Override
    void initialize() {
        // CRITICAL: explicit renderer setup. This is what was missing in
        // 0.1.0 through 0.1.6 and what was causing renderTemplate() to be
        // bypassed entirely.
        this.renderer = new HandlebarsRenderer()

        this.setName("iLO")
        this.setDescription("HPE iLO console and Redfish status panel for ProLiant hosts. v${VERSION}.")
        this.setAuthor("builtbyfood")
        this.setSourceCodeLocationUrl("https://github.com/builtbyfood/morpheus-ilo-console")
        this.setIssueTrackerUrl("https://github.com/builtbyfood/morpheus-ilo-console/issues")

        this.configStore = new IloConfigStore()
        this.detectionService = new IloDetectionService()
        this.auditLogger = new AuditLogger()

        // v0.1.23: the IloConsoleController is intentionally NOT registered,
        // and no plugin permissions are declared.
        //
        // 0.1.16-0.1.22 chased a controller route at /iloConsole/launch with
        // every permission-declaration pattern we could find (built-in role
        // codes, plugin-declared codes, single Permission vs List, plugin-
        // level setPermissions vs controller-level getPermissions). All
        // returned 403. We never got concrete signal from the appliance about
        // which layer was failing (no entries in morpheus-ui/current for the
        // 403, no obvious "permission not granted" line). Rather than keep
        // guessing, 0.1.23 takes the controller out of the picture entirely
        // and does the auto-login dance from the tab itself, using a plain
        // HTML form POST to iLO — no controller route, no inline JS, no CSP
        // fight.
        //
        // The IloConsoleController source is kept in the tree as scaffolding
        // for future Redfish-backed actions (power on/off/reset, BIOS reads,
        // etc.) but not currently wired up.
        // this.setPermissions([...])  ← removed: nothing references it now
        // this.controllers.add(new IloConsoleController(this, morpheus))  ← removed

        registerProvider(new IloConsoleServerTabProvider(this, morpheus))

        log.info("iLO Console plugin ${VERSION} initialized (custom HandlebarsRenderer, no controller routes)")
    }

    /**
     * Tell Morpheus this plugin uses its own renderer (set in initialize()).
     * Required by the framework — without this returning true, Morpheus
     * uses an internal default rendering path that doesn't invoke our
     * provider methods.
     *
     * Note: no @Override — Plugin declares hasCustomRenderer as a property
     * field, not a method, so @Override fails strict checking. We shadow
     * the property accessor via Groovy's dynamic dispatch (same pattern
     * as VmFoldersPlugin.groovy).
     */
    Boolean hasCustomRenderer() { return true }

    @Override
    void onDestroy() {
        log.info("iLO Console plugin ${VERSION} destroyed")
    }
}
