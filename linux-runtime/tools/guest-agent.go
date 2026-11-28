package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const control = "/run/andmx"
const poll = 35 * time.Millisecond

type request struct {
	Argv      []string
	Directory string
	Env       map[string]string
	Merge     bool
	Rows      uint16
	Cols      uint16
}

type windowSize struct {
	rows, cols, x, y uint16
}

func main() {
	if err := os.WriteFile(filepath.Join(control, "ready"), nil, 0600); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	active := make(map[string]bool)
	done := make(chan string)
	ticker := time.NewTicker(poll)
	defer ticker.Stop()
	for {
		select {
		case id := <-done:
			delete(active, id)
		case <-ticker.C:
			if exists(filepath.Join(control, "shutdown")) && len(active) == 0 {
				return
			}
			entries, _ := os.ReadDir(control)
			for _, entry := range entries {
				id := entry.Name()
				dir := filepath.Join(control, id)
				if !entry.IsDir() || len(id) != 32 || active[id] || !exists(filepath.Join(dir, "ready")) || exists(filepath.Join(dir, "status")) {
					continue
				}
				active[id] = true
				go func() {
					run(dir)
					done <- id
				}()
			}
		}
	}
}

func run(dir string) {
	status := 125
	defer func() {
		if err := os.WriteFile(filepath.Join(dir, "status.tmp"), []byte(strconv.Itoa(status)), 0600); err == nil {
			_ = os.Rename(filepath.Join(dir, "status.tmp"), filepath.Join(dir, "status"))
		}
	}()
	if signalled(filepath.Join(dir, "cancel")) {
		status = 137
		return
	}
	errout, err := os.OpenFile(filepath.Join(dir, "stderr"), os.O_WRONLY, 0)
	if err != nil {
		return
	}
	defer errout.Close()
	out, err := os.OpenFile(filepath.Join(dir, "stdout"), os.O_WRONLY, 0)
	if err != nil {
		fmt.Fprintln(errout, err)
		return
	}
	defer out.Close()
	raw, err := os.ReadFile(filepath.Join(dir, "request"))
	if err != nil {
		fmt.Fprintln(errout, err)
		return
	}
	var req request
	if err := json.Unmarshal(raw, &req); err != nil || len(req.Argv) == 0 {
		fmt.Fprintln(errout, "Invalid Alpine command")
		return
	}
	stderr := errout
	if req.Merge || req.Rows > 0 {
		stderr = out
	}
	cmd := exec.Command(req.Argv[0], req.Argv[1:]...)
	cmd.Dir = req.Directory
	if cmd.Dir == "" {
		cmd.Dir = "/root"
	}
	cmd.Env = append(os.Environ(), envEntries(req.Env)...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	var stdin io.WriteCloser
	var master *os.File
	var slave *os.File
	if req.Rows > 0 {
		master, slave, err = openTerminal(req.Rows, req.Cols)
		if err != nil {
			fmt.Fprintln(stderr, err)
			return
		}
		defer master.Close()
		defer slave.Close()
		cmd.Stdin, cmd.Stdout, cmd.Stderr = slave, slave, slave
		cmd.SysProcAttr.Setctty = true
		stdin = master
	} else {
		cmd.Stdout, cmd.Stderr = out, stderr
		stdin, err = cmd.StdinPipe()
		if err != nil {
			fmt.Fprintln(stderr, err)
			return
		}
		defer stdin.Close()
	}
	if err := cmd.Start(); err != nil {
		fmt.Fprintln(stderr, err)
		status = 127
		return
	}
	var drained chan struct{}
	if master != nil {
		_ = slave.Close()
		drained = make(chan struct{})
		go func() {
			_, _ = io.Copy(out, master)
			close(drained)
		}()
	}
	finished := make(chan struct{})
	defer close(finished)
	go sendInput(dir, stdin, master != nil, finished)
	go watch(dir, cmd.Process.Pid, master, finished)
	err = cmd.Wait()
	_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	if drained != nil {
		select {
		case <-drained:
		case <-time.After(300 * time.Millisecond):
			_ = master.Close()
			<-drained
		}
	}
	status = cmd.ProcessState.ExitCode()
	if status < 0 {
		status = 137
	}
}

func sendInput(dir string, writer io.WriteCloser, terminal bool, finished <-chan struct{}) {
	input, err := os.Open(filepath.Join(dir, "stdin"))
	if err != nil {
		if !terminal {
			_ = writer.Close()
		}
		return
	}
	defer input.Close()
	if !terminal {
		defer writer.Close()
	}
	for {
		if _, err := io.Copy(writer, input); err != nil {
			return
		}
		if signalled(filepath.Join(dir, "stdin.closed")) {
			_, _ = io.Copy(writer, input)
			return
		}
		select {
		case <-finished:
			return
		case <-time.After(poll):
		}
	}
}

func watch(dir string, pid int, master *os.File, finished <-chan struct{}) {
	ticker := time.NewTicker(poll)
	defer ticker.Stop()
	var last string
	for {
		select {
		case <-finished:
			return
		case <-ticker.C:
			if signalled(filepath.Join(dir, "cancel")) {
				_ = syscall.Kill(-pid, syscall.SIGKILL)
				return
			}
			if master == nil {
				continue
			}
			if data, err := os.ReadFile(filepath.Join(dir, "size")); err == nil && string(data) != last {
				size := windowSize{}
				if _, err := fmt.Sscan(string(data), &size.rows, &size.cols); err == nil {
					_ = ioctl(master, syscall.TIOCSWINSZ, unsafe.Pointer(&size))
				}
				last = string(data)
			}
		}
	}
}

func openTerminal(rows, cols uint16) (*os.File, *os.File, error) {
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, nil, err
	}
	var unlock, number int32
	size := windowSize{rows: rows, cols: cols}
	for _, op := range []struct {
		code uintptr
		arg  unsafe.Pointer
	}{{syscall.TIOCSPTLCK, unsafe.Pointer(&unlock)}, {syscall.TIOCGPTN, unsafe.Pointer(&number)}, {syscall.TIOCSWINSZ, unsafe.Pointer(&size)}} {
		if err := ioctl(master, op.code, op.arg); err != nil {
			master.Close()
			return nil, nil, err
		}
	}
	slave, err := os.OpenFile(fmt.Sprintf("/dev/pts/%d", number), os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		master.Close()
		return nil, nil, err
	}
	return master, slave, nil
}

func ioctl(file *os.File, op uintptr, arg unsafe.Pointer) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, file.Fd(), op, uintptr(arg))
	if errno != 0 {
		return errno
	}
	return nil
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func signalled(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() > 0
}

func envEntries(values map[string]string) []string {
	entries := make([]string, 0, len(values))
	for name, value := range values {
		if name != "" && !strings.ContainsAny(name, "=\x00") && !strings.ContainsRune(value, '\x00') {
			entries = append(entries, name+"="+value)
		}
	}
	return entries
}
