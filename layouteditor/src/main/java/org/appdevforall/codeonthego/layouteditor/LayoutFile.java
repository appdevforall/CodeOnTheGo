package org.appdevforall.codeonthego.layouteditor;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.appdevforall.codeonthego.layouteditor.utils.FileUtil;
import org.jetbrains.annotations.Contract;

import java.io.File;

public class LayoutFile implements Parcelable {

    private String path;
    private String designPath;
    public String name;

    public LayoutFile(String path, String designPath) {
        this.path = path;
        this.designPath = designPath;
        this.name = FileUtil.getLastSegmentFromPath(path);
    }

    public void rename(String newPath, String newDesignPath) {
        File oldFile = new File(path);
        File newFile = new File(newPath);
        oldFile.renameTo(newFile);

        File oldDesignFile = new File(designPath);
        File newDesignFile = new File(newDesignPath);
        oldDesignFile.renameTo(newDesignFile);

        this.path = newPath;
        this.designPath = newDesignPath;
        this.name = FileUtil.getLastSegmentFromPath(newPath);
    }

    public void saveLayout(String content) {
        FileUtil.writeFile(path, content);
    }

    // Saves the internal design-time XML
    public void saveDesignFile(String content) {
        FileUtil.writeFile(designPath, content);
    }

    public String getPath() {
        return path;
    }

    public String getDesignPath() {
        return designPath;
    }

    public String getName() {
        return name;
    }

    public String readDesignFile() {
        return FileUtil.readFile(designPath);
    }

    public String readLayoutFile() {
        return FileUtil.readFile(path);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeString(path);
        parcel.writeString(designPath);
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
        designPath = parcel.readString();
        name = parcel.readString();
    }
}