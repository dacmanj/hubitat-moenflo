/**
 * Moen Flo
 */

metadata {
    definition (name: "Moen Flo", namespace: "dacmanj", author: "David Manuel", importUrl: "https://github.com/...") {
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
        attribute "updated", "text"
        attribute "rssi", "number"
        attribute "ssid", "text"
        attribute "lastHubitatHealthtestStatus", "text"

    }

    preferences {
        input(name: "username", type: "string", title:"User Name", description: "Enter Moen Flo User Name", required: true, displayDuringSetup: true)
        input(name: "password", type: "string", title:"Password", description: "Enter Moen Flo Password", required: true, displayDuringSetup: true)
        input(name: "mac_address", type: "string", title:"Device Id", description: "Enter Device ID from MeetFlo.com", required: true, displayDuringSetup: true)
        input(name: "mode_input", type: "enum", title: "Mode", options: ["home","away","sleep"], defaultValue: "home")
        input(name: "revert_mode", type: "enum", title: "Revert Mode (after Sleep)", options: ["home","away","sleep"], defaultValue: "home")
        input(name: "revert_minutes", type: "number", title: "Revert Time in Minutes (after Sleep)", defaultValue: 120)
    }
    
}

def parse(String description) {
    log.debug(description)
}

def open() {
    login()
    valve_update("open")
}

def logout() {
    state.clear()
    unschedule()
    device.updateDataValue("token","")
}

def updated() {
    configure()
    log.debug(mode_input)
    log.debug(device.currentValue('mode'))
    if (state.mode != mode_input) {
        login()
        setMode(mode_input)
    }
}

def unschedulePolling() {
    unschedule(getDeviceInfo)
}

def schedulePolling() {
    unschedule(getDeviceInfo)
    unschedule(pollMoen)
    schedule('0 0/10 * 1/1 * ? *', pollMoen)
}

def pollMoen() {
    getDeviceInfo()
    getHealthTestInfo()
}

def close() {
    login()
    valve_update("closed")
}

def home() {
    login()
    setMode("home")
}

def away() {
    login()
    setMode("away")
}

def sleepMode() {
    login()
    setMode("sleep")
}

def setMode(mode) {
    
    def device_id = device.getDataValue("device_id")
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

    def response = make_authenticated_post(uri, body, "Mode Update", [204])
    sendEvent(name: "mode", value: mode, isStateChange: true)
    
}

def valve_update(target) {
    def device_id = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}"

    def body = [valve:[target: target]]
    def response = make_authenticated_post(uri, body, "Valve Update")
    device.updateDataValue("token",response.data.token)
    sendEvent(name: "valve", value: target, isStateChange: true)
}

def push(btn) {
    log.debug "button pushed ${btn}"
    switch(btn) { 
       case 1: mode = "home"; break;
       case 2: mode = "away"; break;
       case 3: mode = "sleep"; break;
       default: mode = "home";
    } 
    log.debug "mode case ${mode}"

    setMode(mode)
    
}

def getUserInfo() {
    def user_id = device.getDataValue("user_id")
    log.debug "Getting device id for: ${mac_address}"
    def uri = "https://api-gw.meetflo.com/api/v2/users/${user_id}?expand=locations,alarmSettings"
    def response = make_authenticated_get(uri, "Get User Info")
    device.updateDataValue("location_id", response.data.locations[0].id)
    response.data.locations[0].devices.each {
        if(it.macAddress == mac_address) {
            device.updateDataValue("device_id", it.id)
            log.debug "Found device id: ${it.id}"
            state.configured = true
        }
    }
}

def getDeviceInfo() {
    def device_id = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}"
    def response = make_authenticated_get(uri, "Get Device")
    def data = response.data
    sendEvent(name: "gpm", value: data?.telemetry?.current?.gpm, isStateChange: true)
    sendEvent(name: "psi", value: data?.telemetry?.current?.psi, isStateChange: true)
    sendEvent(name: "temperature", value: data?.telemetry?.current?.tempF, isStateChange: true)
    sendEvent(name: "updated", value: data?.telemetry?.current?.updated, isStateChange: true)
    sendEvent(name: "valve", value: data?.valve?.target, isStateChange: true)
    sendEvent(name: "rssi", value: data?.connectivity?.rssi, isStateChange: true)
    sendEvent(name: "ssid", value: data?.connectivity?.ssid, isStateChange: true)
    
}

def getHealthTestInfo() {
    def lastHealthTestId = device.getDataValue("lastHubitatHealthtestId")
    def deviceId = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${deviceId}/healthTest/${lastHealthTestId}"
    if(lastHealthTestId) {
        def response = make_authenticated_get(uri, "Get HealthTest Info")
        sendEvent(name: "lastHubitatHealthtestStatus", value: response?.data?.status, isStateChange: true)
    }
}

def make_authenticated_get(uri, request_type, success_status = [200, 202]) {
    if (!device.getDataValue("token")) login();
    def headers = [:] 
    headers.put("Content-Type", "application/json")
    headers.put("Authorization", device.getDataValue("token"))
    def response = [:];
    int max_tries = 2;
    int tries = 0;
    while (!response?.status && tries < max_tries) {
    
        try {
            httpGet([headers: headers, uri: uri]) { resp -> def msg = ""
            if (resp?.status in success_status) {
                response = resp;

            }
            else {
                log.debug "${request_type} Failed (${response.status}): ${response.data}"
            }
          }
        }
        catch (Exception e) {
            log.debug "${request_type} Exception: ${e}"
            login()
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
    sendEvent(name: "lastHubitatHealthtestStatus", value: status, isStateChange: true)

}

def make_authenticated_post(uri, body, request_type, success_status = [200, 202]) {
    if (!device.getDataValue("token")) login();
    def headers = [:] 
    headers.put("Content-Type", "application/json")
    headers.put("Authorization", device.getDataValue("token"))
    def response = [:];
    int max_tries = 2;
    int tries = 0;
    while (!response?.status && tries < max_tries) {
    
        try {
            httpPostJson([headers: headers, uri: uri, body: body]) { resp -> def msg = ""
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
            login()

        }
        tries++

    }
    return response
}


def configure() {
    sendEvent(name:"numberOfButtons", value: 3)
    schedulePolling()
}

def login() {
    def uri = "https://api.meetflo.com/api/v1/users/auth"
    def body = [username:username, password:password]
    def headers = [:] 
    headers.put("Content-Type", "application/json")

    try {
        httpPostJson([headers: headers, uri: uri, body: body]) { response -> def msg = ""
        if (response?.status == 200) {
            msg = "Success"
            device.updateDataValue("token",response.data.token)
            device.updateDataValue("user_id", response.data.tokenPayload.user.user_id)
            if (!state.configured) { getUserInfo() }
        }
        else {
            log.debug "Login Failed: ${response.data}"
        }
      }
    }
    catch (Exception e) {
        log.debug "Login exception: ${e}"
    }
}