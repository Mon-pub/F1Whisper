package ch.threema.app.diagnostics

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import ch.threema.app.R
import ch.threema.app.ThreemaApplication
import ch.threema.app.di.Qualifiers
import ch.threema.app.usecases.ExportConnectionDiagnosticsUseCase
import ch.threema.app.utils.ShareUtil
import ch.threema.base.utils.getThreemaLogger
import ch.threema.domain.onprem.OnPremConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private val logger = getThreemaLogger("ConnectivityDiagnosticsDialog")

/**
 * F1Whisper connectivity troubleshooter — the diagnostic dialog.
 *
 * Reachable from BOTH entry points (registration error in `EnterSerialActivity`; chat/connection
 * failure via Advanced Options), sharing one probe engine ([ConnectivityProbeUseCase]) and one
 * report format ([ConnectivityProbeReportWriter]).
 *
 * Flow: spinner + "Running checks…" while the engine probes on [Dispatchers.IO] → per-probe
 * pass/fail list + the localized verdict → "Send diagnostics" (appends the rendered report to
 * `connection_diagnostic.log`, zips it, and shares it exactly like
 * [ch.threema.app.usecases.ExportConnectionDiagnosticsUseCase]) and "Close".
 *
 * **DIAGNOSIS ONLY.** This dialog never triggers any circumvention (no DoH swap, no SNI fronting);
 * it only surfaces the probe results and lets the user share them.
 */
class ConnectivityDiagnosticsDialog : DialogFragment(), KoinComponent {

    /**
     * The unpinned base OkHttp client (no OnPrem certificate pinning). We deliberately use the
     * unpinned client so the HTTPS probes exercise the same path a blocked user sees, instead of
     * masking a MITM/poisoning scenario behind cert pinning. Resolved from Koin.
     */
    private val unpinnedOkHttpClient: OkHttpClient by lazy {
        get(qualifier = Qualifiers.okHttpBase)
    }

    private var progressContainer: View? = null
    private var resultsContainer: View? = null
    private var verdictText: TextView? = null
    private var resultsList: LinearLayout? = null

    /** The completed report, retained so the "Send diagnostics" action can render + share it. */
    private var report: ProbeReport? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        @Suppress("InflateParams")
        val content = inflater.inflate(R.layout.dialog_connectivity_diagnostics, null, false)

