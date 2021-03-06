package net.hockeyapp.android.tasks;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import net.hockeyapp.android.Constants;
import net.hockeyapp.android.R;
import net.hockeyapp.android.utils.HockeyLog;
import net.hockeyapp.android.utils.HttpURLConnectionBuilder;
import net.hockeyapp.android.utils.Util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h3>Description</h3>
 *
 * Internal helper class. Sends feedback to server.
 *
 **/
@SuppressLint("StaticFieldLeak")
public class SendFeedbackTask extends ConnectionTask<Void, Void, HashMap<String, String>> {

    public static final String BUNDLE_FEEDBACK_RESPONSE = "feedback_response";
    public static final String BUNDLE_FEEDBACK_STATUS = "feedback_status";
    public static final String BUNDLE_REQUEST_TYPE = "request_type";

    private static final String TAG = "SendFeedbackTask";
    private static final String FILE_TAG = "HockeyApp";

    private Context mContext;
    private Handler mHandler;
    private String mUrlString;
    private String mName;
    private String mEmail;
    private String mSubject;
    private String mText;
    private String mUserId;
    private List<Uri> mAttachmentUris;
    private String mToken;
    private boolean mIsFetchMessages;
    private ProgressDialog mProgressDialog;
    private boolean mShowProgressDialog;
    private int mLastMessageId;

    /**
     * Send feedback {@link AsyncTask}.
     * If the class is intended to send a simple feedback message, the a POST is made with the
     * specific data
     * If the class is intended to fetch the messages by providing a token, a GET is made
     *
     * @param context         {@link Context} object
     * @param urlString       URL for sending feedback/fetching messages
     * @param name            Name of the feedback sender
     * @param email           Email of the feedback sender
     * @param subject         Message subject
     * @param text            The message
     * @param attachmentUris  List of all attached files
     * @param token           Token received after sending the first feedback. This should be
     *                        stored in {@link SharedPreferences}
     * @param handler         Handler object to send data back to the activity
     * @param isFetchMessages If true, the {@link AsyncTask} will perform a GET, fetching the
     *                        messages.
     *                        If false, the {@link AsyncTask} will perform a POST, sending the
     *                        feedback message
     */
    public SendFeedbackTask(Context context, String urlString, String name, String email,
                            String subject,String text, String userId, List<Uri> attachmentUris, String token,
                            Handler handler, boolean isFetchMessages) {

        this.mContext = context;
        this.mUrlString = urlString;
        this.mName = name;
        this.mEmail = email;
        this.mSubject = subject;
        this.mText = text;
        this.mUserId = userId;
        this.mAttachmentUris = attachmentUris;
        this.mToken = token;
        this.mHandler = handler;
        this.mIsFetchMessages = isFetchMessages;
        this.mShowProgressDialog = true;
        this.mLastMessageId = -1;

        if (context != null) {
            Constants.loadFromContext(context);
        }
    }

    public void setShowProgressDialog(boolean showProgressDialog) {
        this.mShowProgressDialog = showProgressDialog;
    }

    public void setLastMessageId(int lastMessageId) {
        this.mLastMessageId = lastMessageId;
    }

    public void setHandler(Handler handler){
        mHandler = handler;
    }

    public void attach(Context context) {
        this.mContext = context;
        if (getStatus() == Status.RUNNING && (mProgressDialog == null || !mProgressDialog.isShowing()) && mShowProgressDialog) {
            mProgressDialog = ProgressDialog.show(mContext, "", getLoadingMessage(), true, false);
        }
    }

