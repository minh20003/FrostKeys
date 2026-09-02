/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ResourceUtils;

public final class KeyVisualAttributes {
    @Nullable
    public final Typeface mTypeface;

    public final float mLetterRatio;
    public final int mLetterSize;
    public final float mLabelRatio;
    public final int mLabelSize;
    public final float mLargeLetterRatio;
    public final float mHintLetterRatio;
    public final float mShiftedLetterHintRatio;
    public final float mHintLabelRatio;
    public final float mPreviewTextRatio;

    public final int mTextColor;
    public final int mTextInactivatedColor;
    public final int mTextShadowColor;
    public final int mFunctionalTextColor;
    public final int mHintLetterColor;
    public final int mHintLabelColor;
    public final int mShiftedLetterHintInactivatedColor;
    public final int mShiftedLetterHintActivatedColor;
    public final int mPreviewTextColor;

    public final float mHintLabelVerticalAdjustment;
    public final float mLabelOffCenterRatio;
    public final float mHintLabelOffCenterRatio;

    private static final int KEY_TYPEFACE = 0;
    private static final int KEY_LETTER_SIZE = 1;
    private static final int KEY_LABEL_SIZE = 2;
    private static final int KEY_LARGE_LETTER_RATIO = 3;
    private static final int KEY_HINT_LETTER_RATIO = 4;
    private static final int KEY_SHIFTED_LETTER_HINT_RATIO = 5;
    private static final int KEY_HINT_LABEL_RATIO = 6;
    private static final int KEY_PREVIEW_TEXT_RATIO = 7;
    private static final int KEY_TEXT_INACTIVATED_COLOR = 8;
    private static final int KEY_TEXT_SHADOW_COLOR = 9;
    private static final int KEY_SHIFTED_LETTER_HINT_INACTIVATED_COLOR = 10;
    private static final int KEY_SHIFTED_LETTER_HINT_ACTIVATED_COLOR = 11;
    private static final int KEY_HINT_LABEL_VERTICAL_ADJUSTMENT = 12;
    private static final int KEY_LABEL_OFF_CENTER_RATIO = 13;
    private static final int KEY_HINT_LABEL_OFF_CENTER_RATIO = 14;

    private static final int[] KEY_ATTRIBUTE_IDS = {
        R.styleable.Keyboard_Key_keyTypeface,
        R.styleable.Keyboard_Key_keyLetterSize,
        R.styleable.Keyboard_Key_keyLabelSize,
        R.styleable.Keyboard_Key_keyLargeLetterRatio,
        R.styleable.Keyboard_Key_keyHintLetterRatio,
        R.styleable.Keyboard_Key_keyShiftedLetterHintRatio,
        R.styleable.Keyboard_Key_keyHintLabelRatio,
        R.styleable.Keyboard_Key_keyPreviewTextRatio,
        R.styleable.Keyboard_Key_keyTextInactivatedColor,
        R.styleable.Keyboard_Key_keyTextShadowColor,
        R.styleable.Keyboard_Key_keyShiftedLetterHintInactivatedColor,
        R.styleable.Keyboard_Key_keyShiftedLetterHintActivatedColor,
        R.styleable.Keyboard_Key_keyHintLabelVerticalAdjustment,
        R.styleable.Keyboard_Key_keyLabelOffCenterRatio,
        R.styleable.Keyboard_Key_keyHintLabelOffCenterRatio
    };
    private static final int[] KEYBOARD_VIEW_ATTRIBUTE_IDS = {
        R.styleable.KeyboardView_keyTypeface,
        R.styleable.KeyboardView_keyLetterSize,
        R.styleable.KeyboardView_keyLabelSize,
        R.styleable.KeyboardView_keyLargeLetterRatio,
        R.styleable.KeyboardView_keyHintLetterRatio,
        R.styleable.KeyboardView_keyShiftedLetterHintRatio,
        R.styleable.KeyboardView_keyHintLabelRatio,
        R.styleable.KeyboardView_keyPreviewTextRatio,
        R.styleable.KeyboardView_keyTextInactivatedColor,
        R.styleable.KeyboardView_keyTextShadowColor,
        R.styleable.KeyboardView_keyShiftedLetterHintInactivatedColor,
        R.styleable.KeyboardView_keyShiftedLetterHintActivatedColor,
        R.styleable.KeyboardView_keyHintLabelVerticalAdjustment,
        R.styleable.KeyboardView_keyLabelOffCenterRatio,
        R.styleable.KeyboardView_keyHintLabelOffCenterRatio
    };

