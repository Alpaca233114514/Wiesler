# WinVerifyTrust returns a stable error code without changing trust / 不改信任库，直接读取系统验证错误码。
if (-not ('WieslerAuthenticode' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WieslerAuthenticode {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct FileInfo {
        public uint size;
        [MarshalAs(UnmanagedType.LPWStr)] public string path;
        public IntPtr handle, subject;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct TrustData {
        public uint size;
        public IntPtr policy, sip;
        public uint ui, revocation, choice;
        public IntPtr file;
        public uint stateAction;
        public IntPtr state, url;
        public uint flags, context;
        public IntPtr settings;
    }
    [DllImport("wintrust.dll", ExactSpelling = true)]
    static extern uint WinVerifyTrust(IntPtr window, ref Guid action, ref TrustData data);
    public static uint Verify(string path) {
        var file = new FileInfo {size = (uint)Marshal.SizeOf<FileInfo>(), path = path};
        var pointer = Marshal.AllocHGlobal(Marshal.SizeOf<FileInfo>());
        Marshal.StructureToPtr(file, pointer, false);
        var data = new TrustData {size = (uint)Marshal.SizeOf<TrustData>(), ui = 2, choice = 1, file = pointer, stateAction = 1};
        var action = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");
        try { return WinVerifyTrust(new IntPtr(-1), ref action, ref data); }
        finally {
            data.stateAction = 2;
            WinVerifyTrust(new IntPtr(-1), ref action, ref data);
            Marshal.DestroyStructure<FileInfo>(pointer);
            Marshal.FreeHGlobal(pointer);
        }
    }
}
'@
}
