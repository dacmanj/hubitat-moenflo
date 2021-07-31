/**
 * Moen Flo Smart Shutoff for Hubitat by David Manuel is licensed under CC BY 4.0 see https://creativecommons.org/licenses/by/4.0
 * Software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 2021-07-31 v1.0.0 - Hubitat Package Manager support, bump version
 * 2021-01-24 v0.1.08-alpha - Stop checking for last manual healthtest if request fails / id returns nothing
 * 2020-07-30 v0.1.07-alpha - Changed data type of attributes to string, updated debug message for latest hubitat health test
 * 2020-07-13 v0.1.06-alpha - Removed pending notification counts, causing unneeded events, add unit for tempF, round metrics for display
 * 2020-07-13 v0.1.05-alpha - Updated preferences save to separate out password updates
 * 2020-07-13 v0.1.04-alpha - Added last event and last health test to polling
 * 2020-07-13 v0.1.03-alpha - Update to login error logging/handling
 * 2020-07-12 v0.1.02-alpha - Default to First Device
 * 2020-07-12 v0.1.01-alpha - Add Debug Logging
 * 2020-07-12 v0.1.0-alpha - Initial Release
 *
 */

metadata {
    definition (name: "Moen Flo Smart Shutoff", namespace: "dacmanj", author: "David Manuel", importUrl: "https://raw.githubusercontent.com/dacmanj/hubitat-moenflo/master/moenflo.groovy") {
        capability "Valve"
        capability "PushableButton"
        capability "LocationMode"
        capability "Momentary"
        capability "TemperatureMeasurement"
        capability "SignalStrength"

        command "home"
        command "away"
        command "sleepMode"
        command "logout"
        command "manualHealthTest"
        command "pollMoen"

        attribute "numberOfButtons", "number"
        attribute "pushed", "number"
        attribute "mode", "enum", ["home","away","sleep"]
        attribute "valve", "enum", ["open", "closed"]
        attribute "temperature", "number"
        attribute "gpm", "number"
        attribute "psi", "number"
        attribute "updated", "string"
        attribute "rssi", "number"
        attribute "ssid", "string"
        attribute "lastHubitatHealthtestStatus", "string"
        attribute "totalGallonsToday", "number"
        attribute "lastEvent", "string"
        attribute "lastEventDetail", "string"
        attribute "lastEventDateTime", "string"
        attribute "lastHealthTestStatus", "string"
        attribute "lastHealthTestDetail", "string"
        attribute "lastHealthTestDateTime", "string"

    }

    preferences {
        input(name: "username", type: "string", title:"User Name", description: "Enter Moen Flo User Name", required: true, displayDuringSetup: true)
        input(name: "password", type: "password", title:"Password", description: "Enter Moen Flo Password (to set or change it)", displayDuringSetup: true)
        input(name: "mac_address", type: "string", title:"Device Id", description: "Enter Device Id from MeetFlo.com (if you have multiple devices)", required: false, displayDuringSetup: true)
        input(name: "revert_mode", type: "enum", title: "Revert Mode (after Sleep)", options: ["home","away","sleep"], defaultValue: "home")
        input(name: "polling_interval", type: "number", title: "Polling Interval (in Minutes)", range: 5..59, defaultValue: "10")
        input(name: "revert_minutes", type: "number", title: "Revert Time in Minutes (after Sleep)", defaultValue: 120)
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true

    }

}

def logsOff(){
    device.updateSetting("logEnable", false)
    log.warn "Debug Logging Disabled..."
}

def open() {
    valve_update("open")
}

def logout() {
    state.clear()
    unschedule()
    device.updateDataValue("token","")
    device.updateDataValue("device_id","")
    device.updateDataValue("location_id","")
    device.updateDataValue("user_id","")
    device.updateDataValue("encryptedPassword", "")
    device.removeSetting("username")
    device.removeSetting("mac_address")
}

def updated() {
    configure()
    if (state.configured) pollMoen()
    if (logEnable) runIn(1800,logsOff)
}

def installed() {
    runIn(1800,logsOff)
}

def unschedulePolling() {
    unschedule(pollMoen)
}

def schedulePolling() {
    unschedule(pollMoen)
    def pw = device.getDataValue("encryptedPassword")
    if (polling_interval != "None" && pw && pw != "") {
        schedule("0 0/${polling_interval} * 1/1 * ? *", pollMoen)
    }
}

def pollMoen() {
    if (logEnable) log.debug("Polling Moen")
    getDeviceInfo()
    getHealthTestInfo()
    getConsumption()
    getLastAlerts()
}

def close() {
    valve_update("closed")
}

def home() {
    setMode("home")
}

def away() {
    setMode("away")
}

def sleepMode() {
    setMode("sleep")
}

