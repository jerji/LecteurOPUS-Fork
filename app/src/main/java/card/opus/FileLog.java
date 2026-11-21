package card.opus;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLog {
    private static File logFile = null;
    private static final String TAG = "FileLog";
    private static final int MAX_SIZE = 512 * 1024; // 512KB max

    public static void init(Context context) {
        try {
            File dir = new File(context.getFilesDir(), "logs");
            dir.mkdirs();
            logFile = new File(dir, "app_debug.log");
            // Truncate if over max size
            if (logFile.exists() && logFile.length() > MAX_SIZE) {
                logFile.delete();
            }
            write("=== Log started ===");
        } catch (Exception e) {
            Log.e(TAG, "init error: " + e.getMessage());
        }
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("I/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        write("E/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        write("E/" + tag + ": " + msg + " - " + android.util.Log.getStackTraceString(tr));
    }

    private static void write(String line) {
        if (logFile == null) return;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            String ts = sdf.format(new Date());
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write((ts + " " + line + "\n").getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "write error: " + e.getMessage());
        }
    }

    public static String getLogPath(Context context) {
        return new File(context.getFilesDir(), "logs/app_debug.log").getAbsolutePath();
    }
}