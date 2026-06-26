package com.morpheusdata.iloconsole.services

import com.morpheusdata.core.util.HttpApiClient
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/**
 * Minimal HPE iLO Redfish client. Mirrors the auth pattern from the SiLO
 * Home Assistant integration:
 *
 *   1. POST /redfish/v1/SessionService/Sessions { UserName, Password }
 *      -> response headers carry X-Auth-Token and Location
 *   2. All subsequent reads send X-Auth-Token: &lt;token&gt;
 *   3. DELETE &lt;Location&gt; with X-Auth-Token to release the session
 *
 * Per the SiLO design notes: per-request Basic auth burns iLO session slots
 * and eventually triggers NoValidSession errors. Session-token uses one slot
 * per polling cycle.
 *
 * Self-signed certs: iLOs ship with self-signed certs by default. Verification
 * is controlled by the verifySsl flag.
 */
@Slf4j
class RedfishClient {

    final String iloHost                 // bare IP or hostname, no scheme
    final boolean verifySsl
    final HttpApiClient http

    private String authToken              // X-Auth-Token from last login
    private String sessionLocation        // URI to DELETE on logout

    RedfishClient(String iloHost, boolean verifySsl) {
        this.iloHost = iloHost
        this.verifySsl = verifySsl
        this.http = new HttpApiClient()
    }

    private String baseUrl() { return "https://${iloHost}" }