    @Nullable
    public static KeyVisualAttributes newInstance(@NonNull final TypedArray keyAttr) {
        return newInstance(keyAttr, KEY_ATTRIBUTE_IDS);
    }

    /**
     * Reads key visual attributes from the matching KeyboardView styleable. The attribute IDs are
     * the same as Keyboard_Key, but TypedArray indices are specific to each styleable array.
     */
    @Nullable
    public static KeyVisualAttributes newInstanceForKeyboardView(@NonNull final TypedArray keyAttr) {
        return newInstance(keyAttr, KEYBOARD_VIEW_ATTRIBUTE_IDS);
    }

    @Nullable
    private static KeyVisualAttributes newInstance(@NonNull final TypedArray keyAttr,
            @NonNull final int[] attributeIds) {
        final int indexCount = keyAttr.getIndexCount();
        for (int i = 0; i < indexCount; i++) {
            final int attrId = keyAttr.getIndex(i);
            for (final int visualAttributeId : attributeIds) {
                if (attrId == visualAttributeId) {
                    return new KeyVisualAttributes(keyAttr, attributeIds);
                }
            }
        }
        return null;
    }

    private KeyVisualAttributes(@NonNull final TypedArray keyAttr,
            @NonNull final int[] attributeIds) {
        if (keyAttr.hasValue(attributeIds[KEY_TYPEFACE])) {
            mTypeface = Typeface.defaultFromStyle(
                    keyAttr.getInt(attributeIds[KEY_TYPEFACE], Typeface.NORMAL));
        } else {
            mTypeface = null;
        }

        mLetterRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_LETTER_SIZE]);
        mLetterSize = ResourceUtils.getDimensionPixelSize(keyAttr,
                attributeIds[KEY_LETTER_SIZE]);
        mLabelRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_LABEL_SIZE]);
        mLabelSize = ResourceUtils.getDimensionPixelSize(keyAttr,
                attributeIds[KEY_LABEL_SIZE]);
        mLargeLetterRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_LARGE_LETTER_RATIO]);
        mHintLetterRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_HINT_LETTER_RATIO]);
        mShiftedLetterHintRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_SHIFTED_LETTER_HINT_RATIO]);
        mHintLabelRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_HINT_LABEL_RATIO]);
        mPreviewTextRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_PREVIEW_TEXT_RATIO]);

        // todo: check what colors do, and if irrelevant and no plan to use -> remove here and from attr
        final Colors colors = Settings.getValues().mColors;
        mTextColor = colors.get(ColorType.KEY_TEXT);
        // when? -> isShiftedLetterActivated, which is a label flag
        mTextInactivatedColor = keyAttr.getColor(
                attributeIds[KEY_TEXT_INACTIVATED_COLOR], 0);
        // when? -> mKeyTextShadowRadius > 0, but it's always set to -1 (in theme) -> maybe play with this?
        mTextShadowColor = keyAttr.getColor(attributeIds[KEY_TEXT_SHADOW_COLOR], 0);
        mFunctionalTextColor = colors.get(ColorType.FUNCTIONAL_KEY_TEXT);
        mHintLetterColor = colors.get(ColorType.KEY_HINT_TEXT);
        mHintLabelColor = colors.get(ColorType.KEY_TEXT);
        // when? -> hasShiftedLetterHint and not isShiftedLetterActivated -> both are label flags
        mShiftedLetterHintInactivatedColor = keyAttr.getColor(
                attributeIds[KEY_SHIFTED_LETTER_HINT_INACTIVATED_COLOR], 0);
        // when? -> hasShiftedLetterHint and isShiftedLetterActivated -> both are label flags
        mShiftedLetterHintActivatedColor = keyAttr.getColor(
                attributeIds[KEY_SHIFTED_LETTER_HINT_ACTIVATED_COLOR], 0);
        mPreviewTextColor = colors.get(ColorType.KEY_PREVIEW_TEXT);

        mHintLabelVerticalAdjustment = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_HINT_LABEL_VERTICAL_ADJUSTMENT], 0.0f);
        mLabelOffCenterRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_LABEL_OFF_CENTER_RATIO], 0.0f);
        mHintLabelOffCenterRatio = ResourceUtils.getFraction(keyAttr,
                attributeIds[KEY_HINT_LABEL_OFF_CENTER_RATIO], 0.0f);
    }
}
