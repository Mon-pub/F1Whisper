package ch.threema.app.activities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.text.HtmlCompat;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.activities.wizard.components.WizardButtonXml;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.diagnostics.ConnectivityDiagnosticsDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.restrictions.AppRestrictionService;
import ch.threema.app.services.license.LicenseService;
import ch.threema.app.services.license.LicenseServiceUser;
import ch.threema.domain.models.SerialCredentials;
import ch.threema.domain.models.UserCredentials;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.SimpleTextWatcher;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.LocaleUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.executor.BackgroundExecutor;
import ch.threema.app.utils.executor.BackgroundTask;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.models.LicenseCredentials;
import kotlin.Lazy;

import static ch.threema.app.startup.AppStartupUtilKt.finishAndRestartLaterIfNotReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;
import static ch.threema.common.LazyKt.lazy;

// this should NOT extend ThreemaToolbarActivity
public class EnterSerialActivity extends ThreemaActivity {
    private static final Logger logger = getThreemaLogger("EnterSerialActivity");

    private static final String BUNDLE_PASSWORD = "bupw";
    private static final String BUNDLE_LICENSE_KEY = "bulk";
    private static final String BUNDLE_SERVER = "busv";
    private static final String DIALOG_TAG_CHECKING = "check";
    private TextView stateTextView = null;
    private EditText licenseKeyOrUsernameText, passwordText, serverText, activationKeyText;
    private MaterialButton unlockButton;
    private WizardButtonXml loginButtonCompose;

