// Package adb wraps the adb commands the CLI needs. Every invocation goes
// through Runner so tests can substitute a fake.
package adb

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Runner executes one adb invocation and returns its stdout.
type Runner interface {
	Run(ctx context.Context, args ...string) ([]byte, error)
}

// Exec runs the adb binary at Path.
type Exec struct{ Path string }

func (e Exec) Run(ctx context.Context, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, e.Path, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = strings.TrimSpace(string(out))
		}
		return out, fmt.Errorf("adb %s: %v: %s", strings.Join(args, " "), err, msg)
	}
	return out, nil
}

// SocketSuffix ends every probe socket name: "<package>.probe".
const SocketSuffix = ".probe"

type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
}

type Forward struct {
	Serial string
	Local  string
	Remote string
}

type Client struct{ Runner Runner }

func (c Client) Devices(ctx context.Context) ([]Device, error) {
	out, err := c.Runner.Run(ctx, "devices")
	if err != nil {
		return nil, err
	}
	return ParseDevices(string(out)), nil
}

// ProbePackages lists packages that currently listen on a probe socket.
func (c Client) ProbePackages(ctx context.Context, serial string) ([]string, error) {
	out, err := c.Runner.Run(ctx, "-s", serial, "shell", "cat", "/proc/net/unix")
	if err != nil {
		return nil, err
	}
	return ParseProcNetUnix(string(out)), nil
}

// Forward maps a free host port to the app's abstract socket and returns the port.
func (c Client) Forward(ctx context.Context, serial, pkg string) (int, error) {
	out, err := c.Runner.Run(ctx, "-s", serial, "forward", "tcp:0", "localabstract:"+pkg+SocketSuffix)
	if err != nil {
		return 0, err
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(out)))
	if err != nil || port <= 0 {
		return 0, fmt.Errorf("adb forward: unexpected output %q", strings.TrimSpace(string(out)))
	}
	return port, nil
}

func (c Client) RemoveForward(ctx context.Context, serial string, port int) error {
	_, err := c.Runner.Run(ctx, "-s", serial, "forward", "--remove", "tcp:"+strconv.Itoa(port))
	return err
}

func (c Client) Forwards(ctx context.Context) ([]Forward, error) {
	out, err := c.Runner.Run(ctx, "forward", "--list")
	if err != nil {
		return nil, err
	}
	var fs []Forward
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) == 3 {
			fs = append(fs, Forward{Serial: f[0], Local: f[1], Remote: f[2]})
		}
	}
	return fs, nil
}

// ParseDevices parses `adb devices` output.
func ParseDevices(out string) []Device {
	var ds []Device
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of devices") || strings.HasPrefix(line, "*") {
			continue
		}
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		ds = append(ds, Device{Serial: f[0], State: f[1]})
	}
	return ds
}

// ParseProcNetUnix returns the sorted, de-duplicated packages whose abstract
// socket path is "@<package>.probe".
func ParseProcNetUnix(out string) []string {
	seen := map[string]bool{}
	var pkgs []string
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		path := f[len(f)-1]
		if !strings.HasPrefix(path, "@") || !strings.HasSuffix(path, SocketSuffix) {
			continue
		}
		pkg := strings.TrimSuffix(strings.TrimPrefix(path, "@"), SocketSuffix)
		if pkg == "" || seen[pkg] {
			continue
		}
		seen[pkg] = true
		pkgs = append(pkgs, pkg)
	}
	sort.Strings(pkgs)
	return pkgs
}

var tlsSerial = regexp.MustCompile(`^adb-(.+)-[^-]+\._adb-tls-connect\._tcp\.?$`)

// SameDevicePairs reports serials that name one physical device twice: once
// over USB and once over wireless debugging (mDNS "adb-<serial>-xxxx._adb-tls-connect._tcp").
func SameDevicePairs(serials []string) [][2]string {
	present := map[string]bool{}
	for _, s := range serials {
		present[s] = true
	}
	var pairs [][2]string
	for _, s := range serials {
		if m := tlsSerial.FindStringSubmatch(s); m != nil && present[m[1]] {
			pairs = append(pairs, [2]string{m[1], s})
		}
	}
	return pairs
}
