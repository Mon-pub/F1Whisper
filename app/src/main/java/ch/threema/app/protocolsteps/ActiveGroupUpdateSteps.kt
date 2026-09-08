package ch.threema.app.protocolsteps

import ch.threema.app.profilepicture.GroupProfilePictureUploader
import ch.threema.app.profilepicture.GroupProfilePictureUploader.GroupProfilePictureUploadResult
import ch.threema.app.profilepicture.ProfilePicture
import ch.threema.app.profilepicture.RawProfilePicture
import ch.threema.app.services.FileService
import ch.threema.app.utils.OutgoingCspGroupMessageCreator
import ch.threema.app.utils.OutgoingCspMessageHandle
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.utils.runBundledMessagesSendSteps
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.models.GroupIdentity
import ch.threema.data.models.GroupModel
import ch.threema.data.models.GroupModelData
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.domain.models.BasicContact
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.GroupDeleteProfilePictureMessage
import ch.threema.domain.protocol.csp.messages.GroupDisappearingTimerMessage
import ch.threema.domain.protocol.csp.messages.GroupNameMessage
import ch.threema.domain.protocol.csp.messages.GroupSetProfilePictureMessage
import ch.threema.domain.protocol.csp.messages.GroupSetupMessage
import ch.threema.domain.protocol.csp.messages.groupcall.GroupCallStartMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.ProtocolException
import java.util.Date
import kotlin.jvm.Throws

private val logger = getThreemaLogger("ActiveGroupUpdateSteps")

sealed interface ExpectedProfilePictureChange {

    /**
     * The profile picture is expected to be set.
     */
    sealed interface Set : ExpectedProfilePictureChange {
        val profilePicture: ProfilePicture

        /**
         * The profile picture is expected to be set and was uploaded to the blob server. This is the case for groups with at least one member or if
         * multi device has been active when the group was created or updated.
         */
        data class WithUpload(
            val profilePictureUploadResultSuccess: GroupProfilePictureUploadResult.Success,
        ) : Set {
            override val profilePicture: ProfilePicture
                get() = profilePictureUploadResultSuccess.profilePicture
        }

        /**
         * The profile picture is expected to be set but wasn't uploaded to the blob server as when the change was made, the group was a notes group and
         * multi device wasn't active.
         */
        data class WithoutUpload(
            override val profilePicture: ProfilePicture,
        ) : Set
    }

    /**
     * The profile picture is expected to be removed.
     */
    data object Remove : ExpectedProfilePictureChange
}

data class PredefinedMessageIds(
    val messageId1: MessageId,
    val messageId2: MessageId,
    val messageId3: MessageId,
    val messageId4: MessageId,
) {
    /**
     * F1Whisper (tenth fork review, F10-05): the id of the disappearing-timer control that carries the group's current
     * timer to a newly added member.
     *
     * **Derived rather than persisted, deliberately.** These ids exist so a retried or process-restarted task re-sends
     * the SAME message rather than a duplicate, and the four above achieve that by being serialised into the archived
     * task data. A fifth persisted field would change the encoding of `GroupCreateTaskData` and `GroupUpdateTaskData`,
     * whose own comments say any modification requires a recovery handler - and `TaskArchiverImpl` DROPS a task whose
     * decode and recovery both fail, so getting that wrong would silently discard a queued group update from a
     * v6.4.3-38 database. Deriving the id keeps both encodings byte-identical, so there is no migration to get wrong,
     * and gives the stability requirement for free: the same archived task always derives the same id.
     *
     * XOR with a fixed non-zero constant is a bijection on 64 bits, so this is provably never equal to [messageId1],
     * and it collides with the other three only with the probability any two random ids do.
     */
    val disappearingTimerMessageId: MessageId
        get() = MessageId(messageId1.messageIdLong xor DISAPPEARING_TIMER_ID_DERIVATION)

    companion object {
        /** ASCII `F1W_TMR1`, so a value seen in a log is recognisable as this derivation rather than a random id. */
        internal const val DISAPPEARING_TIMER_ID_DERIVATION: Long = 0x4631575F544D5231L

        fun random(): PredefinedMessageIds =
            PredefinedMessageIds(
                messageId1 = MessageId.random(),
                messageId2 = MessageId.random(),
                messageId3 = MessageId.random(),
                messageId4 = MessageId.random(),
            )
    }
}

