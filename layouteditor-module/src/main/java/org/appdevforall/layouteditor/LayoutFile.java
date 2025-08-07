package com.itsvks.layouteditor;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.itsvks.layouteditor.utils.FileUtil;

import org.jetbrains.annotations.Contract;

import java.io.File;

public class LayoutFile implements Parcelable {

  private String path;
  public String name;

  public LayoutFile(String path, String designPath) {
    this.path = path;
    this.name = FileUtil.getLastSegmentFromPath(designPath);
  }

  //todo untested.
  public void rename(String newPath, String newDesignPah) {

    File newFile = new File(newPath);
    File oldFile = new File(newPath);
    oldFile.renameTo(newFile);

    path = newPath;
    name = FileUtil.getLastSegmentFromPath(path);
  }


  //saves only to original file
  public void saveLayout(String content) {
    FileUtil.writeFile(path, content);
  }

  public String getPath() {
    return path;
  }


  public String getName() {
    return name;
  }

  public String readDesignFile() {
    return FileUtil.readFile(path);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(@NonNull Parcel parcel, int flags) {
    parcel.writeString(path);
    parcel.writeString(name);
  }

  public static final Parcelable.Creator<LayoutFile> CREATOR =
    new Parcelable.Creator<>() {
      @NonNull
      @Contract("_ -> new")
      public LayoutFile createFromParcel(Parcel in) {
        return new LayoutFile(in);
      }

      @NonNull
      @Contract(value = "_ -> new", pure = true)
      public LayoutFile[] newArray(int size) {
        return new LayoutFile[size];
      }
    };

  private LayoutFile(@NonNull Parcel parcel) {
    path = parcel.readString();
    name = parcel.readString();
  }
}
