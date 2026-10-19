package watch

import "testing"

func TestTruthy(t *testing.T) {
	for _, tc := range []struct {
		expr, data string
		want       bool
	}{
		{".confirmed", `{"confirmed":true}`, true},
		{".confirmed", `{"confirmed":false}`, false},
		{".missing", `{}`, false},
		{".n", `{"n":0}`, true},
		{".s", `{"s":""}`, true},
		{".a", `{"a":[]}`, true},
		{`.params.outcome == "ShowError"`, `{"params":{"outcome":"ShowError"}}`, true},
		{"empty", `{}`, false},
	} {
		u, err := Compile(tc.expr)
		if err != nil {
			t.Fatal(err)
		}
		got, err := u.Match([]byte(tc.data))
		if err != nil || got != tc.want {
			t.Errorf("%s on %s: got %v, %v", tc.expr, tc.data, got, err)
		}
	}
}

func TestCompileError(t *testing.T) {
	if _, err := Compile(".a ==="); err == nil {
		t.Fatal("expected a compile error")
	}
	if _, err := Compile("$undefined"); err == nil {
		t.Fatal("expected an undefined variable error")
	}
}

func TestRuntimeErrorIsNoMatch(t *testing.T) {
	u, _ := Compile(".a.b")
	if ok, err := u.Match([]byte(`{"a":1}`)); ok || err == nil {
		t.Fatalf("got %v, %v", ok, err)
	}
}