/**
 * The active group update steps are executed with the *expected* changes. It is possible that the
 * group has changed in the meantime. Therefore, it is checked whether the changes are up to date.
 *
 * Note that if the group profile picture needs to be uploaded and it fails, a [ProtocolException]
 * will be thrown.
 */
@Throws(ProtocolException::class)
suspend fun runActiveGroupUpdateSteps(
    expectedProfilePictureChange: ExpectedProfilePictureChange?,
    addMembers: Set<String>,
    removeMembers: Set<String>,
    predefinedMessageIds: PredefinedMessageIds,
    groupModel: GroupModel,
    services: OutgoingCspMessageServices,
    identityBlockedSteps: IdentityBlockedSteps,
    groupCallManager: GroupCallManager,
    fileService: FileService,
    groupProfilePictureUploader: GroupProfilePictureUploader,
    handle: ActiveTaskCodec,
) {
    // This is the current snapshot that represents the truth. All changes are validated based on
    // this data before they are communicated (sent out as csp messages).
    val groupModelData = groupModel.data ?: run {
        logger.warn("Group model data not available")
        return
    }

    if (groupModelData.groupIdentity.creatorIdentity != services.userService.identity) {
        logger.error("User is not the creator of the group")
        return
    }

    if (!groupModelData.isMember) {
        logger.error("The group has been disbanded")
        return
    }

    val sanitizedMembersToRemove =
        (removeMembers - groupModelData.otherMembers).toBasicContacts(services.contactModelRepository)

    val messages = mutableListOf<OutgoingCspMessageHandle>()

    if (sanitizedMembersToRemove.isNotEmpty()) {
        messages.add(
            createGroupSetup(
                sanitizedMembersToRemove,
                emptySet(),
                groupModel.groupIdentity,
                predefinedMessageIds.messageId1,
            ),
        )
    }

    if (groupModelData.otherMembers.isNotEmpty()) {
        val members = groupModelData.otherMembers.toBasicContacts(services.contactModelRepository)

        messages.addAll(
            listOfNotNull(
                createGroupSetup(members, groupModelData, predefinedMessageIds.messageId1),
                createGroupName(members, groupModelData, predefinedMessageIds.messageId2),
                createGroupProfilePictureMessage(
                    members,
                    groupModel,
                    expectedProfilePictureChange,
                    fileService,
                    groupProfilePictureUploader,
                    predefinedMessageIds.messageId3,
                ),
                createGroupCallStartMessage(
                    addMembers.intersect(groupModelData.otherMembers)
                        .toBasicContacts(services.contactModelRepository),
                    groupModel,
                    predefinedMessageIds.messageId4,
                    groupCallManager,
                ),
                // F1Whisper (tenth fork review, F10-05): the group's CURRENT disappearing timer, to the members being
                // added and to nobody else.
                //
                // The defect: the timer is announced only when a user changes the picker, to the membership that
                // existed at that moment, so a member added afterwards was never a recipient of that message and had
                // no way to learn the policy. They defaulted to OFF and sent under it - the supplied report shows a
                // new member sending with `timer=nulls advertised=0` into a 30-second group, and converging only when
                // another member happened to change the timer later. Multi-device was off in that report, so
                // reflection cannot explain it.
                //
                // Listed AFTER setup so the recipient can resolve the group before it processes the 0x95, and sent on
                // the wire in that order because the send steps run each stage over the senders in list order.
                createGroupDisappearingTimer(
                    addMembers.intersect(groupModelData.otherMembers)
                        .toBasicContacts(services.contactModelRepository),
                    groupModel,
                    groupModelData,
                    services,
                    predefinedMessageIds.disappearingTimerMessageId,
                ),
            ),
        )
    }

    handle.runBundledMessagesSendSteps(messages, services, identityBlockedSteps)
}

