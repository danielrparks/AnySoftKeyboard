package com.anysoftkeyboard.ime;

import static com.anysoftkeyboard.ime.AnySoftKeyboardIncognito.isNumberPassword;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import com.anysoftkeyboard.android.NightMode;
import com.anysoftkeyboard.android.PowerSaving;
import com.anysoftkeyboard.api.KeyCodes;
import com.anysoftkeyboard.base.utils.Logger;
import com.anysoftkeyboard.keyboards.Keyboard;
import com.anysoftkeyboard.keyboards.views.AnyKeyboardViewBase;
import com.anysoftkeyboard.keyboards.views.preview.AboveKeyPositionCalculator;
import com.anysoftkeyboard.keyboards.views.preview.AboveKeyboardPositionCalculator;
import com.anysoftkeyboard.keyboards.views.preview.KeyPreviewsController;
import com.anysoftkeyboard.keyboards.views.preview.KeyPreviewsManager;
import com.anysoftkeyboard.keyboards.views.preview.NullKeyPreviewsManager;
import com.anysoftkeyboard.keyboards.views.preview.PositionCalculator;
import com.anysoftkeyboard.prefs.AnimationsLevel;
import com.anysoftkeyboard.rx.GenericOnError;
import com.anysoftkeyboard.theme.KeyboardTheme;
import com.github.karczews.rxbroadcastreceiver.RxBroadcastReceivers;
import com.menny.android.anysoftkeyboard.R;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

public abstract class AnySoftKeyboardPressEffects extends AnySoftKeyboardClipboard {

    private AudioManager mAudioManager;
    private static final float SILENT = 0.0f;
    private static final float SYSTEM_VOLUME = -1.0f;
    private float mCustomSoundVolume = SILENT;

    private Vibrator mVibrator;
    private Object mVibrationEffect; // will be Integer if API < 26; else VibrationEffect
    private Object mVibrationEffectForLongPress;
    @NonNull private KeyPreviewsController mKeyPreviewController = new NullKeyPreviewsManager();

    @NonNull private final PublishSubject<Long> mKeyPreviewSubject = PublishSubject.create();

    @NonNull
    private final PublishSubject<Boolean> mKeyPreviewForPasswordSubject = PublishSubject.create();

