package dev.jamesrich.sofaking.data

/**
 * How this installation names itself to the server: [platform] is a short token such as `android-tv`, `ios` or
 * `desktop`, and [deviceName] what the viewer would call the device. Both only label the device in the admin portal.
 */
data class ClientInfo(val platform: String = "", val deviceName: String = "")
