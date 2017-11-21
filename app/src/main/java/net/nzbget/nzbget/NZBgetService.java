package net.nzbget.nzbget;

/**
 * Based on https://github.com/nzbget/android - GPL-2.0 !
 */


import android.app.DownloadManager;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.wdc.nassdk.MyCloudUIServer;

import static net.nzbget.nzbget.AppConstants.ACTION_INSTALL_DEFAULT;
import static net.nzbget.nzbget.AppConstants.ACTION_INSTALL_CUSTOM;
import static net.nzbget.nzbget.AppConstants.ACTION_START;
import static net.nzbget.nzbget.AppConstants.ACTION_STOP;
import static net.nzbget.nzbget.AppConstants.ACTION_REMOVE;
import static net.nzbget.nzbget.AppConstants.KEY_MYCLOUD_USER;

public class NZBgetService extends IntentService {

    private final String TAG = NZBgetService.class.getName();

    public NZBgetService() {
        super(NZBgetService.class.getName());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "NZBgetService onHandleIntent.......");
        if (intent != null) {
            final String action = intent.getAction();
            Daemon d = Daemon.getInstance();
            if (!intent.hasExtra(KEY_MYCLOUD_USER)) {
                Log.e(TAG, "I don't deal with anonymous strangers");
                return;
            }
            final String userId = intent.getStringExtra(KEY_MYCLOUD_USER);
            // final String installType = intent.getStringExtra("INSTALL_TYPE")
            final String installType = null;

            switch (action) {
                case ACTION_INSTALL_DEFAULT:
                    Log.i(TAG, "Default nzbget installation");
                    installDaemon(installType, userId);
                    break;
                case ACTION_INSTALL_CUSTOM:
                    Log.i(TAG, "Custom nzbget installation");
                    installCustom(userId);
                    break;
                case ACTION_START:
                    Log.i(TAG, "Starting nzbget");
                    loadScripts(userId);
                    d.start();
                    break;
                case ACTION_STOP:
                    Log.i(TAG, "Stopping nzbget");
                    d.stop();
                    break;
                case ACTION_REMOVE:
                    Log.i(TAG, "Removing nzbget");
                    d.remove();
                    break;
                default:
                    Log.e(TAG, "Got unexpected action: " + action);
            }
        }

    }

