package com.dk.psychclient;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the native boundary with a fake API; never launches an executable or loads kernel32. */
class WindowsWorkerJobTest {
    @Test void configuresKillOnCloseWithoutInheritanceAndClosesTemporaryProcessHandle() throws Exception {
        FakeApi api = new FakeApi();
        WindowsWorkerJob job = WindowsWorkerJob.create(api);
        assertEquals(WindowsWorkerJob.KILL_ON_JOB_CLOSE, api.limitFlags);
        job.assign(new WorkerSidecarTest.FakeProcess(42));
        assertEquals(42, api.pid);
        assertEquals(0x0101, api.access);
        assertEquals(List.of("create", "configure", "open", "assign", "close-process"), api.events);
        job.terminateAndConfirm(Duration.ZERO);
        job.close();
        job.close();
        assertEquals(1, api.events.stream().filter("close-job"::equals).count());
    }

    @Test void failedConfigurationClosesJobBeforeAnyProcessCanBeStarted() {
        FakeApi api = new FakeApi();
        api.configure = false;
        IOException failure = assertThrows(IOException.class, () -> WindowsWorkerJob.create(api));
        assertTrue(failure.getMessage().contains("5"));
        assertEquals(List.of("create", "configure", "close-job"), api.events);
    }

    @Test void failedAssignmentClosesOnlyTemporaryProcessHandleUntilOwnerConfirmsCleanup() throws Exception {
        FakeApi api = new FakeApi();
        api.assign = false;
        WindowsWorkerJob job = WindowsWorkerJob.create(api);
        assertThrows(IOException.class, () -> job.assign(new WorkerSidecarTest.FakeProcess(42)));
        assertTrue(api.events.contains("close-process"));
        assertFalse(api.events.contains("close-job"));
        job.terminateAndConfirm(Duration.ZERO);
        job.close();
        assertEquals("close-job", api.events.getLast());
    }

    @Test void waitsForEntireJobAndRetainsHandleWhileAnyDescendantIsAlive() throws Exception {
        FakeApi api = new FakeApi();
        api.active = 2;
        api.exitOnTerminate = false;
        WindowsWorkerJob job = WindowsWorkerJob.create(api);
        assertThrows(IOException.class, () -> job.terminateAndConfirm(Duration.ZERO));
        assertThrows(IOException.class, job::close);
        assertFalse(api.events.contains("close-job"));
        api.exitOnTerminate = true;
        job.terminateAndConfirm(Duration.ZERO);
        job.close();
        assertEquals(0, api.active);
        assertEquals("close-job", api.events.getLast());
    }

    @Test void failedQueryTerminationOrCloseRetainsHandleForRetry() throws Exception {
        FakeApi api = new FakeApi();
        WindowsWorkerJob job = WindowsWorkerJob.create(api);
        api.query = false;
        assertThrows(IOException.class, () -> job.terminateAndConfirm(Duration.ZERO));
        assertThrows(IOException.class, job::close);
        api.query = true;
        api.active = 1;
        api.terminate = false;
        assertThrows(IOException.class, () -> job.terminateAndConfirm(Duration.ZERO));
        assertFalse(api.events.contains("close-job"));
        api.terminate = true;
        job.terminateAndConfirm(Duration.ZERO);
        api.closeJob = false;
        assertThrows(IOException.class, job::close);
        api.closeJob = true;
        job.close();
        assertEquals(2, api.events.stream().filter("close-job"::equals).count());
    }

    private static final class FakeApi implements WindowsWorkerJob.Api {
        final HANDLE job = new HANDLE(Pointer.createConstant(1));
        final HANDLE process = new HANDLE(Pointer.createConstant(2));
        final List<String> events = new ArrayList<>();
        boolean configure = true;
        boolean assign = true;
        boolean query = true;
        boolean terminate = true;
        boolean closeJob = true;
        boolean exitOnTerminate = true;
        int active;
        int limitFlags;
        int pid;
        int access;
        @Override public HANDLE CreateJobObjectW(Pointer attributes, WString name) {
            assertNull(attributes, "Default SECURITY_ATTRIBUTES must make the job non-inheritable");
            assertNull(name, "Named jobs can be reopened outside this Server");
            events.add("create");
            return job;
        }
        @Override public boolean SetInformationJobObject(HANDLE handle, int type, Pointer info, int size) {
            assertSame(job, handle);
            assertEquals(9, type);
            var limits = new WindowsWorkerJob.ExtendedLimitInformation();
            assertEquals(limits.size(), size);
            limits.getPointer().write(0, info.getByteArray(0, size), 0, size);
            limits.read();
            limitFlags = limits.basicLimitInformation.limitFlags;
            events.add("configure");
            return configure;
        }
        @Override public HANDLE OpenProcess(int access, boolean inherit, int pid) {
            assertFalse(inherit);
            this.access = access;
            this.pid = pid;
            events.add("open");
            return process;
        }
        @Override public boolean AssignProcessToJobObject(HANDLE job, HANDLE process) {
            assertSame(this.job, job);
            assertSame(this.process, process);
            events.add("assign");
            return assign;
        }
        @Override public boolean TerminateJobObject(HANDLE job, int exitCode) {
            assertSame(this.job, job);
            assertEquals(1, exitCode);
            events.add("terminate");
            if (terminate && exitOnTerminate) active = 0;
            return terminate;
        }
        @Override public boolean QueryInformationJobObject(HANDLE job, int type, Pointer info, int size,
                                                           Pointer returnedLength) {
            assertSame(this.job, job);
            assertEquals(1, type);
            var accounting = new WindowsWorkerJob.BasicAccountingInformation();
            assertEquals(accounting.size(), size);
            accounting.activeProcesses = active;
            accounting.write();
            info.write(0, accounting.getPointer().getByteArray(0, size), 0, size);
            return query;
        }
        @Override public boolean CloseHandle(HANDLE handle) {
            events.add(handle == job ? "close-job" : "close-process");
            return handle != job || closeJob;
        }
        @Override public int GetLastError() { return 5; }
    }
}
