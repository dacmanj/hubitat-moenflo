/**
 * Moen Flo
 */

metadata {
    definition (name: "Moen Flo", namespace: "dacmanj", author: "David Manuel", importUrl: "https://github.com/...") {
        capability "Valve"
        capability "PushableButton"
        capability "LocationMode"
        capability "Momentary"
        
        command "home"
        command "away"
        command "sleepMode"

        attribute "numberOfButtons", "number"
        attribute "pushed", "number"
        attribute "mode", "enum", ["home","away","sleep"]
        attribute "valve", "enum", ["open", "closed"]

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
    if (!state.configured) { get_device() }
    valve_update("open")
}

def updated() {
    configure()
    log.debug(mode_input)
    log.debug(device.currentValue('mode'))
    if (state.mode != mode_input) {
        login()
        set_mode(mode_input)
    }
}

def close() {
    login()
    if (!state.configured) { get_device() }
    valve_update("closed")
}

def home() {
    login()
    set_mode("home")
}

def away() {
    login()
    set_mode("away")
}

def sleepMode() {
    login()
    set_mode("sleep")
}

def set_mode(mode) {
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

    try {
        httpPostJson([headers: headers, uri: uri, body: body]) { response -> def msg = ""
        if (response?.status == 204) {
            msg = "Success"
            sendEvent(name: "mode", value: mode, isStateChange: true)
            log.debug "Mode Update Successful: ${mode}"


        }
        else {
            log.debug "Mode Update Failed (${response.status}): ${response.data}"
        }
      }
    }
    catch (Exception e) {
        log.debug "Mode Update Failed, exception: ${e}"
    } 
    
}

def valve_update(target) {
    def device_id = device.getDataValue("device_id")
    def uri = "https://api-gw.meetflo.com/api/v2/devices/${device_id}"

    def body = [valve:[target: target]]
    def headers = [:] 
    headers.put("Content-Type", "application/json")
    headers.put("Authorization", device.getDataValue("token"))

    try {
        httpPostJson([headers: headers, uri: uri, body: body]) { response -> def msg = ""
        if (response?.status == 200) {
            msg = "Success"
            device.updateDataValue("token",response.data.token)
            sendEvent(name: "valve", value: target, isStateChange: true)
            log.debug "Valve Update Successful: ${target}"


        }
        else {
            log.debug "Valve Update Failed: ${response.data}"
        }
      }
    }
    catch (Exception e) {
        log.debug "Valve Update Failed, exception: ${e}"
    } 

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

    set_mode(mode)
    
}

def get_device() {
    def user_id = device.getDataValue("user_id")
    log.debug "Getting device id for: ${mac_address}"
    def uri = "https://api-gw.meetflo.com/api/v2/users/${user_id}?expand=locations,alarmSettings"
    def headers = [:] 
    headers.put("Content-Type", "application/json")
    headers.put("Authorization", device.getDataValue("token"))
    
    try {
        httpGet([headers: headers, uri: uri]) { response -> def msg = ""
        if (response?.status == 200) {
            msg = "Success"
            device.updateDataValue("location_id", response.data.locations[0].id)
            response.data.locations[0].devices.each {
                if(it.macAddress == mac_address) {
                    device.updateDataValue("device_id", it.id)
                    log.debug "Found device id: ${it.id}"
                    state.configured = true
                }
            }

        }
        else {
            log.debug "Get Device Failed: ${response.data}"
        }
      }
    }
    catch (Exception e) {
        log.debug "Get Device exception: ${e}"
    }


}

def configure() {
    sendEvent(name:"numberOfButtons", value: 3)
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