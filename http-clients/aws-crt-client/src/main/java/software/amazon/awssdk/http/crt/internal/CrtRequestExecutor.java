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

import static software.amazon.awssdk.http.HttpMetric.AVAILABLE_CONCURRENCY;
import static software.amazon.awssdk.http.HttpMetric.CONCURRENCY_ACQUIRE_DURATION;
import static software.amazon.awssdk.http.HttpMetric.LEASED_CONCURRENCY;
import static software.amazon.awssdk.http.HttpMetric.MAX_CONCURRENCY;
import static software.amazon.awssdk.http.HttpMetric.PENDING_CONCURRENCY_ACQUIRES;
import static software.amazon.awssdk.http.crt.internal.CrtUtils.wrapWithIoExceptionIfRetryable;
import static software.amazon.awssdk.utils.NumericUtils.saturatedCast;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLHandshakeException;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpClientConnectionManager;
import software.amazon.awssdk.crt.http.HttpException;
import software.amazon.awssdk.crt.http.HttpManagerMetrics;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpStreamResponseHandler;
import software.amazon.awssdk.http.SdkCancellationException;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.crt.internal.request.CrtRequestAdapter;
import software.amazon.awssdk.http.crt.internal.response.CrtResponseAdapter;
import software.amazon.awssdk.http.crt.internal.response.InputStreamAdaptingHttpStreamResponseHandler;
import software.amazon.awssdk.metrics.MetricCollector;
import software.amazon.awssdk.metrics.NoOpMetricCollector;
import software.amazon.awssdk.utils.Logger;

@SdkInternalApi
public final class CrtRequestExecutor {
    public static final int CRT_TLS_NEGOTIATION_ERROR_CODE = 1029;

    private static final Logger log = Logger.loggerFor(CrtRequestExecutor.class);

    public CompletableFuture<SdkHttpFullResponse> execute(CrtRequestContext executionContext) {
        // go ahead and get a reference to the metricCollector since multiple futures will
        // need it regardless.
        MetricCollector metricCollector = executionContext.metricCollector();
        boolean shouldPublishMetrics = metricCollector != null && !(metricCollector instanceof NoOpMetricCollector);

        long acquireStartTime = 0;

        if (shouldPublishMetrics) {
            // go ahead and get acquireStartTime for the concurrency timer as early as possible,
            // so it's as accurate as possible, but only do it in a branch since clock_gettime()
            // results in a full sys call barrier (multiple mutexes and a hw interrupt).
            acquireStartTime = System.nanoTime();
        }

        CompletableFuture<SdkHttpFullResponse> requestFuture = new CompletableFuture<>();

        // When a Connection is ready from the Connection Pool, schedule the Request on the connection
        CompletableFuture<HttpClientConnection> httpClientConnectionCompletableFuture =
            executionContext.crtConnPool().acquireConnection();

        long finalAcquireStartTime = acquireStartTime;

        httpClientConnectionCompletableFuture.whenComplete((crtConn, throwable) -> {
            if (shouldPublishMetrics) {
                reportMetrics(executionContext.crtConnPool(), metricCollector, finalAcquireStartTime);
            }

            // If we didn't get a connection for some reason, fail the request
            if (throwable != null) {
                Throwable toThrow;
                if (throwable instanceof HttpException) {
                    HttpException httpException = (HttpException) throwable;

                    if (httpException.getErrorCode() == CRT_TLS_NEGOTIATION_ERROR_CODE) {
                        toThrow = new SSLHandshakeException(httpException.getMessage());
                    } else {
                        toThrow = new IOException(httpException.getMessage(), httpException);
                    }
                } else {
                    toThrow = new IOException("An exception occurred when acquiring a connection", throwable);
                }
                requestFuture.completeExceptionally(toThrow);
                return;
            }

            executeRequest(executionContext, requestFuture, crtConn);
        });

        return requestFuture;
    }

    public CompletableFuture<Void> execute(CrtAsyncRequestContext executionContext) {
        // go ahead and get a reference to the metricCollector since multiple futures will
        // need it regardless.
        MetricCollector metricCollector = executionContext.metricCollector();
        boolean shouldPublishMetrics = metricCollector != null && !(metricCollector instanceof NoOpMetricCollector);

        long acquireStartTime = 0;

        if (shouldPublishMetrics) {
            // go ahead and get acquireStartTime for the concurrency timer as early as possible,
            // so it's as accurate as possible, but only do it in a branch since clock_gettime()
            // results in a full sys call barrier (multiple mutexes and a hw interrupt).
            acquireStartTime = System.nanoTime();
        }

        CompletableFuture<Void> requestFuture = createAsyncExecutionFuture(executionContext.sdkRequest());

        // When a Connection is ready from the Connection Pool, schedule the Request on the connection
        CompletableFuture<HttpClientConnection> httpClientConnectionCompletableFuture =
            executionContext.crtConnPool().acquireConnection();

        long finalAcquireStartTime = acquireStartTime;

        httpClientConnectionCompletableFuture.whenComplete((crtConn, throwable) -> {
            AsyncExecuteRequest asyncRequest = executionContext.sdkRequest();

            if (shouldPublishMetrics) {
                reportMetrics(executionContext.crtConnPool(), metricCollector, finalAcquireStartTime);
            }

            // If we didn't get a connection for some reason, fail the request
            if (throwable != null) {
                Throwable toThrow = new IOException("An exception occurred when acquiring a connection", throwable);
                if (throwable instanceof HttpException) {
                    HttpException httpException = (HttpException) throwable;

                    if (httpException.getErrorCode() == CRT_TLS_NEGOTIATION_ERROR_CODE) {
                        toThrow = new SSLHandshakeException(httpException.getMessage());
                    }
                }

                reportAsyncFailure(crtConn, toThrow, requestFuture, asyncRequest.responseHandler());
                return;
            }

            executeRequest(executionContext, requestFuture, crtConn, asyncRequest);
        });

        return requestFuture;
    }

