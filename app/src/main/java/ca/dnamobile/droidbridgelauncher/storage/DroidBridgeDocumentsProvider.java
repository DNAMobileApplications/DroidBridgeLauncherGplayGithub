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

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Path;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public class DroidBridgeDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY_SUFFIX = ".documents";
    public static final String ROOT_ID = "javalauncher_root";
    public static final String ROOT_DOCUMENT_ID = "root";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null && TextUtils.isEmpty(PathManager.DIR_GAME_HOME)) {
            PathManager.initContextConstants(context);
        }
        return true;
    }

    @NonNull
    public static String getAuthority(@NonNull Context context) {
        String authority = context.getPackageName() + AUTHORITY_SUFFIX;
        return authority;
    }

    @NonNull
    public static Uri buildRootUri(@NonNull Context context) {
        return DocumentsContract.buildRootUri(getAuthority(context), ROOT_ID);
    }

    @Nullable
    public static Uri buildDocumentUriForFile(@NonNull Context context, @NonNull File file) {
        try {
            return DocumentsContract.buildDocumentUri(getAuthority(context), getDocumentIdForFile(context, file));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static Uri buildTreeUriForFile(@NonNull Context context, @NonNull File file) {
        try {
            String authority = getAuthority(context);
            String documentId = getDocumentIdForFile(context, file);
            Uri rootTreeUri = DocumentsContract.buildTreeDocumentUri(authority, ROOT_DOCUMENT_ID);
            return DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, documentId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    public static File getRootDirectoryForContext(@NonNull Context context) throws FileNotFoundException {


        File launcherHome = PathManager.getDefaultLauncherHome(context);
        if (!launcherHome.exists() && !launcherHome.mkdirs()) {
            throw new FileNotFoundException("Unable to create launcher home: " + launcherHome.getAbsolutePath());
        }

        File minecraftHome = new File(launcherHome, ".minecraft");
        if (!minecraftHome.exists() && !minecraftHome.mkdirs()) {
            throw new FileNotFoundException("Unable to create .minecraft folder: " + minecraftHome.getAbsolutePath());
        }
        return minecraftHome;
    }

    @NonNull
    public static String getDocumentIdForFile(@NonNull Context context, @NonNull File file) throws FileNotFoundException {
        try {
            File root = getRootDirectoryForContext(context).getCanonicalFile();
            File target = file.getCanonicalFile();

            if (root.equals(target)) {
                return ROOT_DOCUMENT_ID;
            }

            String rootPath = root.getPath();
            String targetPath = target.getPath();
            if (targetPath.startsWith(rootPath + File.separator)) {
                String documentId = targetPath.substring(rootPath.length() + 1);
                return documentId;
            }
            throw new FileNotFoundException("File is outside launcher document root: " + file.getAbsolutePath());
        } catch (IOException e) {
            FileNotFoundException wrapped = new FileNotFoundException(e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    @Override
    public Cursor queryRoots(@Nullable String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        File root = getRootDirectory();

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        row.add(Root.COLUMN_TITLE, getLauncherTitle());
        row.add(Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE |
                        Root.FLAG_SUPPORTS_RECENTS |
                        Root.FLAG_SUPPORTS_SEARCH |
                        Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(Root.COLUMN_AVAILABLE_BYTES, root.getFreeSpace());
        return result;
    }

    @Override
    public Cursor queryDocument(@NonNull String documentId, @Nullable String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        File file = getFileForDocumentId(documentId);
        includeFile(result, documentId, file);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(@NonNull String parentDocumentId,
                                      @Nullable String[] projection,
                                      @Nullable String sortOrder) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        File parent = getFileForDocumentId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files == null) return result;

        for (File file : files) {
            String childDocumentId = getDocumentIdForFile(file);
            includeFile(result, childDocumentId, file);
        }
        return result;
    }

    @Override
    public Path findDocumentPath(@Nullable String parentDocumentId,
                                 @NonNull String childDocumentId) throws FileNotFoundException {
        String childId = normalizeDocumentId(childDocumentId);



        boolean hasParentDocumentId = !TextUtils.isEmpty(parentDocumentId);
        String parentId = hasParentDocumentId
                ? normalizeDocumentId(parentDocumentId)
                : ROOT_DOCUMENT_ID;

        File child = getFileForDocumentId(childId);
        if (!child.exists()) {
            throw new FileNotFoundException("Document does not exist: " + childId);
        }

        if (hasParentDocumentId && !ROOT_DOCUMENT_ID.equals(parentId)) {
            File parent = getFileForDocumentId(parentId);
            if (!isSameOrChild(parent, child)) {
                throw new FileNotFoundException("Document is outside requested parent: " + childId);
            }
        }

        ArrayList<String> path = hasParentDocumentId
                ? buildDocumentIdPathFromParent(parentId, childId)
                : buildDocumentIdPathFromRoot(childId);



        String returnedRootId = hasParentDocumentId ? null : ROOT_ID;
        return new Path(returnedRootId, path);
    }

    @Override
    public Cursor querySearchDocuments(@NonNull String rootId,
                                       @NonNull String query,
                                       @Nullable String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        if (!ROOT_ID.equals(rootId)) return result;

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        searchRecursively(result, getRootDirectory(), lowerQuery, 0);
        return result;
    }

    @Override
    public boolean isChildDocument(@NonNull String parentDocumentId, @NonNull String documentId) {
        try {
            File parent = getFileForDocumentId(parentDocumentId).getCanonicalFile();
            File child = getFileForDocumentId(documentId).getCanonicalFile();
            String parentPath = parent.getPath();
            String childPath = child.getPath();
            return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String createDocument(@NonNull String parentDocumentId,
                                 @NonNull String mimeType,
                                 @NonNull String displayName) throws FileNotFoundException {
        File parent = getFileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Parent is not a directory: " + parentDocumentId);
        }

        File target = buildUniqueFile(parent, displayName, DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType));
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!target.mkdirs() && !target.isDirectory()) {
                    throw new IOException("Unable to create directory");
                }
            } else {
                File parentFile = target.getParentFile();
                if (parentFile != null && !parentFile.exists()) parentFile.mkdirs();
                if (!target.createNewFile()) {
                    throw new IOException("Unable to create file");
                }
            }
            return getDocumentIdForFile(target);
        } catch (IOException e) {
            FileNotFoundException wrapped = new FileNotFoundException(e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    @Override
    public void deleteDocument(@NonNull String documentId) throws FileNotFoundException {
        File target = getFileForDocumentId(documentId);
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            throw new FileNotFoundException("Refusing to delete launcher root");
        }
        deleteRecursively(target);
    }

    @Override
    public String renameDocument(@NonNull String documentId, @NonNull String displayName) throws FileNotFoundException {
        File target = getFileForDocumentId(documentId);
        File parent = target.getParentFile();
        if (parent == null) throw new FileNotFoundException("No parent for: " + documentId);

        File renamed = buildUniqueFile(parent, displayName, target.isDirectory());
        if (!target.renameTo(renamed)) {
            throw new FileNotFoundException("Unable to rename: " + documentId);
        }
        return getDocumentIdForFile(renamed);
    }

    @Override
    public ParcelFileDescriptor openDocument(@NonNull String documentId,
                                             @NonNull String mode,
                                             @Nullable CancellationSignal signal) throws FileNotFoundException {
        File file = getFileForDocumentId(documentId);
        if (file.isDirectory()) {
            throw new FileNotFoundException("Cannot open directory: " + documentId);
        }
        return ParcelFileDescriptor.open(file, parseMode(mode));
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(@NonNull String documentId,
                                                     @NonNull Point sizeHint,
                                                     @Nullable CancellationSignal signal) throws FileNotFoundException {
        return super.openDocumentThumbnail(documentId, sizeHint, signal);
    }

    private void includeFile(@NonNull MatrixCursor result,
                             @NonNull String documentId,
                             @NonNull File file) {
        int flags = 0;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        if (file.canWrite() && !ROOT_DOCUMENT_ID.equals(documentId)) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        if (file.isFile() && file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, ROOT_DOCUMENT_ID.equals(documentId) ? getLauncherTitle() : file.getName());
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(file));
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isFile() ? file.length() : 0);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private void searchRecursively(@NonNull MatrixCursor result,
                                   @NonNull File directory,
                                   @NonNull String lowerQuery,
                                   int depth) {
        if (depth > 8 || result.getCount() >= 50) return;

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                includeFile(result, getDocumentIdForFile(file), file);
            }
            if (file.isDirectory()) {
                searchRecursively(result, file, lowerQuery, depth + 1);
            }
            if (result.getCount() >= 50) return;
        }
    }

    @NonNull
    private ArrayList<String> buildDocumentIdPathFromRoot(@NonNull String documentId) {
        ArrayList<String> path = new ArrayList<>();
        path.add(ROOT_DOCUMENT_ID);

        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            return path;
        }

        String clean = normalizeDocumentId(documentId);
        if (ROOT_DOCUMENT_ID.equals(clean)) {
            return path;
        }

        String[] parts = clean.replace('\\', '/').split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            if (current.length() > 0) current.append('/');
            current.append(part);
            path.add(current.toString());
        }
        return path;
    }

    @NonNull
    private ArrayList<String> buildDocumentIdPathFromParent(
            @NonNull String parentDocumentId,
            @NonNull String childDocumentId
    ) {
        ArrayList<String> fullPath = buildDocumentIdPathFromRoot(childDocumentId);
        String parentId = normalizeDocumentId(parentDocumentId);

        int parentIndex = fullPath.indexOf(parentId);
        if (parentIndex < 0) {
            ArrayList<String> fallback = new ArrayList<>();
            fallback.add(parentId);
            String childId = normalizeDocumentId(childDocumentId);
            if (!parentId.equals(childId)) fallback.add(childId);
            return fallback;
        }

        return new ArrayList<>(fullPath.subList(parentIndex, fullPath.size()));
    }

    @NonNull
    private String normalizeDocumentId(@Nullable String documentId) {
        if (TextUtils.isEmpty(documentId)) return ROOT_DOCUMENT_ID;
        String clean = documentId.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/") && clean.length() > 1) clean = clean.substring(0, clean.length() - 1);
        return TextUtils.isEmpty(clean) ? ROOT_DOCUMENT_ID : clean;
    }

    private boolean isSameOrChild(@NonNull File parent, @NonNull File child) {
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

    @NonNull
    private File getRootDirectory() throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Provider context is null");
        return getRootDirectoryForContext(context);
    }

    @NonNull
    private File getFileForDocumentId(@NonNull String documentId) throws FileNotFoundException {
        File root = getRootDirectory();
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            return root;
        }

        try {
            File target = new File(root, documentId).getCanonicalFile();
            File canonicalRoot = root.getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String targetPath = target.getPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("Invalid document path: " + documentId);
            }
            return target;
        } catch (IOException e) {
            FileNotFoundException wrapped = new FileNotFoundException(e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    @NonNull
    private String getDocumentIdForFile(@NonNull File file) {
        Context context = getContext();
        if (context != null) {
            try {
                return getDocumentIdForFile(context, file);
            } catch (FileNotFoundException ignored) {
            }
        }
        return file.getName();
    }

    @NonNull
    private String getMimeType(@NonNull File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;

        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(mimeType)) return mimeType;
        }
        return "application/octet-stream";
    }

    private int parseMode(@NonNull String mode) {
        boolean write = mode.contains("w");
        boolean truncate = mode.contains("t");
        boolean append = mode.contains("a");

        if (write) {
            int accessMode = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
            if (truncate) accessMode |= ParcelFileDescriptor.MODE_TRUNCATE;
            if (append) accessMode |= ParcelFileDescriptor.MODE_APPEND;
            return accessMode;
        }
        return ParcelFileDescriptor.MODE_READ_ONLY;
    }

    @NonNull
    private File buildUniqueFile(@NonNull File parent, @NonNull String displayName, boolean directory) {
        File target = new File(parent, displayName);
        if (!target.exists()) return target;

        String baseName = displayName;
        String extension = "";
        if (!directory) {
            int dot = displayName.lastIndexOf('.');
            if (dot > 0) {
                baseName = displayName.substring(0, dot);
                extension = displayName.substring(dot);
            }
        }

        int index = 1;
        do {
            target = new File(parent, baseName + " (" + index + ")" + extension);
            index++;
        } while (target.exists());
        return target;
    }

    private void deleteRecursively(@NonNull File file) throws FileNotFoundException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new FileNotFoundException("Unable to delete: " + file.getAbsolutePath());
        }
    }

    @NonNull
    private String getLauncherTitle() {
        Context context = getContext();
        if (context == null) return "DroidBridge";
        try {
            return context.getString(R.string.app_name);
        } catch (Throwable ignored) {
            return "DroidBridge";
        }
    }

    @NonNull
    private String[] resolveRootProjection(@Nullable String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    @NonNull
    private String[] resolveDocumentProjection(@Nullable String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }
}
