package io.agora.rtt;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

public class RttTask {

    // Agora ID and security parameters
    private static final String appId = System.getenv("APP_ID"); 
    private static final String appCertificate = System.getenv("APP_CERTIFICATE"); 
    private static final String customerId = System.getenv("CUSTOMER_ID"); 
    private static final String customerSecret = System.getenv("CUSTOMER_SECRET"); 

    // Cloud storage parameters
    private String ossSecretKey = System.getenv("OSS_SECRET_KEY"); 
    private String ossAccessKey = System.getenv("OSS_ACCESS_KEY");
    private String ossBucketName = System.getenv("OSS_BUCKET_NAME"); 
    private String ossBucketFolder = System.getenv("OSS_BUCKET_FOLDER"); 
    private String ossBucketSubFolder = System.getenv("OSS_BUCKET_SUBFOLDER"); 
    private static final String baseUrl = "https://api.agora.io";
    
    // Authorization header for HTTP requests
    private static final String authorizationHeader = "Basic " + new String(Base64.getEncoder()
            .encode((customerId + ":" + customerSecret).getBytes()));;
    
    public String channelName; // The channel for the RTT task
    public String status; // Holds the last status returned
    public String language = "en-US"; // Max 2 simultaneous languages are supported, separated by a comma.
    public String taskId = ""; // Holds the ID of the RTT task
    public int userId; // Identifies the user who sent the start request
    public int maxIdleTime = 120; // If there is no activity of this time, the task stops automatically.

    // Unique uids to access the audio in the channel, and send the text
    private int uidAudio = 111, uidText = 222; 
    private String tokenAudio, tokenText; // Tokens corresponding to the audio and text uids

    private String builderToken; // The builder token required to send start, query and stop requests
    private String instanceId; // A string that identifies the RTT task
    
    public RttTask(int userId, String channelName) {
        this.userId = userId;
        this.channelName = channelName;
        // InstanceId can be any unique string, best practice is to set it to the channel name.
        instanceId = channelName; 
    }

