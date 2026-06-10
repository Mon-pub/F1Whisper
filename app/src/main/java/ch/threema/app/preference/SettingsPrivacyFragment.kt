package ch.threema.app.preference

import android.os.Build
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.TwoStatePreference
import ch.threema.android.ToastDuration
import ch.threema.android.showToast
import ch.threema.app.R
import ch.threema.app.activities.BlockedIdentitiesActivity
import ch.threema.app.activities.ExcludedSyncIdentitiesActivity
import ch.threema.app.dialogs.GenericAlertDialog
import ch.threema.app.preference.service.PreferenceService
import ch.threema.app.preference.service.SynchronizedSettingsService
import ch.threema.app.restrictions.AppRestrictions
import ch.threema.app.services.ContactService
import ch.threema.app.services.LockAppService
import ch.threema.app.utils.*
import ch.threema.app.workers.ShareTargetUpdateWorker
import ch.threema.base.utils.getThreemaLogger
import org.koin.android.ext.android.inject

private val logger = getThreemaLogger("SettingsPrivacyFragment")

class SettingsPrivacyFragment :
    ThreemaPreferenceFragment(),
    GenericAlertDialog.DialogClickListener {
    init {
        logScreenVisibility(logger)
    }

    private val contactService: ContactService by inject()
    private val lockAppService: LockAppService by inject()
    private val preferenceService: PreferenceService by inject()
    private val synchronizedSettingsService: SynchronizedSettingsService by inject()
    private val appRestrictions: AppRestrictions by inject()

    private lateinit var disableScreenshot: CheckBoxPreference
    private var disableScreenshotChecked = false

    override fun initializePreferences() {
        super.initializePreferences()

        disableScreenshot = getPref(synchronizedSettingsService.getScreenshotPolicySetting().preferenceKey)
        disableScreenshotChecked = this.disableScreenshot.isChecked

        if (lockAppService.isLockingEnabled) {
            disableScreenshot.isEnabled = false
            disableScreenshot.isSelectable = false
        }

        initWorkRestrictedPrefs()

        initExcludedSyncIdentitiesPref()

        initBlockedContactsPref()

        initResetReceiptsPref()

        initDirectSharePref()
    }

    override fun getPreferenceTitleResource(): Int = R.string.prefs_privacy

    override fun getPreferenceResource(): Int = R.xml.preference_privacy

    override fun onDestroyView() {
        if (isAdded) {
            DialogUtil.dismissDialog(parentFragmentManager, DIALOG_TAG_VALIDATE, true)
        }
        super.onDestroyView()
    }

    override fun onDetach() {
        super.onDetach()
        if (disableScreenshot.isChecked != disableScreenshotChecked) {
            ConfigUtils.recreateActivity(activity)
        }
    }

    private fun initWorkRestrictedPrefs() {
        if (ConfigUtils.isWorkRestricted()) {
            if (appRestrictions.isBlockUnknownOrNull() != null) {
                val blockUnknown: CheckBoxPreference = getPref(synchronizedSettingsService.getUnknownContactPolicySetting().preferenceKey)
                blockUnknown.isEnabled = false
                blockUnknown.isSelectable = false
            }
            if (appRestrictions.isScreenshotsDisabledOrNull() != null) {
                disableScreenshot.isEnabled = false
                disableScreenshot.isSelectable = false
            }
        }
    }

    private fun initExcludedSyncIdentitiesPref() {
        getPref<Preference>("pref_excluded_sync_identities").onClick {
            startActivity(ExcludedSyncIdentitiesActivity.createIntent(requireContext()))
        }
    }

    private fun initBlockedContactsPref() {
        getPref<Preference>("pref_blocked_contacts").onClick {
            startActivity(BlockedIdentitiesActivity.createIntent(requireContext()))
        }
    }

    private fun initResetReceiptsPref() {
        getPref<Preference>("pref_reset_receipts").onClick {
            val dialog = GenericAlertDialog.newInstance(
                R.string.prefs_title_reset_receipts,
                // TODO(ANDR-3686)
                getString(R.string.prefs_sum_reset_receipts) + "?",
                R.string.yes,
                R.string.no,
            )
            dialog.targetFragment = this@SettingsPrivacyFragment
            dialog.show(parentFragmentManager, DIALOG_TAG_RESET_RECEIPTS)
        }
    }

    private fun initDirectSharePref() {
        getPrefOrNull<TwoStatePreference>(R.string.preferences__direct_share)?.onChange<Boolean> { enabled ->
            if (enabled) {
                ShareTargetUpdateWorker.scheduleShareTargetShortcutUpdate(requireContext())
            } else {
                ShareTargetUpdateWorker.cancelScheduledShareTargetShortcutUpdate(requireContext())
                ShortcutUtil.deleteAllShareTargetShortcuts(preferenceService)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val preferenceCategory = getPref<PreferenceCategory>("pref_key_other")
            preferenceCategory.removePreference(getPref(resources.getString(R.string.preferences__disable_smart_replies)))
        }
    }

    private fun resetReceipts() {
        Thread(
            {
                contactService.resetReceiptsSettings()
                showToast(R.string.reset_successful, ToastDuration.SHORT)
            },
            "ResetReceiptSettings",
        ).start()
    }

    override fun onYes(tag: String?, data: Any?) {
        resetReceipts()
    }

    companion object {
        private const val DIALOG_TAG_VALIDATE = "vali"
        private const val DIALOG_TAG_RESET_RECEIPTS = "rece"
    }
}
