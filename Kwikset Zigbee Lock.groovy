/*
 * Capabilities
 * - Battery
 * - Configuration
 * - Refresh
 * - Switch
 * - Valve
*/

metadata {
	// Automatically generated. Make future change here.
	definition (name: "KwikSet Zigbee Lock", namespace: "sebirdman", author: "Stephen Bird") {
		capability "Actuator"
		capability "Lock"
		capability "Polling"
		capability "Refresh"
        capability "Temperature Measurement"
		capability "Lock Codes"
		capability "Battery"
        capability "Configuration"

		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0004,0005,0009,0020,0101,0402,0B05,FDBD", outClusters: "000A,0019"
	}

	// simulator metadata
	simulator {
		// status messages
		status "on": "on/off: 1"
		status "off": "on/off: 0"

		// reply messages
		reply "zcl on-off on": "on/off: 1"
		reply "zcl on-off off": "on/off: 0"
	}

	// UI tile definitions
	tiles {
		standardTile("lock", "device.lock", width: 2, height: 2, canChangeIcon: true) {
			state "off", label: 'closed', action: "lock.unlock", icon: "st.locks.lock.locked", backgroundColor: "#ffffff"
			state "on", label: 'open', action: "lock.lock", icon: "st.locks.lock.unlocked", backgroundColor: "#53a7c0"
		}
		standardTile("refresh", "device.lock", inactiveLabel: false, decoration: "flat") {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}
        valueTile("temperature", "device.temperature") {
			state("temperature", label:'${currentValue}°', unit:"F",
				backgroundColors:[
					[value: 31, color: "#153591"],
					[value: 44, color: "#1e9cbb"],
					[value: 59, color: "#90d2a7"],
					[value: 74, color: "#44b621"],
					[value: 84, color: "#f1d801"],
					[value: 95, color: "#d04e00"],
					[value: 96, color: "#bc2323"]
				]
			)
		}
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false) {
			state "battery", label:'${currentValue}% battery'
		}
		main "lock"
		details(["lock", "refresh", "temperature", "battery"])
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	log.info description
   
    Map map = [:]
    if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	} else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
    log.debug "Parse returned $map"
	def result = map ? createEvent(map) : null
    
    
    return result
}

// Commands to device

def lock() {
	sendEvent(name: "lock", value: "off")
	"st cmd 0x${device.deviceNetworkId} 2 0x0101 0 {}"
}

def unlock() {
	sendEvent(name: "lock", value: "on")
	"st cmd 0x${device.deviceNetworkId} 2 0x0101 1 {}"
}


def refresh() {
	log.debug "sending refresh command"
	"st rattr 0x${device.deviceNetworkId} 2 0x0101 0"
    "st rattr 0x${device.deviceNetworkId} 2 0x0402 0"
    "st rattr 0x${device.deviceNetworkId} 2 0x0001 0x0020"
}

def configure() {
	log.debug "binding"

	"zdo bind 0x${device.deviceNetworkId} 2 3 0x0101 {${device.zigbeeId}} {}"
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	log.debug "Desc Map: $descMap"
 
	Map resultMap = [:]
	if (descMap.cluster == "0101" && descMap.attrId == "0000") {
		def value = getLockStatus(descMap.value)
		resultMap = getLockResult(value)
	} else if (descMap.cluster == "0001") {
		resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
	}
 
	return resultMap
}

def getLockStatus(value) {
	def status = Integer.parseInt(value, 16)
	if(status == 0 || status == 1){
		return "off"
	} else {
		return "on"
	}

}

private Map getLockResult(value) {
	log.debug 'LOCK'
	def descriptionText = "Lock is ${value}"
	return [
		name: 'lock',
		value: value,
		descriptionText: descriptionText
	]
}

private Map parseCatchAllMessage(String description) {
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    if (shouldProcessMessage(cluster)) {
        switch(cluster.clusterId) {
            case 0x0001:
            	resultMap = getBatteryResult(cluster.data.last())
                break

            case 0x0402:
                // temp is last 2 data values. reverse to swap endian
                String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
                def value = getTemperature(temp)
                resultMap = getTemperatureResult(value)
                break

			case 0x0101:
            	log.debug 'lock unknown data'
                break
        }
    }

    return resultMap
}

private Map getBatteryResult(rawValue) {
	log.debug 'Battery'
	def linkText = getLinkText(device)
    
    def result = [
    	name: 'battery'
    ]
    
	def volts = rawValue / 10
	def descriptionText
	if (volts > 6.5) {
		result.descriptionText = "${linkText} battery has too much power (${volts} volts)."
	}
	else {
		def minVolts = 4.0
    	def maxVolts = 6.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
		result.value = Math.min(100, (int) pct * 100)
		result.descriptionText = "${linkText} battery was ${result.value}%"
	}

	return result
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    boolean ignoredMessage = cluster.profileId != 0x0104 || 
        cluster.command == 0x0B ||
        cluster.command == 0x07 ||
        (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
    return !ignoredMessage
}

def getTemperature(value) {
	def celsius = Integer.parseInt(value, 16).shortValue() / 100
	if(getTemperatureScale() == "C"){
		return celsius
	} else {
		return celsiusToFahrenheit(celsius) as Integer
	}
}

private Map getTemperatureResult(value) {
	log.debug 'TEMP'
	def linkText = getLinkText(device)
	if (tempOffset) {
		def offset = tempOffset as int
		def v = value as int
		value = v + offset
	}
	def descriptionText = "${linkText} was ${value}°${temperatureScale}"
	return [
		name: 'temperature',
		value: value,
		descriptionText: descriptionText
	]
}