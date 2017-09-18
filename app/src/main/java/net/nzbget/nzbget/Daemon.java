package net.nzbget.nzbget;

/**
 * Based on https://github.com/nzbget/android - GPL-2.0 !
 */

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Daemon
{

    private static Daemon instance = null;
    private static String DAEMONSH = "/data/data/net.nzbget.nzbget/lib/libdadaemon.so";

    public static Daemon getInstance()
    {
        if (instance == null)
        {
            instance = new Daemon();
        }
        return instance;
    }

    public enum Status { STATUS_NO_INSTALLER, STATUS_NOTINSTALLED, STATUS_STOPPED, STATUS_RUNNING; };

    public Status status(String userDir)
    {
        if (! new File("/data/data/net.nzbget.nzbget/nzbget").exists())
        {
            File dir = new File(userDir);
            File[] files = dir.listFiles();
            for (File inFile : files) {
                String curName = inFile.getName();
                if (curName.startsWith("nzbget-") && curName.endsWith(".run")) {
                    return Status.STATUS_NOTINSTALLED;
                }
            }
            return Status.STATUS_NO_INSTALLER;
        }

        File lockFile = new File("/data/data/net.nzbget.nzbget/nzbget/nzbget.lock");
        if (!lockFile.exists())
        {
            return Status.STATUS_STOPPED;
        }

        try
        {
            Process process = Runtime.getRuntime().exec("ps /data/data/net.nzbget.nzbget/nzbget/nzbget");
            BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
            int num = 0;
            while (br.readLine() != null)
            {
                num++;
            }
            if (num > 1)
            {
                return Status.STATUS_RUNNING;
            }
        }
        catch (IOException e)
        {
            // ignore
        }

        return Status.STATUS_STOPPED;
    }

    public boolean exec(String cmdLine)
    {
        boolean ok = false;
        try
        {
            Log.i("Daemon", "executing: " + cmdLine);
            Process proc = Runtime.getRuntime().exec(cmdLine);
            InputStream stdout = proc.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            InputStream stderr = proc.getErrorStream();
            BufferedReader br2 = new BufferedReader(new InputStreamReader(stderr));
            proc.waitFor();
            String processOutput = br.readLine();
            while (processOutput != null && processOutput.length() > 0) {
                Log.d("Daemon", "stdout: " + processOutput);
                processOutput = br.readLine();
            }
            processOutput = br2.readLine();
            while (processOutput != null && processOutput.length() > 0) {
                Log.d("Daemon", "errout: " + processOutput);
                processOutput = br2.readLine();
            }
            ok = proc.exitValue() == 0;
            Log.d("Daemon", "exitval: " + ok);
        }
        catch (Exception e)
        {
            Log.e("Daemon", "Command '" + cmdLine + "' failed", e);
        }
        return ok;
    }

    public boolean install(String installer, String maindir)
    {
        // path to the installer
        return exec(DAEMONSH + " install " + installer);
    }

    public boolean remove()
    {
        return exec(DAEMONSH + " remove");
    }

    public boolean start()
    {
        return exec(DAEMONSH + " start");
    }

    public boolean stop()
    {
        return exec(DAEMONSH + " stop");
    }
}
