package ch.threema.app.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F1Whisper: architecture fitness test guarding against the re-entrancy defect coming back.
 *
 * [ExpirySweepTest] proves the *helper* is safe. It does nothing to stop someone writing
 * `for (ConversationModel conv : this.conversationCache) { ... enforceIfExpired ... }` again next year — which is
 * exactly how the `6.4.3o-37` crash loop entered, survived three releases, and was only found in a 101 MB user log.
 * This test scans source text so a new call site cannot land unreviewed.
 *
 * ## What it enforces
 *
 * 1. `ConversationServiceImpl` never *invokes* the mutating API itself. It owns the conversation cache that the
 *    delete/save listeners structurally modify, so its only permitted route is [ExpirySweep].
 * 2. ...but the sweep is still there. Without that second check, rule 1 is trivially satisfiable by deleting the
 *    belt-and-suspenders sweep outright — a guard that rewards deleting the feature is worse than no guard.
 * 3. The complete set of call sites matches a reviewed allowlist, so a new one has to be justified.
 * 4. The scan actually found the sources. A source-scanning test that silently passes when it cannot locate the tree
 *    is worse than no test, so this fails loudly with the resolved absolute path instead.
 *
 * ## Why the scan strips comments first
 *
 * Three files mention `enforceIfExpired` only in prose: [ExpirySweep]'s own Javadoc, and comments in
 * `MessageServiceImpl` and `ConversationNotificationUtil` (the latter writes the full call syntax inside a comment).
 * A naive text match would fail on the very file the fix introduced, and such a guard gets deleted within a week.
 *
 * Design: `.claude/tasks/conversation-cache-reentrancy-crash.md`.
 */
