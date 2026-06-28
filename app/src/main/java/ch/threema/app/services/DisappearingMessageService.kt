package ch.threema.app.services

import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import ch.threema.app.ThreemaApplication
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.receivers.DisappearingMessageAlarmReceiver
import ch.threema.base.utils.getThreemaLogger
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private val logger = getThreemaLogger("DisappearingMessageService")

/**
 * F1Whisper: coordinates Signal-style per-conversation disappearing messages.
 *
 * Unlike the upstream keep-messages-N-days purge (which is a WorkManager global policy), this
 * service handles short-timer per-conversation countdowns:
 *
 * - Per-conversation timer stored on ContactModel / GroupModelOld.
 * - Per-message countdown: [expireStartedAt] stamps the first-read time (incoming) or send time
 *   (outgoing); [expiresAt] = startedAt + timerSeconds * 1000.
 * - A single [AlarmManager] alarm fires at MIN(expiresAt) across both tables.
 * - On fire: hard-delete ALL overdue rows (both tables) via [MessageService.remove].
 * - Boot / app-update rearm: [purgeOverdueAndRearm] is called from [AutostartWorker] + [UpdateReceiver].
 * - Belt-and-suspenders: [enforceIfExpired] is called at every surface that reads a message model
 *   (adapters, fragments, notification builder).
 *
 * This is a singleton acquired via [getInstance].
 */
class DisappearingMessageService private constructor() {

