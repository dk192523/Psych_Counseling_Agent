package com.dk.psychclient;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.time.Duration;

/** An unnamed, non-inheritable Windows job owned exclusively by the Server JVM. */
final class WindowsWorkerJob implements WorkerSidecar.ProcessJob {
    static final int KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int EXTENDED_LIMIT_INFORMATION = 9;
    private static final int BASIC_ACCOUNTING_INFORMATION = 1;
    private static final int PROCESS_TERMINATE_AND_SET_QUOTA = 0x0001 | 0x0100;
    private final Api api;
    private HANDLE handle;

    // Kept separate from class initialization so Linux fake-process tests never load kernel32.
    private static final class NativeApi {
        private static final Api INSTANCE = Native.load("kernel32", Api.class);
    }

    static WindowsWorkerJob create() throws IOException {
        if (!Platform.isWindows()) throw new IOException("桌面 Worker 需要 Windows 进程作业约束。");
        return create(NativeApi.INSTANCE);
    }

    static WindowsWorkerJob create(Api api) throws IOException {
        HANDLE handle = api.CreateJobObjectW(null, null);
        if (invalid(handle)) throw error(api, "创建 Worker 作业");
        WindowsWorkerJob job = new WindowsWorkerJob(api, handle);
        try {
            ExtendedLimitInformation limits = new ExtendedLimitInformation();
            limits.basicLimitInformation.limitFlags = KILL_ON_JOB_CLOSE;
            limits.write();
            if (!api.SetInformationJobObject(handle, EXTENDED_LIMIT_INFORMATION,
                    limits.getPointer(), limits.size())) throw error(api, "设置 Worker 作业限制");
            return job;
        } catch (IOException | RuntimeException | Error failure) {
            // No process has been launched or assigned yet.
            if (!api.CloseHandle(handle)) failure.addSuppressed(error(api, "释放未启用的 Worker 作业"));
            throw failure;
        }
    }

    private WindowsWorkerJob(Api api, HANDLE handle) {
        this.api = api;
        this.handle = handle;
    }

    @Override public void assign(Process child) throws IOException {
        long pid = child.pid();
        if (pid <= 0 || pid > 0xffff_ffffL) throw new IOException("Worker 进程编号无效。");
        HANDLE process = api.OpenProcess(PROCESS_TERMINATE_AND_SET_QUOTA, false, (int) pid);
        if (invalid(process)) throw error(api, "打开 Worker 进程");
        IOException failure = null;
        try {
            if (!api.AssignProcessToJobObject(handle, process)) failure = error(api, "约束 Worker 进程树");
        } finally {
            if (!api.CloseHandle(process)) {
                IOException closeFailure = error(api, "释放 Worker 进程句柄");
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    @Override public void terminateAndConfirm(Duration timeout) throws IOException {
        if (handle == null) return;
        if (activeProcesses() == 0) return;
        if (!api.TerminateJobObject(handle, 1)) throw error(api, "终止 Worker 进程树");
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (activeProcesses() != 0) {
                if (System.nanoTime() >= deadline) throw new IOException("Worker 进程树未确认关闭，已保留作业句柄和运行锁。");
                try { Thread.sleep(10); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() throws IOException {
        if (handle == null) return;
        if (activeProcesses() != 0) throw new IOException("Worker 作业仍有进程，不能释放运行锁。");
        if (!api.CloseHandle(handle)) throw error(api, "释放 Worker 作业");
        handle = null;
    }

    private int activeProcesses() throws IOException {
        BasicAccountingInformation accounting = new BasicAccountingInformation();
        if (!api.QueryInformationJobObject(handle, BASIC_ACCOUNTING_INFORMATION,
                accounting.getPointer(), accounting.size(), null)) throw error(api, "确认 Worker 进程树退出");
        accounting.read();
        return accounting.activeProcesses;
    }

    private static boolean invalid(HANDLE handle) {
        return handle == null || handle.getPointer() == null
                || Pointer.nativeValue(handle.getPointer()) == -1;
    }

    private static IOException error(Api api, String operation) {
        return new IOException(operation + "失败（Windows 错误码 " + api.GetLastError() + "）。");
    }

    interface Api extends StdCallLibrary {
        HANDLE CreateJobObjectW(Pointer attributes, WString name);
        boolean SetInformationJobObject(HANDLE job, int informationClass, Pointer information, int size);
        HANDLE OpenProcess(int access, boolean inherit, int pid);
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        boolean TerminateJobObject(HANDLE job, int exitCode);
        boolean QueryInformationJobObject(HANDLE job, int informationClass, Pointer information, int size,
                                          Pointer returnedLength);
        boolean CloseHandle(HANDLE handle);
        int GetLastError();
    }

    @Structure.FieldOrder({"perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
            "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit", "affinity",
            "priorityClass", "schedulingClass"})
    public static class BasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public SIZE_T minimumWorkingSetSize = new SIZE_T();
        public SIZE_T maximumWorkingSetSize = new SIZE_T();
        public int activeProcessLimit;
        public SIZE_T affinity = new SIZE_T();
        public int priorityClass;
        public int schedulingClass;
    }

    @Structure.FieldOrder({"readOperationCount", "writeOperationCount", "otherOperationCount",
            "readTransferCount", "writeTransferCount", "otherTransferCount"})
    public static class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;
    }

    @Structure.FieldOrder({"basicLimitInformation", "ioInfo", "processMemoryLimit", "jobMemoryLimit",
            "peakProcessMemoryUsed", "peakJobMemoryUsed"})
    public static class ExtendedLimitInformation extends Structure {
        public BasicLimitInformation basicLimitInformation = new BasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public SIZE_T processMemoryLimit = new SIZE_T();
        public SIZE_T jobMemoryLimit = new SIZE_T();
        public SIZE_T peakProcessMemoryUsed = new SIZE_T();
        public SIZE_T peakJobMemoryUsed = new SIZE_T();
    }

    @Structure.FieldOrder({"totalUserTime", "totalKernelTime", "thisPeriodTotalUserTime",
            "thisPeriodTotalKernelTime", "totalPageFaultCount", "totalProcesses", "activeProcesses",
            "totalTerminatedProcesses"})
    public static class BasicAccountingInformation extends Structure {
        public long totalUserTime;
        public long totalKernelTime;
        public long thisPeriodTotalUserTime;
        public long thisPeriodTotalKernelTime;
        public int totalPageFaultCount;
        public int totalProcesses;
        public int activeProcesses;
        public int totalTerminatedProcesses;
    }
}
