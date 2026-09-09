// SPDX-License-Identifier: Apache-2.0
package com.android.phone.euicc;

import java.io.IOException;

/** Parser shared by the read and select responses in Xiaomi's MIPC protocol. */
final class EsimStatus {
    private EsimStatus() {}

    static int parse(String[] values) throws IOException {
        if (values == null || values.length != 1 || values[0] == null) {
            throw new IOException("Malformed modem response");
        }
        String first = values[0].split(",", -1)[0].trim();
        if (!first.matches("-?[0-9]{1,2}")) throw new IOException("Malformed modem status");
        return Integer.parseInt(first);
    }
}
