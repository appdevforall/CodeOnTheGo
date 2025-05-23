/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.provider;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import androidx.annotation.NonNull;
import com.itsaky.androidide.resources.R;
import com.itsaky.androidide.utils.Environment;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A document provider for the Storage Access Framework which exposes the files in the $HOME/
 * directory to other apps.
 *
 * <p>Note that this replaces providing an activity matching the ACTION_GET_CONTENT intent:
 *
 * <p>"A document provider and ACTION_GET_CONTENT should be considered mutually exclusive. If you
 * support both of them simultaneously, your app will appear twice in the system picker UI, offering
 * two different ways of accessing your stored data. This would be confusing for users." -
 * http://developer.android.com/guide/topics/providers/document-provider.html#43
 *
 * @see <a
 * href="https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java">TermuxDocumentsProvider</a>
 */
public class IDEDocumentsProvider extends DocumentsProvider {

  private static final String ALL_MIME_TYPES = "*/*";
  private static final Logger LOG = LoggerFactory.getLogger(IDEDocumentsProvider.class);
  private static final File BASE_DIR = getBaseDir();
  // The default columns to return information about a root if no specific
  // columns are requested in a query.
  private static final String[] DEFAULT_ROOT_PROJECTION =
      new String[]{
          Root.COLUMN_ROOT_ID,
          Root.COLUMN_MIME_TYPES,
          Root.COLUMN_FLAGS,
          Root.COLUMN_ICON,
          Root.COLUMN_TITLE,
          Root.COLUMN_SUMMARY,
          Root.COLUMN_DOCUMENT_ID,
          Root.COLUMN_AVAILABLE_BYTES
      };
  // The default columns to return information about a document if no specific
  // columns are requested in a query.
  private static final String[] DEFAULT_DOCUMENT_PROJECTION =
      new String[]{
          Document.COLUMN_DOCUMENT_ID,
          Document.COLUMN_MIME_TYPE,
          Document.COLUMN_DISPLAY_NAME,
          Document.COLUMN_LAST_MODIFIED,
          Document.COLUMN_FLAGS,
          Document.COLUMN_SIZE
      };

