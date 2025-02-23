/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.crt.internal;

import java.io.IOException;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpException;

@SdkInternalApi
public final class CrtUtils {

    private CrtUtils() {
    }

    public static Throwable wrapWithIoExceptionIfRetryable(HttpException httpException) {
        Throwable toThrow = httpException;
        if (HttpClientConnection.isErrorRetryable(httpException)) {
            // IOExceptions get retried, and if the CRT says this error is retryable,
            // it's semantically an IOException anyway.
            toThrow = new IOException(httpException);
        }
        return toThrow;
    }
}