    /**
     * POST to SessionService/Sessions and capture X-Auth-Token + Location.
     * Throws on non-success.
     */
    void login(String username, String password) {
        HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                headers: ['Content-Type': 'application/json', 'Accept': 'application/json'],
                body: JsonOutput.toJson([UserName: username, Password: password]),
                ignoreSSL: !verifySsl,
                contentType: 'application/json'
        )
        def resp = http.callJsonApi(baseUrl(), '/redfish/v1/SessionService/Sessions', opts, 'POST')
        if (resp == null || !resp.success) {
            throw new IllegalStateException("iLO ${iloHost}: session login failed (${resp?.errorCode})")
        }
        Map<String, String> headers = normalizeHeaders(resp.headers)
        this.authToken = headers['x-auth-token']
        this.sessionLocation = headers['location']
        if (!this.authToken) {
            throw new IllegalStateException("iLO ${iloHost}: 201 but no X-Auth-Token header")
        }
        log.debug("iLO ${iloHost}: session established")
    }

    void logout() {
        if (!authToken || !sessionLocation) return
        try {
            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['X-Auth-Token': authToken, 'Accept': 'application/json'],
                    ignoreSSL: !verifySsl
            )
            http.callJsonApi(baseUrl(), sessionLocation, opts, 'DELETE')
        } catch (Throwable t) {
            log.warn("iLO ${iloHost}: logout failed (server-side timeout will reap it): ${t.message}")
        } finally {
            this.authToken = null
            this.sessionLocation = null
        }
    }

    /** GET a Redfish path. Returns parsed JSON Map/List, or null on error. */
    Object getJson(String path) {
        if (!authToken) throw new IllegalStateException("not logged in")
        HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                headers: ['X-Auth-Token': authToken, 'Accept': 'application/json'],
                ignoreSSL: !verifySsl
        )
        def resp = http.callJsonApi(baseUrl(), path, opts, 'GET')
        if (resp == null || !resp.success) {
            log.debug("iLO ${iloHost} GET ${path} failed: ${resp?.errorCode}")
            return null
        }
        return resp.data
    }

    /**
     * Aggregator. Pulls the standard tab status set in a single logged-in
     * session and returns a Map ready to bind to the template. Always logs
     * out before returning.
     */
    Map collectStatus(String username, String password) {
        Map result = [success: false, error: null]
        try {
            login(username, password)
            def root = getJson('/redfish/v1/') ?: [:]
            def system = getJson('/redfish/v1/Systems/1') ?: [:]
            def manager = getJson('/redfish/v1/Managers/1') ?: [:]
            def thermal = getJson('/redfish/v1/Chassis/1/Thermal') ?: [:]

            result.success = true
            result.redfishVersion = root.RedfishVersion
            result.iloModel = manager.Model ?: 'iLO'
            // HPE iLO firmware string often begins with the model name (e.g.
            // "iLO 6 v1.74") — strip that to avoid duplicating it in the UI.
            String rawFw = manager.FirmwareVersion as String
            if (rawFw && result.iloModel && rawFw.toLowerCase().startsWith((result.iloModel as String).toLowerCase() + ' ')) {
                result.iloFirmware = rawFw.substring(((result.iloModel as String) + ' ').length()).trim()
            } else {
                result.iloFirmware = rawFw
            }
            // v0.1.29 — more from the /Managers/1 response
            result.iloDateTime = manager.DateTime                          // "2026-06-26T13:42:18Z"
            result.iloDateTimeOffset = manager.DateTimeLocalOffset         // "+00:00"
            // License is HPE-OEM. The exact path varies by iLO version; the
            // common shape is Oem.Hpe.License.{LicenseType, LicenseString}.
            def hpeOem = manager.Oem?.Hpe ?: [:]
            result.iloLicenseType = hpeOem.License?.LicenseType            // "iLO Advanced" / "iLO Standard" / etc.
            result.iloLicenseKey  = hpeOem.License?.LicenseKey
            result.iloLicenseExpire = hpeOem.License?.LicenseExpire
            result.powerState = system.PowerState
            result.health = system.Status?.HealthRollup ?: system.Status?.Health
            result.biosVersion = system.BiosVersion
            result.cpuCount = system.ProcessorSummary?.Count
            result.cpuModel = system.ProcessorSummary?.Model
            result.memoryGiB = system.MemorySummary?.TotalSystemMemoryGiB
            result.memoryHealth = system.MemorySummary?.Status?.HealthRollup
            result.serial = system.SerialNumber
            result.sku = system.SKU
            result.hostName = system.HostName

            // v0.1.29 — more data from the /Systems/1 response we already have
            result.bootProgress = system.BootProgress?.LastState           // "OSRunning" / "SystemHardwareInitializationComplete" / etc.
            result.bootSource   = system.Boot?.BootSourceOverrideTarget    // "None" / "Pxe" / "Hdd" / etc.
            result.bootSourceEnabled = system.Boot?.BootSourceOverrideEnabled
            // TrustedModules is a small array (usually 0-1 entries) per Redfish spec
            def tpms = (system.TrustedModules ?: [])
            def tpm = tpms.find { it.ModuleType }
            if (tpm) {
                result.tpmModuleType = tpm.ModuleType                       // "TPM2_0", "TPM1_2"
                result.tpmState = tpm.Status?.State                         // "Enabled" / "Disabled"
                result.tpmInterface = tpm.InterfaceType
            }

            // v0.1.32 — HPE OEM extensions on /Systems/1. v0.1.33: guarded
            // in its own try-catch — previously a malformed OEM payload could
            // throw and skip every block below, including all Chassis/Power/
            // Network/Drives reads.
            try {
                def hpeSystemOem = system.Oem?.Hpe ?: [:]
                // DeviceDiscoveryComplete tells us whether iLO has finished
                // probing all attached hardware. If this isn't
                // "vMainDeviceDiscoveryComplete", drives/storage may legitimately
                // not appear yet — surface it so the user knows to wait or reboot.
                result.deviceDiscovery = hpeSystemOem.DeviceDiscoveryComplete?.DeviceDiscovery
                result.amsStatus = hpeSystemOem.AggregateHealthStatus?.AgentlessManagementService?.Status?.Health
                // AggregateHealthStatus breaks the health rollup down by subsystem,
                // so when overall Health isn't "OK" we can show *which* subsystem
                // is reporting it. Each value is OK / Warning / Critical / null.
                def ahs = hpeSystemOem.AggregateHealthStatus ?: [:]
                result.healthBreakdown = [
                        biosOrHardware    : ahs.BiosOrHardwareHealth?.Status?.Health,
                        fans              : ahs.Fans?.Status?.Health,
                        fanRedundancy     : ahs.FanRedundancy?.Status?.Health,
                        memory            : ahs.Memory?.Status?.Health,
                        network           : ahs.Network?.Status?.Health,
                        powerSupplies     : ahs.PowerSupplies?.Status?.Health,
                        psuRedundancy     : ahs.PowerSupplyRedundancy?.Status?.Health,
                        processors        : ahs.Processors?.Status?.Health,
                        storage           : ahs.Storage?.Status?.Health,
                        temperatures      : ahs.Temperatures?.Status?.Health,
                        smartStorageBatt  : ahs.SmartStorageBattery?.Status?.Health
                ].findAll { k, v -> v != null }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} OEM AggregateHealthStatus read failed: ${t.message}")
            }

            // Thermal reads — temperatures + fans. v0.1.33: wrapped in
            // try-catch since `.sort { it.name as String }` previously threw
            // NPE on sensors with null Name, escaping past all subsequent
            // Chassis/Power/Network/Drives reads and emptying most of the tab.
            // Sort closures now coerce null names to empty string so the
            // comparator is stable across sparse iLO responses.
            try {
                def temps = (thermal.Temperatures ?: []).findAll {
                    it.Status?.State == 'Enabled' && it.ReadingCelsius != null
                }
                // v0.1.27 — keep the top-4 hottest for the Power & Cooling summary card
                result.temperatures = temps.collect {
                    [name: it.Name, c: it.ReadingCelsius, upperCrit: it.UpperThresholdCritical, upperWarn: it.UpperThresholdNonCritical]
                }.sort { -((it.c ?: 0) as Integer) }.take(4)
                // v0.1.32 — keep the full list for the "All Temperatures" collapsible
                result.allTemperatures = temps.collect {
                    [
                            name      : it.Name,
                            c         : it.ReadingCelsius,
                            upperWarn : it.UpperThresholdNonCritical,
                            upperCrit : it.UpperThresholdCritical,
                            physicalCtx: it.PhysicalContext,
                            health    : it.Status?.Health
                    ]
                }.sort { ((it.name ?: '') as String) }

                def fans = (thermal.Fans ?: []).findAll {
                    it.Status?.State == 'Enabled' && it.Reading != null
                }
                result.fans = fans.collect { [name: it.Name, pct: it.Reading, health: it.Status?.Health] }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Thermal read/parse failed: ${t.message}")
                result.temperatures = result.temperatures ?: []
                result.allTemperatures = result.allTemperatures ?: []
                result.fans = result.fans ?: []
            }

            // v0.1.27 — additional data groups. Each block is defensive so a
            // single missing endpoint doesn't blank the whole tab.

            // Power & PSU
            try {
                def power = getJson('/redfish/v1/Chassis/1/Power') ?: [:]
                def pc = (power.PowerControl ?: [])[0]
                if (pc != null) {
                    // v0.1.28: iLO 6 on entry-level ProLiant (e.g. MicroServer Gen11)
                    // sometimes reports PowerConsumedWatts: 0 even when the server is
                    // running — the firmware doesn't update the instant reading
                    // frequently. PowerMetrics.AverageConsumedWatts is usually
                    // accurate. Prefer the average when current reads 0.
                    Integer current = pc.PowerConsumedWatts as Integer
                    Integer average = pc.PowerMetrics?.AverageConsumedWatts as Integer
                    result.powerConsumedWatts = (current != null && current > 0) ? current : (average ?: current)
                    result.powerAvgWatts = average
                    result.powerCapacityWatts = pc.PowerCapacityWatts
                    result.powerLimitWatts = pc.PowerLimit?.LimitInWatts
                    // v0.1.32 — extended metrics: min/max over the iLO sampling
                    // interval, useful for capacity planning visibility.
                    result.powerMinWatts = pc.PowerMetrics?.MinConsumedWatts
                    result.powerMaxWatts = pc.PowerMetrics?.MaxConsumedWatts
                    result.powerIntervalMin = pc.PowerMetrics?.IntervalInMin
                }
                def psus = (power.PowerSupplies ?: []).findAll {
                    it.Status?.State == 'Enabled' || it.PowerSupplyType
                }
                result.psuCount = psus.size()
                result.psuHealthyCount = psus.count { it.Status?.Health == 'OK' }
                result.psuRedundancy = (power.Redundancy ?: [])[0]?.Status?.Health
                result.psuModel = psus[0]?.Model
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Power read failed: ${t.message}")
            }

            // Chassis: asset tag, SKU, indicator LED
            try {
                def chassis = getJson('/redfish/v1/Chassis/1') ?: [:]
                result.assetTag = chassis.AssetTag
                result.chassisSku = chassis.SKU
                result.indicatorLed = chassis.IndicatorLED
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Chassis read failed: ${t.message}")
            }

            // Host network — first enabled EthernetInterface with a useful field
            try {
                def ethColl = getJson('/redfish/v1/Systems/1/EthernetInterfaces') ?: [:]
                def members = ethColl.Members ?: []
                for (def member : members) {
                    String memberPath = member?.'@odata.id' as String
                    if (!memberPath) continue
                    def eth = getJson(memberPath)
                    if (eth == null) continue
                    String mac = eth.MACAddress
                    def ipv4Addresses = eth.IPv4Addresses ?: []
                    String ip = ipv4Addresses.find { it.Address }?.Address
                    if (ip || (mac && eth.LinkStatus == 'LinkUp')) {
                        result.hostMac = mac
                        result.hostIp = ip
                        result.hostLink = eth.LinkStatus
                        result.hostSpeedMbps = eth.SpeedMbps
                        break
                    } else if (mac && !result.hostMac) {
                        result.hostMac = mac
                        result.hostLink = eth.LinkStatus
                    }
                }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} EthernetInterfaces read failed: ${t.message}")
            }

            // v0.1.28: iLO's own management network (its dedicated/shared NIC)
            try {
                def iloEthColl = getJson('/redfish/v1/Managers/1/EthernetInterfaces') ?: [:]
                def iloMembers = iloEthColl.Members ?: []
                for (def member : iloMembers) {
                    String memberPath = member?.'@odata.id' as String
                    if (!memberPath) continue
                    def eth = getJson(memberPath)
                    if (eth == null) continue
                    // Prefer the interface that's actually carrying traffic
                    def ip = (eth.IPv4Addresses ?: []).find { it.Address }?.Address
                    if (ip && !result.iloIp) {
                        result.iloMac = eth.MACAddress
                        result.iloIp = ip
                        result.iloHostName = eth.HostName ?: eth.FQDN
                        result.iloFqdn = eth.FQDN
                        break
                    } else if (eth.MACAddress && !result.iloMac) {
                        result.iloMac = eth.MACAddress
                        result.iloHostName = eth.HostName ?: eth.FQDN
                    }
                }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} iLO EthernetInterfaces read failed: ${t.message}")
            }

            // Drive enumeration. iLO 6 deprecated the legacy SmartStorage OEM
            // path entirely; the canonical place to look is the DMTF Redfish
            // Storage model. But entry-level ProLiant Gen11 (e.g. MicroServer
            // Gen11) without a Smart Array controller doesn't always populate
            // /Systems/1/Storage — direct-attached SATA via chipset AHCI
            // requires HPE's Agentless Management Service (AMS) running on
            // the host OS to be visible, and NVMe drives may appear under
            // additional chassis entries that RDE storage devices register
            // (e.g. /Chassis/DE040000/Drives/0).
            //
            // Lookup chain (each step runs only if drives is still empty):
            //   1. /Systems/1/Storage/<n>/Drives/<n>           (modern controller)
            //   2. /Systems/1/SimpleStorage/<n>/Devices/<n>    (legacy)
            //   3. /Chassis/1/Drives                           (direct chassis collection)
            //   4. /Chassis/1.Links.Drives                     (chassis Links array)
            //   5. /Chassis collection walk → each chassis Drives + Links.Drives
            //      (catches RDE devices that register their own chassis IDs)
            //
            // The drivesDiag map records what each step returned so the user
            // can see in Diagnostics whether iLO is enumerating drives at all.
            Map drivesDiag = [:]
            try {
                List drives = []

                // (1) Modern: /Systems/1/Storage → controllers → Drives[]
                def storageColl = getJson('/redfish/v1/Systems/1/Storage') ?: [:]
                int sysStorageMembers = (storageColl.Members ?: []).size()
                drivesDiag['sysStorageMembers'] = sysStorageMembers
                (storageColl.Members ?: []).each { storMember ->
                    String storPath = storMember?.'@odata.id' as String
                    if (!storPath) return
                    def stor = getJson(storPath)
                    if (stor == null) return
                    (stor.Drives ?: []).each { driveRef ->
                        String drivePath = driveRef?.'@odata.id' as String
                        if (!drivePath) return
                        def drive = getJson(drivePath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                }
                drivesDiag['afterSysStorage'] = drives.size()

                // (2) Legacy: /SimpleStorage
                if (drives.isEmpty()) {
                    def simpleColl = getJson('/redfish/v1/Systems/1/SimpleStorage') ?: [:]
                    int simpleMembers = (simpleColl.Members ?: []).size()
                    drivesDiag['simpleStorageMembers'] = simpleMembers
                    (simpleColl.Members ?: []).each { ssMember ->
                        String ssPath = ssMember?.'@odata.id' as String
                        if (!ssPath) return
                        def ss = getJson(ssPath)
                        if (ss == null) return
                        (ss.Devices ?: []).each { dev ->
                            Long bytes = dev.CapacityBytes as Long
                            drives << [
                                    model     : dev.Model,
                                    serial    : null,
                                    mediaType : null,
                                    protocol  : null,
                                    capacityGB: bytes ? (bytes / 1_000_000_000L) as Long : null,
                                    health    : dev.Status?.Health,
                                    state     : dev.Status?.State,
                                    failurePredicted: null
                            ]
                        }
                    }
                    drivesDiag['afterSimpleStorage'] = drives.size()
                }

                // (3) Direct chassis Drives collection: /Chassis/1/Drives
                if (drives.isEmpty()) {
                    def chDrives = getJson('/redfish/v1/Chassis/1/Drives')
                    int chDrivesMembers = (chDrives?.Members ?: []).size()
                    drivesDiag['chassis1DrivesMembers'] = chDrivesMembers
                    (chDrives?.Members ?: []).each { dRef ->
                        String dPath = dRef?.'@odata.id' as String
                        if (!dPath) return
                        def drive = getJson(dPath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                    drivesDiag['afterChassis1Drives'] = drives.size()
                }

                // (4) Chassis Links.Drives[] array (some firmwares put refs here)
                if (drives.isEmpty()) {
                    def chassis = getJson('/redfish/v1/Chassis/1')
                    int chLinkDrives = (chassis?.Links?.Drives ?: []).size()
                    drivesDiag['chassis1LinksDrives'] = chLinkDrives
                    (chassis?.Links?.Drives ?: []).each { dRef ->
                        String dPath = dRef?.'@odata.id' as String
                        if (!dPath) return
                        def drive = getJson(dPath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                    drivesDiag['afterChassis1Links'] = drives.size()
                }

                // (5) Walk all chassis entries (RDE devices register their own)
                if (drives.isEmpty()) {
                    def chassisColl = getJson('/redfish/v1/Chassis') ?: [:]
                    List allChassis = (chassisColl.Members ?: []) as List
                    drivesDiag['chassisCollectionMembers'] = allChassis.size()
                    allChassis.each { cRef ->
                        String cPath = cRef?.'@odata.id' as String
                        if (!cPath || cPath.endsWith('/Chassis/1') || cPath.endsWith('/Chassis/1/')) return
                        def ch = getJson(cPath)
                        if (ch == null) return
                        // Try Drives collection on this chassis
                        def chDriveColl = getJson("${cPath}/Drives".toString())
                        (chDriveColl?.Members ?: []).each { dRef ->
                            String dPath = dRef?.'@odata.id' as String
                            if (!dPath) return
                            def drive = getJson(dPath)
                            if (drive == null) return
                            drives << buildDriveEntry(drive)
                        }
                        // Try Links.Drives on this chassis too
                        (ch.Links?.Drives ?: []).each { dRef ->
                            String dPath = dRef?.'@odata.id' as String
                            if (!dPath) return
                            def drive = getJson(dPath)
                            if (drive == null) return
                            drives << buildDriveEntry(drive)
                        }
                    }
                    drivesDiag['afterChassisCollection'] = drives.size()
                }

                result.drives = drives
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Drives read failed: ${t.message}")
                drivesDiag['exception'] = t.message
            }
            result.drivesDiag = drivesDiag

            // Recent IML events (Integrated Management Log) — last 5.
            // v0.1.28: try with OData $top first (efficient on supported firmware),
            // fall back to fetching the full Members list and taking the tail.
            // Some iLO 6 firmware doesn't honor OData filters on the IML endpoint.
            try {
                def iml = getJson('/redfish/v1/Managers/1/LogServices/IML/Entries?$top=5')
                if (iml == null || !(iml.Members)) {
                    iml = getJson('/redfish/v1/Managers/1/LogServices/IML/Entries')
                }
                def members = (iml?.Members ?: []) as List
                // IML entries are usually returned chronologically — take the last 5
                def tail = members.size() > 5 ? members.subList(members.size() - 5, members.size()) : members
                def entries = tail.reverse().collect { e ->
                    [
                            created : e.Created,
                            severity: e.Severity,
                            message : e.Message
                    ]
                }
                result.recentEvents = entries
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} IML read failed: ${t.message}")
            }

            // v0.1.29: per-DIMM detail. Walks /Systems/1/Memory and fetches each
            // member. iLO 6 returns ~16-24 slots even on small servers (most
            // empty), so we filter to populated DIMMs only.
            try {
                def memColl = getJson('/redfish/v1/Systems/1/Memory') ?: [:]
                List dimms = []
                (memColl.Members ?: []).each { mRef ->
                    String mPath = mRef?.'@odata.id' as String
                    if (!mPath) return
                    def dimm = getJson(mPath)
                    if (dimm == null) return
                    Integer cap = dimm.CapacityMiB as Integer
                    if (cap == null || cap == 0) return  // empty slot — skip
                    dimms << [
                            slot         : dimm.DeviceLocator ?: dimm.Id,
                            capacityGiB  : cap ? (cap / 1024) as Integer : null,
                            speedMHz     : dimm.OperatingSpeedMhz,
                            manufacturer : dimm.Manufacturer,
                            partNumber   : dimm.PartNumber,
                            type         : dimm.MemoryDeviceType,
                            health       : dimm.Status?.Health,
                            state        : dimm.Status?.State
                    ]
                }
                result.dimms = dimms
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Memory detail read failed: ${t.message}")
            }

            // v0.1.29: active iLO sessions (HPE OEM). Tells you who else is
            // logged into this iLO right now — useful for "is the console
            // already in use?" before launching IRC.
            try {
                def sessColl = getJson('/redfish/v1/SessionService/Sessions') ?: [:]
                List sessions = []
                (sessColl.Members ?: []).each { sRef ->
                    String sPath = sRef?.'@odata.id' as String
                    if (!sPath) return
                    def sess = getJson(sPath)
                    if (sess == null) return
                    // Skip the session we just created for this status pull
                    // (its UserName equals the credential we logged in with).
                    if (sess.'@odata.id' == sessionLocation) return
                    sessions << [
                            userName : sess.UserName,
                            sourceIp : sess.ClientOriginIPAddress ?: sess.Oem?.Hpe?.MySession?.ClientOriginIp,
                            sessionType: sess.Oem?.Hpe?.MySession?.SessionType ?: 'Web'
                    ]
                }
                result.activeSessions = sessions
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Sessions read failed: ${t.message}")
            }

            // v0.1.32: firmware inventory — a summary of the firmware versions
            // iLO knows about (System BIOS, iLO itself, network adapters, etc.).
            // Useful for "is this server up to date" without needing to deep-dive
            // into iLO's own UI. From the standard /UpdateService/FirmwareInventory
            // collection, but iLO populates it with HPE-specific component names.
            try {
                def fwColl = getJson('/redfish/v1/UpdateService/FirmwareInventory') ?: [:]
                List components = []
                (fwColl.Members ?: []).each { fwRef ->
                    String fwPath = fwRef?.'@odata.id' as String
                    if (!fwPath) return
                    def fw = getJson(fwPath)
                    if (fw == null) return
                    components << [
                            name    : fw.Name,
                            version : fw.Version,
                            updateable : fw.Updateable
                    ]
                }
                result.firmwareInventory = components
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} FirmwareInventory read failed: ${t.message}")
            }

            // v0.1.34 — Power Trend from /Chassis/1/EnvironmentMetrics +
            // /Chassis/1/Power/PowerMeter.
            //
            // EnvironmentMetrics (DMTF) gives the current PowerWatts reading
            // plus an optional ReadingRangeMin/Max. PowerMeter (HPE OEM under
            // /Chassis/1/Power) is where iLO 6 puts the historical sample
            // array — a rolling buffer of per-minute or per-five-minute
            // PowerConsumed averages over the last 24h. Together they fill
            // the Power Trend card; either alone is enough to render the
            // gauge half.
            //
            // Gen11 MicroServer typically returns Reading=null on both
            // endpoints (the hardware tier doesn't track power), in which
            // case powerTrend ends up with all-null fields and the
            // ServerTabProvider renders the "no history available" empty
            // state.
            try {
                Map trend = [
                        current     : null,
                        units       : 'W',
                        history     : [],
                        min         : null,
                        max         : null,
                        avg         : null,
                        intervalMin : null,
                        source      : null
                ]
                // EnvironmentMetrics — current + range
                try {
                    def em = getJson('/redfish/v1/Chassis/1/EnvironmentMetrics')
                    if (em != null) {
                        def pw = em.PowerWatts ?: [:]
                        if (pw.Reading != null) {
                            trend.current = pw.Reading as Integer
                            trend.source = 'EnvironmentMetrics'
                        }
                        if (pw.ReadingRangeMin != null) trend.min = pw.ReadingRangeMin as Integer
                        if (pw.ReadingRangeMax != null) trend.max = pw.ReadingRangeMax as Integer
                    }
                } catch (Throwable t) {
                    log.debug("iLO ${iloHost} EnvironmentMetrics read failed: ${t.message}")
                }
                // PowerMeter — historical samples. HPE OEM extension under
                // /Chassis/1/Power/PowerMeter on iLO 6. Shape:
                //   { Samples: [ {Time: "2026-...", Average: 42, Maximum: 50}, ... ] }
                // Samples are oldest-first; we want newest at the right edge of
                // the sparkline, so we keep the array as-is (left→right = oldest→newest).
                try {
                    def pm = getJson('/redfish/v1/Chassis/1/Power/PowerMeter')
                    if (pm != null) {
                        List samples = (pm.Samples ?: []) as List
                        if (samples) {
                            // Cap at last 60 samples — the sparkline width
                            // is small and dense polylines just look noisy.
                            int keep = Math.min(samples.size(), 60)
                            List tail = samples.subList(samples.size() - keep, samples.size())
                            List hist = tail.collect { s ->
                                [
                                        ts  : s.Time as String,
                                        avg : (s.Average != null ? (s.Average as Integer) : null),
                                        max : (s.Maximum != null ? (s.Maximum as Integer) : null)
                                ]
                            }.findAll { it.avg != null || it.max != null }
                            trend.history = hist
                            // Sample-derived stats — only set if we don't have
                            // them from EnvironmentMetrics already
                            if (hist) {
                                List avgs = hist.collect { (it.avg ?: it.max) as Integer }.findAll { it != null }
                                if (avgs) {
                                    if (trend.min == null) trend.min = avgs.min() as Integer
                                    if (trend.max == null) trend.max = avgs.max() as Integer
                                    trend.avg = ((avgs.sum() as Integer) / avgs.size()) as Integer
                                }
                                trend.source = trend.source ? "${trend.source}+PowerMeter" : 'PowerMeter'
                            }
                        }
                    }
                } catch (Throwable t) {
                    log.debug("iLO ${iloHost} PowerMeter read failed: ${t.message}")
                }
                // Cross-fill from the existing /Power read so the card has
                // *something* useful even if EnvironmentMetrics and PowerMeter
                // are both empty.
                if (trend.current == null && result.powerConsumedWatts != null) {
                    trend.current = result.powerConsumedWatts as Integer
                }
                if (trend.min == null && result.powerMinWatts != null) trend.min = result.powerMinWatts as Integer
                if (trend.max == null && result.powerMaxWatts != null) trend.max = result.powerMaxWatts as Integer
                if (trend.avg == null && result.powerAvgWatts != null) trend.avg = result.powerAvgWatts as Integer
                if (trend.intervalMin == null && result.powerIntervalMin != null) trend.intervalMin = result.powerIntervalMin as Integer
                result.powerTrend = trend
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} PowerTrend block failed: ${t.message}")
                result.powerTrend = [current:null, units:'W', history:[], min:null, max:null, avg:null, intervalMin:null, source:null]
            }

            // v0.1.34 — Network Adapters walk.
            //
            // /Chassis/1/NetworkAdapters is the modern DMTF collection. Each
            // NetworkAdapter resource carries Model/Manufacturer/Serial/Part/
            // Firmware.Current.VersionString, plus references to:
            //   - NetworkPorts (older schema, the iLO 6 1.x norm)
            //   - Ports (newer schema, iLO 7+)
            // We try NetworkPorts first since that's what iLO 6 1.74 publishes.
            //
            // Per-port fields:
            //   - LinkStatus: "LinkUp"/"LinkDown"/"NoLink"/"Starting"
            //   - CurrentLinkSpeedMbps (or LinkSpeedMbps on some firmware)
            //   - ActiveLinkTechnology: "Ethernet" usually
            //   - AssociatedNetworkAddresses: [MAC, ...] (array, port may have multiple)
            //   - Oem.Hpe.PortHealth: HPE port-level health
            //   - Oem.Hpe.LinkStatus / Oem.Hpe.PortStatus: HPE-specific status
            //   - PhysicalPortNumber: physical port index
            //
            // Both NetworkAdapters card AND NICPortLED card consume this data —
            // we walk once, render twice (NIC LED card filters to a compact
            // per-port view focused on health/link rather than full detail).
            try {
                def naColl = getJson('/redfish/v1/Chassis/1/NetworkAdapters') ?: [:]
                List adapters = []
                (naColl.Members ?: []).each { naRef ->
                    String naPath = naRef?.'@odata.id' as String
                    if (!naPath) return
                    def na = getJson(naPath)
                    if (na == null) return
                    // Adapter-level fields
                    String fwVer = na.Firmware?.Current?.VersionString ?: na.FirmwarePackageVersion
                    Map adapter = [
                            id          : na.Id,
                            name        : na.Name,
                            model       : na.Model,
                            manufacturer: na.Manufacturer,
                            serial      : na.SerialNumber,
                            partNumber  : na.PartNumber,
                            firmware    : fwVer,
                            sku         : na.SKU,
                            health      : na.Status?.Health,
                            state       : na.Status?.State,
                            ports       : []
                    ]
                    // Determine ports collection URL — NetworkPorts first
                    // (iLO 6 1.x), Ports second (Redfish 2022+). Both fields
                    // are odata-references to a Collection.
                    String portsPath = (na.NetworkPorts?.'@odata.id' as String) ?: (na.Ports?.'@odata.id' as String)
                    if (portsPath) {
                        def portsColl = getJson(portsPath)
                        (portsColl?.Members ?: []).each { pRef ->
                            String pPath = pRef?.'@odata.id' as String
                            if (!pPath) return
                            def p = getJson(pPath)
                            if (p == null) return
                            // MAC: AssociatedNetworkAddresses is the canonical
                            // location on NetworkPort; on newer "Ports" it's
                            // Ethernet.AssociatedMACAddresses. Try both.
                            List macs = (p.AssociatedNetworkAddresses ?: p.Ethernet?.AssociatedMACAddresses ?: []) as List
                            // Speed: NetworkPort uses CurrentLinkSpeedMbps; older firmware
                            // sometimes uses LinkSpeedMbps; the newer Ports schema reports
                            // CurrentSpeedGbps. Walk in that priority order.
                            Integer speedMbps = null
                            if (p.CurrentLinkSpeedMbps != null) {
                                speedMbps = p.CurrentLinkSpeedMbps as Integer
                            } else if (p.LinkSpeedMbps != null) {
                                speedMbps = p.LinkSpeedMbps as Integer
                            } else if (p.CurrentSpeedGbps != null) {
                                speedMbps = ((p.CurrentSpeedGbps as Integer) * 1000) as Integer
                            }
                            def portOem = p.Oem?.Hpe ?: [:]
                            adapter.ports << [
                                    id               : p.Id,
                                    name             : p.Name,
                                    portNumber       : p.PhysicalPortNumber ?: p.PortId,
                                    linkStatus       : p.LinkStatus,
                                    speedMbps        : speedMbps,
                                    activeTech       : p.ActiveLinkTechnology,
                                    macs             : macs.collect { it?.toString() }.findAll { it },
                                    health           : p.Status?.Health,
                                    state            : p.Status?.State,
                                    // HPE OEM extensions — drive the NIC LED card
                                    hpePortHealth    : portOem.PortHealth,
                                    hpeLinkStatus    : portOem.LinkStatus,
                                    hpePortStatus    : portOem.PortStatus,
                                    hpeFlexLOM       : portOem.FlexLOM,
                                    hpeNicCapacity   : portOem.NicCapacity
                            ]
                        }
                        // Sort ports by physical port number / id — iLO often
                        // returns them in random order, which makes for an
                        // ugly UI table.
                        adapter.ports = adapter.ports.sort { ((it.portNumber ?: it.id ?: '') as String) }
                    }
                    adapters << adapter
                }
                // Sort adapters by name so the UI ordering is stable across
                // refreshes (Members order from iLO isn't guaranteed stable).
                result.networkAdapters = adapters.sort { ((it.name ?: it.id ?: '') as String) }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} NetworkAdapters read failed: ${t.message}")
                result.networkAdapters = []
            }

            // v0.1.34 — Volumes / RAID walk.
            //
            // Walks /Systems/1/Storage and for each controller fetches the
            // Volumes collection. On Gen11 MicroServer without a Smart Array
            // controller, /Systems/1/Storage returns zero members and this
            // block produces an empty list — the card then doesn't render.
            //
            // Per-volume fields from the DMTF Volume resource:
            //   - Id, Name, DisplayName (HPE OEM sometimes)
            //   - RAIDType: "RAID0"/"RAID1"/"RAID5"/"RAID6"/"RAID10"/"RAID50"/"RAID60"
            //   - CapacityBytes
            //   - Status.Health / Status.State
            //   - Links.Drives[]: list of refs to constituent drives → count = stripe width
            //   - Operations[]: in-progress operations (rebuild, init, expand) with PercentComplete
            //   - WriteCachePolicy, ReadCachePolicy (HPE OEM)
            //   - Encrypted (HPE OEM)
            try {
                List volumes = []
                def storColl = getJson('/redfish/v1/Systems/1/Storage') ?: [:]
                (storColl.Members ?: []).each { storMember ->
                    String storPath = storMember?.'@odata.id' as String
                    if (!storPath) return
                    def stor = getJson(storPath)
                    if (stor == null) return
                    // Controller name for grouping in the UI
                    String ctlName = null
                    def storCtls = (stor.StorageControllers ?: stor.Controllers ?: []) as List
                    if (storCtls) {
                        ctlName = storCtls[0]?.Name ?: storCtls[0]?.Model
                    }
                    if (!ctlName) ctlName = (stor.Name ?: stor.Id) as String
                    // Volumes collection — usually $stor.Volumes['@odata.id'],
                    // sometimes just $storPath + '/Volumes'.
                    String volPath = (stor.Volumes?.'@odata.id' as String) ?: "${storPath}/Volumes".toString()
                    def volColl = getJson(volPath)
                    (volColl?.Members ?: []).each { vRef ->
                        String vPath = vRef?.'@odata.id' as String
                        if (!vPath) return
                        def v = getJson(vPath)
                        if (v == null) return
                        Long capBytes = v.CapacityBytes as Long
                        Long capGB = capBytes ? (capBytes / 1_000_000_000L) as Long : null
                        // Drive count = stripe width. Links.Drives is an array
                        // of refs; we just count it rather than walking.
                        int driveCount = (v.Links?.Drives ?: []).size()
                        // In-progress operation, if any
                        def op = (v.Operations ?: [])[0]
                        String opName = op?.OperationName as String
                        Integer opPct = op?.PercentComplete as Integer
                        def volOem = v.Oem?.Hpe ?: [:]
                        volumes << [
                                id          : v.Id,
                                name        : v.Name ?: v.DisplayName ?: volOem.LogicalDriveName,
                                raidType    : v.RAIDType ?: volOem.Raid,
                                capacityGB  : capGB,
                                health      : v.Status?.Health,
                                state       : v.Status?.State,
                                driveCount  : driveCount,
                                opName      : opName,
                                opPct       : opPct,
                                controller  : ctlName,
                                encrypted   : (v.Encrypted == true) || (volOem.LogicalDriveEncryption == true),
                                writeCache  : v.WriteCachePolicy ?: volOem.WriteCachePolicy,
                                readCache   : v.ReadCachePolicy ?: volOem.ReadCachePolicy,
                                bootable    : volOem.BootVolume
                        ]
                    }
                }
                result.volumes = volumes.sort { ((it.controller ?: '') as String) + ((it.name ?: it.id ?: '') as String) }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Volumes read failed: ${t.message}")
                result.volumes = []
            }
        } catch (Throwable t) {
            log.warn("iLO ${iloHost} status collection failed: ${t.message}", t)
            // v0.1.33: record the partial-failure info but DO NOT flip success
            // to false if we already got the core system reads — partial data
            // is better than no data, and we surface the error in Diagnostics.
            // If the very first reads (login + Systems/1) failed, success is
            // still false at this point and the error tab renders normally.
            result.partialError = "${t.class.simpleName}: ${t.message}".toString()
            if (!result.success) {
                result.error = t.message
            }
        } finally {
            logout()
        }
        return result
    }

    /**
     * Normalize a drive entry from any of the shapes we walk:
     *   - Standard Redfish /Systems/1/Storage/<n>/Drives/<n> (Drive resource)
     *   - HPE OEM /Systems/1/SmartStorage/.../PhysicalDrives/<n>
     *     (uses CapacityGB/CapacityMiB instead of CapacityBytes,
     *      "InterfaceType" and "MediaType" instead of Protocol/MediaType)
     *   - /Chassis/<id>/Drives/<n> (Drive resource — same shape as Storage path,
     *     used by RDE storage devices that register their own chassis IDs)
     *
     * v0.1.33: enriched to capture HPE OEM extension fields documented in the
     * iLO 6 v1.77 storage resource definitions — drive temperature, lifetime
     * power-on hours, NVMe identifier, predicted media life, bay/slot info
     * via Location[], encryption status, drive form factor, and the richer
     * Oem.Hpe.DriveStatus (separate from base Status, sometimes more accurate).
     */
    private static Map buildDriveEntry(def d) {
        Long capacityGB = null
        if (d.CapacityBytes) {
            capacityGB = ((d.CapacityBytes as Long) / 1_000_000_000L) as Long
        } else if (d.CapacityGB) {
            capacityGB = d.CapacityGB as Long
        } else if (d.CapacityMiB) {
            // 1 MiB = 1048576 bytes; convert to GB (decimal) to match other paths
            capacityGB = (((d.CapacityMiB as Long) * 1_048_576L) / 1_000_000_000L) as Long
        }
        String protocol  = (d.Protocol as String) ?: (d.InterfaceType as String)
        // HPE iLO 6 publishes a richer status under Oem.Hpe.DriveStatus —
        // prefer it when present since the base Status.Health is sometimes
        // a roll-up that hides specific drive problems.
        def hpeOem = d.Oem?.Hpe ?: [:]
        String healthFromOem = hpeOem.DriveStatus?.Health
        String stateFromOem  = hpeOem.DriveStatus?.State
        // Location[] is an array; the most useful entry's Info field
        // describes where the drive physically sits (e.g. "Box 1 Bay 3",
        // "Bay 5", "Slot 21" for NVMe AICs).
        String locationInfo = null
        def loc = (d.Location ?: [])[0]
        if (loc?.Info) {
            locationInfo = loc.Info as String
        } else if (d.PhysicalLocation?.PartLocation?.ServiceLabel) {
            // Standard Redfish location fallback
            locationInfo = d.PhysicalLocation.PartLocation.ServiceLabel as String
        }
        return [
                model           : d.Model,
                serial          : d.SerialNumber,
                mediaType       : d.MediaType as String,
                protocol        : protocol,
                capacityGB      : capacityGB,
                health          : healthFromOem ?: (d.Status?.Health),
                state           : stateFromOem  ?: (d.Status?.State),
                failurePredicted: d.FailurePredicted,
                // v0.1.33 enrichments
                tempC           : hpeOem.CurrentTemperatureCelsius,
                tempHealth      : hpeOem.TemperatureStatus?.Health,
                powerOnHours    : hpeOem.PowerOnHours,
                nvmeId          : hpeOem.NVMeId,
                healthUpdateMode: hpeOem.HealthUpdated,            // "Boot" or "Dynamic"
                location        : locationInfo,
                formFactor      : d.DriveFormFactor,               // "M2_2280", "Drive2_5", "EDSFF_E3_Short", ...
                encryptionStatus: d.EncryptionStatus,              // "Unencrypted" / "Unlocked" / "Locked"
                lifeLeftPct     : d.PredictedMediaLifeLeftPercent, // 100 = pristine, lower = worn
                hotspareType    : d.HotspareType                   // "None" / "Global" / "Chassis" / "Dedicated"
        ]
    }

    private static Map<String, String> normalizeHeaders(def raw) {
        Map<String, String> out = [:]
        if (raw == null) return out
        if (raw instanceof Map) {
            raw.each { k, v ->
                String key = k.toString().toLowerCase()
                if (v instanceof List) out[key] = v[0]?.toString()
                else out[key] = v?.toString()
            }
        } else if (raw instanceof List) {
            raw.each { entry ->
                if (entry instanceof Map.Entry) {
                    out[entry.key.toString().toLowerCase()] = entry.value?.toString()
                }
            }
        }
        return out
    }
}