  @NonNull
  private static File getBaseDir() {
    if (Environment.HOME != null) {
      return Environment.HOME;
    }

    return new File(Environment.DEFAULT_HOME);
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public boolean isChildDocument(String parentDocumentId, String documentId) {
    return documentId.startsWith(parentDocumentId);
  }

  @Override
  public String createDocument(String parentDocumentId, String mimeType, String displayName)
      throws FileNotFoundException {
    File newFile = new File(parentDocumentId, displayName);
    int noConflictId = 2;
    while (newFile.exists()) {
      newFile = new File(parentDocumentId, displayName + " (" + noConflictId++ + ")");
    }
    try {
      boolean succeeded;
      if (Document.MIME_TYPE_DIR.equals(mimeType)) {
        succeeded = newFile.mkdir();
      } else {
        succeeded = newFile.createNewFile();
      }
      if (!succeeded) {
        throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
      }
    } catch (IOException e) {
      throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
    }
    return newFile.getPath();
  }

  @Override
  public void deleteDocument(String documentId) throws FileNotFoundException {
    File file = getFileForDocId(documentId);
    if (!file.delete()) {
      throw new FileNotFoundException("Failed to delete document with id " + documentId);
    }
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    final MatrixCursor result =
        new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
    final String applicationName = getContext().getString(R.string.app_name);

    final MatrixCursor.RowBuilder row = result.newRow();
    LOG.debug("queryRoots() before all add");
    row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(BASE_DIR));
    LOG.debug("queryRoots() before all add, 1");
    row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(BASE_DIR));
    LOG.debug("queryRoots() before all add, 2");
    row.add(Root.COLUMN_SUMMARY, null);
    LOG.debug("queryRoots() before all add, 3");
    row.add(
        Root.COLUMN_FLAGS,
        Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
    LOG.debug("queryRoots() before all add, 4");
    row.add(Root.COLUMN_TITLE, applicationName);
    LOG.debug("queryRoots() before all add, 5");
    row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
    LOG.debug("queryRoots() before all add, 6");
    row.add(Root.COLUMN_AVAILABLE_BYTES, BASE_DIR.getFreeSpace());
    LOG.debug("queryRoots() before all add, 7");
    row.add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground);
    LOG.debug("queryRoots() before all add");
    return result;
  }

  /**
   * Get the document id given a file. This document id must be consistent across time as other
   * applications may save the ID and use it to reference documents later.
   *
   * <p>The reverse of @{link #getFileForDocId}.
   */
  private static String getDocIdForFile(File file) {
    return file.getAbsolutePath();
  }

  @Override
  public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
    final MatrixCursor result =
        new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
    includeFile(result, documentId, null);
    return result;
  }

  @Override
  public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
      throws FileNotFoundException {
    final MatrixCursor result =
        new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
    final File parent = getFileForDocId(parentDocumentId);
    for (File file : parent.listFiles()) {
      includeFile(result, null, file);
    }
    return result;
  }

  @Override
  public Cursor querySearchDocuments(String rootId, String query, String[] projection)
      throws FileNotFoundException {
    final MatrixCursor result =
        new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
    final File parent = getFileForDocId(rootId);

    // This example implementation searches file names for the query and doesn't rank search
    // results, so we can stop as soon as we find a sufficient number of matches.  Other
    // implementations might rank results and use other data about files, rather than the file
    // name, to produce a match.
    final LinkedList<File> pending = new LinkedList<>();
    pending.add(parent);

    final int MAX_SEARCH_RESULTS = 50;
    while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
      final File file = pending.removeFirst();
      // Avoid directories outside the $HOME directory linked with symlinks (to avoid e.g.
      // search
      // through the whole SD card).
      boolean isInsideHome;
      try {
        isInsideHome = file.getCanonicalPath().startsWith(Environment.HOME.getAbsolutePath());
      } catch (IOException e) {
        isInsideHome = true;
      }
      if (isInsideHome) {
        if (file.isDirectory()) {
          Collections.addAll(pending, file.listFiles());
        } else {
          if (file.getName().toLowerCase(Locale.ROOT).contains(query)) {
            includeFile(result, null, file);
          }
        }
      }
    }

    return result;
  }

  @Override
  public String getDocumentType(String documentId) throws FileNotFoundException {
    File file = getFileForDocId(documentId);
    return getMimeType(file);
  }

  @Override
  public ParcelFileDescriptor openDocument(
      final String documentId, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    final File file = getFileForDocId(documentId);
    final int accessMode = ParcelFileDescriptor.parseMode(mode);
    return ParcelFileDescriptor.open(file, accessMode);
  }

  @Override
  public AssetFileDescriptor openDocumentThumbnail(
      String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
    final File file = getFileForDocId(documentId);
    final ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    return new AssetFileDescriptor(pfd, 0, file.length());
  }

  /**
   * Add a representation of a file to a cursor.
   *
   * @param result the cursor to modify
   * @param docId  the document ID representing the desired file (may be null if given file)
   * @param file   the File object representing the desired file (may be null if given docID)
   */
  private void includeFile(MatrixCursor result, String docId, File file)
      throws FileNotFoundException {
    if (docId == null) {
      docId = getDocIdForFile(file);
    } else {
      file = getFileForDocId(docId);
    }

    int flags = 0;
    if (file.isDirectory()) {
      if (file.canWrite()) {
        flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
      }
    } else if (file.canWrite()) {
      flags |= Document.FLAG_SUPPORTS_WRITE;
    }
    if (file.getParentFile().canWrite()) {
      flags |= Document.FLAG_SUPPORTS_DELETE;
    }

    final String displayName = file.getName();
    final String mimeType = getMimeType(file);
    if (mimeType.startsWith("image/")) {
      flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
    }

    final MatrixCursor.RowBuilder row = result.newRow();
    row.add(Document.COLUMN_DOCUMENT_ID, docId);
    row.add(Document.COLUMN_DISPLAY_NAME, displayName);
    row.add(Document.COLUMN_SIZE, file.length());
    row.add(Document.COLUMN_MIME_TYPE, mimeType);
    row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    row.add(Document.COLUMN_FLAGS, flags);
    row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
  }

  /**
   * Get the file given a document id (the reverse of {@link #getDocIdForFile(File)}).
   */
  private static File getFileForDocId(String docId) throws FileNotFoundException {
    final File f = new File(docId);
    if (!f.exists()) {
      throw new FileNotFoundException(f.getAbsolutePath() + " not found");
    }
    return f;
  }

  private static String getMimeType(File file) {
    if (file.isDirectory()) {
      return Document.MIME_TYPE_DIR;
    } else {
      final String name = file.getName();
      final int lastDot = name.lastIndexOf('.');
      if (lastDot >= 0) {
        final String extension = name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mime != null) {
          return mime;
        }
      }
      return "application/octet-stream";
    }
  }
}