def setMode(mode) {
    if (logEnable) log.debug "Setting Flo mode to ${mode}"
    def location_id = device.getDataValue("location_id")
    def uri = "https://api-gw.meetflo.com/api/v2/locations/${location_id}/systemMode"
    def body = [target:mode]
    def headers = [:]
    headers.put("Content-Type", "application/json")
    headers.put("Authorization", device.getDataValue("token"))
    if (mode == "sleep") {
        body.put("revertMinutes", revert_minutes)
        body.put("revertMode", revert_mode)
    }

    if (!location_id || location_id == "") {
        log.debug "Failed to set mode: No Location Id"
    } else if (!mode || mode == "") {
        log.debug "Failed to set mode: No Location Id"
    } else {
        def response = make_authenticated_post(uri, body, "Mode Update", [204])
        sendEvent(name: "mode", value: mode)
    }

}

def valve_update(target) {
    def device_id = device.getDataValue("device_id")
    if (device_id && device_id != null) {
        def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}"
        if (logEnable) log.debug "Updating valve status to ${target}"
        def body = [valve:[target: target]]
        def response = make_authenticated_post(uri, body, "Valve Update")
        sendEvent(name: "valve", value: response?.data?.valve?.target)
    } else {
        log.debug "Failed to update: no device_id found"
    }
}

def push(btn) {
    switch(btn) {
       case 1: mode = "home"; break;
       case 2: mode = "away"; break;
       case 3: mode = "sleep"; break;
       default: mode = "home";
    }
    if (logEnable) log.debug "Setting Flo mode to ${mode} via button press"
    setMode(mode)
}

def getUserInfo() {
    def user_id = device.getDataValue("user_id")
    if (mac_address) { log.debug "Getting device id for: ${mac_address}"}
    else { log.debug "Defaulting to first device found." }
    def uri = "https://api-gw.meetflo.com/api/v2/users/${user_id}?expand=locations,alarmSettings"
    def response = make_authenticated_get(uri, "Get User Info")
    device.updateDataValue("location_id", response.data.locations[0].id)
    response.data.locations[0].devices.each {
        if(it.macAddress == mac_address || !mac_address || mac_address == "") {
            device.updateDataValue("device_id", it.id)
            device.updateSetting("mac_address", it.macAddress)
            if(logEnable) log.debug "Found device id: ${it.id}"
        }
    }
}

def getDeviceInfo() {
    def device_id = device.getDataValue("device_id")
    if (!device_id || device_id == "") {
        log.debug "Cannot complete device info request: No Device Id"
    } else {
        def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}"
        def response = make_authenticated_get(uri, "Get Device")
        def data = response.data
        sendEvent(name: "gpm", value: round(data?.telemetry?.current?.gpm))
        sendEvent(name: "psi", value: round(data?.telemetry?.current?.psi))
        sendEvent(name: "temperature", value: data?.telemetry?.current?.tempF, unit: "F")
        sendEvent(name: "updated", value: data?.telemetry?.current?.updated)
        sendEvent(name: "valve", value: data?.valve?.target)
        sendEvent(name: "rssi", value: data?.connectivity?.rssi)
        sendEvent(name: "ssid", value: data?.connectivity?.ssid)
        def system_mode = data?.fwProperties?.system_mode
        def SYSTEM_MODES = [2: "home", 3: "away", 5: "sleep"]
        sendEvent(name: "mode", value: SYSTEM_MODES[system_mode])
    }
}

def getLastAlerts() {
    def device_id = device.getDataValue("device_id")
    if (!device_id || device_id == "") {
        log.debug "Cannot fetch alerts: No Device Id"
    } else {
        def uri = "https://api-gw.meetflo.com/api/v2/alerts?isInternalAlarm=false&deviceId=${device_id}"
        def response = make_authenticated_get(uri, "Get Alerts")
        def data = response.data.items
        if (data) {
            sendEvent(name: "lastEvent", value: data[0]?.displayTitle)
            sendEvent(name: "lastEventDetail", value: data[0].displayMessage)
            sendEvent(name: "lastEventDateTime", value: data[0].createAt)
            for (alert in data) {
                if (alert?.healthTest?.roundId) {
                    sendEvent(name: "lastHealthTestStatus", value: alert.displayTitle)
                    sendEvent(name: "lastHealthTestDetail", value: alert.displayMessage)
                    sendEvent(name: "lastHealthTestDateTime", value: alert.createAt)
                    break;
                }
            }
        }
    }
}

def round(d, places = 2) {
     return (d as double).round(2)
}

def getConsumption() {
    def location_id = device.getDataValue("location_id")
    def startDate = new Date().format('yyyy-MM-dd') + 'T00:00:00.000'
    def endDate = new Date().format('yyyy-MM-dd') + 'T23:59:59.999'
    def uri = "https://api-gw.meetflo.com/api/v2/water/consumption?startDate=${startDate}&endDate=${endDate}&locationId=${location_id}&interval=1h"

    if (!location_id || location_id == "") {
        log.debug "No location id: Consumption info request failed."
    } else {
        def response = make_authenticated_get(uri, "Get Consumption")
        def data = response.data
        sendEvent(name: "totalGallonsToday", value: round(data?.aggregations?.sumTotalGallonsConsumed))
    }
}

