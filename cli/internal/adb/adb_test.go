package adb

import (
	"reflect"
	"testing"
)

func TestParseProcNetUnix(t *testing.T) {
	out := `Num       RefCount Protocol Flags    Type St Inode Path
0000000000000000: 00000002 00000000 00010000 0001 01 20141 @dev.droidprobe.sample.probe
0000000000000000: 00000003 00000000 00000000 0001 03 20150 @dev.droidprobe.sample.probe
0000000000000000: 00000002 00000000 00010000 0001 01 20142 @com.example.app.probe
0000000000000000: 00000002 00000000 00010000 0001 01 1234 /dev/socket/probe
0000000000000000: 00000002 00000000 00010000 0001 01 1235 @jdwp-control
0000000000000000: 00000003 00000000 00000000 0001 03 1236
`
	got := ParseProcNetUnix(out)
	want := []string{"com.example.app", "dev.droidprobe.sample"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v", got)
	}
	if got := ParseProcNetUnix("cat: /proc/net/unix: Permission denied\n"); len(got) != 0 {
		t.Fatalf("got %v", got)
	}
}

func TestParseDevices(t *testing.T) {
	out := "* daemon started successfully\nList of devices attached\nemulator-5554\tdevice\nR58M\tunauthorized\n\n"
	want := []Device{{"emulator-5554", "device"}, {"R58M", "unauthorized"}}
	if got := ParseDevices(out); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v", got)
	}
}

func TestSameDevicePairs(t *testing.T) {
	got := SameDevicePairs([]string{"R5CT10ABCDE", "adb-R5CT10ABCDE-7i8s2x._adb-tls-connect._tcp", "emulator-5554", "adb-OTHER-abc._adb-tls-connect._tcp"})
	want := [][2]string{{"R5CT10ABCDE", "adb-R5CT10ABCDE-7i8s2x._adb-tls-connect._tcp"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v", got)
	}
}