    companion object {
        const val ACTION_FIRE = "ch.threema.app.DISAPPEARING_FIRE"
        private const val REQUEST_CODE_DISAPPEARING = 201

        @Volatile
        private var instance: DisappearingMessageService? = null

        @JvmStatic
        fun getInstance(): DisappearingMessageService =
            instance ?: synchronized(this) {
                instance ?: DisappearingMessageService().also { instance = it }
            }

        /**
         * Cheap, idempotent, @AnyThread enforcement check. Returns `true` if the model was deleted.
         *
         * - Short-circuits if `expireStartedAt == null` (clock not started yet).
         * - If `expiresAt` is null but `expireStartedAt` and `disappearingTimerSeconds` are set, lazily
         *   computes and stamps `expiresAt` (repairs a previously-missed stamp).
         * - Deletes via [MessageService.remove] if `expiresAt <= now`.
         * - An in-flight [ConcurrentHashMap] set prevents double-deletes from concurrent callers.
         *
         * Must NEVER throw; callers discard the return value if they don't care.
         *
         * This is `@JvmStatic` so Java callers can write
         * `DisappearingMessageService.enforceIfExpired(model)` without `.Companion.`.
         */
        @AnyThread
        @JvmStatic
        fun enforceIfExpired(model: AbstractMessageModel): Boolean =
            getInstance().enforceIfExpiredInternal(model)

        // E2: per-conversation throttle map (in-memory, reset on process restart).
        // Key = unique conversation id string; value = System.currentTimeMillis() of last broadcast.
        private val lastTimerBroadcastAt = ConcurrentHashMap<String, Long>()

        /** E2 throttle window: re-assert at most once per 5 minutes per conversation. */
        private const val TIMER_REBROADCAST_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Freeze the per-conversation disappearing timer onto [model] at creation time.
         *
         * Called from [ContactMessageReceiver.createLocalModel] and
         * [GroupMessageReceiver.createLocalModel] so EVERY message type (outgoing AND incoming)
         * gets the timer frozen at the moment the model is built.
         *
         * For OUTGOING messages, the clock is started immediately by [startOutgoingClock] which
         * the outgoing pipeline calls right after [saveLocalModel].
         * For INCOMING messages, [model.expireStartedAt] stays null until [markAsRead] fires.
         *
         * Rules:
         * - No-op if [convTimerSeconds] is null or <= 0 (timer off).
         * - No-op if [model.disappearingTimerSeconds] is already set (idempotent).
         * - No-op for status/system message types.
         * - Does NOT set [expireStartedAt] / [expiresAt] here.
         *
         * `@JvmStatic` so Java receivers can call without `.Companion.`.
         */
        @JvmStatic
        fun freezeTimer(model: AbstractMessageModel, convTimerSeconds: Int?) {
            if (convTimerSeconds == null || convTimerSeconds <= 0) return
            if (model.disappearingTimerSeconds != null) return
            when (model.type) {
                MessageType.STATUS,
                MessageType.VOIP_STATUS,
                MessageType.GROUP_CALL_STATUS,
                MessageType.FORWARD_SECURITY_STATUS,
                MessageType.GROUP_STATUS,
                MessageType.DISAPPEARING_STATUS -> return
                else -> { /* proceed */ }
            }
            model.disappearingTimerSeconds = convTimerSeconds
        }

        /**
         * Start the outgoing countdown clock on [model].
         *
         * Called after [saveLocalModel] on every outgoing content-message send path so the
         * clock begins at the moment the message is saved (Signal EXPIRES_FROM_SEND_TIME parity).
         *
         * No-op if the timer was not frozen ([disappearingTimerSeconds] == null/0) or if
         * the clock is already started ([expireStartedAt] != null).
         *
         * `@JvmStatic` for Java callers.
         */
        @JvmStatic
        fun startOutgoingClock(model: AbstractMessageModel) {
            val timerSecs = model.disappearingTimerSeconds ?: return
            if (timerSecs <= 0) return
            if (model.expireStartedAt != null) return
            val now = System.currentTimeMillis()
            model.expireStartedAt = now
            model.expiresAt = now + timerSecs * 1000L
        }

        /**
         * Convenience for Java outgoing send paths: starts the clock, persists the stamp to the
         * database via [MessageService.save], and re-arms the alarm.
         *
         * Call ONCE per outgoing message, right after [receiver.saveLocalModel] has written the
         * initial row (so the model already has a primary key).  No-op if the timer is not active
         * on the model or the clock was already started.
         *
         * `@JvmStatic` for Java callers (MessageServiceImpl).
         */
        @JvmStatic
        fun armOutgoingClock(model: AbstractMessageModel) {
            startOutgoingClock(model)
            if (model.expireStartedAt == null) return   // clock still not started → timer off
            try {
                val sm = ThreemaApplication.getServiceManager() ?: return
                sm.messageService.save(model)
                getInstance().rescheduleNextAlarm()
                logger.debug("Disappearing: armed outgoing clock uid={} expiresAt={}", model.uid, model.expiresAt)
            } catch (e: Exception) {
                logger.warn("Disappearing: armOutgoingClock save failed uid={}", model.uid, e)
            }
        }

        /**
         * [Deprecated alias] kept so Java call sites added in fix round 2a still compile while
         * the rename to [freezeTimer] propagates. Delegates directly to [freezeTimer].
         *
         * `@JvmStatic` for Java callers.
         */
        @JvmStatic
        fun stampOutgoing(model: AbstractMessageModel, convTimerSeconds: Int?) =
            freezeTimer(model, convTimerSeconds)

        /**
         * Pure predicate — returns `true` if [model] is currently overdue for deletion.
         *
         * Applies the same expiry math as [enforceIfExpired] but NEVER mutates or deletes anything.
         * ui-builder uses this during list-view bind to decide whether to hide the row and post an
         * async delete; [enforceIfExpired] then does the actual removal.
         *
         * `@JvmStatic` for Java callers.
         */
        @AnyThread
        @JvmStatic
        fun isExpired(model: AbstractMessageModel): Boolean {
            return try {
                val startedAt = model.expireStartedAt ?: return false
                val timerSecs = model.disappearingTimerSeconds
                val expiresAt = model.expiresAt
                    ?: if (timerSecs != null && timerSecs > 0) startedAt + timerSecs * 1000L
                       else return false
                expiresAt <= System.currentTimeMillis()
            } catch (e: Exception) {
                false
            }
        }

        /**
         * E2: returns true if we should re-broadcast the timer for [conversationKey] now.
         * Throttled to once per [TIMER_REBROADCAST_INTERVAL_MS] per conversation per process life.
         */
        @JvmStatic
        fun shouldRebroadcastTimer(conversationKey: String): Boolean {
            val last = lastTimerBroadcastAt[conversationKey] ?: return true
            return System.currentTimeMillis() - last >= TIMER_REBROADCAST_INTERVAL_MS
        }

        /**
         * E2: record that we just broadcast the timer for [conversationKey].
         */
        @JvmStatic
        fun recordTimerBroadcast(conversationKey: String) {
            lastTimerBroadcastAt[conversationKey] = System.currentTimeMillis()
        }
    }

