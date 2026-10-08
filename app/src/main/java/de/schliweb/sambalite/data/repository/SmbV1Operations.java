/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** SMBv1 (CIFS/NT1) operations backed by jcifs-ng. Used when legacySmbV1=true on the connection. */
public class SmbV1Operations {

  private static final String TAG = "SmbV1Operations";
  private static final int COPY_BUFFER_SIZE = 256 * 1024;

  private volatile boolean downloadCancelled = false;
  private volatile boolean uploadCancelled = false;

  public void cancelDownload() {
    downloadCancelled = true;
  }

  public void cancelUpload() {
    uploadCancelled = true;
  }

  // ── Context / URL helpers ──────────────────────────────────────────────────

  private CIFSContext buildContext(SmbConnection connection) throws CIFSException {
    Properties props = new Properties();
    props.setProperty("jcifs.smb.client.minVersion", "SMB1");
    props.setProperty("jcifs.smb.client.maxVersion", "SMB1");
    props.setProperty("jcifs.smb.client.signingPreferred", "false");
    props.setProperty("jcifs.smb.client.signingEnforced", "false");
    props.setProperty("jcifs.smb.client.connTimeout", "30000");
    props.setProperty("jcifs.smb.client.responseTimeout", "60000");
    CIFSContext base = new BaseContext(new PropertyConfiguration(props));

    String username = connection.getUsername() != null ? connection.getUsername() : "";
    String password = connection.getPassword() != null ? connection.getPassword() : "";
    String domain = connection.getDomain() != null ? connection.getDomain() : "";

    if (username.isEmpty() && password.isEmpty()) {
      return base.withAnonymousCredentials();
    }
    return base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password));
  }

  /** Builds a smb:// URL with a trailing slash (required for directories by jcifs-ng). */
  private String buildDirUrl(SmbConnection connection, String shareName, String path) {
    StringBuilder sb = new StringBuilder("smb://");
    sb.append(connection.getServer());
    int port = connection.getPort();
    if (port != 445) {
      sb.append(':').append(port);
    }
    sb.append('/').append(shareName).append('/');
    if (path != null && !path.isEmpty()) {
      String normalized = path.replace('\\', '/');
      while (normalized.startsWith("/")) normalized = normalized.substring(1);
      sb.append(normalized);
      if (!normalized.endsWith("/")) sb.append('/');
    }
    return sb.toString();
  }

  /** Builds a smb:// URL for a file (no trailing slash). */
  private String buildFileUrl(SmbConnection connection, String shareName, String path) {
    String url = buildDirUrl(connection, shareName, path);
    if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
    return url;
  }

  private String getShareName(String sharePath) {
    if (sharePath == null || sharePath.isEmpty()) return "";
    String p = sharePath;
    while (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
    int idx = p.indexOf('/');
    if (idx == -1) idx = p.indexOf('\\');
    return idx == -1 ? p : p.substring(0, idx);
  }

  // ── Core SMB operations ────────────────────────────────────────────────────

  @NonNull
  public List<String> listShares(@NonNull SmbConnection connection) throws Exception {
    CIFSContext ctx = buildContext(connection);
    StringBuilder sb = new StringBuilder("smb://");
    sb.append(connection.getServer());
    int port = connection.getPort();
    if (port != 445) sb.append(':').append(port);
    sb.append('/');
    SmbFile host = new SmbFile(sb.toString(), ctx);
    List<String> shares = new ArrayList<>();
    for (SmbFile f : host.listFiles()) {
      String name = f.getName();
      if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
      shares.add(name);
    }
    return shares;
  }

  public boolean testConnection(@NonNull SmbConnection connection) throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    String url = buildDirUrl(connection, shareName, "");
    SmbFile share = new SmbFile(url, ctx);
    return share.exists();
  }

  @NonNull
  public List<SmbFileItem> listFiles(@NonNull SmbConnection connection, @NonNull String path)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    String url = buildDirUrl(connection, shareName, path);
    SmbFile dir = new SmbFile(url, ctx);
    List<SmbFileItem> result = new ArrayList<>();
    SmbFile[] files = dir.listFiles();
    if (files == null) return result;
    for (SmbFile f : files) {
      String rawName = f.getName();
      boolean isDir = rawName.endsWith("/");
      String name = isDir ? rawName.substring(0, rawName.length() - 1) : rawName;
      String childPath = path.isEmpty() ? name : path + "/" + name;
      SmbFileItem.Type type = isDir ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
      long size = isDir ? 0 : f.length();
      Date lastModified = new Date(f.lastModified());
      result.add(new SmbFileItem(name, childPath, type, size, lastModified));
    }
    return result;
  }

  @Nullable
  public SmbFileItem getFileItem(@NonNull SmbConnection connection, @NonNull String path)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    // Try as directory first
    String dirUrl = buildDirUrl(connection, shareName, path);
    SmbFile asDir = new SmbFile(dirUrl, ctx);
    if (asDir.exists()) {
      String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
      return new SmbFileItem(
          name, path, SmbFileItem.Type.DIRECTORY, 0, new Date(asDir.lastModified()));
    }
    String fileUrl = buildFileUrl(connection, shareName, path);
    SmbFile asFile = new SmbFile(fileUrl, ctx);
    if (asFile.exists()) {
      String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
      return new SmbFileItem(
          name, path, SmbFileItem.Type.FILE, asFile.length(), new Date(asFile.lastModified()));
    }
    return null;
  }

  public boolean fileExists(@NonNull SmbConnection connection, @NonNull String path)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile f = new SmbFile(buildFileUrl(connection, shareName, path), ctx);
    return f.exists() && f.isFile();
  }

  public boolean folderExists(@NonNull SmbConnection connection, @NonNull String path)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile f = new SmbFile(buildDirUrl(connection, shareName, path), ctx);
    return f.exists() && f.isDirectory();
  }

  public long getRemoteFileSize(@NonNull SmbConnection connection, @NonNull String path) {
    try {
      CIFSContext ctx = buildContext(connection);
      String shareName = getShareName(connection.getShare());
      SmbFile f = new SmbFile(buildFileUrl(connection, shareName, path), ctx);
      return f.exists() ? f.length() : -1;
    } catch (Exception e) {
      LogUtils.e(TAG, "getRemoteFileSize failed: " + e.getMessage());
      return -1;
    }
  }

  public void deleteFile(@NonNull SmbConnection connection, @NonNull String path) throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    // Try file URL first, then dir URL (for directories)
    SmbFile f = new SmbFile(buildFileUrl(connection, shareName, path), ctx);
    if (!f.exists()) {
      f = new SmbFile(buildDirUrl(connection, shareName, path), ctx);
    }
    if (!f.exists()) throw new IOException("Not found: " + path);
    f.delete();
  }

  @NonNull
  public List<String> deleteFiles(@NonNull SmbConnection connection, @NonNull List<String> paths)
      throws Exception {
    List<String> failed = new ArrayList<>();
    for (String path : paths) {
      try {
        deleteFile(connection, path);
      } catch (Exception e) {
        LogUtils.e(TAG, "deleteFiles failed for " + path + ": " + e.getMessage());
        failed.add(path);
      }
    }
    return failed;
  }

  public void renameFile(
      @NonNull SmbConnection connection, @NonNull String oldPath, @NonNull String newName)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    int lastSlash = Math.max(oldPath.lastIndexOf('/'), oldPath.lastIndexOf('\\'));
    String parentPath = lastSlash > 0 ? oldPath.substring(0, lastSlash) : "";
    String newPath = parentPath.isEmpty() ? newName : parentPath + "/" + newName;

    SmbFile src = new SmbFile(buildFileUrl(connection, shareName, oldPath), ctx);
    if (!src.exists()) {
      src = new SmbFile(buildDirUrl(connection, shareName, oldPath), ctx);
    }
    SmbFile dst = new SmbFile(buildFileUrl(connection, shareName, newPath), ctx);
    src.renameTo(dst);
  }

  public void createDirectory(
      @NonNull SmbConnection connection, @NonNull String path, @NonNull String name)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    String fullPath = path.isEmpty() ? name : path + "/" + name;
    SmbFile dir = new SmbFile(buildDirUrl(connection, shareName, fullPath), ctx);
    dir.mkdir();
  }

  // ── Download / Upload ──────────────────────────────────────────────────────

  public void downloadFile(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull File localFile)
      throws Exception {
    downloadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    try (InputStream is = remote.getInputStream();
        FileOutputStream fos = new FileOutputStream(localFile)) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = is.read(buf)) != -1) {
        if (downloadCancelled) throw new IOException("Download cancelled");
        fos.write(buf, 0, n);
      }
    }
    long remoteTs = remote.lastModified();
    if (remoteTs > 0) localFile.setLastModified(remoteTs);
  }

  public void downloadFile(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull String localFilePath)
      throws Exception {
    downloadFile(connection, remotePath, new File(localFilePath));
  }

  public void downloadFileWithProgress(
      @NonNull SmbConnection connection,
      @NonNull String remotePath,
      @NonNull File localFile,
      @Nullable BackgroundSmbManager.ProgressCallback progressCallback)
      throws Exception {
    downloadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    long totalBytes = remote.length();
    long transferred = 0;
    try (InputStream is = remote.getInputStream();
        FileOutputStream fos = new FileOutputStream(localFile)) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = is.read(buf)) != -1) {
        if (downloadCancelled) throw new IOException("Download cancelled");
        fos.write(buf, 0, n);
        transferred += n;
        if (progressCallback != null && totalBytes > 0) {
          progressCallback.updateBytesProgress(transferred, totalBytes, localFile.getName());
        }
      }
    }
    long remoteTs = remote.lastModified();
    if (remoteTs > 0) localFile.setLastModified(remoteTs);
  }

  public void uploadFile(
      @NonNull SmbConnection connection, @NonNull File localFile, @NonNull String remotePath)
      throws Exception {
    uploadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    try (FileInputStream fis = new FileInputStream(localFile);
        OutputStream os = remote.getOutputStream()) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = fis.read(buf)) != -1) {
        if (uploadCancelled) throw new IOException("Upload cancelled");
        os.write(buf, 0, n);
      }
    }
    remote.setLastModified(localFile.lastModified());
  }

  public void uploadFileWithProgress(
      @NonNull SmbConnection connection,
      @NonNull File localFile,
      @NonNull String remotePath,
      @Nullable BackgroundSmbManager.ProgressCallback progressCallback)
      throws Exception {
    uploadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    long totalBytes = localFile.length();
    long transferred = 0;
    try (FileInputStream fis = new FileInputStream(localFile);
        OutputStream os = remote.getOutputStream()) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = fis.read(buf)) != -1) {
        if (uploadCancelled) throw new IOException("Upload cancelled");
        os.write(buf, 0, n);
        transferred += n;
        if (progressCallback != null && totalBytes > 0) {
          progressCallback.updateBytesProgress(transferred, totalBytes, localFile.getName());
        }
      }
    }
    remote.setLastModified(localFile.lastModified());
  }

  // ── Byte-range reads ───────────────────────────────────────────────────────

  public byte[] readRange(
      @NonNull SmbConnection connection, @NonNull String remotePath, long offset, int length)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    try (InputStream is = remote.getInputStream()) {
      long skipped = 0;
      while (skipped < offset) {
        long n = is.skip(offset - skipped);
        if (n <= 0) break;
        skipped += n;
      }
      byte[] buf = new byte[length];
      int read = 0;
      while (read < length) {
        int n = is.read(buf, read, length - read);
        if (n == -1) break;
        read += n;
      }
      if (read == length) return buf;
      byte[] trimmed = new byte[read];
      System.arraycopy(buf, 0, trimmed, 0, read);
      return trimmed;
    }
  }

  public byte[] readFileBytes(
      @NonNull SmbConnection connection, @NonNull String remotePath, long maxBytes)
      throws Exception {
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    long fileSize = remote.length();
    if (maxBytes > 0 && fileSize > maxBytes) {
      throw new IOException("File size " + fileSize + " exceeds limit " + maxBytes);
    }
    try (InputStream is = remote.getInputStream();
        ByteArrayOutputStream baos =
            new ByteArrayOutputStream((int) Math.min(fileSize, 1024 * 1024))) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = is.read(buf)) != -1) {
        baos.write(buf, 0, n);
      }
      return baos.toByteArray();
    }
  }

  // ── Folder download ────────────────────────────────────────────────────────

  public void downloadFolder(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull File localFolder)
      throws Exception {
    downloadFolderWithProgress(connection, remotePath, localFolder, null);
  }

  public void downloadFolderWithProgress(
      @NonNull SmbConnection connection,
      @NonNull String remotePath,
      @NonNull File localFolder,
      @Nullable BackgroundSmbManager.MultiFileProgressCallback progressCallback)
      throws Exception {
    if (!localFolder.exists() && !localFolder.mkdirs()) {
      throw new IOException("Cannot create local folder: " + localFolder.getPath());
    }
    List<SmbFileItem> items = listFiles(connection, remotePath);
    for (SmbFileItem item : items) {
      if (downloadCancelled) throw new IOException("Download cancelled");
      if (item.isDirectory()) {
        File subDir = new File(localFolder, item.getName());
        downloadFolderWithProgress(connection, item.getPath(), subDir, progressCallback);
      } else {
        File localFile = new File(localFolder, item.getName());
        if (progressCallback != null) {
          progressCallback.updateProgress("Downloading: " + item.getName());
        }
        downloadFile(connection, item.getPath(), localFile);
        if (progressCallback != null) {
          progressCallback.updateBytesProgress(item.getSize(), item.getSize(), item.getName());
        }
      }
    }
  }

  // ── Stream-based helpers (for SAF-based workers) ──────────────────────────

  /**
   * Uploads from an arbitrary InputStream to a remote path. Caller is responsible for closing in.
   */
  public void uploadFromStream(
      @NonNull SmbConnection connection, @NonNull InputStream in, @NonNull String remotePath)
      throws Exception {
    uploadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    try (OutputStream os = remote.getOutputStream()) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (uploadCancelled) throw new IOException("Upload cancelled");
        os.write(buf, 0, n);
      }
    }
  }

  /**
   * Downloads a remote file to an arbitrary OutputStream. Caller is responsible for closing out.
   */
  public void downloadToStream(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull OutputStream out)
      throws Exception {
    downloadCancelled = false;
    CIFSContext ctx = buildContext(connection);
    String shareName = getShareName(connection.getShare());
    SmbFile remote = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
    try (InputStream is = remote.getInputStream()) {
      byte[] buf = new byte[COPY_BUFFER_SIZE];
      int n;
      while ((n = is.read(buf)) != -1) {
        if (downloadCancelled) throw new IOException("Download cancelled");
        out.write(buf, 0, n);
      }
    }
  }

  public long getRemoteLastModified(@NonNull SmbConnection connection, @NonNull String remotePath) {
    try {
      CIFSContext ctx = buildContext(connection);
      String shareName = getShareName(connection.getShare());
      SmbFile f = new SmbFile(buildFileUrl(connection, shareName, remotePath), ctx);
      return f.lastModified();
    } catch (Exception e) {
      return 0;
    }
  }

  // ── Search ─────────────────────────────────────────────────────────────────

  public void searchFilesStreaming(
      @NonNull SmbConnection connection,
      @NonNull String path,
      @NonNull String query,
      int searchType,
      boolean includeSubfolders,
      @NonNull Consumer<SmbFileItem> onResult)
      throws Exception {
    searchRecursive(
        connection, path, query.toLowerCase(Locale.ROOT), searchType, includeSubfolders, onResult);
  }

  private void searchRecursive(
      SmbConnection connection,
      String path,
      String lowerQuery,
      int searchType,
      boolean recurse,
      Consumer<SmbFileItem> onResult)
      throws Exception {
    List<SmbFileItem> items = listFiles(connection, path);
    for (SmbFileItem item : items) {
      boolean nameMatches = item.getName().toLowerCase(Locale.ROOT).contains(lowerQuery);
      boolean typeMatches =
          searchType == 0
              || (searchType == 1 && item.isFile())
              || (searchType == 2 && item.isDirectory());
      if (nameMatches && typeMatches) {
        onResult.accept(item);
      }
      if (item.isDirectory() && recurse) {
        searchRecursive(connection, item.getPath(), lowerQuery, searchType, true, onResult);
      }
    }
  }
}
