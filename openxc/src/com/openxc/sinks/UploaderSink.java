package com.openxc.sinks;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import com.openxc.remote.RawMeasurement;

public class UploaderSink extends ContextualVehicleDataSink {
    private final static String TAG = "UploaderSink";
    private final static int UPLOAD_BATCH_SIZE = 25;
    private final static int MAXIMUM_QUEUED_RECORDS = 5000;
    private final static int HTTP_TIMEOUT = 5000;

    private URI mUri;
    private BlockingQueue<String> mRecordQueue;
    private Lock mQueueLock;
    private Condition mRecordsQueuedSignal;
    private UploaderThread mUploader;

    public UploaderSink(Context context, URI uri) {
        super(context);
        mUri = uri;
        mRecordQueue = new LinkedBlockingQueue<String>(
                MAXIMUM_QUEUED_RECORDS);
        mQueueLock = new ReentrantLock();
        mRecordsQueuedSignal = mQueueLock.newCondition();
        mUploader = new UploaderThread();
        mUploader.start();
    }

    public UploaderSink(Context context, String path) throws DataSinkException {
        this(context, uriFromString(path));
    }

    public void stop() {
        super.stop();
        mUploader.done();
    }

    public void receive(RawMeasurement measurement) {
        String data = measurement.serialize(true);
        mRecordQueue.offer(data);
        if(mRecordQueue.size() >= UPLOAD_BATCH_SIZE) {
            mQueueLock.lock();
            mRecordsQueuedSignal.signal();
            mQueueLock.unlock();
        }
    }

    private static URI uriFromString(String path) throws DataSinkException {
        try {
            return new URI(path);
        } catch(java.net.URISyntaxException e) {
            throw new UploaderException(
                "Uploading path in wrong format -- expected: ip:port");
        }
    }

    public static boolean validatePath(String path) {
        if(path == null) {
            Log.w(TAG, "Uploading path not set (it's " + path + ")");
            return false;
        }

        try {
            uriFromString(path);
            return true;
        } catch(DataSinkException e) {
            return false;
        }
    }

    private static class UploaderException extends DataSinkException {
        public UploaderException() { }

        public UploaderException(String message) {
            super(message);
        }
    }

    private class UploaderThread extends Thread {
        private boolean mRunning = true;

        public void done() {
            mRunning = false;
        }

        private String constructRequestData(ArrayList<String> records)
                throws UploaderException {
            StringWriter buffer = new StringWriter(512);
            JsonFactory jsonFactory = new JsonFactory();
            try {
                JsonGenerator gen = jsonFactory.createJsonGenerator(buffer);

                gen.writeStartObject();
                gen.writeArrayFieldStart("records");
                Iterator<String> recordIterator = records.iterator();
                while(recordIterator.hasNext()) {
                    gen.writeRaw(recordIterator.next());
                    if(recordIterator.hasNext()) {
                        gen.writeRaw(",");
                    }
                }
                gen.writeEndArray();
                gen.writeEndObject();

                gen.close();
            } catch(IOException e) {
                Log.w(TAG, "Unable to encode all data to JSON -- " +
                        "message may be incomplete", e);
                throw new UploaderException();
            }
            return buffer.toString();
        }

        private HttpPost constructRequest(String data)
                throws UploaderException {
            HttpPost request = new HttpPost(mUri);
            try {
                ByteArrayEntity entity = new ByteArrayEntity(data.getBytes("UTF8"));
                entity.setContentEncoding(
                        new BasicHeader("Content-Type", "application/json"));
                request.setEntity(entity);
            } catch(UnsupportedEncodingException e) {
                Log.w(TAG, "Couldn't encode records for uploading", e);
                throw new UploaderException();
            }
            return request;
        }

        private void makeRequest(HttpPost request) throws InterruptedException {
            HttpParams parameters = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(parameters, HTTP_TIMEOUT);
            HttpConnectionParams.setSoTimeout(parameters, HTTP_TIMEOUT);
            final HttpClient client = new DefaultHttpClient(parameters);
            try {
                HttpResponse response = client.execute(request);
                final int statusCode = response.getStatusLine().getStatusCode();
                if(statusCode != HttpStatus.SC_CREATED) {
                    Log.w(TAG, "Got unxpected status code: " + statusCode);
                }
            } catch(IOException e) {
                Log.w(TAG, "Problem uploading the record", e);
                try {
                    Thread.sleep(5000);
                } catch(InterruptedException e2) {
                    Log.w(TAG, "Uploader interrupted after an error", e2);
                    throw e2;
                }
            }
        }

        private ArrayList<String> getRecords() throws InterruptedException {
            mQueueLock.lock();
            if(mRecordQueue.isEmpty()) {
                // the queue is already thread safe, but we use this lock to get
                // a condition variable we can use to signal when a batch has
                // been queued.
                mRecordsQueuedSignal.await();
            }

            ArrayList<String> records = new ArrayList<String>();
            mRecordQueue.drainTo(records, UPLOAD_BATCH_SIZE);

            mQueueLock.unlock();
            return records;
        }

        public void run() {
            while(mRunning) {
                try {
                    ArrayList<String> records = getRecords();
                    String data = constructRequestData(records);
                    HttpPost request = constructRequest(data);
                    makeRequest(request);
                } catch(UploaderException e) {
                    Log.w(TAG, "Problem uploading the record", e);
                } catch(InterruptedException e) {
                    Log.w(TAG, "Uploader was interrupted", e);
                    break;
                }
            }
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("uri", mUri)
            .add("queuedRecords", mRecordQueue.size())
            .toString();
    }
}
