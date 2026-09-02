/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.provider.ContactsContract.Contacts;
import helium314.keyboard.latin.utils.Log;

import helium314.keyboard.latin.ContactsManager.ContactsChangedListener;
import helium314.keyboard.latin.define.DebugFlags;
import helium314.keyboard.latin.utils.ExecutorUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A content observer that listens to updates to content provider {@link Contacts#CONTENT_URI}.
 */
public class ContactsContentObserver implements Runnable {
    private static final String TAG = "ContactsContentObserver";

    private final Context mContext;
    private final ContactsManager mManager;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private ContentObserver mContentObserver;
    private ContactsChangedListener mContactsChangedListener;
    private boolean mRegistered;

    public ContactsContentObserver(final ContactsManager manager, final Context context) {
        mManager = manager;
        mContext = context;
    }

    public synchronized void registerObserver(final ContactsChangedListener listener) {
        if (!mManager.isContactSuggestionsAccessEnabled()) {
            Log.i(TAG, "Contact suggestions are disabled or unavailable. Not registering the observer.");
            return;
        }
        if (mRegistered) return;

        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "registerObserver()");
        }
        mContactsChangedListener = listener;
        mContentObserver = new ContentObserver(null /* handler */) {
            @Override
            public void onChange(boolean self) {
                ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD)
                        .execute(ContactsContentObserver.this);
            }
        };
        final ContentResolver contentResolver = mContext.getContentResolver();
        try {
            contentResolver.registerContentObserver(Contacts.CONTENT_URI, true, mContentObserver);
            mRegistered = true;
        } catch (final SecurityException e) {
            // Permission can be revoked between the gate above and registration.
            mContentObserver = null;
            mContactsChangedListener = null;
            Log.i(TAG, "Contact permission changed before observer registration.", e);
        }
    }

    @Override
    public void run() {
        if (!mManager.isContactSuggestionsAccessEnabled()) {
            Log.i(TAG, "Contact suggestions are disabled or unavailable. Not updating contacts.");
            unregister();
            return;
        }

        if (!mRunning.compareAndSet(false /* expect */, true /* update */)) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "run() : Already running. Don't waste time checking again.");
            }
            return;
        }
        final ContactsChangedListener listener;
        synchronized (this) {
            listener = mContactsChangedListener;
        }
        if (listener != null && haveContentsChanged()) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "run() : Contacts have changed. Notifying listeners.");
            }
            listener.onContactsChange();
        }
        mRunning.set(false);
    }

    boolean haveContentsChanged() {
        if (!mManager.isContactSuggestionsAccessEnabled()) {
            Log.i(TAG, "Contact suggestions are disabled or unavailable. Marking contacts as unchanged.");
            return false;
        }

        final long startTime = SystemClock.uptimeMillis();
        final int contactCount = mManager.getContactCount();
        if (contactCount > ContactsDictionaryConstants.MAX_CONTACTS_PROVIDER_QUERY_LIMIT) {
            // If there are too many contacts then return false. In this rare case it is impossible
            // to include all of them anyways and the cost of rebuilding the dictionary is too high.
            // TODO: Sort and check only the most recent contacts?
            return false;
        }
        if (contactCount != mManager.getContactCountAtLastRebuild()) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "haveContentsChanged() : Count changed from "
                        + mManager.getContactCountAtLastRebuild() + " to " + contactCount);
            }
            return true;
        }
        final ArrayList<String> names = mManager.getValidNames(Contacts.CONTENT_URI);
        if (names.hashCode() != mManager.getHashCodeAtLastRebuild()) {
            return true;
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "haveContentsChanged() : No change detected in "
                    + (SystemClock.uptimeMillis() - startTime) + " ms)");
        }
        return false;
    }

    public synchronized void unregister() {
        if (!mRegistered) return;
        try {
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        } catch (final IllegalArgumentException e) {
            // The platform may have already dropped the observer after a permission change.
            Log.i(TAG, "Contact observer was already unregistered.", e);
        }
        mRegistered = false;
        mContentObserver = null;
        mContactsChangedListener = null;
    }
}
