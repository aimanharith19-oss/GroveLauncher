/*
 * Interop shim for Shizuku (https://github.com/RikkaApps/Shizuku-API, MIT License, (c) 2021 RikkaW).
 * Shizuku hands its binder to apps wrapped in a parcelable of exactly this class name, so Canopy needs a class
 * with the same name and parcel format to receive it. Written for Canopy; no Shizuku library code is bundled.
 */
package moe.shizuku.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

public class BinderContainer implements Parcelable {
    public IBinder binder;

    public BinderContainer(IBinder binder) { this.binder = binder; }

    protected BinderContainer(Parcel in) { binder = in.readStrongBinder(); }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeStrongBinder(binder); }

    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override public BinderContainer createFromParcel(Parcel source) { return new BinderContainer(source); }
        @Override public BinderContainer[] newArray(int size) { return new BinderContainer[size]; }
    };
}