    // Onprem setup wizard credential entry mode. The default is a single "Activation key" field
    // that encodes username+password; the toggle switches to the classic username + password rows.
    private MaterialButton credentialModeToggle;
    private View activationKeyLayout, unlockLayout, passwordLayout;
    private boolean activationKeyMode = true;

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);

    private final Lazy<BackgroundExecutor> backgroundExecutor = lazy(BackgroundExecutor::new);

    // We need to use getResources().getIdentifier(...) because of flavor specific layout files for this fragment
    @SuppressLint("DiscouragedApi")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
        if (finishAndRestartLaterIfNotReady(this)) {
            return;
        }

        if (!ConfigUtils.isSerialLicensed()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_enter_serial);

        checkForValidCredentialsInBackground();

        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(R.id.layout_parent_top),
            InsetSides.vertical(),
            SpacingValues.symmetric(R.dimen.wizard_contents_padding, R.dimen.wizard_contents_padding_horizontal)
        );

        stateTextView = findViewById(R.id.unlock_state);
        licenseKeyOrUsernameText = findViewById(R.id.license_key);
        passwordText = findViewById(getResources().getIdentifier("password", "id", getPackageName()));
        serverText = findViewById(getResources().getIdentifier("server", "id", getPackageName()));

        // The activation-key field and credential-mode toggle only exist in the onprem layout.
        // They are optional elsewhere, so we tolerate them being absent.
        activationKeyText = findViewById(getResources().getIdentifier("activation_key", "id", getPackageName()));
        credentialModeToggle = findViewById(getResources().getIdentifier("credential_mode_toggle", "id", getPackageName()));
        activationKeyLayout = findViewById(getResources().getIdentifier("activation_key_layout", "id", getPackageName()));
        unlockLayout = findViewById(getResources().getIdentifier("unlock_layout", "id", getPackageName()));
        passwordLayout = findViewById(getResources().getIdentifier("password_layout", "id", getPackageName()));

        TextView enterKeyExplainText = findViewById(R.id.layout_top);
        enterKeyExplainText.setText(HtmlCompat.fromHtml(getString(R.string.flavored__enter_serial_body), HtmlCompat.FROM_HTML_MODE_COMPACT));
        enterKeyExplainText.setClickable(true);
        enterKeyExplainText.setMovementMethod(LinkMovementMethod.getInstance());

        if (!ConfigUtils.isWorkBuild() && !ConfigUtils.isOnPremBuild()) {
            setupForShopBuild();
        } else {
            setupForWorkBuild();
        }

        setupLanguageButton();

        setupCredentialModeToggle();

        handleUrlIntent(getIntent());
    }

    /**
     * Wire up the language selector button shown in the top corner of the setup wizard. This lets
     * the user change the app language before authenticating. The button is optional in the layout,
     * so we tolerate it being absent.
     */
    private void setupLanguageButton() {
        final @Nullable ImageButton changeLanguageButton = findViewById(R.id.change_language_button);
        if (changeLanguageButton != null) {
            changeLanguageButton.setOnClickListener(v -> showLanguageSelectionDialog());
        }
    }

    /**
     * Show a single-choice dialog of the supported app languages. Selecting a language applies it
     * via {@link AppCompatDelegate#setApplicationLocales(LocaleListCompat)}, which recreates the
     * activity so the wizard re-renders in the chosen language (with correct RTL where applicable).
     * Reuses the same arrays as the in-app settings language picker.
     */
    private void showLanguageSelectionDialog() {
        final String[] languageNames = getResources().getStringArray(R.array.list_app_languages);
        final String[] languageValues = getResources().getStringArray(R.array.list_app_languages_values);

        // Determine the currently active app language so we can pre-select it.
        final LocaleListCompat applicationLocales = AppCompatDelegate.getApplicationLocales();
        final Locale currentLocale = applicationLocales.isEmpty() ? null : applicationLocales.get(0);
        final String currentValue = LocaleUtil.mapLocaleToPredefinedLocales(currentLocale, languageValues);

        int checkedItem = 0;
        for (int i = 0; i < languageValues.length; i++) {
            if (languageValues[i].equals(currentValue)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prefs_language_override)
            .setSingleChoiceItems(languageNames, checkedItem, (dialog, which) -> {
                dialog.dismiss();
                applyAppLanguage(languageValues[which]);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /**
     * Apply the given language tag as the per-app locale. An empty tag resets to the system default.
     * Mirrors the behaviour of {@code SettingsAppearanceFragment} (note that zh-hans-CN / zh-hant-TW
     * tags carry the script so the variant is applied correctly).
     */
    private void applyAppLanguage(@NonNull String languageTag) {
        final LocaleListCompat localeList;
        if (!languageTag.isEmpty()) {
            localeList = LocaleListCompat.create(Locale.forLanguageTag(languageTag));
        } else {
            // An empty locale list resets to the system default.
            localeList = LocaleListCompat.getEmptyLocaleList();
        }
        AppCompatDelegate.setApplicationLocales(localeList);
    }

    /**
     * Wire up the credential-mode toggle. The onprem wizard defaults to a single "Activation key"
     * field; the toggle switches to the classic username + password rows and back. The toggle and
     * the activation-key field are optional in the layout, so we tolerate them being absent (e.g.
     * the non-onprem shop layout has neither). Clearing the inline error on text change keeps the
     * behaviour consistent with the username/password fields.
     */
    private void setupCredentialModeToggle() {
        if (activationKeyText != null) {
            activationKeyText.addTextChangedListener(new TextChangeWatcher());
        }
        if (credentialModeToggle == null) {
            // Nothing to toggle (e.g. non-onprem layout). Keep the username/password flow as-is.
            return;
        }
        credentialModeToggle.setOnClickListener(v -> applyCredentialMode(!activationKeyMode));

        // When MDM presets credentials, the activation-key field cannot be used (the username and
        // password are fixed and shown in the disabled classic fields), so default to the classic
        // username/password view and hide the toggle. Otherwise default to the activation-key view.
        boolean hasPresetCredentials = getConfiguredUsername() != null || getConfiguredPassword() != null;
        if (hasPresetCredentials) {
            credentialModeToggle.setVisibility(View.GONE);
            applyCredentialMode(false);
        } else {
            applyCredentialMode(true);
        }
    }

    /**
     * Show/hide the activation-key row vs the username + password rows and update the toggle label.
     * The server URL row stays visible in both modes. Any previous inline error is cleared.
     */
    private void applyCredentialMode(boolean useActivationKey) {
        activationKeyMode = useActivationKey;

        if (activationKeyLayout != null) {
            activationKeyLayout.setVisibility(useActivationKey ? View.VISIBLE : View.GONE);
        }
        if (unlockLayout != null) {
            unlockLayout.setVisibility(useActivationKey ? View.GONE : View.VISIBLE);
        }
        if (passwordLayout != null) {
            passwordLayout.setVisibility(useActivationKey ? View.GONE : View.VISIBLE);
        }
        if (credentialModeToggle != null) {
            credentialModeToggle.setText(useActivationKey
                ? R.string.use_username_password
                : R.string.use_activation_key);
        }
        if (stateTextView != null) {
            stateTextView.setText("");
        }
    }

    /**
     * Decode an activation key into the username + password it encodes. The format must match the
     * server's {@code issue-key} output byte-for-byte:
     * {@code base64url_nopad(username_utf8) + "." + base64url_nopad(password_utf8)}.
     * Since '.' never appears in the URL-safe base64 alphabet, the split on the single '.' is
     * unambiguous. Returns {@code null} when the input is malformed (not exactly one '.', or either
     * side fails URL-safe, no-padding base64 decode to UTF-8).
     */
    @Nullable
    private UserCredentials decodeActivationKey(@Nullable String activationKey) {
        if (activationKey == null) {
            return null;
        }
        final String trimmed = activationKey.trim();
        final int separatorIndex = trimmed.indexOf('.');
        // Exactly one '.' is required: present, and the only one.
        if (separatorIndex < 0 || trimmed.indexOf('.', separatorIndex + 1) >= 0) {
            return null;
        }
        final String usernamePart = trimmed.substring(0, separatorIndex);
        final String passwordPart = trimmed.substring(separatorIndex + 1);

        final String username = decodeBase64UrlNoPadToUtf8(usernamePart);
        final String password = decodeBase64UrlNoPadToUtf8(passwordPart);
        if (username == null || password == null) {
            return null;
        }
        return new UserCredentials(username, password);
    }

    /**
     * Decode a single URL-safe, no-padding base64 segment to a UTF-8 string, returning {@code null}
     * on any decode failure (the strict flag makes malformed input fail rather than be silently
     * accepted).
     */
    @Nullable
    private String decodeBase64UrlNoPadToUtf8(@NonNull String segment) {
        try {
            final byte[] decoded = Base64.decode(
                segment,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP
            );
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void checkForValidCredentialsInBackground() {
        // In case there are credentials, we can validate them and skip this activity so that the
        // user does not have to enter them again.
        if (dependencies.getLicenseService().hasCredentials()) {
            backgroundExecutor.getValue().execute(new BackgroundTask<Boolean>() {
                @Override
                public void runBefore() {
                    // Nothing to do
                }

                @Override
                public Boolean runInBackground() {
                    return dependencies.getLicenseService().validate(false) == null;
                }

                @Override
                public void runAfter(Boolean result) {
                    if (Boolean.TRUE.equals(result)) {
                        logger.info("Credentials are available and valid");
                        ConfigUtils.recreateActivity(EnterSerialActivity.this);
                    }
                }
            });
        }
    }

    private void setupForShopBuild() {
        licenseKeyOrUsernameText.addTextChangedListener(new PasswordWatcher());
        licenseKeyOrUsernameText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        licenseKeyOrUsernameText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(11)});
        licenseKeyOrUsernameText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                if (licenseKeyOrUsernameText.getText().length() == 11) {
                    doUnlock();
                }
                return true;
            }
            return false;
        });
        unlockButton = findViewById(R.id.unlock_button);
        unlockButton.setOnClickListener(v -> doUnlock());

        this.setLoginButtonEnabled(false);
    }

    @SuppressLint("DiscouragedApi")
    private void setupForWorkBuild() {
        licenseKeyOrUsernameText.addTextChangedListener(new TextChangeWatcher());
        passwordText.addTextChangedListener(new TextChangeWatcher());

        // We need to use getResources().getIdentifier(...) because of flavor specific layout files for this fragment
        loginButtonCompose = findViewById(getResources().getIdentifier("unlock_button_work_compose", "id", getPackageName()));
        loginButtonCompose.setOnClickListener(v -> doUnlock());

        // We need to use getResources().getIdentifier(...) because of flavor specific layout files for this fragment
        TextView lostCredentialsHelp = findViewById(getResources().getIdentifier("work_lost_credential_help", "id", getPackageName()));
        lostCredentialsHelp.setText(getString(R.string.work_lost_credentials_help));

        // Always enable for work build
        setLoginButtonEnabled(true);

        // Disable the edit texts based on whether there are unchangeable preset values that must be
        // used.
        String configuredUsername = getConfiguredUsername();
        if (configuredUsername != null) {
            licenseKeyOrUsernameText.setText(configuredUsername);
            licenseKeyOrUsernameText.setEnabled(false);
        }

        String configuredPassword = getConfiguredPassword();
        if (configuredPassword != null) {
            logger.info("A password is configured, disabling password field");
            passwordText.setEnabled(false);
        }

        if (ConfigUtils.isOnPremBuild()) {
            String configuredServerUrl = getConfiguredOnPremServerUrl();
            if (configuredServerUrl != null) {
                serverText.setText(getBaseUrl(configuredServerUrl));
                serverText.setEnabled(false);
            }

            if (ConfigUtils.isWhitelabelOnPremBuild(this) && configuredServerUrl == null) {
                // In case of a whitelabel build without pre configured server url, we do not want
                // the server url to be edited even if there is no configured server url. App setup
                // won't be possible.
                serverText.setEnabled(false);
                setLoginButtonEnabled(false);
                onMissingPresetUrl();
            }
        }
    }

    private void handleUrlIntent(@Nullable Intent intent) {
        // In case the activation link is not available or in the wrong format, it will be null.
        Uri activationLink = getActivationLink(intent);

        if (ConfigUtils.isSerialLicenseValid()) {
            if (activationLink != null) {
                // We inform the user only if there was an active attempt to re-license the app.
                Toast.makeText(this, R.string.already_licensed, Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }

        if (activationLink != null) {
            // In case there is an activation link that could be checked, we parse it, combine it
            // with mdm values (if work) and check the resulting credentials.
            checkActivationLinkAndMdm(activationLink);
        } else if (ConfigUtils.isWorkRestricted()) {
            // Otherwise we just check whether we have all the information solely from mdm config.
            String username = getConfiguredUsername();
            String password = getConfiguredPassword();

            if (ConfigUtils.isOnPremBuild()) {
                String server = getConfiguredOnPremServerUrl();

                if (!TestUtil.isEmptyOrNull(username) && !TestUtil.isEmptyOrNull(password) && !TestUtil.isEmptyOrNull(server)) {
                    check(new UserCredentials(username, password), server);
                }
            } else {
                if (!TestUtil.isEmptyOrNull(username) && !TestUtil.isEmptyOrNull(password)) {
                    check(new UserCredentials(username, password), null);
                }
            }
        }
    }

    private void setLoginButtonEnabled(final boolean isEnabled) {
        if (!ConfigUtils.isWorkBuild() && !ConfigUtils.isOnPremBuild()) {
            if (this.unlockButton != null) {
                unlockButton.setClickable(isEnabled);
                unlockButton.setEnabled(isEnabled);
            }
        } else if (this.loginButtonCompose != null) {
            loginButtonCompose.setButtonEnabled(isEnabled);
        }
    }

    private void checkActivationLinkAndMdm(@NonNull Uri activationLink) {
        String query = activationLink.getQuery();
        if (query != null && !query.isEmpty()) {
            if (dependencies.getLicenseService() instanceof LicenseServiceUser) {
                checkWorkActivationLinkAndMdm(activationLink);
            } else {
                checkPrivateActivationLink(activationLink);
            }
        }
    }

    private void checkPrivateActivationLink(@NonNull Uri data) {
        final String key = data.getQueryParameter("key");
        if (!TestUtil.isEmptyOrNull(key)) {
            check(new SerialCredentials(key), null);
        }
    }

    private void checkWorkActivationLinkAndMdm(@NonNull Uri data) {
        final String intentUsername = getIntentUsername(data);
        final String intentPassword = getIntentPassword(data);
        final String intentServerUrl = getIntentServerUrl(data);

        final String configuredUsername = getConfiguredUsername();
        final String configuredPassword = getConfiguredPassword();
        final String configuredServerUrl = getConfiguredOnPremServerUrl();

        final String effectiveUsername = configuredUsername != null ? configuredUsername : intentUsername;
        final String effectivePassword = configuredPassword != null ? configuredPassword : intentPassword;
        final String effectiveServerUrl;

        if (ConfigUtils.isWhitelabelOnPremBuild(this)) {
            // Assert that we have a server url on the whitelabel build. If we don't, we abort
            // parsing the uri.
            if (configuredServerUrl == null) {
                return;
            }

            // Check that the intent server url matches the configured server url
            if (intentServerUrl != null && !intentServerUrl.isBlank()) {
                if (!configuredServerUrl.equals(intentServerUrl)) {
                    onIntentServerUrlMismatch();
                    return;
                }
            }

            // The effective server url is always the configured server url on whitelabel builds.
            effectiveServerUrl = configuredServerUrl;
        } else if (ConfigUtils.isOnPremBuild()) {
            // Check that both server urls equal if they are both defined
            if (configuredServerUrl != null && intentServerUrl != null && !configuredServerUrl.equals(intentServerUrl)) {
                onIntentServerUrlMismatch();
                return;
            }

            // If there is a configured server url, use it. Otherwise try the intent server url
            effectiveServerUrl = configuredServerUrl != null ? configuredServerUrl : intentServerUrl;
        } else {
            // On non-onprem builds we never use a server url
            effectiveServerUrl = null;
        }

        // Pre-fill the available credentials into the edit texts.
        licenseKeyOrUsernameText.setText(effectiveUsername);
        // We must not display the password that has been set by mdm. However, we can show
        // the password provided by the activation link.
        if (configuredPassword == null) {
            passwordText.setText(intentPassword);
        }

        // Check the credentials if available
        if (ConfigUtils.isOnPremBuild()) {
            // Also show the server url if available
            serverText.setText(effectiveServerUrl != null ? getBaseUrl(effectiveServerUrl) : null);

            // Check license if credentials and server url are available
            if (!TestUtil.isEmptyOrNull(effectiveUsername) && !TestUtil.isEmptyOrNull(effectivePassword) && !TestUtil.isEmptyOrNull(effectiveServerUrl)) {
                check(new UserCredentials(effectiveUsername, effectivePassword), effectiveServerUrl);
            }
        } else {
            // Check license if credentials are available
            if (!TestUtil.isEmptyOrNull(effectiveUsername) && !TestUtil.isEmptyOrNull(effectivePassword)) {
                check(new UserCredentials(effectiveUsername, effectivePassword), null);
            }
        }
    }

    private void doUnlock() {
        // hide keyboard to make error message visible on low resolution displays
        EditTextUtil.hideSoftKeyboard(this.licenseKeyOrUsernameText);

        this.setLoginButtonEnabled(false);

        if (ConfigUtils.isOnPremBuild()) {
            String configuredUsername = getConfiguredUsername();
            String configuredPassword = getConfiguredPassword();
            String configuredServerUrl = getConfiguredOnPremServerUrl();

            String effectiveUsername;
            String effectivePassword;

            // In activation-key mode, decode the single key into username + password and feed the
            // exact same license-credentials flow used by the classic username/password mode. MDM
            // preset credentials (if any) always take precedence over what the user typed.
            if (activationKeyMode && activationKeyText != null && configuredUsername == null && configuredPassword == null) {
                UserCredentials decoded = decodeActivationKey(activationKeyText.getText().toString());
                if (decoded == null) {
                    this.setLoginButtonEnabled(true);
                    this.stateTextView.setText(getString(R.string.invalid_activation_key));
                    return;
                }
                effectiveUsername = decoded.username;
                effectivePassword = decoded.password;
            } else {
                effectiveUsername = configuredUsername != null ? configuredUsername : licenseKeyOrUsernameText.getText().toString();
                effectivePassword = configuredPassword != null ? configuredPassword : passwordText.getText().toString();
            }

            String effectiveServerUrl = configuredServerUrl != null ? configuredServerUrl : serverText.getText().toString();

            if (!TestUtil.isEmptyOrNull(effectiveUsername) && !TestUtil.isEmptyOrNull(effectivePassword) && !TestUtil.isEmptyOrNull(effectiveServerUrl)) {
                this.check(new UserCredentials(effectiveUsername, effectivePassword), effectiveServerUrl);
            } else {
                this.setLoginButtonEnabled(true);
                this.stateTextView.setText(getString(R.string.invalid_input));
            }
        } else if (ConfigUtils.isWorkBuild()) {
            String configuredUsername = getConfiguredUsername();
            String configuredPassword = getConfiguredPassword();

            String effectiveUsername = configuredUsername != null ? configuredUsername : licenseKeyOrUsernameText.getText().toString();
            String effectivePassword = configuredPassword != null ? configuredPassword : passwordText.getText().toString();

            if (!TestUtil.isEmptyOrNull(effectiveUsername) && !TestUtil.isEmptyOrNull(effectivePassword)) {
                this.check(new UserCredentials(effectiveUsername, effectivePassword), null);
            } else {
                this.setLoginButtonEnabled(true);
                this.stateTextView.setText(getString(R.string.invalid_input));
            }
        } else {
            this.check(new SerialCredentials(this.licenseKeyOrUsernameText.getText().toString()), null);
        }
    }

    private class PasswordWatcher extends SimpleTextWatcher {
        @Override
        public void afterTextChanged(@NonNull Editable editable) {
            String initial = editable.toString();
            String processed = initial.replaceAll("[^a-zA-Z0-9]", "");
            processed = processed.replaceAll("([a-zA-Z0-9]{5})(?=[a-zA-Z0-9])", "$1-");

            if (!initial.equals(processed)) {
                editable.replace(0, initial.length(), processed);
            }

            //enable login only if the length of the key is 11 chars
            setLoginButtonEnabled(editable.length() == 11);
        }
    }

    public class TextChangeWatcher extends SimpleTextWatcher {
        @Override
        public void afterTextChanged(@NonNull Editable editable) {
            if (stateTextView != null) {
                stateTextView.setText("");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (licenseKeyOrUsernameText != null && !TestUtil.isBlankOrNull(licenseKeyOrUsernameText.getText())) {
            outState.putString(BUNDLE_LICENSE_KEY, licenseKeyOrUsernameText.getText().toString());
        }

        if (passwordText != null && !TestUtil.isBlankOrNull(passwordText.getText())) {
            outState.putString(BUNDLE_PASSWORD, passwordText.getText().toString());
        }

        if (serverText != null && !TestUtil.isBlankOrNull(serverText.getText())) {
            outState.putString(BUNDLE_SERVER, serverText.getText().toString());
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void check(final LicenseCredentials credentials, String onPremServer) {
        if (ConfigUtils.isOnPremBuild()) {
            var oppfUrl = onPremServer != null
                ? getUrlToOppf(onPremServer)
                : null;
            var preferenceService = dependencies.getPreferenceService();
            preferenceService.setOppfUrl(oppfUrl);
            preferenceService.setLicenseUsername(((UserCredentials) credentials).username);
            preferenceService.setLicensePassword(((UserCredentials) credentials).password);
        }

        new AsyncTask<Void, Void, String>() {
            @Override
            protected void onPreExecute() {
                GenericProgressDialog.newInstance(R.string.flavored__checking_serial, R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_CHECKING);
            }

            @Override
            protected String doInBackground(Void... voids) {
                String error = getString(R.string.error);
                try {
                    LicenseService licenseService = dependencies.getLicenseService();
                    error = licenseService.validate(credentials);
                    if (error == null) {
                        // validated
                        if (ConfigUtils.isWorkBuild()) {
                            AppRestrictionService.getInstance()
                                .fetchAndStoreWorkMDMSettings(
                                    dependencies.getApiConnector(),
                                    (UserCredentials) credentials
                                );
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
                return error;
            }

            @Override
            protected void onPostExecute(String error) {
                setLoginButtonEnabled(true);
                DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_CHECKING, true);
                if (error == null) {
                    ConfigUtils.recreateActivity(EnterSerialActivity.this);
                } else {
                    changeState(error);
                    // F1Whisper: on an OnPrem setup failure (typically "Failed to fetch OnPrem
                    // config" — the OPPF fetch is blocked/censored), offer the connectivity
                    // troubleshooter so the user can diagnose and share what is blocking the path.
                    // Pre-auth: no identity yet, so the dialog derives ancillary hosts from the
                    // entered host (no cached config).
                    if (ConfigUtils.isOnPremBuild()) {
                        offerConnectivityTroubleshooter(onPremServer);
                    }
                }
            }
        }.execute();
    }

    /**
     * F1Whisper: prompt the user to run the connectivity troubleshooter after an OnPrem setup
     * failure, then launch {@link ConnectivityDiagnosticsDialog} against the entered server host.
     * Guarded so a missing/blank host silently skips the offer (nothing to probe).
     */
    private void offerConnectivityTroubleshooter(@Nullable String onPremServer) {
        final String host = extractProbeHost(onPremServer);
        if (host == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connectivity_probe_dialog_title)
            .setMessage(R.string.connectivity_probe_verdict_partial_failure)
            .setPositiveButton(R.string.connectivity_probe_dialog_title, (dialog, which) ->
                ConnectivityDiagnosticsDialog.show(getSupportFragmentManager(), host, false))
            .setNegativeButton(R.string.close, null)
            .show();
    }

    /**
     * Derive the bare host to probe from the entered server value (e.g. "thm.f1tech.info",
     * "https://thm.f1tech.info" or a full ".../prov/config.oppf" URL all collapse to
     * "thm.f1tech.info"). Returns null when no usable host can be derived.
     */
    @Nullable
    private String extractProbeHost(@Nullable String onPremServer) {
        if (onPremServer == null || onPremServer.isBlank()) {
            return null;
        }
        try {
            final String oppfUrl = getUrlToOppf(onPremServer);
            final String host = Uri.parse(oppfUrl).getHost();
            return (host != null && !host.isBlank()) ? host : null;
        } catch (Exception e) {
            logger.warn("Could not derive probe host from server input", e);
            return null;
        }
    }

    /**
     * Shows an error to the user that the provided server url in the activation link does not match
     * the requirements, i.e., either the server url set by mdm (onprem) or the pre-configured
     * server url (whitelabel onprem).
     */
    private void onIntentServerUrlMismatch() {
        logger.error("The intent's server url does not match the requirements");
        changeState(getString(R.string.error_preset_onprem_url_mismatch_intent));
    }

    /**
     * Shows an error to the user that there is no pre-configured server url. This can happen when
     * a whitelabel build does not have the preconfigured url set.
     */
    private void onMissingPresetUrl() {
        logger.error("The preset server url is missing for a whitelabel build");
        changeState("No preset OPPF Url");
    }

    private void changeState(String state) {
        this.stateTextView.setText(state);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened or orientation changes
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleUrlIntent(intent);
    }

    @Nullable
    private String getPresetOnPremServerUrlIfWhiteLabeled() {
        //noinspection ConstantValue
        return ConfigUtils.isWhitelabelOnPremBuild(this) && BuildConfig.PRESET_OPPF_URL != null
            ? BuildConfig.PRESET_OPPF_URL
            : null;
    }

    @NonNull
    private String getUrlToOppf(@NonNull String url) {
        // Normalize user input so "<host>", "https://<host>" and "http://<host>/" all collapse to the
        // same canonical https URL. Strip any leading scheme and surrounding whitespace, then drop
        // trailing slashes, before we (re)apply the https scheme and expand to the provisioning path.
        url = url.trim();
        if (url.startsWith("https://")) {
            url = url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            url = url.substring("http://".length());
        }

        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        url = "https://" + url;

        if (!url.endsWith(".oppf")) {
            // Automatically expand hostnames to default provisioning URL
            url += "/prov/config.oppf";
        }

        return url;
    }

    @NonNull
    private String getBaseUrl(@NonNull String url) {
        return url
            .replace("https://", "")
            .replace("/prov/config.oppf", "");
    }

    @Nullable
    private Uri getActivationLink(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }

        Uri data = intent.getData();
        String scheme = data != null ? data.getScheme() : null;
        if (scheme == null) {
            return null;
        }

        if (scheme.startsWith(BuildConfig.uriScheme)) {
            return data;
        } else if (scheme.startsWith("https")) {
            String path = data.getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }

            return path.startsWith("/license") ? data : null;
        } else {
            return null;
        }
    }

    /**
     * Get the username from the uri. Returns null if no username is present.
     */
    @Nullable
    private String getIntentUsername(@NonNull Uri uri) {
        return uri.getQueryParameter("username");
    }

    /**
     * Get the password from the uri. Returns null if no password is present.
     */
    @Nullable
    private String getIntentPassword(@NonNull Uri uri) {
        return uri.getQueryParameter("password");
    }

    /**
     * Get the server url and make it point directly to the oppf if possible. Returns null if no
     * server url is set.
     */
    @Nullable
    private String getIntentServerUrl(@NonNull Uri uri) {
        final String serverUrl = uri.getQueryParameter("server");
        return serverUrl != null ? getUrlToOppf(serverUrl) : null;
    }

    /**
     * Get the username configured via mdm. If no username is configured via mdm, null is returned.
     */
    @Nullable
    private String getConfiguredUsername() {
        return dependencies.getAppRestrictions().getLicenseUsername();
    }

    /**
     * Get the password configured via mdm. If no password is configured via mdm, null is returned.
     */
    @Nullable
    private String getConfiguredPassword() {
        return dependencies.getAppRestrictions().getLicensePassword();
    }

    /**
     * Get the configure onprem server url and make it point to the oppf file if possible. On
     * whitelabel builds this is the pre-configured url, on normal onprem builds it is the mdm
     * defined server url or null. On non-onprem builds, null is returned.
     */
    @Nullable
    private String getConfiguredOnPremServerUrl() {
        if (!ConfigUtils.isOnPremBuild()) {
            return null;
        }

        final String serverUrl = ConfigUtils.isWhitelabelOnPremBuild(this)
            ? getPresetOnPremServerUrlIfWhiteLabeled()
            : dependencies.getAppRestrictions().getOnPremServer();

        return serverUrl != null ? getUrlToOppf(serverUrl) : null;
    }

    @NonNull
    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, EnterSerialActivity.class);
    }
}
