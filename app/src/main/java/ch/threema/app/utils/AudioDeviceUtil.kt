package ch.threema.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import ch.threema.app.R

/**
 * AudioDevice is the names of possible audio devices that we currently
 * support.
 */
enum class AudioDevice {
    SPEAKER_PHONE,
    WIRED_HEADSET,
    EARPIECE,
    BLUETOOTH,
    NONE,
}

/**
 * Get the icon resource of this audio device type
 */
fun AudioDevice.getIconResource(): Int {
    return when (this) {
        AudioDevice.SPEAKER_PHONE -> R.drawable.ic_volume_up_outline
        AudioDevice.WIRED_HEADSET -> R.drawable.ic_headset_mic_outline
        AudioDevice.EARPIECE -> R.drawable.ic_phone_in_talk
        AudioDevice.BLUETOOTH -> R.drawable.ic_bluetooth_searching_outline
        AudioDevice.NONE -> R.drawable.ic_mic_off_outline
    }
}

/**
 * Get the string resource of this audio device type
 */
fun AudioDevice.getStringResource(): Int {
    return when (this) {
        AudioDevice.SPEAKER_PHONE -> R.string.voip_speakerphone
        AudioDevice.WIRED_HEADSET -> R.string.voip_wired_headset
        AudioDevice.EARPIECE -> R.string.voip_earpiece
        AudioDevice.BLUETOOTH -> R.string.voip_bluetooth
        AudioDevice.NONE -> R.string.voip_none
    }
}

/**
 * Check whether this device has an earpiece (most phones) or not (most tablets)
 */
fun hasEarpiece(audioManager: AudioManager, context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices.any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
    } else {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
}

/**
 * Get the default audio device of the given set
 */
fun getDefaultAudioDevice(audioDevices: Set<AudioDevice>): AudioDevice {
    return listOf(
        AudioDevice.BLUETOOTH,
        AudioDevice.WIRED_HEADSET,
        AudioDevice.EARPIECE,
    ).firstOrNull { it in audioDevices } ?: AudioDevice.SPEAKER_PHONE
}

/**
 * Find the platform communication device (API 31+) matching a logical [AudioDevice].
 *
 * Permission-free: getAvailableCommunicationDevices()/setCommunicationDevice() carry no
 * permission requirement, unlike the classic BluetoothHeadset profile proxy. This is what lets
 * Bluetooth call audio work without BLUETOOTH_CONNECT (and detects LE Audio headsets, which the
 * classic profile proxy cannot see).
 *
 * A2DP is deliberately not mapped to BLUETOOTH: it is a media-only profile and not a valid
 * setCommunicationDevice() candidate. Paired watch companions are excluded so a watch never wins
 * Bluetooth selection.
 */
@RequiresApi(Build.VERSION_CODES.S)
fun findCommunicationDevice(audioManager: AudioManager, device: AudioDevice): AudioDeviceInfo? {
    val types = when (device) {
        AudioDevice.BLUETOOTH -> intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        AudioDevice.WIRED_HEADSET -> intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        AudioDevice.EARPIECE -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        AudioDevice.SPEAKER_PHONE -> intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            // Some devices expose the loudspeaker only under the SAFE type (compile-time int constant).
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        )
        AudioDevice.NONE -> return null
    }
    return audioManager.availableCommunicationDevices.firstOrNull {
        it.type in types && !it.productName.contains(" Watch", ignoreCase = true)
    }
}
