package io.github.zopulus.ffc.util;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Provider and activity share this lock; readers never restore a backup during a write. */
public final class ConfigFile {
    private ConfigFile() {}
    public static synchronized JSONObject read(Context context) throws Exception {
        return new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8));
    }
    /** Bootstrap only a missing file; preserve existing data and AtomicFile backups. */
    public static synchronized boolean initializeIfMissing(Context context, JSONObject defaults) throws Exception {
        File base = file(context).getBaseFile();
        if (base.exists() || new File(base.getPath() + ".bak").exists()) return false;
        write(context, defaults);
        return true;
    }
    public static synchronized void write(Context context, JSONObject json) throws Exception {
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "config.json"));
    }
}
