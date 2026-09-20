/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package androidx.media3.session;
/**
 * Interface from MediaController to MediaSession.
 *
 * <p>It's for internal use only, not intended to be used by library users.
 */// Note: Keep this interface oneway. Otherwise a malicious app may make a blocking call to make
// session frozen.

public interface IMediaSession extends android.os.IInterface
{
  /** Default implementation for IMediaSession. */
  public static class Default implements androidx.media3.session.IMediaSession
  {
    // Id < 3000 is reserved to avoid potential collision with media2 1.x.

    @Override public void setVolume(androidx.media3.session.IMediaController caller, int seq, float volume) throws android.os.RemoteException
    {
    }
    @Override public void setDeviceVolume(androidx.media3.session.IMediaController caller, int seq, int volume) throws android.os.RemoteException
    {
    }
    @Override public void setDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int volume, int flags) throws android.os.RemoteException
    {
    }
    @Override public void increaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void increaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException
    {
    }
    @Override public void decreaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void decreaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException
    {
    }
    @Override public void setDeviceMuted(androidx.media3.session.IMediaController caller, int seq, boolean muted) throws android.os.RemoteException
    {
    }
    @Override public void setDeviceMutedWithFlags(androidx.media3.session.IMediaController caller, int seq, boolean muted, int flags) throws android.os.RemoteException
    {
    }
    @Override public void setAudioAttributes(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle audioAttributes, boolean handleAudioFocus) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItemWithStartPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, long startPositionMs) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItemWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, boolean resetPosition) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItemsWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, boolean resetPosition) throws android.os.RemoteException
    {
    }
    @Override public void setMediaItemsWithStartIndex(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, int startIndex, long startPositionMs) throws android.os.RemoteException
    {
    }
    @Override public void setPlayWhenReady(androidx.media3.session.IMediaController caller, int seq, boolean playWhenReady) throws android.os.RemoteException
    {
    }
    @Override public void onControllerResult(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle controllerResult) throws android.os.RemoteException
    {
    }
    @Override public void connect(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle connectionRequest) throws android.os.RemoteException
    {
    }
    @Override public void onCustomCommand(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle sessionCommand, android.os.Bundle args) throws android.os.RemoteException
    {
    }
    @Override public void setRepeatMode(androidx.media3.session.IMediaController caller, int seq, int repeatMode) throws android.os.RemoteException
    {
    }
    @Override public void setShuffleModeEnabled(androidx.media3.session.IMediaController caller, int seq, boolean shuffleModeEnabled) throws android.os.RemoteException
    {
    }
    @Override public void removeMediaItem(androidx.media3.session.IMediaController caller, int seq, int index) throws android.os.RemoteException
    {
    }
    @Override public void removeMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex) throws android.os.RemoteException
    {
    }
    @Override public void clearMediaItems(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void moveMediaItem(androidx.media3.session.IMediaController caller, int seq, int currentIndex, int newIndex) throws android.os.RemoteException
    {
    }
    @Override public void moveMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, int newIndex) throws android.os.RemoteException
    {
    }
    @Override public void replaceMediaItem(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
    {
    }
    @Override public void replaceMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, android.os.IBinder mediaItems) throws android.os.RemoteException
    {
    }
    @Override public void play(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void pause(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void prepare(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void setPlaybackParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playbackParametersBundle) throws android.os.RemoteException
    {
    }
    @Override public void setPlaybackSpeed(androidx.media3.session.IMediaController caller, int seq, float speed) throws android.os.RemoteException
    {
    }
    @Override public void addMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
    {
    }
    @Override public void addMediaItemWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
    {
    }
    @Override public void addMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException
    {
    }
    @Override public void addMediaItemsWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.IBinder mediaItems) throws android.os.RemoteException
    {
    }
    @Override public void setPlaylistMetadata(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playlistMetadata) throws android.os.RemoteException
    {
    }
    @Override public void stop(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void release(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekToDefaultPosition(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekToDefaultPositionWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex) throws android.os.RemoteException
    {
    }
    @Override public void seekTo(androidx.media3.session.IMediaController caller, int seq, long positionMs) throws android.os.RemoteException
    {
    }
    @Override public void seekToWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex, long positionMs) throws android.os.RemoteException
    {
    }
    @Override public void seekBack(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekForward(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekToPreviousMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekToNextMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void setVideoSurface(androidx.media3.session.IMediaController caller, int seq, android.view.Surface surface) throws android.os.RemoteException
    {
    }
    @Override public void flushCommandQueue(androidx.media3.session.IMediaController caller) throws android.os.RemoteException
    {
    }
    @Override public void seekToPrevious(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void seekToNext(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
    {
    }
    @Override public void setTrackSelectionParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle trackSelectionParametersBundle) throws android.os.RemoteException
    {
    }
    @Override public void setRatingWithMediaId(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId, android.os.Bundle rating) throws android.os.RemoteException
    {
    }
    @Override public void setRating(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle rating) throws android.os.RemoteException
    {
    }
    // Next Id for MediaSession: 3057

    @Override public void getLibraryRoot(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void getItem(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId) throws android.os.RemoteException
    {
    }
    @Override public void getChildren(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void search(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void getSearchResult(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void subscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void unsubscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements androidx.media3.session.IMediaSession
  {
    private static final java.lang.String DESCRIPTOR = "androidx.media3.session.IMediaSession";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an androidx.media3.session.IMediaSession interface,
     * generating a proxy if needed.
     */
    public static androidx.media3.session.IMediaSession asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof androidx.media3.session.IMediaSession))) {
        return ((androidx.media3.session.IMediaSession)iin);
      }
      return new androidx.media3.session.IMediaSession.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
        case TRANSACTION_setVolume:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          float _arg2;
          _arg2 = data.readFloat();
          this.setVolume(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setDeviceVolume:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.setDeviceVolume(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setDeviceVolumeWithFlags:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          this.setDeviceVolumeWithFlags(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_increaseDeviceVolume:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.increaseDeviceVolume(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_increaseDeviceVolumeWithFlags:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.increaseDeviceVolumeWithFlags(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_decreaseDeviceVolume:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.decreaseDeviceVolume(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_decreaseDeviceVolumeWithFlags:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.decreaseDeviceVolumeWithFlags(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setDeviceMuted:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          boolean _arg2;
          _arg2 = (0!=data.readInt());
          this.setDeviceMuted(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setDeviceMutedWithFlags:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          boolean _arg2;
          _arg2 = (0!=data.readInt());
          int _arg3;
          _arg3 = data.readInt();
          this.setDeviceMutedWithFlags(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setAudioAttributes:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          boolean _arg3;
          _arg3 = (0!=data.readInt());
          this.setAudioAttributes(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setMediaItem(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setMediaItemWithStartPosition:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          long _arg3;
          _arg3 = data.readLong();
          this.setMediaItemWithStartPosition(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setMediaItemWithResetPosition:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          boolean _arg3;
          _arg3 = (0!=data.readInt());
          this.setMediaItemWithResetPosition(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.IBinder _arg2;
          _arg2 = data.readStrongBinder();
          this.setMediaItems(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setMediaItemsWithResetPosition:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.IBinder _arg2;
          _arg2 = data.readStrongBinder();
          boolean _arg3;
          _arg3 = (0!=data.readInt());
          this.setMediaItemsWithResetPosition(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setMediaItemsWithStartIndex:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.IBinder _arg2;
          _arg2 = data.readStrongBinder();
          int _arg3;
          _arg3 = data.readInt();
          long _arg4;
          _arg4 = data.readLong();
          this.setMediaItemsWithStartIndex(_arg0, _arg1, _arg2, _arg3, _arg4);
          return true;
        }
        case TRANSACTION_setPlayWhenReady:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          boolean _arg2;
          _arg2 = (0!=data.readInt());
          this.setPlayWhenReady(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onControllerResult:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.onControllerResult(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_connect:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.connect(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onCustomCommand:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.onCustomCommand(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setRepeatMode:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.setRepeatMode(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setShuffleModeEnabled:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          boolean _arg2;
          _arg2 = (0!=data.readInt());
          this.setShuffleModeEnabled(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_removeMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.removeMediaItem(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_removeMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          this.removeMediaItems(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_clearMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.clearMediaItems(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_moveMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          this.moveMediaItem(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_moveMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          int _arg4;
          _arg4 = data.readInt();
          this.moveMediaItems(_arg0, _arg1, _arg2, _arg3, _arg4);
          return true;
        }
        case TRANSACTION_replaceMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.replaceMediaItem(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_replaceMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          android.os.IBinder _arg4;
          _arg4 = data.readStrongBinder();
          this.replaceMediaItems(_arg0, _arg1, _arg2, _arg3, _arg4);
          return true;
        }
        case TRANSACTION_play:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.play(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_pause:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.pause(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_prepare:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.prepare(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_setPlaybackParameters:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setPlaybackParameters(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setPlaybackSpeed:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          float _arg2;
          _arg2 = data.readFloat();
          this.setPlaybackSpeed(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_addMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.addMediaItem(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_addMediaItemWithIndex:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.addMediaItemWithIndex(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_addMediaItems:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.IBinder _arg2;
          _arg2 = data.readStrongBinder();
          this.addMediaItems(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_addMediaItemsWithIndex:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          android.os.IBinder _arg3;
          _arg3 = data.readStrongBinder();
          this.addMediaItemsWithIndex(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setPlaylistMetadata:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setPlaylistMetadata(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_stop:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.stop(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_release:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.release(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekToDefaultPosition:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekToDefaultPosition(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekToDefaultPositionWithMediaItemIndex:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.seekToDefaultPositionWithMediaItemIndex(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_seekTo:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          long _arg2;
          _arg2 = data.readLong();
          this.seekTo(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_seekToWithMediaItemIndex:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          long _arg3;
          _arg3 = data.readLong();
          this.seekToWithMediaItemIndex(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_seekBack:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekBack(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekForward:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekForward(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekToPreviousMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekToPreviousMediaItem(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekToNextMediaItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekToNextMediaItem(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_setVideoSurface:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.view.Surface _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.view.Surface.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setVideoSurface(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_flushCommandQueue:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          this.flushCommandQueue(_arg0);
          return true;
        }
        case TRANSACTION_seekToPrevious:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekToPrevious(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_seekToNext:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          this.seekToNext(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_setTrackSelectionParameters:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setTrackSelectionParameters(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_setRatingWithMediaId:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.setRatingWithMediaId(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_setRating:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.setRating(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_getLibraryRoot:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.getLibraryRoot(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_getItem:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          this.getItem(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_getChildren:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _arg3;
          _arg3 = data.readInt();
          int _arg4;
          _arg4 = data.readInt();
          android.os.Bundle _arg5;
          if ((0!=data.readInt())) {
            _arg5 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg5 = null;
          }
          this.getChildren(_arg0, _arg1, _arg2, _arg3, _arg4, _arg5);
          return true;
        }
        case TRANSACTION_search:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.search(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_getSearchResult:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _arg3;
          _arg3 = data.readInt();
          int _arg4;
          _arg4 = data.readInt();
          android.os.Bundle _arg5;
          if ((0!=data.readInt())) {
            _arg5 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg5 = null;
          }
          this.getSearchResult(_arg0, _arg1, _arg2, _arg3, _arg4, _arg5);
          return true;
        }
        case TRANSACTION_subscribe:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.subscribe(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_unsubscribe:
        {
          data.enforceInterface(descriptor);
          androidx.media3.session.IMediaController _arg0;
          _arg0 = androidx.media3.session.IMediaController.Stub.asInterface(data.readStrongBinder());
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          this.unsubscribe(_arg0, _arg1, _arg2);
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements androidx.media3.session.IMediaSession
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      // Id < 3000 is reserved to avoid potential collision with media2 1.x.

      @Override public void setVolume(androidx.media3.session.IMediaController caller, int seq, float volume) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeFloat(volume);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setVolume, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setVolume(caller, seq, volume);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setDeviceVolume(androidx.media3.session.IMediaController caller, int seq, int volume) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(volume);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDeviceVolume, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setDeviceVolume(caller, seq, volume);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int volume, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(volume);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDeviceVolumeWithFlags, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setDeviceVolumeWithFlags(caller, seq, volume, flags);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void increaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_increaseDeviceVolume, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().increaseDeviceVolume(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void increaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_increaseDeviceVolumeWithFlags, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().increaseDeviceVolumeWithFlags(caller, seq, flags);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void decreaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_decreaseDeviceVolume, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().decreaseDeviceVolume(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void decreaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_decreaseDeviceVolumeWithFlags, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().decreaseDeviceVolumeWithFlags(caller, seq, flags);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setDeviceMuted(androidx.media3.session.IMediaController caller, int seq, boolean muted) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(((muted)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDeviceMuted, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setDeviceMuted(caller, seq, muted);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setDeviceMutedWithFlags(androidx.media3.session.IMediaController caller, int seq, boolean muted, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(((muted)?(1):(0)));
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDeviceMutedWithFlags, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setDeviceMutedWithFlags(caller, seq, muted, flags);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setAudioAttributes(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle audioAttributes, boolean handleAudioFocus) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((audioAttributes!=null)) {
            _data.writeInt(1);
            audioAttributes.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          _data.writeInt(((handleAudioFocus)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAudioAttributes, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setAudioAttributes(caller, seq, audioAttributes, handleAudioFocus);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItem(caller, seq, mediaItemBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItemWithStartPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, long startPositionMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          _data.writeLong(startPositionMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItemWithStartPosition, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItemWithStartPosition(caller, seq, mediaItemBundle, startPositionMs);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItemWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, boolean resetPosition) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          _data.writeInt(((resetPosition)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItemWithResetPosition, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItemWithResetPosition(caller, seq, mediaItemBundle, resetPosition);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeStrongBinder(mediaItems);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItems(caller, seq, mediaItems);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItemsWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, boolean resetPosition) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeStrongBinder(mediaItems);
          _data.writeInt(((resetPosition)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItemsWithResetPosition, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItemsWithResetPosition(caller, seq, mediaItems, resetPosition);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setMediaItemsWithStartIndex(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, int startIndex, long startPositionMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeStrongBinder(mediaItems);
          _data.writeInt(startIndex);
          _data.writeLong(startPositionMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMediaItemsWithStartIndex, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setMediaItemsWithStartIndex(caller, seq, mediaItems, startIndex, startPositionMs);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setPlayWhenReady(androidx.media3.session.IMediaController caller, int seq, boolean playWhenReady) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(((playWhenReady)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setPlayWhenReady, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setPlayWhenReady(caller, seq, playWhenReady);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onControllerResult(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle controllerResult) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((controllerResult!=null)) {
            _data.writeInt(1);
            controllerResult.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onControllerResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onControllerResult(caller, seq, controllerResult);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void connect(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle connectionRequest) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((connectionRequest!=null)) {
            _data.writeInt(1);
            connectionRequest.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_connect, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().connect(caller, seq, connectionRequest);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onCustomCommand(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle sessionCommand, android.os.Bundle args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((sessionCommand!=null)) {
            _data.writeInt(1);
            sessionCommand.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          if ((args!=null)) {
            _data.writeInt(1);
            args.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onCustomCommand, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onCustomCommand(caller, seq, sessionCommand, args);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setRepeatMode(androidx.media3.session.IMediaController caller, int seq, int repeatMode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(repeatMode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setRepeatMode, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setRepeatMode(caller, seq, repeatMode);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setShuffleModeEnabled(androidx.media3.session.IMediaController caller, int seq, boolean shuffleModeEnabled) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(((shuffleModeEnabled)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setShuffleModeEnabled, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setShuffleModeEnabled(caller, seq, shuffleModeEnabled);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void removeMediaItem(androidx.media3.session.IMediaController caller, int seq, int index) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(index);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().removeMediaItem(caller, seq, index);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void removeMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(fromIndex);
          _data.writeInt(toIndex);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().removeMediaItems(caller, seq, fromIndex, toIndex);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void clearMediaItems(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_clearMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().clearMediaItems(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void moveMediaItem(androidx.media3.session.IMediaController caller, int seq, int currentIndex, int newIndex) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(currentIndex);
          _data.writeInt(newIndex);
          boolean _status = mRemote.transact(Stub.TRANSACTION_moveMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().moveMediaItem(caller, seq, currentIndex, newIndex);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void moveMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, int newIndex) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(fromIndex);
          _data.writeInt(toIndex);
          _data.writeInt(newIndex);
          boolean _status = mRemote.transact(Stub.TRANSACTION_moveMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().moveMediaItems(caller, seq, fromIndex, toIndex, newIndex);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void replaceMediaItem(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(index);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_replaceMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().replaceMediaItem(caller, seq, index, mediaItemBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void replaceMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, android.os.IBinder mediaItems) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(fromIndex);
          _data.writeInt(toIndex);
          _data.writeStrongBinder(mediaItems);
          boolean _status = mRemote.transact(Stub.TRANSACTION_replaceMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().replaceMediaItems(caller, seq, fromIndex, toIndex, mediaItems);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void play(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_play, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().play(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void pause(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_pause, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().pause(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void prepare(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_prepare, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().prepare(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setPlaybackParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playbackParametersBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((playbackParametersBundle!=null)) {
            _data.writeInt(1);
            playbackParametersBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setPlaybackParameters, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setPlaybackParameters(caller, seq, playbackParametersBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setPlaybackSpeed(androidx.media3.session.IMediaController caller, int seq, float speed) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeFloat(speed);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setPlaybackSpeed, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setPlaybackSpeed(caller, seq, speed);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void addMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_addMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().addMediaItem(caller, seq, mediaItemBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void addMediaItemWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(index);
          if ((mediaItemBundle!=null)) {
            _data.writeInt(1);
            mediaItemBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_addMediaItemWithIndex, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().addMediaItemWithIndex(caller, seq, index, mediaItemBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void addMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeStrongBinder(mediaItems);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addMediaItems, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().addMediaItems(caller, seq, mediaItems);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void addMediaItemsWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.IBinder mediaItems) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(index);
          _data.writeStrongBinder(mediaItems);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addMediaItemsWithIndex, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().addMediaItemsWithIndex(caller, seq, index, mediaItems);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setPlaylistMetadata(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playlistMetadata) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((playlistMetadata!=null)) {
            _data.writeInt(1);
            playlistMetadata.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setPlaylistMetadata, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setPlaylistMetadata(caller, seq, playlistMetadata);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void stop(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_stop, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().stop(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void release(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_release, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().release(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToDefaultPosition(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToDefaultPosition, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToDefaultPosition(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToDefaultPositionWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(mediaItemIndex);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToDefaultPositionWithMediaItemIndex, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToDefaultPositionWithMediaItemIndex(caller, seq, mediaItemIndex);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekTo(androidx.media3.session.IMediaController caller, int seq, long positionMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeLong(positionMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekTo, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekTo(caller, seq, positionMs);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex, long positionMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeInt(mediaItemIndex);
          _data.writeLong(positionMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToWithMediaItemIndex, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToWithMediaItemIndex(caller, seq, mediaItemIndex, positionMs);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekBack(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekBack, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekBack(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekForward(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekForward, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekForward(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToPreviousMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToPreviousMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToPreviousMediaItem(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToNextMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToNextMediaItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToNextMediaItem(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setVideoSurface(androidx.media3.session.IMediaController caller, int seq, android.view.Surface surface) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((surface!=null)) {
            _data.writeInt(1);
            surface.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setVideoSurface, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setVideoSurface(caller, seq, surface);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void flushCommandQueue(androidx.media3.session.IMediaController caller) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_flushCommandQueue, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().flushCommandQueue(caller);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToPrevious(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToPrevious, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToPrevious(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void seekToNext(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_seekToNext, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().seekToNext(caller, seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setTrackSelectionParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle trackSelectionParametersBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((trackSelectionParametersBundle!=null)) {
            _data.writeInt(1);
            trackSelectionParametersBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setTrackSelectionParameters, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setTrackSelectionParameters(caller, seq, trackSelectionParametersBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setRatingWithMediaId(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId, android.os.Bundle rating) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(mediaId);
          if ((rating!=null)) {
            _data.writeInt(1);
            rating.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setRatingWithMediaId, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setRatingWithMediaId(caller, seq, mediaId, rating);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void setRating(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle rating) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((rating!=null)) {
            _data.writeInt(1);
            rating.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_setRating, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setRating(caller, seq, rating);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      // Next Id for MediaSession: 3057

      @Override public void getLibraryRoot(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_getLibraryRoot, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().getLibraryRoot(caller, seq, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void getItem(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(mediaId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getItem, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().getItem(caller, seq, mediaId);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void getChildren(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(parentId);
          _data.writeInt(page);
          _data.writeInt(pageSize);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_getChildren, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().getChildren(caller, seq, parentId, page, pageSize, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void search(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(query);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_search, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().search(caller, seq, query, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void getSearchResult(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(query);
          _data.writeInt(page);
          _data.writeInt(pageSize);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSearchResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().getSearchResult(caller, seq, query, page, pageSize, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void subscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(parentId);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_subscribe, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().subscribe(caller, seq, parentId, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void unsubscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((caller!=null))?(caller.asBinder()):(null)));
          _data.writeInt(seq);
          _data.writeString(parentId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_unsubscribe, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().unsubscribe(caller, seq, parentId);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      public static androidx.media3.session.IMediaSession sDefaultImpl;
    }
    static final int TRANSACTION_setVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3001);
    static final int TRANSACTION_setDeviceVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3002);
    static final int TRANSACTION_setDeviceVolumeWithFlags = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3050);
    static final int TRANSACTION_increaseDeviceVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3003);
    static final int TRANSACTION_increaseDeviceVolumeWithFlags = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3051);
    static final int TRANSACTION_decreaseDeviceVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3004);
    static final int TRANSACTION_decreaseDeviceVolumeWithFlags = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3052);
    static final int TRANSACTION_setDeviceMuted = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3005);
    static final int TRANSACTION_setDeviceMutedWithFlags = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3053);
    static final int TRANSACTION_setAudioAttributes = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3056);
    static final int TRANSACTION_setMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3006);
    static final int TRANSACTION_setMediaItemWithStartPosition = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3007);
    static final int TRANSACTION_setMediaItemWithResetPosition = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3008);
    static final int TRANSACTION_setMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3009);
    static final int TRANSACTION_setMediaItemsWithResetPosition = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3010);
    static final int TRANSACTION_setMediaItemsWithStartIndex = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3011);
    static final int TRANSACTION_setPlayWhenReady = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3012);
    static final int TRANSACTION_onControllerResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3013);
    static final int TRANSACTION_connect = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3014);
    static final int TRANSACTION_onCustomCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3015);
    static final int TRANSACTION_setRepeatMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3016);
    static final int TRANSACTION_setShuffleModeEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3017);
    static final int TRANSACTION_removeMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3018);
    static final int TRANSACTION_removeMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3019);
    static final int TRANSACTION_clearMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3020);
    static final int TRANSACTION_moveMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3021);
    static final int TRANSACTION_moveMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3022);
    static final int TRANSACTION_replaceMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3054);
    static final int TRANSACTION_replaceMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3055);
    static final int TRANSACTION_play = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3023);
    static final int TRANSACTION_pause = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3024);
    static final int TRANSACTION_prepare = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3025);
    static final int TRANSACTION_setPlaybackParameters = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3026);
    static final int TRANSACTION_setPlaybackSpeed = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3027);
    static final int TRANSACTION_addMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3028);
    static final int TRANSACTION_addMediaItemWithIndex = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3029);
    static final int TRANSACTION_addMediaItems = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3030);
    static final int TRANSACTION_addMediaItemsWithIndex = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3031);
    static final int TRANSACTION_setPlaylistMetadata = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3032);
    static final int TRANSACTION_stop = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3033);
    static final int TRANSACTION_release = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3034);
    static final int TRANSACTION_seekToDefaultPosition = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3035);
    static final int TRANSACTION_seekToDefaultPositionWithMediaItemIndex = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3036);
    static final int TRANSACTION_seekTo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3037);
    static final int TRANSACTION_seekToWithMediaItemIndex = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3038);
    static final int TRANSACTION_seekBack = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3039);
    static final int TRANSACTION_seekForward = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3040);
    static final int TRANSACTION_seekToPreviousMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3041);
    static final int TRANSACTION_seekToNextMediaItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3042);
    static final int TRANSACTION_setVideoSurface = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3043);
    static final int TRANSACTION_flushCommandQueue = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3044);
    static final int TRANSACTION_seekToPrevious = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3045);
    static final int TRANSACTION_seekToNext = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3046);
    static final int TRANSACTION_setTrackSelectionParameters = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3047);
    static final int TRANSACTION_setRatingWithMediaId = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3048);
    static final int TRANSACTION_setRating = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3049);
    static final int TRANSACTION_getLibraryRoot = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4000);
    static final int TRANSACTION_getItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4001);
    static final int TRANSACTION_getChildren = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4002);
    static final int TRANSACTION_search = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4003);
    static final int TRANSACTION_getSearchResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4004);
    static final int TRANSACTION_subscribe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4005);
    static final int TRANSACTION_unsubscribe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4006);
    public static boolean setDefaultImpl(androidx.media3.session.IMediaSession impl) {
      // Only one user of this interface can use this function
      // at a time. This is a heuristic to detect if two different
      // users in the same process use this function.
      if (Stub.Proxy.sDefaultImpl != null) {
        throw new IllegalStateException("setDefaultImpl() called twice");
      }
      if (impl != null) {
        Stub.Proxy.sDefaultImpl = impl;
        return true;
      }
      return false;
    }
    public static androidx.media3.session.IMediaSession getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  // Id < 3000 is reserved to avoid potential collision with media2 1.x.

  public void setVolume(androidx.media3.session.IMediaController caller, int seq, float volume) throws android.os.RemoteException;
  public void setDeviceVolume(androidx.media3.session.IMediaController caller, int seq, int volume) throws android.os.RemoteException;
  public void setDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int volume, int flags) throws android.os.RemoteException;
  public void increaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void increaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException;
  public void decreaseDeviceVolume(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void decreaseDeviceVolumeWithFlags(androidx.media3.session.IMediaController caller, int seq, int flags) throws android.os.RemoteException;
  public void setDeviceMuted(androidx.media3.session.IMediaController caller, int seq, boolean muted) throws android.os.RemoteException;
  public void setDeviceMutedWithFlags(androidx.media3.session.IMediaController caller, int seq, boolean muted, int flags) throws android.os.RemoteException;
  public void setAudioAttributes(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle audioAttributes, boolean handleAudioFocus) throws android.os.RemoteException;
  public void setMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException;
  public void setMediaItemWithStartPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, long startPositionMs) throws android.os.RemoteException;
  public void setMediaItemWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle, boolean resetPosition) throws android.os.RemoteException;
  public void setMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException;
  public void setMediaItemsWithResetPosition(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, boolean resetPosition) throws android.os.RemoteException;
  public void setMediaItemsWithStartIndex(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems, int startIndex, long startPositionMs) throws android.os.RemoteException;
  public void setPlayWhenReady(androidx.media3.session.IMediaController caller, int seq, boolean playWhenReady) throws android.os.RemoteException;
  public void onControllerResult(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle controllerResult) throws android.os.RemoteException;
  public void connect(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle connectionRequest) throws android.os.RemoteException;
  public void onCustomCommand(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle sessionCommand, android.os.Bundle args) throws android.os.RemoteException;
  public void setRepeatMode(androidx.media3.session.IMediaController caller, int seq, int repeatMode) throws android.os.RemoteException;
  public void setShuffleModeEnabled(androidx.media3.session.IMediaController caller, int seq, boolean shuffleModeEnabled) throws android.os.RemoteException;
  public void removeMediaItem(androidx.media3.session.IMediaController caller, int seq, int index) throws android.os.RemoteException;
  public void removeMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex) throws android.os.RemoteException;
  public void clearMediaItems(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void moveMediaItem(androidx.media3.session.IMediaController caller, int seq, int currentIndex, int newIndex) throws android.os.RemoteException;
  public void moveMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, int newIndex) throws android.os.RemoteException;
  public void replaceMediaItem(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException;
  public void replaceMediaItems(androidx.media3.session.IMediaController caller, int seq, int fromIndex, int toIndex, android.os.IBinder mediaItems) throws android.os.RemoteException;
  public void play(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void pause(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void prepare(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void setPlaybackParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playbackParametersBundle) throws android.os.RemoteException;
  public void setPlaybackSpeed(androidx.media3.session.IMediaController caller, int seq, float speed) throws android.os.RemoteException;
  public void addMediaItem(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle mediaItemBundle) throws android.os.RemoteException;
  public void addMediaItemWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.Bundle mediaItemBundle) throws android.os.RemoteException;
  public void addMediaItems(androidx.media3.session.IMediaController caller, int seq, android.os.IBinder mediaItems) throws android.os.RemoteException;
  public void addMediaItemsWithIndex(androidx.media3.session.IMediaController caller, int seq, int index, android.os.IBinder mediaItems) throws android.os.RemoteException;
  public void setPlaylistMetadata(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle playlistMetadata) throws android.os.RemoteException;
  public void stop(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void release(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekToDefaultPosition(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekToDefaultPositionWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex) throws android.os.RemoteException;
  public void seekTo(androidx.media3.session.IMediaController caller, int seq, long positionMs) throws android.os.RemoteException;
  public void seekToWithMediaItemIndex(androidx.media3.session.IMediaController caller, int seq, int mediaItemIndex, long positionMs) throws android.os.RemoteException;
  public void seekBack(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekForward(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekToPreviousMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekToNextMediaItem(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void setVideoSurface(androidx.media3.session.IMediaController caller, int seq, android.view.Surface surface) throws android.os.RemoteException;
  public void flushCommandQueue(androidx.media3.session.IMediaController caller) throws android.os.RemoteException;
  public void seekToPrevious(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void seekToNext(androidx.media3.session.IMediaController caller, int seq) throws android.os.RemoteException;
  public void setTrackSelectionParameters(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle trackSelectionParametersBundle) throws android.os.RemoteException;
  public void setRatingWithMediaId(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId, android.os.Bundle rating) throws android.os.RemoteException;
  public void setRating(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle rating) throws android.os.RemoteException;
  // Next Id for MediaSession: 3057

  public void getLibraryRoot(androidx.media3.session.IMediaController caller, int seq, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void getItem(androidx.media3.session.IMediaController caller, int seq, java.lang.String mediaId) throws android.os.RemoteException;
  public void getChildren(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void search(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void getSearchResult(androidx.media3.session.IMediaController caller, int seq, java.lang.String query, int page, int pageSize, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void subscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void unsubscribe(androidx.media3.session.IMediaController caller, int seq, java.lang.String parentId) throws android.os.RemoteException;
}
