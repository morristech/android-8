/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package com.etesync.syncadapter.syncadapter;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.logging.Level;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.Constants;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.NotificationHelper;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.Exceptions;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.ServiceDB;
import com.etesync.syncadapter.model.ServiceDB.Collections;
import com.etesync.syncadapter.ui.DebugInfoActivity;
import lombok.Cleanup;
import okhttp3.HttpUrl;

import static com.etesync.syncadapter.Constants.KEY_ACCOUNT;

public class ContactsSyncAdapterService extends SyncAdapterService {

    @Override
    protected AbstractThreadedSyncAdapter syncAdapter() {
        return new ContactsSyncAdapter(this);
    }


    private static class ContactsSyncAdapter extends SyncAdapter {

        public ContactsSyncAdapter(Context context) {
            super(context);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
            super.onPerformSync(account, extras, authority, provider, syncResult);
            NotificationHelper notificationManager = new NotificationHelper(getContext(), "journals-contacts", Constants.NOTIFICATION_CONTACTS_SYNC);
            notificationManager.cancel();

            ServiceDB.OpenHelper dbHelper = new ServiceDB.OpenHelper(getContext());
            try {
                AccountSettings settings = new AccountSettings(getContext(), account);
                if (!extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL) && !checkSyncConditions(settings))
                    return;

                new RefreshCollections(account, CollectionInfo.Type.ADDRESS_BOOK).run();

                SQLiteDatabase db = dbHelper.getReadableDatabase();
                Long service = dbHelper.getService(db, account, ServiceDB.Services.SERVICE_CARDDAV);
                if (service != null) {
                    HttpUrl principal = HttpUrl.get(settings.getUri());
                    CollectionInfo info = remoteAddressBook(db, service);
                    try {
                        ContactsSyncManager syncManager = new ContactsSyncManager(getContext(), account, settings, extras, authority, provider, syncResult, principal, info);
                        syncManager.performSync();
                    } catch (InvalidAccountException e) {
                        App.log.log(Level.SEVERE, "Couldn't get account settings", e);
                    }
                } else
                    App.log.info("No CardDAV service found in DB");
            } catch (Exceptions.ServiceUnavailableException e) {
                syncResult.stats.numIoExceptions++;
                syncResult.delayUntil = (e.retryAfter > 0) ? e.retryAfter : Constants.DEFAULT_RETRY_DELAY;
            } catch (Exception | OutOfMemoryError e) {
                int syncPhase = R.string.sync_phase_journals;
                String title = getContext().getString(R.string.sync_error_contacts, account.name);

                notificationManager.setThrowable(e);

                final Intent detailsIntent = notificationManager.getDetailsIntent();
                detailsIntent.putExtra(KEY_ACCOUNT, account);
                if (!(e instanceof Exceptions.UnauthorizedException)) {
                    detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority);
                    detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase);
                }
                notificationManager.notify(title, getContext().getString(syncPhase));
            } finally {
                dbHelper.close();
            }

            App.log.info("Address book sync complete");
        }

        @Nullable
        private CollectionInfo remoteAddressBook(@NonNull SQLiteDatabase db, long service) {
            @Cleanup Cursor c = db.query(Collections._TABLE, null,
                    Collections.SERVICE_ID + "=? AND " + Collections.SYNC, new String[]{String.valueOf(service)}, null, null, null);
            if (c.moveToNext()) {
                ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(c, values);
                return CollectionInfo.fromDB(values);
            } else
                return null;
        }
    }

}