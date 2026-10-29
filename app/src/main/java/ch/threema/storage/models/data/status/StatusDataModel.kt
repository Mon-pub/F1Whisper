package ch.threema.storage.models.data.status

import androidx.annotation.IntDef
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.JsonArrayIterator
import ch.threema.common.addObject
import ch.threema.storage.models.data.MessageDataInterface
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

private val logger = getThreemaLogger("StatusDataModel")

sealed interface StatusDataModel : MessageDataInterface {
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        VoipStatusDataModel.TYPE,
        GroupCallStatusDataModel.TYPE,
        ForwardSecurityStatusDataModel.TYPE,
        GroupStatusDataModel.TYPE,
        DisappearingStatusDataModel.TYPE,
    )
    annotation class StatusType

    @StatusType
    val type: Int

    fun getParams(): Map<String, Any?>

    companion object {

        /**
         * Deserializes a [StatusDataModel] object from a JSON representation.
         * Returns null if [jsonString] is not a valid [StatusDataModel].
         */
        @JvmStatic
        fun deserialize(jsonString: String): StatusDataModel? {
            return try {
                val iterator = JsonArrayIterator(jsonString)
                when (val type = iterator.nextInt()) {
                    VoipStatusDataModel.TYPE -> {
                        VoipStatusDataModel.createFromParams(iterator.nextPrimitiveValueMap()!!)
                    }
                    GroupCallStatusDataModel.TYPE -> {
                        GroupCallStatusDataModel.createFromParams(iterator.nextPrimitiveValueMap()!!)
                    }
                    ForwardSecurityStatusDataModel.TYPE -> {
                        ForwardSecurityStatusDataModel.createFromParams(iterator.nextPrimitiveValueMap()!!)
                    }
                    GroupStatusDataModel.TYPE -> {
                        GroupStatusDataModel.createFromParams(iterator.nextPrimitiveValueMap()!!)
                    }
                    DisappearingStatusDataModel.TYPE -> {
                        DisappearingStatusDataModel.createFromParams(iterator.nextPrimitiveValueMap()!!)
                    }
                    else -> error("Unexpected type $type")
                }
            } catch (e: Exception) {
                logger.error("Failed to deserialize status data model", e)
                null
            }
        }

        /**
         * Serializes [data] into a JSON representation
         */
        fun serialize(data: StatusDataModel): String =
            buildJsonArray {
                add(data.type)
                addObject(data.getParams())
            }
                .toString()
    }
}
