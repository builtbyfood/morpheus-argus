package com.morpheusdata.iloconsole

import groovy.transform.CompileStatic

/**
 * View model for the iLO Console tab. Used in place of a Groovy Map because
 * Morpheus's Handlebars-Java renderer appears to only resolve JavaBean
 * property accessors, not Map keys, when binding template `{{object.X}}`
 * expressions. (0.1.5 diagnostic build confirmed: every {{object.diag.X}}
 * field rendered blank with a Map-backed model. Hypnos's working pattern
 * passes a JavaBean.)
 *
 * Every field has an explicit non-null default and an explicit getter
 * (Groovy generates them). Public fields too in case Handlebars uses field
 * reflection rather than bean introspection in some path.
 */
@CompileStatic
class IloTabModel {

    // Identity
    String serverId         = ''
    String serverName       = ''

    // Hardware
    String vendor           = ''
    String model            = ''
    String serial           = ''
    String infoSource       = ''   // "properties" | "direct" | ""

    // Config (label-driven)
    Boolean configured      = false
    String iloHost          = ''
    String credentialId     = ''
    Boolean verifySsl       = false
    String rawLabelsCsv     = ''   // comma-joined, ready to display

    // Status (Redfish read result)
    Boolean statusSuccess   = false
    String powerState       = ''
    String powerClass       = ''
    String health           = ''
    String healthClass      = ''
    String iloModel         = ''
    String iloFirmware      = ''
    String biosVersion      = ''
    String cpuCount         = ''
    String cpuModel         = ''
    String memoryGiB        = ''

    // Diagnostics (all stringified so Handlebars renders them cleanly)
    String diagResolution      = ''
    String diagParamNull       = ''
    String diagParamClass      = ''
    String diagDirectId        = ''
    String diagDirectName      = ''
    String diagDirectVendor    = ''
    String diagDirectModel     = ''
    String diagGetterIdThrew   = ''
    String diagPropsKeyCount   = ''
    String diagPropsHasId      = ''
    String diagPropsHasVendor  = ''
    String diagIdFromProps     = ''
    String diagVendorFromProps = ''
    String diagModelFromProps  = ''
    String diagPropsThrew      = ''

    // Pre-rendered HTML fallback — if even bean access still doesn't work
    // for some fields, this single field rendered via triple-stash will
    // always show.
    String preRenderedDiag     = ''

    String errorMsg            = ''
}
