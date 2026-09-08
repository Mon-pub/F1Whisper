package ch.threema.app.protocolsteps

import ch.threema.app.profilepicture.GroupProfilePictureUploader
import ch.threema.app.profilepicture.GroupProfilePictureUploader.GroupProfilePictureUploadResult
import ch.threema.app.profilepicture.RawProfilePicture
import ch.threema.app.services.FileService
import ch.threema.app.services.UserService
import ch.threema.app.utils.OutgoingCspGroupMessageCreator
import ch.threema.app.utils.OutgoingCspMessageHandle
import ch.threema.app.utils.OutgoingCspMessageServices
import ch.threema.app.utils.runBundledMessagesSendSteps
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.base.utils.getThreemaLogger
import ch.threema.data.models.GroupModel
import ch.threema.data.models.GroupModelData
import ch.threema.domain.models.BasicContact
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.GroupDeleteProfilePictureMessage
import ch.threema.domain.protocol.csp.messages.GroupDisappearingTimerMessage
import ch.threema.domain.protocol.csp.messages.GroupNameMessage
import ch.threema.domain.protocol.csp.messages.GroupSetProfilePictureMessage
import ch.threema.domain.protocol.csp.messages.GroupSetupMessage
import ch.threema.domain.protocol.csp.messages.groupcall.GroupCallStartMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.storage.DatabaseService
import ch.threema.storage.models.IncomingGroupSyncRequestLogModel
import java.util.Date

private val logger = getThreemaLogger("ActiveGroupStateResyncSteps")

suspend fun runActiveGroupStateResyncSteps(
    groupModel: GroupModel,
    targetMembers: Set<BasicContact>,
    preGeneratedMessageIds: PreGeneratedMessageIds,
    userService: UserService,
    groupProfilePictureUploader: GroupProfilePictureUploader,
    fileService: FileService,
    groupCallManager: GroupCallManager,
    databaseService: DatabaseService,
    outgoingCspMessageServices: OutgoingCspMessageServices,
    identityBlockedSteps: IdentityBlockedSteps,
    handle: ActiveTaskCodec,
) {
    if (groupModel.groupIdentity.creatorIdentity != userService.identity) {
        logger.error("Cannot run active group state resync steps for group with different creator")
        return
    }

    val groupModelData = groupModel.data ?: run {
        logger.error("Cannot run active group state resync steps for deleted group")
        return
    }

    if (!groupModelData.isMember) {
        logger.error("Cannot run active group state resync steps for left group")
        return
    }

    val updatedTargetMembers = targetMembers
        .filter { groupModelData.otherMembers.contains(it.identity) }
        .toSet()

    if (updatedTargetMembers.isEmpty()) {
        logger.info("No target members are group members")
        return
    }

    val currentTimestamp = Date()

    val messages = listOfNotNull(
        createSetupMessageHandle(
            preGeneratedMessageIds.firstMessageId,
            currentTimestamp,
            updatedTargetMembers,
            groupModelData,
        ),
        createNameMessageHandle(
            preGeneratedMessageIds.secondMessageId,
            currentTimestamp,
            updatedTargetMembers,
            groupModelData,
        ),
        createProfilePictureMessageHandle(
            preGeneratedMessageIds.thirdMessageId,
            currentTimestamp,
            updatedTargetMembers,
            groupModel,
            groupProfilePictureUploader,
            fileService,
        ),
        createGroupCallStartMessageHandle(
            preGeneratedMessageIds.fourthMessageId,
            currentTimestamp,
            updatedTargetMembers,
            groupModel,
            groupCallManager,
        ),
        // F1Whisper (tenth fork review, F10-05): a resync transfers the group's current state, and the disappearing
        // timer is part of that state. Omitting it left a stale member holding whatever timer it last happened to
        // hear - including a positive one for a group that has since turned the timer off - and a resync is precisely
        // the moment that is meant to be corrected. Targeted at the resync members only, and listed after setup so the
        // recipient can resolve the group before it processes the 0x95.
        createDisappearingTimerMessageHandle(
            preGeneratedMessageIds.disappearingTimerMessageId,
            currentTimestamp,
            updatedTargetMembers,
            groupModel,
            groupModelData,
            outgoingCspMessageServices,
        ),
    )

    handle.runBundledMessagesSendSteps(messages, outgoingCspMessageServices, identityBlockedSteps)

    val incomingGroupSyncRequestLogModelFactory =
        databaseService.incomingGroupSyncRequestLogModelFactory
    updatedTargetMembers.map {
        IncomingGroupSyncRequestLogModel(
            groupModel.getDatabaseId(),
            it.identity,
            currentTimestamp.time,
        )
    }.forEach {
        incomingGroupSyncRequestLogModelFactory.createOrUpdate(it)
    }
}

