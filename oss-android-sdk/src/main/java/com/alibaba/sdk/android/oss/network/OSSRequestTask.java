package com.alibaba.sdk.android.oss.network;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.common.OSSHeaders;
import com.alibaba.sdk.android.oss.common.OSSLog;
import com.alibaba.sdk.android.oss.common.utils.DateUtil;
import com.alibaba.sdk.android.oss.common.utils.OSSUtils;
import com.alibaba.sdk.android.oss.internal.OSSRetryHandler;
import com.alibaba.sdk.android.oss.internal.OSSRetryType;
import com.alibaba.sdk.android.oss.internal.RequestMessage;
import com.alibaba.sdk.android.oss.internal.ResponseParser;
import com.alibaba.sdk.android.oss.internal.ResponseParsers;
import com.alibaba.sdk.android.oss.model.GetObjectRequest;
import com.alibaba.sdk.android.oss.model.OSSRequest;
import com.alibaba.sdk.android.oss.model.OSSResult;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Created by zhouzhuo on 11/22/15.
 */
public class OSSRequestTask<T extends OSSResult> implements Callable<T> {

    private ResponseParser<T> responseParser;

    private RequestMessage message;

    private ExecutionContext context;

    private OkHttpClient client;

    private OSSRetryHandler retryHandler;

    private int currentRetryCount = 0;

    public OSSRequestTask(RequestMessage message, ResponseParser parser, ExecutionContext context, int maxRetry) {
        this.responseParser = parser;
        this.message = message;
        this.context = context;
        this.client = context.getClient();
        this.retryHandler = new OSSRetryHandler(maxRetry);
    }

    @Override
    public T call() throws Exception {

        Request request = null;
        Response response = null;
        Exception exception = null;
        Call call = null;

        try {
            if(context.getApplicationContext() != null) {
                OSSLog.logInfo(OSSUtils.buildBaseLogInfo(context.getApplicationContext()));
            }

            OSSLog.logDebug("[call] - ");

            OSSRequest ossRequest = context.getRequest();

            // validate request
            OSSUtils.ensureRequestValid(ossRequest, message);
            // signing
            OSSUtils.signRequest(message);

            if (context.getCancellationHandler().isCancelled()) {
                throw new InterruptedIOException("This task is cancelled!");
            }

            Request.Builder requestBuilder = new Request.Builder();

            // build request url
            String url = message.buildCanonicalURL();
            requestBuilder = requestBuilder.url(url);

            // set request headers
            for (String key : message.getHeaders().keySet()) {
                requestBuilder = requestBuilder.addHeader(key, message.getHeaders().get(key));
            }

            String contentType = message.getHeaders().get(OSSHeaders.CONTENT_TYPE);

            // set request body
            switch (message.getMethod()) {
                case POST:
                case PUT:
                    OSSUtils.assertTrue(contentType != null, "Content type can't be null when upload!");
                    InputStream inputStream = null;
                    long length = 0;
                    if (message.getUploadData() != null) {
                        inputStream = new ByteArrayInputStream(message.getUploadData());
                        length = message.getUploadData().length;
                    } else if (message.getUploadFilePath() != null) {
                        File file = new File(message.getUploadFilePath());
                        inputStream = new FileInputStream(file);
                        length = file.length();
                    } else if (message.getUploadInputStream() != null) {
                        inputStream = message.getUploadInputStream();
                        length = message.getReadStreamLength();
                    }

                    if(inputStream != null) {
                        requestBuilder = requestBuilder.method(message.getMethod().toString(),
                                NetworkProgressHelper.addProgressRequestBody(inputStream,length,contentType,context));
                    }else {
                        requestBuilder = requestBuilder.method(message.getMethod().toString(), RequestBody.create(null, new byte[0]));
                    }
                    break;
                case GET:
                    requestBuilder = requestBuilder.get();
                    break;
                case HEAD:
                    requestBuilder = requestBuilder.head();
                    break;
                case DELETE:
                    requestBuilder = requestBuilder.delete();
                    break;
                default:
                    break;
            }

            request = requestBuilder.build();

            if(ossRequest instanceof GetObjectRequest){
                client = NetworkProgressHelper.addProgressResponseListener(client,context);
                OSSLog.logDebug("getObject");
            }

            call = client.newCall(request);

            context.getCancellationHandler().setCall(call);

            // send sync request
            response = call.execute();

            // response log
            Map<String, List<String>> headerMap = response.headers().toMultimap();
            StringBuilder printRsp = new StringBuilder();
            printRsp.append("response:---------------------\n");
            printRsp.append("response code: " + response.code() + " for url: " + request.url()+"\n");
            printRsp.append("response msg: "+ response.message()+"\n");
            for(String key : headerMap.keySet()){
                printRsp.append("responseHeader ["+key+"]: ").append(headerMap.get(key).get(0)+"\n");
            }
            OSSLog.logDebug(printRsp.toString());

        } catch (Exception e) {
            OSSLog.logError("Encounter local execpiton: " + e.toString());
            if (OSSLog.isEnableLog()) {
                e.printStackTrace();
            }
            exception = new ClientException(e.getMessage(), e);
        }

        if (response != null) {
            String responseDateString = response.header(OSSHeaders.DATE);
            try {
                // update the server time after every response
                long serverTime = DateUtil.parseRfc822Date(responseDateString).getTime();
                DateUtil.setCurrentServerTime(serverTime);
            } catch (Exception ignore) {
                // Fail to parse the time, ignore it
            }
        }

        if (exception == null && (response.code() == 203 || response.code() >= 300)) {
            try {
                exception = ResponseParsers.parseResponseErrorXML(response, request.method().equals("HEAD"));
            } catch (IOException e) {
                exception = new ClientException(e.getMessage(), e);
            }
        } else if (exception == null) {
            try {
                T result = responseParser.parse(response);
                if (context.getCompletedCallback() != null) {
                    try {
                        context.getCompletedCallback().onSuccess(context.getRequest(), result);
                    } catch (Exception ignore) {
                        // The callback throws the exception, ignore it
                    }
                }
                return result;
            } catch (IOException e) {
                exception = new ClientException(e.getMessage(), e);
            }
        }

        // reconstruct exception caused by manually cancelling
        if ((call != null && call.isCanceled())
                || context.getCancellationHandler().isCancelled()) {
            exception = new ClientException("Task is cancelled!", exception.getCause(), true);
        }

        OSSRetryType retryType = retryHandler.shouldRetry(exception, currentRetryCount);
        OSSLog.logError("[run] - retry, retry type: " + retryType);
        if (retryType == OSSRetryType.OSSRetryTypeShouldRetry) {
            this.currentRetryCount++;
            if(context.getRetryCallback() != null){
                context.getRetryCallback().onRetryCallback();
            }
            return call();
        } else if (retryType == OSSRetryType.OSSRetryTypeShouldFixedTimeSkewedAndRetry) {
            // Updates the DATE header value and try again
            if (response != null) {
                message.getHeaders().put(OSSHeaders.DATE, response.header(OSSHeaders.DATE));
            }
            this.currentRetryCount++;
            if(context.getRetryCallback() != null){
                context.getRetryCallback().onRetryCallback();
            }
            return call();
        } else {
            if (exception instanceof ClientException) {
                if (context.getCompletedCallback() != null) {
                    context.getCompletedCallback().onFailure(context.getRequest(), (ClientException) exception, null);
                }
            } else {
                if (context.getCompletedCallback() != null) {
                    context.getCompletedCallback().onFailure(context.getRequest(), null, (ServiceException) exception);
                }
            }
            throw exception;
        }
    }
}
