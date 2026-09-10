// SPDX-License-Identifier: Apache-2.0
package com.android.phone.euicc;

import android.hardware.radio.RadioResponseInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Narrow client for the modem ABI shipped on malachite; see bringup/ESIM.md. */
final class MtkEsimTransport {
    private static final String DESCRIPTOR =
            "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem";
    private static final String HASH = "87512b9a1978fdb596a8d9176854d3761382dc82";
    private static final int VERSION = 2;
    private static final int GET_VERSION = 16777215;
    private static final int GET_HASH = 16777214;
    private static final int SEND_STRINGS = 11;
    private static final int ACKNOWLEDGE = 25;
    private static final int SET_MTK_CALLBACKS = 26;
    private static final int STRINGS_RESPONSE = 7;
    private static final int MTK_CLIENT = 0;
    private static final AtomicInteger NEXT_SERIAL = new AtomicInteger(0x45000000);
    private static final MtkEsimTransport INSTANCE = new MtkEsimTransport();

    private volatile IBinder modem;
    private volatile Request pending;
    private final Callback response = new Callback(DESCRIPTOR + "Response", true);
    private final Callback indication = new Callback(DESCRIPTOR + "Indication", false);

    static MtkEsimTransport getInstance() { return INSTANCE; }

    // Serialize requests without locking the Binder callback thread.
    synchronized int readState() throws IOException {
        return exchange(new String[] {"MIPC_GET_ESIM_STATE"}, 2000);
    }

    synchronized void select(boolean esim, BooleanSupplier maySwitch) throws IOException {
        int state = readState();
        if (state != 0 && state != 1) throw new IOException("State unavailable");
        if (state == (esim ? 1 : 0)) return;
        // The preceding read can take time; check calls/restrictions again immediately
        // before changing the modem connection.
        if (!maySwitch.getAsBoolean()) throw new IOException("Switch blocked");
        int status = exchange(new String[] {"MIPC_SET_ESIM_STATE", esim ? "1" : "0"}, 7000);
        // MEP retry/mapping statuses 4 and 5 are deliberately not treated as success.
        if (status != 0 && status != 1) throw new IOException("Switch rejected");
        if (readState() != (esim ? 1 : 0)) throw new IOException("Switch not confirmed");
    }

    private void connect() throws IOException {
        if (modem != null && modem.isBinderAlive()) return;
        IBinder candidate = ServiceManager.checkService(DESCRIPTOR + "/slot2");
        if (candidate == null) throw new IOException("Modem service unavailable");
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!candidate.transact(GET_VERSION, data, reply, 0)) {
                    throw new IOException("Modem version unavailable");
                }
                reply.readException();
                if (reply.readInt() != VERSION) throw new IOException("Unsupported modem ABI");
            } finally {
                data.recycle();
                reply.recycle();
            }
            data = Parcel.obtain();
            reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!candidate.transact(GET_HASH, data, reply, 0)) {
                    throw new IOException("Modem hash unavailable");
                }
                reply.readException();
                if (!HASH.equals(reply.readString())) throw new IOException("Unsupported modem ABI");
            } finally {
                data.recycle();
                reply.recycle();
            }
            data = Parcel.obtain();
            try {
                // Registration can immediately deliver indications on a Binder thread.
                // Publish the endpoint first so acknowledgement requests can be answered.
                modem = candidate;
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(response);
                data.writeStrongBinder(indication);
                if (!candidate.transact(SET_MTK_CALLBACKS, data, null, IBinder.FLAG_ONEWAY)) {
                    throw new IOException("Callback registration unavailable");
                }
            } finally {
                data.recycle();
            }
            candidate.linkToDeath(() -> {
                if (modem != candidate) return;
                Request request = pending;
                if (request != null) request.done.countDown();
            }, 0);
        } catch (IOException | RemoteException | RuntimeException failure) {
            modem = null;
            if (failure instanceof IOException transportFailure) throw transportFailure;
            throw new IOException("Modem connection failed", failure);
        }
    }

    private int exchange(String[] command, long timeoutMillis) throws IOException {
        connect();
        Request request = new Request(NEXT_SERIAL.getAndIncrement());
        pending = request;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(request.serial);
            data.writeStringArray(command);
            data.writeInt(MTK_CLIENT);
            if (!modem.transact(SEND_STRINGS, data, null, IBinder.FLAG_ONEWAY)) {
                throw new IOException("Command unavailable");
            }
            if (!request.done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IOException("Modem response timeout");
            }
            if (request.error != 0 || request.result == null) {
                throw new IOException("Modem response unavailable");
            }
            return EsimStatus.parse(request.result);
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("Modem request failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Modem request interrupted", failure);
        } finally {
            data.recycle();
            pending = null;
        }
    }

    private void acknowledge() {
        IBinder current = modem;
        if (current == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            current.transact(ACKNOWLEDGE, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // The waiting request will time out or observe Binder death.
        } finally {
            data.recycle();
        }
    }

    private final class Callback extends Binder {
        private final String descriptor;
        private final boolean isResponse;

        Callback(String descriptor, boolean isResponse) {
            this.descriptor = descriptor;
            this.isResponse = isResponse;
            markVintfStability();
            attachInterface(null, descriptor);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(descriptor);
                return true;
            }
            if (code >= 1 && code <= GET_VERSION) data.enforceInterface(descriptor);
            if (code == GET_VERSION || code == GET_HASH) {
                reply.writeNoException();
                if (code == GET_VERSION) reply.writeInt(VERSION); else reply.writeString(HASH);
                return true;
            }
            if (isResponse && code == STRINGS_RESPONSE) {
                RadioResponseInfo info = data.readTypedObject(RadioResponseInfo.CREATOR);
                String[] result = data.createStringArray();
                data.enforceNoDataAvail();
                if (info != null && info.type == 2) acknowledge();
                Request request = pending;
                if (info != null && info.type != 1 && request != null
                        && info.serial == request.serial) {
                    request.error = info.error;
                    request.result = result;
                    request.done.countDown();
                }
                return true;
            }
            if (!isResponse && code >= 1 && code <= 8) {
                // We do not consume unsolicited modem data or log its payload.
                if (data.readInt() == 1) acknowledge();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static final class Request {
        final int serial;
        final CountDownLatch done = new CountDownLatch(1);
        int error = -1;
        String[] result;
        Request(int serial) { this.serial = serial; }
    }
}