data class PreGeneratedMessageIds(
    val firstMessageId: MessageId,
    val secondMessageId: MessageId,
    val thirdMessageId: MessageId,
    val fourthMessageId: MessageId,
) {
    /**
     * F1Whisper (tenth fork review, F10-05): the id of the disappearing-timer control, its own and not one of the four
     * above. Derived the same way and for the same reasons as
     * [PredefinedMessageIds.disappearingTimerMessageId]; these ids are not persisted, but deriving keeps one rule
     * rather than two.
     */
    val disappearingTimerMessageId: MessageId
        get() = MessageId(firstMessageId.messageIdLong xor PredefinedMessageIds.DISAPPEARING_TIMER_ID_DERIVATION)
}

/**
 * F1Whisper (tenth fork review, F10-05): a `0x95` carrying the group's current shared timer to the resync targets.
 *
 * Sends an explicit `0` when the timer is off, so a stale member holding a positive timer converges to OFF rather than
 * keeping it. Pure state transfer: no local write, no status row, and nothing sent to members who are already in step.
 */
private fun createDisappearingTimerMessageHandle(
    messageId: MessageId,
    currentTimestamp: Date,
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    groupModelData: GroupModelData,
    outgoingCspMessageServices: OutgoingCspMessageServices,
): OutgoingCspMessageHandle? {
    if (receivers.isEmpty()) {
        return null
    }
    val timerSeconds = currentGroupDisappearingTimerSeconds(groupModel, outgoingCspMessageServices)
    logger.info("Resyncing the group disappearing timer to {} member(s): {}s", receivers.size, timerSeconds)
    return OutgoingCspMessageHandle(
        receivers,
        OutgoingCspGroupMessageCreator(
            messageId,
            currentTimestamp,
            groupModelData.groupIdentity,
        ) {
            GroupDisappearingTimerMessage().apply {
                this.timerSeconds = timerSeconds
            }
        },
    )
}

private fun createSetupMessageHandle(
    messageId: MessageId,
    currentTimestamp: Date,
    receivers: Set<BasicContact>,
    groupModelData: GroupModelData,
) = OutgoingCspMessageHandle(
    receivers,
    OutgoingCspGroupMessageCreator(
        messageId,
        currentTimestamp,
        groupModelData.groupIdentity,
    ) {
        GroupSetupMessage().apply {
            members = groupModelData.otherMembers.toTypedArray()
        }
    },
)

private fun createNameMessageHandle(
    messageId: MessageId,
    currentTimestamp: Date,
    receivers: Set<BasicContact>,
    groupModelData: GroupModelData,
) = OutgoingCspMessageHandle(
    receivers,
    OutgoingCspGroupMessageCreator(
        messageId,
        currentTimestamp,
        groupModelData.groupIdentity,
    ) {
        GroupNameMessage().apply {
            groupName = groupModelData.name ?: ""
        }
    },
)

private fun createProfilePictureMessageHandle(
    messageId: MessageId,
    currentTimestamp: Date,
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    groupProfilePictureUploader: GroupProfilePictureUploader,
    fileService: FileService,
): OutgoingCspMessageHandle? {
    val uploadResult = fileService.getGroupProfilePictureBytes(groupModel)?.let { bytes ->
        groupProfilePictureUploader.tryUploadingGroupProfilePicture(RawProfilePicture(bytes))
    }

    val groupProfilePictureMessageCreator = when (uploadResult) {
        is GroupProfilePictureUploadResult.Success -> {
            {
                GroupSetProfilePictureMessage().apply {
                    blobId = uploadResult.blobId
                    size = uploadResult.size
                    encryptionKey = uploadResult.encryptionKey
                }
            }
        }

        null -> {
            {
                GroupDeleteProfilePictureMessage()
            }
        }

        else -> {
            logger.warn("Could not upload group profile picture. Skipping profile picture message.")
            return null
        }
    }

    return OutgoingCspMessageHandle(
        receivers,
        OutgoingCspGroupMessageCreator(
            messageId,
            currentTimestamp,
            groupModel.groupIdentity,
        ) {
            groupProfilePictureMessageCreator()
        },
    )
}

private suspend fun createGroupCallStartMessageHandle(
    messageId: MessageId,
    currentTimestamp: Date,
    receivers: Set<BasicContact>,
    groupModel: GroupModel,
    groupCallManager: GroupCallManager,
): OutgoingCspMessageHandle? {
    return groupCallManager.getGroupCallStartData(groupModel)?.let {
        OutgoingCspMessageHandle(
            receivers,
            OutgoingCspGroupMessageCreator(
                messageId,
                currentTimestamp,
                groupModel,
            ) {
                GroupCallStartMessage(it)
            },
        )
    }
}