    @Override
    public void onCreate() {
        super.onCreate();

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        addDisposable(
                Observable.combineLatest(
                                PowerSaving.observePowerSavingState(
                                        getApplicationContext(),
                                        R.string.settings_key_power_save_mode_sound_control),
                                NightMode.observeNightModeState(
                                        getApplicationContext(),
                                        R.string.settings_key_night_mode_sound_control,
                                        R.bool.settings_default_true),
                                RxBroadcastReceivers.fromIntentFilter(
                                                getApplicationContext(),
                                                new IntentFilter(
                                                        AudioManager.RINGER_MODE_CHANGED_ACTION))
                                        .startWith(new Intent()),
                                prefs().getBoolean(
                                                R.string.settings_key_sound_on,
                                                R.bool.settings_default_sound_on)
                                        .asObservable(),
                                prefs().getBoolean(
                                                R.string.settings_key_use_custom_sound_volume,
                                                R.bool.settings_default_false)
                                        .asObservable(),
                                prefs().getInteger(
                                                R.string.settings_key_custom_sound_volume,
                                                R.integer.settings_default_zero_value)
                                        .asObservable(),
                                (powerState,
                                        nightState,
                                        soundIntent,
                                        soundOn,
                                        useCustomVolume,
                                        customVolumeLevel) -> {
                                    if (powerState) return SILENT;
                                    if (nightState) return SILENT;
                                    if (mAudioManager.getRingerMode()
                                            != AudioManager.RINGER_MODE_NORMAL) return SILENT;
                                    if (!soundOn) return SILENT;

                                    if (useCustomVolume) {
                                        return customVolumeLevel / 100f;
                                    } else {
                                        return SYSTEM_VOLUME;
                                    }
                                })
                        .subscribe(
                                customVolume -> {
                                    if (mCustomSoundVolume != customVolume) {
                                        if (customVolume == SILENT) {
                                            mAudioManager.unloadSoundEffects();
                                        } else if (mCustomSoundVolume == SILENT) {
                                            mAudioManager.loadSoundEffects();
                                        }
                                    }
                                    mCustomSoundVolume = customVolume;
                                    // demo
                                    performKeySound(KeyCodes.SPACE);
                                },
                                t -> Logger.w(TAG, t, "Failed to read custom volume prefs")));

        addDisposable(
                Observable.combineLatest(
                                PowerSaving.observePowerSavingState(
                                        getApplicationContext(),
                                        R.string.settings_key_power_save_mode_vibration_control),
                                NightMode.observeNightModeState(
                                        getApplicationContext(),
                                        R.string.settings_key_night_mode_vibration_control,
                                        R.bool.settings_default_true),
                                prefs().getBoolean(
                                        R.string.settings_key_use_system_vibration,
                                        R.bool.settings_default_use_system_vibration)
                                        .asObservable(),
                                prefs().getInteger(
                                                R.string
                                                        .settings_key_vibrate_on_key_press_duration_int,
                                                R.integer
                                                        .settings_default_vibrate_on_key_press_duration_int)
                                        .asObservable(),
                                (powerState, nightState, systemVibration, vibrationDuration) ->
                                        powerState ? 0 : nightState ? 0 : getVibrationEffect(systemVibration, vibrationDuration, false))
                        .subscribe(
                                value -> {
                                    // don't do the demo when the preferences are first read
                                    boolean skipDemo = mVibrationEffect == null;
                                    mVibrationEffect = value;
                                    // demo
                                    if (!skipDemo) performKeyVibration(KeyCodes.SPACE, false);
                                },
                                t -> Logger.w(TAG, t, "Failed to get vibrate duration")));

        addDisposable(
                Observable.combineLatest(
                        prefs().getBoolean(
                                R.string.settings_key_use_system_vibration,
                                R.bool.settings_default_use_system_vibration)
                                .asObservable(),
                        prefs().getBoolean(
                                R.string.settings_key_vibrate_on_long_press,
                                R.bool.settings_default_vibrate_on_long_press)
                                .asObservable(),
                        (systemVibration, shouldVibrate) ->
                                getVibrationEffect(systemVibration, shouldVibrate ? 7 : 0, true))
                        .subscribe(
                                value -> {
                                    boolean skipDemo = mVibrationEffectForLongPress == null;
                                    mVibrationEffectForLongPress = value;
                                    // demo
                                    if (!skipDemo) performKeyVibration(KeyCodes.SPACE, true);
                                },
                                t -> Logger.w(TAG, t, "Failed to get vibrate duration")));

        addDisposable(
                Observable.combineLatest(
                                prefs().getBoolean(
                                                R.string.settings_key_key_press_shows_preview_popup,
                                                R.bool.settings_default_key_press_shows_preview_popup)
                                        .asObservable(),
                                AnimationsLevel.createPrefsObservable(this),
                                prefs().getString(
                                                R.string
                                                        .settings_key_key_press_preview_popup_position,
                                                R.string
                                                        .settings_default_key_press_preview_popup_position)
                                        .asObservable(),
                                mKeyPreviewSubject.startWith(0L),
                                mKeyPreviewForPasswordSubject
                                        .startWith(false)
                                        .distinctUntilChanged(),
                                this::createKeyPreviewController)
                        .subscribe(
                                controller ->
                                        onNewControllerOrInputView(controller, getInputView()),
                                GenericOnError.onError("key-preview-controller-setup")));
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        mKeyPreviewForPasswordSubject.onNext(isTextPassword(info) || isNumberPassword(info));
    }

    @VisibleForTesting
    protected void onNewControllerOrInputView(
            KeyPreviewsController controller, InputViewBinder inputViewBinder) {
        mKeyPreviewController.destroy();
        mKeyPreviewController = controller;
        if (inputViewBinder instanceof AnyKeyboardViewBase) {
            ((AnyKeyboardViewBase) inputViewBinder).setKeyPreviewController(controller);
        }
    }

    @Override
    protected void onThemeChanged(@NonNull KeyboardTheme theme) {
        super.onThemeChanged(theme);
        // triggering a new controller creation
        mKeyPreviewSubject.onNext(SystemClock.uptimeMillis());
    }

