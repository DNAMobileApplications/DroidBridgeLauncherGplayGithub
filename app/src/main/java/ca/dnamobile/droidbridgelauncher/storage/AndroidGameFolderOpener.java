/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.storage;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Opens folders requested by Minecraft/OptiFine from inside the running JVM.
 *
 * Desktop Minecraft normally uses java.awt.Desktop, LWJGL Sys.openURL(file://...),
 * or xdg-open to reveal resourcepacks/shaderpacks/texturepacks. Android has no
 * desktop shell, so those requests must be translated to DocumentsUI/SAF URIs.
 */
public final class AndroidGameFolderOpener {
    private static final String TAG = "GameFolderOpener";

    private AndroidGameFolderOpener() {
    }

    public static boolean openFromGame(@Nullable Context context, @Nullable String rawTarget) {
        if (context == null || isBlank(rawTarget)) return false;

        Context appContext = context.getApplicationContext();
        PathManager.initContextConstants(appContext);

        String targetText = normalizeTargetText(rawTarget);
        if (isBlank(targetText)) return false;

        File fileTarget = resolveFileTarget(targetText);
        if (fileTarget != null) {
            return openFolderTarget(appContext, fileTarget, targetText);
        }

        return openExternalUri(appContext, targetText);
    }

    private static boolean openFolderTarget(@NonNull Context context,
                                            @NonNull File requestedTarget,
                                            @NonNull String originalTarget) {
        File folder = requestedTarget;
        if (requestedTarget.exists() && requestedTarget.isFile()) {
            File parent = requestedTarget.getParentFile();
            if (parent != null) folder = parent;
        }

        if (!folder.exists() && shouldCreateRequestedFolder(folder)) {
            boolean created = false;
            try {
                created = folder.mkdirs();
            } catch (Throwable throwable) {
                Logging.i(TAG, "mkdirs failed for in-game folder request "
                        + folder.getAbsolutePath() + ": " + readableError(throwable));
            }
            Logging.i(TAG, "mkdirs for in-game folder request=" + created
                    + ", target=" + folder.getAbsolutePath());
        }

        if (!folder.exists()) {
            File parent = folder.getParentFile();
            if (parent != null && parent.exists()) folder = parent;
        }

        try {
            folder = folder.getCanonicalFile();
        } catch (IOException ignored) {
            folder = folder.getAbsoluteFile();
        }

        Logging.i(TAG, "Opening in-game folder request: original=" + originalTarget
                + ", resolved=" + folder.getAbsolutePath()
                + ", exists=" + folder.exists()
                + ", directory=" + folder.isDirectory());

        Uri folderUri = buildInitialUri(context, folder);
        if (folderUri == null) {
            Logging.i(TAG, "No DocumentsUI URI for in-game folder request: " + folder.getAbsolutePath());
            return openExternalUri(context, Uri.fromFile(folder).toString());
        }

        return openFolderViewAt(context, folderUri, folder);
    }

    @Nullable
    private static Uri buildInitialUri(@NonNull Context context, @NonNull File target) {
        Uri selectedStorageUri = buildSelectedStorageInitialUri(context, target);
        if (selectedStorageUri != null) return selectedStorageUri;

        try {
            File root = DroidBridgeDocumentsProvider.getRootDirectoryForContext(context).getCanonicalFile();
            if (!isSameOrChild(root, target)) return null;
            Uri treeUri = DroidBridgeDocumentsProvider.buildTreeUriForFile(context, target);
            if (treeUri != null) return treeUri;
            return DroidBridgeDocumentsProvider.buildDocumentUriForFile(context, target);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Launcher DocumentsProvider mapping failed for "
                    + target.getAbsolutePath() + ": " + readableError(throwable));
            return null;
        }
    }

    @Nullable
    private static Uri buildSelectedStorageInitialUri(@NonNull Context context, @NonNull File target) {
        ArrayList<StorageLocation> locations = new ArrayList<>();
        try {
            StorageLocation selected = StorageLocationStore.getSelectedLocation(context);
            locations.add(selected);
        } catch (Throwable ignored) {
        }

        try {
            for (StorageLocation location : StorageLocationStore.getLocations(context)) {
                boolean alreadyAdded = false;
                for (StorageLocation existing : locations) {
                    if (existing.getId().equals(location.getId())) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) locations.add(location);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read storage locations for in-game open request: "
                    + readableError(throwable));
        }

        for (StorageLocation location : locations) {
            Uri mapped = buildInitialUriForStorageLocation(context, location, target);
            if (mapped != null) return mapped;
        }
        return null;
    }

    @Nullable
    private static Uri buildInitialUriForStorageLocation(@NonNull Context context,
                                                        @NonNull StorageLocation location,
                                                        @NonNull File target) {
        if (location.isDefaultLocation()) return null;
        String uriString = location.getUriString();
        if (isBlank(uriString)) return null;

        try {
            Uri treeUri = Uri.parse(uriString.trim());
            Uri mapped = buildInitialUriForLocationRoot(context, treeUri, location.getLauncherHomePath(), target);
            if (mapped != null) return mapped;
            return buildInitialUriForLocationRoot(context, treeUri, location.getMinecraftHomePath(), target);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to map storage location " + location.getId()
                    + " for in-game open request: " + readableError(throwable));
            return null;
        }
    }

    @Nullable
    private static Uri buildInitialUriForLocationRoot(@NonNull Context context,
                                                     @NonNull Uri treeUri,
                                                     @Nullable String localRootPath,
                                                     @NonNull File target) throws IOException {
        if (isBlank(localRootPath)) return null;

        File localRoot = new File(localRootPath.trim()).getCanonicalFile();
        if (!isSameOrChild(localRoot, target)) return null;

        String relativePath = getRelativePathBetweenFiles(localRoot, target);
        Uri resolved = SafMinecraftMirror.findRelativePathInTree(context, treeUri, relativePath);
        if (resolved != null) return resolved;

        return buildTreeDocumentUriByAppendingPath(treeUri, relativePath);
    }

    @Nullable
    private static Uri buildTreeDocumentUriByAppendingPath(@NonNull Uri treeUri,
                                                          @Nullable String relativePath) {
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (isBlank(treeDocumentId)) return null;

            String documentId = appendDocumentIdPath(treeDocumentId, relativePath);
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to append DocumentsUI path for in-game open request: "
                    + readableError(throwable));
            return null;
        }
    }

    private static boolean openFolderViewAt(@NonNull Context context,
                                            @NonNull Uri folderUri,
                                            @NonNull File folder) {
        ArrayList<Intent> attempts = new ArrayList<>();

        addDocumentsUiBrowseAttempt(context, attempts, folderUri, null, null);
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.google.android.documentsui", "com.android.documentsui.files.FilesActivity");
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.google.android.documentsui", "com.google.android.documentsui.files.FilesActivity");
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.google.android.documentsui", "com.android.documentsui.FilesActivity");
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.google.android.documentsui", "com.google.android.documentsui.FilesActivity");
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.android.documentsui", "com.android.documentsui.files.FilesActivity");
        addDocumentsUiBrowseAttempt(context, attempts, folderUri,
                "com.android.documentsui", "com.android.documentsui.FilesActivity");

        for (Intent intent : attempts) {
            try {
                context.startActivity(intent);
                Logging.i(TAG, "Opened in-game folder request: " + folder.getAbsolutePath()
                        + " -> " + folderUri);
                return true;
            } catch (Throwable throwable) {
                Logging.i(TAG, "DocumentsUI folder attempt failed for "
                        + folder.getAbsolutePath() + ": " + readableError(throwable)
                        + ", component=" + intent.getComponent()
                        + ", package=" + intent.getPackage());
            }
        }

        return openFolderTreePickerFallback(context, folderUri, folder);
    }

    private static void addDocumentsUiBrowseAttempt(@NonNull Context context,
                                                    @NonNull ArrayList<Intent> attempts,
                                                    @NonNull Uri folderUri,
                                                    @Nullable String packageName,
                                                    @Nullable String className) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);

        try {
            intent.setClipData(ClipData.newUri(context.getContentResolver(), "Minecraft Folder", folderUri));
        } catch (Throwable ignored) {
        }

        if (!isBlank(packageName) && !isBlank(className)) {
            intent.setComponent(new ComponentName(packageName, className));
        } else if (!isBlank(packageName)) {
            intent.setPackage(packageName);
        }

        attempts.add(intent);
    }

    private static boolean openFolderTreePickerFallback(@NonNull Context context,
                                                        @NonNull Uri folderUri,
                                                        @NonNull File folder) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);

        try {
            intent.setClipData(ClipData.newUri(context.getContentResolver(), "Minecraft Folder", folderUri));
        } catch (Throwable ignored) {
        }

        try {
            context.startActivity(intent);
            Logging.i(TAG, "Opened in-game folder request through tree picker fallback: "
                    + folder.getAbsolutePath() + " -> " + folderUri);
            return true;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Tree picker fallback failed for in-game folder request "
                    + folder.getAbsolutePath() + ": " + readableError(throwable));
            return false;
        }
    }

    private static boolean openExternalUri(@NonNull Context context, @NonNull String targetText) {
        try {
            Uri uri = Uri.parse(targetText);
            if (uri.getScheme() == null && targetText.startsWith("/")) {
                uri = Uri.fromFile(new File(targetText));
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logging.i(TAG, "Opened in-game external URI: " + targetText);
            return true;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to open in-game external URI " + targetText
                    + ": " + readableError(throwable));
            return false;
        }
    }

    @Nullable
    private static File resolveFileTarget(@NonNull String targetText) {
        try {
            URI uri = new URI(targetText);
            String scheme = uri.getScheme();
            if (scheme == null) {
                if (targetText.startsWith("/")) return new File(targetText);
                return null;
            }
            if ("file".equalsIgnoreCase(scheme)) {
                return new File(uri);
            }
            return null;
        } catch (Throwable ignored) {
            if (targetText.startsWith("file:")) {
                try {
                    String raw = targetText.substring("file:".length());
                    while (raw.startsWith("//")) raw = raw.substring(1);
                    return new File(URLDecoder.decode(raw, "UTF-8"));
                } catch (Throwable ignoredAgain) {
                    return null;
                }
            }
            if (targetText.startsWith("/")) return new File(targetText);
            return null;
        }
    }

    @NonNull
    private static String normalizeTargetText(@NonNull String rawTarget) {
        String text = rawTarget.replace('\0', ' ').trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).trim();
        }

        String[] parts = text.split("\\s+");
        if (parts.length > 1 && isOpenCommand(parts[0])) {
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].startsWith("-")) return stripQuotes(parts[i]);
            }
        }
        return stripQuotes(text);
    }

    @NonNull
    private static String stripQuotes(@NonNull String value) {
        String out = value.trim();
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 1) {
            return out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    private static boolean isOpenCommand(@NonNull String value) {
        String lower = new File(value).getName().toLowerCase(Locale.ROOT);
        return "xdg-open".equals(lower)
                || "open".equals(lower)
                || "gio".equals(lower)
                || "kioclient".equals(lower)
                || "kde-open".equals(lower)
                || "gnome-open".equals(lower);
    }

    private static boolean shouldCreateRequestedFolder(@NonNull File folder) {
        String name = folder.getName().toLowerCase(Locale.ROOT);
        return "resourcepacks".equals(name)
                || "shaderpacks".equals(name)
                || "texturepacks".equals(name)
                || "mods".equals(name)
                || "saves".equals(name)
                || "datapacks".equals(name)
                || "screenshots".equals(name);
    }

    @NonNull
    private static String appendDocumentIdPath(@NonNull String rootDocumentId, @Nullable String relativePath) {
        if (isBlank(relativePath)) return rootDocumentId;
        String cleanRoot = rootDocumentId;
        while (cleanRoot.endsWith("/") && cleanRoot.length() > 1) {
            cleanRoot = cleanRoot.substring(0, cleanRoot.length() - 1);
        }

        String cleanRelative = relativePath.trim().replace('\\', '/');
        while (cleanRelative.startsWith("/")) cleanRelative = cleanRelative.substring(1);
        while (cleanRelative.endsWith("/") && cleanRelative.length() > 1) {
            cleanRelative = cleanRelative.substring(0, cleanRelative.length() - 1);
        }
        if (TextUtils.isEmpty(cleanRelative)) return cleanRoot;
        return cleanRoot + "/" + cleanRelative;
    }

    @NonNull
    private static String getRelativePathBetweenFiles(@NonNull File root, @NonNull File child) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalChild = child.getCanonicalFile();
        if (canonicalRoot.equals(canonicalChild)) return "";

        String rootPath = canonicalRoot.getPath();
        String childPath = canonicalChild.getPath();
        if (!childPath.startsWith(rootPath + File.separator)) {
            throw new FileNotFoundException(child.getAbsolutePath() + " is outside " + root.getAbsolutePath());
        }
        return childPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static boolean isSameOrChild(@NonNull File parent, @NonNull File child) {
        try {
            File canonicalParent = parent.getCanonicalFile();
            File canonicalChild = child.getCanonicalFile();
            String parentPath = canonicalParent.getPath();
            String childPath = canonicalChild.getPath();
            return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private static String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (TextUtils.isEmpty(message) ? "" : ": " + message);
    }
}
