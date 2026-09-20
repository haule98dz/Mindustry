package mindustry.android;

import android.*;
import android.annotation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.widget.*;
import arc.func.*;
import arc.util.*;

import java.io.*;

public class AndroidStorageHelper{
    public static final String PREFS_NAME = "mindustry_android_storage";
    public static final String KEY_CUSTOM_DATA_DIR = "custom_data_dir";
    public static final String KEY_SETUP_COMPLETED = "storage_setup_completed";

    public static final int STORAGE_PERM_REQUEST_CODE = 9901;
    public static final int STORAGE_FOLDER_PICKER_REQUEST_CODE = 9902;

    private static Runnable pendingPermissionCallback;
    private static Cons<File> pendingFolderCallback;
    private static boolean pendingIsInitial = false;
    private static boolean waitingForPermission = false;
    private static boolean handledResult = false;

    public static boolean hasStoragePermission(Context context){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            return Environment.isExternalStorageManager();
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                   context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void requestStoragePermission(Activity activity){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            try{
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, STORAGE_PERM_REQUEST_CODE);
            }catch(Exception e){
                try{
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivityForResult(intent, STORAGE_PERM_REQUEST_CODE);
                }catch(Exception ex){
                    Log.err("Failed to open storage settings", ex);
                    Toast.makeText(activity, "Could not open storage settings", Toast.LENGTH_SHORT).show();
                }
            }
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            activity.requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_PERM_REQUEST_CODE);
        }
    }

    public static File getDefaultDataDir(Context context){
        File ext = context.getExternalFilesDir(null);
        return ext != null ? ext : context.getFilesDir();
    }

    public static File getRecommendedSyncDir(){
        return new File(Environment.getExternalStorageDirectory(), "Mindustry");
    }

    public static File getDocumentsSyncDir(){
        File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(docs, "Mindustry");
    }

    public static boolean isSetupCompleted(Context context){
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_COMPLETED, false);
    }

    public static boolean isUsingDefault(Context context){
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_CUSTOM_DATA_DIR, null);
        return saved == null || saved.equals(getDefaultDataDir(context).getAbsolutePath());
    }

    public static boolean isSetupNeeded(Context context){
        if(!isSetupCompleted(context)) return true;
        if(!isUsingDefault(context) && !hasStoragePermission(context)) return true;
        return false;
    }

    public static File getDataDir(Context context){
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String custom = prefs.getString(KEY_CUSTOM_DATA_DIR, null);
        if(custom != null && !custom.trim().isEmpty()){
            File file = new File(custom.trim());
            if(file.exists() || file.mkdirs()){
                return file;
            }
        }
        return getDefaultDataDir(context);
    }

    public static void setDataDir(Context context, String path){
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_DATA_DIR, path)
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .apply();
    }

    public static void checkAndSetupStorage(Activity activity, Runnable onReady){
        activity.runOnUiThread(() -> {
            if(isActivityDead(activity)) return;

            if(isSetupCompleted(activity)){
                if(isUsingDefault(activity) || hasStoragePermission(activity)){
                    onReady.run();
                    return;
                }
            }

            if(!hasStoragePermission(activity)){
                promptPermissionDialog(activity, onReady);
            }else{
                promptChooseFolder(activity, true, folder -> onReady.run());
            }
        });
    }

    public static void promptPermissionDialog(Activity activity, Runnable onReady){
        if(isActivityDead(activity)) return;

        new AlertDialog.Builder(activity)
            .setTitle("Storage Access & Data Sync")
            .setMessage("To allow syncing Mindustry game data (saves, maps, schematics) between your devices (e.g., using Syncthing), Mindustry needs permission to access a shared folder outside the restricted Android sandbox.\n\nWould you like to grant 'All files access' to select a syncable folder, or continue using the default isolated folder?")
            .setCancelable(false)
            .setPositiveButton("Grant Permission", (dialog, which) -> {
                pendingPermissionCallback = onReady;
                waitingForPermission = true;
                handledResult = false;
                requestStoragePermission(activity);
            })
            .setNegativeButton("Use Default Folder", (dialog, which) -> {
                setDataDir(activity, getDefaultDataDir(activity).getAbsolutePath());
                onReady.run();
            })
            .show();
    }

    public static void promptChooseFolder(Activity activity, boolean isInitialSetup, Cons<File> onFolderChosen){
        if(isActivityDead(activity)) return;

        pendingFolderCallback = onFolderChosen;
        pendingIsInitial = isInitialSetup;

        File rec = getRecommendedSyncDir();
        File doc = getDocumentsSyncDir();
        File def = getDefaultDataDir(activity);

        String[] options = {
            "Recommended: " + rec.getAbsolutePath(),
            "Documents: " + doc.getAbsolutePath(),
            "Browse with System File Picker...",
            "Enter Custom Path...",
            "Default Folder (Isolated Android/data)"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("Select Game Data Folder")
            .setItems(options, (dialog, which) -> {
                switch(which){
                    case 0 -> selectFolder(activity, rec, isInitialSetup, onFolderChosen);
                    case 1 -> selectFolder(activity, doc, isInitialSetup, onFolderChosen);
                    case 2 -> {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        try{
                            activity.startActivityForResult(intent, STORAGE_FOLDER_PICKER_REQUEST_CODE);
                        }catch(Exception e){
                            Log.err("Failed to open document tree picker", e);
                            Toast.makeText(activity, "System file picker unavailable", Toast.LENGTH_SHORT).show();
                            promptCustomPathDialog(activity, isInitialSetup, onFolderChosen);
                        }
                    }
                    case 3 -> promptCustomPathDialog(activity, isInitialSetup, onFolderChosen);
                    case 4 -> selectFolder(activity, def, isInitialSetup, onFolderChosen);
                }
            });

        if(!isInitialSetup){
            builder.setNegativeButton("Cancel", null);
        }else{
            builder.setCancelable(false);
        }

        builder.show();
    }

    private static void promptCustomPathDialog(Activity activity, boolean isInitialSetup, Cons<File> onFolderChosen){
        if(isActivityDead(activity)) return;

        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(getRecommendedSyncDir().getAbsolutePath());
        input.setSelection(input.getText().length());

        FrameLayout container = new FrameLayout(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int)(16 * activity.getResources().getDisplayMetrics().density);
        params.setMargins(margin, margin / 2, margin, margin / 2);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(activity)
            .setTitle("Enter Custom Folder Path")
            .setMessage("Specify the full path on your device to store game data:")
            .setView(container)
            .setPositiveButton("OK", (dialog, which) -> {
                String path = input.getText().toString().trim();
                if(path.isEmpty()){
                    Toast.makeText(activity, "Path cannot be empty", Toast.LENGTH_SHORT).show();
                    promptChooseFolder(activity, isInitialSetup, onFolderChosen);
                }else{
                    selectFolder(activity, new File(path), isInitialSetup, onFolderChosen);
                }
            })
            .setNegativeButton("Back", (dialog, which) -> promptChooseFolder(activity, isInitialSetup, onFolderChosen))
            .show();
    }

    public static void selectFolder(Activity activity, File folder, boolean isInitialSetup, Cons<File> onFolderChosen){
        if(isActivityDead(activity)) return;

        try{
            if(!folder.exists() && !folder.mkdirs()){
                throw new IOException("Unable to create directory");
            }

            File testFile = new File(folder, ".mindustry_write_test_" + System.currentTimeMillis());
            if(!testFile.createNewFile()){
                throw new IOException("Cannot write test file in directory");
            }
            testFile.delete();
        }catch(Exception e){
            Log.err("Selected directory is not writable: " + folder, e);
            new AlertDialog.Builder(activity)
                .setTitle("Folder Not Writable")
                .setMessage("Mindustry cannot write to the selected location:\n" + folder.getAbsolutePath() + "\n\nPlease select another folder.")
                .setPositiveButton("Choose Again", (dialog, which) -> promptChooseFolder(activity, isInitialSetup, onFolderChosen))
                .show();
            return;
        }

        File defaultDir = getDefaultDataDir(activity);
        boolean hasExisting = hasExistingData(defaultDir);
        boolean targetHasData = hasExistingData(folder);

        if(isInitialSetup && !folder.equals(defaultDir) && hasExisting && !targetHasData){
            new AlertDialog.Builder(activity)
                .setTitle("Migrate Existing Save Data?")
                .setMessage("Existing save data and settings were found in the default app folder.\n\nWould you like to copy them to your new sync folder:\n" + folder.getAbsolutePath() + "\n\nThis preserves all your campaign progress and saves.")
                .setCancelable(false)
                .setPositiveButton("Copy Data", (dialog, which) -> {
                    ProgressDialog progress = new ProgressDialog(activity);
                    progress.setMessage("Copying save data to new folder...");
                    progress.setCancelable(false);
                    progress.show();

                    new Thread(() -> {
                        try{
                            copyDirectory(defaultDir, folder);
                        }catch(Exception ex){
                            Log.err("Failed to copy data to new folder", ex);
                        }
                        activity.runOnUiThread(() -> {
                            try{
                                if(progress.isShowing()) progress.dismiss();
                            }catch(Exception ignored){}
                            setDataDir(activity, folder.getAbsolutePath());
                            onFolderChosen.get(folder);
                        });
                    }).start();
                })
                .setNegativeButton("Start Fresh", (dialog, which) -> {
                    setDataDir(activity, folder.getAbsolutePath());
                    onFolderChosen.get(folder);
                })
                .show();
        }else{
            setDataDir(activity, folder.getAbsolutePath());
            onFolderChosen.get(folder);
        }
    }

    public static void handleResume(Activity activity){
        activity.runOnUiThread(() -> {
            if(isActivityDead(activity)) return;

            if(waitingForPermission && !handledResult){
                if(hasStoragePermission(activity)){
                    waitingForPermission = false;
                    promptChooseFolder(activity, true, folder -> {
                        if(pendingPermissionCallback != null){
                            pendingPermissionCallback.run();
                            pendingPermissionCallback = null;
                        }
                    });
                }
            }
            handledResult = false;
        });
    }

    public static void handleActivityResult(Activity activity, int requestCode, int resultCode, Intent data){
        activity.runOnUiThread(() -> {
            if(isActivityDead(activity)) return;

            if(requestCode == STORAGE_PERM_REQUEST_CODE){
                handledResult = true;
                waitingForPermission = false;
                if(hasStoragePermission(activity)){
                    promptChooseFolder(activity, true, folder -> {
                        if(pendingPermissionCallback != null){
                            pendingPermissionCallback.run();
                            pendingPermissionCallback = null;
                        }
                    });
                }else{
                    new AlertDialog.Builder(activity)
                        .setTitle("Permission Not Granted")
                        .setMessage("Storage permission was not granted. Mindustry will not be able to save data to a shared folder without this permission.\n\nWould you like to try again or continue using the default isolated folder?")
                        .setCancelable(false)
                        .setPositiveButton("Try Again", (dialog, which) -> requestStoragePermission(activity))
                        .setNegativeButton("Use Default Folder", (dialog, which) -> {
                            setDataDir(activity, getDefaultDataDir(activity).getAbsolutePath());
                            if(pendingPermissionCallback != null){
                                pendingPermissionCallback.run();
                                pendingPermissionCallback = null;
                            }
                        })
                        .show();
                }
            }else if(requestCode == STORAGE_FOLDER_PICKER_REQUEST_CODE){
                if(resultCode == Activity.RESULT_OK && data != null && data.getData() != null){
                    Uri treeUri = data.getData();
                    String path = getPathFromTreeUri(treeUri);
                    if(path != null){
                        selectFolder(activity, new File(path), pendingIsInitial, pendingFolderCallback);
                    }else{
                        new AlertDialog.Builder(activity)
                            .setTitle("Unrecognized Location")
                            .setMessage("Could not resolve a direct file path from the selected location. Please choose a folder on primary storage or SD card, or use the recommended /sdcard/Mindustry folder.")
                            .setPositiveButton("Choose Again", (dialog, which) -> promptChooseFolder(activity, pendingIsInitial, pendingFolderCallback))
                            .show();
                    }
                }else if(pendingIsInitial){
                    promptChooseFolder(activity, true, pendingFolderCallback);
                }
            }
        });
    }

    public static void handlePermissionsResult(Activity activity, int requestCode, String[] permissions, int[] grantResults){
        activity.runOnUiThread(() -> {
            if(isActivityDead(activity)) return;

            if(requestCode == STORAGE_PERM_REQUEST_CODE){
                boolean granted = grantResults.length > 0;
                for(int res : grantResults){
                    if(res != PackageManager.PERMISSION_GRANTED) granted = false;
                }

                if(granted){
                    promptChooseFolder(activity, true, folder -> {
                        if(pendingPermissionCallback != null){
                            pendingPermissionCallback.run();
                            pendingPermissionCallback = null;
                        }
                    });
                }else{
                    new AlertDialog.Builder(activity)
                        .setTitle("Permission Not Granted")
                        .setMessage("Storage permission was denied. Would you like to try again or use the default folder?")
                        .setCancelable(false)
                        .setPositiveButton("Try Again", (dialog, which) -> requestStoragePermission(activity))
                        .setNegativeButton("Use Default", (dialog, which) -> {
                            setDataDir(activity, getDefaultDataDir(activity).getAbsolutePath());
                            if(pendingPermissionCallback != null){
                                pendingPermissionCallback.run();
                                pendingPermissionCallback = null;
                            }
                        })
                        .show();
                }
            }
        });
    }

    public static String getPathFromTreeUri(Uri uri){
        if(uri == null) return null;
        try{
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if(docId != null){
                String[] parts = docId.split(":", 2);
                String type = parts[0];
                String relPath = parts.length > 1 ? parts[1] : "";
                relPath = Uri.decode(relPath);

                if("primary".equalsIgnoreCase(type)){
                    File root = Environment.getExternalStorageDirectory();
                    return relPath.isEmpty() ? root.getAbsolutePath() : new File(root, relPath).getAbsolutePath();
                }else{
                    File ext = new File("/storage/" + type);
                    if(ext.exists()){
                        return relPath.isEmpty() ? ext.getAbsolutePath() : new File(ext, relPath).getAbsolutePath();
                    }
                    return new File("/storage/" + type, relPath).getAbsolutePath();
                }
            }
        }catch(Exception e){
            Log.err("Error extracting path from SAF URI: " + uri, e);
        }
        return null;
    }

    public static boolean hasExistingData(File dir){
        if(dir == null || !dir.exists()) return false;
        if(new File(dir, "settings.bin").exists()) return true;
        File saves = new File(dir, "saves");
        if(saves.exists() && saves.isDirectory()){
            String[] list = saves.list();
            return list != null && list.length > 0;
        }
        return false;
    }

    public static void copyDirectory(File src, File dest) throws IOException{
        if(src == null || !src.exists()) return;
        if(src.isDirectory()){
            if(!dest.exists() && !dest.mkdirs()){
                throw new IOException("Cannot create directory: " + dest);
            }
            File[] files = src.listFiles();
            if(files != null){
                for(File file : files){
                    if("cache".equals(file.getName()) || "tmp".equals(file.getName())) continue;
                    copyDirectory(file, new File(dest, file.getName()));
                }
            }
        }else{
            File parent = dest.getParentFile();
            if(parent != null && !parent.exists() && !parent.mkdirs()){
                throw new IOException("Cannot create parent directory: " + parent);
            }
            try(InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)){
                byte[] buf = new byte[8192];
                int len;
                while((len = in.read(buf)) > 0){
                    out.write(buf, 0, len);
                }
            }
        }
    }

    private static boolean isActivityDead(Activity activity){
        return activity == null || activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed());
    }
}
