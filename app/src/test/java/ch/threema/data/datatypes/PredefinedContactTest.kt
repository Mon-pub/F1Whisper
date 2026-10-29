package ch.threema.data.datatypes

import ch.threema.app.BuildFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredefinedContactTest {
    @Test
    fun testPublicKey() {
        val expectedThreemaChannelPublicKey = when (BuildFlavor.current.buildEnvironment) {
            // Note that the live and sandbox environment use the same public key
            BuildFlavor.BuildEnvironment.LIVE, BuildFlavor.BuildEnvironment.SANDBOX ->
                "3a38650c681435bd1fb8498e213a2919b09388f5803aa44640e0f706326a865c"

            // There is no threema channel in onprem
            BuildFlavor.BuildEnvironment.ONPREM -> null
        }

        val expectedSupportPublicKey = when (BuildFlavor.current.buildEnvironment) {
            // Note that the live and sandbox environment use the same public key
            BuildFlavor.BuildEnvironment.LIVE, BuildFlavor.BuildEnvironment.SANDBOX ->
                "0f944d18324b2132c61d8e40afce60a0ebd701bb11e89be94972d4229e94722a"

            // There is no support chat in onprem
            BuildFlavor.BuildEnvironment.ONPREM -> null
        }

        assertEquals(
            expected = expectedThreemaChannelPublicKey,
            actual = PredefinedContact.getPredefinedContact("*THREEMA")?.publicKey?.toHexString(),
        )
        assertEquals(
            expected = expectedSupportPublicKey,
            actual = PredefinedContact.getPredefinedContact("*SUPPORT")?.publicKey?.toHexString(),
        )
    }

    @Test
    fun testSpecialContact() {
        if (BuildFlavor.current.buildEnvironment == BuildFlavor.BuildEnvironment.ONPREM) {
            // Onprem ships no predefined contacts at all
            assertNull(PredefinedContact.getPredefinedContact("*MY3DATA"))
            assertNull(PredefinedContact.getPredefinedContact("*3MAPUSH"))
            return
        }
        val my3data = PredefinedContact.getPredefinedContact("*MY3DATA")!!
        val threemapush = PredefinedContact.getPredefinedContact("*3MAPUSH")!!

        assertFalse(my3data.isSpecial)
        assertTrue(threemapush.isSpecial)
    }

    @Test
    fun testNickname() {
        if (BuildFlavor.current.buildEnvironment == BuildFlavor.BuildEnvironment.ONPREM) {
            // Onprem ships no predefined contacts at all
            assertNull(PredefinedContact.getPredefinedContact("*MY3DATA"))
            assertNull(PredefinedContact.getPredefinedContact("*3MAPUSH"))
            return
        }
        val my3data = PredefinedContact.getPredefinedContact("*MY3DATA")!!
        val threemapush = PredefinedContact.getPredefinedContact("*3MAPUSH")!!

        assertEquals("My Threema Data", my3data.nickname)
        assertEquals("Threema Push", threemapush.nickname)
    }
}
