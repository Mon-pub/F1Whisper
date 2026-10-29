package ch.threema.storage.models.data.status

import kotlin.test.Test
import kotlin.test.assertNull

class StatusDataModelTest {
    @Test
    fun `deserialize invalid status model`() {
        // invalid json
        assertIsInvalidStatusDataModel("")
        // not a JSON array
        assertIsInvalidStatusDataModel("{}")
        // missing params object
        assertIsInvalidStatusDataModel("""[1]""")
        // unknown type (5 is the fork's disappearing-timer status and is a valid type)
        assertIsInvalidStatusDataModel("""[99,{}]""")
    }

    private fun assertIsInvalidStatusDataModel(input: String) {
        assertNull(
            StatusDataModel.deserialize(input),
        )
    }
}