    private static void reportMetrics(HttpClientConnectionManager connManager, MetricCollector metricCollector,
                                      long acquireStartTime) {
        long acquireCompletionTime = System.nanoTime();
        Duration acquireTimeTaken = Duration.ofNanos(acquireCompletionTime - acquireStartTime);
        metricCollector.reportMetric(CONCURRENCY_ACQUIRE_DURATION, acquireTimeTaken);
        HttpManagerMetrics managerMetrics = connManager.getManagerMetrics();
        // currently this executor only handles HTTP 1.1. Until H2 is added, the max concurrency settings are 1:1 with TCP
        // connections. When H2 is added, this code needs to be updated to handle stream multiplexing
        metricCollector.reportMetric(MAX_CONCURRENCY, connManager.getMaxConnections());
        metricCollector.reportMetric(AVAILABLE_CONCURRENCY, saturatedCast(managerMetrics.getAvailableConcurrency()));
        metricCollector.reportMetric(LEASED_CONCURRENCY, saturatedCast(managerMetrics.getLeasedConcurrency()));
        metricCollector.reportMetric(PENDING_CONCURRENCY_ACQUIRES, saturatedCast(managerMetrics.getPendingConcurrencyAcquires()));
    }

    private void executeRequest(CrtAsyncRequestContext executionContext,
                                CompletableFuture<Void> requestFuture,
                                HttpClientConnection crtConn,
                                AsyncExecuteRequest asyncRequest) {
        HttpRequest crtRequest = CrtRequestAdapter.toAsyncCrtRequest(executionContext);
        HttpStreamResponseHandler crtResponseHandler =
            CrtResponseAdapter.toCrtResponseHandler(crtConn, requestFuture, asyncRequest.responseHandler());

        // Submit the request on the connection
        try {
            crtConn.makeRequest(crtRequest, crtResponseHandler).activate();
        } catch (HttpException e) {
            Throwable toThrow = wrapWithIoExceptionIfRetryable(e);
            reportAsyncFailure(crtConn,
                          toThrow,
                          requestFuture,
                          asyncRequest.responseHandler());
        } catch (IllegalStateException | CrtRuntimeException e) {
            // CRT throws IllegalStateException if the connection is closed
            reportAsyncFailure(crtConn, new IOException("An exception occurred when making the request", e),
                          requestFuture,
                          asyncRequest.responseHandler());
        }
    }

    private void executeRequest(CrtRequestContext executionContext,
                                CompletableFuture<SdkHttpFullResponse> requestFuture,
                                HttpClientConnection crtConn) {
        HttpRequest crtRequest = CrtRequestAdapter.toCrtRequest(executionContext);

        try {
            HttpStreamResponseHandler crtResponseHandler = new InputStreamAdaptingHttpStreamResponseHandler(crtConn,
                                                                                                            requestFuture);

            // Submit the request on the connection
            crtConn.makeRequest(crtRequest, crtResponseHandler).activate();
        } catch (HttpException e) {
            crtConn.close();

            Throwable toThrow = wrapWithIoExceptionIfRetryable(e);
            requestFuture.completeExceptionally(toThrow);
        } catch (IllegalStateException | CrtRuntimeException e) {
            crtConn.close();
            requestFuture.completeExceptionally(e);
        }
    }

    /**
     * Create the execution future and set up the cancellation logic.
     * @return The created execution future.
     */
    private CompletableFuture<Void> createAsyncExecutionFuture(AsyncExecuteRequest request) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        future.whenComplete((r, t) -> {
            if (t == null) {
                return;
            }

            // TODO: Aborting request once it's supported in CRT
            if (future.isCancelled()) {
                request.responseHandler().onError(new SdkCancellationException("The request was cancelled"));
            }
        });

        return future;
    }

    /**
     * Notify the provided response handler and future of the failure.
     */
    private void reportAsyncFailure(HttpClientConnection crtConn,
                               Throwable cause,
                               CompletableFuture<Void> executeFuture,
                               SdkAsyncHttpResponseHandler responseHandler) {
        if (crtConn != null) {
            crtConn.close();
        }

        try {
            responseHandler.onError(cause);
        } catch (Exception e) {
            log.error(() -> "SdkAsyncHttpResponseHandler " + responseHandler + " threw an exception in onError. It will be "
                            + "ignored.", e);
        }
        executeFuture.completeExceptionally(cause);
    }
}