    private val scheduler = AlarmScheduler(
        requestCode = REQUEST_CODE_DISAPPEARING,
        buildIntent = { ctx ->
            Intent(ctx, DisappearingMessageAlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
        },
    )

    /**
     * Set of message UIDs whose deletion is already in-flight — guards against double-delete races
     * when [enforceIfExpired] is called concurrently from multiple threads.
     */
    private val inFlight: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    // -------------------------------------------------------------------------
    // Public API consumed by the tasks, incoming handler, and markAsRead
    // -------------------------------------------------------------------------

    /**
     * Persist the per-conversation timer on [receiver] and enqueue the outgoing
     * [DisappearingTimerMessage] / [GroupDisappearingTimerMessage] to all members.
     * Also inserts a local DISAPPEARING_STATUS status message in the conversation.
     *
     * Called from UI when the user changes the timer via the duration picker.
     */
    fun setConversationTimer(receiver: MessageReceiver<*>, seconds: Int) {
        val serviceManager = ThreemaApplication.getServiceManager() ?: run {
            logger.warn("ServiceManager unavailable; cannot set conversation timer")
            return
        }
        val messageService = try {
            serviceManager.messageService
        } catch (e: Exception) {
            logger.error("Could not get message service", e)
            return
        }
        when (receiver.type) {
            MessageReceiver.Type_CONTACT -> {
                val contactReceiver = receiver as ch.threema.app.messagereceiver.ContactMessageReceiver
                val contact = contactReceiver.contact ?: return
                contact.setDisappearingMessagesTimerSeconds(if (seconds > 0) seconds else null)
                serviceManager.databaseService.contactModelFactory.createOrUpdate(contact)

                // Insert local status message
                messageService.createDisappearingStatus(receiver, null, seconds)

                // Enqueue outgoing control message
                serviceManager.taskManager.schedule(
                    ch.threema.app.tasks.OutgoingDisappearingTimerMessageTask(
                        toIdentity = contact.identity,
                        timerSeconds = seconds,
                    )
                )
            }

            MessageReceiver.Type_GROUP -> {
                val groupReceiver = receiver as ch.threema.app.messagereceiver.GroupMessageReceiver
                val group = groupReceiver.group ?: return
                group.setDisappearingMessagesTimerSeconds(if (seconds > 0) seconds else null)
                serviceManager.databaseService.groupModelFactory.update(group)

                // Insert local status message
                messageService.createDisappearingStatus(receiver, null, seconds)

                // Enqueue outgoing group control message
                val members = serviceManager.groupService.getGroupMemberIdentities(group)
                    ?.filterNot { it == serviceManager.userService.identity }
                    ?.toSet()
                    ?: emptySet()
                serviceManager.taskManager.schedule(
                    ch.threema.app.tasks.OutgoingGroupDisappearingTimerMessageTask(
                        groupId = group.apiGroupId,
                        creatorIdentity = group.creatorIdentity,
                        recipientIdentities = members,
                        timerSeconds = seconds,
                    )
                )
            }

            else -> logger.warn("setConversationTimer called with unsupported receiver type {}", receiver.type)
        }
    }

    // -------------------------------------------------------------------------
    // E2: piggyback timer re-assert on outgoing send
    // -------------------------------------------------------------------------

    /**
     * E2 — when the user sends a message in a conversation with an active disappearing timer,
     * silently re-broadcast the timer value to the peer (throttled to once per 5 min per
     * conversation) so late-joiners or missed-sync peers stay in lock-step.
     *
     * Schedules the same outgoing task as [setConversationTimer] but does NOT insert a local
     * status row (suppressed by passing a timerSeconds equal to what is already persisted, and
     * [IncomingDisappearingTimerTask] / [IncomingGroupDisappearingTimerTask] suppress duplicate
     * status rows when the received value matches the already-set conversation timer).
     *
     * No-op when: timer is off, throttle window has not elapsed, or ServiceManager unavailable.
     */
    fun piggybackTimerReassert(receiver: MessageReceiver<*>) {
        val conversationKey = receiver.uniqueIdString ?: return
        if (!shouldRebroadcastTimer(conversationKey)) return

        val serviceManager = ThreemaApplication.getServiceManager() ?: return
        val taskManager = try { serviceManager.taskManager } catch (e: Exception) { return }

        when (receiver.type) {
            MessageReceiver.Type_CONTACT -> {
                val contactReceiver = receiver as? ch.threema.app.messagereceiver.ContactMessageReceiver ?: return
                val contact = contactReceiver.contact ?: return
                val timerSecs = contact.disappearingMessagesTimerSeconds ?: return
                if (timerSecs <= 0) return
                recordTimerBroadcast(conversationKey)
                taskManager.schedule(
                    ch.threema.app.tasks.OutgoingDisappearingTimerMessageTask(
                        toIdentity = contact.identity,
                        timerSeconds = timerSecs,
                    )
                )
                logger.debug("E2: piggybacking timer re-assert to {} ({}s)", contact.identity, timerSecs)
            }

            MessageReceiver.Type_GROUP -> {
                // F1Whisper GROUP convergence fix (Option X): groups NEVER piggyback. The group
                // disappearing timer is a single shared field that converges on the last genuine
                // user change (broadcast once from setConversationTimer). A per-member re-assert
                // here re-injected each member's stale value on every send → endless 30↔300↔OFF
                // ping-pong. It is removed. 0x95 is durable + server-queued, so an offline member
                // still catches up on reconnect without any client re-assert.
            }

            else -> { /* distribution list etc — no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // Alarm engine
    // -------------------------------------------------------------------------

    /**
     * Re-arm the alarm for the earliest pending expiry across both message tables.
     * Safe to call from any thread.
     */
    fun rescheduleNextAlarm(context: Context = ThreemaApplication.getAppContext()) {
        scheduler.rescheduleNextAlarm(context) {
            val db = ThreemaApplication.getServiceManager()?.databaseService ?: return@rescheduleNextAlarm null
            val t1 = db.messageModelFactory.earliestExpiry
            val t2 = db.groupMessageModelFactory.earliestExpiry
            when {
                t1 != null && t2 != null -> minOf(t1, t2)
                else -> t1 ?: t2
            }
        }
    }

    /**
     * Delete ALL messages overdue across both tables and re-arm the alarm.
     * Called from [DisappearingMessageAlarmReceiver] on a worker thread.
     */
    @WorkerThread
    fun fireDue() {
        val serviceManager = ThreemaApplication.getServiceManager() ?: run {
            logger.warn("ServiceManager unavailable in fireDue; will re-arm retry")
            // re-arm a short retry so the rows don't get stuck
            rescheduleNextAlarm()
            return
        }
        val messageService = try {
            serviceManager.messageService
        } catch (e: Exception) {
            logger.warn("Cannot get MessageService in fireDue; will retry later", e)
            rescheduleNextAlarm()
            return
        }
        val db = serviceManager.databaseService
        val now = System.currentTimeMillis()

        // Sweep ALL overdue rows (not just the MIN) so a missed row is caught on every fire.
        val contactDue = db.messageModelFactory.getMessagesExpiredBefore(now)
        val groupDue = db.groupMessageModelFactory.getMessagesExpiredBefore(now)

        (contactDue + groupDue).forEach { model ->
            try {
                messageService.remove(model, false)
                logger.info("Disappearing: deleted overdue message uid={}", model.uid)
            } catch (e: Exception) {
                logger.error("Could not delete overdue disappearing message uid={}", model.uid, e)
            }
        }

        rescheduleNextAlarm()
    }

    /**
     * Startup sweep: purge every message whose expiresAt has already passed, then re-arm.
     * Recovers the boot-gap / process-kill / MIUI alarm-drop / missed-reschedule scenario.
     * Call from [AutostartWorker] and [UpdateReceiver] and on warm-start/master-key-unlock.
     */
    @WorkerThread
    fun purgeOverdueAndRearm() {
        val serviceManager = ThreemaApplication.getServiceManager() ?: run {
            logger.warn("ServiceManager unavailable in purgeOverdueAndRearm; will retry on next boot")
            return
        }
        val messageService = try {
            serviceManager.messageService
        } catch (e: Exception) {
            logger.warn("Cannot get MessageService in purgeOverdueAndRearm", e)
            return
        }
        val db = serviceManager.databaseService
        val now = System.currentTimeMillis()

        // Backfill missing expiresAt for started-but-unstamped messages, then sweep.
        // We rely on the factory to query only started rows for efficiency.
        val contactDue = db.messageModelFactory.getMessagesExpiredBefore(now)
        val groupDue = db.groupMessageModelFactory.getMessagesExpiredBefore(now)

        (contactDue + groupDue).forEach { model ->
            try {
                messageService.remove(model, false)
                logger.info("Disappearing startup sweep: deleted uid={}", model.uid)
            } catch (e: Exception) {
                logger.error("Disappearing startup sweep: error deleting uid={}", model.uid, e)
            }
        }

        rescheduleNextAlarm()
    }

    // -------------------------------------------------------------------------
    // Belt-and-suspenders funnel
    // -------------------------------------------------------------------------

    @AnyThread
    private fun enforceIfExpiredInternal(model: AbstractMessageModel): Boolean {
        try {
            // 1. Only act on messages with a started countdown.
            if (model.expireStartedAt == null) return false

            // 2. Lazily compute expiresAt if missing (repair path).
            if (model.expiresAt == null) {
                val timerSecs = model.disappearingTimerSeconds ?: return false
                if (timerSecs <= 0) return false
                model.expiresAt = model.expireStartedAt!! + timerSecs * 1000L
                // Save the repaired stamp so other callers see it.
                val serviceManager = ThreemaApplication.getServiceManager()
                serviceManager?.messageService?.save(model)
            }

            // 3. Check if expired.
            val expiresAt = model.expiresAt ?: return false
            if (expiresAt > System.currentTimeMillis()) return false

            // 4. Guard against double-delete.
            val uid = model.uid ?: return false
            if (!inFlight.add(uid)) return false

            // 5. Hard delete.
            try {
                val serviceManager = ThreemaApplication.getServiceManager() ?: return false
                serviceManager.messageService.remove(model, false)
                logger.info("Disappearing enforceIfExpired: deleted uid={}", uid)
                return true
            } finally {
                inFlight.remove(uid)
            }
        } catch (e: Exception) {
            logger.error("enforceIfExpired: unexpected error for uid={}", model.uid, e)
            return false
        }
    }
}
