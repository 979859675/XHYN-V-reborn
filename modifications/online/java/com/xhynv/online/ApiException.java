// SPDX-License-Identifier: MIT
package com.xhynv.online;

/** A failed request. {@code status} is the HTTP status, or 0 when the server could not be reached. */
final class ApiException extends Exception {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
