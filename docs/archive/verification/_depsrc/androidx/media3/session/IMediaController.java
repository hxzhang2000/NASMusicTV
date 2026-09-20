/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package androidx.media3.session;
/**
 * Interface from MediaSession to MediaController.
 *
 * <p>It's for internal use only, not intended to be used by library users.
 */// Note: Keep this interface oneway. Otherwise a malicious app may make a blocking call to make
// controller frozen.

public interface IMediaController extends android.os.IInterface
{
  /** Default implementation for IMediaController. */
  public static class Default implements androidx.media3.session.IMediaController
  {
    // Id < 3000 is reserved to avoid potential collision with media2 1.x.

    @Override public void onConnected(int seq, android.os.Bundle connectionResult) throws android.os.RemoteException
    {
    }
    @Override public void onSessionResult(int seq, android.os.Bundle sessionResult) throws android.os.RemoteException
    {
    }
    @Override public void onLibraryResult(int seq, android.os.Bundle libraryResult) throws android.os.RemoteException
    {
    }
    @Override public void onSetCustomLayout(int seq, java.util.List<android.os.Bundle> commandButtonList) throws android.os.RemoteException
    {
    }
    @Override public void onCustomCommand(int seq, android.os.Bundle command, android.os.Bundle args) throws android.os.RemoteException
    {
    }
    @Override public void onDisconnected(int seq) throws android.os.RemoteException
    {
    }
    /** Deprecated: Use onPlayerInfoChangedWithExclusions from MediaControllerStub#VERSION_INT=2. */
    @Override public void onPlayerInfoChanged(int seq, android.os.Bundle playerInfoBundle, boolean isTimelineExcluded) throws android.os.RemoteException
    {
    }
    /** Introduced to deprecate onPlayerInfoChanged (from MediaControllerStub#VERSION_INT=2). */
    @Override public void onPlayerInfoChangedWithExclusions(int seq, android.os.Bundle playerInfoBundle, android.os.Bundle playerInfoExclusions) throws android.os.RemoteException
    {
    }
    @Override public void onPeriodicSessionPositionInfoChanged(int seq, android.os.Bundle sessionPositionInfo) throws android.os.RemoteException
    {
    }
    @Override public void onAvailableCommandsChangedFromPlayer(int seq, android.os.Bundle commandsBundle) throws android.os.RemoteException
    {
    }
    @Override public void onAvailableCommandsChangedFromSession(int seq, android.os.Bundle sessionCommandsBundle, android.os.Bundle playerCommandsBundle) throws android.os.RemoteException
    {
    }
    @Override public void onRenderedFirstFrame(int seq) throws android.os.RemoteException
    {
    }
    @Override public void onExtrasChanged(int seq, android.os.Bundle extras) throws android.os.RemoteException
    {
    }
    @Override public void onSessionActivityChanged(int seq, android.app.PendingIntent pendingIntent) throws android.os.RemoteException
    {
    }
    // Next Id for MediaController: 3014

