/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.ui.setup;

import android.accounts.Account;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog;

import java.util.logging.Level;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.ui.DebugInfoActivity;
import com.etesync.syncadapter.ui.setup.BaseConfigurationFinder.Configuration;

public class LoginCredentialsChangeFragment extends DialogFragment implements LoaderManager.LoaderCallbacks<Configuration> {
    protected static final String ARG_LOGIN_CREDENTIALS = "credentials",
            ARG_ACCOUNT = "account";
    private Account account;

    public static LoginCredentialsChangeFragment newInstance(Account account, LoginCredentials credentials) {
        LoginCredentialsChangeFragment frag = new LoginCredentialsChangeFragment();
        Bundle args = new Bundle(1);
        args.putParcelable(ARG_ACCOUNT, account);
        args.putParcelable(ARG_LOGIN_CREDENTIALS, credentials);
        frag.setArguments(args);
        return frag;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setTitle(R.string.login_configuration_detection);
        progress.setMessage(getString(R.string.login_querying_server));
        progress.setIndeterminate(true);
        progress.setCanceledOnTouchOutside(false);
        setCancelable(false);
        return progress;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getLoaderManager().initLoader(0, getArguments(), this);
        account = getArguments().getParcelable(ARG_ACCOUNT);
    }

    @Override
    public Loader<Configuration> onCreateLoader(int id, Bundle args) {
        return new ServerConfigurationLoader(getContext(), (LoginCredentials) args.getParcelable(ARG_LOGIN_CREDENTIALS));
    }

    @Override
    public void onLoadFinished(Loader<Configuration> loader, Configuration data) {
        if (data != null) {
            if (data.isFailed())
                // no service found: show error message
                getFragmentManager().beginTransaction()
                        .add(NothingDetectedFragment.newInstance(data.logs), null)
                        .commitAllowingStateLoss();
            else {
                final AccountSettings settings;

                try {
                    settings = new AccountSettings(getActivity(), account);
                } catch (InvalidAccountException e) {
                    App.log.log(Level.INFO, "Account is invalid or doesn't exist (anymore)", e);
                    getActivity().finish();
                    return;
                }
                settings.setAuthToken(data.authtoken);
            }
        } else
            App.log.severe("Configuration detection failed");

        dismissAllowingStateLoss();
    }

    @Override
    public void onLoaderReset(Loader<Configuration> loader) {
    }


    public static class NothingDetectedFragment extends DialogFragment {
        private static String KEY_LOGS = "logs";

        public static NothingDetectedFragment newInstance(String logs) {
            Bundle args = new Bundle();
            args.putString(KEY_LOGS, logs);
            NothingDetectedFragment fragment = new NothingDetectedFragment();
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        @NonNull
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.login_configuration_detection)
                    .setIcon(R.drawable.ic_error_dark)
                    .setMessage(R.string.login_wrong_username_or_password)
                    .setNeutralButton(R.string.login_view_logs, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(getActivity(), DebugInfoActivity.class);
                            intent.putExtra(DebugInfoActivity.KEY_LOGS, getArguments().getString(KEY_LOGS));
                            startActivity(intent);
                        }
                    })
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // dismiss
                        }
                    })
                    .create();
        }
    }

    static class ServerConfigurationLoader extends AsyncTaskLoader<Configuration> {
        final Context context;
        final LoginCredentials credentials;

        public ServerConfigurationLoader(Context context, LoginCredentials credentials) {
            super(context);
            this.context = context;
            this.credentials = credentials;
        }

        @Override
        protected void onStartLoading() {
            forceLoad();
        }

        @Override
        public Configuration loadInBackground() {
            return new BaseConfigurationFinder(context, credentials).findInitialConfiguration();
        }
    }
}
