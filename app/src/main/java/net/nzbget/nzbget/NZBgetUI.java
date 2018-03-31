package net.nzbget.nzbget;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.wdc.nassdk.MyCloudUIServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

import static net.nzbget.nzbget.AppConstants.ACTION_INSTALL_DEFAULT;
import static net.nzbget.nzbget.AppConstants.ACTION_INSTALL_CUSTOM;
import static net.nzbget.nzbget.AppConstants.ACTION_REMOVE;
import static net.nzbget.nzbget.AppConstants.ACTION_START;
import static net.nzbget.nzbget.AppConstants.ACTION_STOP;

/**
 * Based on https://github.com/nzbget/android - GPL-2.0 !
 */

/**
 * This is the UI class of the application and it overrrides the get() and post() methods to serve
 * different needs of this application.
 *
 * This class extends MyCloudUIServer and implement all required methods.
 */
public class NZBgetUI extends MyCloudUIServer {
    private static final String TAG = NZBgetUI.class.getName();
    private Context mContext;

    // intent extra key for NZBgetService
    public static final String KEY_MYCLOUD_USER = "mycloud_userid";
    // cookie vars
    private static final int COOKIE_EXPIRATION_PERIOD_DAYS = 30;
    private static final String ACCESS_TOKEN_NAME = "access_token";
    private static final int LIMIT = 1000;

    /**
     * Constructor
     */
    public NZBgetUI(Context context) {
        super(context);
        mContext = context;
    }

//    @Override
//    public Response get(IHTTPSession ihttpSession) {
//        String token = getAccessToken(ihttpSession); //Get access token
//        String baseUrl = getBaseUrl(ihttpSession); // get base url for further calls
//        Log.d(TAG, "##### base URL:" + baseUrl);
//        return newFixedLengthResponse("<html><head></head><body><h2>Please enter folder</h2><form action=\""+baseUrl+"\" method=\"post\"><input type=\"text\" name=\"folder_name\"><br/><br/><input type=\"submit\" value=\"Create\"></form></body></html>");
//    }

    /**
     * This get method serve as the landing screen for this application.
     *
     * @param session
     * @return Response
     */
    @Override
    public Response get(IHTTPSession session) {
        String token = getAccessToken(session); //Get access token
        String baseUrl = getBaseUrl(session); // get base url for further calls
        String uri = session.getUri();
        String userDir = MyCloudUIServer.getRootFolder(mContext, getMyCloudUserId(session));
        Log.d(TAG, "##### base URL: " + baseUrl);
        Log.d(TAG, "##### access_token: " + token);
        if (uri != null && uri.endsWith("status")) {
            Daemon d = Daemon.getInstance();
            Daemon.Status s = d.status(userDir);
            String response = "";
            switch (s) {
                case STATUS_NO_INSTALLER:
                    response = "NO_INSTALLER"; break;
                case STATUS_NOT_INSTALLED:
                    response = "NOT_INSTALLED"; break;
                case STATUS_RUNNING:
                    response = "RUNNING"; break;
                case STATUS_STOPPED:
                    response = "STOPPED"; break;
            }
            return newFixedLengthResponse(response);
        }
        Response response = newFixedLengthResponse(getViewSource(session));

        if (token != null) {
            Log.d(TAG, "Set cookies for token " + token);
            final NanoHTTPD.CookieHandler cookieHandler = new NanoHTTPD.CookieHandler(session.getHeaders());
            cookieHandler.set(ACCESS_TOKEN_NAME, token, COOKIE_EXPIRATION_PERIOD_DAYS);
            cookieHandler.unloadQueue(response);
            String setCookieHeader = response.getHeader("Set-Cookie");
            Log.d(TAG, "Cookie Header: " + setCookieHeader);
        }
        return response; // This is an another way to load html page.
    }

    /**
     * The post method to serve users post requests. Anything UI want to send for backend service should ideally use post.
     * @param session - the iHttpSession
     * @return
     */
    @Override
    public Response post(IHTTPSession session) {
        try{
            Map<String, String> files = new HashMap<String, String>();
            session.parseBody(files);

            String postBody = session.getQueryParameterString();
            Map<String, List<String>> postParams = session.getParameters();
            // f
            // action=Download
            // 0
            Log.d(TAG, "##### Post Body: " + postBody);
            String action = postParams.get("action").get(0);

            String intentAction = "";
            switch (action) {
                case "Download":
                    intentAction = ACTION_INSTALL_DEFAULT;
                    break;
                case "Install":
                    intentAction = ACTION_INSTALL_CUSTOM;
                    break;
                case "Start":
                    intentAction = ACTION_START;
                    break;
                case "Stop":
                    intentAction = ACTION_STOP;
                    break;
                case "Remove":
                    intentAction = ACTION_REMOVE;
                    break;
                default:
                    Log.i(TAG, "Unsupported action: " + action);
                    return newFixedLengthResponse("<button onclick=\"window.history.back()\">Invalid Action</button>");
            }
            startIntent(getMyCloudUserId(session), intentAction);

            return newFixedLengthResponse("<button onclick=\"window.history.back()\">Back</button>");

        }catch (Exception e) {
            Log.d(TAG, "##### POST handling exception " + e.getMessage());
            return newFixedLengthResponse("<h1>Sorry, folder creation failed.</h1>");
        }
    }

    /**
     * Initiate the background business process service
     * @param myCloudUser
     */
    private void startIntent(String myCloudUser, String action){
        Log.d(TAG, "User " + myCloudUser + " wants to do action " + action);
        Intent i = new Intent(mContext, NZBgetService.class);
        i.setAction(action);
        i.putExtra(KEY_MYCLOUD_USER, myCloudUser);
        mContext.startService(i);
    }

    public static String getMyCloudUserId(IHTTPSession session) {
        String myCloudUser = MyCloudUIServer.getMyCloudUserId(session);
        if (TextUtils.isEmpty(myCloudUser)){
            myCloudUser = "auth0|usernotfound";
        }
        return myCloudUser;
    }

    /*
    *  Get the IPv4 address of the Cuberite server
    */
    public String getIpAddress() {
        try {
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.getName().contains("eth")) {
                    for (Enumeration<InetAddress> addresses = networkInterface.getInetAddresses(); addresses.hasMoreElements();) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && (address.getAddress().length == 4)) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            return e.toString();
        }
        return null;
    }

    /**
     * Load html page from asset folder
     * @return
     */
    private String getViewSource(IHTTPSession session) {
        try {
            String baseUrl = getBaseUrl(session); // get base url for further calls
            baseUrl = (baseUrl == null)? "" : baseUrl;
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(mContext.getAssets().open("index.html")));
            String line = "";
            String ipAddress = getIpAddress();
            while ((line = reader.readLine()) != null) {
                line = line.replaceAll("###IP_ADDRESS###", ipAddress);
                line = line.replaceAll("###BASE_URL###", baseUrl);
                builder.append(line + '\n');
            }
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return "<h1>Sorry index.html cannot be loaded</h1>";
        }
    }

}