def getHealthTestInfo() {
    def lastHealthTestId = device.getDataValue("lastHubitatHealthtestId")
    def deviceId = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${deviceId}/healthTest/${lastHealthTestId}"
    if(lastHealthTestId && lastHealthTestId != "") {
        def response = make_authenticated_get(uri, "Get Last Hubitat HealthTest Info")
        sendEvent(name: "lastHubitatHealthtestStatus", value: response?.data?.status)
        if (!response?.data?.status) {
            device.removeDataValue("lastHubitatHealthtestId")
        }
    } else {
        if (logEnabled) log.debug "Skipping Healthtest Update: No Hubitat Health Test Id Found"
    }
}

def make_authenticated_get(uri, request_type, success_status = [200, 202]) {
    def token = device.getDataValue("token")
    if (!token || token == "") login();
    def response = [:];
    int max_tries = 2;
    int tries = 0;
    while (!response?.status && tries < max_tries) {
        def headers = [:]
        headers.put("Content-Type", "application/json")
        headers.put("Authorization", device.getDataValue("token"))

        try {
            httpGet([headers: headers, uri: uri]) { resp -> def msg = ""
                if (logEnable) log.debug("${request_type} Received Response Code: ${resp?.status}")
                if (resp?.status in success_status) {
                    response = resp;
                }
                else {
                    log.debug "${request_type} Failed (${response.status}): ${response.data}"
                    login()
                }
              }
        }
        catch (Exception e) {
            log.debug "${request_type} Exception: ${e}"
            if (e.getMessage().contains("Forbidden") || e.getMessage().contains("Unauthorized")) {
                log.debug "Refreshing token..."
                login()
            }
        }
        tries++

    }
    return response
}

def manualHealthTest() {
    def device_id = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}/healthTest/run"
    def response = make_authenticated_post(uri, "", "Manual Health Test")
    def roundId = response?.data?.roundId
    def created = response?.data?.created
    def status = response?.data?.status
    device.updateDataValue("lastHubitatHealthtest", created)
    device.updateDataValue("lastHubitatHealthtestId", roundId)
    sendEvent(name: "lastHubitatHealthtestStatus", value: status)

}

def make_authenticated_post(uri, body, request_type, success_status = [200, 202]) {
    def token = device.getDataValue("token")
    if (!token || token == "") login();
    def response = [:];
    int max_tries = 2;
    int tries = 0;
    while (!response?.status && tries < max_tries) {
        def headers = [:]
        headers.put("Content-Type", "application/json")
        headers.put("Authorization", device.getDataValue("token"))

        try {
            httpPostJson([headers: headers, uri: uri, body: body]) { resp -> def msg = ""
                if (logEnable) log.debug("${request_type} Received Response Code: ${resp?.status}")
                if (resp?.status in success_status) {
                    response = resp;
                }
                else {
                    log.debug "${request_type} Failed (${resp.status}): ${resp.data}"
                }
            }
        }
        catch (Exception e) {
            log.debug "${request_type} Exception: ${e}"
            if (e.getMessage().contains("Forbidden") || e.getMessage().contains("Unauthorized")) {
                log.debug "Refreshing token..."
                login()
            }
        }
        tries++

    }
    return response
}


def configure() {
    def token = device.getDataValue("token")
    if (password && password != "") {
        device.updateDataValue("encryptedPassword", encrypt(password))
        device.removeSetting("password")
        if (!mac_address || mac_address == "" || !device.getDataValue("device_id") || !device.getDataValue("device_id") == "") {
            state.configured = false
        }
        login()
    } else if (!token || token == "") {
        log.debug "Unable to configure -- invalid login"
        return
    }
    if (isConfigurationValid()) {
        sendEvent(name:"numberOfButtons", value: 3)
        schedulePolling()
        state.configured = true
    }
}

def isNotBlank(obj) {
    return obj && obj != ""
}

def isConfigurationValid() {
    def token = device.getDataValue("token")
    def device_id = device.getDataValue("device_id")
    def location_id = device.getDataValue("location_id")
    def pw = device.getDataValue("encryptedPassword")
    def user = device.getDataValue("user_id")

    return isNotBlank(token) && isNotBlank(device_id) &&
        isNotBlank(location_id) && isNotBlank(pw) && isNotBlank(user)
}

def login() {
    def uri = "https://api.meetflo.com/api/v1/users/auth"
    def pw = decrypt(device.getDataValue("encryptedPassword"))
    if (!pw || pw == "") {
        log.debug("Login Failed: No password")
    } else {
        def body = [username:username, password:pw]
        def headers = [:]
        headers.put("Content-Type", "application/json")

        try {
            httpPostJson([headers: headers, uri: uri, body: body]) { response -> def msg = response?.status
                if (logEnable) log.debug("Login received response code ${response?.status}")
                    if (response?.status == 200) {
                        msg = "Success"
                        device.updateDataValue("token",response.data.token)
                        device.updateDataValue("user_id", response.data.tokenPayload.user.user_id)
                        if (!state.configured) { getUserInfo() }
                    }
                    else {
                        log.debug "Login Failed: (${response.status}) ${response.data}"
                        state.configured = false
                    }
              }
        }
        catch (Exception e) {
            log.debug "Login exception: ${e}"
            log.debug "Login Failed: Please confirm your Flo Credentials"
            state.configured = false
        }
    }
}