    public void detach() {
        mContext = null;
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        }
    }

    @Override
    protected void onPreExecute() {
        if ((mProgressDialog == null || !mProgressDialog.isShowing()) && mShowProgressDialog) {
            mProgressDialog = ProgressDialog.show(mContext, "", getLoadingMessage(), true, false);
        }
    }

    @Override
    protected HashMap<String, String> doInBackground(Void... args) {
        if (mIsFetchMessages && mToken != null) {
            /** If we are fetching messages then do a GET */
            return doGet();
        } else {
            /**
             * If we are sending a feedback do POST, and if we are sending a feedback
             * to an existing discussion do PUT
             */
            if (!mIsFetchMessages) {
                if (mAttachmentUris.isEmpty()) {
                    return doPostPut();
                } else {
                    HashMap<String, String> result = doPostPutWithAttachments();
                    if (result != null) {
                        clearTemporaryFolder(result);
                    }
                    return result;
                }
            } else {
                return null;
            }
        }
    }

    private void clearTemporaryFolder(HashMap<String, String> result) {
        String status = result.get("status");
        if ((status != null) && (status.startsWith("2")) && (mContext != null)) {
            File folder = new File(mContext.getCacheDir(), FILE_TAG);
            if (folder.exists()) {
                for (File file : folder.listFiles()) {
                    if (file != null) {
                        Boolean success = file.delete();
                        if (!success) {
                            HockeyLog.debug(TAG, "Error deleting file from temporary folder");
                        }
                    }
                }
            }

            // Delete sent screenshots as well.
            File hockeyAppStorageDir = Constants.getHockeyAppStorageDir(mContext);
            File[] screenshots = hockeyAppStorageDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".jpg");
                }
            });
            if (screenshots != null) {
                for (File screenshot : screenshots) {
                    if (mAttachmentUris.contains(Uri.fromFile(screenshot))) {
                        if (screenshot.delete()) {
                            HockeyLog.debug(TAG, "Screenshot '" + screenshot.getName() + "' has been deleted");
                        } else {
                            HockeyLog.error(TAG, "Error deleting screenshot");
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onPostExecute(HashMap<String, String> result) {
        if (mProgressDialog != null) {
            try {
                mProgressDialog.dismiss();
            } catch (Exception ignored) {
            }
        }

        /** If the Handler object is not NULL, send a message to the Activity with the result */
        if (mHandler != null) {
            Message msg = new Message();
            Bundle bundle = new Bundle();

            if (result != null) {
                bundle.putString(BUNDLE_REQUEST_TYPE, result.get("type"));
                bundle.putString(BUNDLE_FEEDBACK_RESPONSE, result.get("response"));
                bundle.putString(BUNDLE_FEEDBACK_STATUS, result.get("status"));
            } else {
                bundle.putString(BUNDLE_REQUEST_TYPE, "unknown");
            }

            msg.setData(bundle);

            mHandler.sendMessage(msg);
        }
    }

    /**
     * POST/PUT
     *
     * @return
     */
    private HashMap<String, String> doPostPut() {
        HashMap<String, String> result = new HashMap<>();
        result.put("type", "send");

        HttpURLConnection urlConnection = null;
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("name", mName);
            parameters.put("email", mEmail);
            parameters.put("subject", mSubject);
            parameters.put("text", mText);
            parameters.put("bundle_identifier", Constants.APP_PACKAGE);
            parameters.put("bundle_short_version", Constants.APP_VERSION_NAME);
            parameters.put("bundle_version", Constants.APP_VERSION);
            parameters.put("os_version", Constants.ANDROID_VERSION);
            parameters.put("oem", Constants.PHONE_MANUFACTURER);
            parameters.put("model", Constants.PHONE_MODEL);
            parameters.put("sdk_version", Constants.SDK_VERSION);
            if (mUserId != null) {
                parameters.put("user_string", mUserId);
            }

            if (mToken != null) {
                mUrlString += mToken + "/";
            }

            TrafficStats.setThreadStatsTag(Constants.THREAD_STATS_TAG);
            urlConnection = new HttpURLConnectionBuilder(mUrlString)
                    .setRequestMethod(mToken != null ? "PUT" : "POST")
                    .writeFormFields(parameters)
                    .build();

            urlConnection.connect();

            result.put("status", String.valueOf(urlConnection.getResponseCode()));
            result.put("response", getStringFromConnection(urlConnection));
        } catch (IOException e) {
            HockeyLog.error("Failed to send feedback message", e);
        } finally {
            TrafficStats.clearThreadStatsTag();
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        return result;
    }

    /**
     * POST/PUT with attachments
     *
     * @return
     */
    private HashMap<String, String> doPostPutWithAttachments() {
        HashMap<String, String> result = new HashMap<>();
        result.put("type", "send");

        HttpURLConnection urlConnection = null;
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("name", mName);
            parameters.put("email", mEmail);
            parameters.put("subject", mSubject);
            parameters.put("text", mText);
            parameters.put("bundle_identifier", Constants.APP_PACKAGE);
            parameters.put("bundle_short_version", Constants.APP_VERSION_NAME);
            parameters.put("bundle_version", Constants.APP_VERSION);
            parameters.put("os_version", Constants.ANDROID_VERSION);
            parameters.put("oem", Constants.PHONE_MANUFACTURER);
            parameters.put("model", Constants.PHONE_MODEL);
            parameters.put("sdk_version", Constants.SDK_VERSION);
            if (mUserId != null) {
                parameters.put("user_string", mUserId);
            }
            if (mToken != null) {
                mUrlString += mToken + "/";
            }

            TrafficStats.setThreadStatsTag(Constants.THREAD_STATS_TAG);
            urlConnection = new HttpURLConnectionBuilder(mUrlString)
                    .setRequestMethod(mToken != null ? "PUT" : "POST")
                    .writeMultipartData(parameters, mContext, mAttachmentUris)
                    .build();

            urlConnection.connect();

            result.put("status", String.valueOf(urlConnection.getResponseCode()));
            result.put("response", getStringFromConnection(urlConnection));

        } catch (IOException e) {
            HockeyLog.error("Failed to send feedback message", e);
        } finally {
            TrafficStats.clearThreadStatsTag();
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        return result;
    }

    /**
     * GET
     *
     * @return
     */
    private HashMap<String, String> doGet() {
        StringBuilder sb = new StringBuilder();
        sb.append(mUrlString).append(Util.encodeParam(mToken));

        if (mLastMessageId != -1) {
            sb.append("?last_message_id=").append(mLastMessageId);
        }

        HashMap<String, String> result = new HashMap<>();

        HttpURLConnection urlConnection = null;
        try {
            TrafficStats.setThreadStatsTag(Constants.THREAD_STATS_TAG);
            urlConnection = new HttpURLConnectionBuilder(sb.toString())
                    .build();

            result.put("type", "fetch");

            urlConnection.connect();

            result.put("status", String.valueOf(urlConnection.getResponseCode()));
            result.put("response", getStringFromConnection(urlConnection));
        } catch (IOException e) {
            HockeyLog.error("Failed to fetching feedback messages", e);
        } finally {
            TrafficStats.clearThreadStatsTag();
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        return result;
    }

    /**
     * Builds loading message depending on request type.
     *
     * @return loading message for progress bar.
     */
    private String getLoadingMessage() {
        String loadingMessage = mContext.getString(R.string.hockeyapp_feedback_sending_feedback_text);
        if (mIsFetchMessages) {
            loadingMessage = mContext.getString(R.string.hockeyapp_feedback_fetching_feedback_text);
        }
        return loadingMessage;
    }
}
