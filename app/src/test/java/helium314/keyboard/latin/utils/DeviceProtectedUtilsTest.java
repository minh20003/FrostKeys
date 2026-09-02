/*
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

public class DeviceProtectedUtilsTest {
    @Test
    public void copyNonSensitivePreferencesNeverCopiesLegacyCloudSecrets() {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("regular_boolean", true);
        source.put("regular_string", "safe value");
        source.put("pref_gemini_api_key", "gemini-secret");
        source.put("pref_klipy_api_key", "klipy-secret");
        source.put("klipy_customer_id", "customer-secret");
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);

        DeviceProtectedUtils.copyNonSensitivePreferences(source, editor);

        verify(editor).putBoolean("regular_boolean", true);
        verify(editor).putString("regular_string", "safe value");
        verify(editor, never()).putString(eq("pref_gemini_api_key"), anyString());
        verify(editor, never()).putString(eq("pref_klipy_api_key"), anyString());
        verify(editor, never()).putString(eq("klipy_customer_id"), anyString());
        verify(editor, never()).putBoolean(eq("pref_gemini_api_key"), anyBoolean());
        verify(editor, never()).putStringSet(eq("pref_gemini_api_key"), any());
    }
}