    private RttResult getBuilderToken() throws IOException {
        // Build the request endpoint url
        String url = baseUrl + "/v1/projects/" + appId + "/rtsc/speech-to-text/builderTokens";

        MediaType mediaType = MediaType.parse("application/json");
        // Specify the instanceID in the request body
        RequestBody body = RequestBody.create("{\n \"instanceId\" : \""
                + instanceId + "\" \n}", mediaType);
        // Send an HTTP POST request
        Request request = new Request.Builder()
                .addHeader("Authorization", authorizationHeader)
                .url(url)
                .method("POST", body)
                .build();

        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            String result = response.body().string();
            JSONObject jsonObject = new JSONObject(result);
            if (response.isSuccessful()) {
                // Save the builder token and return Success
                builderToken = jsonObject.getString("tokenName");
                return RttResult.SUCCESS;
            } else {
                // Failed to fetch a builder token
                status = jsonObject.getString("message");
                System.out.println("builder token not received: " + status);
                return RttResult.FAILED_TO_FETCH_BUILDER_TOKEN;
            }
        }
    }

    public RttResult startTranscription() throws JSONException, IOException {
        // Get a builderToken
        getBuilderToken();

        // Build the request endpoint url
        String url = baseUrl + "/v1/projects/" + appId
                + "/rtsc/speech-to-text/tasks?" + "builderToken=" + builderToken;

        // Get two RTC tokens
        tokenAudio = TokenBuilder.getToken(appId, appCertificate, channelName, uidAudio, 3600);
        tokenText = TokenBuilder.getToken(appId, appCertificate, channelName, uidText, 3600);

        // Build HTTP Request body JSON
        JSONObject startConfig = new JSONObject()
                .put("audio", new JSONObject()
                        .put("subscribeSource", "AGORARTC") // Currently fixed
                        .put("agoraRtcConfig", new JSONObject() 
                                .put("channelName", channelName) // Name of the channel for RTT 
                                .put("uid", String.valueOf(uidAudio)) // Uid used by the audio streaming bot. Must be an integer specified as a string. For example "111"
                                .put("token", tokenAudio) // RTC token for the audio uid
                                .put("channelType", "LIVE_TYPE") // Currently fixed
                                .put("subscribeConfig", new JSONObject()
                                        .put("subscribeMode", "CHANNEL_MODE")) // Currently fixed
                                .put("maxIdleTime", maxIdleTime))) // If there is no audio stream in the channel for more than this time, the RTT task stops automatically
                .put("config", new JSONObject()
                        .put("features", new JSONArray()
                                .put("RECOGNIZE")) // Currently fixed
                        .put("recognizeConfig", new JSONObject()
                                .put("language", language) // Supports at most two language codes separated by commas. For example, "en-US,ja-JP"
                                .put("model", "Model") // Currently fixed
                                .put("output", new JSONObject()
                                        .put("destinations", new JSONArray()
                                                .put("AgoraRTCDataStream")
                                                .put("Storage"))
                                        .put("agoraRTCDataStream",
                                                new JSONObject()
                                                        .put("channelName", channelName) // Name of the channel for RTT 
                                                        .put("uid", String.valueOf(uidText)) // Uid used for text streaming. Must be a unique integer specified as a string. For example "222"
                                                        .put("token", tokenText)) // RTC token for the text uid
                                        .put("cloudStorage", new JSONArray()
                                                .put(new JSONObject()
                                                        .put("format", "HLS") // Currently fixed
                                                        .put("storageConfig",
                                                                new JSONObject()
                                                                        .put("accessKey", ossAccessKey) // Access key of oss
                                                                        .put("secretKey", ossSecretKey) // Secret key of oss
                                                                        .put("bucket", ossBucketName) // Oss bucket name
                                                                        .put("vendor", 6) // Your Oss Vendor ID
                                                                        .put("region", 0) // Your Oss Region ID
                                                                        .put("fileNamePrefix",
                                                                                new JSONArray() // An array of directory strings to append to storage files
                                                                                        .put("folder")
                                                                                        .put("subFolder"))))))));

        MediaType mediaType = MediaType.parse("application/json");
        // Set the request body
        RequestBody body = RequestBody.create(startConfig.toString(), mediaType);
        Request request = new Request.Builder()
                .addHeader("Authorization", authorizationHeader) // Set the request header
                .url(url)
                .method("POST", body)
                .build();
        // Send a POST request
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            String result = response.body().string();
            if (response.isSuccessful()) {
                try {
                    // Read the status and task ID from the response 
                    JSONObject jsonObject = new JSONObject(result);
                    status = jsonObject.getString("status");
                    taskId = jsonObject.getString("taskId");
                    if (status.equals("IN_PROGRESS") || status.equals("STARTED")) {
                        System.out.println(
                                "RTT task started for channel: " + channelName + " ID: " + taskId);
                        // Confirm success
                        return RttResult.SUCCESS;
                    } else {
                        System.out.println("RTT task status: " + status);
                    }
                } catch (Exception e) {
                    System.out.println("Error parsing start call response");
                }
            } else {
                System.out.println("Exception starting speech-to-text task: " + result);
            }
            return RttResult.FAILED_TO_START_TASK;
        }
    }

    public RttResult stopTranscription() {
        // Build the request endpoint url
        String url = baseUrl + "/v1/projects/" + appId
                + "/rtsc/speech-to-text/tasks/" + taskId
                + "?builderToken=" + builderToken;

        // Send a DELETE request
        Request request = new Request.Builder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", authorizationHeader)
                .url(url)
                .delete()
                .build();

        OkHttpClient httpClient = new OkHttpClient();
        try {
            Response response = httpClient.newCall(request).execute();

            if (response.isSuccessful()) {
                status = "STOPPED";
                return RttResult.SUCCESS;
            } else {
                status = "UNKNOWN";
                return RttResult.FAILED_TO_STOP_TASK;
            }
        } catch (IOException e) {
            status = "Exception stopping RTT task";
            System.out.println(status);
            return RttResult.FAILED_TO_STOP_TASK;
        }

    }

    public RttResult queryTask() {
        // Build the request endpoint url
        String url = baseUrl + "/v1/projects/" + appId
                + "/rtsc/speech-to-text/tasks/" + taskId
                + "?builderToken=" + builderToken;

        // Send a GET request
        Request request = new Request.Builder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", authorizationHeader)
                .url(url)
                .get()
                .build();

        OkHttpClient httpClient = new OkHttpClient();
        try {
            Response response = httpClient.newCall(request).execute();
            String result = response.body().string();

            JSONObject jsonObject = new JSONObject(result);
            if (response.isSuccessful()) {
                status = jsonObject.getString("status");
                // Confirm Success
                return RttResult.SUCCESS;
            } else {
                status = jsonObject.getString("message");
                return RttResult.FAILED_TO_QUERY_TASK;
            }
        } catch (IOException e) {
            status = "Exception querying RTT task";
            System.out.println(status);
            return RttResult.FAILED_TO_QUERY_TASK;
        }
    }
}