    @Override public void onChildrenChanged(int seq, java.lang.String parentId, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override public void onSearchResultChanged(int seq, java.lang.String query, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements androidx.media3.session.IMediaController
  {
    private static final java.lang.String DESCRIPTOR = "androidx.media3.session.IMediaController";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an androidx.media3.session.IMediaController interface,
     * generating a proxy if needed.
     */
    public static androidx.media3.session.IMediaController asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof androidx.media3.session.IMediaController))) {
        return ((androidx.media3.session.IMediaController)iin);
      }
      return new androidx.media3.session.IMediaController.Stub.Proxy(obj);
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
        case TRANSACTION_onConnected:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onConnected(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onSessionResult:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onSessionResult(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onLibraryResult:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onLibraryResult(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onSetCustomLayout:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          java.util.List<android.os.Bundle> _arg1;
          _arg1 = data.createTypedArrayList(android.os.Bundle.CREATOR);
          this.onSetCustomLayout(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onCustomCommand:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.onCustomCommand(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onDisconnected:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          this.onDisconnected(_arg0);
          return true;
        }
        case TRANSACTION_onPlayerInfoChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          boolean _arg2;
          _arg2 = (0!=data.readInt());
          this.onPlayerInfoChanged(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onPlayerInfoChangedWithExclusions:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.onPlayerInfoChangedWithExclusions(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onPeriodicSessionPositionInfoChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onPeriodicSessionPositionInfoChanged(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onAvailableCommandsChangedFromPlayer:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onAvailableCommandsChangedFromPlayer(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onAvailableCommandsChangedFromSession:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          android.os.Bundle _arg2;
          if ((0!=data.readInt())) {
            _arg2 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg2 = null;
          }
          this.onAvailableCommandsChangedFromSession(_arg0, _arg1, _arg2);
          return true;
        }
        case TRANSACTION_onRenderedFirstFrame:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          this.onRenderedFirstFrame(_arg0);
          return true;
        }
        case TRANSACTION_onExtrasChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.os.Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onExtrasChanged(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onSessionActivityChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          android.app.PendingIntent _arg1;
          if ((0!=data.readInt())) {
            _arg1 = android.app.PendingIntent.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.onSessionActivityChanged(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_onChildrenChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _arg2;
          _arg2 = data.readInt();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.onChildrenChanged(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_onSearchResultChanged:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _arg2;
          _arg2 = data.readInt();
          android.os.Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = android.os.Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.onSearchResultChanged(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements androidx.media3.session.IMediaController
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

      @Override public void onConnected(int seq, android.os.Bundle connectionResult) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((connectionResult!=null)) {
            _data.writeInt(1);
            connectionResult.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onConnected, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onConnected(seq, connectionResult);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onSessionResult(int seq, android.os.Bundle sessionResult) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((sessionResult!=null)) {
            _data.writeInt(1);
            sessionResult.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onSessionResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onSessionResult(seq, sessionResult);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onLibraryResult(int seq, android.os.Bundle libraryResult) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((libraryResult!=null)) {
            _data.writeInt(1);
            libraryResult.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onLibraryResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onLibraryResult(seq, libraryResult);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onSetCustomLayout(int seq, java.util.List<android.os.Bundle> commandButtonList) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          _data.writeTypedList(commandButtonList);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onSetCustomLayout, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onSetCustomLayout(seq, commandButtonList);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onCustomCommand(int seq, android.os.Bundle command, android.os.Bundle args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((command!=null)) {
            _data.writeInt(1);
            command.writeToParcel(_data, 0);
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
            getDefaultImpl().onCustomCommand(seq, command, args);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onDisconnected(int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onDisconnected, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onDisconnected(seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      /** Deprecated: Use onPlayerInfoChangedWithExclusions from MediaControllerStub#VERSION_INT=2. */
      @Override public void onPlayerInfoChanged(int seq, android.os.Bundle playerInfoBundle, boolean isTimelineExcluded) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((playerInfoBundle!=null)) {
            _data.writeInt(1);
            playerInfoBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          _data.writeInt(((isTimelineExcluded)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPlayerInfoChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onPlayerInfoChanged(seq, playerInfoBundle, isTimelineExcluded);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      /** Introduced to deprecate onPlayerInfoChanged (from MediaControllerStub#VERSION_INT=2). */
      @Override public void onPlayerInfoChangedWithExclusions(int seq, android.os.Bundle playerInfoBundle, android.os.Bundle playerInfoExclusions) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((playerInfoBundle!=null)) {
            _data.writeInt(1);
            playerInfoBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          if ((playerInfoExclusions!=null)) {
            _data.writeInt(1);
            playerInfoExclusions.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPlayerInfoChangedWithExclusions, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onPlayerInfoChangedWithExclusions(seq, playerInfoBundle, playerInfoExclusions);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onPeriodicSessionPositionInfoChanged(int seq, android.os.Bundle sessionPositionInfo) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((sessionPositionInfo!=null)) {
            _data.writeInt(1);
            sessionPositionInfo.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPeriodicSessionPositionInfoChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onPeriodicSessionPositionInfoChanged(seq, sessionPositionInfo);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onAvailableCommandsChangedFromPlayer(int seq, android.os.Bundle commandsBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((commandsBundle!=null)) {
            _data.writeInt(1);
            commandsBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onAvailableCommandsChangedFromPlayer, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onAvailableCommandsChangedFromPlayer(seq, commandsBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onAvailableCommandsChangedFromSession(int seq, android.os.Bundle sessionCommandsBundle, android.os.Bundle playerCommandsBundle) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((sessionCommandsBundle!=null)) {
            _data.writeInt(1);
            sessionCommandsBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          if ((playerCommandsBundle!=null)) {
            _data.writeInt(1);
            playerCommandsBundle.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onAvailableCommandsChangedFromSession, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onAvailableCommandsChangedFromSession(seq, sessionCommandsBundle, playerCommandsBundle);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onRenderedFirstFrame(int seq) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onRenderedFirstFrame, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onRenderedFirstFrame(seq);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onExtrasChanged(int seq, android.os.Bundle extras) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((extras!=null)) {
            _data.writeInt(1);
            extras.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onExtrasChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onExtrasChanged(seq, extras);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onSessionActivityChanged(int seq, android.app.PendingIntent pendingIntent) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          if ((pendingIntent!=null)) {
            _data.writeInt(1);
            pendingIntent.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onSessionActivityChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onSessionActivityChanged(seq, pendingIntent);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      // Next Id for MediaController: 3014

      @Override public void onChildrenChanged(int seq, java.lang.String parentId, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          _data.writeString(parentId);
          _data.writeInt(itemCount);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onChildrenChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onChildrenChanged(seq, parentId, itemCount, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onSearchResultChanged(int seq, java.lang.String query, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(seq);
          _data.writeString(query);
          _data.writeInt(itemCount);
          if ((libraryParams!=null)) {
            _data.writeInt(1);
            libraryParams.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_onSearchResultChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().onSearchResultChanged(seq, query, itemCount, libraryParams);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      public static androidx.media3.session.IMediaController sDefaultImpl;
    }
    static final int TRANSACTION_onConnected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3000);
    static final int TRANSACTION_onSessionResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3001);
    static final int TRANSACTION_onLibraryResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3002);
    static final int TRANSACTION_onSetCustomLayout = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3003);
    static final int TRANSACTION_onCustomCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3004);
    static final int TRANSACTION_onDisconnected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3005);
    static final int TRANSACTION_onPlayerInfoChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3006);
    static final int TRANSACTION_onPlayerInfoChangedWithExclusions = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3012);
    static final int TRANSACTION_onPeriodicSessionPositionInfoChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3007);
    static final int TRANSACTION_onAvailableCommandsChangedFromPlayer = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3008);
    static final int TRANSACTION_onAvailableCommandsChangedFromSession = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3009);
    static final int TRANSACTION_onRenderedFirstFrame = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3010);
    static final int TRANSACTION_onExtrasChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3011);
    static final int TRANSACTION_onSessionActivityChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3013);
    static final int TRANSACTION_onChildrenChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4000);
    static final int TRANSACTION_onSearchResultChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4001);
    public static boolean setDefaultImpl(androidx.media3.session.IMediaController impl) {
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
    public static androidx.media3.session.IMediaController getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  // Id < 3000 is reserved to avoid potential collision with media2 1.x.

  public void onConnected(int seq, android.os.Bundle connectionResult) throws android.os.RemoteException;
  public void onSessionResult(int seq, android.os.Bundle sessionResult) throws android.os.RemoteException;
  public void onLibraryResult(int seq, android.os.Bundle libraryResult) throws android.os.RemoteException;
  public void onSetCustomLayout(int seq, java.util.List<android.os.Bundle> commandButtonList) throws android.os.RemoteException;
  public void onCustomCommand(int seq, android.os.Bundle command, android.os.Bundle args) throws android.os.RemoteException;
  public void onDisconnected(int seq) throws android.os.RemoteException;
  /** Deprecated: Use onPlayerInfoChangedWithExclusions from MediaControllerStub#VERSION_INT=2. */
  public void onPlayerInfoChanged(int seq, android.os.Bundle playerInfoBundle, boolean isTimelineExcluded) throws android.os.RemoteException;
  /** Introduced to deprecate onPlayerInfoChanged (from MediaControllerStub#VERSION_INT=2). */
  public void onPlayerInfoChangedWithExclusions(int seq, android.os.Bundle playerInfoBundle, android.os.Bundle playerInfoExclusions) throws android.os.RemoteException;
  public void onPeriodicSessionPositionInfoChanged(int seq, android.os.Bundle sessionPositionInfo) throws android.os.RemoteException;
  public void onAvailableCommandsChangedFromPlayer(int seq, android.os.Bundle commandsBundle) throws android.os.RemoteException;
  public void onAvailableCommandsChangedFromSession(int seq, android.os.Bundle sessionCommandsBundle, android.os.Bundle playerCommandsBundle) throws android.os.RemoteException;
  public void onRenderedFirstFrame(int seq) throws android.os.RemoteException;
  public void onExtrasChanged(int seq, android.os.Bundle extras) throws android.os.RemoteException;
  public void onSessionActivityChanged(int seq, android.app.PendingIntent pendingIntent) throws android.os.RemoteException;
  // Next Id for MediaController: 3014

  public void onChildrenChanged(int seq, java.lang.String parentId, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException;
  public void onSearchResultChanged(int seq, java.lang.String query, int itemCount, android.os.Bundle libraryParams) throws android.os.RemoteException;
}
