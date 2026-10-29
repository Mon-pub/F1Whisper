package ch.threema.app.utils

import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AudioDeviceUtilTest {

    private fun deviceInfo(deviceType: Int, name: String = "Device"): AudioDeviceInfo =
        mockk {
            every { type } returns deviceType
            every { productName } returns name
        }

    private fun audioManagerWith(vararg devices: AudioDeviceInfo): AudioManager =
        mockk {
            every { availableCommunicationDevices } returns devices.toList()
        }

    @Test
    fun `bluetooth SCO headset is selected for BLUETOOTH`() {
        val sco = deviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pixel Buds")
        assertSame(sco, findCommunicationDevice(audioManagerWith(sco), AudioDevice.BLUETOOTH))
    }

    @Test
    fun `LE Audio headset is selected for BLUETOOTH`() {
        val ble = deviceInfo(AudioDeviceInfo.TYPE_BLE_HEADSET, "LE Buds")
        assertSame(ble, findCommunicationDevice(audioManagerWith(ble), AudioDevice.BLUETOOTH))
    }

    @Test
    fun `paired watch is never selected for BLUETOOTH`() {
        val watch = deviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Galaxy Watch5")
        assertNull(findCommunicationDevice(audioManagerWith(watch), AudioDevice.BLUETOOTH))
    }

    @Test
    fun `A2DP is not a BLUETOOTH communication candidate`() {
        val a2dp = deviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BT Speaker")
        assertNull(findCommunicationDevice(audioManagerWith(a2dp), AudioDevice.BLUETOOTH))
    }

    @Test
    fun `earpiece is selected for EARPIECE`() {
        val earpiece = deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val am = audioManagerWith(earpiece, deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertSame(earpiece, findCommunicationDevice(am, AudioDevice.EARPIECE))
    }

    @Test
    fun `builtin speaker is selected for SPEAKER_PHONE`() {
        val speaker = deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertSame(speaker, findCommunicationDevice(audioManagerWith(speaker), AudioDevice.SPEAKER_PHONE))
    }

    @Test
    fun `safe builtin speaker is accepted for SPEAKER_PHONE`() {
        val safe = deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        assertSame(safe, findCommunicationDevice(audioManagerWith(safe), AudioDevice.SPEAKER_PHONE))
    }

    @Test
    fun `usb headset is selected for WIRED_HEADSET`() {
        val usb = deviceInfo(AudioDeviceInfo.TYPE_USB_HEADSET)
        assertSame(usb, findCommunicationDevice(audioManagerWith(usb), AudioDevice.WIRED_HEADSET))
    }

    @Test
    fun `NONE never resolves to a device`() {
        val am = audioManagerWith(deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNull(findCommunicationDevice(am, AudioDevice.NONE))
    }

    @Test
    fun `absent device type resolves to null`() {
        val am = audioManagerWith(deviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNull(findCommunicationDevice(am, AudioDevice.BLUETOOTH))
    }
}