        progressContainer = content.findViewById(R.id.progress_container)
        resultsContainer = content.findViewById(R.id.results_container)
        verdictText = content.findViewById(R.id.verdict_text)
        resultsList = content.findViewById(R.id.results_list)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connectivity_probe_dialog_title)
            .setView(content)
            // The positive button starts as "Send diagnostics" but is disabled until the probe run
            // finishes (there is nothing to send while probing). We keep the button from
            // auto-dismissing so a failed share does not close the dialog.
            .setPositiveButton(R.string.connectivity_probe_send_btn, null)
            .setNegativeButton(R.string.connectivity_probe_close_btn) { _, _ -> dismissAllowingStateLoss() }
            .create()

        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Disable "Send diagnostics" until the report is ready, and override its click so a failed
        // share does not dismiss the dialog.
        val sendButton = (dialog as? androidx.appcompat.app.AlertDialog)
            ?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        sendButton?.isEnabled = report != null
        sendButton?.setOnClickListener { onSendClicked() }

        if (report == null) {
            runProbe()
        }
    }

    /**
     * Run the probe battery once. Results are rendered on the main thread; the engine itself hops to
     * [Dispatchers.IO] internally, and never throws (partial failures degrade to error fields).
     */
    private fun runProbe() {
        val host = requireArguments().getString(ARG_HOST).orEmpty()
        val useCachedConfig = requireArguments().getBoolean(ARG_USE_CACHED_CONFIG, false)

        showProgress()

        lifecycleScope.launch {
            val result = try {
                val cachedConfig = if (useCachedConfig) loadCachedConfig() else null
                ConnectivityProbeUseCase(unpinnedOkHttpClient).call(
                    target = ProbeTarget(host = host),
                    cachedConfig = cachedConfig,
                )
            } catch (e: Throwable) {
                // The engine is designed never to throw, but guard the UI regardless.
                logger.error("Connectivity probe failed unexpectedly", e)
                null
            }

            if (!isAdded) {
                return@launch
            }

            report = result
            if (result != null) {
                renderResults(result)
            } else {
                renderFatalError()
            }

            (dialog as? androidx.appcompat.app.AlertDialog)
                ?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.isEnabled = report != null
        }
    }

    /**
     * For the Advanced Options entry point, read the cached & verified OPPF (chat/dir/blob hosts).
     * Best-effort: any failure (locked master key, no service manager, no cached config) degrades to
     * null and the engine derives ancillary hosts from the target domain instead.
     */
    private fun loadCachedConfig(): OnPremConfig? = try {
        ThreemaApplication.getServiceManager()
            ?.onPremConfigFetcherProvider
            ?.getOnPremConfigFetcher()
            ?.getCached()
    } catch (e: Throwable) {
        logger.info("No cached OnPrem config available for probing: {}", e.javaClass.simpleName)
        null
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun showProgress() {
        progressContainer?.isVisible = true
        resultsContainer?.isVisible = false
    }

    private fun renderResults(report: ProbeReport) {
        progressContainer?.isVisible = false
        resultsContainer?.isVisible = true

        verdictText?.text = verdictString(report.verdict)

        val list = resultsList ?: return
        list.removeAllViews()

        // DNS resolver matrix first (the poisoning signal), then the reachability probes.
        addSectionHeader(list, R.string.connectivity_probe_label_dns)
        for (dns in report.dns) {
            addRow(
                list,
                ok = dns.succeeded,
                label = dns.resolverName,
                detail = if (dns.succeeded) {
                    dns.allIps.joinToString(", ").ifEmpty { "-" } + "  (${dns.latencyMs}ms)"
                } else {
                    (dns.error ?: "failed") + "  (${dns.latencyMs}ms)"
                },
            )
        }

        for (probe in report.probes) {
            addRow(list, ok = probe.ok, label = probe.name, detail = probe.detail)
        }

        if (report.hints.isNotEmpty()) {
            addSectionHeader(list, R.string.connectivity_probe_label_hints)
            for (hint in report.hints) {
                addHintRow(list, hintString(hint))
            }
        }
    }

    private fun renderFatalError() {
        progressContainer?.isVisible = false
        resultsContainer?.isVisible = true
        verdictText?.text = getString(R.string.an_error_occurred)
        resultsList?.removeAllViews()
    }

    private fun addSectionHeader(parent: LinearLayout, labelRes: Int) {
        val header = TextView(requireContext()).apply {
            setTextAppearance(R.style.Threema_TextAppearance_SectionHeader)
            text = getString(labelRes)
            val topPadding = resources.getDimensionPixelSize(R.dimen.grid_unit_x1)
            setPadding(0, topPadding, 0, topPadding / 2)
        }
        parent.addView(header)
    }

    private fun addRow(parent: LinearLayout, ok: Boolean, label: String, detail: String) {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_connectivity_probe_row, parent, false)
        val status = row.findViewById<TextView>(R.id.probe_status)
        val labelView = row.findViewById<TextView>(R.id.probe_label)
        val detailView = row.findViewById<TextView>(R.id.probe_detail)

        // Colour-independent pass/fail glyph (works for colour-blind users and dark/light themes).
        status.text = if (ok) "✓" else "✗"
        labelView.text = label
        detailView.text = detail
        parent.addView(row)
    }

    /** Hints are informational, not pass/fail — use a neutral glyph instead of ✓/✗. */
    private fun addHintRow(parent: LinearLayout, label: String) {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_connectivity_probe_row, parent, false)
        val status = row.findViewById<TextView>(R.id.probe_status)
        val labelView = row.findViewById<TextView>(R.id.probe_label)
        val detailView = row.findViewById<TextView>(R.id.probe_detail)

        status.text = "•"
        labelView.text = label
        detailView.text = ""
        parent.addView(row)
    }

    private fun verdictString(verdict: Verdict): String = getString(
        when (verdict) {
            Verdict.ALL_OK -> R.string.connectivity_probe_verdict_all_ok
            Verdict.NO_INTERNET -> R.string.connectivity_probe_verdict_no_internet
            Verdict.DNS_POISONING_SUSPECTED -> R.string.connectivity_probe_verdict_dns_poisoning
            Verdict.SNI_BLOCKING_SUSPECTED -> R.string.connectivity_probe_verdict_sni_blocking
            Verdict.IP_HOST_BLOCKED -> R.string.connectivity_probe_verdict_ip_blocked
            Verdict.CHAT_PORT_BLOCKED -> R.string.connectivity_probe_verdict_chat_port_blocked
            Verdict.SLOW_THROTTLED -> R.string.connectivity_probe_verdict_slow_throttled
            Verdict.PARTIAL_FAILURE -> R.string.connectivity_probe_verdict_partial_failure
        },
    )

    private fun hintString(hint: DiagnosticHint): String = getString(
        when (hint) {
            DiagnosticHint.MIDDLEBOX_TERMINATED -> R.string.connectivity_probe_hint_middlebox
            DiagnosticHint.PORT53_HIJACK -> R.string.connectivity_probe_hint_port53
        },
    )

    // -----------------------------------------------------------------------
    // Share
    // -----------------------------------------------------------------------

    /**
     * Render the report into `connection_diagnostic.log`, zip it, and hand it to the system share
     * sheet — the same plumbing [ch.threema.app.usecases.ExportConnectionDiagnosticsUseCase] uses.
     */
    private fun onSendClicked() {
        val report = report ?: return
        lifecycleScope.launch {
            // Build ONE combined report: the passive OS / notification / battery snapshot PLUS this
            // active probe report, via the shared ExportConnectionDiagnosticsUseCase (identical zip /
            // share plumbing as the Advanced Options "Connection & notification diagnostics" export).
            // So a post-registration censorship report carries both halves in a single file.
            val zipFile = try {
                ExportConnectionDiagnosticsUseCase(
                    requireContext().applicationContext,
                    get(),
                    get(),
                    get(),
                    get(),
                ).call(probeReport = report)
            } catch (e: Exception) {
                logger.error("Failed to build connectivity diagnostics zip", e)
                null
            }

            if (!isAdded) {
                return@launch
            }

            if (zipFile != null) {
                ShareUtil.shareFile(requireContext(), zipFile, ZIP_FILE_NAME, MIME_ZIP)
            } else {
                android.widget.Toast.makeText(requireContext(), R.string.an_error_occurred, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        progressContainer = null
        resultsContainer = null
        verdictText = null
        resultsList = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_HOST = "host"
        private const val ARG_USE_CACHED_CONFIG = "useCachedConfig"

        private const val ZIP_FILE_NAME = "connection_diagnostics.zip"
        private const val MIME_ZIP = "application/zip"

        const val DIALOG_TAG = "connectivityDiagnostics"

        /**
         * @param host            The bare hostname to probe (e.g. the entered OPPF host at
         *                        registration, or the cached OPPF host from Advanced Options).
         * @param useCachedConfig When true, the dialog resolves the cached & verified OPPF at runtime
         *                        to probe the real chat/dir/blob hosts (Advanced Options entry). When
         *                        false (registration, pre-auth), ancillary hosts are derived from
         *                        [host].
         */
        @JvmStatic
        @JvmOverloads
        fun newInstance(host: String, useCachedConfig: Boolean = false): ConnectivityDiagnosticsDialog =
            ConnectivityDiagnosticsDialog().apply {
                arguments = bundleOf(
                    ARG_HOST to host,
                    ARG_USE_CACHED_CONFIG to useCachedConfig,
                )
            }

        /** Convenience for the Java caller in `EnterSerialActivity`. */
        @JvmStatic
        fun show(fragmentManager: FragmentManager, host: String, useCachedConfig: Boolean) {
            newInstance(host, useCachedConfig).show(fragmentManager, DIALOG_TAG)
        }
    }
}