private fun createGroupSetup(
    receivers: Set<BasicContact>,
    groupModelData: GroupModelData,
    messageId: MessageId,
) = createGroupSetup(
    receivers,
    groupModelData.otherMembers,
    groupModelData.groupIdentity,
    messageId,
)

private fun createGroupSetup(
    receivers: Set<BasicContact>,
    members: Set<String>,
    groupIdentity: GroupIdentity,
    messageId: MessageId,
) = OutgoingCspMessageHandle(
    receivers,
    OutgoingCspGroupMessageCreator(
        messageId,
        Date(),
        groupIdentity,
    ) {
        GroupSetupMessage().apply {
            this.members = members.toTypedArray()
        }
    },
)

/**
 * F1Whisper (tenth fork review, F10-05): a `0x95` carrying the group's current shared timer, for [receivers].
 *
 * Sends an explicit `0` when the timer is off rather than sending nothing, because a re-added member may still hold a
 * stale positive timer from before they were removed; silence would leave them counting down messages the group no
 * longer expires. The recipient's own incoming handler applies it, so this is pure state transfer: no local write, no
 * status row, and no broadcast to existing members. `setConversationTimer` is deliberately not used here - it mutates
 * local state, creates a status message and announces to the FULL current membership, which is the piggyback re-assert
 * the third review removed.
 *
 * @return `null` when there is nobody to bootstrap.
 */
private fun createGroupDisappearingTimer(
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    groupModelData: GroupModelData,
    services: OutgoingCspMessageServices,
    messageId: MessageId,
): OutgoingCspMessageHandle? {
    if (receivers.isEmpty()) {
        return null
    }
    val timerSeconds = currentGroupDisappearingTimerSeconds(groupModel, services)
    logger.info(
        "Bootstrapping the group disappearing timer to {} newly added member(s): {}s",
        receivers.size,
        timerSeconds,
    )
    return OutgoingCspMessageHandle(
        receivers,
        OutgoingCspGroupMessageCreator(
            messageId,
            Date(),
            groupModelData.groupIdentity,
        ) {
            GroupDisappearingTimerMessage().apply {
                this.timerSeconds = timerSeconds
            }
        },
    )
}

/**
 * F1Whisper (tenth fork review, F10-05): the group's ONE shared disappearing timer, in seconds, `0` when off.
 *
 * Read from [ch.threema.storage.models.group.GroupModelOld], which is where the single shared field lives; the newer
 * [GroupModel] data does not carry it. A failure to read is reported as OFF, so the worst case is a bootstrap that
 * under-states the timer and is corrected by the next genuine change, never one that invents a timer nobody set.
 */
internal fun currentGroupDisappearingTimerSeconds(
    groupModel: GroupModel,
    services: OutgoingCspMessageServices,
): Int =
    try {
        services.groupService.getById(groupModel.getDatabaseId())
            ?.disappearingMessagesTimerSeconds
            ?.takeIf { it > 0 }
            ?: 0
    } catch (e: Exception) {
        logger.warn("Could not read the group disappearing timer; bootstrapping as off", e)
        0
    }

private fun createGroupName(
    receivers: Set<BasicContact>,
    groupModelData: GroupModelData,
    messageId: MessageId,
) = OutgoingCspMessageHandle(
    receivers,
    OutgoingCspGroupMessageCreator(
        messageId,
        Date(),
        groupModelData.groupIdentity,
    ) {
        GroupNameMessage().apply {
            this.groupName = groupModelData.name
        }
    },
)

