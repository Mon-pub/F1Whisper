package ch.threema.app.services.messageplayer

import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.DistributionListMessageModel
import ch.threema.storage.models.MessageModel
import ch.threema.storage.models.group.GroupMessageModel

/**
 * F1Whisper (twelfth fork review, F12-01): the process-wide identity a message carries in the listen-once registries
 * ([ListenOnceBurnBarrier], [ListenOnceOwnership], [ListenOnceBurnRegistry]).
 *
 * **The defect this closes.** All three registries used to be keyed by the table-local integer row id alone. Contact,
 * group and distribution-list messages live in three separate SQLite tables with three separate AUTOINCREMENT
 * sequences, so equal ids across those namespaces are perfectly normal - and a registry hit for "id 47" said nothing
 * about WHICH message with id 47 it described. For the burn barrier that was data loss: `AudioMessagePlayer.open`
 * consulted the barrier before asking whether the current model was even a listen-once message, and on a collision it
 * re-drove the burn with the unrelated model in hand, whose own table then received the consumed-metadata write and
 * whose own UID keyed the file removal. An ordinary audio message could be permanently marked consumed and lose its
 * media because a listen-once message in another table shared its integer id. Ownership and the animation registry
 * had the same collision with smaller blast: a false cross-message playback refusal (and a suppressed repair burn),
 * and a burn burst on an unrelated bubble.
 *
 * The identity is the model's namespace (its concrete table) plus its UID - the same UID that already keys the
 * message file store globally, generated as a random UUID at creation. A model that has no UID yet (unsaved, or a
 * bare test model) falls back to its row id, still disambiguated by the namespace. Value semantics: two instances
 * derived from the same message are equal, which is all the registries need.
 */
data class ListenOnceMessageIdentity(val namespace: String, val key: String) {

    override fun toString(): String = "$namespace/$key"

    companion object {
        /**
         * The identity of [messageModel]. Total: never null, never throws, and never collides across the message
         * tables - an unknown subtype keys by its class name, which still cannot alias another namespace.
         */
        @JvmStatic
        fun of(messageModel: AbstractMessageModel): ListenOnceMessageIdentity {
            val namespace = when (messageModel) {
                is GroupMessageModel -> "group"
                is DistributionListMessageModel -> "distribution-list"
                is MessageModel -> "contact"
                else -> messageModel.javaClass.name
            }
            val uid = messageModel.uid
            val key = if (uid.isNullOrEmpty()) "id:${messageModel.id}" else "uid:$uid"
            return ListenOnceMessageIdentity(namespace, key)
        }
    }
}
