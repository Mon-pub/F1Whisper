package ch.threema.app.services

import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import ch.threema.app.ThreemaApplication
import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.receivers.DisappearingMessageAlarmReceiver
import ch.threema.base.utils.getThreemaLogger
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageType
import ch.threema.storage.models.ballot.BallotModel
import ch.threema.storage.models.group.GroupMessageModel
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

        /**
         * How many broken rows one repair pass will look at, per table. The scan has no supporting
         * index, so it is bounded rather than unbounded; a device with more damage than this repairs
         * the rest on the next launch, which is the right trade against stalling a cold start.
         */
        private const val REPAIR_SCAN_LIMIT = 1000

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

        /**
         * Freeze the SHARED per-conversation disappearing timer onto [model] at creation time.
         *
         * Called from [ContactMessageReceiver.createLocalModel] and
         * [GroupMessageReceiver.createLocalModel] so EVERY message type gets a timer frozen at the
         * moment the model is built. For an OUTGOING message that value is authoritative: it is the
         * policy the sender advertises on the wire and counts its own copy down against.
         *
         * For an INCOMING message it is only a provisional placeholder. The receiver has no way to
         * know the sender's policy at `createLocalModel` time, so it stamps the local conversation
         * timer and [MessageServiceImpl.processIncomingContactMessage] / `…GroupMessage` then
         * OVERWRITE it with the value the sender actually advertised, via [freezeIncomingTimer]. The
         * two are no longer the same value by design — see [DisappearingFreezeDecision].
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
            if (isTimerExemptType(model)) return
            model.disappearingTimerSeconds = convTimerSeconds
        }

        /**
         * F1Whisper: freeze the timer the SENDER advertised onto an INCOMING [model], AUTHORITATIVELY.
         *
         * The sibling of [freezeTimer], and deliberately NOT a relaxation of it. [freezeTimer] is
         * idempotent and no-ops on `<= 0`, which is exactly right for the outgoing callers that depend
         * on "freeze once, at compose time, never re-freeze". Those same two properties make it unable
         * to express either half of the incoming decision:
         *
         * - it cannot store `0`, so it cannot record "the sender explicitly said OFF";
         * - it cannot overwrite, so it cannot correct the provisional local-timer stamp that
         *   `createLocalModel` already put on the model.
         *
         * Both are required. A recipient whose own timer reads 30 s must still keep a message the
         * sender sent with the timer OFF, and a recipient whose own timer reads OFF must still expire a
         * message the sender sent at 30 s — that second case is the reported bug this wave exists to fix.
         *
         * It also owns the countdown stamps, because the freeze and the clock cannot be decided apart:
         * [markAsRead] may already have run and, seeing no timer, declined to start a countdown it can
         * never revisit. Whichever way the interleaving falls, the model leaves this function in a state
         * that enforces the SENDER's policy — clock started, re-derived, or cancelled. The caller owns
         * only the persistence and the alarm re-arm.
         *
         * @param model an INCOMING message model, and one **freshly read from the database** — see the
         *   `expireStartedAt` handling in the body, which is only correct against current state.
         * @param resolvedTimerSeconds the output of
         *   [DisappearingFreezeDecision.resolveIncomingTimer]: `null` = store nothing, `0` = explicit
         *   sender OFF, `> 0` = the sender's timer.
         * @return `true` if [model] was mutated and therefore needs saving.
         */
        @JvmStatic
        fun freezeIncomingTimer(model: AbstractMessageModel, resolvedTimerSeconds: Int?): Boolean {
            if (resolvedTimerSeconds == null) return false
            // An OUTGOING message's timer is this device's own, frozen at compose time, already
            // advertised on the wire and already counting down — nothing arriving from the network may
            // rewrite it. Both current callers are identity-scoped and never pass one, so this guard
            // makes the invariant local rather than dependent on every future caller staying correct.
            if (model.isOutbox) return false
            if (isTimerExemptType(model)) return false
            if (model.disappearingTimerSeconds == resolvedTimerSeconds) return false
            model.disappearingTimerSeconds = resolvedTimerSeconds
            // If the countdown had already begun, its deadline was derived from the PROVISIONAL timer
            // that createLocalModel stamped — a concurrent markAsRead can win the race to this row,
            // because the new-message listener fires before the receive path reaches the freeze.
            // Re-derive the deadline from the sender's value instead of leaving timer and expiresAt
            // inconsistent, and clear it outright when the sender said OFF, so enforcement follows the
            // sender's policy in EVERY interleaving. Callers must pass a freshly-read model, otherwise
            // this reads a stale expireStartedAt and the correction silently does not happen.
            val startedAt = model.expireStartedAt
            if (startedAt != null) {
                if (resolvedTimerSeconds > 0) {
                    model.expiresAt = startedAt + resolvedTimerSeconds * 1000L
                } else {
                    model.expireStartedAt = null
                    model.expiresAt = null
                }
            } else if (model.isRead && resolvedTimerSeconds > 0) {
                // The message is already read but carries no countdown, so [MessageServiceImpl.markAsRead]
                // ran BEFORE this freeze and declined to start one: at that moment the model held no timer
                // and the local conversation timer was off. It can never run again — `MessageUtil.canMarkAsRead`
                // requires `!isRead` — and `enforceIfExpired` short-circuits on a null `expireStartedAt`. So
                // unless the clock is started right here, the sender's timer never begins and the message is
                // kept FOREVER: the Finding 3 outcome reached by a different route, and on device
                // indistinguishable from the original bug.
                //
                // The clock starts at the moment the message was actually read, not now. Using now would hand
                // the recipient a longer window than the sender allowed, by exactly the receive-path delay —
                // which for an image message includes the whole `saveMedia` disk write.
                val startsAt = model.readAt?.time ?: System.currentTimeMillis()
                model.expireStartedAt = startsAt
                model.expiresAt = startsAt + resolvedTimerSeconds * 1000L
            }
            return true
        }

        /**
         * Status and system rows are never disappearing messages, whichever direction they came from.
         *
         * `internal` rather than `private`: both callers are `@JvmStatic`, so they compile to statics on the
         * outer class and a private companion member would force the compiler to synthesise an accessor
         * (lint `SyntheticAccessor`). Visibility is still module-scoped.
         */
        internal fun isTimerExemptType(model: AbstractMessageModel): Boolean =
            when (model.type) {
                MessageType.STATUS,
                MessageType.VOIP_STATUS,
                MessageType.GROUP_CALL_STATUS,
                MessageType.FORWARD_SECURITY_STATUS,
                MessageType.GROUP_STATUS,
                MessageType.DISAPPEARING_STATUS,
                -> true

                else -> false
            }

        // F1Whisper (fifth fork review, F5-06): `startOutgoingClock`, `armOutgoingClock` and `armOutgoingClockIfSent`
        // lived here and are GONE.
        //
        // They were the second write. `MessageServiceImpl.updateOutgoingMessageState` persisted the terminal state, left
        // its synchronized block, and only then called arming, which stamped `System.currentTimeMillis()` and saved
        // again. A process death between the two left a sent message with a terminal state and no deadline -
        // permanently, because the startup repair pass deliberately refuses outgoing rows with no start. And reading the
        // wall clock at arming time discarded the authoritative timestamp the transition carried, so a `SENT` update
        // reflected from another device started a full interval from the moment the reflection was processed.
        //
        // The countdown is now decided by [OutgoingClockDecision.resolveStart] from the TRANSITION's timestamp and
        // written in the same conditional row update as the state itself, by
        // `MessageServiceImpl.applyOutgoingStateTransition`. There is no arming entry point left to call from the wrong
        // place, which is the point.

        /**
         * [Deprecated alias] kept so Java call sites added in fix round 2a still compile while
         * the rename to [freezeTimer] propagates. Delegates directly to [freezeTimer].
         *
         * The name is historical: the callers pass the shared conversation timer and stamp EVERY
         * model they build, inbound included — nothing here is outgoing-only.
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
                    ?: if (timerSecs != null && timerSecs > 0) {
                        startedAt + timerSecs * 1000L
                    } else {
                        return false
                    }
                expiresAt <= System.currentTimeMillis()
            } catch (e: Exception) {
                false
            }
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
                val previousSeconds = contact.disappearingMessagesTimerSeconds
                contact.setDisappearingMessagesTimerSeconds(if (seconds > 0) seconds else null)
                serviceManager.databaseService.contactModelFactory.createOrUpdate(contact)
                logger.info(
                    "Disappearing: local timer change for contact {}: {}s -> {}s (advertising 0x85)",
                    contact.identity,
                    previousSeconds,
                    seconds,
                )

                // Insert local status message
                messageService.createDisappearingStatus(receiver, null, seconds)

                // Enqueue outgoing control message
                serviceManager.taskManager.schedule(
                    ch.threema.app.tasks.OutgoingDisappearingTimerMessageTask(
                        toIdentity = contact.identity,
                        timerSeconds = seconds,
                    ),
                )
            }

            MessageReceiver.Type_GROUP -> {
                val groupReceiver = receiver as ch.threema.app.messagereceiver.GroupMessageReceiver
                val group = groupReceiver.group ?: return
                val previousSeconds = group.disappearingMessagesTimerSeconds
                group.setDisappearingMessagesTimerSeconds(if (seconds > 0) seconds else null)
                serviceManager.databaseService.groupModelFactory.update(group)
                logger.info(
                    "Disappearing: local timer change for group {}: {}s -> {}s (advertising 0x95)",
                    group.apiGroupId,
                    previousSeconds,
                    seconds,
                )

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
                    ),
                )
            }

            else -> logger.warn("setConversationTimer called with unsupported receiver type {}", receiver.type)
        }
    }

    // -------------------------------------------------------------------------
    // NOTE: there is deliberately NO piggyback timer re-assert, for 1:1 or for groups.
    //
    // A re-assert read the SHARED conversation field, so it re-broadcast a value the sender had
    // merely ADOPTED, never chosen. Combined with the peer column only ever being written by an
    // INCOMING 0x85 (advertising never seeds your own), that destroyed a user's OFF:
    //   A sets 30 → B adopts 30 → A turns OFF (A's peer column is still unset, B never sent an
    //   0x85) → B sends any message → B re-asserts the 30 it merely adopted → A treats it as the
    //   peer's first advertisement and re-adopts 30. The user's OFF is silently gone, and A then
    //   re-asserts 30 back to B, so the pair converges on a value neither user chose.
    // The re-assert also bailed out when the timer was off, so a lost 0x85(0) had no recovery path
    // while every positive value did — biasing the whole system toward "on".
    //
    // Deleting it restores the invariant that makes convergence hold, the same one the group fix
    // established in v6.4.3-29 (user-confirmed 4-way on device): the ONLY 0x85/0x95 on the wire are
    // genuine user changes, broadcast once from [setConversationTimer]. Nothing adopted is ever
    // re-injected. Recovery for an offline peer comes from the control message being durable and
    // server-queued with at-least-once delivery, not from a client re-assert — which never
    // recovered OFF anyway.
    //
    // An un-updated v6.4.3-37 peer still re-asserts every 5 minutes, and at-least-once delivery
    // still means the same 0x85 can arrive twice. Neither is filtered on receive: an adopt gate that
    // could tell them from a genuine change would need a logical clock (Signal's expireTimerVersion),
    // and the gate that was tried instead of one dropped real changes (see
    // [DisappearingTimerConvergence]).
    //
    // An earlier revision of this note claimed the "frozen 4-byte 0x85 body cannot carry" such a
    // clock. That is FALSE, and the claim is on record as having produced a wrong build: 0x85/0x95 is
    // [DisappearingTimerMessage], OUR OWN fork type, decoded purely in Kotlin by MessageCoder with no
    // libthreema or proto involvement. Its body is frozen only by our already-shipped -26..-37
    // clients, and a length-based backward-compatible decode could extend it whenever we choose to.
    //
    // Adding the version field was DEFERRED by decision, not blocked. Since every content message now
    // carries the sender's own per-message timer in its encrypted metadata (see
    // [DisappearingFreezeDecision]), what survives here is cosmetic: a stale re-assert can still flip
    // the PICKER, but it can no longer un-time a received message or change what the peer's messages
    // expire at. This is the residual groups have accepted since v6.4.3-29, and it is what the
    // server-side mandatory-update floor exists to close.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Alarm engine
    // -------------------------------------------------------------------------

    /**
     * Re-arm the alarm for the earliest pending expiry across both message tables.
     * Safe to call from any thread.
     */
    fun rescheduleNextAlarm(context: Context = ThreemaApplication.getAppContext()) {
        scheduler.rescheduleNextAlarm(context) {
            // Unavailable, NOT None. Without the service graph we know nothing about what is pending,
            // and reporting an empty queue here used to cancel the alarm outright and leave the whole
            // engine disarmed until the next boot — including on the fireDue path whose log line
            // claimed it was arming a retry. See AlarmPlanDecision.
            val db = ThreemaApplication.getServiceManager()?.databaseService
                ?: return@rescheduleNextAlarm AlarmTarget.Unavailable
            val t1 = db.messageModelFactory.earliestExpiry
            val t2 = db.groupMessageModelFactory.earliestExpiry
            val earliest = when {
                t1 != null && t2 != null -> minOf(t1, t2)
                else -> t1 ?: t2
            }
            earliest?.let { AlarmTarget.At(it) } ?: AlarmTarget.None
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
                if (deleteExpiredMessage(serviceManager, messageService, model, now)) {
                    logger.info("Disappearing: deleted overdue message uid={}", model.uid)
                }
            } catch (e: Exception) {
                logger.error("Could not delete overdue disappearing message uid={}", model.uid, e)
            }
        }

        rescheduleNextAlarm()
    }

    /**
     * Startup pass: repair rows whose countdown can never reach a deadline, then sweep and re-arm.
     *
     * Split from [purgeOverdueAndRearm] because the two have very different costs. The sweep is an
     * indexed range read and is cheap enough to run every time a chat is opened; the repair scan has
     * no index that suits it ([MessageModelFactory.getRepairableExpiryCandidates] explains why) and
     * belongs on the boot/app-update path, where one bounded scan per launch is affordable.
     *
     * The repair is what makes this function's promise true. Before it, the sweep's own comment
     * claimed to "backfill missing expiresAt" while running a query that could not select a row with
     * a missing `expiresAt` at all: a row in that state was invisible to the sweep AND to the alarm,
     * and survived until a UI surface happened to call [enforceIfExpired] on it. If the user never
     * reopened that chat, a message the sender had asked to delete was kept indefinitely.
     */
    @WorkerThread
    fun repairAndPurgeOverdue() {
        repairUnreachableExpiries()
        purgeOverdueAndRearm()
    }

    /**
     * Stamp the countdown onto rows that can never reach a deadline on their own.
     *
     * The decision per row is [ExpiryRepairDecision]'s, not this function's; everything here is
     * plumbing. Failures are logged and skipped rather than aborting the pass: one unreadable row
     * must not stop the others from being repaired, and the next launch tries again.
     */
    @WorkerThread
    private fun repairUnreachableExpiries() {
        val serviceManager = ThreemaApplication.getServiceManager() ?: return
        val db = serviceManager.databaseService
        val now = System.currentTimeMillis()

        val candidates: List<AbstractMessageModel> = try {
            db.messageModelFactory.getRepairableExpiryCandidates(REPAIR_SCAN_LIMIT) +
                db.groupMessageModelFactory.getRepairableExpiryCandidates(REPAIR_SCAN_LIMIT)
        } catch (e: Exception) {
            logger.warn("Could not read expiry repair candidates", e)
            return
        }

        var repaired = 0
        var superseded = 0
        candidates.forEach { model ->
            val repair = ExpiryRepairDecision.repairFor(
                isOutbox = model.isOutbox,
                isRead = model.isRead,
                timerSeconds = model.disappearingTimerSeconds,
                expireStartedAt = model.expireStartedAt,
                expiresAt = model.expiresAt,
                readAt = model.readAt?.time,
                nowMillis = now,
            ) ?: return@forEach
            try {
                // F1Whisper (fourth fork review, F4-06): a conditional, column-scoped UPDATE, NOT a full-row save.
                //
                // This pass reads whole detached models and can be arbitrarily far behind the database by the time it
                // writes. It used to hand the mutated snapshot to `messageService.save`, which is a full-row upsert: if
                // the message had been hard-deleted in between, the upsert INSERTED the stale snapshot back as a new
                // row, and if it had been deleted for everyone, the full-row write restored the old body and nulled the
                // deletion timestamp. Content the user had deleted came back, and a newer deletion was lost.
                //
                // `repairExpiry` can do neither. It writes only the two expiry columns, and its WHERE clause re-checks
                // at write time what the candidate query checked at read time, so a row that has gone, been deleted for
                // everyone, had its timer cleared, or been given a proper countdown by a concurrent mark-as-read is
                // simply not matched. A pass that computes deadlines does not own message content.
                val applied = when (model) {
                    is GroupMessageModel ->
                        db.groupMessageModelFactory
                            .repairExpiry(model.id, repair.expireStartedAt, repair.expiresAt)

                    else ->
                        db.messageModelFactory
                            .repairExpiry(model.id, repair.expireStartedAt, repair.expiresAt)
                }
                if (!applied) {
                    superseded++
                    logger.debug("Disappearing repair: row uid={} was superseded between read and write; left alone", model.uid)
                    return@forEach
                }
                // Keep the caller's instance coherent with the row that was actually written.
                model.expireStartedAt = repair.expireStartedAt
                model.expiresAt = repair.expiresAt
                repaired++
                logger.info(
                    "Disappearing repair: stamped uid={} timer={}s startedAt={} expiresAt={}",
                    model.uid,
                    model.disappearingTimerSeconds,
                    repair.expireStartedAt,
                    repair.expiresAt,
                )
            } catch (e: Exception) {
                logger.error("Disappearing repair: could not stamp uid={}", model.uid, e)
            }
        }
        if (repaired > 0 || superseded > 0) {
            logger.info(
                "Disappearing repair: repaired {} of {} candidate rows ({} superseded)",
                repaired,
                candidates.size,
                superseded,
            )
        }
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
                if (deleteExpiredMessage(serviceManager, messageService, model, now)) {
                    logger.info("Disappearing startup sweep: deleted uid={}", model.uid)
                }
            } catch (e: Exception) {
                logger.error("Disappearing startup sweep: error deleting uid={}", model.uid, e)
            }
        }

        rescheduleNextAlarm()
    }

    /**
     * F1Whisper (fourth fork review, F4-08): delete an expired message, INCLUDING whatever else it governs.
     *
     * The defect this removes: all three expiry paths called the generic `messageService.remove`, which deletes the message
     * row and its files and nothing more. A poll, though, is not stored in the message at all. The carrier message only
     * points at a ballot whose question, choices, votes and group/identity links live in their own tables, and the
     * conversation's open-poll surface ([ch.threema.app.ui.OpenBallotNoticeView]) queries those tables directly rather than
     * the message list. Expiring the carrier therefore removed the bubble and left the poll itself fully alive and
     * interactive - the question readable, the votes intact, and vote/results/close still offered.
     *
     * A message that introduced content owns that content's lifetime. So when the expiring message is the SETUP carrier of a
     * ballot, the whole aggregate goes: `BallotService.remove` deletes the votes, choices, links and the ballot, deletes
     * every message associated with it (this carrier and any close carrier with it, which is what "handled consistently"
     * means here), and fires the ballot listeners so the open-poll surface refreshes.
     *
     * A CLOSE carrier expiring is NOT treated as governing: the setup message may well still be visible, and removing the
     * poll from under it would be the same defect in reverse. It is removed as an ordinary message. In practice the setup
     * carrier is the older row and therefore expires first under any shared timer, taking the close carrier with it.
     *
     * Falls back to the plain removal whenever the ballot cannot be resolved, so an unreadable ballot table can never leave
     * an expired message undeleted.
     *
     * F1Whisper (fifth fork review, F5-04): the row is CLAIMED before any of that happens.
     * [MessageService.removeIfStillDue] re-checks timer, start, deadline, deletion state and due-ness at write time, so a
     * message whose timer was turned off (a duplicate advertising an explicit OFF) or whose deadline was moved (a freeze
     * re-deriving it from the sender's corrected timer) between the query and this call is left alone rather than
     * destroyed from the stale snapshot. Winning that delete is also what makes this caller the only one entitled to
     * remove the ballot aggregate the message governed, which is why the ballot removal now runs AFTER it rather than
     * instead of it.
     *
     * @return whether this caller claimed and removed the message.
     */
    @WorkerThread
    private fun deleteExpiredMessage(
        serviceManager: ServiceManager,
        messageService: MessageService,
        model: AbstractMessageModel,
        nowMillis: Long,
    ): Boolean {
        if (!messageService.removeIfStillDue(model, nowMillis)) {
            logger.debug("Disappearing: uid={} was no longer the due row it was read as; left alone", model.uid)
            return false
        }
        removedGovernedBallot(serviceManager, model)
        return true
    }

    /**
     * If [model] is the setup carrier of a ballot, remove that ballot aggregate and report `true`; otherwise report `false`
     * so the caller removes the message the ordinary way. See [deleteExpiredMessage].
     *
     * Every failure answers `false`. Falling back to the plain removal can leave a ballot behind, which is the defect this
     * addresses; leaving the MESSAGE behind would mean content outliving the deadline the sender set, which is worse.
     */
    @WorkerThread
    private fun removedGovernedBallot(serviceManager: ServiceManager, model: AbstractMessageModel): Boolean {
        val governedBallotId = BallotCarrierDecision.governedBallotId(model) ?: return false
        return try {
            val ballotService = serviceManager.ballotService
            val ballot: BallotModel = ballotService.get(governedBallotId) ?: return false
            logger.info(
                "Disappearing: expiring poll carrier uid={} also removes ballot id={} and its votes/choices",
                model.uid,
                ballot.id,
            )
            ballotService.remove(ballot)
        } catch (e: Exception) {
            logger.warn(
                "Could not remove the ballot governed by uid={}; falling back to removing the message only",
                model.uid,
                e,
            )
            false
        }
    }

    // -------------------------------------------------------------------------
    // Belt-and-suspenders funnel
    // -------------------------------------------------------------------------

    /**
     * F1Whisper: the funnel runs on whatever thread is about to put the row in front of the user, main thread included,
     * because every caller needs the answer BEFORE it draws. A synchronous boolean is the entire contract: hand the work
     * to a worker and the expired message is rendered first and removed afterwards, which is the leak this funnel exists
     * to prevent.
     *
     * `@WorkerThread` on the callees is therefore a preference here, not a requirement: what they do is one conditional
     * row update and one delete against a thread-safe SQLite handle. Suppressed at the boundary, deliberately NOT added
     * to `lint-baseline-onprem.xml`, so the exception stays next to the reason for it instead of in an 8 MB file nobody
     * reads.
     */
    @Suppress("WrongThread")
    @AnyThread
    private fun enforceIfExpiredInternal(model: AbstractMessageModel): Boolean {
        try {
            // 1. Only act on messages with a started countdown.
            if (model.expireStartedAt == null) return false

            // 2. Lazily compute expiresAt if missing (repair path).
            if (model.expiresAt == null && !repairMissingDeadline(model)) {
                return false
            }

            // 3. Check if expired.
            val now = System.currentTimeMillis()
            val expiresAt = model.expiresAt ?: return false
            if (expiresAt > now) return false

            // 4. Guard against double-delete.
            val uid = model.uid ?: return false
            if (!inFlight.add(uid)) return false

            // 5. Hard delete - claimed, so a row that stopped being due between step 3 and here survives.
            try {
                val serviceManager = ThreemaApplication.getServiceManager() ?: return false
                if (!deleteExpiredMessage(serviceManager, serviceManager.messageService, model, now)) {
                    return false
                }
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

    /**
     * F1Whisper (fifth fork review, F5-04): stamp a deadline onto a row whose countdown has started but never reached one,
     * through the SAME non-inserting conditional update the startup repair pass uses.
     *
     * The defect: this path used to compute the missing deadline on the detached model and hand the whole thing to
     * `MessageService#save`, a full-row upsert. A row hard-deleted in between was recreated from the stale snapshot; a row
     * deleted for everyone had its body restored and its deletion nulled. And the caller then went straight on to DELETE
     * content from that same stale snapshot, so a lost write was not merely ignored, it was followed by an unconditional
     * removal.
     *
     * Now: if the conditional update writes nothing, the row is not the row this decision was made about. Re-read it and
     * continue only from what is actually on disk; if it cannot be re-read, or still has no reachable deadline, abort and
     * leave it to the bounded startup repair scan.
     *
     * @return whether [model] now carries a deadline that current state agrees with.
     */
    @Suppress("WrongThread") // reached only from the funnel above, which explains why this runs on the calling thread
    @AnyThread
    private fun repairMissingDeadline(model: AbstractMessageModel): Boolean {
        val db = ThreemaApplication.getServiceManager()?.databaseService ?: return false
        val timerSecs = model.disappearingTimerSeconds ?: return false
        if (timerSecs <= 0) return false
        val startedAt = model.expireStartedAt ?: return false
        val computed = startedAt + timerSecs * 1000L

        val applied = when (model) {
            is GroupMessageModel -> db.groupMessageModelFactory.repairExpiry(model.id, startedAt, computed)
            else -> db.messageModelFactory.repairExpiry(model.id, startedAt, computed)
        }
        if (applied) {
            model.expiresAt = computed
            return true
        }

        // Superseded, gone, deleted for everyone, or no longer timed. Whatever the reason, the deadline this caller
        // computed is not authoritative and must not become the basis of a deletion.
        val current = when (model) {
            is GroupMessageModel -> db.groupMessageModelFactory.getById(model.id)
            else -> db.messageModelFactory.getById(model.id)
        }
        if (current == null) {
            logger.debug("Disappearing: lazy deadline repair for uid={} found no row; nothing to enforce", model.uid)
            return false
        }
        model.disappearingTimerSeconds = current.disappearingTimerSeconds
        model.expireStartedAt = current.expireStartedAt
        model.expiresAt = current.expiresAt
        return model.expiresAt != null
    }
}