private fun createGroupProfilePictureMessage(
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    expectedProfilePictureChange: ExpectedProfilePictureChange?,
    fileService: FileService,
    groupProfilePictureUploader: GroupProfilePictureUploader,
    messageId: MessageId,
): OutgoingCspMessageHandle {
    val currentGroupProfilePicture = fileService.getGroupProfilePictureBytes(groupModel)?.let { bytes -> RawProfilePicture(bytes) }

    when (expectedProfilePictureChange) {
        is ExpectedProfilePictureChange.Set -> {
            if (currentGroupProfilePicture == null) {
                logger.info("Unexpected change: No profile picture set")
            }
        }

        is ExpectedProfilePictureChange.Remove -> {
            if (currentGroupProfilePicture != null) {
                logger.info("Unexpected change: Profile picture available")
            }
        }

        null -> Unit
    }

    if (currentGroupProfilePicture == null) {
        return getDeleteProfilePictureMessageHandle(receivers, messageId, groupModel.groupIdentity)
    }

    val groupPhotoUploadResult = getFinalGroupPhotoUploadResult(expectedProfilePictureChange, currentGroupProfilePicture, groupProfilePictureUploader)

    when (groupPhotoUploadResult) {
        is GroupProfilePictureUploadResult.Success -> Unit
        is GroupProfilePictureUploadResult.Failure.OnPremAuthTokenInvalid ->
            throw ProtocolException("Could not upload profile picture (onprem auth token invalid)")

        is GroupProfilePictureUploadResult.Failure.UploadFailed ->
            throw ProtocolException("Could not upload profile picture (upload failed)")
    }

    return getSetProfilePictureMessageHandle(
        receivers = receivers,
        messageId = messageId,
        groupIdentity = groupModel.groupIdentity,
        groupProfilePictureUploadResultSuccess = groupPhotoUploadResult,
    )
}

private fun getFinalGroupPhotoUploadResult(
    expectedProfilePictureChange: ExpectedProfilePictureChange?,
    currentGroupProfilePicture: ProfilePicture,
    groupProfilePictureUploader: GroupProfilePictureUploader,
): GroupProfilePictureUploadResult {
    // If the group profile picture has been uploaded and is still equal to the current group profile picture, then we can reuse the blob information
    // from the previous upload.
    if (expectedProfilePictureChange is ExpectedProfilePictureChange.Set.WithUpload &&
        expectedProfilePictureChange.profilePictureUploadResultSuccess.profilePicture.contentEquals(currentGroupProfilePicture)
    ) {
        return expectedProfilePictureChange.profilePictureUploadResultSuccess
    }

    // Otherwise, we just upload it again.
    return groupProfilePictureUploader.tryUploadingGroupProfilePicture(currentGroupProfilePicture)
}

private fun getSetProfilePictureMessageHandle(
    receivers: Set<BasicContact>,
    messageId: MessageId,
    groupIdentity: GroupIdentity,
    groupProfilePictureUploadResultSuccess: GroupProfilePictureUploadResult.Success,
): OutgoingCspMessageHandle {
    return OutgoingCspMessageHandle(
        receivers,
        OutgoingCspGroupMessageCreator(
            messageId,
            Date(),
            groupIdentity,
        ) {
            GroupSetProfilePictureMessage().apply {
                this.blobId = groupProfilePictureUploadResultSuccess.blobId
                this.encryptionKey = groupProfilePictureUploadResultSuccess.encryptionKey
                this.size = groupProfilePictureUploadResultSuccess.size
            }
        },
    )
}

private fun getDeleteProfilePictureMessageHandle(
    receivers: Set<BasicContact>,
    messageId: MessageId,
    groupIdentity: GroupIdentity,
): OutgoingCspMessageHandle {
    return OutgoingCspMessageHandle(
        receivers,
        OutgoingCspGroupMessageCreator(
            messageId,
            Date(),
            groupIdentity,
        ) {
            GroupDeleteProfilePictureMessage()
        },
    )
}

private suspend fun createGroupCallStartMessage(
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    messageId: MessageId,
    groupCallManager: GroupCallManager,
): OutgoingCspMessageHandle? {
    return groupCallManager.getGroupCallStartData(groupModel)?.let {
        return OutgoingCspMessageHandle(
            receivers,
            OutgoingCspGroupMessageCreator(
                messageId,
                Date(),
                groupModel.groupIdentity,
            ) {
                GroupCallStartMessage(it)
            },
        )
    }
}

private fun Iterable<String>.toBasicContacts(contactModelRepository: ContactModelRepository) =
    this.map { contactModelRepository.getByIdentity(it) }
        .mapNotNull { it?.data }
        .map { it.toBasicContact() }
        .toSet()
