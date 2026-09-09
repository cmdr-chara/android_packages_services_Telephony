// SPDX-License-Identifier: Apache-2.0
package com.android.phone.euicc;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.util.Log;

import com.android.phone.R;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Phone-UID bridge for the shared physical-SIM/eUICC modem selector. */
public final class SharedSimSlotService extends Service {
    public static final int MSG_READ = 1;
    public static final int MSG_SELECT = 2;
    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_BLOCKED = 2;

    private static final String TAG = "SharedSimSlotService";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final int PHYSICAL = 0;
    private static final int EMBEDDED = 1;

    private final ThreadPoolExecutor mWorker =
            new ThreadPoolExecutor(
                    0,
                    1,
                    10,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4),
                    runnable -> new Thread(runnable, "SharedSimSlot"));
    private final Handler mHandler =
            new Handler(
                    Looper.getMainLooper(),
                    message -> {
                        handleRequest(message);
                        return true;
                    });
    private final Messenger mMessenger = new Messenger(mHandler);

    @Override
    public IBinder onBind(Intent intent) {
        return isSupported(this) ? mMessenger.getBinder() : null;
    }

    @Override
    public void onDestroy() {
        mWorker.shutdownNow();
        super.onDestroy();
    }

    private static boolean isSupported(Context context) {
        return context.getResources().getBoolean(R.bool.config_mtk_euicc_slot_switch)
                && context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
    }

    private void handleRequest(Message request) {
        if (request.replyTo == null
                || !isTrustedCaller(request.sendingUid)
                || (request.what != MSG_READ && request.what != MSG_SELECT)
                || (request.arg1 != PHYSICAL && request.arg1 != EMBEDDED)) {
            Log.w(TAG, "Rejected request from uid=" + request.sendingUid);
            return;
        }
        Request task = new Request(request.what, request.arg1, request.replyTo);
        try {
            mWorker.execute(() -> runRequest(task));
        } catch (RejectedExecutionException busy) {
            reply(task, RESULT_ERROR, -1);
        }
    }

    private void runRequest(Request request) {
        int result = RESULT_OK;
        int state = -1;
        try {
            MtkEsimTransport transport = MtkEsimTransport.getInstance();
            if (request.what == MSG_SELECT) {
                if (!mayChange() || isInCall()) {
                    result = RESULT_BLOCKED;
                } else {
                    boolean embedded = request.state == EMBEDDED;
                    transport.select(embedded, () -> mayChange() && !isInCall());
                }
            }
            if (result == RESULT_OK) {
                state = transport.readState();
                if (state != PHYSICAL && state != EMBEDDED) result = RESULT_ERROR;
                else if (request.state == EMBEDDED && state == EMBEDDED && !waitForEuicc()) {
                    result = RESULT_ERROR;
                    if (request.what == MSG_SELECT) {
                        try {
                            transport.select(false, () -> mayChange() && !isInCall());
                            state = transport.readState();
                            Log.i(TAG, "Restored physical SIM after eUICC readiness failure");
                        } catch (IOException rollbackFailure) {
                            Log.w(TAG, "Unable to restore physical SIM");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Shared SIM request failed: " + failure.getMessage());
            result = RESULT_ERROR;
        }
        reply(request, result, state);
    }

    private boolean mayChange() {
        UserManager users = getSystemService(UserManager.class);
        return users != null
                && users.isAdminUser()
                && !users.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
    }

    private boolean isInCall() {
        TelecomManager telecom = getSystemService(TelecomManager.class);
        return telecom == null || telecom.isInCall();
    }

    private boolean waitForEuicc() {
        TelephonyManager telephony = getSystemService(TelephonyManager.class);
        if (telephony == null) return false;
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        do {
            for (UiccCardInfo card : telephony.getUiccCardsInfo()) {
                if (card.getPhysicalSlotIndex() == 1
                        && card.isEuicc()
                        && card.getEid() != null
                        && !card.getEid().isEmpty()
                        && card.getPorts().stream().anyMatch(port -> port.isActive())) {
                    return true;
                }
            }
            SystemClock.sleep(250);
        } while (SystemClock.elapsedRealtime() < deadline);
        Log.w(TAG, "eUICC did not become ready");
        return false;
    }

    private boolean isTrustedCaller(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null
                && Arrays.asList(packages).contains(SETTINGS_PACKAGE)
                && getPackageManager().checkSignatures(getPackageName(), SETTINGS_PACKAGE)
                        == PackageManager.SIGNATURE_MATCH;
    }

    private static void reply(Request request, int result, int state) {
        try {
            Message response = Message.obtain(null, request.what, state, result);
            request.replyTo.send(response);
        } catch (RemoteException ignored) {
            // The requesting Settings activity went away.
        }
    }

    private static final class Request {
        final int what;
        final int state;
        final Messenger replyTo;

        Request(int what, int state, Messenger replyTo) {
            this.what = what;
            this.state = state;
            this.replyTo = replyTo;
        }
    }
}
