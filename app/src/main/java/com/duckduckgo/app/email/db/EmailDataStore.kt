/*
 * Copyright (c) 2020 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.email.db

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.duckduckgo.app.pixels.AppPixelName
import com.duckduckgo.app.statistics.pixels.Pixel
import java.io.IOException
import java.security.GeneralSecurityException

interface EmailDataStore {
    var emailToken: String?
    var nextAlias: String?
    var emailUsername: String?
    var cohort: String?
    var lastUsedDate: String?
    fun canUseEncryption(): Boolean
}

class EmailEncryptedSharedPreferences(
    private val context: Context,
    private val pixel: Pixel,
) : EmailDataStore {

    private val encryptedPreferences: SharedPreferences? by lazy { encryptedPreferences() }

    @Synchronized
    private fun encryptedPreferences(): SharedPreferences? {
        try {
            return EncryptedSharedPreferences.create(
                context,
                FILENAME,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            when (t) {
                is IOException -> pixel.enqueueFire(AppPixelName.ENCRYPTED_IO_EXCEPTION)
                is GeneralSecurityException -> pixel.enqueueFire(AppPixelName.ENCRYPTED_GENERAL_EXCEPTION)
                else -> { /* noop */ }
            }
        }
        return null
    }

    override var emailToken: String?
        get() = encryptedPreferences?.getString(KEY_EMAIL_TOKEN, null)
        set(value) {
            encryptedPreferences?.edit(commit = true) {
                if (value == null) {
                    remove(KEY_EMAIL_TOKEN)
                } else {
                    putString(KEY_EMAIL_TOKEN, value)
                }
            }
        }

    override var nextAlias: String?
        get() = encryptedPreferences?.getString(KEY_NEXT_ALIAS, null)
        set(value) {
            encryptedPreferences?.edit(commit = true) {
                if (value == null) {
                    remove(KEY_NEXT_ALIAS)
                } else {
                    putString(KEY_NEXT_ALIAS, value)
                }
            }
        }

    override var emailUsername: String?
        get() = encryptedPreferences?.getString(KEY_EMAIL_USERNAME, null)
        set(value) {
            encryptedPreferences?.edit(commit = true) {
                if (value == null) {
                    remove(KEY_EMAIL_USERNAME)
                } else {
                    putString(KEY_EMAIL_USERNAME, value)
                }
            }
        }

    override var cohort: String?
        get() = encryptedPreferences?.getString(KEY_COHORT, null)
        set(value) {
            encryptedPreferences?.edit(commit = true) {
                if (value == null) {
                    remove(KEY_COHORT)
                } else {
                    putString(KEY_COHORT, value)
                }
            }
        }

    override var lastUsedDate: String?
        get() = encryptedPreferences?.getString(KEY_LAST_USED_DATE, null)
        set(value) {
            encryptedPreferences?.edit(commit = true) {
                if (value == null) {
                    remove(KEY_LAST_USED_DATE)
                } else {
                    putString(KEY_LAST_USED_DATE, value)
                }
            }
        }

    override fun canUseEncryption(): Boolean = encryptedPreferences != null

    companion object {
        const val FILENAME = "com.duckduckgo.app.email.settings"
        const val KEY_EMAIL_TOKEN = "KEY_EMAIL_TOKEN"
        const val KEY_EMAIL_USERNAME = "KEY_EMAIL_USERNAME"
        const val KEY_NEXT_ALIAS = "KEY_NEXT_ALIAS"
        const val KEY_COHORT = "KEY_COHORT"
        const val KEY_LAST_USED_DATE = "KEY_LAST_USED_DATE"
    }
}
