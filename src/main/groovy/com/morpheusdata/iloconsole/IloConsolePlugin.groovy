/*
 * Copyright 2026 builtbyfood
 * Licensed under the Apache License, Version 2.0
 */
package com.morpheusdata.iloconsole

import com.morpheusdata.core.Plugin
import com.morpheusdata.iloconsole.services.AuditLogger
import com.morpheusdata.iloconsole.services.IloConfigStore
import com.morpheusdata.iloconsole.services.IloDetectionService
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HandlebarsRenderer
import groovy.json.JsonSlurper
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

    public static final String VERSION = "0.1.47"

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

    // ── v0.1.36 / v0.1.37: plugin-level settings ────────────────────────
    //
    // v0.1.36 used SELECT dropdowns with optionSource methods on the
    // Plugin class. Those rendered as "No options available" because
    // plugin-api 1.3.1's optionSource lookup expects the method on a
    // registered OptionSourceProvider, not on the Plugin class. Rather
    // than chase OptionSourceProvider registration (and its own
    // permission cascade — see the controller route history in
    // 0.1.16-0.1.23), v0.1.37 splits the settings into CHECKBOX inputs
    // that map cleanly to the same three behavior axes.
    //
    // Defaults all "on" preserve v0.1.36 default behavior — auto-login
    // SSO in a popup using the sessionkey URL path (with cookie fallback).
    //
    // The "Sessionkey only — no fallback" mode from v0.1.36 is dropped.
    // With Auto + cookie fallback as the default, forced-no-fallback adds
    // no operational value; if needed later it becomes a fourth checkbox.
    @Override
    List<OptionType> getSettings() {
        [
                // Console: Auto-login (SSO) -------------------------------------
                // Checked  = pre-authenticate iLO session and land user on
                //            the console without a second login (default).
                // Unchecked = open /irc.html without auth; user types creds
                //            at iLO's prompt (iLO logs the actual user
                //            identity rather than the shared service account).
                new OptionType(
                        name        : 'Console: Auto-login (SSO)',
                        code        : 'morpheus-argus-console-auto-login',
                        fieldName   : 'consoleAutoLogin',
                        fieldLabel  : 'Console: Auto-login (SSO)',
                        fieldContext: 'config',
                        displayOrder: 10,
                        required    : false,
                        inputType   : OptionType.InputType.CHECKBOX,
                        defaultValue: 'on',
                        helpText    : 'Checked (default): pre-authenticate the iLO session via Redfish so the user lands on the console without a second login. Unchecked: open /irc.html without auth; user types credentials at iLO\'s prompt (useful when iLO must log the actual user identity).'
                ),

                // Console: Open in popup window ---------------------------------
                // Checked  = sized popup window (1280x800), default.
                // Unchecked = standard browser tab.
                new OptionType(
                        name        : 'Console: Open in Popup Window',
                        code        : 'morpheus-argus-console-popup-window',
                        fieldName   : 'consolePopupWindow',
                        fieldLabel  : 'Console: Open in Popup Window',
                        fieldContext: 'config',
                        displayOrder: 20,
                        required    : false,
                        inputType   : OptionType.InputType.CHECKBOX,
                        defaultValue: 'on',
                        helpText    : 'Checked (default): console opens in a sized window (1280x800). Unchecked: console opens as a standard browser tab. Some Firefox configurations override window-size hints regardless of this setting.'
                ),

                // Console: Use sessionkey URL authentication --------------------
                // Checked  = mint Redfish session at tab render, pass token
                //            via ?sessionkey= URL (deterministic, no cookie
                //            commit race). Falls back to cookie path on mint
                //            failure.
                // Unchecked = force the v0.1.35 form-POST + cookie redirect
                //            path — escape hatch for any firmware quirk
                //            where sessionkey URLs do not work.
                new OptionType(
                        name        : 'Console: Use Sessionkey URL Authentication',
                        code        : 'morpheus-argus-console-sessionkey-auth',
                        fieldName   : 'consoleSessionkeyAuth',
                        fieldLabel  : 'Console: Use Sessionkey URL Authentication',
                        fieldContext: 'config',
                        displayOrder: 30,
                        required    : false,
                        inputType   : OptionType.InputType.CHECKBOX,
                        defaultValue: 'on',
                        helpText    : 'Checked (default): mint a Redfish session at tab render and pass the token via ?sessionkey= URL. Deterministic, no cookie race. Falls back to the cookie POST path automatically if the mint fails. Unchecked: force the v0.1.35 cookie POST path.'
                )
        ]
    }

    /**
     * Read the saved plugin settings with safe defaults. Returns a Map
     * with launchMode / windowMode / authMethod keys so the rendering
     * code stays unchanged from v0.1.36 — only the persistence layer
     * differs.
     *
     * v0.1.37 — settings are now booleans (CHECKBOX inputs). Mapping:
     *   consoleAutoLogin      on/true → launchMode = 'auto'  (else 'link')
     *   consolePopupWindow    on/true → windowMode = 'popup' (else 'tab')
     *   consoleSessionkeyAuth on/true → authMethod = 'auto'  (else 'cookie')
     *
     * Morpheus stores CHECKBOX values as strings ('on'/'off' or boolean
     * true/false depending on version). We accept both and treat anything
     * that is not unambiguously false-y as checked.
     *
     * v0.1.40 — Field reports of every setting reading as "on" regardless
     * of UI checkbox state even after v0.1.38's JsonSlurper fix. Two
     * possible causes:
     *
     *   (A) Morpheus drops unchecked CHECKBOX fields from the persisted
     *       JSON entirely — `cfg.consolePopupWindow` is missing rather
     *       than `false`. v0.1.38's `isOn(null) → true` then read it as
     *       on. Fix: when the config Map has ANY saved keys (i.e. user
     *       has visited Settings and saved at least once), treat a
     *       missing key as "unchecked" rather than "default-on". When
     *       the config Map is entirely empty, treat all missing keys as
     *       default-on (preserves fresh-install behavior).
     *
     *   (B) Morpheus auto-fills the OptionType's defaultValue when the
     *       form submits no value for an unchecked CHECKBOX, persisting
     *       `consolePopupWindow: "on"` regardless of UI state. This
     *       cannot be fixed from the plugin side — defaultValue is the
     *       only thing telling Morpheus what to render initially, and
     *       removing it makes new installs always unchecked. If the
     *       diagnostics row shows the unchecked field present in the
     *       JSON with value "on", we're in case (B) and need to file a
     *       Morpheus issue.
     *
     * Returns the parsed map AND a `_rawJson` / `_parsedKeys` annotation
     * the caller can surface in Diagnostics to distinguish (A) from (B)
     * from real-data inspection.
     */
    Map readArgusSettings() {
        Map cfg = [:]
        String rawJson = null
        Throwable parseError = null
        try {
            // v0.1.42 — call the correct accessor with the correct
            // blocking idiom. Two things needed to land here:
            //
            // 1. Method name. v0.1.38..v0.1.40 called the nonexistent
            //    `morpheus.getPluginConfig()`; v0.1.41 fixed that to
            //    `morpheus.getSettings(this)` per Morpheus's official
            //    plugin docs. The forensics diagnostic in v0.1.40 was
            //    what surfaced the actual MissingMethodException.
            //
            // 2. Reactive blocking idiom. `morpheus.getSettings(this)`
            //    returns an RxJava 3 Single<String>. v0.1.41 called
            //    `.toBlocking().firstOrDefault(null)` on it — that's
            //    the RxJava 2 idiom and threw MissingMethodException
            //    against SingleJust on Morpheus's RxJava 3 runtime.
            //    The RxJava 3 equivalent is `blockingGet()`, which
            //    returns the emitted value directly (or throws on
            //    error / null in strict-null mode).
            //
            // Defense: try blockingGet() first (RxJava 3 Single/Maybe),
            // fall back to subscribe() pattern (works for any
            // Single/Maybe/Observable). The subscribe path is what the
            // Morpheus docs example uses verbatim, so if blockingGet()
            // is missing for some reason, subscribe() will still work
            // against whatever reactive type Morpheus emits.
            def settingsObs = morpheus?.getSettings(this)
            if (settingsObs != null) {
                try {
                    // Most likely path: RxJava 3 Single.blockingGet().
                    rawJson = settingsObs.blockingGet() as String
                } catch (MissingMethodException blockingGetMissing) {
                    // Fall back to the doc-snippet subscribe pattern. Works
                    // for any reactive type because subscribe() is the
                    // universal terminal operator.
                    String collected = ''
                    Throwable subErr = null
                    settingsObs.subscribe(
                            { Object outData -> collected = (outData != null ? outData.toString() : '') } as groovy.lang.Closure,
                            { Throwable err   -> subErr = err } as groovy.lang.Closure
                    )
                    if (subErr) throw subErr
                    rawJson = collected
                }
            }
            if (rawJson && rawJson.trim() && rawJson.trim() != 'null') {
                def parsed = new JsonSlurper().parseText(rawJson)
                if (parsed instanceof Map) {
                    cfg = parsed as Map
                }
            }
        } catch (Throwable t) {
            parseError = t
            log.warn("argus: readArgusSettings parse failed: ${t.message}")
            cfg = [:]
        }

        // v0.1.40 — boolean used by the smart-null branch of isOn() below.
        // True when the user has saved settings at least once (cfg has any
        // keys). False on fresh install or if the parse failed.
        boolean configHasSavedKeys = !cfg.isEmpty()

        // Treat a setting as ENABLED unless it is unambiguously false-y.
        // Defaults when key is missing:
        //   - configHasSavedKeys == false → key missing means fresh install,
        //     fall back to defaultValue 'on' for first-render UX.
        //   - configHasSavedKeys == true → key missing means user explicitly
        //     unchecked it and Morpheus dropped the field, return false.
        Closure<Boolean> isOn = { Object v ->
            if (v == null) return !configHasSavedKeys
            if (v instanceof Boolean) return v as Boolean
            String s = v.toString().toLowerCase().trim()
            return !(s in ['false', 'off', 'no', '0', ''])
        }

        boolean autoLogin       = isOn(cfg?.consoleAutoLogin)
        boolean popupWindow     = isOn(cfg?.consolePopupWindow)
        boolean sessionkeyAuth  = isOn(cfg?.consoleSessionkeyAuth)

        return [
                launchMode: autoLogin       ? 'auto'  : 'link',
                windowMode: popupWindow     ? 'popup' : 'tab',
                authMethod: sessionkeyAuth  ? 'auto'  : 'cookie',
                // v0.1.40 — diagnostic-only annotations so the tab can
                // surface what we actually read from Morpheus. Strictly
                // metadata, never affects rendering decisions.
                _rawJson         : rawJson,
                _parsedKeys      : (cfg as Map).keySet().collect { it as String },
                _parseError      : (parseError?.message as String),
                _configHasKeys   : configHasSavedKeys,
                _rawAutoLogin    : (cfg?.consoleAutoLogin as String),
                _rawPopupWindow  : (cfg?.consolePopupWindow as String),
                _rawSessionkey   : (cfg?.consoleSessionkeyAuth as String)
        ]
    }

    @Override
    void onDestroy() {
        log.info("iLO Console plugin ${VERSION} destroyed")
    }
}
