package org.andmx.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class LinuxRuntime {
    private static final String ASSETS = "linux-runtime/";
    private static final long DISK_SIZE = 512L * 1024 * 1024;
    private final Context context;
    private final File directory;
    private final File launcher;
    private final File kernel;
    private final File initramfs;
    private final File disk;

    public interface Progress {
        void update(String stage);
    }

    public LinuxRuntime(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "linux-runtime/alpine-3.24.4");
        launcher = new File(this.context.getApplicationInfo().nativeLibraryDir, "liblinux_runtime.so");
        kernel = new File(directory, "vmlinuz");
        initramfs = new File(directory, "initramfs");
        disk = new File(directory, "alpine.ext4");
    }

    public boolean isAvailable() {
        return Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") && launcher.canExecute();
    }

    public void prepare(Progress progress) throws IOException {
        if (!isAvailable()) {
            throw new IOException("The ARM64 Linux engine is unavailable");
        }
        try (DiskGuard guard = lockDisk()) {
            prepareKeymap();
            File version = new File(directory, "boot.version");
            String bundled;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(ASSETS + "boot.version"), StandardCharsets.US_ASCII))) {
                bundled = reader.readLine();
            }
            if (bundled == null || bundled.length() != 64) throw new IOException("Invalid Alpine boot version");
            boolean current = version.isFile() && bundled.equals(
                new String(Files.readAllBytes(version.toPath()), StandardCharsets.US_ASCII));
            if (current && kernel.isFile() && initramfs.isFile() && disk.length() == DISK_SIZE) return;
            progress.update("Preparing Alpine from the app");
            copyBootAsset("vmlinuz", kernel);
            copyBootAsset("initramfs", initramfs);
            if (disk.length() != DISK_SIZE) createDisk();
            File partialVersion = new File(directory, "boot.version.partial");
            Files.write(partialVersion.toPath(), bundled.getBytes(StandardCharsets.US_ASCII));
            replace(partialVersion, version);
            progress.update("Alpine is ready to boot");
        }
    }

    public Console openConsole(boolean allowNetwork, File control, File home, File workspace) throws IOException {
        DiskGuard guard = lockDisk();
        try {
            if (!kernel.isFile() || !initramfs.isFile() || disk.length() != DISK_SIZE) {
                throw new IOException("Prepare Alpine before opening a console");
            }
            List<String> args = new ArrayList<>();
            String boot = "console=ttyAMA0 quiet loglevel=3 panic=5";
            if (workspace != null) boot += " runtime.workspace=1";
            if (allowNetwork) {
                boot += " runtime.dns=" + dnsServer();
                String mirror = mirrorAddress();
                if (mirror != null) boot += " runtime.mirror=" + mirror;
            }
            Collections.addAll(args, launcher.getAbsolutePath(), "-M", "virt", "-accel", "tcg",
                "-cpu", "cortex-a57", "-smp", "2", "-m", "384", "-display", "none",
                "-L", directory.getAbsolutePath(), "-nodefaults", "-monitor", "none", "-serial", "stdio", "-no-reboot",
                "-kernel", kernel.getAbsolutePath(), "-initrd", initramfs.getAbsolutePath(),
                "-append", boot,
                "-drive", "file=" + disk.getAbsolutePath() + ",if=none,id=alpine,format=raw,cache=writethrough",
                "-device", "virtio-blk-device,drive=alpine");
            if (allowNetwork) {
                Collections.addAll(args, "-netdev", "user,id=alpine-net,net=192.168.76.0/24",
                    "-device", "virtio-net-device,netdev=alpine-net");
            } else {
                Collections.addAll(args, "-nic", "none");
            }
            share(args, "control", control);
            share(args, "home", home);
            if (workspace != null) share(args, "workspace", workspace);
            ProcessBuilder builder = new ProcessBuilder(args).directory(directory).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().put("LD_LIBRARY_PATH", launcher.getParent());
            return new Console(builder.start(), guard);
        } catch (IOException | RuntimeException failure) {
            guard.close();
            throw failure;
        }
    }

    private String dnsServer() {
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        LinkProperties link = network == null ? null : network.getLinkProperties(network.getActiveNetwork());
        if (link != null) {
            for (InetAddress address : link.getDnsServers()) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        }
        return "192.168.76.3";
    }

    private static String mirrorAddress() {
        try {
            for (InetAddress address : InetAddress.getAllByName("dl-cdn.alpinelinux.org")) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private DiskGuard lockDisk() throws IOException {
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create the Linux environment directory");
        }
        RandomAccessFile file = new RandomAccessFile(new File(directory, "console.lock"), "rw");
        try {
            FileLock lock = file.getChannel().tryLock();
            if (lock != null) return new DiskGuard(file, lock);
        } catch (OverlappingFileLockException ignored) {
        } catch (IOException | RuntimeException failure) {
            file.close();
            throw failure;
        }
        file.close();
        throw new IOException("Close the active Alpine session before starting another one");
    }

    private static void share(List<String> args, String name, File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("Unavailable Alpine share: " + directory);
        String path = directory.getCanonicalPath().replace(",", ",,");
        Collections.addAll(args, "-fsdev", "local,id=" + name + ",path=" + path + ",security_model=none",
            "-device", "virtio-9p-device,fsdev=" + name + ",mount_tag=andmx_" + name);
    }

    private void prepareKeymap() throws IOException {
        File keymaps = new File(directory, "keymaps");
        Files.createDirectories(keymaps.toPath());
        File keymap = new File(keymaps, "en-us");
        if (keymap.isFile()) return;
        File partial = new File(keymaps, "en-us.partial");
        try (InputStream input = context.getAssets().open(ASSETS + "keymaps/en-us");
             OutputStream output = new FileOutputStream(partial)) {
            copy(input, output, 128 * 1024);
        }
        replace(partial, keymap);
    }

    private void copyBootAsset(String name, File target) throws IOException {
        File partial = new File(directory, name + ".partial");
        try (InputStream input = context.getAssets().open(ASSETS + name);
             OutputStream output = new FileOutputStream(partial)) {
            copy(input, output, 32L * 1024 * 1024);
        }
        replace(partial, target);
    }

    private void createDisk() throws IOException {
        File partial = new File(directory, "alpine.ext4.partial");
        try (InputStream input = new GZIPInputStream(context.getAssets().open(ASSETS + "disk.template"));
             RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
            output.setLength(0);
            byte[] bytes = new byte[64 * 1024];
            long position = 0;
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (position + count > DISK_SIZE) throw new IOException("Invalid Alpine disk size");
                boolean zero = true;
                for (int index = 0; index < count && zero; index++) zero = bytes[index] == 0;
                if (zero) output.seek(position + count);
                else output.write(bytes, 0, count);
                position += count;
            }
            if (position != DISK_SIZE) throw new IOException("Incomplete Alpine disk image");
            output.setLength(DISK_SIZE);
            output.getFD().sync();
        }
        replace(partial, disk);
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] bytes = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(bytes)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Alpine artifact exceeds its size limit");
            output.write(bytes, 0, count);
        }
        return total;
    }

    private static void replace(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static final class Console implements AutoCloseable {
        private final Process process;
        private final DiskGuard guard;

        private Console(Process process, DiskGuard guard) {
            this.process = process;
            this.guard = guard;
        }

        public InputStream input() { return process.getInputStream(); }

        public boolean isAlive() { return process.isAlive(); }

        public void stop() { process.destroy(); }

        public int waitFor() throws InterruptedException { return process.waitFor(); }

        @Override
        public synchronized void close() throws IOException {
            boolean interrupted = false;
            try {
                if (process.isAlive()) {
                    stop();
                    try {
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                        process.destroyForcibly();
                    }
                }
                while (process.isAlive()) {
                    try {
                        process.waitFor();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            } finally {
                try {
                    guard.close();
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class DiskGuard implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;

        private DiskGuard(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) lock.release();
            } finally {
                file.close();
            }
        }
    }
}