    @Override
    public View onCreateInputView() {
        final View view = super.onCreateInputView();
        // triggering a new controller creation
        mKeyPreviewSubject.onNext(SystemClock.uptimeMillis());
        return view;
    }

    @NonNull
    private KeyPreviewsController createKeyPreviewController(
            Boolean enabled,
            AnimationsLevel animationsLevel,
            String position,
            Long random /*ignoring this one*/,
            Boolean isPasswordField) {
        if (enabled
                && animationsLevel != AnimationsLevel.None
                && Boolean.FALSE.equals(isPasswordField)) {
            final PositionCalculator positionCalculator;
            final int maxPopups;
            if ("above_key".equals(position)) {
                positionCalculator = new AboveKeyPositionCalculator();
                maxPopups =
                        getResources().getInteger(R.integer.maximum_instances_of_preview_popups);
            } else {
                positionCalculator = new AboveKeyboardPositionCalculator();
                maxPopups = 1;
            }
            return new KeyPreviewsManager(this, positionCalculator, maxPopups);
        } else {
            return new NullKeyPreviewsManager();
        }
    }

    private void performKeySound(int primaryCode) {
        if (mCustomSoundVolume != SILENT && primaryCode != 0) {
            final int keyFX;
            switch (primaryCode) {
                case 13:
                case KeyCodes.ENTER:
                    keyFX = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case KeyCodes.DELETE:
                    keyFX = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case KeyCodes.SPACE:
                    keyFX = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
                case KeyCodes.SHIFT:
                case KeyCodes.SHIFT_LOCK:
                case KeyCodes.CTRL:
                case KeyCodes.CTRL_LOCK:
                case KeyCodes.MODE_ALPHABET:
                case KeyCodes.MODE_SYMOBLS:
                case KeyCodes.KEYBOARD_MODE_CHANGE:
                case KeyCodes.KEYBOARD_CYCLE_INSIDE_MODE:
                case KeyCodes.ALT:
                    keyFX = AudioManager.FX_KEY_CLICK;
                    break;
                default:
                    keyFX = AudioManager.FX_KEYPRESS_STANDARD;
            }
            mAudioManager.playSoundEffect(keyFX, mCustomSoundVolume);
        }
    }

    private void performKeyVibration(int primaryCode, boolean longPress) {
        final Object vibrationEffect = longPress ? mVibrationEffectForLongPress : mVibrationEffect;
        try {
            if (primaryCode != 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !(vibrationEffect instanceof Integer)) {
                    mVibrator.vibrate((VibrationEffect) vibrationEffect);
                } else if ((Integer) vibrationEffect > 0) {
                    mVibrator.vibrate((Integer) vibrationEffect);
                }
            }
        } catch (Exception e) {
            Logger.w(TAG, "Failed to interact with vibrator! Disabling for now.");
            mVibrationEffect = 0;
            mVibrationEffectForLongPress = 0;
        }
    }

    @NonNull
    private Object getVibrationEffect(boolean systemVibration, int vibrationDuration, boolean longPress) {
        if (vibrationDuration == 0) return 0;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && systemVibration) {
                return VibrationEffect.createPredefined(longPress ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK);
            } else {
                return VibrationEffect.createOneShot(vibrationDuration, VibrationEffect.DEFAULT_AMPLITUDE);
            }
        } else return vibrationDuration;
    }

    @VisibleForTesting
    protected AudioManager getAudioManager() {
        return mAudioManager;
    }

    @VisibleForTesting
    protected Vibrator getVibrator() {
        return mVibrator;
    }

    @Override
    public void onPress(int primaryCode) {
        super.onPress(primaryCode);

        performKeySound(primaryCode);
        performKeyVibration(primaryCode, false);
    }

    @Override
    public void onText(Keyboard.Key key, CharSequence text) {
        super.onText(key, text);

        performKeySound(KeyCodes.QUICK_TEXT);
        performKeyVibration(KeyCodes.QUICK_TEXT, false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mKeyPreviewSubject.onComplete();
        mKeyPreviewController.destroy();
        mAudioManager.unloadSoundEffects();
    }

    @Override
    public void onLongPressDone(@NonNull Keyboard.Key key) {
        performKeyVibration(key.getPrimaryCode(), true);
    }
}