class ExpirySweepGuardTest {
    private enum class ScanState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, RAW_STRING }

    /** The bare name. Used to find every file that references the API at all. */
    private val needle = "enforceIfExpired"

    /**
     * The *invocation* form. A `::enforceIfExpired` method reference does not match it, which is deliberate: the
     * invariant is "never invoke it yourself", not "never name it" — handing the reference to [ExpirySweep] is the
     * prescribed route. `enforceIfExpiredInternal(` does not match either, since the next character is `I`, not
     * whitespace or `(`; [conversationServiceMustNotInvokeEnforceDirectly] checks both of those rather than
     * assuming them.
     *
     * **Why a regex and not the literal `"enforceIfExpired("`.** A literal substring match was the first version of
     * this guard, and it had a proven bypass: both Java and Kotlin accept whitespace around the selector and the
     * argument list, so
     *
     * ```java
     * DisappearingMessageService . enforceIfExpired (null);
     * ```
     *
     * compiles, re-enters the cache exactly like the crash-looping code did, and slipped past the literal match with
     * the suite still green. The allowlist case cannot backstop it either, because `ConversationServiceImpl.java` is
     * itself allowlisted. `\s*` closes that hole — including a line break between the name and the paren, since `\s`
     * covers newlines and the stripper preserves them.
     */
    private val directInvocation = Regex("""enforceIfExpired\s*\(""")

    private val conversationServiceRelativePath = "ch/threema/app/services/ConversationServiceImpl.java"

    /**
     * Every file under `app/src/main/java` allowed to reference `enforceIfExpired`, each with the reason it is safe,
     * from the re-entrancy audit. Adding an entry is a deliberate, reviewable act — read the [ExpirySweep] Javadoc
     * first and be sure your call site is not inside a traversal a listener can reach.
     *
     * `ExpirySweep.java` is deliberately absent: its six mentions are Javadoc and get stripped before matching. If it
     * ever shows up in the scan, the comment stripping is broken, not the allowlist.
     */
    private val allowedCallSites = setOf(
        // Declares enforceIfExpired (:66) and enforceIfExpiredInternal (:520); the funnel itself, not a call site.
        "ch/threema/app/services/DisappearingMessageService.kt",
        // Method reference ONLY, handed to ExpirySweep.collectThenEnforce. Direct invocation here is forbidden
        // (see conversationServiceMustNotInvokeEnforceDirectly): this class owns the cache that the delete/save
        // listeners structurally modify.
        "ch/threema/app/services/ConversationServiceImpl.java",
        // :1751 — inside markAsRead(); no traversal at the site, and the one traversing caller
        // (MarkAsReadRoutine.kt:41) always iterates a fresh, listener-unreachable list.
        "ch/threema/app/services/MessageServiceImpl.java",
        // :4332 — jumpToQuotedMessage(); straight-line, single message model.
        // :7247 — selectedMessages.removeIf(...). AUDITED SAFE: the only structural mutators of selectedMessages
        //   (:2043 posted, :3240, :4234, :4241, :4426-4427) are user-input driven and unreachable from any of the
        //   3 registered MessageListeners. The fragment's own listener runs INLINE (RuntimeUtil:26-33) but touches
        //   only the adapter, never this list.
        // :7567 — showMessageDetailScreen(); guard clause on one model.
        "ch/threema/app/fragments/composemessage/ComposeMessageFragment.java",
        // :221 — initActivity(); one model, finishes the activity on expiry.
        "ch/threema/app/activities/MediaViewerActivity.java",
        // :521 — inside getView(), so it MUST stay deferred: only the pure isExpired() predicate (:519) runs inline;
        // the delete is posted via parent.post(). Deleting inline mutates the adapter's backing list mid-bind.
        // Do not remove the post().
        "ch/threema/app/adapters/ComposeMessageAdapter.java",
        // :330 — same rule as ComposeMessageAdapter: inside the bind pass, isExpired() inline (:327), delete posted
        // to the main looper. Do not remove the post().
        "ch/threema/app/adapters/decorators/ChatAdapterDecorator.java",
        // :60 — convert(); no traversal at the site. Its caller (GlobalListeners:508) does iterate
        // modifiedMessageModels, but every emitter of that list passes a fresh or immutable list no listener
        // can reach.
        "ch/threema/app/utils/ConversationNotificationUtil.java",
    )

    @Test
    fun conversationServiceMustNotInvokeEnforceDirectly() {
        assertFalse(
            directInvocation.containsMatchIn("enforceIfExpiredInternal("),
            "the direct-invocation pattern must not match enforceIfExpiredInternal( — otherwise this guard would " +
                "fire on an unrelated private method and would need special-casing",
        )
        assertFalse(
            directInvocation.containsMatchIn("DisappearingMessageService::enforceIfExpired"),
            "a `::` method reference must not match — handing the reference to ExpirySweep is the prescribed " +
                "route, and flagging it would make the guard unsatisfiable by the correct fix",
        )
        assertTrue(
            directInvocation.containsMatchIn("DisappearingMessageService . enforceIfExpired (null);"),
            "the spaced-out invocation MUST match. A literal \"enforceIfExpired(\" substring match let this " +
                "compiling, cache-re-entering form through with the suite green; that is why the pattern " +
                "tolerates whitespace. Do not regress this to a literal match.",
        )

        val root = resolveSourceRoot()
        val file = File(root, conversationServiceRelativePath)
        assertTrue(file.isFile, "ConversationServiceImpl.java not found at ${file.absolutePath}")

        val occurrences = directInvocation.findAll(stripComments(file.readText())).count()

        assertEquals(
            0,
            occurrences,
            "ConversationServiceImpl must NEVER invoke enforceIfExpired itself (found $occurrences invocation(s)). " +
                "It owns conversationCache, and enforcing expiry deletes a message, which synchronously re-enters " +
                "this same class (ListenerManager.handle -> GlobalListeners.onRemoved -> refreshWithDeletedMessage " +
                "-> messageDeleted -> sort()) and bumps the cache's modCount, killing any live iterator. That is " +
                "the 6.4.3o-37 crash loop. Route it through ExpirySweep.collectThenEnforce instead, and read the " +
                "ExpirySweep Javadoc first. A `DisappearingMessageService::enforceIfExpired` method reference " +
                "handed to ExpirySweep is fine and is what this file is expected to contain.",
        )
    }

    @Test
    fun conversationServiceStillSweeps() {
        val root = resolveSourceRoot()
        val code = stripComments(File(root, conversationServiceRelativePath).readText())

        assertTrue(
            code.contains("ExpirySweep.collectThenEnforce"),
            "ConversationServiceImpl.getAll must still run the belt-and-suspenders sweep via " +
                "ExpirySweep.collectThenEnforce. Deleting the sweep would satisfy " +
                "conversationServiceMustNotInvokeEnforceDirectly while silently regressing the guarantee that no " +
                "overdue message survives a conversation-list refresh. Fix the traversal, never the feature.",
        )
        assertTrue(
            code.contains("DisappearingMessageService::enforceIfExpired"),
            "the sweep must still hand DisappearingMessageService::enforceIfExpired to ExpirySweep — a sweep that " +
                "no longer enforces expiry is not a sweep",
        )
    }

    @Test
    fun enforceIfExpiredCallSitesMatchTheAllowlist() {
        val root = resolveSourceRoot()

        val found = sourceFiles(root)
            .filter { stripComments(it.readText()).contains(needle) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()

        val unexpected = (found - allowedCallSites).sorted()
        val vanished = (allowedCallSites - found).sorted()

        assertTrue(
            unexpected.isEmpty(),
            "A new `enforceIfExpired` call site appeared in $unexpected. Enforcing expiry DELETES a message and " +
                "synchronously re-enters ConversationServiceImpl, structurally modifying the shared conversation " +
                "cache (sort()/cache()). If your call site sits inside ANY traversal of a collection that a " +
                "deletion or modification listener can touch, route it through ExpirySweep instead. Read the " +
                "ExpirySweep Javadoc, then add your file to ALLOWED_CALL_SITES.",
        )
        assertTrue(
            vanished.isEmpty(),
            "These allowlisted `enforceIfExpired` call sites no longer exist: $vanished. If you removed one on " +
                "purpose, drop it from the allowlist in the same commit. If you did not, a disappearing-message " +
                "enforcement point has been lost and overdue messages will survive at that surface.",
        )
        assertEquals(
            allowedCallSites,
            found,
            "the set of files referencing enforceIfExpired must equal the reviewed allowlist exactly",
        )
    }

    @Test
    fun sourceRootIsResolvable() {
        val root = resolveSourceRoot()
        assertTrue(root.isDirectory, "the resolved source root is not a directory: ${root.absolutePath}")

        val files = sourceFiles(root)
        assertTrue(
            files.isNotEmpty(),
            "the scan found no .java/.kt files under ${root.absolutePath}. A source-scanning guard that passes " +
                "because it cannot find the sources is worse than no guard, so this fails instead.",
        )
        assertTrue(
            files.any { it.name == "DisappearingMessageService.kt" },
            "the scan of ${root.absolutePath} did not reach DisappearingMessageService.kt, which declares " +
                "enforceIfExpired — the root resolved to the wrong tree (${files.size} file(s) seen)",
        )
    }

    /**
     * Gradle runs unit tests with the module directory (`app/`) as the working directory, so `src/main/java` is the
     * normal hit; the repo-root form is a fallback. Fails loudly with both absolute paths rather than skipping.
     */
    private fun resolveSourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: fail(
                "Could not resolve the source root to scan. Tried, relative to the test working directory " +
                    "'${File(".").absolutePath}': " + candidates.joinToString(", ") { it.absolutePath } +
                    ". This guard must never pass vacuously, so it fails instead.",
            )
    }

    private fun sourceFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .toList()

    /**
     * Removes `//` line comments and block comments, leaving string and character literals intact so a `//` inside a
     * literal cannot swallow the rest of a line. Newlines are preserved so nothing else shifts.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var state = ScanState.CODE
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val c1 = source.getOrElse(i + 1) { '\u0000' }
            val c2 = source.getOrElse(i + 2) { '\u0000' }
            when (state) {
                ScanState.CODE -> when {
                    c == '/' && c1 == '/' -> {
                        state = ScanState.LINE_COMMENT
                        i += 2
                    }

                    c == '/' && c1 == '*' -> {
                        state = ScanState.BLOCK_COMMENT
                        i += 2
                    }

                    c == '"' && c1 == '"' && c2 == '"' -> {
                        out.append("\"\"\"")
                        state = ScanState.RAW_STRING
                        i += 3
                    }

                    c == '"' -> {
                        out.append(c)
                        state = ScanState.STRING
                        i++
                    }

                    c == '\'' -> {
                        out.append(c)
                        state = ScanState.CHAR
                        i++
                    }

                    else -> {
                        out.append(c)
                        i++
                    }
                }

                ScanState.LINE_COMMENT -> {
                    if (c == '\n') {
                        out.append(c)
                        state = ScanState.CODE
                    }
                    i++
                }

                ScanState.BLOCK_COMMENT -> {
                    if (c == '*' && c1 == '/') {
                        state = ScanState.CODE
                        i += 2
                    } else {
                        if (c == '\n') {
                            out.append(c)
                        }
                        i++
                    }
                }

                ScanState.RAW_STRING -> {
                    if (c == '"' && c1 == '"' && c2 == '"') {
                        out.append("\"\"\"")
                        state = ScanState.CODE
                        i += 3
                    } else {
                        out.append(c)
                        i++
                    }
                }

                ScanState.STRING, ScanState.CHAR -> {
                    val terminator = if (state == ScanState.STRING) '"' else '\''
                    out.append(c)
                    when {
                        c == '\\' && i + 1 < source.length -> {
                            out.append(c1)
                            i += 2
                        }

                        c == terminator || c == '\n' -> {
                            state = ScanState.CODE
                            i++
                        }

                        else -> i++
                    }
                }
            }
        }
        return out.toString()
    }
}