//    @Override
//    public int onStartCommand(Intent intent, int flags, int startId) {
//        Log.e(TAG, "NZBgetService onStartCommand..........");
//        return Service.START_STICKY;
//    }

    private enum InstallerKind {STABLE_RELEASE, STABLE_DEBUG, TESTING_RELEASE, TESTING_DEBUG}

    private boolean downloading = false;
    private boolean installing = false;
    private InstallerKind installerKind;
    private String downloadName;
    private String mainDir;
    private long downloadId;
    private String downloadUrl;

    private void finished() {
        //setStatusText(null);
        // TODO: broadcast DO_NOT_DISTURB = false
        //enableButtons(true);
        downloading = false;
    }

    private void setupMainDir(String userId) {
        File rootDir = new File(MyCloudUIServer.getRootFolder(NZBgetService.this, userId));

        File maindir = new File(rootDir, "nzbget");
        if (maindir.mkdirs()) {
            Log.i(TAG, "Created nzbget maindir: " + maindir.getAbsolutePath());
        } else {
            if (maindir.exists()) {
                Log.i(TAG, "Nzbget maindir already exists: " + maindir.getAbsolutePath());
            } else {
                Log.e(TAG, "Nzbget maindir could not be created.. check storage permissions for " + maindir.getAbsolutePath());
            }
        }
        this.mainDir = maindir.getAbsolutePath();
    }

    public void installDaemon(String installerType, String userId) {
        setupMainDir(userId);

        Log.i("InstallService", "Downloading installer package...");

        if (installerType == null || installerType.equals("STABLE_RELEASE")) {
            installerKind = InstallerKind.STABLE_RELEASE;
        } else if (installerType.equals("STABLE_DEBUG")) {
            installerKind = InstallerKind.STABLE_DEBUG;
        } else if (installerType.equals("TESTING_RELEASE")) {
            installerKind = InstallerKind.TESTING_RELEASE;
        } else if (installerType.equals("TESTING_DEBUG")) {
            installerKind = InstallerKind.TESTING_DEBUG;
        }

        downloading = true;
        // TODO: create DO_NOT_DISTURB broadcast
        //enableButtons(false);
        downloadInfo();
    }

    public void installCustom(String userId) {
        setupMainDir(userId);

        String downloadName = null;
        File dir = new File(MyCloudUIServer.getRootFolder(this, userId));
        File[] files = dir.listFiles();
        for (File inFile : files) {
            String curName = inFile.getName();
            if (curName.startsWith("nzbget-") && curName.endsWith(".run") &&
                    (downloadName == null || downloadName.compareTo(curName) > 0)) {
                downloadName = curName;
            }
        }
        // TODO: broadcast DO_NOT_DISTURB
        //enableButtons(false);
        if (downloadName != null) {
            try {
                // copy installer to files dir to circumvent | characters in filename :-(
                File installer = new File(getFilesDir(), downloadName);
                copy(new File(dir, downloadName), installer);
                installFile(installer);
            } catch (IOException e) {
                Log.e("InstallService", "Failed to copy " + downloadName + " to files directory");
            }
        } else {
            Log.e("InstallService", "Could not find NZBGet daemon installer in Download-directory.");
            finished();
        }
    }

    private void downloadInfo() {
        File dataDir = this.getFilesDir();
        File file = new File(dataDir, "nzbget-version-linux.json");
        file.delete();

        String url = "http://nzbget.net/info/nzbget-version-linux.json";
        Log.i("InstallService", "Start download " + url + " to " + file.getAbsolutePath());
        String result = downloadFile(url, file.getAbsolutePath());
        if (result != null) {
            Log.e("InstallService", "Failed to download version info: " + result);
            finished();
        } else {
            infoCompleted(0);
        }
//        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
//        request.setTitle("NZBGet daemon installer info");
//        request.setDescription("nzbget-version-linux.json");
//        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
//        request.setDestinationInExternalFilesDir(this, null, "nzbget-version-linux.json");
//
//        registerReceiver(onDownloadFinishReceiver = new BroadcastReceiver()
//        {
//            @Override
//            public void onReceive(Context context, Intent i)
//            {
//                infoCompleted(i.getExtras().getLong(DownloadManager.EXTRA_DOWNLOAD_ID));
//            }
//        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
//
//        registerReceiver(onNotificationClickReceiver = new BroadcastReceiver()
//        {
//            @Override
//            public void onReceive(Context context, Intent intent)
//            {
//                downloadTouched();
//            }
//        }, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));

        // get download service and enqueue file
//        DownloadManager manager = (DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
//        downloadId = manager.enqueue(request);
    }

    private void infoCompleted(long aLong) {
        Log.i("InstallService", "download info completed");
//        try
//        {
//            unregisterReceiver(onDownloadFinishReceiver);
//            unregisterReceiver(onNotificationClickReceiver);
//        }
//        catch (IllegalArgumentException e)
//        {
//            //Patch for bug: http://code.google.com/p/android/issues/detail?id=6191
//        }

        downloadUrl = null;
        downloadName = null;

        File file = new File(getFilesDir(), "nzbget-version-linux.json");
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                if (((installerKind == InstallerKind.STABLE_RELEASE ||
                        installerKind == InstallerKind.STABLE_DEBUG) &&
                        line.indexOf("stable-download") > -1) ||
                        ((installerKind == InstallerKind.TESTING_RELEASE ||
                                installerKind == InstallerKind.TESTING_DEBUG) &&
                                line.indexOf("testing-download") > -1)) {
                    downloadUrl = line.substring(line.indexOf('"', line.indexOf(":")) + 1, line.length() - 2);
                    if (installerKind == InstallerKind.STABLE_DEBUG ||
                            installerKind == InstallerKind.TESTING_DEBUG) {
                        downloadUrl = downloadUrl.substring(0, downloadUrl.lastIndexOf(".run")) + "-debug.run";
                    }
                    Log.i("InstallService", "URL: " + downloadUrl);
                    downloadName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                    Log.i("InstallService", "Name: " + downloadName);
                    break;
                }

                Log.i("InstallService", line);
            }
            br.close();
            file.delete();
        } catch (IOException e) {
            Log.d("InstallService", "Could not read version info:" + e.getMessage());
            finished();
            return;
        }

        if (downloadUrl == null) {
            Log.d("InstallService", "Could not read version info: file format error.");
            finished();
            return;
        }

        downloadInstaller();
    }

    private void downloadInstaller() {
        File file = new File(getFilesDir(), downloadName);
        file.delete();
        Log.d("InstallService", "Start download of installer: " + file.getAbsolutePath());
        String result = downloadFile(downloadUrl, file.getAbsolutePath());
        if (result != null) {
            downloadTouched();
        } else {
            downloadCompleted(0);
        }
    }

    private void downloadTouched() {
        Log.i("InstallService", "Cancelling download");
        // do cancel
    }

    protected void downloadCompleted(long downloadId) {
        downloading = false;

        Log.i("InstallService", "download installer completed");

        // TODO: validate - is part of installer

        Log.i("InstallService", "download installer successful");
        File installer = new File(getFilesDir(), downloadName);
        installFile(installer);
    }

    private boolean validDownload(long downloadId) {
        //Verify if download is a success
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = manager.query(new DownloadManager.Query().setFilterById(downloadId));
        if (c.moveToFirst()) {
            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                return true;
            }
        }
        return false;
    }

    public void copy(File src, File dst) throws IOException {
        FileInputStream inStream = new FileInputStream(src);
        FileOutputStream outStream = new FileOutputStream(dst);
        FileChannel inChannel = inStream.getChannel();
        FileChannel outChannel = outStream.getChannel();
        inChannel.transferTo(0, inChannel.size(), outChannel);
        inStream.close();
        outStream.close();
    }

    private String downloadFile(String stringUrl, String targetLocation) {
        //receiver.send(DownloadReceiver.PROGRESS_START, null);

        String result = null;

        InputStream inputStream = null;
        OutputStream outputStream = null;
        HttpURLConnection connection = null;
        install:
        try {
            Log.d("InstallService", "Started downloading " + stringUrl);
            URL url = new URL(stringUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                String error = "Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage();
                Log.e("InstallService", error);
                result = error;
                break install;
            }

            int length = connection.getContentLength();
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(targetLocation);

            byte data[] = new byte[4096];
            long total = 0;
            int count;
            while ((count = inputStream.read(data)) != -1) {
                total += count;
                if (length > 0) { // only if total length is known
                    Bundle bundle = new Bundle();
                    bundle.putInt("progress", (int) total);
                    bundle.putInt("max", length);
                    //receiver.send(DownloadReceiver.PROGRESS_NEWDATA, bundle);
                }
                outputStream.write(data, 0, count);
            }
            Log.d("InstallService", "Finished downloading");
        } catch (Exception e) {
            result = e.toString();
            Log.e("InstallService", "An error occurred when downloading a zip", e);
        } finally {
            try {
                if (outputStream != null)
                    outputStream.close();
                if (inputStream != null)
                    inputStream.close();
            } catch (IOException ignored) {
            }

            if (connection != null)
                connection.disconnect();
        }

        //receiver.send(DownloadReceiver.PROGRESS_END, null);
        return result;
    }

    /* Install the installer file */
    private void installFile(File installer) {
        Log.i("InstallService", "Installing...");
        installing = true;
        boolean ok = Daemon.getInstance().install(installer.getAbsolutePath(), mainDir);

        // to use the Android installer instead of the shell script, uncomment this
        // extractInstaller(installer);

        updateConfig();
        installCompleted(true, "Failed");

//        InstallTask task = new InstallTask(this, downloadName);
//        Thread thread = new Thread(task);
//        thread.start();
    }

    private void updateConfig()
    {
        Log.i("InstallService", "Updating config file with MainDir " + mainDir);
        File config = new File("/data/data/net.nzbget.nzbget/nzbget/nzbget.conf");
        try {
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(config));
            String line = "";
            while ((line = reader.readLine()) != null) {
                line = line.replaceAll("^MainDir=.*", "MainDir=" + mainDir);
                builder.append(line + '\n');
            }
            reader.close();
            FileWriter fw = new FileWriter(config);
            fw.write(builder.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getHeaderSize(String installer) {
        try {
            BufferedReader pmi = new BufferedReader(new FileReader(installer));
            try {
                String line;
                while ((line = pmi.readLine()) != null)
                    if (line.contains("HEADER"))
                        // get the second word as a long.
                        return Integer.parseInt(line.split("=")[1]);
                return -1;
            } finally {
                pmi.close();
            }
        } catch (FileNotFoundException e) {
            Log.e("InstallService", "An error occurred when seeking the header", e);
        } catch (IOException e) {
            Log.e("InstallService", "An error occurred when seeking the header", e);
        }
        return -1;
    }

    /* Extract the tar.gz archive from the installer after parsing the header */
    private void extractInstaller(String installer) {
        final int BUFFER = 2048;

        String tarFile = installer;
        String destFolder = getDir("nzbget", 0).getAbsolutePath();
        int headerSize = getHeaderSize(installer);
        Log.i("InstallService", "Installer has header of " + headerSize + " bytes");

        String arch = getArchitecture();
        Log.i("InstallService", "Select " + arch + " architecture");

        try {
            FileInputStream fin = new FileInputStream(tarFile);
            BufferedInputStream in = new BufferedInputStream(fin);

            /** Skip the header **/
            in.skip(headerSize);

            /** Create a TarArchiveInputStream object. **/
            GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);
            TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn);

            TarArchiveEntry entry = null;

            /** Read the tar entries using the getNextEntry method **/
            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                //Log.d("InstallService", "Extracting: " + entry.getName());
                /** If the entry is a directory, create the directory. **/
                if (entry.isDirectory()) {
                    File f = new File(destFolder, entry.getName());
                    f.mkdirs();
                }
                /**
                 * If the entry is a file,write the decompressed file to the disk
                 * and close destination stream.
                 **/
                else {
                    int count;
                    byte data[] = new byte[BUFFER];

                    File f = new File(destFolder, entry.getName());
                    FileOutputStream fos = new FileOutputStream(f);
                    BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);
                    while ((count = tarIn.read(data, 0, BUFFER)) != -1) {
                        dest.write(data, 0, count);
                    }
                    dest.close();

                    /** Clean up unnecessary binaries **/
                    String fileName = validateName(entry.getName(), arch);
                    if (fileName == null) {
                        Log.d("InstallService", "Skipped: " + entry.getName());
                        f.delete();
                    } else {
                        Log.d("InstallService", "Extracted: " + fileName);
                        f.renameTo(new File(destFolder, fileName));
                    }
                }
            }
            /** Close the input stream **/
            tarIn.close();
            Log.i("InstallService", "untar completed successfully!!");

        } catch (FileNotFoundException e) {
            Log.e("InstallService", "Failed to unpack due to FileNotFound", e);
        } catch (IOException e) {
            Log.e("InstallService", "Failed to unpack due to IOException", e);
        }
    }

    /* Get the architecture of the current platform */
    private String getArchitecture() {
        /* armv71, i686, 'mips' or 'mips64', 'arch64' or 'x86_64' */
        String arch = System.getProperty("os.arch");
        String[] armel_re = {"armv5.*", "armv6.*", "armel"};
        for (String regex : armel_re) {
            if (arch.matches(regex))
                return "armel";
        }
        String[] armhf_re = {"armv7.*", "armv8.*", "armhf"};
        for (String regex : armhf_re) {
            if (arch.matches(regex))
                return "armhf";
        }
        return arch;
    }

    /* Get the fileName or null when it's the wrong architecture */
    private String validateName(String filename, String arch) {
        String[] splitFile = filename.split("-");
        if ("unrar nzbget 7za".contains(splitFile[0])) {
            return (splitFile[1].equals(arch))? splitFile[0] : null;
        }
        return filename;
    }

    private void installCompleted(final boolean ok, final String errMessage) {
        installing = false;
        Log.i(TAG, "Installed completed: " + ok);
        // show this on GUI
    }

    /* Copy scripts from the MainDir/scripts folder into the internal scripts folder */
    private void loadScripts(String userId) {
        setupMainDir(userId);

        File dir = new File(mainDir, "scripts");
        File[] files = dir.listFiles();
        for (File inFile : files) {
            String curName = inFile.getName();
            if (curName.endsWith(".sh")) {
                File dst = new File(getFilesDir(), "../nzbget/scripts/" + curName);
                try {
                    copy(inFile, dst);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to copy " + curName + " to internal scripts dir");
                }
            }
        }

    }
}
